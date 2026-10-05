// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadObjects.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.utils.downloader

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Only the record shapes plugins read; the bridge has no downloads. */
object DownloadObjects {
    interface DownloadCached {
        val id: Int
    }

    @Serializable
    data class DownloadHeaderCached(
        @JsonProperty("apiName") @SerialName("apiName") val apiName: String,
        @JsonProperty("url") @SerialName("url") val url: String,
        @JsonProperty("type") @SerialName("type") val type: TvType,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("poster") @SerialName("poster") val poster: String?,
        @JsonProperty("cacheTime") @SerialName("cacheTime") val cacheTime: Long,
        @JsonProperty("id") @SerialName("id") override val id: Int,
    ) : DownloadCached

    @Serializable
    data class ResumeWatching(
        @JsonProperty("parentId") @SerialName("parentId") val parentId: Int,
        @JsonProperty("episodeId") @SerialName("episodeId") val episodeId: Int?,
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("season") @SerialName("season") val season: Int?,
        @JsonProperty("updateTime") @SerialName("updateTime") val updateTime: Long,
        @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
    )
}
