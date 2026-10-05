package com.kino.csbridge.plugins

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kino.csbridge.BridgeApp
import com.kino.csbridge.contract.CsWire
import com.lagradost.cloudstream3.APIHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On ART: real dex through PathClassLoader with the bridge's own class loader as parent, and the ABI check against
 * the classes the bridge APK really ships (the JVM tests resolve against the test classpath instead).
 */
@RunWith(AndroidJUnit4::class)
class PluginRegistryDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = File(context.filesDir, "plugins")
    private val registries = mutableListOf<PluginRegistry>()

    private fun registry() = PluginRegistry(context).also { registries += it }
    private fun cs3(dex: ByteArray, name: String = "DexFixture") =
        DexFixtures.cs3(File.createTempFile("fixture", ".cs3", context.cacheDir), listOf(dex), DexFixtures.manifest(name, "fixture.DexPlugin"))
    private fun PluginRegistry.installFile(id: String, f: File) = f.inputStream().use { install(id, it, DexFixtures.sha256(f)) }
    private fun dexProviders() = APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it.javaClass.name == "fixture.DexApi" } }

    @Before fun clean() { dir.deleteRecursively() }

    @After fun tearDown() {
        registries.forEach { r -> r.ids().forEach { r.remove(it) } }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.javaClass.name == "fixture.DexApi" } }
        dir.deleteRecursively()
    }

    @Test fun abiCheckAgainstTheShippedClasses() {
        val loader = BridgeApp::class.java.classLoader!!
        assertEquals(emptyList<String>(), SymbolChecker(loader).missing(cs3(DexFixtures.currentEpisodeDex())))
        assertEquals(emptyList<String>(), SymbolChecker(loader).missing(cs3(DexFixtures.pluginDex())))
        assertEquals(listOf("${DexFixtures.EPISODE}-><init>(${DexFixtures.EPISODE_CTOR_OLD.joinToString("")})V"),
            SymbolChecker(loader).missing(cs3(DexFixtures.oldEpisodeDex())))
    }

    @Test fun realDexPluginRegistersUnderItsIdAndUnloadsCleanly() {
        val r = registry()
        val f = cs3(DexFixtures.pluginDex())
        val a = r.installFile("cloudstream-dex-a", f).providers.single()
        val b = r.installFile("cloudstream-dex-b", f).providers.single()
        assertEquals("DexFixture", a.name)
        assertNotSame(a.javaClass, b.javaClass) // one class loader per plugin id
        assertEquals(2, dexProviders().size)
        assertFalse(File(dir, "cloudstream-dex-a.cs3").canWrite())
        r.remove("cloudstream-dex-a")
        assertEquals(listOf(b), dexProviders())
        assertEquals(listOf(CsWire.HelloPlugin("cloudstream-dex-b", DexFixtures.sha256(f))), r.stored())
    }

    @Test fun throwingLoadLeavesNothing() {
        val r = registry()
        assertThrows(IllegalStateException::class.java) { r.installFile("cloudstream-dex-bad", cs3(DexFixtures.pluginDex(throwAfterRegister = true))) }
        assertEquals(emptyList<Any>(), dexProviders())
        assertEquals(emptyList<String>(), r.ids())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".cs3") || it.name.startsWith("loading-") })
    }

    @Test fun storedPluginLoadsLazilyAndAMarkerMakesItIncompatible() {
        val f = cs3(DexFixtures.pluginDex())
        registry().installFile("cloudstream-dex-a", f)
        // New process: what the old one registered is gone; the file and the index stay.
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.javaClass.name == "fixture.DexApi" } }
        registries.clear()
        val r = registry()
        assertEquals("DexFixture", r.providers("cloudstream-dex-a").single().name)

        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.javaClass.name == "fixture.DexApi" } }
        registries.clear()
        File(dir, "loading-cloudstream-dex-a").writeText("")
        val crashed = registry()
        assertEquals(CsWire.Err.INCOMPATIBLE, assertThrows(PluginRegistry.RegistryException::class.java) { crashed.providers("cloudstream-dex-a") }.code)
        assertEquals(1, crashed.stored().size)
    }
}
