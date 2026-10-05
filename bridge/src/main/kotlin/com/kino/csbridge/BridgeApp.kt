package com.kino.csbridge

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.kino.csbridge.net.NetworkShim
import com.kino.csbridge.prefs.HostPrefs
import com.kino.csbridge.prefs.HostStore
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import java.util.concurrent.ConcurrentHashMap

/**
 * Never a CloudStreamApp (see [initForBridge]). Owns where CloudStream code keeps state: every SharedPreferences name
 * lives in Kino, in the calling plugin's `kino.storage` ([HostStore], [HostPrefs]). DataStore's `rebuild_preference`
 * keys are stored as they are; the default prefs are the name "/default" (no real name has a "/"); any other name is its own namespace. No
 * preference file is ever written in the bridge's data dir.
 *
 * Only this Context is routed. A ContextImpl reached another way (`baseContext`, `createConfigurationContext`,
 * `createDeviceProtectedStorageContext`, `createPackageContext`, the service's own context) would still write a real
 * prefs file; no upstream code path plugins reach does that (they get this app from `load(context)` and
 * CloudStreamApp.context), so only a plugin going out of its way would.
 */
class BridgeApp : Application() {
    internal val prefsStore by lazy { HostStore({ Ops.registry(this).ids() }) }
    private val views = ConcurrentHashMap<String, HostPrefs>()

    /** Null for `rebuild_preference`, "/default" for the default prefs, else the name itself. */
    private fun namespaceOf(name: String?): String? = when (name) {
        PREFERENCES_NAME -> null
        "${packageName}_preferences" -> DEFAULT_NAMESPACE
        else -> name.toString()
    }

    private fun view(name: String?): HostPrefs {
        val ns = namespaceOf(name)
        return views.computeIfAbsent(ns ?: REBUILD_KEY) { HostPrefs(prefsStore, ns) }
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = view(name)

    /** Clears that name's values (for the calling plugin); there is no file to delete. */
    override fun deleteSharedPreferences(name: String?): Boolean = view(name).edit().clear().commit()

    /** There are no prefs files to move, here or in another Context. */
    override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean = false

    override fun onCreate() {
        super.onCreate()
        // Before any plugin loads: getKey/setKey and plugins reading CloudStreamApp.context see this app.
        CloudStreamApp.initForBridge(this)
        // Before any plugin loads: every request CloudStream code makes goes through Kino's IHost.
        NetworkShim.install()
    }

    private companion object {
        /** Android refuses "/" in a prefs name, so no plugin-chosen name can ever be this one. */
        const val DEFAULT_NAMESPACE = "/default"
        /** Map key of the rebuild_preference view: a NUL can never be a namespace the views map gets from a name. */
        const val REBUILD_KEY = "\u0000rebuild"
    }
}
