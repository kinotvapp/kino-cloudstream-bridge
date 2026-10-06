package com.kino.csbridge

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.profileinstaller.ProfileInstaller
import com.kino.csbridge.net.NetworkShim
import com.kino.csbridge.prefs.HostPrefs
import com.kino.csbridge.prefs.HostStore
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Never a CloudStreamApp (see [initForBridge]). Owns where CloudStream code keeps state: every SharedPreferences name
 * lives in Kino, in the calling plugin's `kino.storage` ([HostStore], [HostPrefs]). DataStore's `rebuild_preference`
 * keys are stored as they are; the default prefs are the namespace "/default"; any other name is its own namespace ([namespaceOf]). No
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

    /**
     * Null for `rebuild_preference`, "/default" for the default prefs, else the name itself, except names Android would
     * refuse: one with a "/" (a path separator) or a NUL. Nothing stops a plugin passing one here, and as they are
     * "/default" would alias the default prefs and a NUL would let one name forge another's keys (HostPrefs keys are
     * NUL + namespace + NUL + key). Those become "/" + the name with "%" and NUL escaped: every such namespace starts with
     * "/", none is "/default", and two names never share one. A null name is "null", as on Android (null.xml).
     */
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

    /** Clears that name's values (for the calling plugin); there is no file to delete. */
    override fun deleteSharedPreferences(name: String?): Boolean = view(name).edit().clear().commit()

    /** There are no prefs files to move, here or in another Context. */
    override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean = false

    override fun onCreate() {
        super.onCreate()
        com.kino.csbridge.ops.Json.initIdPrefixes()
        // Before any plugin loads: getKey/setKey and plugins reading CloudStreamApp.context see this app.
        CloudStreamApp.initForBridge(this)
        // Before any plugin loads: every request CloudStream code makes goes through Kino's IHost.
        NetworkShim.install()
        installBaselineProfile()
    }

    /**
     * androidx.startup's ProfileInstallerInitializer runs in the default process, which never starts here: Kino binds
     * only BridgeService, in `:plugins`. Without this the baseline profile in the APK is never installed on a sideloaded
     * device (ART does not read it by itself). ProfileInstaller skips the work when this APK's profile is already
     * installed. Off the main thread, so the bind does not wait for it.
     */
    private fun installBaselineProfile() {
        if (processName()?.endsWith(":plugins") != true) return
        Thread({ runCatching { ProfileInstaller.writeProfile(this) } }, "bridge-profile").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun processName(): String? =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8) }.getOrNull()

    private companion object {
        /** No plugin-chosen name maps here: [namespaceOf] moves every name with a "/" under "/" + escaped name. */
        const val DEFAULT_NAMESPACE = "/default"
        /** Map key of the rebuild_preference view: a NUL can never be a namespace the views map gets from a name. */
        const val REBUILD_KEY = "\u0000rebuild"
    }
}
