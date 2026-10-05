package com.kino.csbridge.ops

import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkFilterTest {
    private fun link(url: String, type: ExtractorLinkType? = null, init: suspend ExtractorLink.() -> Unit = {}) =
        runBlocking { newExtractorLink("src", "name", url, type, init) }

    @Test fun `https video and m3u8 links pass`() {
        assertTrue(LinkFilter.accept(link("https://cdn.example/v/1.mp4")))
        assertTrue(LinkFilter.accept(link("https://cdn.example/hls/master.m3u8?t=1", ExtractorLinkType.M3U8)))
        assertTrue(LinkFilter.accept(link("https://cdn.example/d/manifest.mpd", ExtractorLinkType.DASH)))
    }

    @Test fun `torrent and magnet link types are dropped whatever the url`() {
        assertFalse(LinkFilter.accept(link("https://cdn.example/v/1.mp4", ExtractorLinkType.TORRENT)))
        assertFalse(LinkFilter.accept(link("https://cdn.example/v/1.mp4", ExtractorLinkType.MAGNET)))
    }

    @Test fun `magnet urls and torrent paths are dropped`() {
        assertFalse(LinkFilter.accept(link("magnet:?xt=urn:btih:abc", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("MAGNET:?xt=urn:btih:abc", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("https://t.example/file.torrent", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("https://t.example/FILE.TORRENT?x=1", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("https://t.example/file%2Etorrent", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("https://t.example/file.torrent/", ExtractorLinkType.VIDEO)))
        assertTrue(LinkFilter.accept(link("https://t.example/torrent/video.mp4")))
    }

    @Test fun `non-https, relative, userinfo and DRM links are dropped`() {
        assertFalse(LinkFilter.accept(link("http://cdn.example/v/1.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("/embed/novideo.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("//cdn.example/v/1.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("embed/novideo.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("ftp://cdn.example/v.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("https://user:pw@cdn.example/v.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(LinkFilter.accept(link("", ExtractorLinkType.VIDEO)))
        val drm = runBlocking { newDrmExtractorLink("s", "n", "https://cdn.example/d.mpd", ExtractorLinkType.DASH, java.util.UUID.randomUUID()) }
        assertFalse(LinkFilter.accept(drm))
    }

    @Test fun `subtitles follow the same url rule`() = runBlocking {
        assertTrue(LinkFilter.accept(newSubtitleFile("es", "https://s.example/a.vtt")))
        assertFalse(LinkFilter.accept(newSubtitleFile("es", "http://s.example/a.vtt")))
        assertFalse(LinkFilter.accept(newSubtitleFile("es", "/a.vtt")))
    }

    @Test fun `headers merge the referer and drop Host, Content-Length, Connection and split values`() {
        val l = link("https://cdn.example/v.mp4") {
            referer = "https://site.example/"
            headers = mapOf("User-Agent" to "UA", "Host" to "evil", "content-length" to "1", "CONNECTION" to "close",
                "X-Bad" to "a\r\nInjected: 1")
        }
        assertEquals(mapOf("User-Agent" to "UA", "referer" to "https://site.example/"), LinkFilter.headers(l))
        // An explicit Referer wins over the field, as getAllHeaders does.
        val l2 = link("https://cdn.example/v.mp4") { referer = "https://a.example/"; headers = mapOf("Referer" to "https://b.example/") }
        assertEquals(mapOf("Referer" to "https://b.example/"), LinkFilter.headers(l2))
    }
}
