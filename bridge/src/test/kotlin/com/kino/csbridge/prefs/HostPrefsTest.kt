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

/** Kino's side as CsStorageRouter answers it: one map per CloudStream plugin id, ops get/set/remove/keys. */
internal class FakeKinoStorage : IHost.Stub() {
    val stores = ConcurrentHashMap<String, MutableMap<String, String>>()
    val ops = CopyOnWriteArrayList<JSONObject>()
    @Volatile var block: CountDownLatch? = null
    @Volatile var fail: Exception? = null
    @Volatile var answer: String? = null

    override fun request(m: String?, b: ParcelFileDescriptor?, s: ParcelFileDescriptor?, r: IHttpReply?) {}
    override fun webView(j: String?, r: IReply?) {}
    override fun storage(opJson: String): String {
        val o = JSONObject(opJson)
        ops += o
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
    }
}

@RunWith(RobolectricTestRunner::class)
class HostPrefsTest {
    private val kino = FakeKinoStorage()
    private val logs = CopyOnWriteArrayList<String>()
    private var installed = listOf<String>()
    private var host: IHost? = kino

    private fun prefs(timeoutMs: Long = 2_000) =
        HostPrefs({ installed }, { host }, readTimeoutMs = timeoutMs, writeTimeoutMs = timeoutMs, log = { logs += it })

    private fun <T> asPlugin(id: String, deadlineMs: Long = 60_000, block: () -> T): T {
        val previous = CallContext.threadLocal.get()
        CallContext.threadLocal.set(CallContext("call-1", id, CallContext.deadlineFromNow(deadlineMs)))
        try { return block() } finally { CallContext.threadLocal.set(previous) }
    }

    @After fun tearDown() {
        kino.block?.countDown()
        CallContext.threadLocal.remove()
    }

    @Test fun `a write with a live call goes to that plugin's Kino storage as a typed JSON string`() {
        val p = prefs()
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
        val p = prefs()
        asPlugin(A) { p.edit().putString("k", "from a").apply() }
        asPlugin(B) { p.edit().putString("k", "from b").apply() }
        assertEquals("from a", asPlugin(A) { p.getString("k", null) })
        assertEquals("from b", asPlugin(B) { p.getString("k", null) })
        assertEquals(setOf(A, B), kino.stores.keys)
    }

    @Test fun `reads go to Kino every time, so another device's change is seen at once`() {
        val p = prefs()
        asPlugin(A) { p.edit().putInt("n", 1).commit() }
        kino.stores[A]!!["n"] = """{"t":"i","v":7}"""
        assertEquals(7, asPlugin(A) { p.getInt("n", 0) })
    }

