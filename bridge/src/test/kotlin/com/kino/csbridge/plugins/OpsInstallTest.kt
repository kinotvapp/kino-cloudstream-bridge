package com.kino.csbridge.plugins

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kino.csbridge.Ops
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.fixture.FixtureApi
import com.kino.csbridge.plugins.fixture.FixtureExtractor
import com.kino.csbridge.plugins.fixture.FixturePlugin
import com.kino.csbridge.plugins.fixture.BlockingPlugin
import com.kino.csbridge.ops.PluginWork
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OpsInstallTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        File(context.filesDir, "plugins").deleteRecursively()
        Ops.instance = PluginRegistry(context, javaClass.classLoader!!, { _, parent -> parent })
    }

    @After fun tearDown() {
        Ops.dupPayload = { it.dup() }
        Ops.instance?.let { r -> r.ids().forEach { r.remove(it) } }
        Ops.instance = null
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it is FixtureApi } }
        extractorApis.withLock { extractorApis.removeAll { it is FixtureExtractor } }
    }

    private fun op(op: String, args: JSONObject = JSONObject(), payload: File? = null, id: String = "cloudstream-fx") = runBlocking {
        val req = CsWire.parseOpRequest(CsWire.opRequest(op, "c1", id, 5000, args))!!
        Ops.run(context, req, payload?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }) { true }
    }

    @Test fun `installPlugin answers the PROTOCOL shape and listPlugins and hello show id and sha`() {
        val f = DexFixtures.cs3(File.createTempFile("fixture", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Cuevana", FixturePlugin::class.java.name, 7))
        val sha = DexFixtures.sha256(f)
        val o = JSONObject(op(CsWire.Op.INSTALL, JSONObject().put("sha256", sha).put("fileName", "Cuevana.cs3"), f))
        assertEquals("Cuevana", o.getString("name")); assertEquals(7, o.getInt("version"))
        assertEquals(0, o.getJSONArray("missingSymbols").length())
        val p = o.getJSONArray("providers").getJSONObject(0)
        assertEquals("Fixture", p.getString("name")); assertEquals("https://fixture.example", p.getString("mainUrl"))
        assertEquals("es", p.getString("lang")); assertEquals(true, p.getBoolean("hasMainPage"))
        assertEquals("[\"Movie\"]", p.getJSONArray("tvTypes").toString()) // Torrent never advertised
        assertEquals("Películas", p.getJSONArray("mainPages").getJSONObject(0).getString("name"))
        assertEquals("/movies", p.getJSONArray("mainPages").getJSONObject(0).getString("data"))

        val listed = CsWire.helloPluginsOf(JSONObject(op(CsWire.Op.LIST, id = "list")).getJSONArray("plugins"))
        assertEquals(listOf(CsWire.HelloPlugin("cloudstream-fx", sha)), listed)
        assertEquals(listed, Ops.helloPlugins(context))

        assertEquals(true, JSONObject(op(CsWire.Op.REMOVE)).getBoolean("ok"))
        assertEquals(emptyList<CsWire.HelloPlugin>(), Ops.helloPlugins(context))
    }

    @Test fun `missing symbols come back in the answer and nothing is installed`() {
        val f = DexFixtures.cs3(File.createTempFile("old", ".cs3"), listOf(DexFixtures.oldEpisodeDex()))
        val o = JSONObject(op(CsWire.Op.INSTALL, JSONObject().put("sha256", DexFixtures.sha256(f)), f))
        assertEquals(0, o.getJSONArray("providers").length())
        assertTrue(o.getJSONArray("missingSymbols").getString(0).startsWith("Lcom/lagradost/cloudstream3/Episode;-><init>("))
        assertEquals(emptyList<CsWire.HelloPlugin>(), Ops.helloPlugins(context))
    }

    @Test fun `registry refusals become wire errors`() {
        val f = DexFixtures.cs3(File.createTempFile("fixture", ".cs3"), listOf(DexFixtures.currentEpisodeDex()))
        assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(op(CsWire.Op.INSTALL, JSONObject().put("sha256", "f".repeat(64)), f))!!.first)
        assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(op(CsWire.Op.INSTALL, JSONObject().put("sha256", DexFixtures.sha256(f))))!!.first)
        assertEquals(CsWire.Err.BAD_REQUEST,
            CsWire.errorOf(op(CsWire.Op.INSTALL, JSONObject().put("sha256", DexFixtures.sha256(f)), f, id = "cloudstream-UPPER"))!!.first)
    }

    @Test fun `provider ops find the provider through the registry by plugin id and index`() {
        val f = DexFixtures.cs3(File.createTempFile("fixture", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Cuevana", FixturePlugin::class.java.name, 7))
        op(CsWire.Op.INSTALL, JSONObject().put("sha256", DexFixtures.sha256(f)), f)
        val items = JSONObject(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "x"))).getJSONArray("items")
        assertEquals("Hit x", items.getJSONObject(0).getString("name"))
        assertEquals("https://fixture.example/m/1", items.getJSONObject(0).getString("url"))
        assertEquals(CsWire.Err.NOT_INSTALLED, CsWire.errorOf(op(CsWire.Op.SEARCH, JSONObject().put("provider", 1).put("query", "x")))!!.first)
        assertEquals(CsWire.Err.NOT_INSTALLED,
            CsWire.errorOf(op(CsWire.Op.SEARCH, JSONObject().put("provider", 0).put("query", "x"), id = "cloudstream-other"))!!.first)
    }

    private fun waitUntil(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) { assertTrue(what, System.nanoTime() < end); Thread.sleep(20) }
    }

    @Test fun `a plugin with 3 stuck tasks is still removed at once, without its beforeUnload`() {
        val f = DexFixtures.cs3(File.createTempFile("fixture", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Cuevana", FixturePlugin::class.java.name, 7))
        op(CsWire.Op.INSTALL, JSONObject().put("sha256", DexFixtures.sha256(f)), f)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            runBlocking {
                repeat(3) {
                    kotlinx.coroutines.withTimeoutOrNull(50) { PluginWork.run("cloudstream-fx") { release.await(20, java.util.concurrent.TimeUnit.SECONDS) } }
                }
            }
            assertTrue(PluginWork.busy("cloudstream-fx"))
            val unloadsBefore = FixturePlugin.unloads
            val t0 = System.nanoTime()
            assertEquals(true, JSONObject(op(CsWire.Op.REMOVE)).getBoolean("ok"))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
            assertEquals(emptyList<CsWire.HelloPlugin>(), Ops.helloPlugins(context))
            assertEquals(unloadsBefore, FixturePlugin.unloads)
        } finally {
            release.countDown()
            PluginWork.resetForTest()
        }
    }

    @Test fun `an install that finishes after Kino stopped waiting is rolled back`() {
        BlockingPlugin.reset()
        val f = DexFixtures.cs3(File.createTempFile("blocking", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Lento", BlockingPlugin::class.java.name, 1))
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val answer = runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(300) {
                    val req = CsWire.parseOpRequest(CsWire.opRequest(CsWire.Op.INSTALL, "c1", "cloudstream-slow", 5000,
                        JSONObject().put("sha256", DexFixtures.sha256(f))))!!
                    Ops.run(context, req, pfd) { true }
                }
            }
            assertEquals(null, answer) // Kino was told the install did not finish
            pfd.close() // as the service does when the op ends; the install reads its own dup
            assertTrue("load() never started", BlockingPlugin.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            BlockingPlugin.release.countDown()
            waitUntil("the late install was not rolled back") { BlockingPlugin.unloads.get() == 1 && "cloudstream-slow" !in Ops.instance!!.ids() }
            assertTrue(APIHolder.allProviders.withLock { APIHolder.allProviders.none { it is FixtureApi } })
        } finally {
            BlockingPlugin.release.countDown()
        }
    }

    @Test fun `an install cancelled before it starts still closes its own descriptor`() {
        val f = DexFixtures.cs3(File.createTempFile("fixture", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Cuevana", FixturePlugin::class.java.name, 7))
        // A descriptor that records its close (the wrapper constructor shares the dup's fd).
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val dups = java.util.concurrent.atomic.AtomicInteger()
        Ops.dupPayload = { dups.incrementAndGet(); object : ParcelFileDescriptor(it.dup()) { override fun close() { closes.incrementAndGet(); super.close() } } }
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            runBlocking {
                // Both registry threads busy: the install below is queued and cancelled before it starts.
                repeat(2) {
                    kotlinx.coroutines.withTimeoutOrNull(50) {
                        PluginWork.run("cloudstream-other", on = PluginWork.registryDispatcher) { release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                    }
                }
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val answer = kotlinx.coroutines.withTimeoutOrNull(200) {
                    val req = CsWire.parseOpRequest(CsWire.opRequest(CsWire.Op.INSTALL, "c1", "cloudstream-fx", 5000,
                        JSONObject().put("sha256", DexFixtures.sha256(f))))!!
                    Ops.run(context, req, pfd) { true }
                }
                assertEquals(null, answer)
                pfd.close()
            }
            assertEquals(1, dups.get())
            // A cancelled, never-started task completes once a registry thread frees up and skips its body.
            release.countDown()
            waitUntil("the install's own descriptor was left open") { closes.get() >= 1 }
            assertEquals(emptyList<CsWire.HelloPlugin>(), Ops.helloPlugins(context)) // never ran
        } finally {
            release.countDown()
            PluginWork.resetForTest()
        }
    }

    @Test fun `a retry that fails validation does not stop a late install's rollback`() {
        BlockingPlugin.reset()
        val f = DexFixtures.cs3(File.createTempFile("blocking", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Lento", BlockingPlugin::class.java.name, 1))
        val retryPool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            val answer = runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(300) {
                    val req = CsWire.parseOpRequest(CsWire.opRequest(CsWire.Op.INSTALL, "c1", "cloudstream-slow", 5000,
                        JSONObject().put("sha256", DexFixtures.sha256(f))))!!
                    Ops.run(context, req, pfd) { true }
                }
            }
            assertEquals(null, answer)
            pfd.close()
            assertTrue("load() never started", BlockingPlugin.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Kino retries with a sha256 that does not match: refused, nothing changes.
            val retry = retryPool.submit<String> {
                op(CsWire.Op.INSTALL, JSONObject().put("sha256", "f".repeat(64)), f, id = "cloudstream-slow")
            }
            BlockingPlugin.release.countDown()
            assertEquals(CsWire.Err.BAD_REQUEST, CsWire.errorOf(retry.get(10, java.util.concurrent.TimeUnit.SECONDS))!!.first)
            waitUntil("the late install was not rolled back") { BlockingPlugin.unloads.get() == 1 && "cloudstream-slow" !in Ops.instance!!.ids() }
        } finally {
            BlockingPlugin.release.countDown()
            retryPool.shutdown()
        }
    }
}
