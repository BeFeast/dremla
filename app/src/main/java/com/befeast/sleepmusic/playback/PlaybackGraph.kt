package com.befeast.sleepmusic.playback

import android.app.Application
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped playback wiring. The engine lives here, not in the service: the launch decision
 * in `onCreate` needs the session state synchronously, the finished state must outlive the
 * service, and tests build the engine without a service.
 */
interface PlaybackGraph {
    val downloads: DownloadCollectionSource? get() = null
    val controls: PlaybackControls

    /** The player the MediaSession wraps ([SessionPlayer] in production). */
    fun sessionPlayer(): Player

    fun sessionCallback(): MediaSession.Callback
}

/** Implemented by the Application; tests supply a graph with fakes through `@Config(application = …)`. */
interface PlaybackProvider {
    val playback: PlaybackGraph
}

@OptIn(UnstableApi::class)
class AppPlaybackGraph internal constructor(
    private val app: Application,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val collectionSource: CollectionSource? = null,
    private val clock: Clock = ElapsedRealtimeClock,
    private val playerFactory: (() -> Player)? = null,
) : PlaybackGraph {
    private val exoPlayer: Player by lazy {
        playerFactory?.invoke() ?: ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    private val nightLog by lazy {
        (app as? com.befeast.sleepmusic.SleepMusicApp)?.nightLog ?: NightLog(app)
    }
    override val downloads by lazy {
        if (collectionSource != null) null else DownloadCollectionSource(
            app, scope, keepId = { engine.state.value.track?.id }, onError = { nightLog.collectionError() },
        )
    }

    val engine: PlaybackEngine by lazy {
        PlaybackEngine(
            scope = scope,
            clock = clock,
            port = ExoPlayerPort { exoPlayer },
            audio = SystemAudioControl(app.getSystemService(AudioManager::class.java)),
            store = SharedPrefsPlaybackStore(app),
            source = collectionSource ?: checkNotNull(downloads),
            host = ServicePlaybackHost(app),
            events = nightLog,
            sleepRoute = AndroidSleepRoute(app),
        )
    }

    private val wrapper: SessionPlayer by lazy { SessionPlayer(exoPlayer, engine) }

    override val controls: PlaybackControls get() = engine

    override fun sessionPlayer(): Player = wrapper

    override fun sessionCallback(): MediaSession.Callback = SessionCallback()
}