    @Test fun `typed values round-trip, a mismatched type answers the default`() {
        val p = prefs()
        asPlugin(A) {
            p.edit().putInt("i", 42).putLong("l", Long.MAX_VALUE).putBoolean("b", true).putFloat("f", 0.1f)
                .putStringSet("ss", setOf("x", "y")).putString("s", "text").commit()
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

    @Test fun `remove, putString null and clear remove in Kino`() {
        val p = prefs()
        asPlugin(A) {
            p.edit().putString("a", "1").putString("b", "2").putString("c", "3").commit()
            p.edit().remove("a").putString("b", null).commit()
            assertEquals(setOf("c"), kino.stores[A]!!.keys)
            p.edit().putString("d", "4").clear().commit() // clear runs first, then this editor's own puts
            assertEquals(setOf("d"), kino.stores[A]!!.keys)
        }
    }

    @Test fun `no live call and exactly one plugin installed attributes to that plugin`() {
        installed = listOf(A)
        val p = prefs()
        assertTrue(p.edit().putString("k", "v").commit())
        assertEquals("v", kino.stores[A]!!["k"]?.let { JSONObject(it).getString("v") })
        assertEquals("v", p.getString("k", null))
    }

    @Test fun `no live call and two plugins keeps the value in memory and never calls Kino`() {
        installed = listOf(A, B)
        val p = prefs()
        assertTrue(p.edit().putString("k", "v").putInt("n", 3).commit())
        p.edit().putString("k", "v2").apply()
        assertEquals("v2", p.getString("k", null))
        assertEquals(3, p.getInt("n", 0))
        assertTrue(kino.ops.isEmpty())
        // Once per key, and the message never names the key or the value.
        assertEquals(listOf(HostPrefs.UNATTRIBUTED, HostPrefs.UNATTRIBUTED), logs)
    }

    @Test fun `no live call and no plugin keeps the value in memory`() {
        val p = prefs()
        p.edit().putString("k", "v").commit()
        assertEquals("v", p.getString("k", null))
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `the installed list failing is treated as unattributed, never a crash`() {
        val p = HostPrefs({ throw IllegalStateException("plugin storage") }, { kino }, log = { logs += it })
        p.edit().putString("k", "v").commit()
        assertEquals("v", p.getString("k", null))
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `no Kino yet answers defaults and drops writes without throwing`() {
        host = null
        val p = prefs()
        asPlugin(A) {
            assertFalse(p.edit().putString("k", "v").commit())
            p.edit().putString("k", "v").apply()
            assertNull(p.getString("k", null))
            assertEquals(9, p.getInt("k", 9))
            assertEquals(emptyMap<String, Any?>(), p.all)
            assertFalse(p.contains("k"))
        }
    }

    @Test fun `a dead Kino answers defaults, and a failed write is tried exactly twice then dropped`() {
        kino.fail = DeadObjectException()
        val p = prefs()
        asPlugin(A) {
            assertEquals("d", p.getString("k", "d"))
            assertEquals(1, kino.ops.size) // a read is never retried
            kino.ops.clear()
            assertFalse(p.edit().putString("k", "v").commit())
        }
        assertEquals(2, kino.ops.size)
        assertTrue(logs.any { it.contains("dropped") })
    }

    @Test fun `a plugin crash inside Kino's answer (any exception) never reaches plugin code`() {
        kino.fail = IllegalArgumentException("boom")
        val p = prefs()
        asPlugin(A) {
            assertEquals(1L, p.getLong("k", 1L))
            assertFalse(p.edit().putLong("k", 2).commit())
        }
    }

    @Test fun `a write Kino refuses is not retried`() {
        kino.answer = CsWire.error(CsWire.Err.TOO_LARGE, "full")
        val p = prefs()
        asPlugin(A) { assertFalse(p.edit().putString("k", "v").commit()) }
        assertEquals(1, kino.ops.size)
        assertTrue(logs.any { it.contains(CsWire.Err.TOO_LARGE) })
    }

    @Test fun `a value over Kino's 256 KB limit is dropped without a call`() {
        val p = prefs()
        asPlugin(A) { assertFalse(p.edit().putString("k", "x".repeat(256 * 1024)).commit()) }
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `a read that blocks on Kino returns the default at its deadline and is logged`() {
        kino.block = CountDownLatch(1)
        val p = prefs(timeoutMs = 200)
        val t0 = System.nanoTime()
        assertEquals("d", asPlugin(A) { p.getString("k", "d") })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 150..1_500)
        assertTrue(logs.any { it.contains("timed out") })
    }

    @Test fun `a write that blocks on Kino is retried once, then dropped, within two deadlines`() {
        kino.block = CountDownLatch(1)
        val p = prefs(timeoutMs = 200)
        val t0 = System.nanoTime()
        assertFalse(asPlugin(A) { p.edit().putString("k", "v").commit() })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 350..2_000)
        assertEquals(2, kino.ops.size)
        assertTrue(logs.any { it.contains("dropped") })
    }

    @Test fun `the op's own deadline bounds a storage call`() {
        kino.block = CountDownLatch(1)
        val p = prefs(timeoutMs = 5_000)
        val t0 = System.nanoTime()
        assertEquals("d", asPlugin(A, deadlineMs = 200) { p.getString("k", "d") })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun `an op already past its deadline never calls Kino`() {
        val p = prefs()
        asPlugin(A, deadlineMs = 0) {
            assertEquals("d", p.getString("k", "d"))
            assertFalse(p.edit().putString("k", "v").commit())
        }
        assertTrue(kino.ops.isEmpty())
    }

    @Test fun `logs never carry a key or a value`() {
        val p = prefs(timeoutMs = 100)
        val key = "secret-key-https://repo.example/x?token=1"
        val value = "secret-value"
        installed = listOf(A, B)
        p.edit().putString(key, value).commit()
        kino.fail = DeadObjectException()
        asPlugin(A) { p.edit().putString(key, value).commit(); p.getString(key, null) }
        kino.fail = null; kino.answer = CsWire.error(CsWire.Err.TOO_LARGE, "full")
        asPlugin(A) { p.edit().putString(key, value).commit() }
        kino.answer = null; kino.block = CountDownLatch(1)
        asPlugin(A) { p.getString(key, null) }
        assertTrue(logs.isNotEmpty())
        logs.forEach { assertFalse(it, it.contains("secret") || it.contains("repo.example")) }
    }

    @Test fun `listeners are accepted and kept, never called`() {
        val p = prefs()
        var calls = 0
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> calls++ }
        p.registerOnSharedPreferenceChangeListener(l)
        asPlugin(A) { p.edit().putString("k", "v").commit() }
        p.unregisterOnSharedPreferenceChangeListener(l)
        assertEquals(0, calls)
    }

    @Test fun `memory-only prefs never call Kino even with a live call`() {
        val p = HostPrefs({ listOf(A) }, { kino }, memoryOnly = true, log = { logs += it })
        asPlugin(A) {
            p.edit().putBoolean("b", true).commit()
            assertTrue(p.getBoolean("b", false))
        }
        assertTrue(kino.ops.isEmpty())
        assertTrue(logs.isEmpty())
    }

    private companion object {
        const val A = "cloudstream-alpha-aaaaaa"
        const val B = "cloudstream-beta-bbbbbb"
    }
}
