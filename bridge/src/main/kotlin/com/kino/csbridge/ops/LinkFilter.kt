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
 *
 * `ExtractorLink` and `SubtitleFile` are open classes with plugin-overridable, mutable properties: every check and the
 * JSON work on a [snapshot] taken once, never on the live object.
 */
object LinkFilter {
    private val DROPPED_HEADERS = setOf("host", "content-length", "connection")
    /** Kino keeps at most 20 headers anyway. */
    private const val MAX_HEADERS = 20
    private const val MAX_HEADER_VALUE_CHARS = 4096
    private const val MAX_HEADER_TOTAL_CHARS = 8192

    /** The same ceiling the protocol puts on request URLs. */
    const val MAX_URL_CHARS = 8192

    /** One read of every field the bridge uses; [headers] already merged with the referer and cleaned. */
    data class Link(
        val url: String, val type: ExtractorLinkType, val drm: Boolean, val name: String, val source: String,
        val referer: String, val quality: Int, val headers: Map<String, String>,
    )

    data class Subtitle(val lang: String, val url: String, val headers: Map<String, String>)

    fun snapshot(link: ExtractorLink): Link {
        val referer = link.referer
        val headers = link.headers
        // getAllHeaders()'s rule, on the values read once.
        val all = if (referer.isNotBlank() && headers.keys.none { it.equals("referer", ignoreCase = true) }) headers + ("referer" to referer) else headers
        return Link(link.url.trim(), link.type, link is DrmExtractorLink, link.name, link.source, referer, link.quality, clean(all))
    }

    fun snapshot(sub: SubtitleFile): Subtitle = Subtitle(sub.lang, sub.url.trim(), clean(sub.headers.orEmpty()))

    fun accept(link: Link): Boolean {
        if (link.drm) return false
        if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) return false
        return acceptUrl(link.url)
    }

    fun accept(sub: Subtitle): Boolean = acceptUrl(sub.url)

    fun accept(link: ExtractorLink): Boolean = accept(snapshot(link))

    fun accept(sub: SubtitleFile): Boolean = accept(snapshot(sub))

    /** Absolute https with a host, no userinfo, no magnet and no `.torrent` path (decoded, any case). */
    fun acceptUrl(raw: String?): Boolean {
        val url = raw?.trim() ?: return false
        if (url.isEmpty() || url.length > MAX_URL_CHARS) return false
        if (peerToPeer(url)) return false
        // OkHttp's parser, as Kino's own filter uses: only http(s) parses, a relative URL does not.
        val u = url.toHttpUrlOrNull() ?: return false
        return u.scheme == "https" && u.host.isNotEmpty() && u.username.isEmpty() && u.password.isEmpty()
    }

    /**
     * A `magnet:` URI or an http(s) URL whose last path segment is a `.torrent` file (decoded, any case). Applied to
     * links and also to items' urls and episodes' data, which are not always URLs (non-URL data is not judged here).
     */
    fun peerToPeer(raw: String?): Boolean {
        val s = raw?.trim() ?: return false
        if (s.startsWith("magnet:", ignoreCase = true)) return true
        val u = s.toHttpUrlOrNull() ?: return false
        return u.pathSegments.lastOrNull { it.isNotEmpty() }?.lowercase()?.endsWith(".torrent") == true
    }

    /** The cleaned `getAllHeaders()` of [link]. */
    fun headers(link: ExtractorLink): Map<String, String> = snapshot(link).headers

    fun headers(sub: SubtitleFile): Map<String, String> = snapshot(sub).headers

    /**
     * Without the headers the player must set itself (Host, Content-Length, Connection), without values that could split
     * a header line, values over 4096 chars, more than [MAX_HEADERS] headers or [MAX_HEADER_TOTAL_CHARS] chars in all.
     */
    private fun clean(all: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        for ((k, v) in all) {
            if (out.size >= MAX_HEADERS) break
            if (k.isBlank() || k.lowercase() in DROPPED_HEADERS) continue
            if (k.length > MAX_HEADER_VALUE_CHARS || v.length > MAX_HEADER_VALUE_CHARS) continue
            if (k.any { it == '\r' || it == '\n' || it == ':' } || v.any { it == '\r' || it == '\n' }) continue
            if (total + k.length + v.length > MAX_HEADER_TOTAL_CHARS) continue
            total += k.length + v.length
            out[k] = v
        }
        return out
    }
}
