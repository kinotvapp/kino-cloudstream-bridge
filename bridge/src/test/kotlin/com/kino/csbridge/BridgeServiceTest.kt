package com.kino.csbridge

import android.content.Intent
import android.os.ParcelFileDescriptor
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IBridge
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.kino.csbridge.contract.IReply
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import com.lagradost.cloudstream3.newMovieSearchResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BridgeServiceTest {
    private class FakeHost : IHost.Stub() {
        override fun request(m: String?, b: ParcelFileDescriptor?, s: ParcelFileDescriptor?, r: IHttpReply?) {}
        override fun webView(j: String?, r: IReply?) {}
        override fun storage(o: String?): String = "{}"
    }

    private class FakeReply : IReply.Stub() {
        val dones = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        override fun event(e: String) { events += e }
        override fun done(r: String) { dones += r; first.countDown() }
        fun await(): String { assertTrue("no done within 5s", first.await(5, TimeUnit.SECONDS)); return dones[0] }
    }

    private var service: BridgeService? = null

    private fun bind(trusted: Boolean): IBridge {
        val s = Robolectric.setupService(BridgeService::class.java)
        s.callerCheck = { trusted }
        service = s
        return IBridge.Stub.asInterface(s.onBind(Intent()))
    }

    private fun req(op: String, callId: String = "c1", plugin: String = "cloudstream-x", deadline: Long = 5000) =
        CsWire.opRequest(op, callId, plugin, deadline, JSONObject())

    @After fun tearDown() { HostLink.host = null; com.kino.csbridge.ops.PluginWork.resetForTest() }

    @Test fun `hello with another protocol answers protocol error`() {
        val s = bind(true)
        assertEquals("protocol", CsWire.errorOf(s.hello(99, FakeHost()))!!.first)
        assertNull(HostLink.host)
    }

    @Test fun `untrusted caller is refused and the host is not kept`() {
        val s = bind(false)
        assertEquals("refused", CsWire.errorOf(s.hello(1, FakeHost()))!!.first)
        assertNull(HostLink.host)
        val r = FakeReply()
        s.call(req(CsWire.Op.LIST), null, r)
        assertEquals("refused", CsWire.errorOf(r.await())!!.first)
    }

    @Test fun `hello answers the contract shape and keeps the host`() {
        val s = bind(true)
        val h = JSONObject(s.hello(CsWire.PROTOCOL_VERSION, FakeHost()))
        assertEquals(CsWire.PROTOCOL_VERSION, h.getInt("protocolVersion"))
        assertTrue(h.getString("upstreamCommit").isNotEmpty())
        assertTrue(h.has("bridgeVersionCode") && h.has("kotlinVersion"))
        assertNotNull(CsWire.helloPluginsOf(h.getJSONArray("plugins")))
        assertNotNull(HostLink.host)
    }

    @Test fun `unknown plugin answers not_installed with exactly one done`() {
        val s = bind(true)
        val r = FakeReply()
        s.call(req(CsWire.Op.SEARCH), null, r)
        assertEquals("not_installed", CsWire.errorOf(r.await())!!.first)
        Thread.sleep(200)
        assertEquals(1, r.dones.size)
    }

    @Test fun `an op that throws replies once with plugin_error, a linkage error is incompatible`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> throw IllegalStateException("boom") }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, r)
        assertEquals("plugin_error", CsWire.errorOf(r.await())!!.first)
        svc.runOp = { _, _, _ -> throw NoSuchMethodError("x") }
        val r2 = FakeReply(); s.call(req(CsWire.Op.SEARCH, "c2"), null, r2)
        assertEquals("incompatible", CsWire.errorOf(r2.await())!!.first)
        Thread.sleep(200)
        assertEquals(1, r.dones.size); assertEquals(1, r2.dones.size)
    }

    @Test fun `ids not minted by Kino are bad_request`() {
        val s = bind(true)
        val r = FakeReply()
        s.call(req(CsWire.Op.SEARCH, plugin = "evil"), null, r)
        assertEquals("bad_request", CsWire.errorOf(r.await())!!.first)
        val r2 = FakeReply(); s.call("{not json", null, r2)
        assertEquals("bad_request", CsWire.errorOf(r2.await())!!.first)
    }

    @Test fun `cancel ends a running op with cancelled`() {
        val s = bind(true); val svc = service!!
        val started = CountDownLatch(1)
        svc.runOp = { _, _, _ -> started.countDown(); awaitCancellation() }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, r)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        s.cancel("c1")
        assertEquals("cancelled", CsWire.errorOf(r.await())!!.first)
        Thread.sleep(200)
        assertEquals(1, r.dones.size)
        assertTrue(svc.jobs.isEmpty())
    }

    @Test fun `deadline ends a silent op with timeout`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> awaitCancellation() }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH, deadline = 200), null, r)
        assertEquals("timeout", CsWire.errorOf(r.await())!!.first)
    }

    @Test fun `a done too large for binder gets exactly one too_large fallback`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> "{}" }
        val calls = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(2)
        val r = object : IReply.Stub() {
            override fun event(e: String) {}
            override fun done(j: String) { calls += j; latch.countDown(); if (calls.size == 1) throw android.os.TransactionTooLargeException() }
        }
        s.call(req(CsWire.Op.SEARCH), null, r)
        assertTrue(latch.await(5, TimeUnit.SECONDS)); Thread.sleep(200)
        assertEquals(2, calls.size)
        assertEquals("too_large", CsWire.errorOf(calls[1])!!.first)
    }

    @Test fun `a result over the cap is replaced by too_large`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> "x".repeat(600 * 1024) }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, r)
        assertEquals("too_large", CsWire.errorOf(r.await())!!.first)
    }

    @Test fun `a stray CancellationException from the plugin is plugin_error`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> throw kotlinx.coroutines.CancellationException("plugin") }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, r)
        assertEquals("plugin_error", CsWire.errorOf(r.await())!!.first)
    }

    @Test fun `a plugin's own inner timeout before our deadline is plugin_error`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> kotlinx.coroutines.withTimeout(20) { awaitCancellation() } }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH, deadline = 5000), null, r)
        assertEquals("plugin_error", CsWire.errorOf(r.await())!!.first)
    }

    @Test fun `only ABI drift errors are incompatible`() {
        val s = bind(true); val svc = service!!
        val cases = listOf<Pair<Throwable, String>>(
            NoSuchFieldError("f") to "incompatible", NoClassDefFoundError("c") to "incompatible",
            AbstractMethodError("m") to "incompatible", IncompatibleClassChangeError("i") to "incompatible",
            ExceptionInInitializerError("e") to "plugin_error", UnsatisfiedLinkError("u") to "plugin_error",
        )
        cases.forEachIndexed { i, (t, code) ->
            svc.runOp = { _, _, _ -> throw t }
            val r = FakeReply(); s.call(req(CsWire.Op.SEARCH, "k$i"), null, r)
            assertEquals(t.toString(), code, CsWire.errorOf(r.await())!!.first)
        }
    }

    @Test fun `listPlugins accepts a non-plugin id, other ops need the cloudstream prefix`() {
        val s = bind(true)
        val r = FakeReply(); s.call(req(CsWire.Op.LIST, plugin = "kino"), null, r)
        assertNotNull(JSONObject(r.await()).optJSONArray("plugins"))
        val r2 = FakeReply(); s.call(req(CsWire.Op.SEARCH, "c2", plugin = "kino"), null, r2)
        assertEquals("bad_request", CsWire.errorOf(r2.await())!!.first)
    }

    @Test fun `url queries and fragments never reach the done message`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> throw java.io.IOException("GET https://h.example/p?token=SECRET&a=1 failed, see https://h.example/x#frag") }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, r)
        val msg = CsWire.errorOf(r.await())!!.second
        assertTrue(msg, !msg.contains("SECRET") && !msg.contains("frag") && msg.contains("https://h.example/p"))
    }

    @Test fun `hello reports the runtime kotlin version`() {
        val s = bind(true)
        assertEquals(KotlinVersion.CURRENT.toString(), JSONObject(s.hello(1, FakeHost())).getString("kotlinVersion"))
    }

    @Test fun `cancel from an untrusted caller is a no-op`() {
        val s = bind(true); val svc = service!!
        val started = CountDownLatch(1)
        svc.runOp = { _, _, _ -> started.countDown(); awaitCancellation() }
        val r = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, r)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        svc.callerCheck = { false }
        s.cancel("c1"); Thread.sleep(200)
        assertTrue(r.dones.isEmpty())
        svc.callerCheck = { true }; s.cancel("c1"); r.await()
    }

    @Test fun `onUnbind cancels every running call once and clears the host`() {
        val s = bind(true); val svc = service!!
        val started = CountDownLatch(2)
        svc.runOp = { _, _, _ -> started.countDown(); awaitCancellation() }
        s.hello(1, FakeHost())
        val a = FakeReply(); val b = FakeReply()
        s.call(req(CsWire.Op.SEARCH, "a1"), null, a); s.call(req(CsWire.Op.SEARCH, "b1"), null, b)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        svc.onUnbind(Intent())
        assertEquals("cancelled", CsWire.errorOf(a.await())!!.first)
        assertEquals("cancelled", CsWire.errorOf(b.await())!!.first)
        Thread.sleep(200)
        assertEquals(1, a.dones.size); assertEquals(1, b.dones.size)
        assertNull(HostLink.host)
    }

    private fun closed(p: ParcelFileDescriptor) = try { p.fd; false } catch (_: IllegalStateException) { true }

    @Test fun `the payload is closed on refusal and after a call`() {
        val s = bind(true); val svc = service!!
        val p1 = ParcelFileDescriptor.createPipe()[0]
        val r1 = FakeReply(); s.call(req(CsWire.Op.SEARCH, plugin = "evil"), p1, r1); r1.await()
        assertTrue(closed(p1))
        svc.runOp = { _, _, _ -> "{}" }
        val p2 = ParcelFileDescriptor.createPipe()[0]
        val r2 = FakeReply(); s.call(req(CsWire.Op.SEARCH, "c2"), p2, r2); r2.await(); Thread.sleep(100)
        assertTrue(closed(p2))
    }

    @Test fun `a duplicate callId is refused and does not disturb the running call`() {
        val s = bind(true); val svc = service!!
        val started = CountDownLatch(1)
        svc.runOp = { _, _, _ -> started.countDown(); awaitCancellation() }
        val a = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, a)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val b = FakeReply(); s.call(req(CsWire.Op.SEARCH), null, b)
        assertEquals("bad_request", CsWire.errorOf(b.await())!!.first)
        assertTrue(a.dones.isEmpty())
        s.cancel("c1"); a.await()
    }

    /** Blocks its thread (no suspension point, like Jsoup on a slow socket), then reports a link and returns. */
    private class BlockingApi(val release: CountDownLatch) : com.lagradost.cloudstream3.MainAPI() {
        override var name = "Blocking"
        override var mainUrl = "https://blocking.example"
        val returned = CountDownLatch(1)
        override suspend fun loadLinks(
            data: String, isCasting: Boolean, subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
            callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
        ): Boolean {
            release.await(10, TimeUnit.SECONDS)
            callback(com.lagradost.cloudstream3.utils.newExtractorLink("s", "n", "https://cdn.example/v.mp4",
                com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO))
            returned.countDown()
            return true
        }
    }

    @Test fun `a plugin blocked past the deadline is answered timeout on time, and its late return sends nothing`() {
        val s = bind(true); val svc = service!!
        val release = CountDownLatch(1)
        val api = BlockingApi(release)
        svc.runOp = { req, _, emit -> com.kino.csbridge.ops.ProviderOps.run(req, emit) { listOf(api) } }
        val r = FakeReply()
        val t0 = System.nanoTime()
        s.call(CsWire.opRequest(CsWire.Op.LOAD_LINKS, "c1", "cloudstream-x", 300, JSONObject().put("provider", 0).put("data", "d")), null, r)
        assertEquals("timeout", CsWire.errorOf(r.await())!!.first)
        assertTrue("done took too long", (System.nanoTime() - t0) / 1_000_000 < 2_000)
        release.countDown()
        assertTrue(api.returned.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, r.dones.size)
        assertTrue(r.events.isEmpty())
    }

    /** search() blocks its thread on [release] (no suspension point); [entered] counts calls that started. */
    private class StuckSearchApi(val release: CountDownLatch) : com.lagradost.cloudstream3.MainAPI() {
        override var name = "Stuck"
        val entered = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun search(query: String, page: Int): com.lagradost.cloudstream3.SearchResponseList? {
            entered.incrementAndGet()
            release.await(20, TimeUnit.SECONDS)
            return com.lagradost.cloudstream3.newSearchResponseList(emptyList())
        }
    }

    private class QuickApi : com.lagradost.cloudstream3.MainAPI() {
        override var name = "Quick"
        override suspend fun search(query: String, page: Int) =
            com.lagradost.cloudstream3.newSearchResponseList(listOf(newMovieSearchResponse("hit", "https://q.example/1")))
    }

    /** Polls [cond] every 20 ms for at most [ms]; fails with [what] if it never holds. */
    private fun waitUntil(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) { assertTrue(what, System.nanoTime() < end); Thread.sleep(20) }
    }

    private fun search(plugin: String, callId: String, deadline: Long) =
        CsWire.opRequest(CsWire.Op.SEARCH, callId, plugin, deadline, JSONObject().put("provider", 0).put("query", "x"))

    @Test fun `more stuck plugins than the plugin pool still leave every done on time, and a stuck plugin turns busy`() {
        val pool = 4
        com.kino.csbridge.ops.PluginWork.dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(pool)
        val s = bind(true); val svc = service!!
        val release = CountDownLatch(1)
        val stuck = StuckSearchApi(release)
        val quick = QuickApi()
        svc.runOp = { req, _, emit -> com.kino.csbridge.ops.ProviderOps.run(req, emit) { listOf(if (req.pluginId == "cloudstream-ok") quick else stuck) } }
        try {
            // 6 stuck ops on 6 plugins: the pool (4) fills, two wait in its queue. Every one is answered at its deadline.
            val replies = (1..6).map { i -> FakeReply().also { s.call(search("cloudstream-s$i", "s$i", 300), null, it) } }
            val t0 = System.nanoTime()
            replies.forEach { assertEquals("timeout", CsWire.errorOf(it.await())!!.first) }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
            // The healthy plugin cannot run on a full pool, but the service still answers it at its deadline.
            val h = FakeReply(); val t1 = System.nanoTime()
            s.call(search("cloudstream-ok", "h1", 300), null, h)
            assertEquals("timeout", CsWire.errorOf(h.await())!!.first)
            assertTrue((System.nanoTime() - t1) / 1_000_000 < 1_500)

            // One plugin with 3 leaked tasks is busy: answered at once, its code not started.
            release.countDown()
            waitUntil("the first stuck tasks never returned") { (1..6).all { com.kino.csbridge.ops.PluginWork.leakedCount("cloudstream-s$it") == 0 } }
            val release2 = CountDownLatch(1)
            val stuck2 = StuckSearchApi(release2)
            svc.runOp = { req, _, emit -> com.kino.csbridge.ops.ProviderOps.run(req, emit) { listOf(if (req.pluginId == "cloudstream-ok") quick else stuck2) } }
            (1..3).map { i -> FakeReply().also { s.call(search("cloudstream-b", "b$i", 200), null, it) } }.forEach { it.await() }
            assertEquals(3, com.kino.csbridge.ops.PluginWork.leakedCount("cloudstream-b"))
            val busy = FakeReply(); val t2 = System.nanoTime()
            s.call(search("cloudstream-b", "b4", 5_000), null, busy)
            assertEquals(CsWire.Err.TIMEOUT to "busy", CsWire.errorOf(busy.await()))
            assertTrue((System.nanoTime() - t2) / 1_000_000 < 500)
            assertEquals(3, stuck2.entered.get())
            // The healthy plugin, with a free slot, gets its real answer.
            val ok = FakeReply(); s.call(search("cloudstream-ok", "h2", 3_000), null, ok)
            assertEquals("hit", JSONObject(ok.await()).getJSONArray("items").getJSONObject(0).getString("name"))
            // Once the stuck code returns, the plugin is no longer busy.
            release2.countDown()
            waitUntil("cloudstream-b stayed busy") { com.kino.csbridge.ops.PluginWork.leakedCount("cloudstream-b") == 0 }
        } finally {
            release.countDown()
        }
    }

    @Test fun `a done refused with DeadObjectException on a live binder is retried once as too_large`() {
        val s = bind(true); val svc = service!!
        svc.runOp = { _, _, _ -> "{}" }
        val calls = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(2)
        val r = object : IReply.Stub() {
            override fun event(e: String) {}
            override fun done(j: String) { calls += j; latch.countDown(); if (calls.size == 1) throw android.os.DeadObjectException() }
        }
        s.call(req(CsWire.Op.SEARCH), null, r)
        assertTrue(latch.await(5, TimeUnit.SECONDS)); Thread.sleep(200)
        assertEquals(2, calls.size)
        assertEquals("{}", calls[0]); assertEquals("too_large", CsWire.errorOf(calls[1])!!.first)
    }

    @Test fun `the result cap leaves room for two dones in binder's oneway space`() {
        // 2 bytes a char in a parcel; about 508 KB of async space for every oneway call to Kino.
        assertTrue(BridgeService.MAX_RESULT_CHARS * 2 * 2 < 508 * 1024)
    }
}
