// GPL-3.0 (this fork).
package com.kino.csbridge

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import java.lang.ref.WeakReference

/**
 * Sets [CloudStreamApp.context] (getKey/setKey, and every plugin that reads it) and the library's `getContext()` to the
 * bridge app, without ever being a CloudStreamApp: that class is final, its context setter is private, and its onCreate
 * installs an uncaught-exception handler that calls exitProcess(1). Upstream files are never edited, so the private
 * setter is called reflectively (R8 keeps it, proguard-rules.pro); if upstream renamed it, the `_context` field is
 * written directly. BridgeAppTest guards both names.
 */
fun CloudStreamApp.Companion.initForBridge(context: Context) {
    val viaSetter = runCatching {
        val m = CloudStreamApp.Companion::class.java.getDeclaredMethod("setContext", Context::class.java)
        m.isAccessible = true
        m.invoke(CloudStreamApp.Companion, context)
    }
    if (viaSetter.isSuccess) return
    val viaField = runCatching {
        val f = CloudStreamApp::class.java.getDeclaredField("_context")
        f.isAccessible = true
        f.set(null, WeakReference(context))
        com.lagradost.api.setContext(context)
    }
    if (viaField.isFailure) {
        // getKey/setKey become no-ops (nothing is stored anywhere), plugins reading the context see null.
        runCatching { Log.e("KinoBridge", "CloudStreamApp context could not be set: ${viaField.exceptionOrNull()?.javaClass?.simpleName}") }
    }
}
