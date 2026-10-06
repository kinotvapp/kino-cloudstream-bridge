package com.kino.csbridge.web

import android.os.ParcelFileDescriptor
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
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The bridge's side of `IHost.webView` (contract v2, bridge-contract/PROTOCOL.md "IHost.webView"): the page load and
 * cookie capture that upstream's WebViewResolver and CloudflareKiller do in a WebView of their own are asked of Kino's
 * hidden WebView instead. This process has no INTERNET permission and never creates a WebView (or touches
 * CookieManager, which loads the WebView provider).
 *
 * Requests are built with [CsWire.webViewRequest] (what Kino's parser accepts) and answers read with
 * [CsWire.parseWebViewReply]: every request the page made that matched (`requests`, primary match first), with its real
 * method and headers, the cookies of the page's final host, the WebView's user agent, and `timedOut` when Kino answered
 * a timeout with what it had (`returnCookiesOnTimeout`).
 *
 * Only inside a live Kino call (Kino refuses anything else too), https only, and every wait is bounded by the call's
 * deadline: no call, no host, a refusal, an error or the deadline all give a [Outcome.Failed], never an exception
 * (cancellation of the calling coroutine excepted). Logs name the host and an error code only: never a path, a query or
 * a cookie.
 */
object HostWebViewResolver {
    // Kino's limits (CsWire.WebView): a value outside them is clamped or refused over there.
    const val MIN_TIMEOUT_MS = CsWire.WebView.MIN_TIMEOUT_MS
    const val MAX_TIMEOUT_MS = CsWire.WebView.MAX_TIMEOUT_MS
    const val MAX_MATCH_CHARS = CsWire.WebView.MAX_MATCH_CHARS
    private const val MAX_URL_CHARS = 8192
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

    /**
     * What Kino's page saw: the matching requests (https only; the primary match first when one was seen), the cookies of
     * the page's final host, its user agent and final address, and whether the page timed out.
     */
    data class Capture(val requests: List<Request>, val cookies: Map<String, String>, val userAgent: String?, val finalUrl: HttpUrl?, val timedOut: Boolean)

    /** A capture, or why there is none: one of Kino's error codes ([CsWire.Err.BUSY], ...) or a local one below. */
    sealed interface Outcome {
        data class Got(val capture: Capture) : Outcome
        data class Failed(val code: String) : Outcome
    }

    // Local failure codes (never sent anywhere; logs and callers only).
    const val NO_CALL = "no_call"
    const val REFUSED_LOCALLY = "refused_locally"
    const val NO_TIME = "no_time"
    const val NO_HOST = "no_host"
    const val UNREACHABLE = "unreachable"
    const val NO_ANSWER = "no_answer"
    const val MALFORMED = "malformed"
    const val FAILED = "failed"

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

    /** The cookie Cloudflare's check ends with. */
    const val CF_CLEARANCE = "cf_clearance"

    /**
     * A pattern as Kino accepts it (1..[MAX_MATCH_CHARS] chars, the limit checked on what is sent); an empty pattern
     * matches anything, as it does upstream. Kino compiles it with IGNORE_CASE, so unless the plugin's regex is
     * case-insensitive itself it is sent as `(?-i)…`: Kino then picks the same first match upstream would. A LITERAL
     * regex is quoted. No other option is forwarded: the pattern runs against URLs, which have no line terminators
     * (MULTILINE, DOT_MATCHES_ALL, UNIX_LINES change nothing there), and Kino compiles with Android's ICU Pattern, whose
     * support for every inline flag is not something to depend on. Null = refused.
     */
    fun matchPattern(match: Regex): String? {
        val pattern = if (RegexOption.LITERAL in match.options) java.util.regex.Pattern.quote(match.pattern) else match.pattern.ifEmpty { "(?:)" }
        val sent = if (RegexOption.IGNORE_CASE in match.options) pattern else "(?-i)$pattern"
        return sent.takeIf { it.length <= MAX_MATCH_CHARS }
    }

    /** The first-load headers Kino would keep, in order ([CsWire.firstLoadHeaders]). */
    fun headersForKino(headers: Map<String, String>): Map<String, String> = CsWire.firstLoadHeaders(headers)

    /** As [ask], keeping only a capture. */
    suspend fun capture(
        url: String, match: Regex?, headers: Map<String, String>, timeoutMs: Long,
        alsoMatch: List<Regex> = emptyList(), waitForCookie: String? = null, returnOnTimeout: Boolean = false,
    ): Capture? = (ask(url, match, headers, timeoutMs, alsoMatch, waitForCookie, returnOnTimeout) as? Outcome.Got)?.capture

    /**
     * Asks Kino's hidden WebView to load [url] (first load with [headers]) for up to [timeoutMs]:
     * - [match]: ends at the first request matching it; with [alsoMatch] (`captureAll`) Kino collects every request
     *   matching it or one of them until the page settles. Patterns past Kino's limits are left out (logged).
     * - [waitForCookie]: ends once that cookie exists (with [match], once both hold).
     * - [returnOnTimeout]: a timeout answers what was captured with `timedOut`, instead of an error.
     * The page's cookies go to [WebCookies] (this plugin, the page's final host) and its user agent to
     * [WebViewResolver.webViewUserAgent].
     */
    suspend fun ask(
        url: String, match: Regex?, headers: Map<String, String>, timeoutMs: Long,
        alsoMatch: List<Regex> = emptyList(), waitForCookie: String? = null, returnOnTimeout: Boolean = false,
    ): Outcome {
        val ctx = CallContext.currentInCoroutine() ?: return Outcome.Failed(NO_CALL) // outside a Kino call: no IPC at all
        val start = url.takeIf { it.length <= MAX_URL_CHARS }?.toHttpUrlOrNull()
        if (start == null || !start.isHttps) {
            log("webview refused locally: not an https URL")
            return Outcome.Failed(REFUSED_LOCALLY)
        }
        val pattern = if (match == null) null else matchPattern(match) ?: run {
            log("webview ${start.host}: match pattern over $MAX_MATCH_CHARS chars, not sent")
            return Outcome.Failed(REFUSED_LOCALLY)
        }
        if (pattern == null && waitForCookie == null) return Outcome.Failed(REFUSED_LOCALLY)
        val also = if (pattern == null) emptyList() else alsoPatterns(start.host, alsoMatch)
        if (ctx.isOver()) return Outcome.Failed(NO_TIME)
        val pageMs = clampTimeout(timeoutMs, ctx.remainingMs()) ?: run {
            log("webview ${start.host}: too little time left in the call")
            return Outcome.Failed(NO_TIME)
        }
        val h = host() ?: run { log("webview ${start.host}: Kino is not connected"); return Outcome.Failed(NO_HOST) }
        val request = CsWire.webViewRequest(CsWire.WebViewRequest(
            ctx.callId, ctx.pluginId, start.toString(), pageMs, headers, match = pattern, alsoMatch = also,
            captureAll = also.isNotEmpty(), waitForCookie = waitForCookie, returnCookiesOnTimeout = returnOnTimeout,
        ))
        val waiter = Waiter()
        try {
            h.webView(request, waiter)
        } catch (e: Exception) { // DeadObjectException / RemoteException, or a Binder-side runtime failure
            log("webview ${start.host}: Kino unreachable (${e.javaClass.simpleName})")
            return Outcome.Failed(UNREACHABLE)
        }
        val answer = await(waiter, ctx, h, pageMs) ?: run { log("webview ${start.host}: no answer in time"); return Outcome.Failed(NO_ANSWER) }
        CsWire.errorOf(answer)?.let { (code, _) ->
            log("webview ${start.host}: Kino answered $code")
            return Outcome.Failed(code)
        }
        val capture = parseAnswer(answer) ?: run { log("webview ${start.host}: malformed answer"); return Outcome.Failed(MALFORMED) }
        capture.userAgent?.let { WebViewResolver.webViewUserAgent = it }
        // Filed only under the page's known final host: cookies of an unknown host are never this host's.
        if (capture.cookies.isNotEmpty() && capture.finalUrl != null) WebCookies.put(ctx.pluginId, capture.finalUrl.host, capture.cookies)
        return Outcome.Got(capture)
    }

    /** [alsoMatch] as sent: each through [matchPattern], at most [CsWire.WebView.MAX_ALSO_MATCH]; what does not fit is logged. */
    private fun alsoPatterns(host: String, alsoMatch: List<Regex>): List<String> {
        val fit = alsoMatch.mapNotNull { matchPattern(it) }
        if (fit.size < alsoMatch.size) log("webview $host: ${alsoMatch.size - fit.size} additional pattern(s) over $MAX_MATCH_CHARS chars, not sent")
        if (fit.size > CsWire.WebView.MAX_ALSO_MATCH) log("webview $host: only the first ${CsWire.WebView.MAX_ALSO_MATCH} additional patterns sent")
        return fit.take(CsWire.WebView.MAX_ALSO_MATCH)
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

    /** A v2 reply as a [Capture]: only https requests, each with its real method (an empty body where OkHttp needs one). */
    internal fun parseAnswer(json: String): Capture? {
        val reply = CsWire.parseWebViewReply(json) ?: return null
        val requests = reply.requests.mapNotNull { r -> runCatching { toRequest(r) }.getOrNull() }
        val ua = reply.userAgent.takeIf { it.isNotBlank() }
        val finalUrl = reply.finalUrl.toHttpUrlOrNull()?.takeIf { it.isHttps }
        return Capture(requests, reply.cookies, ua, finalUrl, reply.timedOut)
    }

    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    private fun toRequest(r: CsWire.CapturedRequest): Request? {
        val u = r.url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        val b = Headers.Builder()
        r.headers.forEach { (k, v) -> runCatching { b.add(k, v) } }
        val body = if (r.method in BODY_METHODS) ByteArray(0).toRequestBody(null) else null
        return Request.Builder().url(u).headers(b.build()).method(r.method, body).build()
    }

    /** A `Cookie`-style string (`a=1; b=2`) as a name → value map, bounded. */
    fun parseCookies(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            if (out.size >= CsWire.WebView.MAX_COOKIES) break
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty() || name.length > CsWire.WebView.MAX_COOKIE_NAME_CHARS || value.length > CsWire.WebView.MAX_COOKIE_VALUE_CHARS) continue
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
        /** Not a webView answer (PROTOCOL.md): the pipe is closed unread and the capture fails at once. */
        override fun doneStream(result: ParcelFileDescriptor?) {
            runCatching { result?.close() }
            answer.complete(CsWire.error(CsWire.Err.BAD_REQUEST, "webView answers come in done"))
        }
    }

    /** Thrown nowhere: cancellation of the caller is rethrown as is, everything else is [fallback]. */
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
