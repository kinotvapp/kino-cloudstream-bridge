// GPL-3.0 (this fork).
package com.kino.csbridge.settings

import android.os.Handler
import android.os.Looper
import com.kino.csbridge.plugins.PluginFrames
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a settings screen's plugin from killing `:plugins` (and every op the service is running) with a throwable
 * raised after `openSettings` returned: a click handler, a late `show()`, its own thread.
 *
 * A throwable is absorbed only when a settings session is open AND the throwable went through a frame of that
 * session's plugin ([PluginFrames]) AND it is not a VM error (other than stack overflow) AND, on the main thread, it was
 * not raised inside a view traversal or a Choreographer frame (the framework may be half-way through updating its own
 * state there). Absorbed: the session's [SettingsSession.Token.onPluginFailure] closes the screen with `plugin_error`.
 * Anything else goes where it went before (the main thread's crash, the previous default handler).
 *
 * - Main thread: a loop guard, installed once per process when the first screen opens, runs `Looper.loop()` again after
 *   an absorbed throwable (the "loop again" pattern; Android only logs a warning). The throwable came out of a Handler or
 *   View callback, i.e. one message; the queue and the rest of the process go on.
 * - Other threads: the default uncaught-exception handler, wrapped once; an absorbed throwable ends only that thread
 *   (or, from a coroutine, is just reported), without the previous handler (which would kill the process).
 */
object SettingsGuard {
    /** Tests turn the loop guard off: Robolectric's main looper runs on the test thread, which `Looper.loop()` would block. */
    @Volatile internal var loopGuard = true
    private val installed = AtomicBoolean()

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (thread !== Looper.getMainLooper().thread && absorb(e)) return@setDefaultUncaughtExceptionHandler
            previous?.uncaughtException(thread, e)
        }
        if (loopGuard) Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post // the main looper never quits; if it did, there is nothing left to guard
                } catch (e: Throwable) {
                    if (!absorb(e, onMainThread = true)) throw e
                }
            }
        }
    }

    /** True when [e] was the open screen's plugin's and was handed to the screen; false = let it crash as before. */
    fun absorb(e: Throwable, onMainThread: Boolean = false): Boolean {
        val session = SettingsSession.current() ?: return false
        if (e is VirtualMachineError && e !is StackOverflowError) return false
        if (!PluginFrames.inStack(session.pluginId, e)) return false
        if (onMainThread && e.stackTrace.any { it.isUnsafeFrame() }) return false
        runCatching { session.onPluginFailure(e) }
        return true
    }

    private fun StackTraceElement.isUnsafeFrame() =
        (className == "android.view.ViewRootImpl" && methodName == "performTraversals") ||
            (className == "android.view.Choreographer" && methodName == "doFrame")
}
