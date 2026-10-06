package com.kino.csbridge.plugins

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.kino.csbridge.CallContext
import com.kino.csbridge.contract.CsWire
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.PLUGIN_VERSION_NOT_SET
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile

/**
 * The plugins this bridge holds: `filesDir/plugins/<id>.cs3` plus `index.json` (`[{pluginId, file, sha256, crashes}]`).
 *
 * Loading mirrors upstream `PluginManager.loadPlugin` (read-only file, `PathClassLoader`, manifest, plugin class,
 * `filename`, resources, `load(context)` / `load()`), with three differences: a member-level ABI check
 * ([SymbolChecker]) runs before any plugin code; the providers, extractors and click actions a plugin registers are
 * recorded by identity under its Kino id (a snapshot diff around `load()`), so [remove] takes out exactly those and
 * nothing that merely shares a name; and a crash-loop guard: `loading-<id>` exists only while that plugin's loading
 * code runs, so finding one at start-up means the process died inside it. Each such death adds one to the entry's
 * `crashes`; a successful load or a re-install resets it, and at [CRASHES_TO_DISABLE] consecutive deaths the plugin
 * is `incompatible` until Kino re-installs it (one OOM kill or unbind mid-load does not disable it).
 *
 * A stored plugin is loaded on first use ([providers]), from inside the op that needs it, so the HTTP its `load()`
 * makes is part of a call Kino serves.
 *
 * **Locks.** Each plugin id has its own lock: its install, remove and load run one at a time, and a plugin whose
 * `load()` hangs holds only that one. Another plugin installs, loads and is removed meanwhile. A wait for a plugin's
 * lock gives up (`timeout`) as soon as the op it serves is over ([CallContext.isOver]), so a waiter never outlives its
 * op. The index (and the snapshot `hello` reads) has a short lock of its own that no plugin code ever runs under.
 * Neither is needed to answer `hello` / `listPlugins` ([stored] reads an immutable snapshot published on every index
 * write), and the payload is streamed and hashed before taking any.
 *
 * **Who registered what.** Loads of different plugins can overlap, so a registration made inside one load's window may
 * be another plugin's. A registration whose class was defined by a plugin's own class loader belongs to that plugin
 * (on a device every .cs3 has its own PathClassLoader, and providers are always plugin classes). One whose class is the
 * bridge's (a library extractor instance, say) belongs to the load whose window it fell in, unless another loaded plugin
 * already holds it; if two loads that overlapped both made such registrations, both claim them, and removing either
 * plugin removes them (a library class registered twice; never another plugin's own class).
 */
