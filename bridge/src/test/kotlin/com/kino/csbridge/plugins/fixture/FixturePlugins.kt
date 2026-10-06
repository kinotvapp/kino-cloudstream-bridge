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
import kotlinx.coroutines.launch

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

/**
 * Has a settings screen (CloudStream's `openSettings`), as plugins set it in `load()`. [mode] picks what the screen
 * does: write prefs from the screen's Context and from a thread of its own, show a dialog, or throw.
 */
class SettingsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FixtureApi())
        openSettings = { ctx ->
            opened.incrementAndGet()
            when (mode) {
                Mode.WRITE -> {
                    ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("quality", "1080").apply()
                    // Plugins also save from their own threads (no op there either).
                    val t = Thread { ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("bg", true).commit() }
                    t.start(); t.join()
                }
                Mode.DIALOG -> dialog = android.app.AlertDialog.Builder(ctx).setMessage("settings").show()
                Mode.THROW -> error("boom https://x.example/p?token=secret")
                Mode.FRAGMENT -> SettingsDialogFragment().show((ctx as androidx.fragment.app.FragmentActivity).supportFragmentManager, "settings")
                Mode.CLICK_THROW -> dialog = android.app.AlertDialog.Builder(ctx).setMessage("settings")
                    .setPositiveButton("Save") { _, _ -> error("save failed https://x.example/p?token=secret") }.show()
                Mode.THREAD_THROW -> worker = Thread { error("worker failed https://x.example/p?token=secret") }.also { it.start() }
                // Reads its values before it shows anything (a Binder round trip each): the screen must wait for it.
                Mode.TEARDOWN_THROW -> TeardownFragment().show((ctx as androidx.fragment.app.FragmentActivity).supportFragmentManager, "settings")
                // The usual Kotlin pattern: kotlinx reports this straight to the main thread's uncaught handler.
                Mode.MAIN_COROUTINE_THROW -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    error("parse failed https://x.example/p?token=secret")
                }
                Mode.SLOW_READ -> worker = Thread { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("quality", null) }.also { it.start() }
            }
        }
    }
    enum class Mode { WRITE, DIALOG, THROW, FRAGMENT, CLICK_THROW, THREAD_THROW, SLOW_READ, TEARDOWN_THROW, MAIN_COROUTINE_THROW }
    /** A plugin fragment (one of the plugin's own classes) whose teardown throws after the screen closed: a binding never set. */
    class TeardownFragment : androidx.fragment.app.DialogFragment() {
        override fun onCreateDialog(savedInstanceState: android.os.Bundle?): android.app.Dialog =
            android.app.AlertDialog.Builder(requireContext()).setMessage("settings").create()
        override fun onDestroyView() {
            super.onDestroyView()
            throw UninitializedPropertyAccessException("binding https://x.example/p?token=secret")
        }
    }

    companion object {
        @Volatile var mode = Mode.WRITE
        @Volatile var dialog: android.app.Dialog? = null
        @Volatile var worker: Thread? = null
        val opened = java.util.concurrent.atomic.AtomicInteger()
        fun reset() { mode = Mode.WRITE; dialog = null; worker = null; opened.set(0) }
    }
}

/** The common plugin pattern: an androidx DialogFragment shown on the host's FragmentManager. */
class SettingsDialogFragment : androidx.fragment.app.DialogFragment() {
    override fun onCreateDialog(savedInstanceState: android.os.Bundle?): android.app.Dialog =
        android.app.AlertDialog.Builder(requireContext()).setMessage("settings").create()
}

/** Another plugin with background work of its own (no op), running while some other plugin's settings screen is open. */
class BackgroundWriterPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(FixtureApi()) }
    companion object {
        fun writeFromOwnThread(context: Context) {
            val t = Thread { context.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("quality", "480").commit() }
            t.start(); t.join()
        }
    }
}
