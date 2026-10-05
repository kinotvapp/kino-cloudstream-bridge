package com.kino.csbridge.plugins

import org.robolectric.RuntimeEnvironment
import android.content.Context
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.fixture.FixtureApi
import com.kino.csbridge.plugins.fixture.FixtureExtractor
import com.kino.csbridge.plugins.fixture.BlockingPlugin
import com.kino.csbridge.plugins.fixture.FixturePlugin
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.extractorApis
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PluginRegistryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val dir get() = File(context.filesDir, "plugins")
    private val created = mutableListOf<PluginRegistry>()

    /** Robolectric cannot run dex: plugin classes come from the test classpath (the .cs3's dex is only ABI-checked). */
    private fun registry(maxBytes: Long = PluginRegistry.MAX_CS3_BYTES) =
        PluginRegistry(context, javaClass.classLoader!!, { _, parent -> parent }, maxBytes).also { created += it }

    private fun cs3(pluginClass: String = FixturePlugin::class.java.name, dex: ByteArray = DexFixtures.currentEpisodeDex(), name: String = "Fixture") =
        DexFixtures.cs3(File.createTempFile("fixture", ".cs3"), listOf(dex), DexFixtures.manifest(name, pluginClass))

    private fun PluginRegistry.installFile(id: String, f: File, sha: String = DexFixtures.sha256(f)) = f.inputStream().use { install(id, it, sha) }

    private fun fixtureProviders() = APIHolder.allProviders.withLock { APIHolder.allProviders.filterIsInstance<FixtureApi>() }
    private fun fixtureExtractors() = extractorApis.withLock { extractorApis.filterIsInstance<FixtureExtractor>() }

    @Before fun clean() { dir.deleteRecursively() }

    @After fun unregisterEverything() {
        created.forEach { r -> r.ids().forEach { runCatching { r.remove(it) } } }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it is FixtureApi } }
        APIHolder.apis.filter { it is FixtureApi }.forEach { APIHolder.removePluginMapping(it) }
        extractorApis.withLock { extractorApis.removeAll { it is FixtureExtractor } }
        dir.deleteRecursively()
    }

    @Test fun `install registers one provider under its id and persists it`() {
        val r = registry()
        val f = cs3()
        val res = r.installFile("cloudstream-fx-1", f)
        assertEquals("Fixture", res.name); assertEquals(3, res.version); assertEquals(emptyList<String>(), res.missingSymbols)
        assertEquals(1, res.providers.size)
        assertSame(res.providers.single(), r.providers("cloudstream-fx-1").single())
        assertEquals(listOf(res.providers.single()), fixtureProviders())
        assertEquals(1, fixtureExtractors().size)
        assertEquals(listOf("cloudstream-fx-1"), r.ids())
        assertEquals(listOf(CsWire.HelloPlugin("cloudstream-fx-1", DexFixtures.sha256(f))), r.stored())
        val stored = File(dir, "cloudstream-fx-1.cs3")
        assertTrue(stored.exists()); assertFalse(stored.canWrite())
        assertEquals(stored.absolutePath, res.providers.single().sourcePlugin)
        assertTrue(dir.listFiles()!!.none { it.name.startsWith(".tmp-") || it.name.startsWith("loading-") })
        // The index on disk names id, file and sha.
        val idx = JSONArray(File(dir, "index.json").readText()).getJSONObject(0)
        assertEquals("cloudstream-fx-1", idx.getString("pluginId")); assertEquals("cloudstream-fx-1.cs3", idx.getString("file"))
        assertEquals(DexFixtures.sha256(f), idx.getString("sha256"))
    }

    @Test fun `two ids of the same plugin keep two providers, and removing one leaves the other`() {
        val r = registry()
        val f = cs3()
        val a = r.installFile("cloudstream-a", f).providers.single()
        val b = r.installFile("cloudstream-b", f).providers.single()
        assertEquals(2, fixtureProviders().size)
        assertEquals(2, fixtureExtractors().size)
        val unloadsBefore = FixturePlugin.unloads
        r.remove("cloudstream-a")
        assertEquals(unloadsBefore + 1, FixturePlugin.unloads)
        assertEquals(listOf(b), fixtureProviders())
        assertEquals(1, fixtureExtractors().size)
        assertFalse(APIHolder.apis.any { it === a })
        assertEquals(listOf("cloudstream-b"), r.ids())
        assertFalse(File(dir, "cloudstream-a.cs3").exists())
        assertThrows(PluginRegistry.RegistryException::class.java) { r.providers("cloudstream-a") }
        assertSame(b, r.providers("cloudstream-b").single())
    }

    @Test fun `re-installing an id replaces its providers`() {
        val r = registry()
        val first = r.installFile("cloudstream-a", cs3()).providers.single()
        val second = r.installFile("cloudstream-a", cs3(name = "Fixture2")).providers.single()
        assertEquals(listOf(second), fixtureProviders())
        assertFalse(first === second)
        assertEquals(1, r.stored().size)
    }

    @Test fun `cross-platform load() is called for a BasePlugin`() {
        val r = registry()
        assertEquals(1, r.installFile("cloudstream-base", cs3(com.kino.csbridge.plugins.fixture.BaseFixturePlugin::class.java.name)).providers.size)
    }

    @Test fun `a plugin whose load throws leaves nothing registered or stored`() {
        val r = registry()
        val e = assertThrows(IllegalStateException::class.java) {
            r.installFile("cloudstream-bad", cs3(com.kino.csbridge.plugins.fixture.ThrowingPlugin::class.java.name))
        }
        assertTrue(e.message!!.startsWith("boom"))
        assertEquals(emptyList<FixtureApi>(), fixtureProviders())
        assertEquals(emptyList<String>(), r.ids())
        assertFalse(File(dir, "cloudstream-bad.cs3").exists())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("loading-") || it.name.endsWith(".cs3") || it.name.startsWith(".tmp-") })
    }

    @Test fun `a class that is not a plugin is a plugin error and nothing is stored`() {
        val r = registry()
        assertThrows(ClassCastException::class.java) { r.installFile("cloudstream-x", cs3(com.kino.csbridge.plugins.fixture.NotAPlugin::class.java.name)) }
        assertEquals(emptyList<String>(), r.ids())
    }

    @Test fun `missing symbols refuse the plugin before loading it`() {
        val r = registry()
        val res = r.installFile("cloudstream-old", cs3(dex = DexFixtures.oldEpisodeDex()))
        assertEquals(1, res.missingSymbols.size)
        assertTrue(res.missingSymbols.single().startsWith("Lcom/lagradost/cloudstream3/Episode;-><init>("))
        assertEquals(emptyList<Any>(), res.providers)
        assertEquals("Fixture", res.name)
        assertEquals(emptyList<FixtureApi>(), fixtureProviders())
        assertEquals(emptyList<String>(), r.ids())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".cs3") || it.name.startsWith(".tmp-") })
    }

    @Test fun `a refused update keeps the installed version`() {
        val r = registry()
        val installed = r.installFile("cloudstream-a", cs3()).providers.single()
        assertEquals(1, r.installFile("cloudstream-a", cs3(dex = DexFixtures.oldEpisodeDex())).missingSymbols.size)
        assertSame(installed, r.providers("cloudstream-a").single())
    }

    @Test fun `sha mismatch, bad id, oversize, junk and missing manifest are refused with nothing left`() {
        val r = registry(maxBytes = 64 * 1024)
        val f = cs3()
        fun code(block: () -> Unit) = assertThrows(PluginRegistry.RegistryException::class.java, block).code
        assertEquals(CsWire.Err.BAD_REQUEST, code { r.installFile("cloudstream-a", f, "0".repeat(64)) })
        assertEquals(CsWire.Err.BAD_REQUEST, code { r.installFile("cloudstream-a", f, "NOT-HEX") })
        assertEquals(CsWire.Err.BAD_REQUEST, code { r.installFile("../a", f) })
        val big = File.createTempFile("big", ".cs3").apply { writeBytes(ByteArray(64 * 1024 + 1)) }
        assertEquals(CsWire.Err.TOO_LARGE, code { r.installFile("cloudstream-a", big) })
        val junk = File.createTempFile("junk", ".cs3").apply { writeBytes(ByteArray(300) { 9 }) }
        assertEquals(CsWire.Err.BAD_REQUEST, code { r.installFile("cloudstream-a", junk) })
        val noManifest = DexFixtures.cs3(File.createTempFile("nomanifest", ".cs3"), listOf(DexFixtures.currentEpisodeDex()), manifest = null)
        assertEquals(CsWire.Err.BAD_REQUEST, code { r.installFile("cloudstream-a", noManifest) })
        assertEquals(emptyList<String>(), r.ids())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".cs3") || it.name.startsWith(".tmp-") })
    }

    @Test fun `a new process loads stored plugins lazily, under their id`() {
        val f = cs3()
        val first = registry().installFile("cloudstream-a", f).providers.single()
        simulateProcessDeath()
        val r = registry()
        r.ensureLoaded()
        assertEquals(listOf(CsWire.HelloPlugin("cloudstream-a", DexFixtures.sha256(f))), r.stored())
        assertEquals(emptyList<FixtureApi>(), fixtureProviders()) // nothing runs before an op asks for it
        val p = r.providers("cloudstream-a").single()
        assertFalse(p === first)
        assertEquals(listOf(p), fixtureProviders())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("loading-") })
    }

    /** A new process finds the marker a load left when the previous process died inside it. */
    private fun crashDuringLoad(id: String) { simulateProcessDeath(); File(dir, "loading-$id").writeText("") }

    private fun incompatible(r: PluginRegistry, id: String) =
        assertEquals(CsWire.Err.INCOMPATIBLE, assertThrows(PluginRegistry.RegistryException::class.java) { r.providers(id) }.code)

    @Test fun `crash-loop guard - one interrupted load is forgiven and a successful load resets the count`() {
        val f = cs3()
        registry().installFile("cloudstream-a", f)
        crashDuringLoad("cloudstream-a")
        val r = registry(); r.ensureLoaded()
        assertFalse(File(dir, "loading-cloudstream-a").exists())
        assertEquals(1, JSONArray(File(dir, "index.json").readText()).getJSONObject(0).getInt("crashes"))
        assertEquals(1, r.providers("cloudstream-a").size) // an OOM kill or an unbind mid-load does not disable it
        assertEquals(0, JSONArray(File(dir, "index.json").readText()).getJSONObject(0).getInt("crashes"))
        // Not consecutive any more: one more death is again only the first.
        crashDuringLoad("cloudstream-a")
        assertEquals(1, registry().providers("cloudstream-a").size)
    }

    @Test fun `crash-loop guard - two consecutive interrupted loads disable the plugin until it is re-installed`() {
        val f = cs3()
        registry().installFile("cloudstream-a", f)
        crashDuringLoad("cloudstream-a")
        registry().ensureLoaded() // count 1, and the process dies again before any successful load
        crashDuringLoad("cloudstream-a")
        val r = registry(); r.ensureLoaded()
        assertFalse(File(dir, "loading-cloudstream-a").exists())
        // Still listed with its sha, so Kino does not re-send the same file in a loop.
        assertEquals(listOf(CsWire.HelloPlugin("cloudstream-a", DexFixtures.sha256(f))), r.stored())
        incompatible(r, "cloudstream-a")
        assertEquals(emptyList<FixtureApi>(), fixtureProviders())
        // Survives another restart.
        simulateProcessDeath()
        val r2 = registry()
        incompatible(r2, "cloudstream-a")
        // Kino re-installs: loads again, count back to zero.
        assertEquals(1, r2.installFile("cloudstream-a", f).providers.size)
        assertEquals(0, JSONArray(File(dir, "index.json").readText()).getJSONObject(0).getInt("crashes"))
        simulateProcessDeath()
        assertEquals(1, registry().providers("cloudstream-a").size)
    }

    @Test fun `a crash marker of plugin A leaves plugin B loadable after a restart`() {
        val f = cs3()
        registry().apply { installFile("cloudstream-a", f); installFile("cloudstream-b", f) }
        crashDuringLoad("cloudstream-a")
        registry().ensureLoaded()
        crashDuringLoad("cloudstream-a")
        val r = registry()
        incompatible(r, "cloudstream-a")
        assertEquals(1, r.providers("cloudstream-b").size)
        assertEquals(0, JSONArray(File(dir, "index.json").readText()).getJSONObject(1).getInt("crashes"))
    }

    @Test fun `a new version whose load fails is rolled back to the previous one`() {
        val r = registry()
        val old = cs3()
        r.installFile("cloudstream-a", old)
        assertThrows(IllegalStateException::class.java) {
            r.installFile("cloudstream-a", cs3(com.kino.csbridge.plugins.fixture.ThrowingPlugin::class.java.name))
        }
        assertEquals(listOf(CsWire.HelloPlugin("cloudstream-a", DexFixtures.sha256(old))), r.stored())
        assertEquals(DexFixtures.sha256(old), DexFixtures.sha256(File(dir, "cloudstream-a.cs3")))
        assertFalse(File(dir, "cloudstream-a.cs3").canWrite())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith(".tmp-") || it.name.startsWith("loading-") })
        // The previous version loads again (lazily), with only its own provider registered.
        assertEquals(1, r.providers("cloudstream-a").size)
        assertEquals(1, fixtureProviders().size)
        // And after a restart, too.
        simulateProcessDeath()
        assertEquals(1, registry().providers("cloudstream-a").size)
    }

    @Test fun `hello and list never wait on a plugin load that hangs, nor on another install`() {
        val r = registry()
        val stable = cs3()
        r.installFile("cloudstream-stable", stable)
        BlockingPlugin.reset()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val hung = pool.submit<Any> { r.installFile("cloudstream-hung", cs3(BlockingPlugin::class.java.name)) }
            assertTrue("load() never started", BlockingPlugin.entered.await(5, TimeUnit.SECONDS))
            // A second install streams and hashes outside the lock, then queues behind the hung load.
            val queued = pool.submit<Any> { r.installFile("cloudstream-queued", cs3()) }
            val hello = pool.submit<List<CsWire.HelloPlugin>> { r.stored() }
            val ids = hello.get(2, TimeUnit.SECONDS).map { it.id }
            assertTrue(ids.contains("cloudstream-stable"))
            assertEquals(ids, r.ids())
            assertFalse(queued.isDone)
            BlockingPlugin.release.countDown()
            hung.get(5, TimeUnit.SECONDS); queued.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("cloudstream-stable", "cloudstream-hung", "cloudstream-queued"), r.ids())
        } finally {
            BlockingPlugin.release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `remove of an id SafeNames refuses is a bad request`() {
        assertEquals(CsWire.Err.BAD_REQUEST, assertThrows(PluginRegistry.RegistryException::class.java) { registry().remove("../index.json") }.code)
        assertEquals(CsWire.Err.BAD_REQUEST, assertThrows(PluginRegistry.RegistryException::class.java) { registry().remove("cloudstream-A") }.code)
    }

    @Test fun `ensureLoaded drops entries whose file no longer matches its sha`() {
        val f = cs3()
        registry().installFile("cloudstream-a", f)
        registry().installFile("cloudstream-b", f)
        simulateProcessDeath()
        File(dir, "cloudstream-a.cs3").apply { setWritable(true); appendBytes(byteArrayOf(1)) }
        File(dir, "cloudstream-b.cs3").apply { setWritable(true); delete() }
        val r = registry(); r.ensureLoaded()
        assertEquals(emptyList<CsWire.HelloPlugin>(), r.stored())
        assertEquals(CsWire.Err.NOT_INSTALLED, assertThrows(PluginRegistry.RegistryException::class.java) { r.providers("cloudstream-a") }.code)
    }

    @Test fun `remove of an unknown id is a no-op and unknown providers are not installed`() {
        val r = registry()
        r.remove("cloudstream-nothing")
        assertEquals(CsWire.Err.NOT_INSTALLED, assertThrows(PluginRegistry.RegistryException::class.java) { r.providers("cloudstream-nothing") }.code)
        assertNotNull(r.ids())
    }

    /** The old registry's in-memory registrations die with the process; its files stay. */
    private fun simulateProcessDeath() {
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it is FixtureApi } }
        APIHolder.apis.filter { it is FixtureApi }.forEach { APIHolder.removePluginMapping(it) }
        extractorApis.withLock { extractorApis.removeAll { it is FixtureExtractor } }
        created.clear()
    }
}
