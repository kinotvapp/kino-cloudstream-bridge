// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragment.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.settings

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/** Never shown. */
class SettingsFragment : Fragment() {
    companion object {
        fun Fragment?.setUpToolbar(title: String) = Unit
        fun Fragment?.setUpToolbar(@StringRes title: Int) = Unit
    }
}
