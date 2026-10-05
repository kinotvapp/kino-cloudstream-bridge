// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/ui/player/IPlayer.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3.ui.player

enum class CSPlayerEvent(val value: Int) {
    Pause(0),
    Play(1),
    SeekForward(2),
    SeekBack(3),
    SkipCurrentChapter(4),
    NextEpisode(5),
    PrevEpisode(6),
    PlayPauseToggle(7),
    ToggleMute(8),
    Restart(9),
    PlayAsAudio(10),
}

enum class PlayerEventSource {
    UI,
    Player,
    Sync,
}

/** Only the members plugins call; there is no player in the bridge. */
interface IPlayer {
    fun getIsPlaying(): Boolean
    fun getPosition(): Long?
    fun seekTo(time: Long, source: PlayerEventSource = PlayerEventSource.UI)
    fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource = PlayerEventSource.UI)
}
