// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class Repository(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("manifestVersion") @SerialName("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") @SerialName("pluginLists") val pluginLists: List<String>,
)

@Serializable
data class SitePlugin(
    @JsonProperty("url") @SerialName("url") val url: String,
    @JsonProperty("status") @SerialName("status") val status: Int,
    @JsonProperty("version") @SerialName("version") val version: Int,
    @JsonProperty("apiVersion") @SerialName("apiVersion") val apiVersion: Int,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("authors") @SerialName("authors") val authors: List<String>,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("repositoryUrl") @SerialName("repositoryUrl") val repositoryUrl: String?,
    @JsonProperty("tvTypes") @SerialName("tvTypes") val tvTypes: List<String>?,
    @JsonProperty("language") @SerialName("language") val language: String?,
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("fileSize") @SerialName("fileSize") val fileSize: Long?,
    @JsonProperty("fileHash") @SerialName("fileHash") val fileHash: String?,
)

@Serializable
data class PluginWrapper(
    @JsonProperty("repository") @SerialName("repository") val repository: Repository,
    @JsonProperty("repositoryData") @SerialName("repositoryData") val repositoryData: RepositoryData,
    @JsonProperty("plugin") @SerialName("plugin") val plugin: SitePlugin,
) {
    companion object {
        private val localRepository = Repository("", "", "", 1, emptyList())
        private val localRepositoryData = RepositoryData("", "", "")
        fun getLocalPluginWrapper(plugin: SitePlugin): PluginWrapper =
            PluginWrapper(localRepository, localRepositoryData, plugin)
    }
}

/** Repositories are Kino's business: the bridge has none, and never fetches or adds one. */
object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"
    val PREBUILT_REPOSITORIES: Array<RepositoryData> = emptyArray()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun convertRawGitUrl(url: String): String = url
    suspend fun parseRepoUrl(url: String): String? = null
    suspend fun parseRepository(url: String): Repository? = null
    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? = null
    fun getRepositories(): Array<RepositoryData> = emptyArray()
    suspend fun addRepository(repository: RepositoryData) = Unit
    suspend fun removeRepository(context: Context, repository: RepositoryData) = Unit
}
