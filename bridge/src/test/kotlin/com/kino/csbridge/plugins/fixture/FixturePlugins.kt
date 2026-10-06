package com.kino.csbridge.plugins.fixture

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

// JVM stand-ins for plugin classes: Robolectric cannot execute dex, so the registry tests load these from the test
// classpath (the .cs3 still carries a dexlib2-written classes.dex for the ABI check). PluginRegistryDeviceTest
// loads a real dex plugin through PathClassLoader.

class FixtureApi : MainAPI() {
    override var name = "Fixture"
    override var mainUrl = "https://fixture.example"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Torrent)
    override val mainPage = mainPageOf("/movies" to "Películas")
    override suspend fun search(query: String): List<SearchResponse> = listOf(newMovieSearchResponse("Hit $query", "/m/1"))
}

class FixtureExtractor : ExtractorApi() {
    override val name = "FixtureExtractor"
    override val mainUrl = "https://extract.example"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class FixturePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FixtureApi())
        registerExtractorAPI(FixtureExtractor())
    }
    override fun beforeUnload() { unloads++ }
    companion object { @Volatile var unloads = 0 }
}

/** Cross-platform entry point (BasePlugin.load()), as non-Android plugins use. */
class BaseFixturePlugin : BasePlugin() {
    override fun load() { registerMainAPI(FixtureApi()) }
}

/** Registers a provider, then throws: the registry must leave nothing behind. */
class ThrowingPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FixtureApi())
        error("boom https://x.example/p?token=secret")
    }
}

/** Not a BasePlugin at all. */
class NotAPlugin

/** load() blocks until the test releases it: an untrusted plugin that hangs. */
class BlockingPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FixtureApi())
        entered.countDown()
        release.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }
    override fun beforeUnload() { unloads.incrementAndGet() }
    companion object {
        @Volatile var entered = java.util.concurrent.CountDownLatch(1)
        @Volatile var release = java.util.concurrent.CountDownLatch(1)
        val unloads = java.util.concurrent.atomic.AtomicInteger()
        fun reset() { entered = java.util.concurrent.CountDownLatch(1); release = java.util.concurrent.CountDownLatch(1); unloads.set(0) }
    }
}

/**
 * Two of these load at once (each from its own class loader, as two .cs3 do): both register a provider while the other
 * is still inside load(), so each registration falls inside both registration windows.
 */
class OverlapPlugin : Plugin() {
    override fun load(context: Context) {
        val gates = com.kino.csbridge.plugins.OverlapGates
        gates.barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
        registerMainAPI(FixtureApi())
        gates.barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}

/** A provider whose getter throws while Kino's answer is built. */
class ThrowingGetterApi : MainAPI() {
    override var name = "Getter"
    override var mainUrl = "https://getter.example"
    override val mainPage: List<com.lagradost.cloudstream3.MainPageData> get() = error("getter")
}

class ThrowingGetterPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(ThrowingGetterApi()) }
}
