package com.kino.csbridge.contract

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The contract's v2 helpers, as both Kino and the bridge use them (bridge-contract/PROTOCOL.md). */
@RunWith(RobolectricTestRunner::class)
class CsWireV2Test {
    @Test fun `v2 constants are the documented ones`() {
        assertEquals(2, CsWire.PROTOCOL_VERSION)
        assertEquals(100_000, CsWire.MAX_INLINE_RESULT_CHARS)
        assertEquals(8 * 1024 * 1024, CsWire.MAX_STREAM_RESULT_BYTES)
        assertEquals("bridge_stream_broken", CsWire.Err.STREAM_BROKEN)
        assertEquals("streams_busy", CsWire.Err.STREAMS_BUSY)
        assertEquals(20, CsWire.WebView.MAX_REQUESTS)
        assertEquals(10, CsWire.WebView.MAX_ALSO_MATCH)
        // An inline done (2 bytes a char in a parcel) leaves room for a second one in binder's ~508 KB oneway space.
        assertTrue(CsWire.MAX_INLINE_RESULT_CHARS * 2 * 2 < 508 * 1024)
    }

    @Test fun `a v1 call is refused`() {
        val v1 = JSONObject(CsWire.opRequest(CsWire.Op.LIST, "c", "p", 1000)).put("v", 1).toString()
        assertNull(CsWire.parseOpRequest(v1))
        assertNotNull(CsWire.parseOpRequest(CsWire.opRequest(CsWire.Op.LIST, "c", "p", 1000)))
    }

    @Test fun `readStreamedResult reads UTF-8 to EOF and refuses what is over the cap or not UTF-8`() {
        fun pipeWith(bytes: ByteArray): ParcelFileDescriptor {
            val p = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(p[1]).use { it.write(bytes) }
            return p[0]
        }
        val json = """{"title":"Señor ✓ 😀"}"""
        assertEquals(json, CsWire.readStreamedResult(pipeWith(json.toByteArray())))
        assertEquals(CsWire.Err.TOO_LARGE, CsWire.errorOf(CsWire.readStreamedResult(pipeWith(ByteArray(CsWire.MAX_STREAM_RESULT_BYTES + 1) { 'a'.code.toByte() })))!!.first)
        assertEquals(CsWire.Err.STREAM_BROKEN, CsWire.errorOf(CsWire.readStreamedResult(pipeWith(byteArrayOf(0xC3.toByte(), 0x28))))!!.first)
    }

    @Test fun `a webView request round-trips with every v2 field`() {
        val r = CsWire.WebViewRequest("c1", "cloudstream-x", "https://a.example/e?id=1", 18_000, mapOf("Referer" to "https://r.example/"),
            match = "(?-i)m3u8", alsoMatch = listOf("(?-i)vtt", "(?-i)key"), captureAll = true, waitForCookie = "cf_clearance",
            returnCookiesOnTimeout = true)
        assertEquals(r, CsWire.parseWebViewRequest(CsWire.webViewRequest(r)))
        // Optional fields at their default are left out.
        val plain = JSONObject(CsWire.webViewRequest(CsWire.WebViewRequest("c1", "p", "https://a.example/", 5_000, match = "m")))
        assertEquals(setOf("callId", "pluginId", "url", "timeoutMs", "headers", "match"), plain.keys().asSequence().toSet())
    }

    @Test fun `webView requests Kino must refuse`() {
        fun req(edit: JSONObject.() -> Unit) = JSONObject().put("callId", "c").put("pluginId", "p").put("url", "https://a.example/")
            .put("timeoutMs", 5000).put("match", "m").apply(edit).toString()
        assertNotNull(CsWire.parseWebViewRequest(req {}))
        val bad = listOf<JSONObject.() -> Unit>(
            { put("url", "http://a.example/") },
            { remove("timeoutMs") },
            { remove("match") }, // neither match nor waitForCookie
            { put("match", "") },
            { put("match", "a".repeat(501)) },
            { put("alsoMatch", JSONArray().put("x")) }, // without captureAll
            { put("captureAll", true).put("alsoMatch", JSONArray((1..11).map { "p$it" })) },
            { put("captureAll", true).put("alsoMatch", JSONArray().put("")) },
            { remove("match"); put("waitForCookie", "x"); put("captureAll", true) }, // captureAll without match
            { put("waitForCookie", "bad name") },
            { put("waitForCookie", "c".repeat(101)) },
            { put("captureAll", "yes") },
            { put("returnCookiesOnTimeout", 1) },
            { put("headers", "x") },
        )
        bad.forEachIndexed { i, b -> assertNull("case $i", CsWire.parseWebViewRequest(req(b))) }
        val cookieOnly = CsWire.parseWebViewRequest(req { remove("match"); put("waitForCookie", "cf_clearance"); put("timeoutMs", 99_999) })!!
        assertEquals(25_000L, cookieOnly.timeoutMs)
        assertNull(cookieOnly.match)
    }

