package com.kino.csbridge.ops

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The bridge's layer of the no-torrent rule (Kino filters again on its side): a link or subtitle reaches Kino only
 * when it is an absolute `https://` URL with a host and no userinfo, is not peer-to-peer (`magnet:`, a `.torrent`
 * path, a TORRENT or MAGNET link type) and, for links, carries no DRM (Kino never receives the keys, so it could not
 * play it).
 */
object LinkFilter {
    private val DROPPED_HEADERS = setOf("host", "content-length", "connection")
    private const val MAX_HEADERS = 64
    private const val MAX_HEADER_CHARS = 8192

    fun accept(link: ExtractorLink): Boolean {
        if (link is DrmExtractorLink) return false
        if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) return false
        return acceptUrl(link.url)
    }

    fun accept(sub: SubtitleFile): Boolean = acceptUrl(sub.url)

    /** Absolute https with a host, no userinfo, no magnet and no `.torrent` path (decoded, any case). */
    fun acceptUrl(raw: String?): Boolean {
        val url = raw?.trim() ?: return false
        if (url.isEmpty() || url.length > MAX_URL_CHARS) return false
        if (url.startsWith("magnet:", ignoreCase = true)) return false
        // OkHttp's parser, as Kino's own filter uses: only http(s) parses, a relative URL does not.
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.scheme != "https" || u.host.isEmpty() || u.username.isNotEmpty() || u.password.isNotEmpty()) return false
        if (u.pathSegments.lastOrNull { it.isNotEmpty() }?.lowercase()?.endsWith(".torrent") == true) return false
        return true
    }

    /**
     * `getAllHeaders()` (the plugin's headers plus its referer) without the ones the player must set itself
     * (Host, Content-Length, Connection) and without values that could split a header line.
     */
    fun headers(link: ExtractorLink): Map<String, String> = clean(link.getAllHeaders())

    fun headers(sub: SubtitleFile): Map<String, String> = clean(sub.headers.orEmpty())

    private fun clean(all: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in all) {
            if (out.size >= MAX_HEADERS) break
            if (k.isBlank() || k.lowercase() in DROPPED_HEADERS) continue
            if (k.length > MAX_HEADER_CHARS || v.length > MAX_HEADER_CHARS) continue
            if (k.any { it == '\r' || it == '\n' || it == ':' } || v.any { it == '\r' || it == '\n' }) continue
            out[k] = v
        }
        return out
    }

    /** The same ceiling the protocol puts on request URLs. */
    const val MAX_URL_CHARS = 8192
}
