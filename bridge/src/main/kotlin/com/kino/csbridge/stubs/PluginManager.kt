// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("url") @SerialName("url") val url: String?,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String,
    @JsonProperty("version") @SerialName("version") val version: Int,
) {
    fun toSitePlugin(): SitePlugin = SitePlugin(
        filePath, PROVIDER_STATUS_OK, maxOf(1, version), 1, internalName, internalName, emptyList(),
        File(filePath).name, null, null, null, null, File(filePath).length(), null
    )
}

/**
 * Upstream downloads, loads and unloads .cs3 files here. In the bridge, loading is the bridge's own job; this
 * object keeps the state plugins read (the loaded-plugin maps) and the unload bookkeeping, and never downloads.
 */
object PluginManager {
    const val TAG = "PluginManager"

    var currentlyLoading: String? = null

    /** File path -> plugin, as upstream. */
    val plugins: MutableMap<String, BasePlugin> = LinkedHashMap()

    /** Url -> plugin, as upstream. */
    val urlPlugins: MutableMap<String, BasePlugin> = LinkedHashMap()

    var loadedLocalPlugins = false
        private set
    var loadedOnlinePlugins = false
        private set

    fun getPluginsOnline(): Array<PluginData> = getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()

    fun getPluginsLocal(): Array<PluginData> = getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()

    /** Plugins cannot ask the bridge to load other plugins. */
    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean = false

    fun unloadPlugin(absolutePath: String) {
        val plugin = synchronized(plugins) { plugins[absolutePath] } ?: return
        try {
            plugin.beforeUnload()
        } catch (_: Throwable) {
        }
        APIHolder.apis.filter { it.sourcePlugin == plugin.filename }.forEach { removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == plugin.filename } }
        extractorApis.withLock { extractorApis.removeAll { it.sourcePlugin == plugin.filename } }
        VideoClickActionHolder.allVideoClickActions.withLock {
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == plugin.filename }
        }
        synchronized(plugins) { plugins.remove(absolutePath) }
        synchronized(urlPlugins) { urlPlugins.values.removeIf { it == plugin } }
    }

    private const val RESERVED_CHARS = "|\\?*<\":>+[]/\'"

    /** Same file names as upstream (DownloadFileManagement.sanitizeFilename with spaces removed + hash). */
    fun getPluginSanitizedFileName(name: String): String {
        var tempName = name
        for (c in RESERVED_CHARS) tempName = tempName.replace(c, ' ')
        tempName = tempName.replace(" ", "").replace("  ", " ").trim(' ')
        return tempName + "." + name.hashCode()
    }

    fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl)
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/$ONLINE_PLUGINS_FOLDER/$folderName/$fileName.cs3")
    }
}
