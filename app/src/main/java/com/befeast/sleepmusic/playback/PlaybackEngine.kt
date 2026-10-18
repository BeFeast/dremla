package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.ShuffleBag
import com.befeast.sleepmusic.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * The only place that decides anything about playback: whether a session starts, what plays next,
 * pause, skip, stop, the sleep timer and the volume envelope. Plain Kotlin on the main thread;
 * every dependency is an interface, so it runs on the JVM with fakes and virtual time.
 *
 * A session starts only from [onUiEntry] with a launcher start; nothing restores one from storage.
 * Every other command is ignored unless a session is active, so a finished or stopped session
 * stays finished until the next launcher start, whatever sends the command.
 */
class PlaybackEngine(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val port: PlayerPort,
    private val audio: AudioControl,
    private val store: PlaybackStore,
    private val source: CollectionSource,
    private val host: PlaybackHost,
    private val events: SessionEvents = SessionEvents.NONE,
    random: Random = Random.Default,
) : PlaybackControls {

    private class Session(val id: Int, val timer: SleepTimer) {
        /** The listener's intent: the phase shows Paused. */
        var paused = false

        /** The pause ramp is running; the player pauses when it reaches silence. */
        var pausePending = false

        /** The player itself is paused. */
        var playerPaused = false

        /** A skip is ramping out; the player advances when it reaches silence. */
        var skipPending = false

        /** [PlayerPort.advance] was called and its [PlayerPort.Listener.onAdvanced] has not arrived yet. */
        var advancing = false

        var consecutiveErrors = 0
        var nowPlaying: NowPlaying? = null
        var durationMs: Long = -1
        var integratedLufs: Double? = null
    }

    private val queue = PlayQueue(ShuffleBag(random), store)
    private val envelope = VolumeEnvelope()
    private val mutableState = MutableStateFlow(SessionState(timerMinutes = store.timerMinutes))
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var session: Session? = null
    private var sessionCounter = 0
    private var pendingStart = false
    private var tickJob: Job? = null
    private var lastGain = -1f
    private var playableCount = 0

    private val portListener = object : PlayerPort.Listener {
        override fun onPlaying() {
            val s = session ?: return
            envelope.markPlaying(clock.nowMs())
            s.consecutiveErrors = 0
        }

        override fun onAdvanced() = handleAdvanced()

        override fun onEnded() {
            if (session != null) finish(EndReason.NothingToPlay)
        }

        override fun onPausedBySystem() {
            val s = session ?: return
            if (s.paused && s.playerPaused) return
            s.paused = true
            s.pausePending = false
            s.playerPaused = true
            envelope.setTransport(0.0)
            publish()
        }

        override fun onError(code: String) = handleError(code)
    }

    init {
        port.setListener(portListener)
        scope.launch { source.collection.collect { onCollection(it) } }
    }

    // ---- entry ----

    override fun onUiEntry(verdict: LaunchVerdict) {
        events.uiEntry(verdict)
        if (!verdict.isLauncherStart) return
        if (session != null) {
            play(CommandSource.Remote)
            return
        }
        val collection = source.collection.value
        if (collection == null) {
            pendingStart = true
            return
        }
        startSession(collection)
    }

    private fun startSession(collection: PlayableCollection) {
        pendingStart = false
        val eligible = eligibleIds(collection)
        playableCount = eligible.size
        if (eligible.isEmpty()) {
            mutableState.update { it.copy(collectionLoaded = true, playableCount = 0) }
            return
        }
        // Always write the silent start, whatever the last session left: the port may stand for a
        // player that does not exist yet and would start at volume 1.
        lastGain = -1f
        setGain(0f)
        val volume = StartVolumeSequence.apply(audio, store.startVolumeLevel)
        val now = clock.nowMs()
        val minutes = SleepTimer.sanitize(store.timerMinutes)
        val first = queue.begin(eligible) ?: return
        val s = Session(++sessionCounter, SleepTimer.start(now, minutes))
        session = s
        envelope.reset(now)
        describe(s, collection, first.id)
        port.load(item(collection, first.id), queue.upNext?.let { item(collection, it.id) })
        publish()
        host.sessionStarted()
        events.sessionStarted(s.id, minutes, volume)
        events.trackStarted(s.id, first.id)
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    // ---- commands ----

    override fun togglePause(source: CommandSource) {
        val s = session ?: return
        if (s.paused) resume(s) else pause(s)
    }

    override fun play(source: CommandSource) {
        val s = session ?: return
        if (s.paused) resume(s)
    }

    override fun pause(source: CommandSource) {
        val s = session ?: return
        if (!s.paused) pause(s)
    }

    private fun pause(s: Session) {
        s.paused = true
        if (s.skipPending) {
            // The skip keeps its own ramp; the new track starts paused.
            publish()
            return
        }
        s.pausePending = !s.playerPaused
        envelope.rampTransport(0.0, VolumeEnvelope.PAUSE_RAMP_MS)
        publish()
    }

    private fun resume(s: Session) {
        s.paused = false
        s.pausePending = false
        if (!s.skipPending) {
            if (s.playerPaused) unpausePlayer(s)
            envelope.rampTransport(1.0, VolumeEnvelope.RESUME_RAMP_MS)
        }
        publish()
    }

    /** The paused player plays again; the host is started again first, in case the system stopped it while paused. */
    private fun unpausePlayer(s: Session) {
        s.playerPaused = false
        host.sessionResumed()
        port.play()
    }

    override fun skip(source: CommandSource) {
        val s = session ?: return
        if (s.skipPending) return
        s.skipPending = true
        // A skip resumes a paused session; a pause pressed during the skip ramp applies to the new track.
        s.paused = false
        s.pausePending = false
        if (s.playerPaused || envelope.transportLevel <= 0.0) {
            envelope.setTransport(0.0)
            advanceNow()
        } else {
            envelope.rampTransport(0.0, VolumeEnvelope.SKIP_RAMP_MS)
            publish()
        }
    }

    override fun cancelPendingStart() { pendingStart = false }

    override fun stop(source: CommandSource) {
        pendingStart = false
        if (session != null) finish(EndReason.Stopped)
    }

    override fun toggleLike() {
        val s = session ?: return
        val id = queue.current?.id ?: return
        store.setLiked(id, id !in store.liked())
        s.nowPlaying = s.nowPlaying?.copy(liked = id in store.liked())
        publish()
    }

    override fun neverAgain() {
        val s = session ?: return
        val id = queue.current?.id ?: return
        store.block(id)
        val collection = source.collection.value ?: PlayableCollection.EMPTY
        val eligible = eligibleIds(collection)
        playableCount = eligible.size
        if (queue.revalidate(eligible)) port.setUpNext(queue.upNext?.let { item(collection, it.id) })
        s.nowPlaying = s.nowPlaying?.copy(liked = false)
        publish()
        skip(CommandSource.Menu)
    }

    override fun setTimerMinutes(minutes: Int) {
        val value = SleepTimer.sanitize(minutes)
        store.timerMinutes = value
        val s = session
        if (s == null) {
            mutableState.update { if (it.isActive) it else it.copy(timerMinutes = value) }
            return
        }
        s.timer.rearm(clock.nowMs(), value)
        publish()
    }

    override fun setStartVolumeLevel(level: Int) {
        store.startVolumeLevel = StartVolumeLevel.sanitize(level)
    }

    // ---- player callbacks ----

    private fun handleAdvanced() {
        val s = session ?: return
        val collection = source.collection.value ?: PlayableCollection.EMPTY
        val next = queue.promote(eligibleIds(collection))
        if (next == null) {
            // The player moved to an item the queue does not know: never play unknown things.
            finish(EndReason.NothingToPlay)
            return
        }
        describe(s, collection, next.id)
        port.setUpNext(queue.upNext?.let { item(collection, it.id) })
        s.advancing = false
        if (s.skipPending) {
            s.skipPending = false
            if (s.paused) {
                s.pausePending = false
                s.playerPaused = true
                envelope.setTransport(0.0)
                port.pause()
            } else {
                if (s.playerPaused) unpausePlayer(s)
                // The new track starts under its own edge fade-in.
                envelope.setTransport(1.0)
            }
        }
        events.trackStarted(s.id, next.id)
        publish()
    }

    private fun handleError(code: String) {
        val s = session ?: return
        events.playbackError(s.id, code)
        s.consecutiveErrors++
        if (s.consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            finish(EndReason.PlaybackFailed)
            return
        }
        val collection = source.collection.value ?: PlayableCollection.EMPTY
        val eligible = eligibleIds(collection)
        queue.revalidate(eligible)
        val next = queue.promote(eligible)
        if (next == null) {
            finish(EndReason.NothingToPlay)
            return
        }
        s.skipPending = false
        s.advancing = false
        s.pausePending = false
        // A paused session stays paused: an error can surface while paused (the preloaded
        // lookahead, for example), and it must not start music the listener paused.
        val keepPaused = s.paused
        s.playerPaused = keepPaused
        envelope.setTransport(if (keepPaused) 0.0 else 1.0)
        if (keepPaused) setGain(0f)
        describe(s, collection, next.id)
        port.load(item(collection, next.id), queue.upNext?.let { item(collection, it.id) }, play = !keepPaused)
        events.trackStarted(s.id, next.id)
        publish()
    }

    private fun onCollection(collection: PlayableCollection?) {
        if (collection == null) return
        if (pendingStart && session == null) {
            startSession(collection)
            return
        }
        val eligible = eligibleIds(collection)
        playableCount = eligible.size
        if (session == null) {
            mutableState.update { it.copy(collectionLoaded = true, playableCount = eligible.size) }
            return
        }
        if (queue.revalidate(eligible)) port.setUpNext(queue.upNext?.let { item(collection, it.id) })
        publish()
    }

    // ---- tick ----

    private fun tick() {
        val s = session ?: return
        val now = clock.nowMs()
        if (s.timer.remainingMs(now) <= 0) {
            finish(EndReason.TimerEnded)
            return
        }
        val gain = envelope.gain(now, port.positionMs, trackDurationMs(s), s.timer.sleepGain(now), s.integratedLufs)
        setGain(gain)
        if (envelope.transportSilent) {
            if (s.skipPending) {
                if (!s.advancing) advanceNow()
            } else if (s.pausePending) {
                s.pausePending = false
                s.playerPaused = true
                port.pause()
            }
        }
        publish()
    }

    private fun advanceNow() {
        val collection = source.collection.value ?: PlayableCollection.EMPTY
        if (queue.revalidate(eligibleIds(collection))) {
            port.setUpNext(queue.upNext?.let { item(collection, it.id) })
        }
        if (queue.upNext == null) {
            finish(EndReason.NothingToPlay)
            return
        }
        session?.advancing = true
        port.advance()
    }

    private fun finish(reason: EndReason) {
        val s = session ?: return
        session = null
        pendingStart = false
        tickJob?.cancel()
        tickJob = null
        setGain(0f)
        port.stopAndClear()
        queue.clear()
        mutableState.value = SessionState(
            phase = Phase.Finished,
            sessionId = s.id,
            timerMinutes = store.timerMinutes,
            endReason = reason,
            collectionLoaded = source.collection.value != null,
            playableCount = playableCount,
            timerEndAtMs = s.timer.endAtMs,
            endedAtMs = clock.nowMs(),
        )
        events.sessionEnded(s.id, reason)
        host.sessionEnded()
    }

    // ---- helpers ----

    private fun publish() {
        val s = session ?: return
        val now = clock.nowMs()
        val duration = trackDurationMs(s)
        val phase = when {
            s.paused -> Phase.Paused
            s.timer.inFade(now) -> Phase.Fading
            else -> Phase.Playing
        }
        mutableState.value = SessionState(
            phase = phase,
            sessionId = s.id,
            track = s.nowPlaying,
            trackRemainingSec = if (duration > 0) ceilSeconds(duration - port.positionMs) else 0,
            timerRemainingSec = ceilSeconds(s.timer.remainingMs(now)),
            timerMinutes = store.timerMinutes,
            endReason = null,
            collectionLoaded = true,
            playableCount = playableCount,
            timerEndAtMs = s.timer.endAtMs,
        )
    }

    private fun describe(s: Session, collection: PlayableCollection, id: String) {
        val track: Track? = collection.track(id)
        s.nowPlaying = track?.let { NowPlaying(it.id, it.title, it.artist, it.category, it.id in store.liked()) }
        s.durationMs = track?.durationSec?.takeIf { it > 0 }?.times(1000) ?: -1
        s.integratedLufs = track?.iLufs
    }

    private fun trackDurationMs(s: Session): Long = port.durationMs.takeIf { it > 0 } ?: s.durationMs

    private fun setGain(gain: Float) {
        val exactZero = gain == 0f && lastGain != 0f
        if (exactZero || abs(gain - lastGain) > VOLUME_EPSILON) {
            lastGain = gain
            port.setVolume(gain)
        }
    }

    private fun eligibleIds(collection: PlayableCollection): List<String> =
        ShuffleBag.eligibleIds(collection.ids, collection.selection, store.blocked()).let {
            if (it.selectionFellBack) emptyList() else it.ids
        }

    private fun item(collection: PlayableCollection, id: String): QueueItem {
        val track = checkNotNull(collection.track(id)) { "track $id is not in the collection" }
        return QueueItem(track.id, source.fileFor(track), track.title, track.artist)
    }

    private fun ceilSeconds(ms: Long): Long = (ms.coerceAtLeast(0L) + 999L) / 1000L

    companion object {
        const val TICK_MS = 50L
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val VOLUME_EPSILON = 1e-5f
    }
}
