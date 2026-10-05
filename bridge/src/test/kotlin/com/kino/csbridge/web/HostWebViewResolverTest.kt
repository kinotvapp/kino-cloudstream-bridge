package com.kino.csbridge.web

import android.os.ParcelFileDescriptor
import com.kino.csbridge.CallContext
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.kino.csbridge.contract.IReply
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class HostWebViewResolverTest {
    /** A fake Kino: records every webView request and answers with [answer] (null = never answers). */
    private class FakeHost(@Volatile var answer: ((JSONObject) -> String?)?) : IHost.Stub() {
        val requests = CopyOnWriteArrayList<JSONObject>()
        override fun request(metaJson: String?, requestBody: ParcelFileDescriptor?, responseSink: ParcelFileDescriptor?, reply: IHttpReply?) =
            error("no HTTP expected here")
        override fun webView(requestJson: String, reply: IReply) {
            val o = JSONObject(requestJson)
            requests += o
            val a = answer?.invoke(o) ?: return
            thread { Thread.sleep(20); reply.done(a) } // oneway: Kino answers later, from another thread
        }
        override fun storage(opJson: String?): String = CsWire.error(CsWire.Err.UNAVAILABLE, "none")
    }

    private lateinit var host: FakeHost

    private fun serve(answer: ((JSONObject) -> String?)?) { host = FakeHost(answer); HostWebViewResolver.host = { host } }

    private fun ctx(deadlineMs: Long = 60_000, callId: String = "call-1") =
        CallContext(callId, "cloudstream-x", CallContext.deadlineFromNow(deadlineMs))

    private fun <T> inCall(c: CallContext = ctx(), block: suspend () -> T): T =
        runBlocking(c + CallContext.threadLocal.asContextElement(c)) { block() }

    private fun found(url: String, headers: Map<String, String> = emptyMap(), cookies: String = "", ua: String = "UA-kino", finalUrl: String = "https://a.example/") =
        JSONObject().put("request", JSONObject().put("url", url).put("headers", JSONObject(headers as Map<*, *>)))
            .put("cookies", cookies).put("userAgent", ua).put("finalUrl", finalUrl).toString()

    @Before fun setUp() {
        WebCookies.clearForTest()
        WebViewResolver.webViewUserAgent = null
    }

    @After fun tearDown() {
        HostWebViewResolver.host = { com.kino.csbridge.HostLink.host }
        HostWebViewResolver.callMarginMs = 1_500L
        HostWebViewResolver.pageOverheadMs = 4_000L
        HostWebViewResolver.replySlackMs = 2_000L
        CallContext.threadLocal.set(null)
    }

    // ---- request shape and limits ---------------------------------------------------------------------------

    @Test fun `request carries call, plugin, url, match, clamped timeout, first-load headers and wantCookies`() {
        serve { found("https://cdn.example/v/master.m3u8?t=1") }
        inCall {
            WebViewResolver(Regex("""master\.m3u8""")).resolveUsingWebView(
                "https://a.example/embed?id=7", referer = "https://r.example/", headers = mapOf("X-Req" to "1", "Cookie" to "s=1"),
            )
        }
        val o = host.requests.single()
        assertEquals(setOf("callId", "pluginId", "url", "match", "timeoutMs", "headers", "wantCookies"), o.keys().asSequence().toSet())
        assertEquals("call-1", o.getString("callId"))
        assertEquals("cloudstream-x", o.getString("pluginId"))
        assertEquals("https://a.example/embed?id=7", o.getString("url"))
        assertEquals("""master\.m3u8""", o.getString("match"))
        assertEquals(25_000L, o.getLong("timeoutMs")) // upstream's 60 s default, cut to Kino's maximum
        assertTrue(o.getBoolean("wantCookies"))
        val h = o.getJSONObject("headers")
        assertEquals("1", h.getString("X-Req"))
        assertEquals("https://r.example/", h.getString("referer"))
        assertFalse("Kino refuses a Cookie first-load header", h.has("Cookie"))
    }

    @Test fun `timeout is clamped to Kino's 1 to 25 s and to what the call has left`() {
        assertEquals(25_000L, HostWebViewResolver.clampTimeout(60_000, Long.MAX_VALUE))
        assertEquals(1_000L, HostWebViewResolver.clampTimeout(0, Long.MAX_VALUE))
        assertEquals(10_000L, HostWebViewResolver.clampTimeout(10_000, 60_000))
        // 10 s left: 1.5 s for the plugin and 4 s of Kino's page overhead leave 4.5 s for the page.
        assertEquals(4_500L, HostWebViewResolver.clampTimeout(60_000, 10_000))
        assertNull(HostWebViewResolver.clampTimeout(60_000, 6_000))
    }

    @Test fun `too little time left, an http url or an oversized pattern make no IPC`() {
        serve { found("https://x.example/a.m3u8") }
        assertEquals(null to emptyList<Request>(), inCall(ctx(deadlineMs = 5_000)) { WebViewResolver(Regex("m3u8")).resolveUsingWebView("https://a.example/") })
        assertEquals(null to emptyList<Request>(), inCall { WebViewResolver(Regex("m3u8")).resolveUsingWebView("http://a.example/") })
        assertEquals(null to emptyList<Request>(), inCall { WebViewResolver(Regex("a".repeat(501))).resolveUsingWebView("https://a.example/") })
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `no CallContext means no IPC`() {
        serve { found("https://x.example/a.m3u8") }
        val r = runBlocking { WebViewResolver(Regex("m3u8")).resolveUsingWebView("https://a.example/") }
        assertEquals(null to emptyList<Request>(), r)
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `header filter keeps Kino's rules and at most 10`() {
        val many = (1..12).associate { "H$it" to "v" } + mapOf("Host" to "x", "Bad Name" to "v", "X-Nl" to "a\nb")
        val kept = HostWebViewResolver.headersForKino(mapOf("Host" to "x", "Bad Name" to "v", "X-Nl" to "a\nb") + many)
        assertEquals((1..10).map { "H$it" }, kept.keys.toList())
    }

    // ---- result mapping -------------------------------------------------------------------------------------

    @Test fun `answer maps to upstream's pair, runs the callback, keeps cookies per host and the WebView UA`() {
        serve { found("https://cdn.example/v/master.m3u8?t=1", headers = mapOf("Referer" to "https://a.example/"), cookies = "cf_clearance=abc; s=1") }
        val seen = CopyOnWriteArrayList<Request>()
        val (req, extra) = inCall {
            WebViewResolver(Regex("""master\.m3u8"""), additionalUrls = listOf(Regex("."))).resolveUsingWebView("https://a.example/embed") { seen += it; false }
        }
        assertEquals("https://cdn.example/v/master.m3u8?t=1", req!!.url.toString())
        assertEquals("GET", req.method)
        assertEquals("https://a.example/", req.header("Referer"))
        assertTrue(extra.isEmpty())
        assertEquals(listOf(req), seen.toList())
        assertEquals("UA-kino", WebViewResolver.webViewUserAgent)
        assertEquals(mapOf("cf_clearance" to "abc", "s" to "1"), WebCookies.get("cloudstream-x", "a.example"))
        assertNull("cookies are per plugin", WebCookies.get("cloudstream-other", "a.example"))
    }

    @Test fun `as an interceptor it proceeds with the request Kino found`() {
        serve { found("https://cdn.example/final.m3u8") }
        val chain = FakeChain(Request.Builder().url("https://a.example/page").build()) { ok(it) }
        val c = ctx()
        CallContext.threadLocal.set(c)
        WebViewResolver(Regex("final")).intercept(chain)
        assertEquals("https://cdn.example/final.m3u8", chain.sent.single().url.toString())
    }

    @Test fun `errors, malformed answers, a non-matching or non-https request all give nothing, never a throw`() {
        val answers = listOf(
            CsWire.error("busy", "otra página"),
            "not json",
            found("https://cdn.example/other.mp4"),
            found("http://cdn.example/a.m3u8"),
            JSONObject().put("request", JSONObject.NULL).put("cookies", "").toString(),
        )
        for (a in answers) {
            serve { a }
            assertEquals(a, null to emptyList<Request>(), inCall { WebViewResolver(Regex("m3u8")).resolveUsingWebView("https://a.example/") })
        }
    }

    @Test fun `no answer ends at the call's deadline with nothing`() {
        HostWebViewResolver.callMarginMs = 0; HostWebViewResolver.pageOverheadMs = 0
        serve(null)
        val t0 = System.nanoTime()
        val r = inCall(ctx(deadlineMs = 1_500)) { WebViewResolver(Regex("m3u8")).resolveUsingWebView("https://a.example/") }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(null to emptyList<Request>(), r)
        assertEquals(1, host.requests.size)
        assertTrue("waited $ms ms", ms in 1_200..4_000)
    }

    @Test fun `a cancelled op stops the wait at once`() {
        serve(null)
        val c = ctx()
        val job = Job().also { c.job = it }
        thread { Thread.sleep(300); job.cancel() }
        val t0 = System.nanoTime()
        val r = inCall(c) { WebViewResolver(Regex("m3u8")).resolveUsingWebView("https://a.example/") }
        assertEquals(null to emptyList<Request>(), r)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
    }

    // ---- CloudflareKiller -----------------------------------------------------------------------------------

    /** A site behind Cloudflare: 503 check page unless the request carries cf_clearance=[good] and Kino's UA. */
    private fun cfSite(good: String) = { r: Request ->
        val cookie = r.header("Cookie").orEmpty()
        if ("cf_clearance=$good" in cookie && r.header("User-Agent") == "UA-kino") ok(r, "content")
        else Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(503).message("check").header("Server", "cloudflare")
            .body("<html>checking</html>".toResponseBody("text/html".toMediaType())).build()
    }

    private fun cfAnswer(clearance: String?) = { _: JSONObject ->
        JSONObject().put("request", JSONObject.NULL).put("cookies", clearance?.let { "cf_clearance=$it; __cf_bm=z" } ?: "")
            .put("userAgent", "UA-kino").put("finalUrl", "https://a.example/").toString()
    }

    private fun intercept(killer: CloudflareKiller, chain: FakeChain): Response {
        val c = ctx()
        CallContext.threadLocal.set(c)
        try { return killer.intercept(chain) } finally { CallContext.threadLocal.set(null) }
    }

    @Test fun `CloudflareKiller solves once in cookie mode, retries once with cf_clearance and the WebView UA`() {
        serve(cfAnswer("ok"))
        val chain = FakeChain(Request.Builder().url("https://a.example/list?p=2").build(), cfSite("ok"))
        val r = intercept(CloudflareKiller(), chain)
        assertEquals(200, r.code)
        assertEquals("content", r.body.string())
        assertEquals(2, chain.sent.size) // the plain request, then one retry
        assertTrue(chain.sent[1].header("Cookie")!!.contains("cf_clearance=ok"))
        assertEquals("UA-kino", chain.sent[1].header("User-Agent"))
        val o = host.requests.single()
        assertFalse("cookie mode: no match", o.has("match"))
        assertEquals("https://a.example/list?p=2", o.getString("url"))
    }

    @Test fun `solved cookies are cached per host, so a new killer of the same plugin opens no page`() {
        serve(cfAnswer("ok"))
        intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/1").build(), cfSite("ok")))
        val chain = FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok"))
        val r = intercept(CloudflareKiller(), chain)
        assertEquals(200, r.code)
        assertEquals(1, chain.sent.size)
        assertEquals(1, host.requests.size)
        // Another host is solved on its own.
        intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://b.example/").build(), cfSite("ok")))
        assertEquals(2, host.requests.size)
    }

    @Test fun `stale cookies are dropped and solved once more`() {
        WebCookies.put("cloudstream-x", "a.example", mapOf("cf_clearance" to "old"))
        WebViewResolver.webViewUserAgent = "UA-kino"
        serve(cfAnswer("new"))
        val chain = FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("new"))
        val r = intercept(CloudflareKiller(), chain)
        assertEquals(200, r.code)
        assertEquals(2, chain.sent.size)
        assertEquals(1, host.requests.size)
        assertEquals("new", WebCookies.get("cloudstream-x", "a.example")!!["cf_clearance"])
    }

    @Test fun `a failed solve returns the check page without a second request`() {
        serve(cfAnswer(null))
        val chain = FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok"))
        val r = intercept(CloudflareKiller(), chain)
        assertEquals(503, r.code)
        assertEquals("<html>checking</html>", r.body.string())
        assertEquals(1, chain.sent.size)
        assertEquals(1, host.requests.size)
    }

    @Test fun `a response that is not a Cloudflare check passes through untouched`() {
        serve(cfAnswer("ok"))
        val chain = FakeChain(Request.Builder().url("https://a.example/").build()) { ok(it) }
        assertEquals(200, intercept(CloudflareKiller(), chain).code)
        assertEquals(1, chain.sent.size)
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `getCookieHeaders gives the WebView UA and this plugin's cookies`() {
        WebViewResolver.webViewUserAgent = "UA-kino"
        WebCookies.put("cloudstream-x", "a.example", mapOf("cf_clearance" to "ok"))
        CallContext.threadLocal.set(ctx())
        val h = CloudflareKiller().getCookieHeaders("https://a.example/x")
        assertEquals("UA-kino", h["user-agent"])
        assertTrue(h["Cookie"]!!.contains("cf_clearance=ok"))
    }

    // ---- never a WebView in this process ---------------------------------------------------------------------

    /** The jar (or directory) a class was loaded from, as its resource URL names it. */
    private fun origin(c: Class<*>): String = c.getResource(c.simpleName + ".class")!!.toString().substringBefore("!/")

    @Test fun `the WebViewResolver plugins load is the bridge's, the library's copy is gone`() {
        val res = "com/lagradost/cloudstream3/network/WebViewResolver.class"
        // Robolectric's sandbox loader lists its parent's entries again: count distinct origins.
        val copies = javaClass.classLoader!!.getResources(res).toList().map { it.toString() }.distinct()
        assertEquals(copies.toString(), 1, copies.size)
        val ours = origin(HostWebViewResolver::class.java)
        assertEquals(ours, origin(WebViewResolver::class.java))
        assertEquals(ours, origin(CloudflareKiller::class.java))
        assertFalse(ours, "library" in ours)
    }

    @Test fun `no class compiled into the bridge references WebView or CookieManager`() {
        val origin = origin(HostWebViewResolver::class.java)
        val forbidden = listOf("android/webkit/WebView", "android/webkit/CookieManager")
        val classes: List<Pair<String, ByteArray>> = if (origin.startsWith("jar:file:")) {
            java.util.zip.ZipFile(File(java.net.URI(origin.removePrefix("jar:")))).use { z ->
                z.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name to z.getInputStream(it).readBytes() }.toList()
            }
        } else {
            val root = File(java.net.URI(origin.substringBefore("com/kino/csbridge/")))
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.relativeTo(root).path to it.readBytes() }.toList()
        }
        assertTrue(classes.any { it.first.endsWith("com/lagradost/cloudstream3/network/WebViewResolver.class") })
        assertTrue(classes.size > 50)
        val offenders = classes.filter { (_, b) -> String(b, Charsets.ISO_8859_1).let { t -> forbidden.any { it in t } } }.map { it.first }
        assertEquals(emptyList<String>(), offenders)
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun ok(r: Request, body: String = "ok") = Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("text/plain".toMediaType())).build()

    private class FakeChain(private val request: Request, private val serve: (Request) -> Response) : Interceptor.Chain {
        val sent = CopyOnWriteArrayList<Request>()
        override fun request(): Request = request
        override fun proceed(request: Request): Response { sent += request; return serve(request) }
        override fun connection(): Connection? = null
        override fun call(): Call = error("unused")
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }
}
