// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/syncproviders/AccountManager.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi

/** The bridge holds no accounts: sync providers exist for type checks only and are never logged in. */
abstract class AccountManager {
    companion object {
        const val NONE_ID: Int = -1
        val aniListApi = AniListApi()
        val simklApi = SimklApi()
        val openSubtitlesApi = OpenSubtitlesApi()

        val syncApis: Array<SyncRepo> = arrayOf(SyncRepo(aniListApi), SyncRepo(simklApi))
        val allApis: Array<AuthRepo> = arrayOf(*syncApis)

        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()
        var cachedAccountIds: MutableMap<String, Int> = mutableMapOf()

        fun accounts(prefix: String): Array<AuthData> = emptyArray()
        fun updateAccounts(prefix: String, array: Array<AuthData>) = Unit
        fun updateAccountsId(prefix: String, id: Int) = Unit

        const val APP_STRING = "cloudstreamapp"
        const val APP_STRING_REPO = "cloudstreamrepo"
        const val APP_STRING_PLAYER = "cloudstreamplayer"
        const val APP_STRING_SEARCH = "cloudstreamsearch"
        const val APP_STRING_RESUME_WATCHING = "cloudstreamcontinuewatching"
        const val APP_STRING_SHARE = "csshare"
    }
}
