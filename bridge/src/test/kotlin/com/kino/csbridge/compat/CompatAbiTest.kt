package com.kino.csbridge.compat

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.lang.reflect.Constructor
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden compat constructors in :library (see BRIDGE.md "Compat ABI") are binary-only, so old
 * extensions reach them by JVM descriptor. These tests call them the same way: by exact parameter
 * types, both the plain form and Kotlin's synthetic defaults form (mask + DefaultConstructorMarker).
 */
class CompatAbiTest {

    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
    private val I = Integer::class.java
    private val S = String::class.java
    private val L = List::class.java
    private val M = Map::class.java

    private fun <T> ctor(c: Class<T>, vararg types: Class<*>): Constructor<T> = c.getDeclaredConstructor(*types)

    /** The synthetic constructor: same params, then the defaults bitmask and the marker. */
    private fun <T> defaults(c: Class<T>, vararg types: Class<*>): Constructor<T> =
        c.getDeclaredConstructor(*types, Int::class.javaPrimitiveType, marker)

    @Test
    fun episodeOldConstructorMapsRatingOn100Scale() {
        val types = arrayOf(S, S, I, I, S, I, S, java.lang.Long::class.java)
        val e = ctor(Episode::class.java, *types).newInstance("data", "Ep", 1, 2, "p", 75, "d", 9L)
        assertEquals("data", e.data)
        assertEquals(1, e.season)
        assertEquals(2, e.episode)
        assertEquals(75, e.score!!.toInt(100))
        assertEquals(9L, e.date)
        assertNull(e.runTime)
    }

    @Test
    fun episodeOldDefaultsConstructorFillsDefaults() {
        val types = arrayOf(S, S, I, I, S, I, S, java.lang.Long::class.java)
        // Only `data` passed: bits 1..7 set means "use the default" for those parameters.
        val e = defaults(Episode::class.java, *types)
            .newInstance("data", null, null, null, null, null, null, null, 0b1111_1110, null)
        assertEquals("data", e.data)
        assertNull(e.name)
        assertNull(e.score)
    }

    @Test
    fun movieOldConstructorMapsOldRating() {
        val types = arrayOf(
            S, S, S, TvType::class.java, S, S, I, S, I, L, I, L, L, L,
            Boolean::class.javaPrimitiveType!!, M, M, S,
        )
        val m = ctor(MovieLoadResponse::class.java, *types).newInstance(
            "Name", "https://a.example/m", "Api", TvType.Movie, "dataUrl", "poster", 2020, "plot",
            7500, listOf("tag"), 90, mutableListOf<Any>(), null, null, false, mutableMapOf<String, String>(),
            null, "bg",
        )
        assertEquals("dataUrl", m.dataUrl)
        assertEquals(75, m.score!!.toInt(100))
        assertEquals("bg", m.backgroundPosterUrl)
        assertEquals("https://a.example/m", m.uniqueUrl)
        assertNull(m.contentRating)
    }

    /** Bits [from]..[to] set: "use the default" for those parameter indexes. */
    private fun mask(from: Int, to: Int): Int = (from..to).fold(0) { acc, i -> acc or (1 shl i) }

    @Test
    fun movieOldDefaultsConstructorFillsNonNullDefaults() {
        val types = arrayOf(
            S, S, S, TvType::class.java, S, S, I, S, I, L, I, L, L, L,
            Boolean::class.javaPrimitiveType!!, M, M, S,
        )
        // name, url, apiName, type, dataUrl given (no default in the old declaration); 5..17 default.
        val m = defaults(MovieLoadResponse::class.java, *types).newInstance(
            "Name", "https://a.example/m", "Api", TvType.Movie, "dataUrl",
            null, null, null, null, null, null, null, null, null, false, null, null, null,
            mask(5, 17), null,
        )
        assertEquals("dataUrl", m.dataUrl)
        assertTrue(m.trailers.isEmpty())
        assertEquals(false, m.comingSoon)
        assertTrue(m.syncData.isEmpty())
        assertNull(m.score)
        assertEquals("https://a.example/m", m.uniqueUrl)
    }

    @Test
    fun liveOldConstructorKeepsTypeAndRating() {
        val types = arrayOf(
            S, S, S, S, S, I, S, TvType::class.java, I, L, I, L, L, L,
            Boolean::class.javaPrimitiveType!!, M, M, S,
        )
        val l = ctor(LiveStreamLoadResponse::class.java, *types).newInstance(
            "Ch", "https://a.example/c", "Api", "dataUrl", null, null, null, TvType.Live, 5000,
            null, null, mutableListOf<Any>(), null, null, false, mutableMapOf<String, String>(), null, null,
        )
        assertEquals(TvType.Live, l.type)
        assertEquals(50, l.score!!.toInt(100))
    }

