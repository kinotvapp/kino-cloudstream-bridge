// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink

data class LinkLoadingResult(
    val links: List<ExtractorLink>,
    val subs: List<SubtitleData>,
    val syncData: HashMap<String, String>
)

/** Same id as upstream for a LoadResponse that did not come from a search result (the only kind the bridge has). */
fun LoadResponse.getId(): Int =
    uniqueUrl.replace(getApiFromNameNull(apiName)?.mainUrl ?: "", "").replace("/", "").hashCode()
