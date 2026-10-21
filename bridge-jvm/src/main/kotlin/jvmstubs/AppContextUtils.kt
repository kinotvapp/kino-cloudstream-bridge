// GPL-3.0. JVM replacement for bridge/.../stubs/AppContextUtils.kt (same FQCN and members; the Android one names AlertDialog
// and the AndroidX HTML parser). The desktop bridge never opens a browser or a dialog.
package com.lagradost.cloudstream3.utils

import android.content.Context
import android.text.Spanned
import androidx.appcompat.app.AlertDialog
import androidx.core.text.toSpanned
import androidx.fragment.app.Fragment

object AppContextUtils {
    fun String?.html(): Spanned = (this ?: "").toSpanned()

    /** No-op: the bridge never opens a browser or a WebView page for the user. */
    fun Context.openBrowser(url: String, fallbackWebview: Boolean = false, fragment: Fragment? = null) = Unit

    /** No-op: no dialogs in the bridge. */
    fun AlertDialog.setDefaultFocus(buttonFocus: Int = android.content.DialogInterface.BUTTON_NEGATIVE) = Unit
}
