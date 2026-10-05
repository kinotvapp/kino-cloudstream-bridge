// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragment.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class VideoWatchState {
    None,
    Watched
}

@Serializable
data class ResultEpisode(
    @SerialName("headerName") val headerName: String,
    @SerialName("name") val name: String?,
    @SerialName("poster") val poster: String?,
    @SerialName("episode") val episode: Int,
    @SerialName("seasonIndex") val seasonIndex: Int?,
    @SerialName("season") val season: Int?,
    @SerialName("data") val data: String,
    @SerialName("apiName") val apiName: String,
    @SerialName("id") val id: Int,
    @SerialName("index") val index: Int,
    @SerialName("position") val position: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("score") val score: Score?,
    @SerialName("description") val description: String?,
    @SerialName("isFiller") val isFiller: Boolean?,
    @SerialName("tvType") val tvType: TvType,
    @SerialName("parentId") val parentId: Int,
    @SerialName("videoWatchState") val videoWatchState: VideoWatchState,
    @SerialName("totalEpisodeIndex") val totalEpisodeIndex: Int? = null,
    @SerialName("airDate") val airDate: Long? = null,
    @SerialName("runTime") val runTime: Int? = null,
    @SerialName("seasonData") val seasonData: SeasonData? = null,
)
