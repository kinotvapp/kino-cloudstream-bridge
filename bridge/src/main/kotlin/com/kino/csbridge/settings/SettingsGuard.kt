// GPL-3.0 (this fork).
package com.kino.csbridge.settings

import android.os.Handler
import android.os.Looper
import com.kino.csbridge.plugins.PluginFrames
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a settings screen's plugin from killing `:plugins` (and every op the service is running) with a throwable
 * raised after `openSettings` returned: a click handler, a late `show()`, a `Dispatchers.Main` coroutine, its own
 * thread, its fragments' teardown.
 *
 * A throwable is absorbed only when a settings session is open (it lasts until the screen is destroyed) AND the
 * innermost frame of it (deepest cause first) that exactly one plugin claims is that session's plugin ([PluginFrames],
 * with the classes the session kept) AND it is not a VM error (other than stack overflow) AND, on the main thread, it
 * was not raised inside a view traversal, a Choreographer frame, or the activity lifecycle dispatch other than the
 * screen's destruction (create, start, resume, pause, stop: the framework's own bookkeeping would be left half-done;
 * those crash as before). Destruction is absorbed: the result was already delivered at finish(), and the system
 * times out a destroy that never reports back and drops the record. Absorbed: the session's
 * [SettingsSession.Token.onPluginFailure] closes the screen with `plugin_error`. Anything else goes where it went before
 * (the main thread's crash, the previous default handler).
 *
 * - Main thread, unwinding: a loop guard, installed once per process when the first screen opens, runs `Looper.loop()`
 *   again after an absorbed throwable (the "loop again" pattern; Android only logs a warning). The throwable came out of
 *   one message; the queue and the rest of the process go on.
 * - Main thread, reported: kotlinx.coroutines hands a `Dispatchers.Main` coroutine's throwable straight to the thread's
 *   uncaught-exception handler without unwinding the loop; the wrapped default handler absorbs it there too (only with
 *   the loop guard installed, so a throwable that did unwind the loop always met the guard first).
 * - Other threads: the same wrapped default handler; an absorbed throwable ends only that thread (or, from a coroutine,
 *   is just reported), without the previous handler (which would kill the process).
 *
 * Logs: the bridge writes one line (class name only). Android's own pre-handler ("FATAL EXCEPTION" for a thread) and
 * kotlinx's Android pre-handler still print the stack and message to logcat before the default handler runs, which the
 * bridge cannot stop; such a logcat entry is not a crash when this screen closed with `plugin_error`.
 */
object SettingsGuard {
    /** Tests turn the loop guard off: Robolectric's main looper runs on the test thread, which `Looper.loop()` would block. */
    @Volatile internal var loopGuard = true
    /** True once the loop guard is running, set from inside it (tests set it to exercise the main-thread report path). */
    @Volatile internal var mainGuarded = false
    private val installed = AtomicBoolean()

    private class Wrapper(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, e: Throwable) {
            if (!handle(thread, e)) previous?.uncaughtException(thread, e)
        }
    }

    /** Wraps the default handler (again if something replaced it since) and, once per process, starts the loop guard. */
    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !is Wrapper) Thread.setDefaultUncaughtExceptionHandler(Wrapper(current))
        if (!installed.compareAndSet(false, true)) return
        if (loopGuard) {
            Handler(Looper.getMainLooper()).post {
                // Only now does a main-thread throwable meet the guard first; before this, one that unwound the loop must
                // still crash cleanly (never be absorbed by the default handler on a dead main thread).
                mainGuarded = true
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
    }

    /** The default handler's decision: true = absorbed (the thread, or the main loop, goes on). */
    internal fun handle(thread: Thread, e: Throwable): Boolean {
        val main = thread === Looper.getMainLooper().thread
        if (main && !mainGuarded) return false
        return absorb(e, onMainThread = main)
    }

    /** True when [e] was the open screen's plugin's and was handed to the screen; false = let it crash as before. */
    fun absorb(e: Throwable, onMainThread: Boolean = false): Boolean {
        val session = SettingsSession.current() ?: return false
        if (e is VirtualMachineError && e !is StackOverflowError) return false
        val pinned = session.claim?.let { session.pluginId to it }
        if (PluginFrames.ownerOf(e, pinned) != session.pluginId) return false
        if (onMainThread && unsafe(e)) return false
        runCatching { session.onPluginFailure(e) }
        return true
    }

    private fun unsafe(e: Throwable): Boolean {
        val frames = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(16).flatMap { it.stackTrace.asSequence() }.toList()
        if (frames.any { it.isRenderFrame() }) return true
        return frames.any { it.isLifecycleFrame() } && frames.none { it.isDestroyFrame() }
    }

    private fun StackTraceElement.isRenderFrame() =
        (className == "android.view.ViewRootImpl" && methodName == "performTraversals") ||
            (className == "android.view.Choreographer" && methodName == "doFrame")

    private fun StackTraceElement.isLifecycleFrame() =
        (className == "android.app.ActivityThread" && (methodName.startsWith("perform") || methodName.startsWith("handle"))) ||
            className.startsWith("android.app.servertransaction.") ||
            (className == "android.app.Instrumentation" && methodName.startsWith("callActivityOn"))

    private fun StackTraceElement.isDestroyFrame() =
        (className == "android.app.ActivityThread" && (methodName == "performDestroyActivity" || methodName == "handleDestroyActivity")) ||
            (className == "android.app.Instrumentation" && methodName == "callActivityOnDestroy")
}
