package com.kino.csbridge.ops

import com.kino.csbridge.contract.CsWire
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.fixUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provider ops: `search`, `mainPage`, `load` and `loadLinks`, each on `providers[args.provider]` of the call's
 * plugin, called through [MainAPI]'s own virtual methods (an old plugin that only overrides `search(query)` is reached
 * through the default `search(query, page)`).
 *
 * All plugin code (the lookup, which may load the plugin, the call, and the mapping, whose getters may be plugin code)
 * runs [detached]: in a coroutine that is not a child of the op's job but carries its context (CallContext, thread
 * local). The op awaits it, and that wait is cancellable: when Kino cancels or the deadline fires, the service answers
 * at once even if the plugin is stuck in blocking code (Jsoup, a sleep) that coroutine cancellation cannot interrupt.
 * What the plugin does afterwards changes nothing: its HTTP is refused (the op's job is over) and [Gate] drops its
 * late link events, so nothing reaches Kino after the one `done`.
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
    /** A `load` answer longer than this is rebuilt leaner (no episode posters and descriptions, then no names). */
    const val LOAD_LEAN_ABOVE_CHARS = 480 * 1024

    /** Ops this object answers. */
    val OPS = setOf(CsWire.Op.SEARCH, CsWire.Op.MAIN_PAGE, CsWire.Op.LOAD, CsWire.Op.LOAD_LINKS)

    /** Upstream's `APIRepository.getTimeout`. */
    fun timeoutOf(hint: Long?): Long = (hint ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /**
     * Runs one provider op. [providers] is the plugin's provider list (it may load the plugin, so it runs detached too).
     * Returns the `done` JSON; throws what the plugin threw, for the service to map.
     */
    suspend fun run(req: CsWire.OpRequest, emit: (String) -> Unit, providers: () -> List<MainAPI>): String {
        val gate = Gate(emit)
        try {
            val args = req.args
            // The plugin first: an op on a plugin the bridge does not hold is not_installed whatever its arguments.
            val apis = detached { providers().toList() }
            val index = (args.opt("provider") as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()
                ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "provider")
            val api = apis.getOrNull(index) ?: return CsWire.error(CsWire.Err.NOT_INSTALLED, "provider")
            return when (req.op) {
                CsWire.Op.SEARCH -> search(api, index, args)
                CsWire.Op.MAIN_PAGE -> mainPage(api, index, args)
                CsWire.Op.LOAD -> load(api, args)
                CsWire.Op.LOAD_LINKS -> loadLinks(api, args, gate)
                else -> CsWire.error(CsWire.Err.BAD_REQUEST, "op")
            }
        } finally {
            gate.close()
        }
    }

    private suspend fun search(api: MainAPI, provider: Int, args: JSONObject): String {
        val query = args.opt("query") as? String ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "query")
        // Upstream answers an empty query with no results, without asking the provider.
        if (query.isEmpty()) return Json.search(emptyList(), provider).toString()
        val hint = detached { api.searchTimeoutMs }
        return timed(hint, { CsWire.error(CsWire.Err.TIMEOUT, "provider timeout") }) {
            val res = api.search(query, 1) ?: return@timed CsWire.error(CsWire.Err.PLUGIN, "no answer")
            Json.search(res.items, provider).toString()
        }
    }

    private suspend fun mainPage(api: MainAPI, provider: Int, args: JSONObject): String {
        val index = (args.opt("index") as? Number)?.toInt() ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "index")
        val page = (args.opt("page") as? Number)?.toInt()?.takeIf { it >= 1 } ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "page")
        val (data, hint) = detached {
            if (!api.hasMainPage) null else api.mainPage.getOrNull(index)?.let { it to api.getMainPageTimeoutMs }
        } ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "main page")
        return timed(hint, { CsWire.error(CsWire.Err.TIMEOUT, "provider timeout") }) {
            val res = api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
                ?: return@timed CsWire.error(CsWire.Err.PLUGIN, "no answer")
            Json.mainPage(res, provider).toString()
        }
    }

    private suspend fun load(api: MainAPI, args: JSONObject): String {
        val url = args.opt("url") as? String ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "url")
        if (Json.invalidData(url)) return CsWire.error(CsWire.Err.BAD_REQUEST, "url")
        val hint = detached { api.loadTimeoutMs }
        return timed(hint, { CsWire.error(CsWire.Err.TIMEOUT, "provider timeout") }) {
            // As upstream's APIRepository.load: a relative url is completed against the provider's mainUrl.
            val res = api.load(api.fixUrl(url)) ?: return@timed CsWire.error(CsWire.Err.PLUGIN, "no answer")
            try {
                // Leaner until it fits; past level 2 the service's result cap answers too_large.
                var out = Json.load(res).toString()
                var lean = 0
                while (out.length > LOAD_LEAN_ABOVE_CHARS && lean < 2) out = Json.load(res, ++lean).toString()
                out
            } catch (e: Json.Refused) {
                CsWire.error(CsWire.Err.BAD_REQUEST, e.message ?: "refused")
            }
        }
    }

    private suspend fun loadLinks(api: MainAPI, args: JSONObject, gate: Gate): String {
        val data = args.opt("data") as? String ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "data")
        // Closed first, so the count is exactly the link events Kino got: a callback racing the end is dropped, not counted.
        fun answer(returned: Boolean): String { gate.close(); return JSONObject().put("returned", returned).put("links", gate.links.get()).toString() }
        // Upstream never calls a provider with data that cannot be data.
        if (Json.invalidData(data)) return answer(false)
        val hint = detached { api.loadLinksTimeoutMs }
        // On the provider's own timeout upstream keeps what was found and reports false; so does this.
        return timed(hint, { answer(false) }) {
            // A link the bridge cannot read or map is dropped here; it never throws into the plugin's own loop.
            val returned = api.loadLinks(data, false,
                { sub -> runCatching { if (LinkFilter.accept(sub)) gate.subtitle { Json.subtitle(sub).toString() } } },
                { link -> runCatching { if (LinkFilter.accept(link)) gate.link { Json.link(link).toString() } } })
            answer(returned)
        }
    }

    /** [block] detached, bounded by the provider's [hint] (upstream's clamp); [onTimeout] answers when it runs out. */
    private suspend fun timed(hint: Long?, onTimeout: () -> String, block: suspend () -> String): String =
        withTimeoutOrNull(timeoutOf(hint)) { detached(block) } ?: onTimeout()

    /**
     * Runs [block] off the op's job but with its context, and waits for it cancellably (see the class KDoc). When the
     * wait ends early the detached work is cancelled too; it stops at its next suspension point, if it ever reaches one.
     */
    private suspend fun <T> detached(block: suspend () -> T): T {
        val ctx = currentCoroutineContext().minusKey(Job) + Dispatchers.IO
        val work = CoroutineScope(ctx).async { block() }
        try {
            return work.await()
        } finally {
            work.cancel()
        }
    }

    /**
     * Sends link and subtitle events while the op is open, at most [MAX_LINK_EVENTS] / [MAX_SUBTITLE_EVENTS]. Plugins call
     * back from any thread; [close] (before `done` is returned) waits for an event being sent and drops every later one.
     * The JSON is built before taking the lock: its getters may be plugin code, which must never hold up [close].
     */
    class Gate(private val emit: (String) -> Unit) {
        private val lock = Any()
        private var open = true
        val links = AtomicInteger(0)
        private var subtitles = 0

        fun link(json: () -> String) {
            if (!isOpen() || links.get() >= MAX_LINK_EVENTS) return
            val s = json()
            synchronized(lock) { if (open && links.get() < MAX_LINK_EVENTS) { emit(s); links.incrementAndGet() } }
        }

        fun subtitle(json: () -> String) {
            if (!isOpen()) return
            val s = json()
            synchronized(lock) { if (open && subtitles < MAX_SUBTITLE_EVENTS) { emit(s); subtitles++ } }
        }

        private fun isOpen() = synchronized(lock) { open }

        fun close() = synchronized(lock) { open = false }
    }
}
