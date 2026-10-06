// GPL-3.0 (this fork).
package com.kino.csbridge.plugins

import java.util.concurrent.ConcurrentHashMap

/**
 * Which loaded plugin a stack frame belongs to, by class name: the classes its `.cs3` dex defines, plus its plugin class
 * and that class's nested and synthetic classes (`Name$…`, the only ones JVM tests can see, where fixtures are not dex).
 * Used where no op says whose code is running: storage access from a plugin's own threads, and a throwable on the
 * settings screen. A frame two loaded plugins both claim (the same class name in two .cs3) names neither.
 */
object PluginFrames {
    private class Owned(val classes: Set<String>, val pluginClass: String) {
        fun owns(name: String) = name in classes || name == pluginClass || name.startsWith("$pluginClass$")
    }

    private val owned = ConcurrentHashMap<String, Owned>()

    fun register(pluginId: String, classes: Set<String>, pluginClass: String) { owned[pluginId] = Owned(classes, pluginClass) }

    fun unregister(pluginId: String) { owned.remove(pluginId) }

    fun owns(pluginId: String, className: String): Boolean = owned[pluginId]?.owns(className) == true

    /** The plugin of the innermost frame of [stack] that exactly one loaded plugin claims, or null. */
    fun ownerOf(stack: Array<StackTraceElement>): String? {
        if (owned.isEmpty()) return null
        for (frame in stack) {
            val claims = owned.entries.filter { it.value.owns(frame.className) }
            if (claims.size == 1) return claims[0].key
        }
        return null
    }

    /** [ownerOf] the calling thread's stack. */
    fun ownerOfCurrentThread(): String? = if (owned.isEmpty()) null else ownerOf(Thread.currentThread().stackTrace)

    /** True when [t] or one of its causes went through a frame of [pluginId]. */
    fun inStack(pluginId: String, t: Throwable): Boolean {
        val o = owned[pluginId] ?: return false
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 16) {
            if (c.stackTrace.any { o.owns(it.className) }) return true
            c = c.cause.takeIf { it !== c }
        }
        return false
    }
}
