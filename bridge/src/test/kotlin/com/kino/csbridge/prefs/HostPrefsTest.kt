package com.kino.csbridge.prefs

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import com.kino.csbridge.CallContext
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.kino.csbridge.contract.IReply
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Kino's side as CsStorageRouter answers it: one map per CloudStream plugin id, ops get/set/remove/keys. */
internal class FakeKinoStorage : IHost.Stub() {
    val stores = ConcurrentHashMap<String, MutableMap<String, String>>()
    val ops = CopyOnWriteArrayList<JSONObject>()
    /** Every op waits on it while set. */
    @Volatile var block: CountDownLatch? = null
    /** Only the next op waits on it (then it is cleared). */
    @Volatile var blockNext: CountDownLatch? = null
    @Volatile var fail: Exception? = null
    @Volatile var answer: String? = null
    private val inFlight = AtomicInteger()
    val maxWritesInFlight = AtomicInteger()

    fun opNames() = ops.map { it.getString("op") }

    override fun request(m: String?, b: ParcelFileDescriptor?, s: ParcelFileDescriptor?, r: IHttpReply?) {}
    override fun webView(j: String?, r: IReply?) {}
    override fun storage(opJson: String): String {
        val o = JSONObject(opJson)
        ops += o
        val write = o.getString("op") in setOf("set", "remove")
        if (write) maxWritesInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            blockNext?.let { blockNext = null; it.await(10, TimeUnit.SECONDS) }
            block?.await(10, TimeUnit.SECONDS)
            fail?.let { throw it }
            answer?.let { return it }
            val id = o.getString("pluginId")
            if (!id.startsWith("cloudstream-")) return CsWire.error(CsWire.Err.REFUSED, "not a CloudStream plugin id")
            val store = stores.getOrPut(id) { LinkedHashMap() }
            return when (o.getString("op")) {
                "get" -> JSONObject().put("value", store[o.getString("key")] ?: JSONObject.NULL).toString()
                "set" -> { store[o.getString("key")] = o.getString("value"); """{"ok":true}""" }
                "remove" -> { store.remove(o.getString("key")); """{"ok":true}""" }
                "keys" -> JSONObject().put("keys", JSONArray(store.keys.toList())).toString()
                else -> CsWire.error(CsWire.Err.BAD_REQUEST, "unknown storage op")
            }
        } finally {
            if (write) inFlight.decrementAndGet()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class HostPrefsTest {
    private val kino = FakeKinoStorage()
    private val logs = CopyOnWriteArrayList<String>()
    private var installed = listOf<String>()
    private var host: IHost? = kino
    private val latches = CopyOnWriteArrayList<CountDownLatch>()

    private fun store(timeoutMs: Long = 2_000, maxDirty: Int = HostStore.MAX_DIRTY) =
        HostStore({ installed }, { host }, readTimeoutMs = timeoutMs, writeTimeoutMs = timeoutMs, maxDirty = maxDirty, log = { logs += it })

    private fun latch() = CountDownLatch(1).also { latches += it }

    private fun <T> asPlugin(id: String, deadlineMs: Long = 60_000, block: () -> T): T {
        val previous = CallContext.threadLocal.get()
        CallContext.threadLocal.set(CallContext("call-1", id, CallContext.deadlineFromNow(deadlineMs)))
        try { return block() } finally { CallContext.threadLocal.set(previous) }
    }

    private fun stored(id: String, key: String) = kino.stores[id]?.get(key)?.let { JSONObject(it).get("v") }

    @After fun tearDown() {
        latches.forEach { it.countDown() }
        CallContext.threadLocal.remove()
    }

    @Test fun `a write with a live call goes to that plugin's Kino storage as a typed JSON string`() {
        val p = HostPrefs(store())
        asPlugin(A) { assertTrue(p.edit().putString("k", "v").commit()) }
        val set = kino.ops.single()
        assertEquals("set", set.getString("op"))
        assertEquals(A, set.getString("pluginId"))
        assertEquals("k", set.getString("key"))
        assertEquals("""{"t":"s","v":"v"}""", set.getString("value"))
        assertEquals("v", asPlugin(A) { p.getString("k", null) })
        assertEquals(A, kino.ops.last().getString("pluginId"))
    }

    @Test fun `each plugin has its own values for the same key`() {
        val s = store()
        val p = HostPrefs(s)
        asPlugin(A) { p.edit().putString("k", "from a").apply() }
        asPlugin(B) { p.edit().putString("k", "from b").apply() }
        assertTrue(s.awaitIdle(5_000))
        assertEquals("from a", asPlugin(A) { p.getString("k", null) })
        assertEquals("from b", asPlugin(B) { p.getString("k", null) })
        assertEquals(setOf(A, B), kino.stores.keys)
    }

    @Test fun `two plugins using the same name stay apart, and names never see each other`() {
        val s = store()
        val ext = HostPrefs(s, "Extension")
        val other = HostPrefs(s, "Other")
        val rebuild = HostPrefs(s)
        asPlugin(A) { ext.edit().putString("token", "a").commit() }
        asPlugin(B) { ext.edit().putString("token", "b").commit() }
        assertEquals("a", asPlugin(A) { ext.getString("token", null) })
        assertEquals("b", asPlugin(B) { ext.getString("token", null) })
        assertNull(asPlugin(A) { other.getString("token", null) })
        assertNull(asPlugin(A) { rebuild.getString("token", null) })
        assertEquals(mapOf("token" to "a"), asPlugin(A) { ext.all })
        assertEquals(emptyMap<String, Any?>(), asPlugin(A) { rebuild.all })
        assertEquals("\u0000Extension\u0000token", kino.stores[A]!!.keys.single())
    }

    @Test fun `values survive a process restart (a fresh store reads them back from Kino)`() {
        asPlugin(A) {
            val first = store()
            HostPrefs(first).edit().putInt("n", 5).apply()
            HostPrefs(first, "Extension").edit().putString("token", "t").apply()
            assertTrue(first.awaitIdle(5_000))
        }
        val fresh = store()
        asPlugin(A) {
            assertEquals(5, HostPrefs(fresh).getInt("n", 0))
            assertEquals("t", HostPrefs(fresh, "Extension").getString("token", null))
        }
    }

    @Test fun `reads go to Kino every time, so another device's change is seen at once`() {
        val p = HostPrefs(store())
        asPlugin(A) { p.edit().putInt("n", 1).commit() }
        kino.stores[A]!!["n"] = """{"t":"i","v":7}"""
        assertEquals(7, asPlugin(A) { p.getInt("n", 0) })
    }

    @Test fun `typed values round-trip, a mismatched type answers the default`() {
        val p = HostPrefs(store())
        asPlugin(A) {
            assertTrue(p.edit().putInt("i", 42).putLong("l", Long.MAX_VALUE).putBoolean("b", true).putFloat("f", 0.1f)
                .putStringSet("ss", setOf("x", "y")).putString("s", "text").commit())
            assertEquals(42, p.getInt("i", 0))
            assertEquals(Long.MAX_VALUE, p.getLong("l", 0))
            assertTrue(p.getBoolean("b", false))
            assertEquals(0.1f, p.getFloat("f", 0f))
            assertEquals(setOf("x", "y"), p.getStringSet("ss", null))
            assertEquals("text", p.getString("s", null))
            assertEquals(5, p.getInt("s", 5))
            assertEquals("d", p.getString("i", "d"))
            assertEquals(mapOf("i" to 42, "l" to Long.MAX_VALUE, "b" to true, "f" to 0.1f, "ss" to setOf("x", "y"), "s" to "text"), p.all)
            assertTrue(p.contains("i"))
            assertFalse(p.contains("missing"))
        }
    }

    @Test fun `remove, putString null and clear remove in Kino, clear of one name keeps the others`() {
        val s = store()
        val p = HostPrefs(s)
        val named = HostPrefs(s, "Extension")
        asPlugin(A) {
            named.edit().putString("keep", "1").commit()
            p.edit().putString("a", "1").putString("b", "2").putString("c", "3").commit()
            p.edit().remove("a").putString("b", null).commit()
            assertEquals(setOf("\u0000Extension\u0000keep", "c"), kino.stores[A]!!.keys)
            p.edit().putString("d", "4").clear().commit() // clear runs first, then this editor's own puts
            assertEquals(setOf("\u0000Extension\u0000keep", "d"), kino.stores[A]!!.keys)
        }
    }

    @Test fun `no live call and exactly one plugin installed attributes to that plugin`() {
        installed = listOf(A)
        val p = HostPrefs(store())
        assertTrue(p.edit().putString("k", "v").commit())
        assertEquals("v", stored(A, "k"))
        assertEquals("v", p.getString("k", null))
    }

    @Test fun `no live call and two plugins keeps the value in memory and never calls Kino`() {
        installed = listOf(A, B)
        val p = HostPrefs(store())
        assertTrue(p.edit().putString("k", "v").putInt("n", 3).commit())
        p.edit().putString("k", "v2").apply()
        assertEquals("v2", p.getString("k", null))
        assertEquals(3, p.getInt("n", 0))
        assertTrue(kino.ops.isEmpty())
        // Once per key, and the message never names the key or the value.
        assertEquals(listOf(HostStore.UNATTRIBUTED, HostStore.UNATTRIBUTED), logs)
    }

    @Test fun `no live call and no plugin keeps the value in memory`() {
        val p = HostPrefs(store())
        p.edit().putString("k", "v").commit()
        assertEquals("v", p.getString("k", null))
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `the installed list failing is treated as unattributed, never a crash`() {
        val p = HostPrefs(HostStore({ throw IllegalStateException("plugin storage") }, { kino }, log = { logs += it }))
        p.edit().putString("k", "v").commit()
        assertEquals("v", p.getString("k", null))
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `no Kino yet answers defaults and drops writes without throwing`() {
        host = null
        val s = store()
        val p = HostPrefs(s)
        asPlugin(A) {
            assertFalse(p.edit().putString("k", "v").commit())
            p.edit().putString("k", "v").apply()
            assertTrue(s.awaitIdle(5_000))
            assertNull(p.getString("k", null))
            assertEquals(9, p.getInt("k", 9))
            assertEquals(emptyMap<String, Any?>(), p.all)
            assertFalse(p.contains("k"))
        }
    }

    @Test fun `a dead Kino answers defaults, and a failed write is tried exactly twice then dropped`() {
        kino.fail = DeadObjectException()
        val p = HostPrefs(store())
        asPlugin(A) {
            assertEquals("d", p.getString("k", "d"))
            assertEquals(1, kino.ops.size) // a read is never retried
            kino.ops.clear()
            assertFalse(p.edit().putString("k", "v").commit())
        }
        assertEquals(2, kino.ops.size)
        assertTrue(logs.any { it.contains("dropped") })
    }

    @Test fun `after the first unanswered write the rest of the batch is dropped, logged once`() {
        kino.fail = DeadObjectException()
        val p = HostPrefs(store())
        asPlugin(A) { assertFalse(p.edit().putString("a", "1").putString("b", "2").putString("c", "3").commit()) }
        assertEquals(2, kino.ops.size) // "a" and its one retry; "b" and "c" never reach Kino
        assertEquals(1, logs.count { it.contains("later writes dropped") })
    }

    @Test fun `any exception from Kino never reaches plugin code`() {
        kino.fail = IllegalArgumentException("boom")
        val p = HostPrefs(store())
        asPlugin(A) {
            assertEquals(1L, p.getLong("k", 1L))
            assertFalse(p.edit().putLong("k", 2).commit())
        }
    }

    @Test fun `a write Kino refuses is not retried`() {
        kino.answer = CsWire.error(CsWire.Err.TOO_LARGE, "full")
        val p = HostPrefs(store())
        asPlugin(A) { assertFalse(p.edit().putString("k", "v").commit()) }
        assertEquals(1, kino.ops.size)
        assertTrue(logs.any { it.contains(CsWire.Err.TOO_LARGE) })
    }

    @Test fun `a value over Kino's 256 KB limit is dropped without a call, the namespace counted`() {
        val s = store()
        asPlugin(A) {
            assertFalse(HostPrefs(s).edit().putString("k", "x".repeat(256 * 1024)).commit())
            // Fits as a bare key, not once the name's prefix is added.
            val v = "x".repeat(256 * 1024 - 1 - """{"t":"s","v":""}""".length)
            assertFalse(HostPrefs(s, "Extension").edit().putString("k", v).commit())
        }
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `apply never blocks on a hung Kino, and reads see the pending write at once`() {
        kino.block = latch()
        val p = HostPrefs(store(timeoutMs = 300))
        asPlugin(A) {
            val t0 = System.nanoTime()
            p.edit().putString("k", "v").putInt("n", 2).apply()
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 100)
            assertEquals("v", p.getString("k", null))
            assertEquals(2, p.getInt("n", 0))
            assertTrue(p.contains("k"))
        }
        assertTrue(kino.opNames().all { it == "set" }) // no read went to Kino
    }

    @Test fun `a commit that Kino does not answer in time returns false at its deadline, and the write still lands`() {
        val gate = latch().also { kino.blockNext = it }
        val s = store(timeoutMs = 200)
        val p = HostPrefs(s)
        val t0 = System.nanoTime()
        assertFalse(asPlugin(A) { p.edit().putString("k", "v").commit() })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 150..1_500)
        gate.countDown()
        assertTrue(s.awaitIdle(5_000))
        assertEquals("v", stored(A, "k"))
    }

    @Test fun `writes land in order after a timeout, one at a time, newest value last`() {
        val gate = latch().also { kino.blockNext = it }
        val s = store(timeoutMs = 200)
        val p = HostPrefs(s)
        asPlugin(A) {
            assertFalse(p.edit().putString("k", "old").commit()) // stuck in Kino past its deadline
            p.edit().putString("k", "mid").apply()
            p.edit().putString("k", "new").putString("other", "x").apply()
            assertEquals("new", p.getString("k", null))
        }
        gate.countDown()
        assertTrue(s.awaitIdle(5_000))
        assertEquals("new", stored(A, "k"))
        assertEquals("x", stored(A, "other"))
        assertEquals(1, kino.maxWritesInFlight.get())
        // "mid" was coalesced into "new": three sets in all, never four.
        assertEquals(listOf("set", "set", "set"), kino.opNames())
    }

    @Test fun `past the dirty-key cap, a new key waits for room up to its deadline, then is dropped`() {
        kino.block = latch()
        val s = store(timeoutMs = 200, maxDirty = 2)
        val p = HostPrefs(s)
        asPlugin(A) {
            p.edit().putString("a", "1").putString("b", "2").apply()
            p.edit().putString("a", "1b").apply() // an already-dirty key always fits
            val t0 = System.nanoTime()
            p.edit().putString("c", "3").apply()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("took $ms ms", ms in 150..1_500)
            assertNull(p.getString("c", null)) // never pending: the read goes to (hung) Kino and times out
        }
        assertTrue(logs.any { it.contains("too many pending writes") })
    }

    @Test fun `a read that blocks on Kino returns the default at its deadline and is logged`() {
        kino.block = latch()
        val p = HostPrefs(store(timeoutMs = 200))
        val t0 = System.nanoTime()
        assertEquals("d", asPlugin(A) { p.getString("k", "d") })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 150..1_500)
        assertTrue(logs.any { it.contains("timed out") })
    }

    @Test fun `the op's own deadline bounds a storage call`() {
        kino.block = latch()
        val p = HostPrefs(store(timeoutMs = 5_000))
        val t0 = System.nanoTime()
        assertEquals("d", asPlugin(A, deadlineMs = 200) { p.getString("k", "d") })
        assertFalse(asPlugin(A, deadlineMs = 200) { p.edit().putString("k", "v").commit() })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun `an op already past its deadline never reads from Kino`() {
        val p = HostPrefs(store())
        asPlugin(A, deadlineMs = 0) { assertEquals("d", p.getString("k", "d")) }
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `a getAll that Kino stops answering returns what it has and logs it once`() {
        val s = store(timeoutMs = 300)
        val p = HostPrefs(s)
        asPlugin(A) { p.edit().putString("a", "1").putString("b", "2").commit() }
        kino.block = latch()
        asPlugin(A) { assertEquals(emptyMap<String, Any?>(), p.all) }
        assertEquals(1, logs.count { it.contains("getAll partial") })
    }

    @Test fun `logs never carry a key or a value`() {
        val s = store(timeoutMs = 100)
        val p = HostPrefs(s)
        val named = HostPrefs(s, "https://repo.example/secret-name")
        val key = "secret-key-https://repo.example/x?token=1"
        val value = "secret-value"
        installed = listOf(A, B)
        p.edit().putString(key, value).commit()
        kino.fail = DeadObjectException()
        asPlugin(A) { p.edit().putString(key, value).commit(); named.edit().putString(key, value).commit(); p.getString(key, null) }
        kino.fail = null; kino.answer = CsWire.error(CsWire.Err.TOO_LARGE, "full")
        asPlugin(A) { p.edit().putString(key, value).commit() }
        kino.answer = null; kino.block = latch()
        asPlugin(A) { p.getString(key, null); p.all; p.edit().putString(key, value).commit() }
        assertTrue(logs.isNotEmpty())
        logs.forEach { assertFalse(it, it.contains("secret") || it.contains("repo.example")) }
    }

    @Test fun `listeners are accepted and kept, never called`() {
        val p = HostPrefs(store())
        var calls = 0
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> calls++ }
        p.registerOnSharedPreferenceChangeListener(l)
        asPlugin(A) { p.edit().putString("k", "v").commit() }
        p.unregisterOnSharedPreferenceChangeListener(l)
        assertEquals(0, calls)
    }

    private companion object {
        const val A = "cloudstream-alpha-aaaaaa"
        const val B = "cloudstream-beta-bbbbbb"
    }
}
