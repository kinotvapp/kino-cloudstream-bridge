// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/library/LibraryViewModel.kt: same ABI, no UI. Members the included upstream SyncAPI.kt uses.
package com.lagradost.cloudstream3.ui.library

// Same entries as upstream; stringRes is 0 (no resources in the bridge).
enum class ListSorting(val stringRes: Int) {
    Query(0),
    RatingHigh(0),
    RatingLow(0),
    UpdatedNew(0),
    UpdatedOld(0),
    AlphabeticalA(0),
    AlphabeticalZ(0),
    ReleaseDateNew(0),
    ReleaseDateOld(0),
}

const val LAST_SYNC_API_KEY = "last_sync_api"
