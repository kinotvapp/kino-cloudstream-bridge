package com.kino.csbridge.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.kino.csbridge.HostLink
import com.kino.csbridge.Ops
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.DexFixtures
import com.kino.csbridge.plugins.PluginRegistry
import com.kino.csbridge.plugins.fixture.FixtureApi
import com.kino.csbridge.plugins.fixture.FixtureExtractor
import com.kino.csbridge.plugins.fixture.FixturePlugin
import com.kino.csbridge.plugins.fixture.SettingsPlugin
import com.kino.csbridge.prefs.FakeKinoStorage
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class PluginSettingsActivityTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val kino = FakeKinoStorage()
    private lateinit var registry: PluginRegistry

    @Before fun setUp() {
        File(context.filesDir, "plugins").deleteRecursively()
        registry = PluginRegistry(context, javaClass.classLoader!!, { _, parent -> parent })
        Ops.instance = registry
        HostLink.host = kino
        SettingsPlugin.reset()
        PluginSettingsActivity.callerCheck = { true }
        PluginSettingsActivity.loadExecutor = Executor { it.run() }
        PluginSettingsActivity.flushExecutor = Executor { it.run() }
        ShadowLog.clear()
    }

    @After fun tearDown() {
        SettingsSession.clearForTest()
        PluginSettingsActivity.callerCheck = { com.kino.csbridge.CallerCheck.isKinoPackage(it, it.callingPackage) }
        PluginSettingsActivity.loadExecutor = Executor { r -> Thread(r).apply { isDaemon = true }.start() }
        PluginSettingsActivity.flushExecutor = Executor { r -> Thread(r).apply { isDaemon = true }.start() }
        registry.ids().forEach { registry.remove(it) }
        Ops.instance = null
        HostLink.host = null
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it is FixtureApi } }
        extractorApis.withLock { extractorApis.removeAll { it is FixtureExtractor } }
    }

    private fun install(id: String, pluginClass: Class<*>): PluginRegistry.InstallResult {
        val f = DexFixtures.cs3(File.createTempFile("fxs", ".cs3"), listOf(DexFixtures.currentEpisodeDex()),
            DexFixtures.manifest("Fx", pluginClass.name, 1))
        return f.inputStream().use { registry.install(id, it, DexFixtures.sha256(f)) }
    }

    private fun launch(id: String? = "cloudstream-a", focus: Boolean = true): ActivityController<PluginSettingsActivity> {
        val intent = Intent(context, PluginSettingsActivity::class.java).apply { id?.let { putExtra(CsWire.Settings.EXTRA_PLUGIN_ID, it) } }
        val c = Robolectric.buildActivity(PluginSettingsActivity::class.java, intent).create().start().resume()
        idle()
        if (!c.get().isFinishing) { c.visible(); idle() } // attaches the window (focus events need it), focus not yet given
        if (focus && !c.get().isFinishing) { c.windowFocusChanged(true); idle() }
        return c
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun ActivityController<PluginSettingsActivity>.error(): String? =
        shadowOf(get()).resultIntent?.getStringExtra(CsWire.Settings.EXTRA_ERROR)

    private fun ActivityController<PluginSettingsActivity>.resultCode() = shadowOf(get()).resultCode

    // ---- hasSettings ----------------------------------------------------------------------------------------------

    @Test fun `installPlugin answers hasSettings true for a plugin that set openSettings, false otherwise`() {
        assertTrue(install("cloudstream-a", SettingsPlugin::class.java).hasSettings)
        assertFalse(install("cloudstream-b", FixturePlugin::class.java).hasSettings)
        fun wire(id: String, cls: Class<*>): JSONObject {
            val f = DexFixtures.cs3(File.createTempFile("fxs", ".cs3"), listOf(DexFixtures.currentEpisodeDex()), DexFixtures.manifest("Fx", cls.name, 1))
            val req = CsWire.parseOpRequest(CsWire.opRequest(CsWire.Op.INSTALL, "c-$id", id, 5000, JSONObject().put("sha256", DexFixtures.sha256(f))))!!
            return JSONObject(runBlocking { Ops.run(context, req, ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) { true } })
        }
        assertEquals(true, wire("cloudstream-c", SettingsPlugin::class.java).getBoolean(CsWire.Settings.HAS_SETTINGS))
        assertEquals(false, wire("cloudstream-d", FixturePlugin::class.java).getBoolean(CsWire.Settings.HAS_SETTINGS))
    }

    // ---- who may start it -----------------------------------------------------------------------------------------

    @Test fun `the manifest exports it only behind the bridge's signature permission, in the plugins process`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, CsWire.Settings.ACTIVITY), 0)
        assertEquals(CsWire.PERMISSION, info.permission)
        assertTrue(info.exported)
        assertEquals("${context.packageName}:plugins", info.processName)
        assertEquals(CsWire.BRIDGE_PACKAGE, context.packageName)
    }

    @Test fun `a start without Kino as the calling package is refused before any plugin code`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        PluginSettingsActivity.callerCheck = { com.kino.csbridge.CallerCheck.isKinoPackage(it, it.callingPackage) }
        val c = launch() // no start for a result: no calling package
        assertTrue(c.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, c.resultCode())
        assertEquals(CsWire.Err.REFUSED, c.error())
        assertEquals(0, SettingsPlugin.opened.get())
        assertNull(SettingsSession.pluginId())
    }

    @Test fun `another app as the calling package is refused`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        PluginSettingsActivity.callerCheck = { com.kino.csbridge.CallerCheck.isKinoPackage(it, it.callingPackage) }
        val intent = Intent(context, PluginSettingsActivity::class.java).putExtra(CsWire.Settings.EXTRA_PLUGIN_ID, "cloudstream-a")
        val c = Robolectric.buildActivity(PluginSettingsActivity::class.java, intent)
        shadowOf(c.get()).setCallingPackage("evil.app")
        c.create(); idle()
        assertEquals(CsWire.Err.REFUSED, c.error())
        assertEquals(0, SettingsPlugin.opened.get())
    }

    // ---- refusals -------------------------------------------------------------------------------------------------

    @Test fun `bad id, unknown plugin, no settings, no Kino and a television each close with their code`() {
        assertEquals(CsWire.Err.BAD_REQUEST, launch(id = null).error())
        assertEquals(CsWire.Err.BAD_REQUEST, launch(id = "other-a").error())
        assertEquals(CsWire.Err.NOT_INSTALLED, launch(id = "cloudstream-zz").error())
        install("cloudstream-b", FixturePlugin::class.java)
        assertEquals(CsWire.Settings.NO_SETTINGS, launch(id = "cloudstream-b").error())
        install("cloudstream-a", SettingsPlugin::class.java)
        HostLink.host = null
        assertEquals(CsWire.Err.UNAVAILABLE, launch().error())
        HostLink.host = kino
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertEquals(CsWire.Settings.UNSUPPORTED_DEVICE, launch().error())
        assertEquals(0, SettingsPlugin.opened.get())
        assertNull(SettingsSession.pluginId())
    }

    // ---- storage routing ------------------------------------------------------------------------------------------

    @Test fun `writes made by the settings screen go to that plugin's Kino storage, with two plugins installed`() {
        install("cloudstream-b", FixturePlugin::class.java)
        install("cloudstream-a", SettingsPlugin::class.java)
        val c = launch()
        assertEquals(1, SettingsPlugin.opened.get())
        idle(PluginSettingsActivity.IDLE_GRACE_MS + 100) // it showed nothing: done
        assertTrue(c.get().isFinishing)
        assertEquals(Activity.RESULT_OK, c.resultCode())
        val a = kino.stores["cloudstream-a"].orEmpty()
        assertEquals("1080", JSONObject(a.getValue("\u0000cfg\u0000quality")).getString("v"))
        assertEquals(true, JSONObject(a.getValue("\u0000cfg\u0000bg")).getBoolean("v")) // from the plugin's own thread
        assertNull(kino.stores["cloudstream-b"])
        assertNull(SettingsSession.pluginId())
    }

    @Test fun `openSettings waits for the window, then the screen stays while the plugin's dialog is up`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        val c = launch(focus = false)
        assertEquals(0, SettingsPlugin.opened.get())
        c.windowFocusChanged(true); idle()
        assertEquals(1, SettingsPlugin.opened.get())
        c.windowFocusChanged(false) // the dialog took focus
        idle(PluginSettingsActivity.IDLE_GRACE_MS * 3)
        assertFalse(c.get().isFinishing)
        assertTrue(SettingsSession.pluginId() == "cloudstream-a")
        SettingsPlugin.dialog!!.dismiss()
        c.windowFocusChanged(true); idle(PluginSettingsActivity.SETTLE_MS + 100)
        assertTrue(c.get().isFinishing)
        assertEquals(Activity.RESULT_OK, c.resultCode())
    }

    // ---- crash guard ----------------------------------------------------------------------------------------------

    @Test fun `a throwing openSettings closes with plugin_error and one log line without the URL`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.THROW
        val c = launch()
        assertEquals(1, SettingsPlugin.opened.get())
        assertTrue(c.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, c.resultCode())
        assertEquals(CsWire.Err.PLUGIN, c.error())
        val lines = ShadowLog.getLogsForTag("KinoBridgeSettings").map { it.msg }
        assertEquals(listOf("openSettings of cloudstream-a failed: IllegalStateException"), lines)
        assertNull(SettingsSession.pluginId())
    }

    @Test fun `a recreated instance never restores the plugin's fragments and closes`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        val intent = Intent(context, PluginSettingsActivity::class.java).putExtra(CsWire.Settings.EXTRA_PLUGIN_ID, "cloudstream-a")
        val c = Robolectric.buildActivity(PluginSettingsActivity::class.java, intent).create(android.os.Bundle())
        idle()
        assertEquals(CsWire.Err.CANCELLED, c.error())
        assertEquals(0, SettingsPlugin.opened.get())
    }
}
