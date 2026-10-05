// Bridge-owned replacement for upstream app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt: same ABI
// (cs3-symbols.tsv: <init>, getCookieHeaders, getSavedCookies, intercept; plus TAG and parseCookieMap), no WebView and no
// CookieManager. Upstream's file is no longer in app-sources.txt.
package com.lagradost.cloudstream3.network

import android.util.Log
import com.kino.csbridge.CallContext
import com.kino.csbridge.web.HostWebViewResolver
import com.kino.csbridge.web.WebCookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Cloudflare's browser check, solved by Kino's hidden WebView in cookie mode (`IHost.webView` without `match`: the page
 * ends as soon as `cf_clearance` exists). Every request still goes through Kino's network, with the solved cookies and
 * the WebView's user agent (Cloudflare ties `cf_clearance` to both).
 *
 * - The cookies are kept per host in [savedCookies] and, process-wide, in [WebCookies] for this plugin, so the next
 *   killer a plugin creates does not open another page (upstream re-reads the WebView CookieManager for that).
 * - A request that hits the check (403/503 from a Cloudflare server) is solved once and retried once with the cookies.
 *   Saved cookies that hit the check again are dropped and solved once more, within the same request.
 * - Only one solve per plugin and host at a time: a second request waits for the first one's cookies (bounded by its
 *   own call's deadline) instead of asking Kino for a second page, which Kino would refuse as busy anyway.
 * - When the solve fails, the check page itself is returned (read into memory before the solve, so no second request),
 *   and for [FAILED_TTL_MS] that plugin and host are not solved again: their checks are answered with the check page
 *   without a Kino page. Requests that waited for a solve that failed return too, without solving.
 * - A solve counts only when the page ended on the request's host (cookies Kino read for another host are not this one's).
 * - Cloudflare's own cookies (`cf_clearance`, `__cf_bm`, any `cf_*` / `__cf*`) from the solved or saved set override the
 *   request's: a plugin that copied [getCookieHeaders] earlier must not send a stale clearance. Every other cookie of the
 *   request wins, as upstream.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = setOf(403, 503)
        private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")
        private const val CF_CLEARANCE = "cf_clearance"
        private const val CHECK_PAGE_MAX_BYTES = 256L * 1024
        /** How long a failed solve keeps that plugin and host from opening another Kino page. */
        const val FAILED_TTL_MS = 60_000L
        private const val LOCK_SLICE_MS = 200L
        private const val MAX_FAILED_ENTRIES = 256
        private val solving = ConcurrentHashMap<String, ReentrantLock>()
        /** plugin + host → [System.nanoTime] of the last failed solve. */
        private val failedAt = ConcurrentHashMap<String, Long>()

        private fun recentlyFailed(key: String): Boolean {
            val at = failedAt[key] ?: return false
            if (System.nanoTime() - at < FAILED_TTL_MS * 1_000_000) return true
            failedAt.remove(key, at)
            return false
        }

        private fun markFailed(key: String) {
            if (failedAt.size >= MAX_FAILED_ENTRIES) {
                val now = System.nanoTime()
                failedAt.entries.removeIf { now - it.value >= FAILED_TTL_MS * 1_000_000 }
                if (failedAt.size >= MAX_FAILED_ENTRIES) failedAt.clear()
            }
            failedAt[key] = System.nanoTime()
        }

        /** Cloudflare's own cookie names: the ones a solve renews. */
        internal fun isCloudflareCookie(name: String): Boolean = name.startsWith("cf_") || name.startsWith("__cf")

        internal fun resetForTest() { solving.clear(); failedAt.clear() }

        /** `a=1; b=2` as a map; blank names or values are dropped. */
        fun parseCookieMap(cookie: String): Map<String, String> = cookie.split(";").associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }

        private fun log(msg: String) { runCatching { Log.w(TAG, msg) } }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = Collections.synchronizedMap(mutableMapOf())

    /** The WebView user agent (when known) and the cookies saved for [url]'s host, as headers. */
    fun getCookieHeaders(url: String): Headers {
        val ua = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) } ?: emptyMap()
        val host = runCatching { URI(url).host }.getOrNull() ?: return getHeaders(ua, emptyMap())
        return getHeaders(ua, known(host) ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val saved = known(host)
        val first = chain.proceed(if (saved != null) withCookies(request, saved) else request)
        if (!isCheck(first)) return first
        // Read the (small) check page now: it is what the plugin gets if the solve fails, without asking again.
        // At most CHECK_PAGE_MAX_BYTES: the original Content-Length may no longer be true.
        val checkPage = first.newBuilder().removeHeader("Content-Length").body(first.peekBody(CHECK_PAGE_MAX_BYTES)).build()
        first.close()
        if (saved != null) forget(host)
        val solved = solve(request, staleFrom = saved) ?: return checkPage
        return chain.proceed(withCookies(request, solved))
    }

    private fun isCheck(r: Response): Boolean = r.code in ERROR_CODES && r.header("Server") in CLOUDFLARE_SERVERS

    /** This killer's cookies for [host] (as saved, also by the plugin itself), else this plugin's from an earlier killer or WebView capture. */
    private fun known(host: String): Map<String, String>? = savedCookies[host]
        ?: CallContext.current()?.pluginId?.let { WebCookies.get(it, host) }?.takeIf { hasClearance(it) }?.also { savedCookies[host] = it }

    private fun forget(host: String) {
        savedCookies.remove(host)
        CallContext.current()?.pluginId?.let { WebCookies.remove(it, host) }
    }

    private fun hasClearance(cookies: Map<String, String>) = cookies.containsKey(CF_CLEARANCE)

    /**
     * Fresh cookies for [request]'s host: one Kino page in cookie mode, or the ones another request of this plugin just
     * solved while this one waited for the lock (a clearance other than [staleFrom]'s). Null on any failure, and without
     * a Kino page while a failed solve for this plugin and host is recent.
     */
    private fun solve(request: Request, staleFrom: Map<String, String>?): Map<String, String>? {
        val ctx = CallContext.current() ?: return null
        val host = request.url.host
        val key = ctx.pluginId + "\u0000" + host.lowercase()
        if (recentlyFailed(key)) return null
        val lock = solving.computeIfAbsent(key) { ReentrantLock() }
        // In short slices, so an op that ends (cancel, deadline) stops waiting at once.
        while (!lock.tryLock(LOCK_SLICE_MS, TimeUnit.MILLISECONDS)) {
            if (ctx.isOver()) return null
        }
        try {
            WebCookies.get(ctx.pluginId, host)
                ?.takeIf { hasClearance(it) && it[CF_CLEARANCE] != staleFrom?.get(CF_CLEARANCE) }
                ?.let { savedCookies[host] = it; return it }
            // The holder before us failed just now: don't open another page for the same check.
            if (recentlyFailed(key) || ctx.isOver()) return null
            log("solving the browser check for $host")
            val capture = runBlocking {
                HostWebViewResolver.quiet(null) {
                    HostWebViewResolver.capture(request.url.toString(), match = null, headers = emptyMap(), timeoutMs = WebViewResolver.DEFAULT_TIMEOUT)
                }
            }
            val onHost = capture != null && (capture.finalUrl == null || capture.finalUrl.host.equals(host, ignoreCase = true))
            val cookies = capture?.cookies?.takeIf { onHost && hasClearance(it) } ?: run {
                log("browser check for $host not solved")
                markFailed(key)
                return null
            }
            failedAt.remove(key)
            savedCookies[host] = cookies
            WebCookies.put(ctx.pluginId, host, cookies)
            return cookies
        } finally {
            lock.unlock()
            if (!lock.hasQueuedThreads()) solving.remove(key, lock)
        }
    }

    /**
     * [request] with [cookies] and the WebView's user agent. The request's own cookies win, except Cloudflare's: those
     * come from [cookies] whenever it has them (a stale copy of a clearance must not undo a solve).
     */
    private fun withCookies(request: Request, cookies: Map<String, String>): Request {
        val own = request.headers("Cookie").flatMap { HostWebViewResolver.parseCookies(it).entries }.associate { it.key to it.value }
        val renewed = cookies.keys.any { isCloudflareCookie(it) }
        val merged = cookies + own.filterKeys { !(renewed && isCloudflareCookie(it)) }
        val b = request.newBuilder().removeHeader("Cookie")
            .header("Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
        WebViewResolver.webViewUserAgent?.let { b.header("User-Agent", it) }
        return b.build()
    }
}
