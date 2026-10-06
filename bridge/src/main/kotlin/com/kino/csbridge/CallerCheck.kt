package com.kino.csbridge

import android.content.Context
import android.content.pm.PackageManager

/** Only Kino may talk to the bridge: its package must own the calling uid and share our signing certificate. */
object CallerCheck {
    const val KINO_PACKAGE = "com.arkiv.player.light"

    fun isKino(context: Context, uid: Int): Boolean = try {
        context.packageManager.getPackagesForUid(uid)?.contains(KINO_PACKAGE) == true && signedLikeUs(context)
    } catch (_: RuntimeException) {
        false // fail closed
    }

    /** [pkg] (an activity's calling package, which the system fills in for a start for a result) is Kino, signed as we are. */
    fun isKinoPackage(context: Context, pkg: String?): Boolean = try {
        pkg == KINO_PACKAGE && signedLikeUs(context)
    } catch (_: RuntimeException) {
        false // fail closed
    }

    private fun signedLikeUs(context: Context) =
        context.packageManager.checkSignatures(context.packageName, KINO_PACKAGE) == PackageManager.SIGNATURE_MATCH
}
