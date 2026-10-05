package com.kino.csbridge.web

import android.util.Log
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IReply
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * The bridge's side of `IHost.webView`: the page load and cookie capture that upstream's WebViewResolver and
 * CloudflareKiller do in a WebView of their own are asked of Kino's hidden WebView instead. This process has no
 * INTERNET permission and never creates a WebView (or touches CookieManager, which loads the WebView provider).
 *
 * Request, exactly what Kino's `PluginBrowser.captureForCloudStream` parses:
 * `{"callId","pluginId","url","match","timeoutMs","headers":{name:value},"wantCookies":true}`. Cookie mode sends
 * `"waitForCookie":"cf_clearance"` instead of `match`: Kino ends the page as soon as that cookie exists (or at the
 * timeout), with no media required. (Kino also defaults to it when `match` is absent; the explicit field is what a
 * later contract combining `match` and `waitForCookie` builds on.)
 * Answer: `{"request":{"url","headers"}|null,"cookies","userAgent","finalUrl"}` or `{"error":{"code","message"}}`.
 *
 * Only inside a live Kino call (Kino refuses anything else too), https only, and every wait is bounded by the call's
 * deadline: no call, no host, a refusal, an error or the deadline all give `null`, never an exception (cancellation of
 * the calling coroutine excepted). Logs name the host and an error code only: never a path, a query or a cookie.
 */
