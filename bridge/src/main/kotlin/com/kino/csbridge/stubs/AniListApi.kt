// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/AniListApi.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Identity and the JSON shapes plugins parse AniList replies into; no login, no requests. */
class AniListApi : SyncAPI() {
    override var name = "AniList"
    override val idPrefix = "anilist"
    override val redirectUrlIdentifier = "anilistlogin"
    override var requireLibraryRefresh = true
    override val hasOAuth2 = true
    override var mainUrl = "https://anilist.co"
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Anilist

    @Serializable
    data class Title(
        @JsonProperty("english") @SerialName("english") val english: String?,
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
    )

    @Serializable
    data class CoverImage(
        @JsonProperty("medium") @SerialName("medium") val medium: String?,
        @JsonProperty("large") @SerialName("large") val large: String?,
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
    )

    @Serializable
    data class RecommendedMedia(
        @JsonProperty("id") @SerialName("id") val id: Int?,
        @JsonProperty("title") @SerialName("title") val title: MediaTitle?,
        @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: MediaCoverImage?,
    )

    @Serializable
    data class RecommendationConnection(
        @JsonProperty("edges") @SerialName("edges") val edges: List<RecommendationEdge> = emptyList(),
        @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Recommendation> = emptyList(),
    )

    @Serializable
    data class RecommendationEdge(
        @JsonProperty("node") @SerialName("node") val node: Recommendation,
    )

    @Serializable
    data class Recommendation(
        @JsonProperty("mediaRecommendation") @SerialName("mediaRecommendation") val mediaRecommendation: RecommendedMedia?,
    )

    @Serializable
    data class MediaCoverImage(
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
        @JsonProperty("large") @SerialName("large") val large: String?,
        @JsonProperty("medium") @SerialName("medium") val medium: String?,
        @JsonProperty("color") @SerialName("color") val color: String?,
    )

    @Serializable
    data class SeasonNextAiringEpisode(
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("timeUntilAiring") @SerialName("timeUntilAiring") val timeUntilAiring: Int?,
    )

    @Serializable
    data class MediaTitle(
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
        @JsonProperty("english") @SerialName("english") val english: String?,
        @JsonProperty("native") @SerialName("native") val native: String?,
        @JsonProperty("userPreferred") @SerialName("userPreferred") val userPreferred: String?,
    )

    @Serializable
    data class LikePageInfo(
        @JsonProperty("total") @SerialName("total") val total: Int?,
        @JsonProperty("currentPage") @SerialName("currentPage") val currentPage: Int?,
        @JsonProperty("lastPage") @SerialName("lastPage") val lastPage: Int?,
        @JsonProperty("perPage") @SerialName("perPage") val perPage: Int?,
        @JsonProperty("hasNextPage") @SerialName("hasNextPage") val hasNextPage: Boolean?,
    )
}