class PluginRegistry(
    context: Context,
    private val parentLoader: ClassLoader = PluginRegistry::class.java.classLoader!!,
    private val loaderFactory: (File, ClassLoader) -> ClassLoader = { f, parent -> PathClassLoader(f.absolutePath, parent) },
    private val maxBytes: Long = MAX_CS3_BYTES,
) {
    class RegistryException(val code: String, message: String) : Exception(message)

    /** [hasSettings]: the plugin set CloudStream's `openSettings` in its `load()` (PROTOCOL.md "Plugin settings"). */
    data class InstallResult(
        val name: String, val version: Int, val providers: List<MainAPI>, val missingSymbols: List<String>, val hasSettings: Boolean = false,
    )

    private data class Entry(val pluginId: String, val file: String, val sha256: String, val crashes: Int = 0) {
        val disabled get() = crashes >= CRASHES_TO_DISABLE
    }

    private class Manifest(val name: String?, val pluginClassName: String, val version: Int, val requiresResources: Boolean)

    /** What one plugin added to CloudStream's global registries; references to its class loader end with this. */
    private class Loaded(
        val plugin: BasePlugin, val path: String, val providers: List<MainAPI>,
        val extractors: List<ExtractorApi>, val actions: List<VideoClickAction>,
        /** The plugin's own class loader, or null when it is [parentLoader] itself (JVM tests). */
        val loader: ClassLoader?,
    )

    private val app: Context = context.applicationContext ?: context
    private val dir = File(app.filesDir, "plugins")
    private val indexFile = File(dir, "index.json")
    /** Guards [index], index.json and [published]; held only for in-memory changes and the index write, never plugin code. */
    private val indexLock = Any()
    private val index = LinkedHashMap<String, Entry>()
    private val loaded = ConcurrentHashMap<String, Loaded>()
    /** One per plugin id: its install, remove and load. Ids are Kino's installed plugins, so the map stays small. */
    private val pluginLocks = ConcurrentHashMap<String, ReentrantLock>()
    /** The class loaders of plugins loading or loaded (distinct from [parentLoader]): who defined a registration's class. */
    private val pluginLoaders: MutableSet<ClassLoader> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    @Volatile private var started = false

    /** What [stored] answers: replaced as a whole after every successful index write, read without the lock. */
    @Volatile private var published: List<CsWire.HelloPlugin> = emptyList()

    /**
     * Once per process (the first `hello`, or whatever op comes first): reads the index, counts every crash marker into
     * the entry's persisted `crashes` (written before the markers are deleted), and drops entries whose file is gone or
     * no longer hashes to its sha256, plus stray temporary and orphan files. No plugin code runs here.
     */
    fun ensureLoaded() {
        if (started) return
        synchronized(indexLock) {
            if (started) return
            dir.mkdirs()
            index.clear()
            readIndex().forEach { index[it.pluginId] = it }
            val markers = dir.listFiles().orEmpty().filter { it.name.startsWith(MARKER) }
            for (m in markers) {
                val id = m.name.removePrefix(MARKER)
                index[id]?.let { index[id] = it.copy(crashes = it.crashes + 1) }
            }
            dir.listFiles().orEmpty().filter { it.name.startsWith(TMP) }.forEach { deleteFile(it) }
            for (e in index.values.toList()) {
                val f = File(dir, e.file)
                if (!f.isFile || sha256Of(f) != e.sha256) { index.remove(e.pluginId); deleteFile(f) }
            }
            writeIndex() // the crash counts are on disk before the markers that justify them go away
            markers.forEach { it.delete() }
            val keep = index.values.map { it.file }.toSet() + indexFile.name
            dir.listFiles().orEmpty().filter { it.isFile && it.name !in keep }.forEach { deleteFile(it) }
            started = true
        }
    }

    /** Ids stored, in install order. Never waits on an install or a plugin's load(). */
    fun ids(): List<String> = stored().map { it.id }

    /**
     * Every stored plugin with the sha256 of its file, disabled ones included (Kino must not re-send them in a loop).
     * Never waits on an install or a plugin's load(): `hello` runs on a binder thread.
     */
    fun stored(): List<CsWire.HelloPlugin> { ensureLoaded(); return published }

    /**
     * Verifies, ABI-checks, stores and loads one `.cs3`. A plugin with missing symbols is answered with them and is not
     * stored; a new version whose load() fails is rolled back to the previous file. In both cases the installed version
     * stays. Plugin throwables propagate after cleanup, so the caller maps them (`incompatible` for linkage errors,
     * `plugin_error` otherwise).
     */
    fun install(pluginId: String, cs3: InputStream, sha256: String, beforeUnload: Boolean = true): InstallResult {
        ensureLoaded()
        val name = SafeNames.fileName(pluginId) ?: throw RegistryException(CsWire.Err.BAD_REQUEST, "plugin id")
        if (!SHA256.matches(sha256)) throw RegistryException(CsWire.Err.BAD_REQUEST, "sha256")
        dir.mkdirs()
        // Unique per call and outside the lock: a slow payload never blocks another op, two installs never share it.
        val tmp = File(dir, "$TMP$name-${UUID.randomUUID()}")
        try {
            if (copyHashing(cs3, tmp) != sha256) throw RegistryException(CsWire.Err.BAD_REQUEST, "sha256 mismatch")
            val manifest = readManifest(tmp)
            val missing = try { SymbolChecker(parentLoader).missing(tmp) } catch (e: IllegalArgumentException) {
                throw RegistryException(CsWire.Err.BAD_REQUEST, "not a .cs3")
            }
            val displayName = manifest.name ?: pluginId
            if (missing.isNotEmpty()) return InstallResult(displayName, manifest.version, emptyList(), missing)
            return withPluginLock(pluginId) {
                val l = replaceLocked(pluginId, name, sha256, tmp, manifest, beforeUnload)
                InstallResult(displayName, manifest.version, l.providers, emptyList(), hasSettings(l.plugin))
            }
        } finally {
            deleteFile(tmp)
        }
    }

    /** Swaps [tmp] in for [pluginId]; the previous version's file and entry come back if the new one does not load. */
    private fun replaceLocked(pluginId: String, name: String, sha256: String, tmp: File, manifest: Manifest, beforeUnload: Boolean): Loaded {
        unloadLocked(pluginId, beforeUnload)
        val target = File(dir, name)
        val previous = synchronized(indexLock) { index[pluginId] }
        val backup = File(dir, "$TMP$name-previous-${UUID.randomUUID()}").takeIf { previous != null && target.isFile }
        var backedUp = false
        try {
            if (backup != null) { if (!target.renameTo(backup)) throw IOException("backup failed"); backedUp = true }
            deleteFile(target)
            if (!tmp.renameTo(target)) throw IOException("rename failed")
            // Android 14 refuses to load a writable dex file.
            if (!target.setReadOnly()) throw IOException("setReadOnly failed")
            // Indexed before load(): if load() kills the process, the marker then names a known entry.
            synchronized(indexLock) { index[pluginId] = Entry(pluginId, name, sha256); writeIndex() }
            val l = loadLocked(pluginId, target, manifest)
            backup?.let { deleteFile(it) }
            return l
        } catch (t: Throwable) {
            // Best effort, step by step: nothing here may replace the original throwable.
            if (backup == null || backedUp) runCatching { deleteFile(target) } // a failed backup left the old file in place
            if (backup != null && backedUp) runCatching { if (!backup.renameTo(target)) deleteFile(backup) }
            synchronized(indexLock) {
                runCatching { if (previous != null && target.isFile) index[pluginId] = previous else index.remove(pluginId) }
                runCatching { writeIndex() }
            }
            throw t
        }
    }

    /**
     * `beforeUnload()` (unless [beforeUnload] is false: the caller knows the plugin is stuck), then exactly this id's
     * registrations out of CloudStream's lists, then the file. Unknown ids: no-op.
     */
    fun remove(pluginId: String, beforeUnload: Boolean = true, maxWaitMs: Long = Long.MAX_VALUE) {
        val name = SafeNames.fileName(pluginId) ?: throw RegistryException(CsWire.Err.BAD_REQUEST, "plugin id")
        ensureLoaded()
        withPluginLock(pluginId, maxWaitMs) {
            unloadLocked(pluginId, beforeUnload)
            synchronized(indexLock) {
                val e = index.remove(pluginId)
                deleteFile(File(dir, e?.file ?: name))
                File(dir, "$MARKER$pluginId").delete()
                if (e != null) writeIndex()
            }
        }
    }

    /** Tests only: forgets [pluginId]'s loaded instance (without `beforeUnload()`), as a new process would start. */
    internal fun unloadForTest(pluginId: String) = withPluginLock(pluginId) { unloadLocked(pluginId, beforeUnload = false) }

    /**
     * Runs [block] holding [pluginId]'s lock. The wait is in short slices and ends with `timeout` once the op this thread
     * serves is over (deadline, cancel), or after [maxWaitMs]: a waiter behind a hung load never outlives its op, and
     * one without an op (a late rollback) gives up after its own bound. Start-up and tests pass no bound.
     */
    private fun <T> withPluginLock(pluginId: String, maxWaitMs: Long = Long.MAX_VALUE, block: () -> T): T {
        val l = pluginLocks.computeIfAbsent(pluginId) { ReentrantLock() }
        val ctx = CallContext.current()
        val start = System.nanoTime()
        while (!l.tryLock(LOCK_SLICE_MS, TimeUnit.MILLISECONDS)) {
            if (ctx?.isOver() == true) throw RegistryException(CsWire.Err.TIMEOUT, "plugin busy")
            if (maxWaitMs != Long.MAX_VALUE && (System.nanoTime() - start) / 1_000_000 >= maxWaitMs) {
                throw RegistryException(CsWire.Err.TIMEOUT, "plugin busy")
            }
        }
        try {
            return block()
        } finally {
            l.unlock()
        }
    }

    /**
     * The providers [pluginId] registered, loading it first if this process has not yet (inside the caller's op).
     * @throws RegistryException `not_installed` when it is not stored, `incompatible` when it killed the bridge in
     * [CRASHES_TO_DISABLE] consecutive loads or no longer links against this bridge.
     */
    fun providers(pluginId: String, maxWaitMs: Long = Long.MAX_VALUE): List<MainAPI> {
        ensureLoaded()
        loaded[pluginId]?.let { return it.providers }
        return withPluginLock(pluginId, maxWaitMs) {
            loaded[pluginId]?.let { return@withPluginLock it.providers }
            val e = synchronized(indexLock) { index[pluginId] } ?: throw RegistryException(CsWire.Err.NOT_INSTALLED, "plugin")
            if (e.disabled) throw RegistryException(CsWire.Err.INCOMPATIBLE, "crashed the bridge while loading; re-install it")
            val f = File(dir, e.file)
            val manifest = try { readManifest(f) } catch (x: RegistryException) { throw RegistryException(CsWire.Err.INCOMPATIBLE, "manifest") }
            // The bridge may have been updated since the install: check again before running its code.
            val missing = try { SymbolChecker(parentLoader).missing(f) } catch (x: IllegalArgumentException) { listOf("not a .cs3") }
            if (missing.isNotEmpty()) throw RegistryException(CsWire.Err.INCOMPATIBLE, "missing ${missing.size}: ${missing.first()}")
            loadLocked(pluginId, f, manifest).providers
        }
    }

    /**
     * The loaded plugin instance of [pluginId], loading it first like [providers] (same errors). For the settings screen,
     * which runs outside any op: a `load()` that makes HTTP gets it refused, as for any request outside a Kino call.
     * With no op to end it, the wait for the plugin's lock gives up (`timeout`) after [maxWaitMs].
     */
    fun plugin(pluginId: String, maxWaitMs: Long = Long.MAX_VALUE): BasePlugin {
        providers(pluginId, maxWaitMs)
        return loaded[pluginId]?.plugin ?: throw RegistryException(CsWire.Err.NOT_INSTALLED, "plugin")
    }

    /**
     * [beforeUnload] runs the plugin's `beforeUnload()` on a fresh daemon thread, waited on at most [BEFORE_UNLOAD_MS]:
     * plugin code that hangs there never holds a remove or an update (or this plugin's lock) longer than that.
     */
    private fun unloadLocked(pluginId: String, beforeUnload: Boolean = true) {
        loaded.remove(pluginId)?.let { l ->
            unloadListeners.forEach { runCatching { it(pluginId) } }
            if (beforeUnload) {
                val t = Thread({ try { l.plugin.beforeUnload() } catch (_: Throwable) {} }, "beforeUnload")
                t.isDaemon = true
                t.start()
                t.join(BEFORE_UNLOAD_MS)
            }
            unregister(l.plugin, l.path, l.providers, l.extractors, l.actions)
            l.loader?.let { pluginLoaders.remove(it) }
            PluginFrames.unregister(pluginId)
        }
    }

    /** Told the id of every loaded plugin as it is unloaded (update, remove), before its `beforeUnload()`. Any thread. */
    private val unloadListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    fun addUnloadListener(l: (String) -> Unit) { unloadListeners += l }
    fun removeUnloadListener(l: (String) -> Unit) { unloadListeners -= l }

    /** Upstream's load sequence, guarded by the crash marker and bracketed by the registration snapshot. */
    private fun loadLocked(pluginId: String, file: File, manifest: Manifest): Loaded {
        val marker = File(dir, "$MARKER$pluginId")
        marker.writeText("") // before any plugin code: static initialisers run from here on
        // Before any plugin code too: threads its load() starts are attributed to it (storage) from their first frame.
        PluginFrames.register(pluginId, onlyPluginClasses(file), manifest.pluginClassName)
        val path = file.absolutePath
        val beforeProviders = snapshot(APIHolder.allProviders)
        val beforeExtractors = snapshot(extractorApis)
        val beforeActions = snapshot(VideoClickActionHolder.allVideoClickActions)
        var plugin: BasePlugin? = null
        var own: ClassLoader? = null
        var done: Loaded? = null
        try {
            val loader = loaderFactory(file, parentLoader)
            if (loader !== parentLoader) { own = loader; pluginLoaders += loader }
            val created: Any = try {
                loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance()
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
            val instance = created as BasePlugin
            plugin = instance
            instance.filename = path
            if (manifest.requiresResources) {
                // As upstream (stackoverflow 7483568): a hidden constructor and addAssetPath, both reflective.
                (instance as? Plugin)?.resources = try {
                    val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, path)
                    @Suppress("DEPRECATION")
                    Resources(assets, app.resources.displayMetrics, app.resources.configuration)
                } catch (t: Throwable) {
                    throw RegistryException(CsWire.Err.INCOMPATIBLE, "resources")
                }
            }
            synchronized(PluginManager.plugins) { PluginManager.plugins[path] = instance }
            synchronized(PluginManager.urlPlugins) { PluginManager.urlPlugins[path] = instance }
            if (instance is Plugin) instance.load(app) else instance.load()
            val l = Loaded(instance, path, mine(APIHolder.allProviders, beforeProviders, own, pluginId) { it.providers },
                mine(extractorApis, beforeExtractors, own, pluginId) { it.extractors },
                mine(VideoClickActionHolder.allVideoClickActions, beforeActions, own, pluginId) { it.actions }, own)
            loaded[pluginId] = l
            done = l
            syncApis()
            // Consecutive deaths only: a load that completed resets the count (best effort; the load itself succeeded).
            synchronized(indexLock) {
                index[pluginId]?.takeIf { it.crashes != 0 }?.let { index[pluginId] = it.copy(crashes = 0); runCatching { writeIndex() } }
            }
            return l
        } finally {
            if (done == null) {
                unregister(plugin, path, mine(APIHolder.allProviders, beforeProviders, own, pluginId) { it.providers },
                    mine(extractorApis, beforeExtractors, own, pluginId) { it.extractors },
                    mine(VideoClickActionHolder.allVideoClickActions, beforeActions, own, pluginId) { it.actions })
                own?.let { pluginLoaders.remove(it) }
                if (loaded[pluginId] == null) PluginFrames.unregister(pluginId)
            }
            marker.delete()
        }
    }

    /**
     * The classes [file]'s dex defines that the bridge cannot load itself: under parent-first loading a bundled copy of a
     * class the bridge has (kotlin, kotlinx.coroutines, OkHttp) never runs, so its frames are never this plugin's.
     */
    private fun onlyPluginClasses(file: File): Set<String> =
        SymbolChecker(parentLoader).definedClasses(file).filterTo(HashSet()) { name ->
            try { Class.forName(name, false, parentLoader); false } catch (_: Throwable) { true }
        }

    /**
     * What [list] gained since [before] that is this load's: defined by its own loader [own], or by a loader that is no
     * plugin's (the bridge's) and not already held by another loaded plugin ([held] reads that plugin's list).
     */
    private fun <T : Any> mine(list: AtomicList<T>, before: Set<T>, own: ClassLoader?, pluginId: String, held: (Loaded) -> List<T>): List<T> {
        val others = identitySet(loaded.filterKeys { it != pluginId }.values.flatMap(held))
        return list.withLock { list.filter { it !in before }.toList() }.filter { item ->
            val cl = item.javaClass.classLoader
            when {
                own != null && cl === own -> true
                cl != null && cl in pluginLoaders -> false // another plugin's own class
                else -> item !in others
            }
        }
    }

    /** By identity against what this plugin registered: upstream's removePluginMapping compares with equals(). */
    private fun unregister(plugin: BasePlugin?, path: String, providers: List<MainAPI>, extractors: List<ExtractorApi>, actions: List<VideoClickAction>) {
        val p = identitySet(providers); val x = identitySet(extractors); val a = identitySet(actions)
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { it in p }
            syncApisLocked()
        }
        extractorApis.withLock { extractorApis.removeAll { it in x } }
        VideoClickActionHolder.allVideoClickActions.withLock { VideoClickActionHolder.allVideoClickActions.removeAll { it in a } }
        synchronized(PluginManager.plugins) { if (PluginManager.plugins[path] === plugin) PluginManager.plugins.remove(path) }
        synchronized(PluginManager.urlPlugins) { PluginManager.urlPlugins.values.removeAll { it === plugin } }
    }

    /**
     * Rebuilds CloudStream's name index (`APIHolder.apis`, `apiMap`) from `allProviders`, under `allProviders`' lock
     * (the one getApiFromNameNull holds while it pairs apiMap with apis, so a lookup never sees a mix).
     *
     * Why: `apis` is a var whose lock is the list object its own critical section replaces (upstream
     * addPluginMapping / removePluginMapping), so two concurrent updates can read the same base and one is lost. Loads
     * and removes of different plugins now overlap, and a lost remove would keep a removed plugin's provider (and its
     * class loader) in `apis`. Every load and unregister ends with this rebuild, so a lost update never outlives the
     * last op that overlapped it. The bridge registers no provider of its own outside `allProviders`.
     */
    private fun syncApis() = APIHolder.allProviders.withLock { syncApisLocked() }

    private fun syncApisLocked() {
        APIHolder.apis = AtomicList(APIHolder.allProviders.toList())
        APIHolder.apiMap = null // rebuilt from `apis` on the next name lookup
    }

    private fun <T> snapshot(list: AtomicList<T>): Set<T> = identitySet(list.withLock { list.toList() })
    private fun <T> identitySet(items: Collection<T>): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(items) }

    private fun copyHashing(input: InputStream, out: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw RegistryException(CsWire.Err.TOO_LARGE, "plugin larger than ${maxBytes shr 20} MB")
                md.update(buf, 0, n)
                o.write(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun readManifest(f: File): Manifest = try {
        ZipFile(f).use { z ->
            // Read from the zip, not loader.getResourceAsStream: a parent-first loader could answer with another manifest.json.
            val entry = z.getEntry("manifest.json") ?: throw RegistryException(CsWire.Err.BAD_REQUEST, "no manifest.json")
            if (entry.size > MAX_MANIFEST_BYTES) throw RegistryException(CsWire.Err.BAD_REQUEST, "manifest.json too large")
            val text = z.getInputStream(entry).use { it.readNBytesCompat(MAX_MANIFEST_BYTES) }.toString(Charsets.UTF_8)
            val o = JSONObject(text)
            val cls = o.optString("pluginClassName").takeIf { it.isNotBlank() && it != "null" }
                ?: throw RegistryException(CsWire.Err.BAD_REQUEST, "manifest.json without pluginClassName")
            Manifest(o.optString("name").takeIf { o.has("name") && !o.isNull("name") && it.isNotBlank() }?.take(200), cls,
                if (o.has("version") && !o.isNull("version")) o.optInt("version", PLUGIN_VERSION_NOT_SET) else PLUGIN_VERSION_NOT_SET,
                o.optBoolean("requiresResources", false))
        }
    } catch (e: RegistryException) {
        throw e
    } catch (e: Exception) {
        throw RegistryException(CsWire.Err.BAD_REQUEST, "not a .cs3")
    }

    private fun InputStream.readNBytesCompat(cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > cap) throw RegistryException(CsWire.Err.BAD_REQUEST, "manifest.json too large")
            out.write(buf, 0, n)
        }
    }

    private fun readIndex(): List<Entry> = try {
        val a = JSONArray(indexFile.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val id = o.getString("pluginId")
            val file = o.getString("file")
            val sha = o.getString("sha256")
            // The file name is never trusted from disk: it must be the one SafeNames derives from the id.
            if (SafeNames.fileName(id) != file || !SHA256.matches(sha)) null else Entry(id, file, sha, o.optInt("crashes", 0).coerceAtLeast(0))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeIndex() {
        val a = JSONArray()
        index.values.forEach { a.put(JSONObject().put("pluginId", it.pluginId).put("file", it.file).put("sha256", it.sha256).put("crashes", it.crashes)) }
        val tmp = File(dir, "${TMP}index-${UUID.randomUUID()}.json")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(indexFile)) { tmp.delete(); throw IOException("index write failed") }
        published = index.values.map { CsWire.HelloPlugin(it.pluginId, it.sha256) }
    }

    private fun sha256Of(f: File): String = f.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().toHex()
    }

    /** Stored files are read-only; deleting needs only the directory to be writable, but restore write first for safety. */
    private fun deleteFile(f: File) { if (f.exists()) { f.setWritable(true); f.delete() } }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** True when [plugin] set `openSettings` (a plain field of upstream's Plugin: reading it runs no plugin code). */
        fun hasSettings(plugin: BasePlugin): Boolean = (plugin as? Plugin)?.openSettings != null

        const val MAX_CS3_BYTES = 20L shl 20
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MARKER = "loading-"
        private const val TMP = ".tmp-"
        /** Consecutive loads that killed the process before the plugin is disabled until re-installed. */
        const val CRASHES_TO_DISABLE = 2
        /** The longest a remove or update waits on a plugin's `beforeUnload()`. */
        const val BEFORE_UNLOAD_MS = 2_000L
        /** How often a wait for a plugin's lock checks whether its op is over. */
        private const val LOCK_SLICE_MS = 100L
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
