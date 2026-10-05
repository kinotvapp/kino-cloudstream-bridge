// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.player

import androidx.fragment.app.Fragment

/** Never shown. Its player is idle: not playing, no position, ignores events. */
class GeneratorPlayer : Fragment() {
    val player: IPlayer = object : IPlayer {
        override fun getIsPlaying(): Boolean = false
        override fun getPosition(): Long? = null
        override fun seekTo(time: Long, source: PlayerEventSource) = Unit
        override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) = Unit
    }
}
