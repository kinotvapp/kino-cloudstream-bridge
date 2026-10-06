// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge (see bridge-contract/PROTOCOL.md; license: bridge-contract/LICENSE).
package com.kino.csbridge.contract

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object CsWire {
    const val PROTOCOL_VERSION = 2
    const val BRIDGE_PACKAGE = "com.kino.csbridge"
    const val BRIDGE_SERVICE = "com.kino.csbridge.BridgeService"
    const val PERMISSION = "com.arkiv.player.permission.CLOUDSTREAM_BRIDGE"
    const val MAX_REQUEST_BODY_BYTES = 1 shl 20
    const val MAX_RESPONSE_BODY_BYTES = 5 shl 20
    const val MAX_REQUESTS_PER_OP = 250
    const val MAX_IN_FLIGHT_PER_PLUGIN = 6
    /** A result of at most this many chars ends its op through `IReply.done`; a longer one through `IReply.doneStream`. */
    const val MAX_INLINE_RESULT_CHARS = 100_000
    /** A streamed result's cap, in UTF-8 bytes. Over it the op ends with `done` and [Err.TOO_LARGE] instead. */
    const val MAX_STREAM_RESULT_BYTES = 8 * 1024 * 1024
    private const val MAX_ID_CHARS = 64
    private const val MAX_URL_CHARS = 8192
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    /** An HTTP token (RFC 9110): header names, and the cookie name `waitForCookie` takes. */
    private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")

    object Op {
        const val INSTALL = "installPlugin"; const val REMOVE = "removePlugin"; const val LIST = "listPlugins"
        const val MAIN_PAGE = "mainPage"; const val SEARCH = "search"; const val LOAD = "load"; const val LOAD_LINKS = "loadLinks"
        val ALL = setOf(INSTALL, REMOVE, LIST, MAIN_PAGE, SEARCH, LOAD, LOAD_LINKS)
    }

    object Err {
        const val PROTOCOL = "protocol"; const val BAD_REQUEST = "bad_request"; const val NOT_INSTALLED = "not_installed"
        const val INCOMPATIBLE = "incompatible"; const val TIMEOUT = "timeout"; const val CANCELLED = "cancelled"
        const val PLUGIN = "plugin_error"; const val NETWORK = "network"; const val REFUSED = "refused"
        const val TOO_LARGE = "too_large"; const val BUDGET = "budget"; const val UNAVAILABLE = "unavailable"
        /** A `doneStream` pipe that ended without the writer's OK (closeWithError, a dead writer) or with invalid UTF-8. */
        const val STREAM_BROKEN = "bridge_stream_broken"
        /** Both of the bridge's stream slots are writing: a large result is answered this in `done`, with no doneStream. */
        const val STREAMS_BUSY = "streams_busy"
        /** `IHost.webView` only: another hidden page is open in Kino. */
        const val BUSY = "busy"
        /** `IHost.webView` only: no live op the person started. */
        const val NOT_ALLOWED = "not_allowed"
        /** `IHost.webView` only: host not allowed, not https, a private address, or a navigation off https. */
        const val BLOCKED = "blocked"
        /** `IHost.webView` only: the device has no WebView. */
        const val BROWSER_UNAVAILABLE = "browser_unavailable"
    }

    /**
     * A plugin's own settings screen (PROTOCOL.md "Plugin settings"). Additive within v2: a bridge without it answers
     * `installPlugin` without [HAS_SETTINGS] (read as false) and has no such activity.
     */
    object Settings {
        /** Optional Boolean in the `installPlugin` result: the plugin set CloudStream's `openSettings` in its `load()`. */
        const val HAS_SETTINGS = "hasSettings"
        /** Explicit component (package [BRIDGE_PACKAGE]); requires [PERMISSION]. Start it for a result. */
        const val ACTIVITY = "com.kino.csbridge.settings.PluginSettingsActivity"
        /** String extra, required: the plugin's Kino id (the `pluginId` of its ops, `cloudstream-…`). */
        const val EXTRA_PLUGIN_ID = "com.kino.csbridge.extra.PLUGIN_ID"
        /** String extra on a `RESULT_CANCELED` result: one of the codes below or an [Err] code. */
        const val EXTRA_ERROR = "com.kino.csbridge.extra.ERROR"
        /** The plugin has no settings screen (`openSettings` is null after `load()`). */
        const val NO_SETTINGS = "no_settings"
        /** Started on a television: the bridge does not show plugin settings there. */
        const val UNSUPPORTED_DEVICE = "unsupported_device"
    }

    /**
     * Reads one `IReply.doneStream` result to its end and closes [result]: the result JSON, or an error JSON
     * ([Err.TOO_LARGE] over [MAX_STREAM_RESULT_BYTES], [Err.STREAM_BROKEN] when the pipe ended without the writer's OK
     * or the bytes are not UTF-8). Blocking: run it off the binder thread, and close [result] from elsewhere to stop it
     * at a deadline (the read then fails as [Err.STREAM_BROKEN]).
     */
    fun readStreamedResult(result: ParcelFileDescriptor): String = try {
        val bytes = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(result).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bytes.write(buf, 0, n)
                if (bytes.size() > MAX_STREAM_RESULT_BYTES) return error(Err.TOO_LARGE, "streamed result over ${MAX_STREAM_RESULT_BYTES shr 20} MB")
            }
            result.checkError() // EOF without the writer's OK status: closeWithError, or the writer died
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (e: IOException) {
        error(Err.STREAM_BROKEN, e.javaClass.simpleName)
    } catch (e: CharacterCodingException) {
        error(Err.STREAM_BROKEN, "not UTF-8")
    }

    /** `IHost.webView` limits (PROTOCOL.md "IHost.webView"). Kino enforces them; the bridge never sends past them. */
    object WebView {
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 25_000L
        /** `match` and each `alsoMatch` pattern: 1..this many chars. */
        const val MAX_MATCH_CHARS = 500
        const val MAX_ALSO_MATCH = 10
        /** Captured requests in one reply. */
        const val MAX_REQUESTS = 20
        /** With `captureAll`, the page ends once the primary match was seen and no new matching request came for this long. */
        const val SETTLE_MS = 1_000L
        const val MAX_FIRST_LOAD_HEADERS = 10
        const val MAX_FIRST_LOAD_HEADER_VALUE_CHARS = 2048
        const val MAX_WAIT_FOR_COOKIE_CHARS = 100
        /** Headers of one captured request. */
        const val MAX_REQUEST_HEADERS = 64
        const val MAX_REQUEST_HEADER_VALUE_CHARS = 8192
        const val MAX_COOKIES = 64
        const val MAX_COOKIE_NAME_CHARS = 256
        const val MAX_COOKIE_VALUE_CHARS = 4096
        /** Names + values of one reply's cookies together; past it, cookies other than Cloudflare's (`cf_*`, `__cf*`) go first. */
        const val MAX_COOKIES_TOTAL_CHARS = 16 * 1024
        const val MAX_USER_AGENT_CHARS = 512
        /** First-load headers Kino never sets from a request. */
        val FORBIDDEN_FIRST_LOAD_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "cookie")
    }

    /** One `IHost.webView` request (PROTOCOL.md). Optional fields at their default are left out of the JSON. */
    data class WebViewRequest(
        val callId: String, val pluginId: String, val url: String, val timeoutMs: Long,
        val headers: Map<String, String> = emptyMap(),
        val match: String? = null,
        val alsoMatch: List<String> = emptyList(),
        val captureAll: Boolean = false,
        val waitForCookie: String? = null,
        val returnCookiesOnTimeout: Boolean = false,
    )

    /** One request the page made, as the WebView saw it. */
    data class CapturedRequest(val url: String, val method: String, val headers: Map<String, String>)

    /** An `IHost.webView` success (or, with `returnCookiesOnTimeout`, timeout) reply. */
    data class WebViewReply(
        val requests: List<CapturedRequest>, val cookies: Map<String, String>, val userAgent: String, val finalUrl: String,
        val timedOut: Boolean,
    )

    fun webViewRequest(r: WebViewRequest): String = JSONObject().put("callId", r.callId).put("pluginId", r.pluginId)
        .put("url", r.url).put("timeoutMs", r.timeoutMs).put("headers", JSONObject(firstLoadHeaders(r.headers) as Map<*, *>))
        .apply {
            r.match?.let { put("match", it) }
            if (r.alsoMatch.isNotEmpty()) put("alsoMatch", JSONArray(r.alsoMatch))
            if (r.captureAll) put("captureAll", true)
            r.waitForCookie?.let { put("waitForCookie", it) }
            if (r.returnCookiesOnTimeout) put("returnCookiesOnTimeout", true)
        }.toString()

    /**
     * [json] as a [WebViewRequest], or null when Kino must answer [Err.BAD_REQUEST]: a field of the wrong type, a value
     * past a limit, `alsoMatch` without `captureAll`, `captureAll` without `match`, or neither `match` nor
     * `waitForCookie`. `timeoutMs` is clamped and first-load headers filtered ([firstLoadHeaders]), never refused.
     * Patterns are not compiled here: Kino compiles them and refuses an invalid one.
     */
    fun parseWebViewRequest(json: String): WebViewRequest? = runCatching {
        val o = JSONObject(json)
        val url = (o.opt("url") as? String)?.takeIf { it.length <= MAX_URL_CHARS && it.startsWith("https://", ignoreCase = true) } ?: return null
        val timeout = (o.opt("timeoutMs") as? Number)?.toLong()?.coerceIn(WebView.MIN_TIMEOUT_MS, WebView.MAX_TIMEOUT_MS) ?: return null
        val headers = when (val h = o.opt("headers")) {
            null -> emptyMap()
            is JSONObject -> firstLoadHeaders(h.keys().asSequence().mapNotNull { k -> (h.opt(k) as? String)?.let { k to it } }.toMap(LinkedHashMap()))
            else -> return null
        }
        fun pattern(v: Any?): String? = (v as? String)?.takeIf { it.length in 1..WebView.MAX_MATCH_CHARS }
        val match = if (o.has("match")) pattern(o.opt("match")) ?: return null else null
        val also = when (val a = o.opt("alsoMatch")) {
            null -> emptyList()
            is JSONArray -> {
                if (a.length() !in 1..WebView.MAX_ALSO_MATCH) return null
                (0 until a.length()).map { pattern(a.opt(it)) ?: return null }
            }
            else -> return null
        }
        fun flag(name: String): Boolean? = when (val v = o.opt(name)) { null -> false; is Boolean -> v; else -> null }
        val captureAll = flag("captureAll") ?: return null
        val onTimeout = flag("returnCookiesOnTimeout") ?: return null
        val cookie = if (o.has("waitForCookie")) {
            (o.opt("waitForCookie") as? String)?.takeIf { it.length <= WebView.MAX_WAIT_FOR_COOKIE_CHARS && HEADER_NAME.matches(it) } ?: return null
        } else null
        if (also.isNotEmpty() && !captureAll) return null
        if (captureAll && match == null) return null
        if (match == null && cookie == null) return null
        WebViewRequest(id(o.getString("callId")) ?: return null, id(o.getString("pluginId")) ?: return null, url, timeout,
            headers, match, also, captureAll, cookie, onTimeout)
    }.getOrNull()

    /** The first-load headers Kino keeps, in order: token names, none of [WebView.FORBIDDEN_FIRST_LOAD_HEADERS], no line breaks, at most [WebView.MAX_FIRST_LOAD_HEADERS]. */
    fun firstLoadHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (out.size >= WebView.MAX_FIRST_LOAD_HEADERS) break
            if (!HEADER_NAME.matches(k) || k.lowercase() in WebView.FORBIDDEN_FIRST_LOAD_HEADERS) continue
            if ('\n' in v || '\r' in v || v.length > WebView.MAX_FIRST_LOAD_HEADER_VALUE_CHARS) continue
            out[k] = v
        }
        return out
    }

    /**
     * The reply JSON for [r], within every limit: invalid entries dropped, at most [WebView.MAX_REQUESTS] requests, and,
     * since the reply travels in one `done`, trailing requests dropped until it is at most [MAX_INLINE_RESULT_CHARS].
     * A [WebViewReply.finalUrl] that is not https (or too long) is never sent: the answer is [Err.BLOCKED] instead.
     */
    fun webViewReply(r: WebViewReply): String {
        if (!r.finalUrl.startsWith("https://", ignoreCase = true) || r.finalUrl.length > MAX_URL_CHARS) {
            return error(Err.BLOCKED, "final address is not https")
        }
        val requests = r.requests.mapNotNull { capturedRequestJson(it) }.take(WebView.MAX_REQUESTS).toMutableList()
        fun build() = JSONObject().put("requests", JSONArray(requests)).put("cookies", JSONObject(cookiesWithinLimits(r.cookies) as Map<*, *>))
            .put("userAgent", r.userAgent.takeIf { validUserAgent(it) } ?: "").put("finalUrl", r.finalUrl)
            .put("timedOut", r.timedOut).toString()
        var out = build()
        while (out.length > MAX_INLINE_RESULT_CHARS && requests.isNotEmpty()) { requests.removeAt(requests.size - 1); out = build() }
        return out
    }

    /**
     * [json] as a [WebViewReply], or null when it is not the success shape (an error answer included). Entries past a
     * limit are dropped, never the whole reply: a request with a bad url or method, a header or cookie that is invalid.
     */
    fun parseWebViewReply(json: String): WebViewReply? = runCatching {
        val o = JSONObject(json)
        if (o.has("error")) return null
        val a = o.optJSONArray("requests") ?: return null
        val requests = (0 until minOf(a.length(), WebView.MAX_REQUESTS)).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val h = r.optJSONObject("headers")
            val headers = h?.keys()?.asSequence()?.mapNotNull { k -> (h.opt(k) as? String)?.let { k to it } }?.toMap(LinkedHashMap()).orEmpty()
            CapturedRequest(r.optString("url"), r.optString("method"), headers).takeIf { capturedRequestJson(it) != null }
                ?.let { it.copy(headers = requestHeaders(it.headers)) }
        }
        val c = o.optJSONObject("cookies") ?: return null
        val cookies = cookiesWithinLimits(c.keys().asSequence().mapNotNull { k -> (c.opt(k) as? String)?.let { k to it } }.toMap(LinkedHashMap()))
        val ua = (o.opt("userAgent") as? String)?.takeIf { validUserAgent(it) } ?: ""
        val finalUrl = (o.opt("finalUrl") as? String)
            ?.takeIf { it.length <= MAX_URL_CHARS && it.startsWith("https://", ignoreCase = true) } ?: return null
        val timedOut = o.opt("timedOut") as? Boolean ?: return null
        WebViewReply(requests, cookies, ua, finalUrl, timedOut)
    }.getOrNull()

    private fun capturedRequestJson(r: CapturedRequest): JSONObject? {
        val web = r.url.startsWith("https://", ignoreCase = true) || r.url.startsWith("http://", ignoreCase = true)
        if (!web || r.url.length > MAX_URL_CHARS || r.method !in METHODS) return null
        return JSONObject().put("url", r.url).put("method", r.method).put("headers", JSONObject(requestHeaders(r.headers) as Map<*, *>))
    }

    private fun requestHeaders(h: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in h) {
            if (out.size >= WebView.MAX_REQUEST_HEADERS) break
            if (HEADER_NAME.matches(k) && v.length <= WebView.MAX_REQUEST_HEADER_VALUE_CHARS && '\n' !in v && '\r' !in v) out[k] = v
        }
        return out
    }

    /**
     * Valid cookies, at most [WebView.MAX_COOKIES], names + values at most [WebView.MAX_COOKIES_TOTAL_CHARS] in all:
     * Cloudflare's own cookies (`cf_*`, `__cf*`) are kept first, the others fill what is left, in order.
     */
    private fun cookiesWithinLimits(c: Map<String, String>): Map<String, String> {
        val valid = c.filter { (k, v) ->
            k.isNotEmpty() && k.length <= WebView.MAX_COOKIE_NAME_CHARS && k.none { it.isWhitespace() || it in ",;=" } &&
                v.isNotEmpty() && v.length <= WebView.MAX_COOKIE_VALUE_CHARS && v.none { it == '\r' || it == '\n' || it == ';' }
        }
        fun cloudflare(name: String) = name.startsWith("cf_") || name.startsWith("__cf")
        val kept = HashSet<String>()
        var chars = 0
        for (pass in 0..1) for ((k, v) in valid) {
            if (cloudflare(k) != (pass == 0) || kept.size >= WebView.MAX_COOKIES) continue
            if (chars + k.length + v.length > WebView.MAX_COOKIES_TOTAL_CHARS) continue
            kept += k; chars += k.length + v.length
        }
        return valid.filterKeys { it in kept } // the reply's own order
    }

    private fun validUserAgent(ua: String) = ua.length <= WebView.MAX_USER_AGENT_CHARS && ua.none { it == '\n' || it == '\r' }

    /** One plugin the bridge holds, as `hello.plugins` lists it: its Kino id and the sha256 (64 lowercase hex) of its `.cs3`. */
    data class HelloPlugin(val id: String, val sha256: String)

    /** `hello.plugins`: `[{"id","sha256"}, …]`. */
    fun helloPluginsToJson(plugins: List<HelloPlugin>): JSONArray =
        JSONArray().apply { plugins.forEach { put(JSONObject().put("id", it.id).put("sha256", it.sha256)) } }

    /** `hello.plugins` read back; null when any entry is malformed (an id the protocol refuses, a sha256 that is not 64 lowercase hex). */
    fun helloPluginsOf(a: JSONArray): List<HelloPlugin>? = runCatching {
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            HelloPlugin(id(o.getString("id")) ?: return null, o.getString("sha256").takeIf { SHA256.matches(it) } ?: return null)
        }
    }.getOrNull()

    data class OpRequest(val op: String, val callId: String, val pluginId: String, val deadlineMs: Long, val args: JSONObject)

    data class HttpMeta(
        val callId: String, val pluginId: String, val method: String, val url: String,
        val headers: List<Pair<String, String>>, val followRedirects: Boolean, val timeoutMs: Int, val hasBody: Boolean,
    )

    fun opRequest(op: String, callId: String, pluginId: String, deadlineMs: Long, args: JSONObject = JSONObject()): String =
        JSONObject().put("v", PROTOCOL_VERSION).put("op", op).put("callId", callId).put("pluginId", pluginId)
            .put("deadlineMs", deadlineMs).put("args", args).toString()

    fun parseOpRequest(json: String): OpRequest? = runCatching {
        val o = JSONObject(json)
        if (o.optInt("v") != PROTOCOL_VERSION) return null
        val op = o.getString("op").takeIf { it in Op.ALL } ?: return null
        OpRequest(op, id(o.getString("callId")) ?: return null, id(o.getString("pluginId")) ?: return null,
            o.getLong("deadlineMs").takeIf { it in 1..600_000 } ?: return null, o.optJSONObject("args") ?: JSONObject())
    }.getOrNull()

    fun error(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message.take(300))).toString()

    /** (code, message) when [json] is an error answer, else null. */
    fun errorOf(json: String): Pair<String, String>? = runCatching {
        JSONObject(json).optJSONObject("error")?.let { it.getString("code") to it.optString("message") }
    }.getOrNull()

    fun httpMeta(
        callId: String, pluginId: String, method: String, url: String, headers: List<Pair<String, String>>,
        followRedirects: Boolean, timeoutMs: Int, hasBody: Boolean,
    ): String = JSONObject().put("callId", callId).put("pluginId", pluginId).put("method", method).put("url", url)
        .put("headers", headersToJson(headers)).put("followRedirects", followRedirects).put("timeoutMs", timeoutMs)
        .put("hasBody", hasBody).toString()

    fun parseHttpMeta(json: String): HttpMeta? = runCatching {
        val o = JSONObject(json)
        val method = o.getString("method").uppercase().takeIf { it in METHODS } ?: return null
        val url = o.getString("url").takeIf { it.length <= MAX_URL_CHARS } ?: return null
        HttpMeta(id(o.getString("callId")) ?: return null, id(o.getString("pluginId")) ?: return null, method, url,
            headersOf(o.optJSONArray("headers") ?: JSONArray()), o.optBoolean("followRedirects", true),
            o.optInt("timeoutMs", 0), o.optBoolean("hasBody", false))
    }.getOrNull()

    /** `[[name, value], …]`: keeps order and repeated names (Set-Cookie). */
    fun headersToJson(headers: List<Pair<String, String>>): JSONArray =
        JSONArray().apply { headers.forEach { (n, v) -> put(JSONArray().put(n).put(v)) } }

    fun headersOf(a: JSONArray): List<Pair<String, String>> =
        (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.takeIf { it.length() == 2 }?.let { it.getString(0) to it.getString(1) } }

    private fun id(s: String): String? = s.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
}
