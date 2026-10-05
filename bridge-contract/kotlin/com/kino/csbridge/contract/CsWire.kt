// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge (see ../../../../PROTOCOL.md).
package com.kino.csbridge.contract

import org.json.JSONArray
import org.json.JSONObject

object CsWire {
    const val PROTOCOL_VERSION = 1
    const val BRIDGE_PACKAGE = "com.kino.csbridge"
    const val BRIDGE_SERVICE = "com.kino.csbridge.BridgeService"
    const val PERMISSION = "com.arkiv.player.permission.CLOUDSTREAM_BRIDGE"
    const val MAX_REQUEST_BODY_BYTES = 1 shl 20
    const val MAX_RESPONSE_BODY_BYTES = 5 shl 20
    const val MAX_REQUESTS_PER_OP = 250
    const val MAX_IN_FLIGHT_PER_PLUGIN = 6
    private const val MAX_ID_CHARS = 64
    private const val MAX_URL_CHARS = 8192
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

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
    }

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
