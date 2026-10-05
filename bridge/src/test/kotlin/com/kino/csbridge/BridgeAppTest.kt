package com.kino.csbridge

import android.content.Context
import androidx.preference.PreferenceManager
import com.kino.csbridge.prefs.FakeKinoStorage
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ExceptionHandler
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BridgeAppTest {
    private val app = RuntimeEnvironment.getApplication()
    private val kino = FakeKinoStorage()

    @Before fun setUp() { HostLink.host = kino }

    @After fun tearDown() { HostLink.host = null; CallContext.threadLocal.remove() }

    private fun <T> asPlugin(id: String = PLUGIN, block: () -> T): T {
        CallContext.threadLocal.set(CallContext("call-1", id, CallContext.deadlineFromNow(60_000)))
        try { return block() } finally { CallContext.threadLocal.remove() }
    }

    /** setKey is apply(): wait for the writer before looking at Kino's side. */
    private fun idle() = assertTrue((app as BridgeApp).prefsStore.awaitIdle(5_000))

    /**
     * LOUD GUARD. CloudStreamApp.onCreate installs an uncaught-exception handler that calls exitProcess(1) (a plugin's
     * stray throw would kill the bridge) and the class is final with a private context setter: BridgeApp must never be
     * one, and its context must be set reflectively. If this fails, do not "fix" it by subclassing.
     */
    @Test fun `BridgeApp is not a CloudStreamApp and never installs its exitProcess handler`() {
        assertTrue(app is BridgeApp)
        assertFalse(CloudStreamApp::class.java.isAssignableFrom(BridgeApp::class.java))
        assertFalse(Thread.getDefaultUncaughtExceptionHandler() is ExceptionHandler)
        assertEquals(null, CloudStreamApp.exceptionHandler)
    }

    /**
     * LOUD GUARD for the reflective context: if upstream renames CloudStreamApp.Companion.setContext or the `_context`
     * field, getKey/setKey and every plugin reading CloudStreamApp.context silently become no-ops. R8 keeps both
     * (proguard-rules.pro).
     */
    @Test fun `CloudStreamApp context and the library context are the bridge app once it started`() {
        assertSame(app, CloudStreamApp.context)
        assertSame(app, com.lagradost.api.getContext())
        val setter = CloudStreamApp.Companion::class.java.getDeclaredMethod("setContext", Context::class.java)
        assertNotNull(setter)
        assertNotNull(CloudStreamApp::class.java.getDeclaredField("_context"))
    }

    @Test fun `initForBridge sets both contexts again from scratch`() {
        CloudStreamApp::class.java.getDeclaredField("_context").apply { isAccessible = true }.set(null, null)
        com.lagradost.api.setContext(null)
        assertEquals(null, CloudStreamApp.context)
        assertEquals(null, com.lagradost.api.getContext())
        CloudStreamApp.initForBridge(app)
        assertSame(app, CloudStreamApp.context)
        assertSame(app, com.lagradost.api.getContext())
    }

    @Test fun `upstream setKey and getKey land in the calling plugin's Kino storage`() {
        asPlugin { CloudStreamApp.setKey("folder/path", mapOf("a" to 1)) }
        idle()
        val stored = kino.stores[PLUGIN]!!["folder/path"]!!
        assertEquals("s", JSONObject(stored).getString("t"))
        assertEquals(mapOf("a" to 1), asPlugin { CloudStreamApp.getKey<Map<String, Int>>("folder/path") })
        assertEquals(listOf("folder/path"), asPlugin { CloudStreamApp.getKeys("folder") })
        asPlugin { CloudStreamApp.removeKey("folder/path") }
        idle()
        assertTrue(kino.stores[PLUGIN]!!.isEmpty())
    }

    @Test fun `PluginManager getPluginsOnline reads through Kino too`() {
        kino.stores.getOrPut(PLUGIN) { LinkedHashMap() }[PLUGINS_KEY] = JSONObject().put("t", "s")
            .put("v", """[{"internalName":"X","url":null,"isOnline":true,"filePath":"/x.cs3","version":3}]""").toString()
        val online = asPlugin { PluginManager.getPluginsOnline() }
        assertEquals("X", online.single().internalName)
    }

    @Test fun `every prefs name persists in Kino, namespaced, and rebuild_preference never sees the others`() {
        assertEquals("rebuild_preference", PREFERENCES_NAME)
        asPlugin {
            app.getSharedPrefs().edit().putString("settings/k", "v").commit()
            app.getDefaultSharedPrefs().edit().putString("k", "d").commit()
            PreferenceManager.getDefaultSharedPreferences(app).edit().putInt("n", 1).commit()
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("x", true).commit()
        }
        val stored = kino.stores[PLUGIN]!!
        assertEquals(setOf("settings/k", "\u0000/default\u0000k", "\u0000/default\u0000n", "\u0000settings\u0000x"), stored.keys)
        asPlugin {
            // DataStore's folder listing never sees a named key, even one whose name looks like the folder.
            assertEquals(listOf("settings/k"), CloudStreamApp.getKeys("settings"))
            assertEquals(setOf("settings/k"), app.getSharedPrefs().all.keys)
            assertEquals(mapOf("k" to "d", "n" to 1), app.getDefaultSharedPrefs().all)
            assertTrue(app.getSharedPreferences("settings", 0).getBoolean("x", false))
            // Clearing rebuild_preference keeps every named key.
            app.getSharedPrefs().edit().clear().commit()
        }
        assertEquals(setOf("\u0000/default\u0000k", "\u0000/default\u0000n", "\u0000settings\u0000x"), stored.keys)
        assertSame(app.getSharedPreferences(PREFERENCES_NAME, 0), app.getSharedPreferences(PREFERENCES_NAME, 0))
    }

    @Test fun `two plugins using the same prefs name stay apart`() {
        asPlugin(PLUGIN) { app.getSharedPreferences("Extension", 0).edit().putString("token", "a").commit() }
        asPlugin(OTHER) { app.getSharedPreferences("Extension", 0).edit().putString("token", "b").commit() }
        assertEquals("a", asPlugin(PLUGIN) { app.getSharedPreferences("Extension", 0).getString("token", null) })
        assertEquals("b", asPlugin(OTHER) { app.getSharedPreferences("Extension", 0).getString("token", null) })
        assertEquals(null, asPlugin(OTHER) { app.getSharedPreferences("Ext", 0).getString("token", null) })
    }

    @Test fun `deleteSharedPreferences clears that name only, moveSharedPreferencesFrom moves nothing`() {
        asPlugin {
            app.getSharedPreferences("a", 0).edit().putString("k", "1").commit()
            app.getSharedPreferences("b", 0).edit().putString("k", "2").commit()
            assertTrue(app.deleteSharedPreferences("a"))
            assertEquals(null, app.getSharedPreferences("a", 0).getString("k", null))
            assertEquals("2", app.getSharedPreferences("b", 0).getString("k", null))
        }
        assertFalse(app.moveSharedPreferencesFrom(app, "b"))
    }

    @Test fun `nothing is written in the bridge's data dir`() {
        val dataDir = app.dataDir
        fun files() = dataDir.walkTopDown().filter(File::isFile).map { it.relativeTo(dataDir).path }.toSet()
        Ops.registry(app).ids() // the plugin registry's own index is not plugin state: created before the snapshot
        val before = files()
        asPlugin {
            CloudStreamApp.setKey("k", "v")
            CloudStreamApp.setKey(PLUGINS_KEY, arrayOf<String>())
            app.getDefaultSharedPrefs().edit().putString("x", "y").commit()
            app.getSharedPreferences("other", 0).edit().putString("x", "y").commit()
            app.getSharedPreferences(PREFERENCES_NAME, 0).edit().putLong("l", 1).apply()
        }
        CloudStreamApp.setKey("unattributed", "v") // no call, no single plugin: memory
        idle()
        assertEquals(emptySet<String>(), files() - before)
        assertFalse(File(dataDir, "shared_prefs/$PREFERENCES_NAME.xml").exists())
        assertTrue(kino.stores[PLUGIN]!!.containsKey("k"))
    }

    private companion object {
        const val PLUGIN = "cloudstream-app-test-cccccc"
        const val OTHER = "cloudstream-other-dddddd"
    }
}
