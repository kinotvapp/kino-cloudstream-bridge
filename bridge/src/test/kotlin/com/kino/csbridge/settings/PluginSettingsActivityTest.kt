package com.kino.csbridge.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.view.MotionEvent
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.kino.csbridge.HostLink
import com.kino.csbridge.Ops
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.DexFixtures
import com.kino.csbridge.plugins.PluginRegistry
import com.kino.csbridge.plugins.fixture.FixtureApi
import com.kino.csbridge.plugins.fixture.FixtureExtractor
import com.kino.csbridge.plugins.fixture.BackgroundWriterPlugin
import com.kino.csbridge.plugins.fixture.BlockingPlugin
import com.kino.csbridge.plugins.fixture.FixturePlugin
import com.kino.csbridge.plugins.fixture.SettingsDialogFragment
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
        SettingsGuard.mainGuarded = false
        SettingsGuard.loopGuard = false // Robolectric's main looper is this test thread
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
        c.finishOut()
        return c
    }

    /** What the system does after finish(): pause, stop, destroy (the session ends after onDestroy). No-op if still open. */
    private fun ActivityController<PluginSettingsActivity>.finishOut() {
        if (!get().isFinishing || get().isDestroyed) return
        runCatching { pause() }; runCatching { stop() }
        destroy(); idle()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun ActivityController<PluginSettingsActivity>.error(): String? =
        shadowOf(get()).resultIntent?.getStringExtra(CsWire.Settings.EXTRA_ERROR)

    private fun ActivityController<PluginSettingsActivity>.resultCode() = shadowOf(get()).resultCode

    // ---- hasSettings ----------------------------------------------------------------------------------------------

    @Test fun `installPlugin answers hasSettings true for a plugin that set openSettings, false otherwise`() {
        assertTrue(install("cloudstream-a", SettingsPlugin::class.java).hasSettings)
        assertFalse(install("cloudstream-b", FixturePlugin::class.java).hasSettings)
        // Missing symbols: nothing loads, so never true (Kino must not offer a gear for a plugin that is not stored).
        val old = DexFixtures.cs3(File.createTempFile("fxs", ".cs3"), listOf(DexFixtures.refusedSymbolDex()),
            DexFixtures.manifest("Fx", SettingsPlugin::class.java.name, 1))
        val refused = old.inputStream().use { registry.install("cloudstream-e", it, DexFixtures.sha256(old)) }
        assertTrue(refused.missingSymbols.isNotEmpty()); assertFalse(refused.hasSettings)
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
        // The security property is the protection level, not the name.
        val level = context.packageManager.getPermissionInfo(CsWire.PERMISSION, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, level)
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
        assertEquals("cloudstream-a", SettingsSession.pluginId()) // until the screen is destroyed
        c.finishOut()
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

    // ---- fix round 1 ----------------------------------------------------------------------------------------------

    @Test fun `a DialogFragment keeps the screen open until it is dismissed`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.FRAGMENT
        val c = launch()
        val f = c.get().supportFragmentManager.findFragmentByTag("settings") as SettingsDialogFragment
        // Even with focus (a fragment that is not a dialog window, or a focus event that came late): it holds a fragment.
        idle(PluginSettingsActivity.IDLE_GRACE_MS * 2)
        assertFalse(c.get().isFinishing)
        c.windowFocusChanged(false)
        f.dismiss(); idle()
        c.windowFocusChanged(true); idle(PluginSettingsActivity.SETTLE_MS + 100)
        assertTrue(c.get().isFinishing)
        assertEquals(Activity.RESULT_OK, c.resultCode())
    }

    @Test fun `a tap outside and Back both close through the flush with RESULT_OK`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        val tapped = launch()
        tapped.get().onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 1f, 1f, 0)); idle()
        assertTrue(tapped.get().isFinishing); assertEquals(Activity.RESULT_OK, tapped.resultCode())
        tapped.finishOut()
        assertNull(SettingsSession.pluginId())
        val backed = launch()
        backed.get().onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(backed.get().isFinishing); assertEquals(Activity.RESULT_OK, backed.resultCode())
        backed.finishOut()
        assertNull(SettingsSession.pluginId())
    }

    @Test fun `a load that does not finish in time closes with timeout`() {
        BlockingPlugin.release.countDown() // lets the install's own load through
        install("cloudstream-a", BlockingPlugin::class.java)
        registry.unloadForTest("cloudstream-a")
        BlockingPlugin.reset()
        PluginSettingsActivity.loadExecutor = Executor { r -> Thread(r).apply { isDaemon = true }.start() }
        try {
            val c = launch()
            assertTrue(BlockingPlugin.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            idle(PluginSettingsActivity.LOAD_TIMEOUT_MS + 100)
            assertEquals(CsWire.Err.TIMEOUT, c.error())
        } finally {
            BlockingPlugin.release.countDown()
        }
    }

    @Test fun `a second screen while one is open is busy and does not take over storage`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        install("cloudstream-b", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        val first = launch()
        assertEquals(CsWire.Err.BUSY, launch(id = "cloudstream-b").error())
        assertEquals("cloudstream-a", SettingsSession.pluginId())
        assertFalse(first.get().isFinishing)
    }

    @Test fun `removing or updating the plugin while its screen is open closes it with cancelled`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        val c = launch()
        c.windowFocusChanged(false)
        registry.remove("cloudstream-a"); idle()
        assertTrue(c.get().isFinishing)
        assertEquals(CsWire.Err.CANCELLED, c.error())
        c.finishOut()
        assertNull(SettingsSession.pluginId())
    }

    @Test fun `another plugin's background thread keeps its own storage while a screen is open`() {
        install("cloudstream-b", BackgroundWriterPlugin::class.java)
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        launch()
        assertEquals("cloudstream-a", SettingsSession.pluginId())
        BackgroundWriterPlugin.writeFromOwnThread(context)
        assertEquals("480", JSONObject(kino.stores["cloudstream-b"].orEmpty().getValue("\u0000cfg\u0000quality")).getString("v"))
        assertNull(kino.stores["cloudstream-a"])
    }

    @Test fun `a throw from the plugin's click handler is absorbed, closes with plugin_error, and nothing else is`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.CLICK_THROW
        var flushed = false
        PluginSettingsActivity.flushExecutor = Executor { flushed = true } // never runs: a plugin_error must not wait on it
        val c = launch()
        c.windowFocusChanged(false)
        // Not the plugin's: crashes as before.
        assertFalse(SettingsGuard.absorb(IllegalStateException("not the plugin's"), onMainThread = true))
        // On a device this reaches the loop guard; here Robolectric hands it to the test thread.
        val e = runCatching {
            SettingsPlugin.dialog!!.let { it as android.app.AlertDialog }.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            idle()
        }.exceptionOrNull()!!
        assertTrue(SettingsGuard.absorb(e, onMainThread = true))
        idle()
        assertTrue(c.get().isFinishing)
        assertEquals(CsWire.Err.PLUGIN, c.error())
        assertEquals(listOf("openSettings of cloudstream-a failed: IllegalStateException"), ShadowLog.getLogsForTag("KinoBridgeSettings").map { it.msg })
        assertFalse("finished at once, no flush wait", flushed)
        // Once the screen is gone, the same throwable is no longer absorbed.
        c.finishOut()
        assertNull(SettingsSession.pluginId())
        assertFalse(SettingsGuard.absorb(e, onMainThread = true))
    }

    @Test fun `a throw inside a view traversal is never absorbed`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        launch()
        val e = IllegalStateException("x").apply {
            stackTrace = arrayOf(StackTraceElement(SettingsPlugin::class.java.name, "onMeasure", null, 1),
                StackTraceElement("android.view.ViewRootImpl", "performTraversals", null, 1))
        }
        assertFalse(SettingsGuard.absorb(e, onMainThread = true))
        assertTrue(SettingsGuard.absorb(e, onMainThread = false))
    }

    @Test fun `a throw on the plugin's own thread is absorbed by the default handler and closes the screen`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.THREAD_THROW
        val c = launch()
        SettingsPlugin.worker!!.join(5_000)
        idle()
        assertTrue(c.get().isFinishing)
        assertEquals(CsWire.Err.PLUGIN, c.error())
    }

    @Test fun `the screen does not close while the plugin is still reading its values from Kino`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.SLOW_READ
        val gate = java.util.concurrent.CountDownLatch(1)
        kino.block = gate
        try {
            val c = launch()
            val end = System.nanoTime() + 2_000_000_000L
            while (kino.ops.none { it.getString("op") == "get" } && System.nanoTime() < end) Thread.sleep(5)
            idle(PluginSettingsActivity.IDLE_GRACE_MS + PluginSettingsActivity.SETTLE_MS * 2)
            assertFalse(c.get().isFinishing)
            gate.countDown()
            SettingsPlugin.worker!!.join(5_000)
            idle(PluginSettingsActivity.SETTLE_MS + 100)
            assertTrue(c.get().isFinishing)
            assertEquals(Activity.RESULT_OK, c.resultCode())
            assertEquals("cloudstream-a", kino.ops.first { it.getString("op") == "get" }.getString("pluginId"))
        } finally {
            gate.countDown(); kino.block = null
        }
    }

    // ---- fix round 2 ----------------------------------------------------------------------------------------------

    @Test fun `classes the bridge can load are never a plugin's, for throwables and storage alike`() {
        // A .cs3 that bundles its own copy of a library class the bridge has: parent-first, that copy never runs.
        val bundled = DexFixtures.dex(DexFixtures.runner("Lkotlinx/coroutines/DispatchedTask;", 1, listOf(DexFixtures.returnVoid())))
        val f = DexFixtures.cs3(File.createTempFile("fxs", ".cs3"), listOf(DexFixtures.currentEpisodeDex(), bundled),
            DexFixtures.manifest("Fx", SettingsPlugin::class.java.name, 1))
        f.inputStream().use { registry.install("cloudstream-a", it, DexFixtures.sha256(f)) }
        val claim = com.kino.csbridge.plugins.PluginFrames.claim("cloudstream-a")!!
        assertTrue(claim.owns("fixture.Current"))
        assertFalse(claim.owns("kotlinx.coroutines.DispatchedTask"))
        val libraryOnly = arrayOf(StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", null, 1))
        assertNull(com.kino.csbridge.plugins.PluginFrames.ownerOf(libraryOnly))
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        launch()
        val e = IllegalStateException("x").apply { stackTrace = libraryOnly }
        assertFalse(SettingsGuard.absorb(e, onMainThread = false))
    }

    @Test fun `a frame two loaded plugins both claim is nobody's`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        install("cloudstream-b", SettingsPlugin::class.java) // same plugin class name in two .cs3
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        launch()
        val e = IllegalStateException("x").apply { stackTrace = arrayOf(StackTraceElement(SettingsPlugin::class.java.name, "f", null, 1)) }
        assertFalse(SettingsGuard.absorb(e, onMainThread = false))
    }

    @Test fun `a throw from the plugin's fragment teardown is contained in onDestroy, even after the plugin was removed`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.TEARDOWN_THROW
        val c = launch()
        c.windowFocusChanged(false)
        registry.remove("cloudstream-a"); idle() // its live classes are unregistered; the screen kept its own copy
        assertNull(com.kino.csbridge.plugins.PluginFrames.claim("cloudstream-a"))
        assertTrue(c.get().isFinishing)
        // The destroy completes (the framework then removes the token's windows): nothing escapes it.
        val e = runCatching { c.pause(); c.stop(); c.destroy() }.exceptionOrNull()
        assertNull(e)
        assertTrue(c.get().isDestroyed)
        assertEquals(CsWire.Err.CANCELLED, c.error())
        idle()
        assertNull(SettingsSession.pluginId())
    }

    @Test fun `a teardown throw that is not the plugin's still escapes onDestroy`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.TEARDOWN_THROW
        val c = launch()
        c.windowFocusChanged(false)
        SettingsSession.clearForTest() // no open session: nothing is the screen's plugin's to absorb
        c.get().finish(); idle()
        val e = runCatching { c.pause(); c.stop(); c.destroy() }.exceptionOrNull()
        assertTrue(e != null)
    }

    @Test fun `a Dispatchers Main coroutine throw reported to the main thread's handler is absorbed when the loop is guarded`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.MAIN_COROUTINE_THROW
        SettingsGuard.mainGuarded = true
        try {
            val c = launch()
            idle()
            assertTrue(c.get().isFinishing)
            assertEquals(CsWire.Err.PLUGIN, c.error())
        } finally {
            SettingsGuard.mainGuarded = false
        }
    }

    @Test fun `without the loop guard a main thread report is never absorbed`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        launch()
        val e = IllegalStateException("x").apply { stackTrace = arrayOf(StackTraceElement(SettingsPlugin::class.java.name, "f", null, 1)) }
        assertFalse(SettingsGuard.handle(Looper.getMainLooper().thread, e))
        assertTrue(SettingsGuard.handle(Thread.currentThread().let { Thread() }, e))
    }

    @Test fun `throws inside the activity lifecycle dispatch crash as before, except the screen's destruction`() {
        install("cloudstream-a", SettingsPlugin::class.java)
        SettingsPlugin.mode = SettingsPlugin.Mode.DIALOG
        launch()
        fun thrown(vararg frames: Pair<String, String>) = RuntimeException("Unable to resume activity",
            IllegalStateException("x").apply { stackTrace = arrayOf(StackTraceElement(SettingsPlugin::class.java.name, "onResume", null, 1)) })
            .apply { stackTrace = frames.map { StackTraceElement(it.first, it.second, null, 1) }.toTypedArray() }
        assertFalse(SettingsGuard.absorb(thrown("android.app.ActivityThread" to "performResumeActivity"), onMainThread = true))
        assertFalse(SettingsGuard.absorb(thrown("android.app.servertransaction.TransactionExecutor" to "execute"), onMainThread = true))
        assertFalse(SettingsGuard.absorb(thrown("android.app.Instrumentation" to "callActivityOnStart"), onMainThread = true))
        assertTrue(SettingsGuard.absorb(thrown("android.app.servertransaction.TransactionExecutor" to "execute",
            "android.app.ActivityThread" to "performDestroyActivity"), onMainThread = true))
    }
}
