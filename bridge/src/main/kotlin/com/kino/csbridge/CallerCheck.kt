package com.kino.csbridge

import android.content.Context
import android.content.pm.PackageManager

/** Only Kino may talk to the bridge: its package must own the calling uid and share our signing certificate. */
object CallerCheck {
    const val KINO_PACKAGE = "com.arkiv.player.light"

    fun isKino(context: Context, uid: Int): Boolean = try {
        val pm = context.packageManager
        pm.getPackagesForUid(uid)?.contains(KINO_PACKAGE) == true &&
            pm.checkSignatures(context.packageName, KINO_PACKAGE) == PackageManager.SIGNATURE_MATCH
    } catch (_: RuntimeException) {
        false // fail closed
    }
}
