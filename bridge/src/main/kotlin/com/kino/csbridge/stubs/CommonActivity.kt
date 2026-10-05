// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3

import android.app.Activity
import android.util.Log
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.utils.UiText

object CommonActivity {
    private const val TAG = "CommonActivity"

    /** There is never a foreground Activity in the bridge. */
    val activity: Activity?
        get() = null

    // Toasts are dropped: no UI. Only the length is logged, never the text (it may carry URLs).
    private fun drop(chars: Int?) {
        Log.i(TAG, "toast dropped (headless), ${chars ?: 0} chars")
    }

    fun showToast(@StringRes message: Int, duration: Int? = null) = drop(null)
    fun showToast(message: String?, duration: Int? = null) = drop(message?.length)
    fun showToast(message: UiText?, duration: Int? = null) = drop(null)
    fun showToast(act: Activity?, text: UiText, duration: Int) = drop(null)
    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) = drop(null)
    fun showToast(act: Activity?, message: String?, duration: Int? = null) = drop(message?.length)
}
