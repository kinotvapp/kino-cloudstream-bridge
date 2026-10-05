// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeViewModel.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.home

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper

class HomeViewModel : ViewModel() {
    companion object {
        /** Watch history is Kino's: nothing to resume in the bridge. */
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? = emptyList()
    }
}
