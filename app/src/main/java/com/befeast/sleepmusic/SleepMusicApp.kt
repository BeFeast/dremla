package com.befeast.sleepmusic

import android.app.Application
import com.befeast.sleepmusic.playback.AppPlaybackGraph
import com.befeast.sleepmusic.playback.PlaybackGraph
import com.befeast.sleepmusic.playback.PlaybackProvider

/** Holds the process-scoped playback graph, built on first access; `onCreate` does nothing else. */
class SleepMusicApp : Application(), PlaybackProvider {
    val nightLog by lazy { com.befeast.sleepmusic.playback.NightLog(this) }
    override val playback: PlaybackGraph by lazy { AppPlaybackGraph(this) }
}