    @Test fun `a webView reply round-trips and keeps within every limit`() {
        val reqs = (1..25).map { CsWire.CapturedRequest("https://cdn.example/$it.m3u8", if (it == 2) "POST" else "GET", mapOf("Referer" to "https://a.example/")) }
        val r = CsWire.WebViewReply(reqs, mapOf("cf_clearance" to "abc"), "UA", "https://a.example/", timedOut = true)
        val back = CsWire.parseWebViewReply(CsWire.webViewReply(r))!!
        assertEquals(20, back.requests.size)
        assertEquals("POST", back.requests[1].method)
        assertEquals(mapOf("cf_clearance" to "abc"), back.cookies)
        assertTrue(back.timedOut)
        // Invalid entries are dropped, not the reply.
        val messy = JSONObject().put("requests", JSONArray()
            .put(JSONObject().put("url", "https://ok.example/a").put("method", "GET").put("headers", JSONObject().put("Bad Name", "v").put("X-Ok", "1")))
            .put(JSONObject().put("url", "ftp://x.example/").put("method", "GET"))
            .put(JSONObject().put("url", "https://x.example/").put("method", "TRACE")))
            .put("cookies", JSONObject().put("ok", "1").put("bad;", "2").put("empty", ""))
            .put("userAgent", "UA\nX").put("finalUrl", "https://a.example/").put("timedOut", false)
        val m = CsWire.parseWebViewReply(messy.toString())!!
        assertEquals(listOf("https://ok.example/a"), m.requests.map { it.url })
        assertEquals(mapOf("X-Ok" to "1"), m.requests[0].headers)
        assertEquals(mapOf("ok" to "1"), m.cookies)
        assertEquals("", m.userAgent)
        // Not the success shape: an error, a v1 answer, a missing timedOut.
        assertNull(CsWire.parseWebViewReply(CsWire.error("busy", "x")))
        assertNull(CsWire.parseWebViewReply(JSONObject().put("request", JSONObject.NULL).put("cookies", "").toString()))
        assertNull(CsWire.parseWebViewReply(JSONObject(CsWire.webViewReply(r)).apply { remove("timedOut") }.toString()))
    }

    @Test fun `a reply's cookies are capped at 16K chars in all, Cloudflare's kept first`() {
        val big = (1..64).associate { "c$it" to "v".repeat(4096) } + mapOf("cf_clearance" to "x".repeat(4000), "__cf_bm" to "y".repeat(100))
        val out = CsWire.webViewReply(CsWire.WebViewReply(emptyList(), big, "UA", "https://a.example/", false))
        assertTrue(out.length <= CsWire.MAX_INLINE_RESULT_CHARS)
        val cookies = CsWire.parseWebViewReply(out)!!.cookies
        assertTrue(cookies.entries.sumOf { it.key.length + it.value.length } <= CsWire.WebView.MAX_COOKIES_TOTAL_CHARS)
        assertEquals("x".repeat(4000), cookies["cf_clearance"])
        assertEquals("y".repeat(100), cookies["__cf_bm"])
        assertEquals(listOf("c1", "c2"), cookies.keys.filter { it.startsWith("c") && !it.startsWith("cf_") })
        // The reader applies the same cap to whatever it is sent.
        val raw = JSONObject(out).put("cookies", JSONObject(big as Map<*, *>)).toString()
        assertEquals(cookies, CsWire.parseWebViewReply(raw)!!.cookies)
    }

    @Test fun `a reply whose finalUrl is not https is malformed`() {
        val r = CsWire.WebViewReply(emptyList(), emptyMap(), "UA", "https://a.example/", false)
        for (bad in listOf("http://a.example/", "", "about:blank")) {
            assertNull(bad, CsWire.parseWebViewReply(JSONObject(CsWire.webViewReply(r)).put("finalUrl", bad).toString()))
        }
    }

    @Test fun `webViewReply answers blocked instead of writing a non-https finalUrl`() {
        for (bad in listOf("http://a.example/", "", "about:blank", "https://a.example/" + "p".repeat(8200))) {
            val out = CsWire.webViewReply(CsWire.WebViewReply(emptyList(), mapOf("cf_clearance" to "x"), "UA", bad, false))
            assertEquals(bad.take(40), CsWire.Err.BLOCKED, CsWire.errorOf(out)!!.first)
        }
    }

    @Test fun `webView error codes are shared constants`() {
        assertEquals(listOf("busy", "not_allowed", "blocked", "browser_unavailable"),
            listOf(CsWire.Err.BUSY, CsWire.Err.NOT_ALLOWED, CsWire.Err.BLOCKED, CsWire.Err.BROWSER_UNAVAILABLE))
    }

    @Test fun `a webView reply too long for one done drops trailing requests`() {
        val big = (1..20).map { CsWire.CapturedRequest("https://cdn.example/" + "p".repeat(8000) + it, "GET", emptyMap()) }
        val out = CsWire.webViewReply(CsWire.WebViewReply(big, emptyMap(), "UA", "https://a.example/", false))
        assertTrue(out.length <= CsWire.MAX_INLINE_RESULT_CHARS)
        val n = CsWire.parseWebViewReply(out)!!.requests.size
        assertTrue("$n", n in 1..12)
        assertFalse(n == 20)
    }
}
