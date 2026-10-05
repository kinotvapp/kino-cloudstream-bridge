// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/settings/Globals.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.settings

import android.content.Context

/** The bridge has no screen: it reports the phone layout, never TV or emulator. */
object Globals {
    var beneneCount = 0

    const val PHONE: Int = 0b00001
    const val TV: Int = 0b00010
    const val EMULATOR: Int = 0b00100
    private const val layoutId = PHONE

    fun Context.updateTv() = Unit

    fun isLandscape(): Boolean = false

    fun isLayout(flags: Int): Boolean = (layoutId and flags) != 0
}
