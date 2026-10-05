// Bridge-owned replacement for upstream library/src/androidMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.android.kt:
// same FQCN and ABI, no WebView. The library's compiled WebViewResolver (and its inner classes) is dropped from this
// module's classpaths by the DropReplacedLibraryClasses artifact transform in bridge/build.gradle.kts; the library's
// top-level helpers in the same upstream file (WebViewResolver_androidKt: toRequest, toWebResourceResponse) stay.
package com.lagradost.cloudstream3.network

import com.kino.csbridge.web.HostWebViewResolver
import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Upstream loads the page in a WebView of its own. Here the page is loaded by Kino's hidden WebView through
 * `IHost.webView` ([HostWebViewResolver]): only inside a live Kino call, https only, the timeout within Kino's
 * 1..25 s. With [additionalUrls], Kino captures every request matching [interceptUrl] or one of them (`captureAll` +
 * `alsoMatch`, at most 10 patterns) and answers what it had on a timeout too, so the extras come back as upstream
 * returns them: `first` the first [interceptUrl] match (or null), `second` the other requests matching
 * [additionalUrls], each with the method and headers the page used. [requestCallBack] runs on each of them after the
 * page, so its `true` cannot end the page early.
 * What Kino's page does not offer is ignored: [userAgent] (Kino's WebView keeps its own, which is what Cloudflare
 * wants anyway), [useOkhttp] (every subrequest goes through Kino's network), [script] / [scriptCallback] (no
 * JavaScript injection). Failure of any kind is upstream's "nothing found": `null to emptyList()`.
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = USER_AGENT,
    val useOkhttp: Boolean = true,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

    /** The constructor older plugins link against (before `script`/`timeout`); binary only. */
    @Deprecated("Binary compatibility for older plugins", level = DeprecationLevel.HIDDEN)
    constructor(
        interceptUrl: Regex,
        additionalUrls: List<Regex> = emptyList(),
        userAgent: String? = USER_AGENT,
        useOkhttp: Boolean = true,
    ) : this(interceptUrl, additionalUrls, userAgent, useOkhttp, null, null, DEFAULT_TIMEOUT)

    companion object {
        /** The user agent of Kino's hidden WebView, set by every answer it gives; null until the first one. */
        var webViewUserAgent: String? = null
        val DEFAULT_TIMEOUT = 60_000L

        /** Upstream creates a WebView to read it when unknown; here it is only what Kino reported (never a WebView). */
        @JvmName("getWebViewUserAgent1")
        fun getWebViewUserAgent(): String? = webViewUserAgent
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fixed = runBlocking { resolveUsingWebView(request).first }
        return chain.proceed(fixed ?: request)
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    /** [method] is not sent: a WebView's first load is always a GET, upstream's included. */
    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        val parsed = url.toHttpUrlOrNull() ?: return null to emptyList()
        val all = if (referer != null) headers + ("referer" to referer) else headers
        return capture(parsed.toString(), all, requestCallBack)
    }

    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        val headers = LinkedHashMap<String, String>()
        for (i in 0 until request.headers.size) headers.putIfAbsent(request.headers.name(i), request.headers.value(i))
        return capture(request.url.toString(), headers, requestCallBack)
    }

    private suspend fun capture(url: String, headers: Map<String, String>, requestCallBack: (Request) -> Boolean): Pair<Request?, List<Request>> =
        HostWebViewResolver.quiet(null to emptyList()) {
            val extra = additionalUrls.isNotEmpty()
            val got = HostWebViewResolver.capture(url, interceptUrl, headers, timeout, alsoMatch = additionalUrls, returnOnTimeout = extra)
                ?: return@quiet null to emptyList()
            // Upstream's contract is containsMatchIn on each pattern: checked again here, whatever Kino matched.
            val primary = got.requests.firstOrNull { interceptUrl.containsMatchIn(it.url.toString()) }
            val others = got.requests.filter { r -> r !== primary && additionalUrls.any { it.containsMatchIn(r.url.toString()) } }
            (listOfNotNull(primary) + others).forEach { runCatching { requestCallBack(it) } }
            primary to others
        }
}
