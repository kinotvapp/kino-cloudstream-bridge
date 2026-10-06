// GPL-3.0 (this fork).
package com.kino.csbridge.prefs

import android.content.SharedPreferences
import android.util.Log
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.settings.SettingsSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Where every SharedPreferences value of CloudStream code lives: in Kino, in the owning plugin's own `kino.storage`,
 * through `IHost.storage` (spec §5.6). Nothing is ever written in the bridge's data dir. [HostPrefs] are views of one
 * SharedPreferences name over this store; all names of one plugin share that plugin's 256 KB quota.
 *
 * **Owner of a call:** the plugin of the live [CallContext]; else the plugin whose settings screen is open
 * ([SettingsSession]); else the only installed plugin; else nobody, and the value is kept in this process's memory only
 * (logged once per key, never naming it). The context is read from the thread
 * local, which follows an op's coroutines. Work a plugin starts outside its op (own scopes, raw threads, callbacks)
 * falls to the second or third rule. Extractors are global: plugin B's extractor run inside plugin A's op writes to A's
 * storage, which is expected (it is A's op).
 *
 * **Writes** go into an in-memory overlay (each entry with a sequence number) and a queue of dirty `(plugin, key)` slots
 * drained by one writer thread, so at most one Binder write is in flight and a late write can never land after a newer
 * one. Slots coalesce: the newest value is what is sent. Reads see the overlay at once (read-your-write); an entry
 * leaves the overlay when Kino acknowledges it or the write is dropped, so the overlay never holds values read from
 * Kino (no stale cache across devices). apply() never waits; commit() waits for its own writes up to one deadline per
 * commit (the smaller of [writeTimeoutMs] and the op's remaining time) and leaves them queued if it passes. A write
 * without an answer (no host, RemoteException) is tried once more, then dropped with the rest of its batch; an answered
 * refusal is final. Past [maxDirty] pending keys, a new key is dropped at once by apply() and waits for room up to its
 * deadline in commit(). The one wait apply() can make: clear() needs Kino's key list (one read bounded by the commit
 * deadline). A Binder write that has not returned after [stallMs] is logged once ("storage writer stalled"), with the
 * plugin id only. Pending writes die with the process, like Android's apply().
 *
 * **Reads** go to Kino every time unless a write is pending, each on a bounded executor and waited on at most its
 * deadline; a read without an answer is the default. Logs carry the op, the plugin id and an error code only: never a
 * key, a value or a URL. Nothing here throws into plugin code.
 */
class HostStore(
    private val installedIds: () -> List<String>,
    private val host: () -> IHost? = { HostLink.host },
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val maxDirty: Int = MAX_DIRTY,
    private val stallMs: Long = STALL_MS,
    private val log: (String) -> Unit = ::defaultLog,
    private val readExecutor: ExecutorService = sharedReadExecutor,
    private val settingsOwner: () -> String? = { SettingsSession.pluginId() },
) {
    private data class Slot(val pluginId: String, val key: String)
    private class Batch { @Volatile var failed = false; val dropLogged = AtomicBoolean() }
    /** [value] null = remove. Identity matters: an entry is cleared only if it is still the one that was sent. */
    private class Pending(val value: String?, val seq: Long, val batch: Batch, val waiters: List<CompletableFuture<Boolean>>)

    private val overlay = ConcurrentHashMap<Slot, Pending>()
    private val queue = LinkedBlockingQueue<Slot>()
    private val queued: MutableSet<Slot> = ConcurrentHashMap.newKeySet()
    private val seq = AtomicLong()
    private val room = Object()
    private val memory = ConcurrentHashMap<String, String>()
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val writer by lazy {
        Thread(::watch, "bridge-storage-watchdog").apply { isDaemon = true; start() }
        Thread(::drain, "bridge-storage-writer").apply { isDaemon = true; start() }
    }

    /** The write in flight: for which plugin and since when ([System.nanoTime]). One object, so the watchdog never pairs one write's start with another's plugin. */
    private class Sending(val pluginId: String, val since: Long)
    @Volatile private var sending: Sending? = null

    /** The plugin whose Kino storage serves this call, or null for this process's memory. */
    private fun owner(): String? {
        CallContext.current()?.pluginId?.takeIf { it.startsWith(ID_PREFIX) }?.let { return it }
        settingsOwner()?.takeIf { it.startsWith(ID_PREFIX) }?.let { return it }
        return try { installedIds().singleOrNull() } catch (_: Exception) { null }
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    /** The stored string for [key], or null when absent or Kino did not answer. */
    internal fun read(key: String): String? {
        val id = owner() ?: return memory[key]
        overlay[Slot(id, key)]?.let { return it.value }
        return get(id, key, readTimeoutMs).second
    }

    /** (answered, value). */
    private fun get(id: String, key: String, timeoutMs: Long): Pair<Boolean, String?> {
        val answer = call("get", id, op("get", id).put("key", key), timeoutMs) ?: return false to null
        CsWire.errorOf(answer)?.let { log("storage get refused: ${it.first} (plugin=$id)"); return false to null }
        val o = JSONObject(answer)
        return true to (if (o.isNull("value")) null else o.optString("value"))
    }

    /** Kino's keys [mine] accepts, merged with pending writes; null when Kino did not answer. */
    private fun kinoKeys(id: String, timeoutMs: Long, mine: (String) -> Boolean): List<String>? {
        val answer = call("keys", id, op("keys", id), timeoutMs) ?: return null
        CsWire.errorOf(answer)?.let { log("storage keys refused: ${it.first} (plugin=$id)"); return null }
        val a = JSONObject(answer).getJSONArray("keys")
        val out = LinkedHashSet<String>()
        for (i in 0 until a.length()) a.getString(i).takeIf(mine)?.let(out::add)
        for ((slot, p) in overlay) if (slot.pluginId == id && mine(slot.key)) { if (p.value == null) out -= slot.key else out += slot.key }
        return out.toList()
    }

    /** Key → raw value for every key [mine] accepts, under one deadline; a partial listing is logged once and returned. */
    internal fun all(mine: (String) -> Boolean): Map<String, String> {
        val id = owner() ?: return memory.filterKeys(mine)
        val until = System.nanoTime() + readTimeoutMs * 1_000_000
        fun left() = ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        val out = LinkedHashMap<String, String>()
        val keys = kinoKeys(id, readTimeoutMs, mine) ?: return out.also { log("storage getAll partial (plugin=$id)") }
        for (key in keys) {
            val pending = overlay[Slot(id, key)]
            if (pending != null) { pending.value?.let { out[key] = it }; continue }
            val (answered, value) = get(id, key, left())
            if (!answered) { log("storage getAll partial (plugin=$id)"); break }
            value?.let { out[key] = it }
        }
        return out
    }

    // ---- writes --------------------------------------------------------------------------------------------------

    /**
     * One editor's save: [wipe] removes every key [mine] accepts first (that needs Kino's key list, a bounded read),
     * then [changes] (key → encoded value, null = remove). [wait] is commit(); apply() never waits (except for room).
     */
    internal fun commit(wipe: Boolean, mine: (String) -> Boolean, changes: Map<String, String?>, wait: Boolean): Boolean {
        val id = owner()
        if (id == null) {
            if (wipe) memory.keys.removeIf(mine)
            for ((key, value) in changes) {
                if (value == null) { memory.remove(key); continue }
                if (warned.add(key)) log(UNATTRIBUTED)
                memory[key] = value
            }
            return true
        }
        var ok = true
        // One deadline for the whole commit, the clear's key read included.
        val budgetMs = minOf(writeTimeoutMs, CallContext.current()?.remainingMs() ?: Long.MAX_VALUE)
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        val batch = LinkedHashMap<String, String?>()
        if (wipe) {
            val left = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            val existing = kinoKeys(id, left, mine)
            if (existing == null) { ok = false; log("storage clear dropped: no key list (plugin=$id)") }
            else existing.forEach { batch[it] = null }
        }
        batch.putAll(changes)
        val b = Batch()
        val futures = ArrayList<CompletableFuture<Boolean>>()
        for ((key, value) in batch) {
            if (value != null && key.length + value.length > MAX_ENTRY_CHARS) {
                log("storage set dropped: ${CsWire.Err.TOO_LARGE} (plugin=$id)"); ok = false; continue
            }
            val slot = Slot(id, key)
            // apply() never waits for room; commit() waits up to its deadline.
            if (!overlay.containsKey(slot) && !(if (wait) awaitRoom(deadline) else overlay.size < maxDirty)) {
                log("storage set dropped: too many pending writes (plugin=$id)"); ok = false; continue
            }
            // Only commit() waits on a future: repeated applies of one key never grow its waiters.
            val f = if (wait) CompletableFuture<Boolean>() else null
            overlay.compute(slot) { _, old ->
                val carried = old?.waiters.orEmpty()
                Pending(value, seq.incrementAndGet(), b, if (f == null) carried else carried + f)
            }
            if (queued.add(slot)) queue.put(slot)
            f?.let(futures::add)
        }
        writer.isAlive // started on first use
        if (!wait) return ok
        for (f in futures) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return false
            val r = try { f.get(left, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { return false }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            if (!r) ok = false
        }
        return ok
    }

    private fun awaitRoom(deadline: Long): Boolean = synchronized(room) {
        while (overlay.size >= maxDirty) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return false
            try { room.wait(left) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        true
    }

    private fun drain() {
        while (true) {
            val slot = try { queue.take() } catch (_: InterruptedException) { continue } // the writer never stops
            queued.remove(slot)
            val p = overlay[slot] ?: continue
            val acked = try {
                if (p.batch.failed) {
                    if (p.batch.dropLogged.compareAndSet(false, true)) {
                        log("storage batch: later writes dropped after an unanswered one (plugin=${slot.pluginId})")
                    }
                    false
                } else send(slot, p)
            } catch (_: Throwable) {
                false // the writer never dies; this write is dropped like an unanswered one
            }
            runCatching { overlay.remove(slot, p) }
            p.waiters.forEach { runCatching { it.complete(acked) } }
            synchronized(room) { room.notifyAll() }
        }
    }

    /** Logs once per stalled write; the plugin id only. */
    private fun watch() {
        var logged: Sending? = null
        while (true) {
            try { Thread.sleep((stallMs / 4).coerceIn(10, 1_000)) } catch (_: InterruptedException) { continue }
            val s = sending ?: continue
            if (s !== logged && System.nanoTime() - s.since > stallMs * 1_000_000) {
                logged = s
                log("storage writer stalled > ${stallMs / 1_000} s (plugin=${s.pluginId})")
            }
        }
    }

    /** One Binder write, waited on to its real return (the only one in flight). True when Kino acknowledged it. */
    private fun send(slot: Slot, p: Pending): Boolean {
        val name = if (p.value == null) "remove" else "set"
        val body = op(name, slot.pluginId).put("key", slot.key).apply { p.value?.let { put("value", it) } }.toString()
        repeat(2) {
            val h = host()
            val answer = if (h == null) null else try {
                sending = Sending(slot.pluginId, System.nanoTime())
                h.storage(body)
            } catch (_: Exception) { null } finally { sending = null }
            if (answer != null) {
                val err = CsWire.errorOf(answer) ?: return true
                log("storage $name refused: ${err.first} (plugin=${slot.pluginId})")
                return false
            }
        }
        log("storage $name dropped after retry (plugin=${slot.pluginId})")
        p.batch.failed = true
        return false
    }

    /** Tests only: commit() futures held by pending writes. */
    internal fun waiterCount(): Int = overlay.values.sumOf { it.waiters.size }

    /** True once every pending write was acknowledged or dropped (tests, and the settings screen before it closes). */
    internal fun awaitIdle(timeoutMs: Long): Boolean {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (overlay.isNotEmpty() || queue.isNotEmpty()) {
            if (System.nanoTime() > until) return false
            Thread.sleep(5)
        }
        return true
    }

    // ---- the bounded read call -----------------------------------------------------------------------------------

    /** Kino's answer, or null (logged) when there is no host, the op is over, Kino failed or the deadline passed. */
    private fun call(name: String, id: String, body: JSONObject, timeoutMs: Long): String? {
        val h = host() ?: run { log("storage $name skipped: no host (plugin=$id)"); return null }
        val budget = minOf(timeoutMs, CallContext.current()?.remainingMs() ?: Long.MAX_VALUE)
        if (budget <= 0) { log("storage $name skipped: call over (plugin=$id)"); return null }
        val json = body.toString()
        val f = try { readExecutor.submit(Callable { h.storage(json) }) } catch (_: RejectedExecutionException) {
            purge(); log("storage $name skipped: queue full (plugin=$id)"); return null
        }
        return try {
            f.get(budget, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(false); purge(); log("storage $name timed out after $budget ms (plugin=$id)"); null
        } catch (e: ExecutionException) {
            log("storage $name failed: ${e.cause?.javaClass?.simpleName} (plugin=$id)"); null
        } catch (_: InterruptedException) {
            f.cancel(false); purge(); Thread.currentThread().interrupt(); log("storage $name interrupted (plugin=$id)"); null
        }
    }

    /** A cancelled task that never started would keep its queue slot until a (possibly stuck) worker takes it. */
    private fun purge() { (readExecutor as? ThreadPoolExecutor)?.purge() }

    private fun op(name: String, id: String) = JSONObject().put("op", name).put("pluginId", id)

    companion object {
        /** Logged once per key kept in memory (the key itself is never logged). */
        const val UNATTRIBUTED = "unattributed setKey kept in memory"
        const val READ_TIMEOUT_MS = 2_000L
        const val WRITE_TIMEOUT_MS = 2_000L
        /** Pending (unacknowledged) keys across all plugins before a new key has to wait for room. */
        const val MAX_DIRTY = 512
        /** A Binder write in flight longer than this is logged once. */
        const val STALL_MS = 10_000L
        /** Kino's `kino.storage` holds 256 KB per plugin; a bigger entry could only be refused (and may not fit a Binder parcel). */
        const val MAX_ENTRY_CHARS = 256 * 1024
        private const val ID_PREFIX = "cloudstream-"
        private const val TAG = "KinoBridgePrefs"

        /**
         * Reads only. Bounded: a Kino that stops answering holds at most these threads (each until its Binder call returns);
         * everything behind them waits in the queue and times out with its caller.
         */
        private val sharedReadExecutor: ExecutorService = ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        ) { r -> Thread(r, "bridge-storage").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

        private fun defaultLog(msg: String) { runCatching { Log.w(TAG, msg) } }
    }
}

/**
 * One SharedPreferences name over a [HostStore]. [namespace] null is CloudStream's `rebuild_preference` (DataStore):
 * its keys are stored as they are, and it never sees the other names' keys. Any other name N stores key K as
 * `"\u0000" + N + "\u0000" + K` (a leading NUL never occurs in DataStore's `folder/path` keys, so upstream's
 * `getKeys(folder)` can never see them).
 *
 * Every value is a typed JSON string `{"t":"s|i|l|f|b|ss","v":…}` so getInt / getStringSet round-trip; a stored string
 * without that envelope reads as a plain string, and a read of the wrong type is the default. Listeners are accepted
 * and kept but never called.
 */
class HostPrefs(private val store: HostStore, namespace: String? = null) : SharedPreferences {
    private val prefix = namespace?.let { "$NUL$it$NUL" }
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    private fun wire(key: String) = prefix?.plus(key) ?: key
    private fun mine(wireKey: String) = if (prefix == null) !wireKey.startsWith(NUL) else wireKey.startsWith(prefix)
    private fun unwire(wireKey: String) = if (prefix == null) wireKey else wireKey.substring(prefix.length)

    private class Typed(val t: String, val v: Any?)

    private inline fun <T> read(key: String, t: String, def: T, pick: (Any?) -> T): T = try {
        store.read(wire(key))?.let(::decode)?.takeIf { it.t == t }?.let { pick(it.v) } ?: def
    } catch (_: Exception) { def }

    override fun getString(key: String, defValue: String?): String? = read(key, "s", defValue) { it as String }
    override fun getInt(key: String, defValue: Int): Int = read(key, "i", defValue) { (it as Number).toInt() }
    override fun getLong(key: String, defValue: Long): Long = read(key, "l", defValue) { (it as Number).toLong() }
    override fun getFloat(key: String, defValue: Float): Float = read(key, "f", defValue) { (it as Number).toFloat() }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key, "b", defValue) { it as Boolean }
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = read(key, "ss", defValues) { stringSet(it) }

    override fun contains(key: String): Boolean = try { store.read(wire(key)) != null } catch (_: Exception) { false }

    override fun getAll(): Map<String, *> = try {
        store.all(::mine).entries.associate { (k, v) -> unwire(k) to valueOf(decode(v)) }
    } catch (_: Exception) { emptyMap<String, Any?>() }

    override fun edit(): SharedPreferences.Editor = Edit()

    private inner class Edit : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any>()
        private var clear = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            synchronized(this) { changes[key] = value ?: REMOVED }
            return this
        }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear(): SharedPreferences.Editor {
            synchronized(this) { clear = true }
            return this
        }
        override fun apply() { save(wait = false) }
        override fun commit(): Boolean = save(wait = true)

        private fun save(wait: Boolean): Boolean = try {
            val (wipe, batch) = synchronized(this) { (clear to LinkedHashMap(changes)).also { clear = false; changes.clear() } }
            var ok = true
            val encoded = LinkedHashMap<String, String?>()
            for ((key, value) in batch) {
                if (value === REMOVED) { encoded[wire(key)] = null; continue }
                val e = encode(value)
                if (e == null) ok = false else encoded[wire(key)] = e // a NaN or infinite float has no JSON form
            }
            store.commit(wipe, ::mine, encoded, wait) && ok
        } catch (_: Exception) { false }
    }

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

    companion object {
        private const val NUL = "\u0000"
        private val REMOVED = Any()

        internal fun encode(value: Any): String? = try {
            val (t, v) = when (value) {
                is String -> "s" to value
                is Int -> "i" to value
                is Long -> "l" to value
                is Float -> "f" to value.toDouble()
                is Boolean -> "b" to value
                is Set<*> -> "ss" to JSONArray(value.map { it as String })
                else -> return null
            }
            JSONObject().put("t", t).put("v", v).toString()
        } catch (_: Exception) { null }

        private fun decode(raw: String): Typed = try {
            val o = JSONObject(raw)
            if (o.length() == 2 && o.has("t") && o.has("v")) Typed(o.getString("t"), o.get("v")) else Typed("s", raw)
        } catch (_: Exception) { Typed("s", raw) }

        private fun stringSet(v: Any?): Set<String> = (v as JSONArray).let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }

        private fun valueOf(t: Typed): Any? = try {
            when (t.t) {
                "s" -> t.v as String
                "i" -> (t.v as Number).toInt()
                "l" -> (t.v as Number).toLong()
                "f" -> (t.v as Number).toFloat()
                "b" -> t.v as Boolean
                "ss" -> stringSet(t.v)
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
