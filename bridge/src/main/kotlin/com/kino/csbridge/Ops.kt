package com.kino.csbridge

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.PluginRegistry
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatch of the wire ops. Task 6 replaces the provider ops; the service only depends on [run] and [helloPlugins].
 */
object Ops {
    /** The process-wide registry; tests set it to one whose loader can run on the JVM. */
    @Volatile internal var instance: PluginRegistry? = null

    fun registry(context: Context): PluginRegistry =
        instance ?: synchronized(this) { instance ?: PluginRegistry(context.applicationContext ?: context).also { instance = it } }

    /**
     * Plugins the bridge holds, for `hello`: id + sha256 of the stored .cs3 (re-hashed once per process). Never waits
     * on an install or a plugin's load(). Throws when the bridge's storage cannot be read; the service answers
     * `unavailable` then, never an empty list (Kino would re-send every plugin).
     */
    fun helloPlugins(context: Context): List<CsWire.HelloPlugin> = registry(context).stored()

    /** Runs one op and returns its `done` JSON. [emit] sends `event` JSON. May throw; the service maps throwables to errors. */
    suspend fun run(context: Context, req: CsWire.OpRequest, payload: ParcelFileDescriptor?, emit: (String) -> Unit): String = try {
        val reg = registry(context)
        when (req.op) {
            CsWire.Op.LIST -> JSONObject().put("plugins", CsWire.helloPluginsToJson(reg.stored())).toString()
            CsWire.Op.INSTALL -> install(reg, req, payload)
            CsWire.Op.REMOVE -> { reg.remove(req.pluginId); JSONObject().put("ok", true).toString() }
            else -> CsWire.error(CsWire.Err.NOT_INSTALLED, "plugin")
        }
    } catch (e: PluginRegistry.RegistryException) {
        CsWire.error(e.code, e.message ?: e.code)
    }

    private fun install(reg: PluginRegistry, req: CsWire.OpRequest, payload: ParcelFileDescriptor?): String {
        val pfd = payload ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "payload")
        val res = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { reg.install(req.pluginId, it, req.args.optString("sha256")) }
        // Provider getters are plugin code: if one throws, the plugin does not stay half-answered and installed.
        val providers = try {
            JSONArray().apply { res.providers.forEach { put(providerJson(it)) } }
        } catch (t: Throwable) {
            reg.remove(req.pluginId)
            throw t
        }
        return JSONObject().put("name", res.name).put("version", res.version).put("providers", providers)
            .put("missingSymbols", JSONArray(res.missingSymbols.take(MAX_MISSING).map { it.take(300) })).toString()
    }

    /** PROTOCOL.md Provider. Torrent is not a type Kino can play from the bridge, so it is never advertised. */
    internal fun providerJson(p: MainAPI): JSONObject = JSONObject()
        .put("name", p.name).put("mainUrl", p.mainUrl).put("lang", p.lang)
        .put("tvTypes", JSONArray(p.supportedTypes.filter { it != TvType.Torrent }.map { it.name }))
        .put("hasMainPage", p.hasMainPage)
        .put("mainPages", JSONArray().apply { p.mainPage.forEach { put(JSONObject().put("name", it.name).put("data", it.data)) } })

    private const val MAX_MISSING = 100
}