object HostWebViewResolver {
    // Kino's limits (PluginBrowser.parseRequest), verbatim: a value outside them is clamped or refused over there.
    const val MIN_TIMEOUT_MS = 1_000L
    const val MAX_TIMEOUT_MS = 25_000L
    const val MAX_MATCH_CHARS = 500
    const val MAX_HEADERS = 10
    const val MAX_HEADER_VALUE_CHARS = 2048
    private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "cookie")
    private const val MAX_URL_CHARS = 8192
    private const val MAX_UA_CHARS = 512
    private const val MAX_COOKIES = 64
    private const val MAX_COOKIE_NAME_CHARS = 256
    private const val MAX_COOKIE_VALUE_CHARS = 4096
    private const val TAG = "CsBridgeWeb"

    /**
     * Time the op keeps after the page for the plugin to use what it found (Kino's `PAGE_CALL_MARGIN_MS`).
     * Tests shrink these four.
     */
    @Volatile internal var callMarginMs = 1_500L
    /** Kino's side around the page itself: up to 3 s waiting for the one hidden-page lock, 1 s grace, teardown. */
    @Volatile internal var pageOverheadMs = 4_000L
    /** Binder and scheduling slack on top of the page's timeout and overhead, before the bridge stops waiting. */
    @Volatile internal var replySlackMs = 2_000L
    @Volatile internal var pollMs = 100L
    @Volatile internal var host: () -> IHost? = { HostLink.host }
    @Volatile internal var log: (String) -> Unit = { msg -> runCatching { Log.w(TAG, msg) } }

    /** What Kino's page saw: the first request matching `match` (null in cookie mode or when none matched), the page's cookies and user agent. */
    data class Capture(val request: Request?, val cookies: Map<String, String>, val userAgent: String?, val finalUrl: HttpUrl?)

    /**
     * The page's timeout to send: [requestedMs] within Kino's [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS], and short enough that
     * Kino's overhead and the plugin's margin still fit in [remainingMs]. Null when not even [MIN_TIMEOUT_MS] fits.
     */
    fun clampTimeout(requestedMs: Long, remainingMs: Long): Long? {
        val wanted = requestedMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        if (remainingMs == Long.MAX_VALUE) return wanted
        val fits = remainingMs - callMarginMs - pageOverheadMs
        return if (fits < MIN_TIMEOUT_MS) null else minOf(wanted, fits)
    }

    /** The cookie a cookie-mode capture waits for. */
    const val CF_CLEARANCE = "cf_clearance"

    /**
     * `match` as Kino accepts it (1..[MAX_MATCH_CHARS] chars, the limit checked on what is sent); an empty pattern
     * matches anything, as it does upstream. Kino compiles it with IGNORE_CASE, so unless the plugin's regex is
     * case-insensitive itself it is sent as `(?-i)…`: Kino then picks the same first match upstream would. Null = refused.
     */
    fun matchPattern(match: Regex): String? {
        val pattern = match.pattern.ifEmpty { "(?:)" }
        val sent = if (RegexOption.IGNORE_CASE in match.options) pattern else "(?-i)$pattern"
        return sent.takeIf { it.length <= MAX_MATCH_CHARS }
    }

    /** The first-load headers Kino would keep, in order: valid names, no hop/cookie headers, no line breaks, at most [MAX_HEADERS]. */
    fun headersForKino(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (out.size >= MAX_HEADERS) break
            if (!HEADER_NAME.matches(k) || k.lowercase() in FORBIDDEN_HEADERS || '\n' in v || '\r' in v || v.length > MAX_HEADER_VALUE_CHARS) continue
            out[k] = v
        }
        return out
    }

    fun requestJson(ctx: CallContext, url: HttpUrl, match: String?, headers: Map<String, String>, timeoutMs: Long): String =
        JSONObject().put("callId", ctx.callId).put("pluginId", ctx.pluginId).put("url", url.toString())
            .apply { if (match != null) put("match", match) else put("waitForCookie", CF_CLEARANCE) }
            .put("timeoutMs", timeoutMs).put("headers", JSONObject(headersForKino(headers) as Map<*, *>))
            .put("wantCookies", true).toString()

    /**
     * Asks Kino's hidden WebView to load [url] (first load with [headers]) for up to [timeoutMs], ending at the first
     * request matching [match], or, with [match] null, as soon as Cloudflare's `cf_clearance` cookie exists.
     * The page's cookies go to [WebCookies] (this plugin, the page's host) and its user agent to
     * [WebViewResolver.webViewUserAgent]. Null when there was no answer to use.
     */
    suspend fun capture(url: String, match: Regex?, headers: Map<String, String>, timeoutMs: Long): Capture? {
        val ctx = CallContext.currentInCoroutine() ?: return null // outside a Kino call: no IPC at all
        val start = url.takeIf { it.length <= MAX_URL_CHARS }?.toHttpUrlOrNull()
        if (start == null || !start.isHttps) {
            log("webview refused locally: not an https URL")
            return null
        }
        val pattern = if (match == null) null else matchPattern(match) ?: run {
            log("webview ${start.host}: match pattern over $MAX_MATCH_CHARS chars, not sent")
            return null
        }
        if (ctx.isOver()) return null
        val pageMs = clampTimeout(timeoutMs, ctx.remainingMs()) ?: run {
            log("webview ${start.host}: too little time left in the call")
            return null
        }
        val h = host() ?: run { log("webview ${start.host}: Kino is not connected"); return null }
        val waiter = Waiter()
        try {
            h.webView(requestJson(ctx, start, pattern, headers, pageMs), waiter)
        } catch (e: Exception) { // DeadObjectException / RemoteException, or a Binder-side runtime failure
            log("webview ${start.host}: Kino unreachable (${e.javaClass.simpleName})")
            return null
        }
        val answer = await(waiter, ctx, h, pageMs) ?: run { log("webview ${start.host}: no answer in time"); return null }
        CsWire.errorOf(answer)?.let { (code, _) ->
            log("webview ${start.host}: Kino answered $code")
            return null
        }
        val capture = parseAnswer(answer) ?: run { log("webview ${start.host}: malformed answer"); return null }
        capture.userAgent?.let { WebViewResolver.webViewUserAgent = it }
        if (capture.cookies.isNotEmpty()) WebCookies.put(ctx.pluginId, (capture.finalUrl ?: start).host, capture.cookies)
        return capture
    }

    /** Kino's done(), or null at the call's deadline, the page's own bound, the op's end or Kino's death. */
    private suspend fun await(waiter: Waiter, ctx: CallContext, h: IHost, pageMs: Long): String? {
        val bound = pageMs + pageOverheadMs + replySlackMs
        val wake = ctx.job?.invokeOnCompletion { waiter.answer.complete(ENDED) }
        try {
            val r = withTimeoutOrNull(minOf(bound, ctx.remainingMs())) {
                var got: String? = null
                while (got == null) {
                    got = withTimeoutOrNull(pollMs) { waiter.answer.await() }
                    if (got == null && !h.asBinder().isBinderAlive) break
                }
                got
            }
            return r?.takeIf { it !== ENDED }
        } finally {
            wake?.dispose()
        }
    }

    internal fun parseAnswer(json: String): Capture? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val request = o.optJSONObject("request")?.let { r ->
            val u = r.optString("url").takeIf { it.length <= MAX_URL_CHARS }?.toHttpUrlOrNull()?.takeIf { it.isHttps }
            u?.let { Request.Builder().url(it).headers(headersOf(r.optJSONObject("headers"))).build() }
        }
        val ua = o.optString("userAgent").takeIf { it.isNotBlank() && it.length <= MAX_UA_CHARS && it.none { c -> c == '\n' || c == '\r' } }
        val finalUrl = o.optString("finalUrl").takeIf { it.length <= MAX_URL_CHARS }?.toHttpUrlOrNull()?.takeIf { it.isHttps }
        return Capture(request, parseCookies(o.optString("cookies")), ua, finalUrl)
    }

    private fun headersOf(o: JSONObject?): Headers {
        val b = Headers.Builder()
        o?.keys()?.forEach { k -> (o.opt(k) as? String)?.let { v -> runCatching { b.add(k, v) } } }
        return b.build()
    }

    /** A `Cookie`-style string (`a=1; b=2`, as CookieManager gives it) as a name → value map, bounded. */
    fun parseCookies(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            if (out.size >= MAX_COOKIES) break
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty() || name.length > MAX_COOKIE_NAME_CHARS || value.length > MAX_COOKIE_VALUE_CHARS) continue
            if (name.any { it.isWhitespace() || it == ',' } || value.any { it == '\r' || it == '\n' || it == ';' }) continue
            out[name] = value
        }
        return out
    }

    private const val ENDED = "\u0000ended"

    private class Waiter : IReply.Stub() {
        val answer = CompletableDeferred<String>()
        override fun event(eventJson: String?) {} // IHost.webView replies via done() only
        override fun done(resultJson: String?) { if (resultJson != null) answer.complete(resultJson) }
    }

    /** Thrown nowhere: cancellation of the caller is rethrown as is, everything else is a null answer. */
    internal inline fun <T> quiet(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("webview failed: ${e.javaClass.simpleName}")
        fallback
    }
}

