package com.befeast.sleepmusic.playback.testing

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.befeast.sleepmusic.playback.PlaybackControls
import com.befeast.sleepmusic.playback.PlaybackGraph
import com.befeast.sleepmusic.playback.PlaybackProvider
import com.befeast.sleepmusic.playback.SessionCallback
import com.befeast.sleepmusic.playback.SessionPlayer

/** Graph with test doubles; set [TestApp.graph] before building the component under test. */
class TestGraph(
    override val controls: PlaybackControls,
    private val inner: Player? = null,
) : PlaybackGraph {
    var sessionPlayerRequests = 0
    private val wrapper by lazy { SessionPlayer(checkNotNull(inner) { "no inner player in this test" }, controls) }

    override fun sessionPlayer(): Player {
        sessionPlayerRequests++
        return wrapper
    }

    override fun sessionCallback(): MediaSession.Callback = SessionCallback()
}

class TestApp : Application(), PlaybackProvider {
    override val playback: PlaybackGraph get() = graph

    companion object {
        lateinit var graph: PlaybackGraph
    }
}
