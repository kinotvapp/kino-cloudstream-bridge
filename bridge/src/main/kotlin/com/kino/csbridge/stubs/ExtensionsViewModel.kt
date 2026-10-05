// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsViewModel.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.settings.extensions

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryData(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("url") @SerialName("url") val url: String,
) {
    constructor(name: String, url: String) : this(null, name, url)
}

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"
