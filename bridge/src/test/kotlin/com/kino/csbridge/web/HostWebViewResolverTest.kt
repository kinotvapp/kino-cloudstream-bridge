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
    private open class FakeHost(@Volatile var answer: ((JSONObject) -> String?)?, val delayMs: Long = 20) : IHost.Stub() {
        val requests = CopyOnWriteArrayList<JSONObject>()
        override fun request(metaJson: String?, requestBody: ParcelFileDescriptor?, responseSink: ParcelFileDescriptor?, reply: IHttpReply?) =
            error("no HTTP expected here")
        override fun webView(requestJson: String, reply: IReply) {
            val o = JSONObject(requestJson)
            assertTrue("not a request Kino accepts: $requestJson", CsWire.parseWebViewRequest(requestJson) != null)
            requests += o
            val a = answer?.invoke(o) ?: return
            thread { Thread.sleep(delayMs); reply.done(a) } // oneway: Kino answers later, from another thread
        }
        override fun storage(opJson: String?): String = CsWire.error(CsWire.Err.UNAVAILABLE, "none")
    }

    private lateinit var host: FakeHost

    private fun serve(answer: ((JSONObject) -> String?)?) = serveAfter(20, answer)

    private fun serveAfter(delayMs: Long, answer: ((JSONObject) -> String?)?) { host = FakeHost(answer, delayMs); HostWebViewResolver.host = { host } }

    private fun ctx(deadlineMs: Long = 60_000, callId: String = "call-1") =
        CallContext(callId, "cloudstream-x", CallContext.deadlineFromNow(deadlineMs))

    private fun <T> inCall(c: CallContext = ctx(), block: suspend () -> T): T =
        runBlocking(c + CallContext.threadLocal.asContextElement(c)) { block() }

    /** Kino's v2 reply. */
    private fun reply(
        requests: List<CsWire.CapturedRequest> = emptyList(), cookies: Map<String, String> = emptyMap(), ua: String = "UA-kino",
        finalUrl: String = "https://a.example/", timedOut: Boolean = false,
    ) = CsWire.webViewReply(CsWire.WebViewReply(requests, cookies, ua, finalUrl, timedOut))

    private fun found(
        url: String, headers: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap(), ua: String = "UA-kino",
        finalUrl: String = "https://a.example/", method: String = "GET",
    ) = reply(listOf(CsWire.CapturedRequest(url, method, headers)), cookies, ua, finalUrl)

    @Before fun setUp() {
        WebCookies.clearForTest()
        CloudflareKiller.resetForTest()
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

    @Test fun `request carries call, plugin, url, match, clamped timeout and first-load headers`() {
        serve { found("https://cdn.example/v/master.m3u8?t=1") }
        inCall {
            WebViewResolver(Regex("""master\.m3u8""")).resolveUsingWebView(
                "https://a.example/embed?id=7", referer = "https://r.example/", headers = mapOf("X-Req" to "1", "Cookie" to "s=1"),
            )
        }
        val o = host.requests.single()
        // No additionalUrls: a single capture, no captureAll / alsoMatch / returnCookiesOnTimeout.
        assertEquals(setOf("callId", "pluginId", "url", "match", "timeoutMs", "headers"), o.keys().asSequence().toSet())
        assertEquals("call-1", o.getString("callId"))
        assertEquals("cloudstream-x", o.getString("pluginId"))
        assertEquals("https://a.example/embed?id=7", o.getString("url"))
        assertEquals("""(?-i)master\.m3u8""", o.getString("match")) // case-sensitive like upstream (Kino adds IGNORE_CASE)
        assertEquals(25_000L, o.getLong("timeoutMs")) // upstream's 60 s default, cut to Kino's maximum
        assertTrue(CsWire.parseWebViewRequest(o.toString()) != null) // what Kino accepts
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
        serve { found("https://cdn.example/v/master.m3u8?t=1", headers = mapOf("Referer" to "https://a.example/"), cookies = mapOf("cf_clearance" to "abc", "s" to "1")) }
        val seen = CopyOnWriteArrayList<Request>()
        val (req, extra) = inCall {
            WebViewResolver(Regex("""master\.m3u8""")).resolveUsingWebView("https://a.example/embed") { seen += it; false }
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
            reply(), // nothing captured
            // The v1 shape is not a v2 answer.
            JSONObject().put("request", JSONObject().put("url", "https://cdn.example/a.m3u8").put("headers", JSONObject()))
                .put("cookies", "").put("userAgent", "UA").put("finalUrl", "https://a.example/").toString(),
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

    /** Kino's cookie-mode answer; the page ends on the requested URL unless [finalUrl] says otherwise. */
    private fun cfAnswer(clearance: String?, finalUrl: String? = null, timedOut: Boolean = false) = { o: JSONObject ->
        reply(cookies = clearance?.let { mapOf("cf_clearance" to it, "__cf_bm" to "z") } ?: emptyMap(),
            finalUrl = finalUrl ?: o.getString("url"), timedOut = timedOut)
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
        assertEquals("cf_clearance", o.getString("waitForCookie"))
        assertTrue(o.getBoolean("returnCookiesOnTimeout"))
        assertFalse(o.has("captureAll"))
        assertEquals("https://a.example/list?p=2", o.getString("url"))
        assertTrue(CsWire.parseWebViewRequest(o.toString()) != null)
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

    // ---- review fixes (F1, F2, F4, F5, F7) --------------------------------------------------------------------

    @Test fun `solved Cloudflare cookies override the request's stale ones, other own cookies still win`() {
        serve(cfAnswer("new"))
        val req = Request.Builder().url("https://a.example/").header("Cookie", "cf_clearance=old; __cf_bm=old; session=mine").build()
        val chain = FakeChain(req, cfSite("new"))
        val r = intercept(CloudflareKiller(), chain)
        assertEquals(200, r.code)
        val sent = HostWebViewResolver.parseCookies(chain.sent[1].header("Cookie")!!)
        assertEquals("new", sent["cf_clearance"])
        assertEquals("z", sent["__cf_bm"])
        assertEquals("mine", sent["session"])
        // Saved cookies on the first try win over a stale copy too: one request, no new page.
        val again = FakeChain(Request.Builder().url("https://a.example/2").header("Cookie", "cf_clearance=old").build(), cfSite("new"))
        assertEquals(200, intercept(CloudflareKiller(), again).code)
        assertEquals(1, again.sent.size)
        assertEquals(1, host.requests.size)
    }

    @Test fun `a failed solve is remembered for a minute, so the next check gets the check page without a page`() {
        serve(cfAnswer(null))
        intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok")))
        val chain = FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok"))
        val r = intercept(CloudflareKiller(), chain)
        assertEquals(503, r.code)
        assertEquals(1, chain.sent.size)
        assertEquals(1, host.requests.size)
        // Another host of the same plugin is not affected.
        intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://b.example/").build(), cfSite("ok")))
        assertEquals(2, host.requests.size)
    }

    @Test fun `requests waiting behind a solve that fails return without solving`() {
        serveAfter(600, cfAnswer(null))
        val killer = CloudflareKiller()
        val results = CopyOnWriteArrayList<Int>()
        val threads = (1..3).map { i ->
            thread {
                if (i > 1) Thread.sleep(150) // start while the first one holds the lock
                results += intercept(killer, FakeChain(Request.Builder().url("https://a.example/$i").build(), cfSite("ok"))).code
            }
        }
        threads.forEach { it.join(10_000) }
        assertEquals(listOf(503, 503, 503), results.toList())
        assertEquals(1, host.requests.size)
    }

    @Test fun `a solve whose page ended on another host does not count`() {
        serve(cfAnswer("ok", finalUrl = "https://challenge.other.example/"))
        val chain = FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok"))
        assertEquals(503, intercept(CloudflareKiller(), chain).code)
        assertEquals(1, chain.sent.size)
        assertNull(WebCookies.get("cloudstream-x", "a.example"))
    }

    @Test fun `match is sent case-sensitive unless the plugin's regex ignores case, limit checked after the prefix`() {
        assertEquals("(?-i)m3u8", HostWebViewResolver.matchPattern(Regex("m3u8")))
        assertEquals("m3u8", HostWebViewResolver.matchPattern(Regex("m3u8", RegexOption.IGNORE_CASE)))
        assertEquals("(?-i)(?:)", HostWebViewResolver.matchPattern(Regex("")))
        assertEquals(500, HostWebViewResolver.matchPattern(Regex("a".repeat(495)))!!.length)
        assertNull(HostWebViewResolver.matchPattern(Regex("a".repeat(496))))
        assertEquals(496, HostWebViewResolver.matchPattern(Regex("a".repeat(496), RegexOption.IGNORE_CASE))!!.length)
    }

    @Test fun `a page's cookies replace the previous ones, capped`() {
        WebCookies.put("p", "h.example", mapOf("old" to "1", "cf_clearance" to "a"))
        WebCookies.put("p", "h.example", mapOf("cf_clearance" to "b"))
        assertEquals(mapOf("cf_clearance" to "b"), WebCookies.get("p", "h.example"))
        WebCookies.put("p", "h.example", (1..100).associate { "c$it" to "v" })
        assertEquals(WebCookies.MAX_COOKIES_PER_ENTRY, WebCookies.get("p", "h.example")!!.size)
    }

    // ---- contract v2: every match, method, cookies on timeout -------------------------------------------------

    private fun captured(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()) = CsWire.CapturedRequest(url, method, headers)

    @Test fun `additionalUrls ask Kino for every match, with alsoMatch and cookies on timeout`() {
        serve { reply(listOf(captured("https://cdn.example/master.m3u8"))) }
        inCall {
            WebViewResolver(Regex("""master\.m3u8"""), additionalUrls = listOf(Regex("""\.vtt"""), Regex("key", RegexOption.IGNORE_CASE)))
                .resolveUsingWebView("https://a.example/embed")
        }
        val o = host.requests.single()
        assertTrue(o.getBoolean("captureAll"))
        assertEquals(listOf("""(?-i)\.vtt""", "key"), (0 until o.getJSONArray("alsoMatch").length()).map { o.getJSONArray("alsoMatch").getString(it) })
        assertTrue(o.getBoolean("returnCookiesOnTimeout"))
        assertFalse(o.has("waitForCookie"))
        assertTrue(CsWire.parseWebViewRequest(o.toString()) != null)
    }

    @Test fun `alsoMatch keeps at most 10 patterns that fit Kino's limit`() {
        serve { reply(listOf(captured("https://cdn.example/m.m3u8"))) }
        val extra = listOf(Regex("x".repeat(600))) + (1..12).map { Regex("p$it") }
        inCall { WebViewResolver(Regex("m3u8"), additionalUrls = extra).resolveUsingWebView("https://a.example/") }
        val also = host.requests.single().getJSONArray("alsoMatch")
        assertEquals((1..10).map { "(?-i)p$it" }, (0 until also.length()).map { also.getString(it) })
    }

    @Test fun `requests map back to upstream's pair with the real method and headers`() {
        serve {
            reply(listOf(
                captured("https://cdn.example/master.m3u8", headers = mapOf("Referer" to "https://a.example/", "Origin" to "https://a.example")),
                captured("https://cdn.example/sub.vtt", headers = mapOf("X-A" to "1")),
                captured("https://cdn.example/key", method = "POST", headers = mapOf("Content-Type" to "application/json")),
                captured("https://cdn.example/other.js"), // matches nothing the plugin asked for
                captured("http://cdn.example/plain.vtt"), // not https
            ))
        }
        val seen = CopyOnWriteArrayList<String>()
        val (primary, extras) = inCall {
            WebViewResolver(Regex("""master\.m3u8"""), additionalUrls = listOf(Regex("""\.vtt"""), Regex("/key")))
                .resolveUsingWebView("https://a.example/embed") { seen += it.url.toString(); false }
        }
        assertEquals("https://cdn.example/master.m3u8", primary!!.url.toString())
        assertEquals("GET", primary.method)
        assertEquals("https://a.example", primary.header("Origin"))
        assertEquals(listOf("https://cdn.example/sub.vtt", "https://cdn.example/key"), extras.map { it.url.toString() })
        assertEquals("1", extras[0].header("X-A"))
        assertEquals("POST", extras[1].method)
        assertEquals("application/json", extras[1].header("Content-Type"))
        assertEquals(listOf("https://cdn.example/master.m3u8", "https://cdn.example/sub.vtt", "https://cdn.example/key"), seen.toList())
    }

    @Test fun `a timeout reply with only additional matches gives null and the extras, as upstream's timeout does`() {
        serve { reply(listOf(captured("https://cdn.example/sub.vtt")), timedOut = true) }
        val (primary, extras) = inCall {
            WebViewResolver(Regex("m3u8"), additionalUrls = listOf(Regex("vtt"))).resolveUsingWebView("https://a.example/")
        }
        assertNull(primary)
        assertEquals(listOf("https://cdn.example/sub.vtt"), extras.map { it.url.toString() })
    }

    @Test fun `a doneStream from Kino is refused, webView answers come in done only`() {
        host = object : FakeHost(null) {
            override fun webView(requestJson: String, reply: IReply) {
                requests += JSONObject(requestJson)
                thread { reply.doneStream(ParcelFileDescriptor.createPipe()[0]) }
            }
        }
        HostWebViewResolver.host = { host }
        HostWebViewResolver.callMarginMs = 0; HostWebViewResolver.pageOverheadMs = 0
        val t0 = System.nanoTime()
        val r = inCall(ctx(deadlineMs = 3_000)) { WebViewResolver(Regex("m3u8")).resolveUsingWebView("https://a.example/") }
        assertEquals(null to emptyList<Request>(), r)
        assertTrue("answered as a failure at once", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
    }

    @Test fun `CloudflareKiller uses the cookies of a timed-out page when cf_clearance is there`() {
        serve(cfAnswer("late", timedOut = true))
        val chain = FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("late"))
        assertEquals(200, intercept(CloudflareKiller(), chain).code)
        assertEquals("late", WebCookies.get("cloudstream-x", "a.example")!!["cf_clearance"])
    }

    @Test fun `a timed-out page without cf_clearance is a failed solve`() {
        serve(cfAnswer(null, timedOut = true))
        assertEquals(503, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok"))).code)
        intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok")))
        assertEquals("remembered as failed: no second page", 1, host.requests.size)
    }

    @Test fun `Kino busy is not remembered as a failed solve`() {
        serve { CsWire.error(CsWire.Err.BUSY, "otra página") }
        assertEquals(503, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok"))).code)
        serve(cfAnswer("ok"))
        assertEquals(200, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok"))).code)
        assertEquals(1, host.requests.size)
    }

    @Test fun `a solve cut short by the op's end is not remembered as failed`() {
        HostWebViewResolver.callMarginMs = 0; HostWebViewResolver.pageOverheadMs = 0
        serve(null) // Kino never answers: the op's deadline ends the wait
        val c = ctx(deadlineMs = 1_500)
        CallContext.threadLocal.set(c)
        val first = try {
            CloudflareKiller().intercept(FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok")))
        } finally { CallContext.threadLocal.set(null) }
        assertEquals(503, first.code)
        serve(cfAnswer("ok"))
        assertEquals(200, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok"))).code)
        assertEquals(1, host.requests.size)
    }

    @Test fun `Kino not connected is a local failure, not remembered for the site`() {
        HostWebViewResolver.host = { null } // NO_HOST
        assertEquals(503, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok"))).code)
        serve(cfAnswer("ok"))
        assertEquals(200, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok"))).code)
        assertEquals(1, host.requests.size)
    }

    @Test fun `too little time left for a page is a local failure, not remembered for the site`() {
        serve(cfAnswer("ok"))
        // 5 s left: under the 1.5 s margin + 4 s overhead + 1 s page Kino needs (NO_TIME), and the op is still alive.
        val c = ctx(deadlineMs = 5_000)
        CallContext.threadLocal.set(c)
        val first = try {
            CloudflareKiller().intercept(FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok")))
        } finally { CallContext.threadLocal.set(null) }
        assertEquals(503, first.code)
        assertTrue("no page was asked", host.requests.isEmpty())
        assertEquals(200, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok"))).code)
        assertEquals(1, host.requests.size)
    }

    @Test fun `Kino's timeout and blocked answers are remembered for the site`() {
        for (code in listOf(CsWire.Err.TIMEOUT, CsWire.Err.BLOCKED)) {
            CloudflareKiller.resetForTest()
            serve { CsWire.error(code, "x") }
            intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok")))
            intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/2").build(), cfSite("ok")))
            assertEquals(code, 1, host.requests.size)
        }
    }

    @Test fun `a reply whose final address is not https solves nothing and files no cookies`() {
        serve { o -> JSONObject(cfAnswer("ok")(o)).put("finalUrl", "http://a.example/").toString() }
        assertEquals(503, intercept(CloudflareKiller(), FakeChain(Request.Builder().url("https://a.example/").build(), cfSite("ok"))).code)
        assertNull(WebCookies.get("cloudstream-x", "a.example"))
    }

    @Test fun `the per-host solve locks are released once nobody holds or waits for them`() {
        serveAfter(300, cfAnswer("ok"))
        val killer = CloudflareKiller()
        val threads = (1..4).map { i ->
            thread {
                if (i > 1) Thread.sleep(100)
                intercept(killer, FakeChain(Request.Builder().url("https://a.example/$i").build(), cfSite("ok")))
            }
        }
        threads.forEach { it.join(10_000) }
        assertEquals(1, host.requests.size)
        assertEquals(0, CloudflareKiller.solvingCountForTest())
    }

    // ---- never a WebView in this process ---------------------------------------------------------------------

    /** The jar (or directory) a class was loaded from, as its resource URL names it. */
    private fun origin(c: Class<*>): String = c.getResource(c.simpleName + ".class")!!.toString().substringBefore("!/")

    /**
     * Guards the transform's jar path (compile and unit-test classpaths) on debug: AGP 9 has no release unit-test task.
     * The dex path (android-classes-directory -> android-classes) and release are checked on the APK itself by
     * verifyReplacedClasses<Variant> (bridge/build.gradle.kts), which every assemble runs.
     */
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
