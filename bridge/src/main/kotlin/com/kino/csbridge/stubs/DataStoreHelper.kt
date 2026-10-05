// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/utils/DataStoreHelper.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.utils

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * Upstream keeps the user's library, watch progress and profiles here. That state is Kino's, not the bridge's:
 * one default profile, nothing bookmarked, nothing watched, and writes are dropped.
 */
object DataStoreHelper {
    @Serializable
    data class Account(
        @JsonProperty("keyIndex") @SerialName("keyIndex") val keyIndex: Int,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("customImage") @SerialName("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") @SerialName("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") @SerialName("lockPin") val lockPin: String? = null,
    )

    const val TAG = "data_store_helper"
    var accounts: Array<Account>
        get() = emptyArray()
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    var selectedKeyIndex: Int
        get() = 0
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    val currentAccount: String get() = selectedKeyIndex.toString()

    fun getAccounts(context: Context): List<Account> = emptyList()

    @Serializable
    data class PosDur(
        @JsonProperty("position") @SerialName("position") val position: Long,
        @JsonProperty("duration") @SerialName("duration") val duration: Long,
    )

    fun Int.toYear(): Date = GregorianCalendar.getInstance().also { it.set(Calendar.YEAR, this) }.time

    @Serializable
    abstract class LibrarySearchResponse(
        @Transient override var id: Int? = null,
        @Transient open val latestUpdatedTime: Long = 0L,
        @Transient override val name: String = "",
        @Transient override val url: String = "",
        @Transient override val apiName: String = "",
        @Transient override var type: TvType? = null,
        @Transient override var posterUrl: String? = null,
        @Transient open val year: Int? = null,
        @Transient open val syncData: Map<String, String>? = null,
        @Transient override var quality: SearchQuality? = null,
        @Transient override var posterHeaders: Map<String, String>? = null,
        @Transient open val plot: String? = null,
        @Transient override var score: Score? = null,
        @Transient open val tags: List<String>? = null,
    ) : SearchResponse

    @Serializable
    data class ResumeWatchingResult(
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") @SerialName("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("parentId") @SerialName("parentId") val parentId: Int?,
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("season") @SerialName("season") val season: Int?,
        @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
    ) : SearchResponse

    fun deleteAllResumeStateIds() = Unit
    fun getAllResumeStateIds(): List<Int>? = null
    fun getLastWatched(id: Int?): DownloadObjects.ResumeWatching? = null
    fun getAllBookmarkedData(): List<LibrarySearchResponse> = emptyList()
    fun getAllSubscriptions(): List<LibrarySearchResponse> = emptyList()
    fun getAllFavorites(): List<LibrarySearchResponse> = emptyList()
    fun getViewPos(id: Int?): PosDur? = null
    fun getVideoWatchState(id: Int?): VideoWatchState? = null
    fun getDub(id: Int): DubStatus? = null
    fun getResultWatchState(id: Int): WatchType = WatchType.NONE
    fun setResultSeason(id: Int, value: Int?) = Unit
}
