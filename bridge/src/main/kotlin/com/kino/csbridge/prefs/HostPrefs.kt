// GPL-3.0 (this fork).
package com.kino.csbridge.prefs

import android.content.SharedPreferences
import android.util.Log
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * CloudStream's `rebuild_preference` (DataStore's getKey/setKey, PluginManager's keys) without a file: every value lives
 * in Kino, in the owning plugin's own `kino.storage`, through `IHost.storage` (spec §5.6). Nothing is ever written in the
 * bridge's data dir.
 *
 * Owner of a call: the plugin of the live [CallContext]; else the only installed plugin; else nobody, and the value is
 * kept in this process's memory only (logged once per key, never naming it). [memoryOnly] prefs always use memory.
 *
 * Every value is stored as a typed JSON string `{"t":"s|i|l|f|b|ss","v":…}` so getInt / getStringSet round-trip.
 * Reads go to Kino every time (no stale cache across devices). Each storage call is a synchronous Binder call into Kino,
 * run on [executor] and waited on at most its deadline (the smaller of its timeout and the op's remaining time): a read
 * that does not answer in time is the default, a write that fails is tried once more and then dropped. Every failure
 * is logged with the op and the plugin id only (never a key, a value or a URL), and nothing here throws into plugin code.
 *
 * Listeners are accepted and kept but never called. apply() is commit(): a write has finished (or been dropped) when it
 * returns, so the next read sees it.
 */
class HostPrefs(
    private val installedIds: () -> List<String>,
    private val host: () -> IHost? = { HostLink.host },
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val memoryOnly: Boolean = false,
    private val log: (String) -> Unit = ::defaultLog,
    private val executor: ExecutorService = sharedExecutor,
) : SharedPreferences {

    private val memory = ConcurrentHashMap<String, String>()
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** The plugin whose Kino storage serves this call, or null for this process's memory. */
    private fun owner(): String? {
        if (memoryOnly) return null
        CallContext.current()?.pluginId?.takeIf { it.startsWith(ID_PREFIX) }?.let { return it }
        return try { installedIds().singleOrNull() } catch (_: Exception) { null }
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    private class Typed(val t: String, val v: Any?)

    /** The stored string for [key], or null when absent or Kino did not answer. */
    private fun raw(key: String): String? {
        val id = owner() ?: return memory[key]
        val answer = call("get", id, op("get", id).put("key", key), readTimeoutMs) ?: return null
        CsWire.errorOf(answer)?.let { log("storage get refused: ${it.first} (plugin=$id)"); return null }
        val o = JSONObject(answer)
        return if (o.isNull("value")) null else o.optString("value")
    }

    private fun typed(key: String): Typed? = try { raw(key)?.let(::decode) } catch (_: Exception) { null }

    private inline fun <T> read(key: String, t: String, def: T, pick: (Any?) -> T): T = try {
        typed(key)?.takeIf { it.t == t }?.let { pick(it.v) } ?: def
    } catch (_: Exception) { def }

    override fun getString(key: String, defValue: String?): String? = read(key, "s", defValue) { it as String }
    override fun getInt(key: String, defValue: Int): Int = read(key, "i", defValue) { (it as Number).toInt() }
    override fun getLong(key: String, defValue: Long): Long = read(key, "l", defValue) { (it as Number).toLong() }
    override fun getFloat(key: String, defValue: Float): Float = read(key, "f", defValue) { (it as Number).toFloat() }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key, "b", defValue) { it as Boolean }
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        read(key, "ss", defValues) { v -> (v as JSONArray).let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } } }

    override fun contains(key: String): Boolean = try { raw(key) != null } catch (_: Exception) { false }

    override fun getAll(): Map<String, *> = try {
        val id = owner()
        if (id == null) {
            memory.mapValues { valueOf(decode(it.value)) }
        } else {
            // One deadline for the whole listing: n keys never make it n timeouts long.
            val until = System.nanoTime() + readTimeoutMs * 1_000_000
            fun left() = ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            val out = LinkedHashMap<String, Any?>()
            for (key in keys(id, readTimeoutMs).orEmpty()) {
                val answer = call("get", id, op("get", id).put("key", key), left()) ?: break
                if (CsWire.errorOf(answer) != null) continue
                val o = JSONObject(answer)
                if (!o.isNull("value")) out[key] = valueOf(decode(o.optString("value")))
            }
            out
        }
    } catch (_: Exception) { emptyMap<String, Any?>() }

    private fun keys(id: String, timeoutMs: Long): List<String>? {
        val answer = call("keys", id, op("keys", id), timeoutMs) ?: return null
        CsWire.errorOf(answer)?.let { log("storage keys refused: ${it.first} (plugin=$id)"); return null }
        val a = JSONObject(answer).getJSONArray("keys")
        return (0 until a.length()).map { a.getString(it) }
    }

    // ---- writes --------------------------------------------------------------------------------------------------

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
        override fun apply() { commit() }

        override fun commit(): Boolean = try {
            val (wipe, batch) = synchronized(this) { (clear to LinkedHashMap(changes)).also { clear = false; changes.clear() } }
            val id = owner()
            if (id == null) commitToMemory(wipe, batch) else commitToKino(id, wipe, batch)
        } catch (_: Exception) { false }
    }

    private fun commitToMemory(wipe: Boolean, batch: Map<String, Any>): Boolean {
        if (wipe) memory.clear()
        var ok = true
        for ((key, value) in batch) {
            if (value === REMOVED) { memory.remove(key); continue }
            val encoded = encode(value)
            if (encoded == null) { ok = false; continue }
            if (!memoryOnly && warned.add(key)) log(UNATTRIBUTED)
            memory[key] = encoded
        }
        return ok
    }

    private fun commitToKino(id: String, wipe: Boolean, batch: Map<String, Any>): Boolean {
        var ok = true
        if (wipe) {
            val existing = keys(id, readTimeoutMs)
            if (existing == null) ok = false
            else for (key in existing) if (key !in batch) ok = write("remove", id, op("remove", id).put("key", key)) && ok
        }
        for ((key, value) in batch) {
            ok = if (value === REMOVED) {
                write("remove", id, op("remove", id).put("key", key)) && ok
            } else {
                val encoded = encode(value)
                when {
                    encoded == null -> { log("storage set dropped: value not storable (plugin=$id)"); false }
                    key.length + encoded.length > MAX_ENTRY_CHARS -> { log("storage set dropped: ${CsWire.Err.TOO_LARGE} (plugin=$id)"); false }
                    else -> write("set", id, op("set", id).put("key", key).put("value", encoded)) && ok
                }
            }
        }
        return ok
    }

    /** One write, tried again once when Kino did not answer; an answered refusal is final. */
    private fun write(name: String, id: String, body: JSONObject): Boolean {
        for (attempt in 1..2) {
            val answer = call(name, id, body, writeTimeoutMs)
            if (answer != null) {
                val err = CsWire.errorOf(answer) ?: return true
                log("storage $name refused: ${err.first} (plugin=$id)")
                return false
            }
            if (CallContext.current()?.isOver() == true) break
        }
        log("storage $name dropped after retry (plugin=$id)")
        return false
    }

    // ---- the Binder call -----------------------------------------------------------------------------------------

    /** Kino's answer, or null (logged) when there is no host, the op is over, Kino failed or the deadline passed. */
    private fun call(name: String, id: String, body: JSONObject, timeoutMs: Long): String? {
        val h = host() ?: run { log("storage $name skipped: no host (plugin=$id)"); return null }
        val budget = minOf(timeoutMs, CallContext.current()?.remainingMs() ?: Long.MAX_VALUE)
        if (budget <= 0) { log("storage $name skipped: call over (plugin=$id)"); return null }
        val json = body.toString()
        val f = try { executor.submit(Callable { h.storage(json) }) } catch (_: RejectedExecutionException) {
            log("storage $name skipped: queue full (plugin=$id)"); return null
        }
        return try {
            f.get(budget, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(false); log("storage $name timed out after $budget ms (plugin=$id)"); null
        } catch (e: ExecutionException) {
            log("storage $name failed: ${e.cause?.javaClass?.simpleName} (plugin=$id)"); null
        } catch (_: InterruptedException) {
            f.cancel(false); Thread.currentThread().interrupt(); log("storage $name interrupted (plugin=$id)"); null
        }
    }

    private fun op(name: String, id: String) = JSONObject().put("op", name).put("pluginId", id)

    // ---- listeners -----------------------------------------------------------------------------------------------

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

    companion object {
        /** Logged once per key kept in memory (the key itself is never logged). */
        const val UNATTRIBUTED = "unattributed setKey kept in memory"
        const val READ_TIMEOUT_MS = 2_000L
        const val WRITE_TIMEOUT_MS = 2_000L
        /** Kino's `kino.storage` holds 256 KB per plugin; a bigger entry could only be refused (and may not fit a Binder parcel). */
        const val MAX_ENTRY_CHARS = 256 * 1024
        private const val ID_PREFIX = "cloudstream-"
        private const val TAG = "KinoBridgePrefs"
        private val REMOVED = Any()

        /**
         * Storage calls only. Bounded: a Kino that stops answering holds at most these threads (each until its Binder call
         * returns); everything behind them waits in the queue and times out with its caller.
         */
        private val sharedExecutor: ExecutorService = ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        ) { r -> Thread(r, "bridge-storage").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

        private fun defaultLog(msg: String) { runCatching { Log.w(TAG, msg) } }

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
        } catch (_: Exception) { null } // a NaN or infinite float has no JSON form

        /** A string not written by us (no envelope) reads as a plain string. */
        private fun decode(raw: String): Typed = try {
            val o = JSONObject(raw)
            if (o.length() == 2 && o.has("t") && o.has("v")) Typed(o.getString("t"), o.get("v")) else Typed("s", raw)
        } catch (_: Exception) { Typed("s", raw) }

        private fun valueOf(t: Typed): Any? = try {
            when (t.t) {
                "s" -> t.v as String
                "i" -> (t.v as Number).toInt()
                "l" -> (t.v as Number).toLong()
                "f" -> (t.v as Number).toFloat()
                "b" -> t.v as Boolean
                "ss" -> (t.v as JSONArray).let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