/**
 * The cookies Kino's hidden WebView earned, per plugin and host, in memory only (this process's stand-in for the
 * WebView CookieManager, which the bridge never touches). Scoped per plugin: one plugin's `cf_clearance` is never sent
 * by another. Bounded: the least recently used host goes first.
 */
object WebCookies {
    private const val MAX_ENTRIES = 128
    const val MAX_COOKIES_PER_ENTRY = 64
    private val map = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?): Boolean = size > MAX_ENTRIES
    }

    private fun key(pluginId: String, host: String) = pluginId + "\u0000" + host.lowercase()

    fun get(pluginId: String, host: String): Map<String, String>? = synchronized(map) { map[key(pluginId, host)] }

    /**
     * Replaces the entry: Kino wipes its WebView's cookies before every page, so each answer is that page's whole jar
     * (merging would keep cookies the site deleted). At most [MAX_COOKIES_PER_ENTRY].
     */
    fun put(pluginId: String, host: String, cookies: Map<String, String>) {
        val capped = if (cookies.size <= MAX_COOKIES_PER_ENTRY) cookies.toMap() else cookies.entries.take(MAX_COOKIES_PER_ENTRY).associate { it.key to it.value }
        synchronized(map) { map[key(pluginId, host)] = capped }
    }

    fun remove(pluginId: String, host: String) { synchronized(map) { map.remove(key(pluginId, host)) } }

    internal fun clearForTest() { synchronized(map) { map.clear() } }
}
