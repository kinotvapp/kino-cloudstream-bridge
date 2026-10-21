// GPL-3.0. JVM replacement for bridge/.../stubs/UIHelper.kt: there is no theme.
package com.lagradost.cloudstream3.utils

import android.content.Context

object UIHelper {
    /** 0, as upstream when the attribute is not in the theme. */
    fun Context.colorFromAttribute(attribute: Int): Int = 0
}
