// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt: same ABI, no UI. Members from cs3-symbols.tsv and those the included upstream files use.
package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.DialogInterface
import android.text.Spanned
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.core.text.toSpanned
import androidx.fragment.app.Fragment

object AppContextUtils {
    fun String?.html(): Spanned {
        val text = this ?: return "".toSpanned()
        return try {
            HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } catch (_: Exception) {
            text.toSpanned()
        }
    }

    /** No-op: the bridge never opens a browser or a WebView page for the user. */
    fun Context.openBrowser(url: String, fallbackWebview: Boolean = false, fragment: Fragment? = null) = Unit

    /** No-op: no dialogs in the bridge. */
    fun AlertDialog.setDefaultFocus(buttonFocus: Int = DialogInterface.BUTTON_NEGATIVE) = Unit
}
