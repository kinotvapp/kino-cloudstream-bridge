// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/actions/VideoClickAction.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.actions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.ActivityOptionsCompat
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import java.util.concurrent.Callable

/** Plugins may register actions; the bridge never shows or runs them (they open other apps). */
object VideoClickActionHolder {
    val allVideoClickActions = atomicListOf<VideoClickAction>()

    fun getActionById(id: Int): VideoClickAction? = null
    fun getByUniqueId(uniqueId: String): VideoClickAction? = allVideoClickActions.firstOrNull { it.uniqueId() == uniqueId }
    fun uniqueIdToId(uniqueId: String?): Int? = null
    fun getPlayers(context: Context? = null): List<VideoClickAction> = emptyList()
}

abstract class VideoClickAction {
    abstract val name: UiText
    open val oneSource: Boolean = false
    open val isPlayer: Boolean = false
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()
    var sourcePlugin: String? = null

    /** No UI thread to run on: returns null without calling [callable]. */
    @Throws
    suspend fun <T> uiThread(callable: Callable<T>): T? = null

    /** No-op: the bridge never starts activities. */
    @Throws
    suspend fun launchResult(intent: Intent?, options: ActivityOptionsCompat? = null) = Unit

    /** No-op: the bridge never starts activities. */
    @Throws
    suspend fun launch(intent: Intent?, bundle: Bundle? = null) = Unit

    fun uniqueId() = "$sourcePlugin:${this::class.qualifiedName}"

    @Throws
    abstract fun shouldShow(context: Context?, video: ResultEpisode?): Boolean

    fun shouldShowSafe(context: Context?, video: ResultEpisode?): Boolean = try {
        shouldShow(context, video)
    } catch (t: Throwable) {
        logError(t)
        false
    }

    @Throws
    abstract suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?)

    /** Never runs the action headless. */
    fun runActionSafe(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?) = ioSafe { }
}
