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

    @After fun tearDown() { HostLink.host = null }

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
}
