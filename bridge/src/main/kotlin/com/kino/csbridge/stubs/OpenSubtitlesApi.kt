// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/OpenSubtitlesApi.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.AuthLoginRequirement
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI

/** Identity and the public request headers plugins reuse; no login, no requests. */
class OpenSubtitlesApi : SubtitleAPI() {
    override val name = "OpenSubtitles"
    override val idPrefix = "opensubtitles"
    override val hasInApp = true
    override val inAppLoginRequirement = AuthLoginRequirement(password = true, username = true)
    override val createAccountUrl = "https://www.opensubtitles.com/en/users/sign_up"

    companion object {
        const val API_KEY = "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2"
        const val HOST = "https://api.opensubtitles.com/api/v1"
        const val TAG = "OPENSUBS"
        const val userAgent = "Cloudstream3 v0.2"
        val headers = mapOf("user-agent" to userAgent, "Api-Key" to API_KEY)
    }
}
