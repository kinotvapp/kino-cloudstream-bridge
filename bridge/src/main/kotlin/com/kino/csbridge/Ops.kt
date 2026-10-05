package com.kino.csbridge

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.ops.PluginWork
import com.kino.csbridge.ops.ProviderOps
import com.kino.csbridge.plugins.PluginRegistry
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Dispatch of the wire ops; the service only depends on [run] and [helloPlugins]. The provider ops live in
 * [ProviderOps], which finds providers only through the registry's per-plugin list (never by name: names collide).
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

    /**
     * Runs one op and returns its `done` JSON. [emit] sends `event` JSON and says whether it left the process. May throw;
     * the service maps throwables to errors. Plugin code never runs on the caller's thread: provider ops run on the
     * plugin pool and are answered `timeout` "busy" at once for a plugin with too many tasks stuck in blocking code
     * ([PluginWork]); install and remove run on the registry's own threads and are never refused for a busy plugin (they
     * skip its `beforeUnload()` instead). listPlugins runs no plugin code.
     */
    suspend fun run(context: Context, req: CsWire.OpRequest, payload: ParcelFileDescriptor?, emit: (String) -> Boolean): String = try {
        val reg = registry(context)
        when (req.op) {
            CsWire.Op.LIST -> JSONObject().put("plugins", CsWire.helloPluginsToJson(reg.stored())).toString()
            CsWire.Op.INSTALL -> install(reg, req, payload)
            CsWire.Op.REMOVE -> remove(reg, req.pluginId)
            in ProviderOps.OPS -> ProviderOps.run(req, emit) { reg.providers(req.pluginId) }
            else -> CsWire.error(CsWire.Err.BAD_REQUEST, "op")
        }
    } catch (e: PluginRegistry.RegistryException) {
        CsWire.error(e.code, e.message ?: e.code)
    }

    /** Per plugin: one install, remove or rollback at a time, and how many installs and removes have started. */
    private val mutationLocks = ConcurrentHashMap<String, Any>()
    private val mutationSeq = ConcurrentHashMap<String, AtomicLong>()
    private fun lockOf(id: String) = mutationLocks.computeIfAbsent(id) { Any() }
    private fun seqOf(id: String) = mutationSeq.computeIfAbsent(id) { AtomicLong() }

    private suspend fun remove(reg: PluginRegistry, id: String): String {
        seqOf(id).incrementAndGet()
        val stuck = PluginWork.busy(id)
        return PluginWork.run(id, on = PluginWork.registryDispatcher) {
            synchronized(lockOf(id)) { reg.remove(id, beforeUnload = !stuck) }
            JSONObject().put("ok", true).toString()
        }
    }

    /**
     * Installs on the registry's threads, from a dup of the payload that the work owns: the service closes its own fd
     * when the op ends, and a late install never reads a closed (or reused) descriptor. An install that lands after Kino
     * stopped waiting (deadline, cancel) is rolled back, unless a newer install or remove of the same plugin has started
     * since, so Kino is never told "failed" about a plugin that stays installed.
     */
    private suspend fun install(reg: PluginRegistry, req: CsWire.OpRequest, payload: ParcelFileDescriptor?): String {
        val pfd = payload ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "payload")
        val id = req.pluginId
        val mine = seqOf(id).incrementAndGet()
        val stuck = PluginWork.busy(id)
        val own = pfd.dup()
        val rollback: (Pair<String, Boolean>) -> Unit = { (_, stored) ->
            if (stored) synchronized(lockOf(id)) { if (seqOf(id).get() == mine) reg.remove(id) }
        }
        return PluginWork.run(id, on = PluginWork.registryDispatcher, onLateResult = rollback) {
            synchronized(lockOf(id)) { installLocked(reg, req, own, beforeUnload = !stuck) }
        }.first
    }

    /** The answer and whether a new version was stored. */
    private fun installLocked(reg: PluginRegistry, req: CsWire.OpRequest, pfd: ParcelFileDescriptor, beforeUnload: Boolean): Pair<String, Boolean> {
        val res = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { reg.install(req.pluginId, it, req.args.optString("sha256"), beforeUnload) }
        // Provider getters are plugin code: if one throws, the plugin does not stay half-answered and installed.
        val providers = try {
            JSONArray().apply { res.providers.forEach { put(providerJson(it)) } }
        } catch (t: Throwable) {
            reg.remove(req.pluginId)
            throw t
        }
        val json = JSONObject().put("name", res.name).put("version", res.version).put("providers", providers)
            .put("missingSymbols", JSONArray(res.missingSymbols.take(MAX_MISSING).map { it.take(300) })).toString()
        return json to res.missingSymbols.isEmpty()
    }

    /** PROTOCOL.md Provider. Torrent is not a type Kino can play from the bridge, so it is never advertised. */
    internal fun providerJson(p: MainAPI): JSONObject = JSONObject()
        .put("name", p.name).put("mainUrl", p.mainUrl).put("lang", p.lang)
        .put("tvTypes", JSONArray(p.supportedTypes.filter { it != TvType.Torrent }.map { it.name }))
        .put("hasMainPage", p.hasMainPage)
        .put("mainPages", JSONArray().apply { p.mainPage.forEach { put(JSONObject().put("name", it.name).put("data", it.data)) } })

    private const val MAX_MISSING = 100
}
