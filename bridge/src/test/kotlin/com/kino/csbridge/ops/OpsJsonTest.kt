package com.kino.csbridge.ops

import com.kino.csbridge.contract.CsWire
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTorrentLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class OpsJsonTest {
    /** A plugin provider whose answers each test sets. */
    open class FakeApi : MainAPI() {
        override var name = "Fake"
        override var mainUrl = "https://fake.example"
        override val hasMainPage = true
        override val mainPage = mainPageOf("/movies" to "Películas", "/live" to "Canales")
        var onLoad: suspend MainAPI.(String) -> LoadResponse? = { null }
        var onSearch: suspend MainAPI.(String, Int) -> List<SearchResponse>? = { _, _ -> null }
        var onMainPage: suspend MainAPI.(Int, MainPageRequest) -> HomePageResponse? = { _, _ -> null }
        var onLinks: suspend (String, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean = { _, _, _ -> false }

        override suspend fun load(url: String) = onLoad(url)
        override suspend fun search(query: String, page: Int) =
            onSearch(query, page)?.let { com.lagradost.cloudstream3.newSearchResponseList(it, false) }
        override suspend fun getMainPage(page: Int, request: MainPageRequest) = onMainPage(page, request)
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                                       callback: (ExtractorLink) -> Unit) = onLinks(data, subtitleCallback, callback)
    }

    /** An old plugin: overrides only `search(query)`, reached through MainAPI's default `search(query, page)`. */
    class OldSearchApi : MainAPI() {
        override var name = "Old"
        override var mainUrl = "https://old.example"
        var calls = 0
        override suspend fun search(query: String): List<SearchResponse> { calls++; return listOf(newMovieSearchResponse("Old $query", "/o/1")) }
    }

    private val events = CopyOnWriteArrayList<String>()
    /** False simulates a binder that refuses the event. */
    @Volatile private var emitOk = true

    private fun op(op: String, args: JSONObject, vararg apis: MainAPI): String = runBlocking {
        val req = CsWire.parseOpRequest(CsWire.opRequest(op, "c1", "cloudstream-fx", 10_000, args))!!
        ProviderOps.run(req, { emitOk && events.add(it) }) { apis.toList() }
    }

    private fun load(api: FakeApi): JSONObject = JSONObject(op(CsWire.Op.LOAD, JSONObject().put("provider", 0).put("url", "/t/1"), api))

    @Test fun `a movie is a one-episode list of its dataUrl, with every title field`() {
        val api = FakeApi().apply {
            onLoad = { url ->
                newMovieLoadResponse("Película", url, TvType.Movie, "https://fake.example/play/1") {
                    plot = "p".repeat(3000); posterUrl = "https://img.example/p.jpg"; backgroundPosterUrl = "https://img.example/b.jpg"
                    year = 2024; tags = (1..30).map { "t$it" } + " "; score = Score.from10(7.5); duration = 118
                }
            }
        }
        val o = load(api)
        assertEquals(1, o.getJSONArray("episodes").length())
        assertEquals("""{"data":"https:\/\/fake.example\/play\/1"}""", o.getJSONArray("episodes").getJSONObject(0).toString())
        val t = o.getJSONObject("title")
        assertEquals("Película", t.getString("name")); assertEquals("https://fake.example/t/1", t.getString("url")) // fixUrl, as upstream
        assertEquals("Movie", t.getString("type")); assertEquals(2000, t.getString("plot").length)
        assertEquals("https://img.example/p.jpg", t.getString("poster")); assertEquals("https://img.example/b.jpg", t.getString("background"))
        assertEquals(2024, t.getInt("year")); assertEquals(20, t.getJSONArray("tags").length())
        assertEquals(7.5, t.getDouble("rating"), 0.001); assertEquals(118, t.getInt("durationMin")); assertEquals("movie", t.getString("kind"))
    }

    @Test fun `a LiveStreamLoadResponse is a one-episode list too`() {
        val api = FakeApi().apply { onLoad = { url -> newLiveStreamLoadResponse("Canal 1", url, "https://live.example/c1.m3u8") } }
        val o = load(api)
        assertEquals(1, o.getJSONArray("episodes").length())
        assertEquals("https://live.example/c1.m3u8", o.getJSONArray("episodes").getJSONObject(0).getString("data"))
        assertEquals("Live", o.getJSONObject("title").getString("type")); assertEquals("live", o.getJSONObject("title").getString("kind"))
    }

    @Test fun `an AnimeMovie is a one-episode list, and unplayable data is an empty list`() {
        val api = FakeApi().apply { onLoad = { url -> newMovieLoadResponse("Filme", url, TvType.AnimeMovie, "{\"id\":3}") } }
        val o = load(api)
        assertEquals("{\"id\":3}", o.getJSONArray("episodes").getJSONObject(0).getString("data"))
        assertEquals("AnimeMovie", o.getJSONObject("title").getString("type"))
        api.onLoad = { url -> newMovieLoadResponse("Pronto", url, TvType.Movie, "") }
        assertEquals(0, load(api).getJSONArray("episodes").length())
    }

    @Test fun `a series sends every episode with its fields, capped at 5000`() {
        val api = FakeApi().apply {
            onLoad = { url ->
                val eps = (1..3).map { i ->
                    newEpisode("/e/$i") { season = 1; episode = i; name = "E$i"; date = 1_700_000_000_000L; posterUrl = "https://img.example/$i.jpg"; description = "D$i" }
                }
                newTvSeriesLoadResponse("Serie", url, TvType.TvSeries, eps)
            }
        }
        val eps = load(api).getJSONArray("episodes")
        assertEquals(3, eps.length())
        val e = eps.getJSONObject(0)
        assertEquals("https://fake.example/e/1", e.getString("data")); assertEquals(1, e.getInt("episode")); assertEquals(1, e.getInt("season"))
        assertEquals("E1", e.getString("name")); assertEquals(1_700_000_000_000L, e.getLong("date")); assertFalse(e.has("dubStatus"))
        assertEquals("https://img.example/1.jpg", e.getString("poster")); assertEquals("D1", e.getString("description"))
        assertEquals("series", load(api).getJSONObject("title").getString("kind"))
        api.onLoad = { url -> newTvSeriesLoadResponse("Serie", url, TvType.TvSeries, (1..6000).map { i -> newEpisode("/e/$i") { episode = i } }) }
        assertEquals(5000, load(api).getJSONArray("episodes").length())
    }

    @Test fun `anime sends every dub status, dubbed first then subbed, each episode marked`() {
        val api = FakeApi().apply {
            onLoad = { url ->
                newAnimeLoadResponse("Anime", url, TvType.Anime) {
                    addEpisodes(DubStatus.Subbed, listOf(newEpisode("/s/1") { episode = 1 }, newEpisode("/s/2") { episode = 2 }))
                    addEpisodes(DubStatus.Dubbed, listOf(newEpisode("/d/1") { episode = 1 }))
                }
            }
        }
        val eps = load(api).getJSONArray("episodes")
        assertEquals(listOf("Dubbed" to "/d/1", "Subbed" to "/s/1", "Subbed" to "/s/2"),
            (0 until eps.length()).map { eps.getJSONObject(it).let { e -> e.getString("dubStatus") to e.getString("data").removePrefix("https://fake.example") } })
    }

    @Test fun `a torrent load response is refused with bad_request`() {
        val api = FakeApi().apply { onLoad = { url -> newTorrentLoadResponse("T", url, magnet = "magnet:?xt=urn:btih:x") } }
        assertEquals(CsWire.Err.BAD_REQUEST to "torrent", CsWire.errorOf(load(api).toString()))
    }

    @Test fun `search items carry type, poster, headers, year, quality and provider, no type is filtered`() {
        val api = FakeApi().apply {
            onSearch = { q, page ->
                assertEquals(1, page)
                listOf(
                    newMovieSearchResponse("Película $q", "/m/1") { posterUrl = "https://img.example/1.jpg"; posterHeaders = mapOf("Referer" to "https://fake.example/"); year = 2020; quality = SearchQuality.HD },
                    newTvSeriesSearchResponse("Serie", "/s/1") { year = 2019 },
                    newLiveSearchResponse("Canal", "/c/1"),
                    newMovieSearchResponse("Adulto", "/x/1", TvType.NSFW),
                    newMovieSearchResponse("Disco", "/a/1", TvType.Music),
                    newMovieSearchResponse("Libro", "/b/1", TvType.AudioBook),
                )
            }
        }
        val items = JSONObject(op(CsWire.Op.SEARCH, JSONObject().put("provider", 1).put("query", "x"), FakeApi(), api)).getJSONArray("items")
        assertEquals(listOf("Movie", "TvSeries", "Live", "NSFW", "Music", "AudioBook"), (0 until items.length()).map { items.getJSONObject(it).getString("type") })
        val m = items.getJSONObject(0)
        assertEquals("Película x", m.getString("name")); assertEquals("https://fake.example/m/1", m.getString("url"))
        assertEquals("https://img.example/1.jpg", m.getString("poster")); assertEquals("https://fake.example/", m.getJSONObject("posterHeaders").getString("Referer"))
        assertEquals(2020, m.getInt("year")); assertEquals("HD", m.getString("quality")); assertEquals(1, m.getInt("provider"))
        assertEquals(2019, items.getJSONObject(1).getInt("year"))
        assertFalse(items.getJSONObject(2).has("year")); assertFalse(items.getJSONObject(2).has("poster"))
    }

    @Test fun `an old plugin's search(query) is reached and an empty query never asks the provider`() {
        val old = OldSearchApi()
        val items = JSONObject(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "q"), old)).getJSONArray("items")
        assertEquals("Old q", items.getJSONObject(0).getString("name"))
        assertEquals("[]", JSONObject(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", ""), old)).getJSONArray("items").toString())
        assertEquals(1, old.calls)
    }

    @Test fun `search caps items at 200`() {
        val api = FakeApi().apply { onSearch = { _, _ -> (1..250).map { newMovieSearchResponse("M$it", "/m/$it") } } }
        assertEquals(200, JSONObject(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "x"), api)).getJSONArray("items").length())
    }

    @Test fun `mainPage asks the indexed page with its data and caps rows at 30`() {
        val api = FakeApi().apply {
            onMainPage = { page, req ->
                assertEquals(3, page); assertEquals("Canales", req.name); assertEquals("/live", req.data)
                @Suppress("DEPRECATION_ERROR")
                HomePageResponse((1..40).map { r -> HomePageList("R$r", listOf(newLiveSearchResponse("C$r", "/c/$r"))) }, hasNext = true)
            }
        }
        val o = JSONObject(op(CsWire.Op.MAIN_PAGE, JSONObject().put("provider", 0).put("index", 1).put("page", 3), api))
        assertEquals(30, o.getJSONArray("rows").length()); assertTrue(o.getBoolean("hasNext"))
        val row = o.getJSONArray("rows").getJSONObject(0)
        assertEquals("R1", row.getString("name")); assertEquals("Live", row.getJSONArray("items").getJSONObject(0).getString("type"))
        assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(op(CsWire.Op.MAIN_PAGE, JSONObject().put("provider", 0).put("index", 9).put("page", 1), api))!!.first)
        val single = FakeApi().apply { onMainPage = { _, req -> newHomePageResponse(req, listOf(newMovieSearchResponse("M", "/m"))) } }
        assertEquals("Películas", JSONObject(op(CsWire.Op.MAIN_PAGE, JSONObject().put("provider", 0).put("index", 0).put("page", 1), single))
            .getJSONArray("rows").getJSONObject(0).getString("name"))
    }

    @Test fun `an unknown provider index is not_installed, a missing one bad_request`() {
        assertEquals(CsWire.Err.NOT_INSTALLED, CsWire.errorOf(op(CsWire.Op.SEARCH, JSONObject().put("provider", 2).put("query", "x"), FakeApi()))!!.first)
        assertEquals(CsWire.Err.NOT_INSTALLED, CsWire.errorOf(op(CsWire.Op.LOAD, JSONObject().put("provider", -1).put("url", "u"), FakeApi()))!!.first)
        assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(op(CsWire.Op.LOAD, JSONObject().put("url", "u"), FakeApi()))!!.first)
    }

    @Test fun `loadLinks streams each accepted link before the next callback and before done, and drops the rest`() {
        var seenBeforeSecond = -1
        val api = FakeApi().apply {
            onLinks = { data, subs, cb ->
                assertEquals("https://fake.example/play/1", data)
                cb(newExtractorLink("S", "1080p", "https://cdn.example/a.m3u8", ExtractorLinkType.M3U8) { quality = 1080; referer = "https://fake.example/" })
                delay(300)
                seenBeforeSecond = events.size
                cb(newExtractorLink("S", "t", "https://cdn.example/a.mp4", ExtractorLinkType.TORRENT))
                cb(newExtractorLink("S", "m", "magnet:?xt=urn:btih:x"))
                cb(newExtractorLink("S", "h", "http://cdn.example/a.mp4", ExtractorLinkType.VIDEO))
                cb(newExtractorLink("S", "r", "/embed/novideo.mp4", ExtractorLinkType.VIDEO))
                subs(newSubtitleFile("es", "https://s.example/a.vtt"))
                subs(newSubtitleFile("en", "http://s.example/a.vtt"))
                cb(newExtractorLink("S", "720p", "https://cdn.example/b.mp4") { quality = 720 })
                true
            }
        }
        val done = JSONObject(op(CsWire.Op.LOAD_LINKS, JSONObject().put("provider", 0).put("data", "https://fake.example/play/1"), api))
        assertEquals(1, seenBeforeSecond)
        assertTrue(done.getBoolean("returned")); assertEquals(2, done.getInt("links"))
        assertEquals(3, events.size)
        val first = JSONObject(events[0])
        assertEquals("link", first.getString("type")); assertEquals("https://cdn.example/a.m3u8", first.getString("url"))
        assertEquals("1080p", first.getString("name")); assertEquals("S", first.getString("source")); assertEquals(1080, first.getInt("quality"))
        assertEquals("M3U8", first.getString("linkType")); assertEquals("https://fake.example/", first.getString("referer"))
        assertEquals("https://fake.example/", first.getJSONObject("headers").getString("referer"))
        val sub = JSONObject(events[1])
        assertEquals("subtitle", sub.getString("type")); assertEquals("es", sub.getString("lang")); assertEquals("https://s.example/a.vtt", sub.getString("url"))
        assertEquals("VIDEO", JSONObject(events[2]).getString("linkType"))
    }

    @Test fun `a link a plugin's own coroutine sends after done never reaches Kino`() {
        val api = FakeApi().apply {
            onLinks = { _, _, cb ->
                CoroutineScope(Dispatchers.IO).launch { delay(200); cb(newExtractorLink("S", "late", "https://cdn.example/late.mp4")) }
                false
            }
        }
        val done = JSONObject(op(CsWire.Op.LOAD_LINKS, JSONObject().put("provider", 0).put("data", "d"), api))
        assertFalse(done.getBoolean("returned")); assertEquals(0, done.getInt("links"))
        Thread.sleep(500)
        assertTrue(events.isEmpty())
    }

    @Test fun `invalid data never reaches the provider`() {
        var called = false
        val api = FakeApi().apply { onLinks = { _, _, _ -> called = true; true } }
        val done = JSONObject(op(CsWire.Op.LOAD_LINKS, JSONObject().put("provider", 0).put("data", "about:blank"), api))
        assertFalse(done.getBoolean("returned")); assertFalse(called)
    }

    @Test fun `a provider timeout hint is upstream's clamp, and a search that runs past it is timeout`() {
        assertEquals(120_000L, ProviderOps.timeoutOf(null)); assertEquals(5_000L, ProviderOps.timeoutOf(1))
        assertEquals(480_000L, ProviderOps.timeoutOf(10_000_000))
        val api = object : FakeApi() { override val searchTimeoutMs: Long = 1 }.apply { onSearch = { _, _ -> awaitCancellation() } }
        val t0 = System.nanoTime()
        assertEquals(CsWire.Err.TIMEOUT, CsWire.errorOf(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "x"), api))!!.first)
        assertTrue((System.nanoTime() - t0) / 1_000_000 in 4_500..9_000)
    }

    @Test fun `a long series is rebuilt lean instead of failing`() {
        val api = FakeApi().apply {
            onLoad = { url -> newTvSeriesLoadResponse("S", url, TvType.TvSeries, (1..1000).map { i -> newEpisode("/e/$i") { episode = i; description = "d".repeat(400) } }) }
        }
        val o = load(api)
        assertTrue(o.toString().length <= ProviderOps.LOAD_LEAN_ABOVE_CHARS)
        assertTrue(ProviderOps.LOAD_LEAN_ABOVE_CHARS < com.kino.csbridge.BridgeService.MAX_RESULT_CHARS)
        assertEquals(1000, o.getJSONArray("episodes").length())
        val e = o.getJSONArray("episodes").getJSONObject(0)
        assertFalse(e.has("description")); assertEquals(1, e.getInt("episode"))
    }


    @Test fun `the largest legal search and mainPage answers stay under the result cap`() {
        val long = "n".repeat(500)
        val api = FakeApi().apply {
            onSearch = { _, _ -> (1..200).map { newMovieSearchResponse(long, "/m/$it" + "u".repeat(300)) { posterUrl = "https://img.example/" + "p".repeat(300) } } }
            onMainPage = { _, _ ->
                @Suppress("DEPRECATION_ERROR")
                HomePageResponse((1..30).map { r -> HomePageList(long, (1..200).map { newMovieSearchResponse(long, "/m/$r/$it") }) }, true)
            }
        }
        val search = op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "x"), api)
        val page = op(CsWire.Op.MAIN_PAGE, JSONObject().put("provider", 0).put("index", 0).put("page", 1), api)
        assertTrue(search.length.toString(), search.length < com.kino.csbridge.BridgeService.MAX_RESULT_CHARS)
        assertTrue(page.length.toString(), page.length < com.kino.csbridge.BridgeService.MAX_RESULT_CHARS)
        assertEquals(30, JSONObject(page).getJSONArray("rows").length())
    }

    @Test fun `peer-to-peer titles and episodes are dropped too`() {
        val api = FakeApi().apply {
            onSearch = { _, _ ->
                listOf(newMovieSearchResponse("T", "/t/1", TvType.Torrent), newMovieSearchResponse("M", "magnet:?xt=urn:btih:x", fix = false),
                    newMovieSearchResponse("F", "https://t.example/a.torrent"), newMovieSearchResponse("OK", "/ok"))
            }
        }
        val items = JSONObject(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "x"), api)).getJSONArray("items")
        assertEquals(1, items.length()); assertEquals("OK", items.getJSONObject(0).getString("name"))
        api.onLoad = { url -> newMovieLoadResponse("M", url, TvType.Movie, "magnet:?xt=urn:btih:x") }
        assertEquals(0, load(api).getJSONArray("episodes").length())
        api.onLoad = { url ->
            newTvSeriesLoadResponse("S", url, TvType.TvSeries, listOf(newEpisode("magnet:?xt=urn:btih:y", fix = false), newEpisode("https://t.example/e.torrent"), newEpisode("/e/1")))
        }
        val eps = load(api).getJSONArray("episodes")
        assertEquals(1, eps.length()); assertEquals("https://fake.example/e/1", eps.getJSONObject(0).getString("data"))
    }

    @Test fun `index and page must be integers`() {
        val api = FakeApi()
        assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(op(CsWire.Op.MAIN_PAGE, JSONObject().put("provider", 0).put("index", 1.7).put("page", 1), api))!!.first)
        assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(op(CsWire.Op.MAIN_PAGE, JSONObject().put("provider", 0).put("index", 0).put("page", 1.5), api))!!.first)
    }

    @Test fun `an oversized event is dropped and a refused event is not counted`() {
        val api = FakeApi().apply {
            onLinks = { _, _, cb ->
                cb(newExtractorLink("S", "big", "https://cdn.example/" + "a".repeat(8150)) { referer = "https://r.example/" + "b".repeat(8150) })
                cb(newExtractorLink("S", "ok", "https://cdn.example/ok.mp4"))
                true
            }
        }
        val done = JSONObject(op(CsWire.Op.LOAD_LINKS, JSONObject().put("provider", 0).put("data", "d"), api))
        assertEquals(1, done.getInt("links")); assertEquals(1, events.size)
        assertEquals("ok", JSONObject(events[0]).getString("name"))
        events.clear(); emitOk = false
        val refused = JSONObject(op(CsWire.Op.LOAD_LINKS, JSONObject().put("provider", 0).put("data", "d"), api))
        assertEquals(0, refused.getInt("links")); assertTrue(events.isEmpty())
    }

    /** A plugin link whose url getter answers https once, then a magnet. */
    @Suppress("DEPRECATION")
    class ShiftyLink : ExtractorLink(source = "S", name = "n", url = "https://cdn.example/a.mp4", referer = "", quality = 720,
        type = ExtractorLinkType.VIDEO) {
        var reads = 0
        override val url: String get() = if (reads++ == 0) "https://cdn.example/a.mp4" else "magnet:?xt=urn:btih:z"
    }

    @Test fun `a link is read once, so the filter and the event see the same url`() {
        val link = ShiftyLink()
        val api = FakeApi().apply { onLinks = { _, _, cb -> cb(link); true } }
        op(CsWire.Op.LOAD_LINKS, JSONObject().put("provider", 0).put("data", "d"), api)
        assertEquals(1, link.reads)
        assertEquals("https://cdn.example/a.mp4", JSONObject(events.single()).getString("url"))
    }
}
