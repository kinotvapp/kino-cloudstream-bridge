// GPL-3.0 (this fork).
package com.kino.csbridge.plugins

import java.util.concurrent.ConcurrentHashMap

/**
 * Which loaded plugin a stack frame belongs to, by class name: the classes its `.cs3` dex defines that only it can run,
 * plus its plugin class and that class's nested and synthetic classes (`Name$…`, the only ones JVM tests can see, where
 * fixtures are not dex). Classes the bridge itself can load are never a plugin's: class loading is parent-first, so a
 * plugin's bundled copy of `kotlin.*`, `kotlinx.coroutines.*`, OkHttp… never runs, and a frame of such a class is the
 * bridge's (or every plugin's). The caller filters those out before [register] ([PluginRegistry] asks the bridge's
 * class loader once per load).
 *
 * Used where no op says whose code is running: storage access from a plugin's own threads, and a throwable on the
 * settings screen. A frame is attributed only when exactly one plugin claims it; a frame two plugins claim (the same
 * class name in two .cs3) names neither and the search goes on to the next frame.
 */
object PluginFrames {
    /** One plugin's classes; a settings screen keeps its own copy so an update or removal mid-close changes nothing. */
    class Claim internal constructor(private val classes: Set<String>, private val pluginClass: String) {
        fun owns(name: String) = name in classes || name == pluginClass || name.startsWith("$pluginClass$")
    }

    private val owned = ConcurrentHashMap<String, Claim>()

    /** [classes] must already exclude every name the bridge's own class loader resolves. */
    fun register(pluginId: String, classes: Set<String>, pluginClass: String) { owned[pluginId] = Claim(classes, pluginClass) }

    fun unregister(pluginId: String) { owned.remove(pluginId) }

    /** [pluginId]'s current claim (immutable: a later update registers a new one), or null when it is not loaded. */
    fun claim(pluginId: String): Claim? = owned[pluginId]

    /**
     * The plugin of the innermost frame of [stack] that exactly one plugin claims, or null. [pinned] (id to claim)
     * stands for that id instead of (or besides) its live registration.
     */
    fun ownerOf(stack: Array<StackTraceElement>, pinned: Pair<String, Claim>? = null): String? {
        if (owned.isEmpty() && pinned == null) return null
        val claims = owned.entries.filter { it.key != pinned?.first }.map { it.key to it.value } + listOfNotNull(pinned)
        for (frame in stack) {
            var owner: String? = null
            var count = 0
            for ((id, c) in claims) if (c.owns(frame.className)) { owner = id; count++ }
            if (count == 1) return owner
        }
        return null
    }

    /** [ownerOf] the calling thread's stack. */
    fun ownerOfCurrentThread(): String? = if (owned.isEmpty()) null else ownerOf(Thread.currentThread().stackTrace)

    /**
     * Whose throwable [t] is: [ownerOf] its deepest cause first (where it was thrown; a framework wrapper such as
     * "Unable to start activity" carries the plugin's frames in its cause), then each outer one.
     */
    fun ownerOf(t: Throwable, pinned: Pair<String, Claim>? = null): String? {
        val chain = ArrayList<Throwable>()
        var c: Throwable? = t
        while (c != null && chain.size < 16 && chain.none { it === c }) { chain += c; c = c.cause }
        for (x in chain.asReversed()) ownerOf(x.stackTrace, pinned)?.let { return it }
        return null
    }
}
