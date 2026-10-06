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
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Per plugin: one install, remove or rollback at a time, and a generation that every change of what is stored bumps
     * (an install that stored a version, one that stored and then removed it, a remove of a stored plugin even if it
     * failed half-way, a rollback), always inside that plugin's lock. The lock is a coroutine Mutex: a second install or
     * remove of a plugin whose install hangs waits without holding a registry thread, and stops waiting when its op is
     * abandoned.
     */
    private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    private val mutationGen = ConcurrentHashMap<String, AtomicLong>()
    private fun lockOf(id: String) = mutationLocks.computeIfAbsent(id) { Mutex() }
    private fun genOf(id: String) = mutationGen.computeIfAbsent(id) { AtomicLong() }

    /**
     * Runs a registry remove of [id] and bumps its generation when what is stored changed: always when it [existed],
     * even if the remove failed half-way (file gone, index write failed), except for a RegistryException, which the
     * registry throws only before touching anything (bad id, its op ended while waiting for the plugin's lock).
     */
    private inline fun removeCounted(reg: PluginRegistry, id: String, existed: Boolean, remove: () -> Unit) {
        var changed = existed
        try {
            remove()
        } catch (e: PluginRegistry.RegistryException) {
            changed = false
            throw e
        } finally {
            if (changed) genOf(id).incrementAndGet()
        }
    }

    /** Tests only: the plugin's current generation. */
    internal fun generationForTest(id: String): Long = genOf(id).get()

    /** Tests swap it to observe the install's own descriptor. */
    @Volatile internal var dupPayload: (ParcelFileDescriptor) -> ParcelFileDescriptor = { it.dup() }

    private suspend fun remove(reg: PluginRegistry, id: String): String {
        val stuck = PluginWork.busy(id)
        return PluginWork.run(id, on = PluginWork.registryDispatcher) {
            lockOf(id).withLock {
                val existed = id in reg.ids()
                removeCounted(reg, id, existed) { reg.remove(id, beforeUnload = !stuck) }
            }
            JSONObject().put("ok", true).toString()
        }
    }

    /**
     * Installs on the registry's threads, from a dup of the payload that the work owns and closes when it completes
     * (run, failed, or cancelled before it started): the service closes its own fd when the op ends, and a late install
     * never reads a closed (or reused) descriptor. An install that lands after Kino stopped waiting (deadline, cancel) is
     * rolled back, unless the plugin really changed since (a newer stored install or a remove); a retry that failed
     * validation changes nothing and does not stop the rollback. So Kino is never told "failed" about a plugin that
     * stays installed.
     */
    private suspend fun install(reg: PluginRegistry, req: CsWire.OpRequest, payload: ParcelFileDescriptor?): String {
        val pfd = payload ?: return CsWire.error(CsWire.Err.BAD_REQUEST, "payload")
        val id = req.pluginId
        val stuck = PluginWork.busy(id)
        val own = dupPayload(pfd)
        // The generation this install created, or -1 when it stored nothing.
        // A rollback has no op to end its waits: it is bounded by ROLLBACK_WAIT_MS in total (a provider op whose load()
        // of this plugin hangs would otherwise hold a registry thread and this plugin's Mutex until that load returns).
        val rollback: suspend (Pair<String, Long>) -> Unit = rollback@{ (_, gen) ->
            if (gen < 0) return@rollback
            val until = System.nanoTime() + ROLLBACK_WAIT_MS * 1_000_000
            val done = try {
                withTimeoutOrNull(ROLLBACK_WAIT_MS) {
                    lockOf(id).withLock {
                        val left = ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                        if (genOf(id).get() == gen) removeCounted(reg, id, existed = true) { reg.remove(id, maxWaitMs = left) }
                    }
                }
            } catch (_: PluginRegistry.RegistryException) { null }
            if (done == null) runCatching { Log.w("KinoBridge", "late install of $id not rolled back: plugin busy for ${ROLLBACK_WAIT_MS / 1_000} s") }
        }
        return PluginWork.run(id, on = PluginWork.registryDispatcher, onLateResult = rollback, onDispose = { runCatching { own.close() } }) {
            lockOf(id).withLock {
                val (json, stored) = installLocked(reg, req, own, beforeUnload = !stuck) { genOf(id).incrementAndGet() }
                json to (if (stored) genOf(id).incrementAndGet() else -1L)
            }
        }.first
    }

    /**
     * The answer and whether a new version was stored. [changed] runs when the version it stored is taken out again (a
     * provider getter threw): what is stored changed, so a pending rollback of an earlier install must not apply.
     */
    private fun installLocked(
        reg: PluginRegistry, req: CsWire.OpRequest, pfd: ParcelFileDescriptor, beforeUnload: Boolean, changed: () -> Unit,
    ): Pair<String, Boolean> {
        val res = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { reg.install(req.pluginId, it, req.args.optString("sha256"), beforeUnload) }
        // Provider getters are plugin code: if one throws, the plugin does not stay half-answered and installed.
        val providers = try {
            JSONArray().apply { res.providers.forEach { put(providerJson(it)) } }
        } catch (t: Throwable) {
            runCatching { reg.remove(req.pluginId) } // the getter's throwable is the answer, not a failed cleanup
            changed() // the version was stored, whether or not it could be taken out again
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
    /** The longest a late install's rollback waits for the plugin (no op bounds it). */
    internal const val ROLLBACK_WAIT_MS = 120_000L
}
