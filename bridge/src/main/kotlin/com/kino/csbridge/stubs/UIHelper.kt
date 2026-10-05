// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.annotation.AttrRes
import androidx.core.content.withStyledAttributes

object UIHelper {
    /** Same theme lookup as upstream (0 when the attribute is not in the theme). */
    fun Context.colorFromAttribute(@AttrRes attribute: Int): Int {
        var color = 0
        withStyledAttributes(attrs = intArrayOf(attribute)) { color = getColor(0, 0) }
        return color
    }
}