    @Test
    fun liveOldDefaultsConstructorFillsNonNullDefaults() {
        val types = arrayOf(
            S, S, S, S, S, I, S, TvType::class.java, I, L, I, L, L, L,
            Boolean::class.javaPrimitiveType!!, M, M, S,
        )
        // name, url, apiName, dataUrl given; 4..17 default, `type = TvType.Live` (index 7) included.
        val l = defaults(LiveStreamLoadResponse::class.java, *types).newInstance(
            "Ch", "https://a.example/c", "Api", "dataUrl",
            null, null, null, null, null, null, null, null, null, null, false, null, null, null,
            mask(4, 17), null,
        )
        assertEquals(TvType.Live, l.type)
        assertEquals("dataUrl", l.dataUrl)
        assertTrue(l.trailers.isEmpty())
        assertEquals(false, l.comingSoon)
        assertTrue(l.syncData.isEmpty())
        assertEquals("https://a.example/c", l.uniqueUrl)
    }

    @Test
    fun tvSeriesOldConstructorKeepsEpisodesAndStatus() {
        val types = arrayOf(
            S, S, S, TvType::class.java, L, S, I, S, ShowStatus::class.java, I, L, I, L, L, L,
            Boolean::class.javaPrimitiveType!!, M, M,
            Class.forName("com.lagradost.cloudstream3.NextAiring"), L, S,
        )
        @Suppress("DEPRECATION_ERROR")
        val ep = Episode("e1")
        val t = ctor(TvSeriesLoadResponse::class.java, *types).newInstance(
            "Show", "https://a.example/s", "Api", TvType.TvSeries, listOf(ep), null, 2021, null,
            ShowStatus.Ongoing, 10000, null, null, mutableListOf<Any>(), null, null, false,
            mutableMapOf<String, String>(), null, null, null, null,
        )
        assertEquals(listOf(ep), t.episodes)
        assertEquals(ShowStatus.Ongoing, t.showStatus)
        assertEquals(100, t.score!!.toInt(100))
    }

    @Test
    fun tvSeriesOldDefaultsConstructorFillsNonNullDefaults() {
        val types = arrayOf(
            S, S, S, TvType::class.java, L, S, I, S, ShowStatus::class.java, I, L, I, L, L, L,
            Boolean::class.javaPrimitiveType!!, M, M,
            Class.forName("com.lagradost.cloudstream3.NextAiring"), L, S,
        )
        @Suppress("DEPRECATION_ERROR")
        val ep = Episode("e1")
        // name, url, apiName, type, episodes given; 5..20 default.
        val t = defaults(TvSeriesLoadResponse::class.java, *types).newInstance(
            "Show", "https://a.example/s", "Api", TvType.TvSeries, listOf(ep),
            null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null,
            mask(5, 20), null,
        )
        assertEquals(listOf(ep), t.episodes)
        assertTrue(t.trailers.isEmpty())
        assertEquals(false, t.comingSoon)
        assertTrue(t.syncData.isEmpty())
        assertNull(t.showStatus)
        assertEquals("https://a.example/s", t.uniqueUrl)
    }

    @Test
    fun animeSearchOldConstructorAndEnumSetSetter() {
        val types = arrayOf(
            S, S, S, TvType::class.java, S, I, EnumSet::class.java, S, M, I,
            SearchQuality::class.java, M,
        )
        val a = defaults(AnimeSearchResponse::class.java, *types).newInstance(
            "A", "https://a.example/a", "Api", TvType.Anime, null, null,
            EnumSet.of(DubStatus.Subbed), null, null, null, null, null,
            // name..dubStatus given; the rest default
            0b1111_1000_0000, null,
        )
        assertEquals(setOf(DubStatus.Subbed), a.dubStatus)
        assertNull(a.score)
        AnimeSearchResponse::class.java.getMethod("setDubStatus", EnumSet::class.java)
            .invoke(a, EnumSet.of(DubStatus.Dubbed))
        assertEquals(setOf(DubStatus.Dubbed), a.dubStatus)
    }

    @Test
    fun extractorLinkOldConstructorHasNoAudioTracks() {
        val types = arrayOf(S, S, S, S, Int::class.javaPrimitiveType!!, M, S, ExtractorLinkType::class.java)
        val x = defaults(ExtractorLink::class.java, *types).newInstance(
            "src", "name", "https://a.example/v.m3u8", "https://a.example/", 720, null, null,
            ExtractorLinkType.M3U8, 0b0110_0000, null,
        )
        assertEquals(720, x.quality)
        assertEquals(ExtractorLinkType.M3U8, x.type)
        assertTrue(x.headers.isEmpty())
        assertTrue(x.audioTracks.isEmpty())
    }
}
