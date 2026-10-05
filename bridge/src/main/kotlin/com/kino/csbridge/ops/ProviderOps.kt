package com.kino.csbridge.ops

import com.kino.csbridge.contract.CsWire
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.fixUrl
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provider ops: `search`, `mainPage`, `load` and `loadLinks`, each on `providers[args.provider]` of the call's
 * plugin, called through [MainAPI]'s own virtual methods (an old plugin that only overrides `search(query)` is reached
 * through the default `search(query, page)`).
 *
 * All plugin code (the lookup, which may load the plugin, the call, and the mapping, whose getters may be plugin code)
 * runs through [PluginWork.run]: on the plugin pool, detached from the op's job, awaited cancellably, so the service
 * answers on time even if the plugin is stuck in blocking code (Jsoup, a sleep) that cancellation cannot interrupt.
 * What the plugin does afterwards changes nothing: its HTTP is refused (the op's job is over) and [Gate] drops its
 * late link events, so nothing reaches Kino after the one `done`. A plugin with too many such stuck tasks is busy:
 * its ops are answered `timeout` at once.
 *
 * Each provider's own timeout hint (`searchTimeoutMs`, `getMainPageTimeoutMs`, `loadTimeoutMs`, `loadLinksTimeoutMs`)
 * is honoured as upstream's APIRepository does (default 2 min, clamped to 5 s..8 min), inside Kino's deadline.
 */
internal object ProviderOps {
    private const val DEFAULT_TIMEOUT_MS = 120_000L
    private const val MIN_TIMEOUT_MS = 5_000L
    private const val MAX_TIMEOUT_MS = 4 * DEFAULT_TIMEOUT_MS
    const val MAX_LINK_EVENTS = 200
    const val MAX_SUBTITLE_EVENTS = 100
    /** One event's JSON past this is dropped (and not counted): binder's async space is shared with the `done`. */
    const val MAX_EVENT_CHARS = 16 * 1024
    /**
     * All events of one op together: past this, events are dropped and not counted. About 200 KB as parcels, plus an
     * inline done of up to 200 KB (larger ones go through a pipe), stays under binder's ~508 KB oneway space.
     */
    const val MAX_EVENTS_TOTAL_CHARS = 100_000
    /**
     * A `load` answer over this many UTF-8 bytes is rebuilt leaner (no episode posters and descriptions, then no names):
     * the streamed result's cap. Anything below it reaches Kino whole (inline or through `doneStream`).
     */
    const val LOAD_LEAN_ABOVE_BYTES = CsWire.MAX_STREAM_RESULT_BYTES.toLong()

    /** [s]'s length in UTF-8 without encoding it (a lone surrogate counted as 3 bytes, never fewer than it takes). */
    fun utf8Length(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** Ops this object answers. */
    val OPS = setOf(CsWire.Op.SEARCH, CsWire.Op.MAIN_PAGE, CsWire.Op.LOAD, CsWire.Op.LOAD_LINKS)

    /** Upstream's `APIRepository.getTimeout`. */
    fun timeoutOf(hint: Long?): Long = (hint ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /** `timeout` with detail "busy": the plugin has [PluginWork.MAX_LEAKED] tasks stuck in blocking code. */
    val BUSY: String = CsWire.error(CsWire.Err.TIMEOUT, "busy")

    /** An integral JSON number as an Int, else null (1.7 is not an index). */
    fun intArg(args: JSONObject, name: String): Int? =
        (args.opt(name) as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()

    /**
     * Runs one provider op. [providers] is the plugin's provider list (it may load the plugin, so it runs on the plugin
     * pool too). [emit] sends one event and says whether it left the process. Returns the `done` JSON; throws what the
     * plugin threw, for the service to map.
     */
    suspend fun run(req: CsWire.OpRequest, emit: (String) -> Boolean, providers: () -> List<MainAPI>): String {
        if (PluginWork.busy(req.pluginId)) return BUSY
        val gate = Gate(emit)
        val call = Call(req.pluginId)
        try {
            val args = req.args
            // The plugin first: an op on a plugin the bridge does not hold is not_installed whatever its arguments.
            val apis = call.plugin { providers().toList() }
            val index = intArg(args, "provider") ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "provider")
            val api = apis.getOrNull(index) ?: return CsWire.error(CsWire.Err.NOT_INSTALLED, "provider")
            return when (req.op) {
                CsWire.Op.SEARCH -> call.search(api, index, args)
                CsWire.Op.MAIN_PAGE -> call.mainPage(api, index, args)
                CsWire.Op.LOAD -> call.load(api, args)
                CsWire.Op.LOAD_LINKS -> call.loadLinks(api, args, gate)
                else -> CsWire.error(CsWire.Err.BAD_REQUEST, "op")
            }
        } finally {
            gate.close()
        }
    }

    private class Call(val pluginId: String) {
        suspend fun <T> plugin(block: suspend () -> T): T = PluginWork.run(pluginId, block = block)

        /** [block] on the plugin pool, bounded by the provider's [hint] (upstream's clamp); [onTimeout] answers when it runs out. */
        suspend fun timed(hint: Long?, onTimeout: () -> String, block: suspend () -> String): String =
            withTimeoutOrNull(timeoutOf(hint)) { plugin(block) } ?: onTimeout()

        suspend fun search(api: MainAPI, provider: Int, args: JSONObject): String {
            val query = args.opt("query") as? String ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "query")
            // Upstream answers an empty query with no results, without asking the provider.
            if (query.isEmpty()) return Json.search(emptyList(), provider).toString()
            val hint = plugin { api.searchTimeoutMs }
            return timed(hint, { CsWire.error(CsWire.Err.TIMEOUT, "provider timeout") }) {
                val res = api.search(query, 1) ?: return@timed CsWire.error(CsWire.Err.PLUGIN, "no answer")
                Json.search(res.items, provider).toString()
            }
        }

        suspend fun mainPage(api: MainAPI, provider: Int, args: JSONObject): String {
            val index = intArg(args, "index") ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "index")
            val page = intArg(args, "page")?.takeIf { it >= 1 } ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "page")
            val (data, hint) = plugin {
                if (!api.hasMainPage) null else api.mainPage.getOrNull(index)?.let { it to api.getMainPageTimeoutMs }
            } ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "main page")
            return timed(hint, { CsWire.error(CsWire.Err.TIMEOUT, "provider timeout") }) {
                val res = api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
                    ?: return@timed CsWire.error(CsWire.Err.PLUGIN, "no answer")
                Json.mainPage(res, provider).toString()
            }
        }

        suspend fun load(api: MainAPI, args: JSONObject): String {
            val url = args.opt("url") as? String ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "url")
            if (Json.invalidData(url)) return CsWire.error(CsWire.Err.BAD_REQUEST, "url")
            val hint = plugin { api.loadTimeoutMs }
            return timed(hint, { CsWire.error(CsWire.Err.TIMEOUT, "provider timeout") }) {
                // As upstream's APIRepository.load: a relative url is completed against the provider's mainUrl.
                val res = api.load(api.fixUrl(url)) ?: return@timed CsWire.error(CsWire.Err.PLUGIN, "no answer")
                try {
                    // Leaner until it fits the stream cap; past level 2 the service answers too_large.
                    var out = Json.load(res).toString()
                    var lean = 0
                    while (utf8Length(out) > LOAD_LEAN_ABOVE_BYTES && lean < 2) out = Json.load(res, ++lean).toString()
                    out
                } catch (e: Json.Refused) {
                    CsWire.error(CsWire.Err.BAD_REQUEST, e.message ?: "refused")
                }
            }
        }

        suspend fun loadLinks(api: MainAPI, args: JSONObject, gate: Gate): String {
            val data = args.opt("data") as? String ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "data")
            // Closed first, so the count is exactly the link events Kino got: a callback racing the end is dropped, not counted.
            fun answer(returned: Boolean): String { gate.close(); return JSONObject().put("returned", returned).put("links", gate.links.get()).toString() }
            // Upstream never calls a provider with data that cannot be data.
            if (Json.invalidData(data)) return answer(false)
            val hint = plugin { api.loadLinksTimeoutMs }
            // On the provider's own timeout upstream keeps what was found and reports false; so does this.
            return timed(hint, { answer(false) }) {
                // Each plugin object is read once into a snapshot, filtered and mapped from that snapshot, so a plugin
                // getter cannot show the filter one value and the mapper another. A link the bridge cannot read or map is
                // dropped here; it never throws into the plugin's own loop.
                val returned = api.loadLinks(data, false,
                    { sub -> runCatching { val s = LinkFilter.snapshot(sub); if (LinkFilter.accept(s)) gate.subtitle(Json.subtitle(s).toString()) } },
                    { link -> runCatching { val s = LinkFilter.snapshot(link); if (LinkFilter.accept(s)) gate.link(Json.link(s).toString()) } })
                answer(returned)
            }
        }
    }

    /**
     * Sends link and subtitle events while the op is open, at most [MAX_LINK_EVENTS] / [MAX_SUBTITLE_EVENTS], each at most
     * [MAX_EVENT_CHARS], [MAX_EVENTS_TOTAL_CHARS] in all. Only an event that left the process counts. Plugins call back from any thread; [close] (before
     * `done` is returned) waits for an event being sent and drops every later one. The JSON is built by the caller from a
     * snapshot, never under the lock.
     */
    class Gate(private val emit: (String) -> Boolean) {
        private val lock = Any()
        private var open = true
        val links = AtomicInteger(0)
        private var subtitles = 0
        private var chars = 0

        fun link(json: String) {
            if (json.length > MAX_EVENT_CHARS) return
            synchronized(lock) { if (open && links.get() < MAX_LINK_EVENTS && send(json)) links.incrementAndGet() }
        }

        fun subtitle(json: String) {
            if (json.length > MAX_EVENT_CHARS) return
            synchronized(lock) { if (open && subtitles < MAX_SUBTITLE_EVENTS && send(json)) subtitles++ }
        }

        /** Under [lock]: within the total budget and sent. */
        private fun send(json: String): Boolean {
            if (chars + json.length > MAX_EVENTS_TOTAL_CHARS || !emit(json)) return false
            chars += json.length
            return true
        }

        fun close() = synchronized(lock) { open = false }
    }
}
