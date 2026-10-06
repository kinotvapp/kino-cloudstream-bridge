package com.kino.csbridge.ops

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject

/**
 * PROTOCOL.md shapes built from CloudStream's response models. Every getter here may be plugin code (the models are
 * open interfaces), so callers run this on the op's plugin thread. Absent values are left out, never sent as null.
 *
 * Caps: [MAX_ITEMS] items per row or search, [MAX_ROWS] rows, [MAX_EPISODES] episodes, [MAX_PLOT] plot chars,
 * [MAX_TAGS] tags. Peer-to-peer items (Torrent type, magnet or .torrent url) and episodes (magnet or .torrent data) are
 * dropped. URLs and `data` are never truncated (a cut one is a broken one): an item whose url is over
 * [LinkFilter.MAX_URL_CHARS], or an episode whose data is over [MAX_DATA_CHARS], is left out instead.
 */
object Json {
    const val MAX_ITEMS = 200
    const val MAX_ROWS = 30
    const val MAX_EPISODES = 5000
    const val MAX_PLOT = 2000
    const val MAX_TAGS = 20
    const val MAX_DATA_CHARS = 16 * 1024
    private const val MAX_NAME = 500
    private const val MAX_TAG = 100
    private const val MAX_HEADERS = 64

    /** Item lists of one answer stop growing past this many chars, so a page stays under the service's result cap. */
    const val ITEMS_BUDGET_CHARS = 100_000

    /** Thrown for a LoadResponse the bridge will not serve (torrents); the op answers `bad_request`. */
    class Refused(message: String) : Exception(message)

    fun item(sr: SearchResponse, provider: Int): JSONObject? {
        val url = sr.url
        if (url.isEmpty() || url.length > LinkFilter.MAX_URL_CHARS) return null
        // Peer-to-peer is dropped everywhere, titles included: a Torrent-typed item, or one whose url is a magnet or a .torrent.
        val type = sr.type
        if (type == TvType.Torrent || LinkFilter.peerToPeer(url)) return null
        val year = when (sr) {
            is MovieSearchResponse -> sr.year
            is TvSeriesSearchResponse -> sr.year
            is AnimeSearchResponse -> sr.year
            else -> null
        }
        return JSONObject()
            .put("name", sr.name.take(MAX_NAME)).put("url", url).put("type", type?.name)
            .put("poster", sr.posterUrl?.takeIf { it.isNotEmpty() && it.length <= LinkFilter.MAX_URL_CHARS })
            .put("posterHeaders", headers(sr.posterHeaders)).put("year", year).put("quality", sr.quality?.name)
            .put("provider", provider)
    }

    /** At most [MAX_ITEMS] items, fewer when [budget] (chars left for the whole answer) runs out. */
    fun items(list: List<SearchResponse>?, provider: Int, budget: Budget = Budget()): JSONArray {
        val out = JSONArray()
        for (sr in list.orEmpty()) {
            if (out.length() >= MAX_ITEMS) break
            val o = item(sr, provider) ?: continue
            if (!budget.take(o.toString().length)) break
            out.put(o)
        }
        return out
    }

    /** `{rows:[{name,items}],hasNext}`. */
    fun mainPage(res: HomePageResponse, provider: Int): JSONObject {
        val budget = Budget()
        val rows = JSONArray()
        for (row in res.items.take(MAX_ROWS)) {
            rows.put(JSONObject().put("name", row.name.take(MAX_NAME)).put("items", items(row.list, provider, budget)))
        }
        return JSONObject().put("rows", rows).put("hasNext", res.hasNext)
    }

    /** `{items}`. */
    fun search(list: List<SearchResponse>?, provider: Int): JSONObject = JSONObject().put("items", items(list, provider))

    /**
     * `{title, episodes}`. Movie, AnimeMovie and Live are a one-episode list of their `dataUrl` (Kino resolves a title
     * by playing its first episode); empty when that data is not playable data (CloudStream's own `isInvalidData`).
     * TvSeries: every episode. Anime: every episode of every dub status, dubbed first, then subbed, then the rest.
     * [lean], for a list that would not fit otherwise: 1 leaves out each episode's poster and description, 2 its name too.
     */
    fun load(res: LoadResponse, lean: Int = 0): JSONObject {
        if (res is TorrentLoadResponse || res.type == TvType.Torrent) throw Refused("torrent")
        val episodes = JSONArray()
        when (res) {
            is MovieLoadResponse -> single(res.dataUrl, episodes)
            is LiveStreamLoadResponse -> single(res.dataUrl, episodes)
            is TvSeriesLoadResponse -> res.episodes.forEach { if (episodes.length() < MAX_EPISODES) episode(it, null, lean)?.let(episodes::put) }
            is AnimeLoadResponse -> {
                val byStatus = res.episodes.toMap()
                val order = listOf(DubStatus.Dubbed, DubStatus.Subbed, DubStatus.None)
                val keys = order.filter { it in byStatus } + byStatus.keys.filter { it !in order }
                for (k in keys) for (e in byStatus[k].orEmpty()) {
                    if (episodes.length() >= MAX_EPISODES) break
                    episode(e, k, lean)?.let(episodes::put)
                }
            }
            else -> throw Refused("unsupported load response")
        }
        return JSONObject().put("title", title(res)).put("episodes", episodes)
    }

    private fun single(data: String?, into: JSONArray) {
        if (!invalidData(data) && data!!.length <= MAX_DATA_CHARS && !LinkFilter.peerToPeer(data)) into.put(JSONObject().put("data", data))
    }

