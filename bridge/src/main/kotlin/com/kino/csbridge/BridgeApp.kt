package com.kino.csbridge

import android.app.Application
import android.content.SharedPreferences
import com.kino.csbridge.net.NetworkShim
import com.kino.csbridge.prefs.HostPrefs
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import java.util.concurrent.ConcurrentHashMap

/**
 * Never a CloudStreamApp (see [initForBridge]). Owns where CloudStream code keeps state: DataStore's
 * `rebuild_preference` lives in Kino, attributed per plugin ([HostPrefs]); every other SharedPreferences name is kept
 * in this process's memory. No preference file is ever written in the bridge's data dir.
 */
class BridgeApp : Application() {
    private val hostPrefs by lazy { HostPrefs({ Ops.registry(this).ids() }) }
    private val memoryPrefs = ConcurrentHashMap<String, SharedPreferences>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        if (name == PREFERENCES_NAME) hostPrefs else memory(name.toString())

    private fun memory(key: String): SharedPreferences =
        memoryPrefs.computeIfAbsent(key) { HostPrefs({ emptyList() }, { null }, memoryOnly = true) }

    override fun onCreate() {
        super.onCreate()
        // Before any plugin loads: getKey/setKey and plugins reading CloudStreamApp.context see this app.
        CloudStreamApp.initForBridge(this)
        // Before any plugin loads: every request CloudStream code makes goes through Kino's IHost.
        NetworkShim.install()
    }
}
