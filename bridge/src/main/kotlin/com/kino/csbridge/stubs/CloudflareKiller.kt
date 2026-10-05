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
 * - When the solve fails, the check page itself is returned (read into memory before the solve, so no second request).
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = setOf(403, 503)
        private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")
        private const val CF_CLEARANCE = "cf_clearance"
        private const val CHECK_PAGE_MAX_BYTES = 256L * 1024
        private val solving = ConcurrentHashMap<String, ReentrantLock>()

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
        val checkPage = first.newBuilder().body(first.peekBody(CHECK_PAGE_MAX_BYTES)).build()
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
     * solved while this one waited for the lock (anything but [staleFrom]). Null on any failure.
     */
    private fun solve(request: Request, staleFrom: Map<String, String>?): Map<String, String>? {
        val ctx = CallContext.current() ?: return null
        val host = request.url.host
        val lock = solving.computeIfAbsent(ctx.pluginId + "\u0000" + host.lowercase()) { ReentrantLock() }
        val waitMs = ctx.remainingMs().coerceAtMost(HostWebViewResolver.MAX_TIMEOUT_MS * 2)
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            WebCookies.get(ctx.pluginId, host)?.takeIf { hasClearance(it) && it != staleFrom }?.let { savedCookies[host] = it; return it }
            log("solving the browser check for $host")
            val capture = runBlocking {
                HostWebViewResolver.quiet(null) {
                    HostWebViewResolver.capture(request.url.toString(), match = null, headers = emptyMap(), timeoutMs = WebViewResolver.DEFAULT_TIMEOUT)
                }
            }
            val cookies = capture?.cookies?.takeIf { hasClearance(it) } ?: run {
                log("browser check for $host not solved")
                return null
            }
            savedCookies[host] = cookies
            WebCookies.put(ctx.pluginId, host, cookies)
            return cookies
        } finally {
            lock.unlock()
        }
    }

    /** [request] with the solved cookies (the request's own win, as upstream) and the WebView's user agent. */
    private fun withCookies(request: Request, cookies: Map<String, String>): Request {
        val own = request.headers("Cookie").flatMap { HostWebViewResolver.parseCookies(it).entries }.associate { it.key to it.value }
        val merged = cookies + own
        val b = request.newBuilder().removeHeader("Cookie")
            .header("Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
        WebViewResolver.webViewUserAgent?.let { b.header("User-Agent", it) }
        return b.build()
    }
}