    /** CloudStream's `APIRepository.isInvalidData`, plus null. */
    fun invalidData(data: String?): Boolean = data == null || data.isEmpty() || data == "[]" || data == "about:blank"

    private fun episode(e: Episode, dub: DubStatus?, lean: Int): JSONObject? {
        val data = e.data
        if (invalidData(data) || data.length > MAX_DATA_CHARS || LinkFilter.peerToPeer(data)) return null
        val o = JSONObject().put("data", data).put("season", e.season).put("episode", e.episode).put("date", e.date)
            .put("dubStatus", dub?.name)
        if (lean < 2) o.put("name", e.name?.take(MAX_NAME))
        if (lean < 1) {
            o.put("description", e.description?.take(MAX_PLOT))
                .put("poster", e.posterUrl?.takeIf { it.isNotEmpty() && it.length <= LinkFilter.MAX_URL_CHARS })
        }
        return o
    }

    fun title(res: LoadResponse): JSONObject = JSONObject()
        .put("name", res.name.take(MAX_NAME)).put("url", res.url.takeIf { it.length <= LinkFilter.MAX_URL_CHARS })
        .put("type", res.type.name)
        .put("plot", res.plot?.take(MAX_PLOT))
        .put("poster", res.posterUrl?.takeIf { it.isNotEmpty() && it.length <= LinkFilter.MAX_URL_CHARS })
        .put("background", res.backgroundPosterUrl?.takeIf { it.isNotEmpty() && it.length <= LinkFilter.MAX_URL_CHARS })
        .put("year", res.year)
        .put("tags", res.tags?.filter { it.isNotBlank() }?.take(MAX_TAGS)?.map { it.take(MAX_TAG) }?.let { JSONArray(it) })
        .put("rating", res.score?.toDouble(10))
        .put("durationMin", res.duration)
        .put("kind", kindOf(res))
        .put("ids", ids(res))

    private val IMDB = Regex("^tt\\d{1,10}$")

    /**
     * CloudStream's id prefixes are empty until the app sets them (upstream's AccountManager.initMainAPI), and then
     * `addMalId`, `addAniListId` and `addKitsuId` would all write the one key. Called before any plugin loads.
     */
    fun initIdPrefixes() {
        LoadResponse.malIdPrefix = "mal"
        LoadResponse.kitsuIdPrefix = "kitsu"
        LoadResponse.aniListIdPrefix = "anilist"
        LoadResponse.simklIdPrefix = "simkl"
    }

    /**
     * `{imdb:"tt…", tmdb, mal, anilist, kitsu}` from what the plugin put in `syncData` (upstream's `addImdbId`,
     * `addTMDbId`, `addMalId`, `addAniListId`, `addKitsuId`); only valid values, null (so left out) when none is.
     * Other services are ignored. Ints are positive digits strings clamped away when over Int.MAX_VALUE.
     */
    private fun ids(res: LoadResponse): JSONObject? {
        fun num(v: String?): Int? = v?.trim()?.takeIf { it.length in 1..10 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        fun <T> guard(f: () -> T?): T? = try { f() } catch (_: Exception) { null }
        val o = JSONObject()
        guard { with(LoadResponse) { res.getImdbId() } }?.trim()?.takeIf { IMDB.matches(it) }?.let { o.put("imdb", it) }
        num(guard { with(LoadResponse) { res.getTMDbId() } })?.let { o.put("tmdb", it) }
        num(guard { with(LoadResponse) { res.getMalId() } })?.let { o.put("mal", it) }
        num(guard { with(LoadResponse) { res.getAniListId() } })?.let { o.put("anilist", it) }
        num(guard { with(LoadResponse) { res.getKitsuId() } })?.let { o.put("kitsu", it) }
        return o.takeIf { it.length() > 0 }
    }

    /** Which response class it was: "movie", "live", "series" or "anime". */
    private fun kindOf(res: LoadResponse): String = when (res) {
        is MovieLoadResponse -> "movie"
        is LiveStreamLoadResponse -> "live"
        is AnimeLoadResponse -> "anime"
        else -> "series"
    }

    /** A link event from a [LinkFilter.snapshot] (never from the live, plugin-mutable object). */
    fun link(l: LinkFilter.Link): JSONObject = JSONObject()
        .put("type", "link").put("url", l.url).put("name", l.name.take(MAX_NAME)).put("source", l.source.take(MAX_NAME))
        .put("referer", l.referer.takeIf { it.isNotEmpty() && it.length <= LinkFilter.MAX_URL_CHARS })
        .put("quality", l.quality).put("linkType", l.type.name)
        .put("headers", JSONObject(l.headers as Map<*, *>))

    fun subtitle(s: LinkFilter.Subtitle): JSONObject = JSONObject()
        .put("type", "subtitle").put("lang", s.lang.take(MAX_NAME)).put("url", s.url)
        .put("headers", JSONObject(s.headers as Map<*, *>))

    private fun headers(h: Map<String, String>?): JSONObject? {
        if (h.isNullOrEmpty()) return null
        val o = JSONObject()
        for ((k, v) in h) { if (o.length() >= MAX_HEADERS) break; o.put(k, v) }
        return o
    }

    /** Chars left for item lists in one answer. */
    class Budget(private var left: Int = ITEMS_BUDGET_CHARS) {
        fun take(n: Int): Boolean { if (n > left) return false; left -= n; return true }
    }
}
