// GPL-3.0 (this fork).
package com.kino.csbridge.settings

import android.app.UiModeManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.kino.csbridge.BridgeApp
import com.kino.csbridge.CallerCheck
import com.kino.csbridge.HostLink
import com.kino.csbridge.Ops
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.plugins.PluginRegistry
import com.kino.csbridge.plugins.SafeNames
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.concurrent.Executor

/**
 * Hosts a CloudStream plugin's own settings screen (upstream `Plugin.openSettings`), for Kino only (PROTOCOL.md
 * "Plugin settings"). It runs in `:plugins`, the process that holds the plugin's classes, and has no window of its own:
 * the plugin's dialog (an AlertDialog, a DialogFragment) shows over Kino.
 *
 * **Who may start it.** Two independent checks: the manifest requires the bridge's signature-level permission (the
 * system refuses anyone not signed like Kino before this class runs), and [onCreate] checks that the calling package,
 * which the system fills in only for a start for a result, is Kino and signed like the bridge ([CallerCheck]). A start
 * without a result has no calling package and is refused (`refused`).
 *
 * **Whose storage.** [SettingsSession] names the plugin from before its load until the screen closes, so every
 * SharedPreferences / DataStore access with no live op goes to that plugin's Kino storage, whatever thread the plugin
 * uses (never the single-installed-plugin fallback). This activity's own getSharedPreferences goes to [BridgeApp]'s
 * routing too, so a plugin handed this Context never writes a prefs file here.
 *
 * **When it closes.** Plugins give no "closed" callback, so: it closes when it has window focus (no dialog is on top of
 * it) and holds no fragment, checked when focus comes back, when a fragment goes, and [IDLE_GRACE_MS] after
 * `openSettings` returned (a plugin that showed nothing). A tap on the empty screen or Back closes it too. Before
 * `finish()` it waits up to [FLUSH_MS] for the plugin's storage writes to reach Kino.
 *
 * **Never crashes the process for a throwable it can catch:** the plugin's load and `openSettings` (with the fragment
 * transactions it queued) are guarded; a throwable is one log line (class name only) and `RESULT_CANCELED` with
 * [CsWire.Settings.EXTRA_ERROR] `plugin_error`. A recreated instance (process death) never restores the plugin's
 * fragments (their classes belong to the plugin's loader) and closes at once.
 */
class PluginSettingsActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var pluginId: String? = null
    private var session: SettingsSession.Token? = null
    private var settings: ((android.content.Context) -> Unit)? = null
    private var focused = false
    private var invoked = false
    private var closing = false
    private val idleCheck = Runnable { closeIfIdle() }

    private val fragmentWatcher = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) { scheduleIdleCheck(SETTLE_MS) }
        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) { scheduleIdleCheck(SETTLE_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore saved fragments: they are the plugin's classes, which the framework cannot instantiate.
        super.onCreate(null)
        if (savedInstanceState != null) return close(CsWire.Err.CANCELLED)
        if (!callerCheck(this)) return close(CsWire.Err.REFUSED)
        if (isTelevision()) return close(CsWire.Settings.UNSUPPORTED_DEVICE)
        val id = intent?.getStringExtra(CsWire.Settings.EXTRA_PLUGIN_ID)
        if (id == null || !id.startsWith(ID_PREFIX) || SafeNames.fileName(id) == null) return close(CsWire.Err.BAD_REQUEST)
        // Without Kino bound, the plugin's settings would read defaults and lose every write.
        if (HostLink.host == null) return close(CsWire.Err.UNAVAILABLE)
        pluginId = id
        session = SettingsSession.begin(id)
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentWatcher, true)
        val timeout = Runnable { if (settings == null && !closing) close(CsWire.Err.TIMEOUT) }
        main.postDelayed(timeout, LOAD_TIMEOUT_MS)
        loadExecutor.execute {
            val outcome: Any = try {
                Ops.registry(applicationContext).plugin(id)
            } catch (e: PluginRegistry.RegistryException) {
                e.code
            } catch (e: LinkageError) {
                logFailure("load", id, e)
                CsWire.Err.INCOMPATIBLE
            } catch (t: Throwable) {
                logFailure("load", id, t)
                CsWire.Err.PLUGIN
            }
            main.post {
                main.removeCallbacks(timeout)
                if (closing || isDestroyed) return@post
                when (outcome) {
                    is String -> close(outcome)
                    is BasePlugin -> {
                        val open = (outcome as? Plugin)?.openSettings
                        if (open == null) close(CsWire.Settings.NO_SETTINGS) else { settings = open; invokeIfReady() }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        if (!hasFocus) return
        if (!invoked) invokeIfReady() else scheduleIdleCheck(SETTLE_MS)
    }

    /** Plugins assume an attached Activity window (dialogs need its token): opens once this one has focus. */
    private fun invokeIfReady() {
        val open = settings ?: return
        if (invoked || closing || !focused) return
        invoked = true
        try {
            open(this)
            supportFragmentManager.executePendingTransactions() // a DialogFragment's own onCreate runs inside the guard
        } catch (t: Throwable) {
            logFailure("openSettings", pluginId, t)
            return close(CsWire.Err.PLUGIN)
        }
        scheduleIdleCheck(IDLE_GRACE_MS)
    }

    private fun scheduleIdleCheck(delayMs: Long) {
        if (!invoked || closing) return
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, delayMs)
    }

    /** Nothing of the plugin's is on screen: no dialog above this window (it has focus) and no fragment held. */
    private fun closeIfIdle() {
        if (!invoked || closing || !focused) return
        val holds = supportFragmentManager.fragments.any { it.isAdded && !it.isRemoving }
        if (!holds) close(null)
    }

    /** A tap that reached this empty window: no dialog is on top, the person tapped outside. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && invoked && focused && supportFragmentManager.fragments.none { it.isAdded }) {
            close(null)
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Back with nothing left to pop, or the plugin finishing its host: closes through the flush. */
    override fun finish() {
        if (closing) super.finish() else close(null)
    }

    /**
     * Sets the result ([error] null = `RESULT_OK`), waits off the main thread for the plugin's pending storage writes
     * (at most [FLUSH_MS]), then finishes. Idempotent.
     */
    private fun close(error: String?) {
        if (closing) return
        closing = true
        main.removeCallbacks(idleCheck)
        if (error == null) setResult(RESULT_OK) else setResult(RESULT_CANCELED, Intent().putExtra(CsWire.Settings.EXTRA_ERROR, error))
        val token = session
        if (token == null) { super.finish(); return }
        flushExecutor.execute {
            runCatching { (application as? BridgeApp)?.prefsStore?.awaitIdle(FLUSH_MS) }
            main.post {
                SettingsSession.end(token)
                super.finish()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentWatcher) }
        session?.let { SettingsSession.end(it) }
        super.onDestroy()
    }

    // The plugin may keep this Context: its prefs go where the app's go (Kino), never to a file in this data dir.
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = applicationContext.getSharedPreferences(name, mode)
    override fun deleteSharedPreferences(name: String?): Boolean = applicationContext.deleteSharedPreferences(name)

    private fun isTelevision(): Boolean = try {
        (getSystemService(UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    } catch (_: RuntimeException) {
        true // unknown: do not show
    }

    companion object {
        private const val TAG = "KinoBridgeSettings"
        private const val ID_PREFIX = "cloudstream-"
        /** The plugin's load (first use in this process) gets this long; past it the screen closes with `timeout`. */
        internal const val LOAD_TIMEOUT_MS = 20_000L
        /** How long after `openSettings` returned a screen that shows nothing is taken as done. */
        internal const val IDLE_GRACE_MS = 1_500L
        /** Lets a dismissal's fragment transaction and focus change land before the idle check. */
        internal const val SETTLE_MS = 300L
        /** The longest the close waits for storage writes to reach Kino. */
        internal const val FLUSH_MS = 3_000L

        /** Tests swap these (the caller check needs a real start for a result; the executors run inline in tests). */
        @Volatile internal var callerCheck: (AppCompatActivity) -> Boolean = { CallerCheck.isKinoPackage(it, it.callingPackage) }
        @Volatile internal var loadExecutor: Executor = Executor { r -> Thread(r, "bridge-settings-load").apply { isDaemon = true }.start() }
        @Volatile internal var flushExecutor: Executor = Executor { r -> Thread(r, "bridge-settings-flush").apply { isDaemon = true }.start() }

        /** One line, the throwable's class only: a message can carry a URL. */
        private fun logFailure(what: String, pluginId: String?, t: Throwable) {
            runCatching { Log.w(TAG, "$what of ${pluginId ?: "?"} failed: ${t.javaClass.simpleName}") }
        }
    }
}
