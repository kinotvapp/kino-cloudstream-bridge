package com.kino.csbridge.plugins.fixture

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
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
