// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/network/RequestsHelper.kt: same ABI, no UI. Members the bridge's CloudflareKiller uses.
// Upstream's buildDefaultClient/initClient (DNS-over-HTTPS settings, disk cache) are left out: the bridge's HTTP
// client is set up by the bridge itself, never from upstream's preferences.
package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/** Set headers > Set cookies > Default headers > Default Cookies (same as upstream). */
fun getHeaders(headers: Map<String, String>, cookie: Map<String, String>): Headers {
    val cookieMap = if (cookie.isNotEmpty()) mapOf(
        "Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" }
    ) else mapOf()
    return (DEFAULT_HEADERS + headers + cookieMap).toHeaders()
}
