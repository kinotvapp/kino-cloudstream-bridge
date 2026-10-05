// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

/** Never shown; plugins reach getView/requireView/getPreferenceScreen through the AndroidX supertypes. */
class SettingsGeneral : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = Unit
}
