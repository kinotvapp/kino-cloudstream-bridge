package com.kino.csbridge.net

import android.Manifest
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.kino.csbridge.contract.IReply
import com.lagradost.cloudstream3.UnsafeSSL
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.Interceptor
import okio.buffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class IpcInterceptorTest {
    /** What the fake Kino answers for one URL; [reply] = false means it never answers. */
    data class Route(
        val status: Int = 200, val headers: List<Pair<String, String>> = emptyList(), val body: ByteArray = ByteArray(0),
        val finalUrl: String? = null, val error: String? = null, val reply: Boolean = true, val dieAfterSend: Boolean = false,

    )

    /** A fake Kino serving from [routes]; records each meta and request body it received. */
    private class FakeHost(val routes: Map<String, Route>) : IHost.Stub() {
        val metas = CopyOnWriteArrayList<CsWire.HttpMeta>()
        val bodies = CopyOnWriteArrayList<ByteArray?>()
        @Volatile var alive = true
        @Volatile var deadOnSend = false

        override fun isBinderAlive(): Boolean = alive

        override fun request(metaJson: String, requestBodyIn: ParcelFileDescriptor?, responseSinkIn: ParcelFileDescriptor, reply: IHttpReply) {
            if (deadOnSend) throw DeadObjectException()
            // In process there is no parcel: duplicate the descriptors, as Binder does for a real Kino.
            val requestBody = requestBodyIn?.dup()
            val responseSink = responseSinkIn.dup()
            val meta = CsWire.parseHttpMeta(metaJson)!!
            metas += meta
            // Robolectric's pipe is a temp file shared by both ends, not a blocking OS pipe: a read before the writer is
            // done sees EOF. So the fake lets the request body writer finish first, and writes a response body before
            // answering (a real Kino answers first and streams after; the interceptor reads only after the answer).
            thread {
                if (requestBody != null) Thread.sleep(300)
                bodies += requestBody?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } }
                val r = routes[meta.url] ?: Route(status = 404)
                when {
                    r.dieAfterSend -> { alive = false; runCatching { responseSink.close() } }
                    !r.reply -> {} // silent: keeps the sink open, never answers
                    r.error != null -> { reply.response(CsWire.error(r.error, "boom https://x.example/a?token=secret")); responseSink.close() }
                    else -> {
                        ParcelFileDescriptor.AutoCloseOutputStream(responseSink).use { it.write(r.body) }
                        reply.response(JSONObject().put("status", r.status).put("message", "M").put("headers", CsWire.headersToJson(r.headers))
                            .put("finalUrl", r.finalUrl ?: meta.url).toString())
                    }
                }
            }
        }
        override fun webView(requestJson: String?, reply: IReply?) {}
        override fun storage(opJson: String?): String = "{}"
    }

    private lateinit var host: FakeHost

    private fun serve(vararg routes: Pair<String, Route>): FakeHost = FakeHost(routes.toMap()).also { host = it; HostLink.host = it }

    private fun <T> inCall(ctx: CallContext = CallContext("call-1", "cloudstream-x", CallContext.deadlineFromNow(10_000)), block: suspend () -> T): T =
        runBlocking(ctx + CallContext.threadLocal.asContextElement(ctx)) { block() }

    @Before fun setUp() { NetworkShim.install() }
    @After fun tearDown() { HostLink.host = null }

    @Test fun `get returns body and repeated set-cookie headers`() {
        serve("https://a.example/p?q=1" to Route(
            headers = listOf("Set-Cookie" to "a=1", "Content-Type" to "text/html", "Set-Cookie" to "b=2"),
            body = "hello".toByteArray(), finalUrl = "https://b.example/final",
        ))
        val r = inCall { app.get("https://a.example/p?q=1") }
        assertEquals(200, r.code)
        assertEquals("hello", r.text)
        assertEquals(listOf("a=1", "b=2"), r.okhttpResponse.headers("Set-Cookie"))
        val h = r.okhttpResponse.headers
        // Kino's headers in order; NiceHttp's own CacheInterceptor (a network interceptor) then adds Cache-Control.
        assertEquals(listOf("Set-Cookie", "Content-Type", "Set-Cookie"), (0 until h.size).map { h.name(it) }.take(3))
        assertEquals("https://b.example/final", r.url)
        val m = host.metas.single()
        assertEquals("call-1", m.callId); assertEquals("cloudstream-x", m.pluginId); assertEquals("GET", m.method)
        assertTrue(m.followRedirects); assertFalse(m.hasBody)
        assertTrue("deadline bounds the timeout", m.timeoutMs in 1..10_000)
    }

    @Test fun `allowRedirects=false reaches Kino as followRedirects=false`() {
        serve("https://a.example/r" to Route(status = 302, headers = listOf("Location" to "https://b.example/")))
        val r = inCall { app.get("https://a.example/r", allowRedirects = false) }
        assertEquals(302, r.code)
        assertFalse(host.metas.single().followRedirects)
    }

    @OptIn(UnsafeSSL::class)
    @Test fun `insecureApp goes through Kino too`() {
        serve("https://a.example/i" to Route(body = "x".toByteArray()))
        assertEquals("x", inCall { insecureApp.get("https://a.example/i", verify = false) }.text)
        assertEquals(1, host.metas.size)
    }

    @Test fun `post body arrives through the pipe`() {
        serve("https://a.example/post" to Route(body = "ok".toByteArray()))
        val payload = """{"k":"v"}"""
        val r = inCall { app.post("https://a.example/post", requestBody = payload.toRequestBody("application/json".toMediaType())) }
        assertEquals("ok", r.text)
        val m = host.metas.single()
        assertTrue(m.hasBody); assertEquals("POST", m.method)
        assertTrue(m.headers.any { it.first.equals("Content-Type", true) && it.second.startsWith("application/json") })
        assertArrayEquals(payload.toByteArray(), host.bodies.single())
    }

    @Test fun `request body over 1 MB is refused before sending`() {
        serve()
        val big = ByteArray(IpcInterceptor.MAX_REQUEST_BODY + 1)
        assertIo { inCall { app.post("https://a.example/big", requestBody = big.toRequestBody()) } }
        assertTrue(host.metas.isEmpty())
    }

    @Test fun `response body over 5 MB fails while reading`() {
        serve("https://a.example/huge" to Route(body = ByteArray(IpcInterceptor.MAX_RESPONSE_BODY + 1)))
        assertIo { inCall { app.get("https://a.example/huge").okhttpResponse.body.bytes() } }
    }

    @Test fun `request outside a call is refused`() {
        serve("https://a.example/" to Route())
        val e = assertIo { runBlocking { app.get("https://a.example/") } }
        assertTrue(e.message!!.contains("outside a Kino call"))
        assertTrue(host.metas.isEmpty())
    }

    @Test fun `no host bound is an IOException`() {
        HostLink.host = null
        assertIo { inCall { app.get("https://a.example/") } }
    }

    @Test fun `Kino dying mid-request surfaces IOException within the deadline`() {
        serve("https://a.example/die" to Route(dieAfterSend = true))
        val t0 = System.nanoTime()
        assertTrue(assertIo { inCall { app.get("https://a.example/die") } }.message!!.contains("Kino died"))
        assertTrue("took ${ms(t0)} ms", ms(t0) < 3_000)
    }

    @Test fun `dead binder on send is an IOException`() {
        serve().deadOnSend = true
        assertTrue(assertIo { inCall { app.get("https://a.example/") } }.message!!.contains("DeadObjectException"))
    }

    @Test fun `silent Kino ends at the call deadline`() {
        serve("https://a.example/silent" to Route(reply = false))
        val t0 = System.nanoTime()
        val e = assertIo { inCall(CallContext("call-2", "cloudstream-x", CallContext.deadlineFromNow(700))) { app.get("https://a.example/silent") } }
        assertTrue(e.message!!.contains("deadline"))
        assertTrue("took ${ms(t0)} ms", ms(t0) in 500..3_000)
    }

    @Test fun `cancelling the op stops the wait`() {
        serve("https://a.example/silent" to Route(reply = false))
        val ctx = CallContext("call-3", "cloudstream-x", CallContext.deadlineFromNow(60_000))
        val job = Job().also { ctx.job = it }
        thread { Thread.sleep(300); job.cancel() }
        val t0 = System.nanoTime()
        // Blocking OkHttp from the op's thread: the interceptor itself must notice the cancellation.
        val e = assertIo { inCall(ctx) { okhttp().newCall(Request.Builder().url("https://a.example/silent").build()).execute() } }
        assertTrue(e.message!!.contains("cancel"))
        // Expected, so quiet: no stack trace (upstream's logError prints one per request), one line when printed.
        assertTrue(e is QuietIOException)
        val printed = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString().trim()
        assertEquals(1, printed.lines().size)
        assertTrue("took ${ms(t0)} ms", ms(t0) < 3_000)
    }

    @Test fun `a network failure in Kino is worded as a network error, not a refusal`() {
        serve("https://a.example/n" to Route(error = CsWire.Err.NETWORK))
        val e = assertIo { inCall { app.get("https://a.example/n") } }
        assertTrue(e.message!!, e.message!!.startsWith("bridge: network error: "))
        assertFalse(e.message!!.contains("refused"))
        assertFalse(e.message!!.contains("secret"))
    }

    @Test fun `only Kino's gates read as refused`() {
        assertEquals("network error: SocketException", IpcInterceptor.failureText(CsWire.Err.NETWORK, "SocketException"))
        assertEquals("timed out: SocketTimeoutException", IpcInterceptor.failureText(CsWire.Err.TIMEOUT, "SocketTimeoutException"))
        assertEquals("Kino unavailable: host stopped", IpcInterceptor.failureText(CsWire.Err.UNAVAILABLE, "host stopped"))
        for (gate in listOf(CsWire.Err.REFUSED, CsWire.Err.BLOCKED, CsWire.Err.NOT_ALLOWED, CsWire.Err.BAD_REQUEST, CsWire.Err.BUDGET)) {
            assertTrue(IpcInterceptor.failureText(gate, "x").startsWith("Kino refused the request: $gate"))
        }
        assertEquals("network error: at https://h.example/a", IpcInterceptor.failureText(CsWire.Err.NETWORK, "at https://h.example/a/b?token=s"))
    }

    @Test fun `Kino cancelling the request is quiet`() {
        serve("https://a.example/c" to Route(error = CsWire.Err.CANCELLED))
        val e = assertIo { inCall { app.get("https://a.example/c") } }
        assertTrue(e is QuietIOException)
    }

    @Test fun `Kino error reply is an IOException without the query`() {
        serve("https://a.example/e" to Route(error = CsWire.Err.REFUSED))
        val e = assertIo { inCall { app.get("https://a.example/e") } }
        assertTrue(e.message!!.contains("refused"))
        assertFalse(e.message!!.contains("secret"))
    }

    @Test fun `redirect flag is read from the bundled OkHttp`() {
        // FAILS LOUDLY when an OkHttp bump breaks the reflective read: allowRedirects=false would then silently follow.
        val req = Request.Builder().url("https://a.example/").build()
        assertEquals(false, RedirectFlag.read(OkHttpClient.Builder().followRedirects(false).build().newCall(req)))
        assertEquals(true, RedirectFlag.read(OkHttpClient().newCall(req)))
    }

    @Test fun `direct OkHttpClient() has no network`() {
        // Robolectric sanity check only (the JVM has a network): the app holds no INTERNET permission. Task 9 proves it on a device.
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals(PackageManager.PERMISSION_DENIED, ctx.checkSelfPermission(Manifest.permission.INTERNET))
        val requested = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.INTERNET in requested)
    }

    @Test fun `install puts the shim on both clients`() {
        @OptIn(UnsafeSSL::class)
        val clients = listOf(app.baseClient, insecureApp.baseClient)
        clients.forEach { c -> assertNotNull(c.interceptors.singleOrNull { it is IpcInterceptor }) }
    }

    @Test fun `a plugin interceptor's header reaches Kino`() {
        serve("https://a.example/h" to Route())
        val add = Interceptor { it.proceed(it.request().newBuilder().header("X-Plugin", "1").build()) }
        inCall { app.get("https://a.example/h", interceptor = add) }
        assertTrue(host.metas.single().headers.contains("X-Plugin" to "1"))
    }

    @Test fun `a retrying interceptor makes one IPC per proceed`() {
        serve("https://a.example/retry" to Route(body = "x".toByteArray()))
        val retry = Interceptor { chain -> chain.proceed(chain.request()).close(); chain.proceed(chain.request()) }
        assertEquals("x", inCall { app.get("https://a.example/retry", interceptor = retry) }.text)
        assertEquals(2, host.metas.size)
    }

    @Test fun `the call context is set inside plugin interceptors and restored after`() {
        serve("https://a.example/ctx" to Route())
        val seen = CopyOnWriteArrayList<Any?>()
        var thread: Thread? = null
        val probe = Interceptor { chain -> thread = Thread.currentThread(); seen += CallContext.current(); chain.proceed(chain.request()) }
        val ctx = CallContext("call-ctx", "cloudstream-x", CallContext.deadlineFromNow(10_000))
        inCall(ctx) { app.get("https://a.example/ctx", interceptor = probe) }
        assertEquals(listOf<Any?>(ctx), seen.toList())
        assertTrue("NiceHttp runs interceptors on OkHttp's dispatcher", thread !== Thread.currentThread())
    }

    @Test fun `withReadTimeout from an interceptor bounds the timeout sent to Kino`() {
        serve("https://a.example/t" to Route())
        val tight = Interceptor { it.withReadTimeout(2, java.util.concurrent.TimeUnit.SECONDS).proceed(it.request()) }
        inCall { app.get("https://a.example/t", interceptor = tight) }
        assertTrue(host.metas.single().timeoutMs in 1..2_000)
    }

    @Test fun `network interceptors run after the application ones`() {
        serve("https://a.example/n" to Route(headers = listOf("X-From-Kino" to "1")))
        val order = CopyOnWriteArrayList<String>()
        val client = app.baseClient.newBuilder()
            .addNetworkInterceptor { c -> order += "network"; c.proceed(c.request()).newBuilder().header("X-Network", "1").build() }
            .addInterceptor { c -> order += "app"; c.proceed(c.request()) }
            .build()
        val r = inCall { client.newCall(Request.Builder().url("https://a.example/n").build()).execute() }
        assertEquals(listOf("app", "network"), order.toList())
        assertEquals("1", r.header("X-Network")); assertEquals("1", r.header("X-From-Kino"))
    }

    @Test fun `CloudflareKiller compiled in the bridge runs and its own call reaches Kino`() {
        serve("https://a.example/cf" to Route(body = "solved".toByteArray()))
        WebViewResolver.webViewUserAgent = "UA-test" // no WebView on the JVM
        val killer = CloudflareKiller().apply { savedCookies["a.example"] = mapOf("cf_clearance" to "ok") }
        assertEquals("solved", inCall { app.get("https://a.example/cf", interceptor = killer) }.text)
        // Its saved-cookie path starts a new app.baseClient call from the dispatcher thread: same Kino call.
        val m = host.metas.single()
        assertEquals("call-1", m.callId)
        assertTrue(m.headers.any { it.first.equals("cookie", true) && it.second.contains("cf_clearance=ok") })
        assertTrue(m.headers.any { it.first.equals("user-agent", true) && it.second == "UA-test" })
    }

    @Test fun `a body Kino closed with an error is an IOException, not a truncated success`() {
        // Robolectric's file-backed pipe has no comm channel, so Kino's closeWithError can't travel through it: the EOF
        // hook is tested against a read end whose checkError reports what a real reliable pipe would. Task 9 checks
        // the real closeWithError on a device.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write("partial".toByteArray()) }
        val closedWithError = object : ParcelFileDescriptor(pipe[0]) {
            override fun checkError() { throw IOException("Remote error: body over cap") }
        }
        assertIo { IpcInterceptor.CheckedSource(closedWithError).buffer().readByteArray() }
        // A cleanly closed pipe reads to the end.
        val ok = ParcelFileDescriptor.createReliablePipe()
        ParcelFileDescriptor.AutoCloseOutputStream(ok[1]).use { it.write("whole".toByteArray()) }
        assertEquals("whole", IpcInterceptor.CheckedSource(ok[0]).buffer().readUtf8())
    }

    @Test fun `URLs in Kino's messages are cut to host and first segment`() {
        assertEquals("boom https://h.example/a end", IpcInterceptor.scrubUrls("boom https://h.example/a/b/c?token=s#f end"))
        assertEquals("https://h.example", IpcInterceptor.scrubUrls("https://h.example?x=1"))
        assertEquals("at HTTPS://h.example/a", IpcInterceptor.scrubUrls("at HTTPS://user:tok3n@h.example/a/b?q=1"))
        assertEquals("Http://h.example", IpcInterceptor.scrubUrls("Http://u@h.example#frag"))
    }

    /** Records whether our cleanup closed a pipe end (status packet) or released it through writeToParcel (silent). */
    private class Recording(inner: ParcelFileDescriptor) : ParcelFileDescriptor(inner) {
        @Volatile var closed = false
        @Volatile var parcelFlags: Int? = null
        @Volatile private var inParcel = false
        // Robolectric's shadow writeToParcel calls close() itself (AOSP closes with Status.SILENCE instead): only a
        // close() from outside writeToParcel is ours.
        override fun close() { if (!inParcel) closed = true; super.close() }
        override fun writeToParcel(out: Parcel, flags: Int) {
            parcelFlags = flags; inParcel = true
            try { super.writeToParcel(out, flags) } finally { inParcel = false }
        }
    }

    @Test fun `our copy of the sink's write end is released without a status, never closed`() {
        // On a device a close() would put Status.OK on the comm channel ahead of Kino's closeWithError or death, and
        // checkError() reads only the first packet. Robolectric can't show the packet; Task 9 checks it on a device.
        serve("https://a.example/s" to Route(body = "ok".toByteArray()))
        val writeEnds = CopyOnWriteArrayList<Recording>()
        val shim = IpcInterceptor(pipes = {
            ParcelFileDescriptor.createReliablePipe().let { arrayOf(it[0], Recording(it[1]).also { r -> writeEnds += r }) }
        })
        val client = OkHttpClient.Builder().addInterceptor(shim).eventListenerFactory(CallCapture).build()
        assertEquals("ok", inCall { client.newCall(Request.Builder().url("https://a.example/s").build()).execute().body.string() })
        val w = writeEnds.single()
        assertFalse("close() would send a status packet", w.closed)
        assertEquals(Parcelable.PARCELABLE_WRITE_RETURN_VALUE, w.parcelFlags)
    }

    @Test fun `a client listing the shim twice still makes one IPC`() {
        serve("https://a.example/twice" to Route())
        val add = Interceptor { it.proceed(it.request().newBuilder().header("X-After", "1").build()) }
        val client = app.baseClient.newBuilder().addInterceptor(IpcInterceptor()).addInterceptor(add).build()
        inCall { client.newCall(Request.Builder().url("https://a.example/twice").build()).execute().close() }
        assertTrue(host.metas.single().headers.contains("X-After" to "1"))
    }

    @Test fun `a crashing plugin interceptor is an IOException, not a dead process`() {
        serve("https://a.example/crash" to Route())
        val npe = Interceptor { it.connection()!!; it.proceed(it.request()) }
        assertTrue(assertIo { inCall { app.get("https://a.example/crash", interceptor = npe) } }.message!!.contains("NullPointerException"))
        val ise = Interceptor { error("plugin bug") }
        assertIo { inCall { app.get("https://a.example/crash", interceptor = ise) } }
        assertTrue(host.metas.isEmpty())
    }

    private fun okhttp() = app.baseClient

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun assertIo(block: () -> Unit): IOException {
        try { block() } catch (e: IOException) { return e }
        fail("expected IOException"); throw AssertionError()
    }
}
