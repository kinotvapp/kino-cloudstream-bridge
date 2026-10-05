package com.kino.csbridge.plugins

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kino.csbridge.Ops
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.fixture.FixtureApi
import com.kino.csbridge.plugins.fixture.FixtureExtractor
import com.kino.csbridge.plugins.fixture.FixturePlugin
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
        Ops.instance?.let { r -> r.ids().forEach { r.remove(it) } }
        Ops.instance = null
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it is FixtureApi } }
        extractorApis.withLock { extractorApis.removeAll { it is FixtureExtractor } }
    }

    private fun op(op: String, args: JSONObject = JSONObject(), payload: File? = null, id: String = "cloudstream-fx") = runBlocking {
        val req = CsWire.parseOpRequest(CsWire.opRequest(op, "c1", id, 5000, args))!!
        Ops.run(context, req, payload?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }) {}
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
}
