// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/player/source_priority/QualityDataHelper.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.player.source_priority

import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

/** No user profiles in the bridge: one profile with upstream's default priorities; writes are dropped. */
object QualityDataHelper {
    const val VIDEO_PROFILE_SETTINGS = "video_profile_settings"
    const val AUTO_SKIP_PRIORITY = 10
    private const val DEFAULT_SOURCE_PRIORITY = 1

    enum class QualityProfileType(val stringRes: Int, val unique: Boolean) {
        None(0, false),
        WiFi(0, true),
        Data(0, true),
        Download(0, true)
    }

    data class QualityProfile(
        val name: UiText,
        val id: Int,
        val types: Set<QualityProfileType>
    )

    fun getSourcePriority(profile: Int, name: String?): Int = DEFAULT_SOURCE_PRIORITY
    fun setSourcePriority(profile: Int, name: String, priority: Int) = Unit
    fun getQualityPriority(profile: Int, quality: Qualities): Int = quality.defaultPriority
    fun setQualityPriority(profile: Int, quality: Qualities, priority: Int) = Unit
    fun getProfiles(): List<QualityProfile> = listOf(QualityProfile(txt("1"), 1, setOf(QualityProfileType.None)))
}
