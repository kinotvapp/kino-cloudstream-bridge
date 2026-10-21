// GPL-3.0 (this fork).
package com.kino.csbridge.desktop

import android.content.Context
import android.content.SharedPreferences
import com.kino.csbridge.Ops
import com.kino.csbridge.prefs.HostPrefs
import com.kino.csbridge.prefs.HostStore
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop's twin of Android's BridgeApp: where CloudStream code keeps state. Every SharedPreferences name lives in
 * Kino, in the calling plugin's `kino.storage` ([HostStore], [HostPrefs]); no preference file is written here.
 * The namespace rules are BridgeApp's, unchanged (see its documentation).
 */
class DesktopApp : Context() {
    internal val prefsStore by lazy { HostStore({ Ops.registry(this).ids() }) }
    private val views = ConcurrentHashMap<String, HostPrefs>()

    internal fun namespaceOf(name: String?): String? = when (name) {
        PREFERENCES_NAME -> null
        "${packageName}_preferences" -> DEFAULT_NAMESPACE
        null -> "null"
        else -> if ('/' in name || '\u0000' in name) "/" + name.replace("%", "%25").replace("\u0000", "%00") else name
    }

    private fun view(name: String?): HostPrefs {
        val ns = namespaceOf(name)
        return views.computeIfAbsent(ns ?: REBUILD_KEY) { HostPrefs(prefsStore, ns) }
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = view(name)

    private companion object {
        const val DEFAULT_NAMESPACE = "/default"
        const val REBUILD_KEY = "\u0000rebuild"
    }
}
