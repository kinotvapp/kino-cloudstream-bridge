// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/SimklApi.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName

/** Identity only; no login, no requests. */
class SimklApi : SyncAPI() {
    override val name = "Simkl"
    override val idPrefix = "simkl"
    override val redirectUrlIdentifier = "simkl"
    override val hasOAuth2 = true
    override val hasPin = true
    override var requireLibraryRefresh = true
    override val mainUrl = "https://api.simkl.com"
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Simkl
}
