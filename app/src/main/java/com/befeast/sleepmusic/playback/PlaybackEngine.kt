package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.ShuffleBag
import com.befeast.sleepmusic.Track
import com.befeast.sleepmusic.online.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
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
    private val sleepRoute: SleepRoute = SleepRoute.NONE,
    private val resolver: StreamResolver = NewPipeResolver(),
    private val resolverDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Epoch time for validating Clock's window end; timers and ramps stay on the monotonic [clock]. */
    private val wallClock: Clock = WallClock,
) : PlaybackControls {

    private class Session(
        val id: Int,
        val timer: SleepTimer,
        val startedAtMs: Long,
        val clock: ClockSessionRequest? = null,
        /** The minutes this night's timer is armed with: Clock's music minutes or the listener's choice. */
        var minutes: Int,
    ) {
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

        var night: OnlineNight? = null
        var onlineCollection: PlayableCollection? = null
        var onlineTracks: Map<String, CatalogTrack> = emptyMap()
        var gain = 1f
        var token = 0L
        var awaitingAudio = false
        var consecutiveErrors = 0
        var nowPlaying: NowPlaying? = null
        var durationMs: Long = -1
        var integratedLufs: Double? = null
    }

    private val queue = PlayQueue(ShuffleBag(random), object : PlaybackStore by store {
        override fun bag() = if (source.online) store.onlineBag() else store.bag()
        override fun saveBag(state: BagState) {
            if (source.online) store.saveOnlineBag(state) else store.saveBag(state)
        }
    })
    private val envelope = VolumeEnvelope()
    private val mutableState = MutableStateFlow(SessionState(
        online = source.online,
        timerMinutes = store.timerMinutes,
        tvOff = store.tvOff,
        sleepRouteStatus = sleepRoute.status(),
    ))
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var session: Session? = null
    private var sessionCounter = 0
    private var pendingStart = false
    /** A Clock request waiting for the collection, consumed by the next [startSession]. */
    private var pendingClock: ClockSessionRequest? = null
    private var tickJob: Job? = null
    private var resolveJob: Job? = null
    private var lastGain = -1f
    private var playableCount = 0

    private val portListener = object : PlayerPort.Listener {
        override fun onPlaying() {
            val s = callbackSession() ?: return
            val night = s.night
            if (night != null) {
                if (!s.awaitingAudio || night.phase !in setOf("starting", "resuming")) return
                s.awaitingAudio = false
                if (!s.paused) envelope.rampTransport(1.0, VolumeEnvelope.RESUME_RAMP_MS)
                val resumed = night.phase == "resuming"
                night.apply(if (resumed) "resume" else "resolve", night.currentTrack, "ok")
                if (!resumed) events.trackStarted(s.id, night.currentTrack)
                publish()
            }
            envelope.markPlaying(clock.nowMs())
            s.consecutiveErrors = 0
        }

        override fun onAdvanced() = handleAdvanced()

        override fun onEnded() {
            val s = callbackSession() ?: return
            if (s.night != null) {
                if (s.awaitingAudio) onlineError(s, FailureReason.UNAVAILABLE.code)
                else if (s.night?.phase == "playing") { s.night?.apply("trackEnd"); resolveOnline(s) }
            } else finish(EndReason.NothingToPlay)
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
        pendingClock = null
        source.onLaunch()
        if (session?.night?.phase == "unavailable") finish(EndReason.Stopped)
        if (commandSession(allowDismiss = false) != null) {
            play(CommandSource.Remote)
            return
        }
        // A new accepted launch must not replay the previous night's Activity dismissal.
        mutableState.update { it.copy(phase = Phase.Idle, closeActivity = false, endReason = null) }
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
        if (eligible.isEmpty() && !source.online) {
            mutableState.update { it.copy(collectionLoaded = true, playableCount = 0) }
            return
        }
        // Always write the silent start, whatever the last session left: the port may stand for a
        // player that does not exist yet and would start at volume 1.
        lastGain = -1f
        setGain(0f)
        val volume = StartVolumeSequence.observe(audio)
        val now = clock.nowMs()
        val clockRequest = pendingClock
        pendingClock = null
        val minutes = clockRequest?.musicMinutes ?: SleepTimer.sanitize(store.timerMinutes)
        val first = queue.begin(eligible)
        if (first == null && !source.online) return
        val s = Session(++sessionCounter, SleepTimer.start(now, minutes), now, clockRequest, minutes)
        session = s
        envelope.reset(now)
        if (source.online) {
            s.onlineCollection = collection
            s.onlineTracks = eligible.mapNotNull { id -> source.onlineTrack(id)?.let { id to it } }.toMap()
            s.night = OnlineNight(eligible.ifEmpty { listOf("") }, next = {
                val available = eligibleIds(collection)
                queue.revalidate(available)
                queue.promote(available)?.id.orEmpty()
            }, initial = first?.id.orEmpty())
            if (first == null) s.night!!.apply("resolve", "", "unavailable")
        } else {
            describe(s, collection, first!!.id)
            port.load(item(collection, first.id), queue.upNext?.let { item(collection, it.id) })
        }
        publish()
        host.sessionStarted()
        events.sessionStarted(s.id, minutes, volume)
        if (s.night != null) resolveOnline(s) else events.trackStarted(s.id, first!!.id)
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
        val s = commandSession() ?: return
        if (s.night?.phase == "unavailable") return
        if (s.paused) resume(s) else pause(s)
    }

    override fun play(source: CommandSource) {
        val s = commandSession() ?: return
        if (s.paused) resume(s)
    }

    override fun pause(source: CommandSource) {
        val s = commandSession() ?: return
        if (!s.paused) pause(s)
    }

    /** A command arriving before the first post-sleep tick cannot revive expired audio. */
    private fun commandSession(allowDismiss: Boolean = true): Session? {
        val s = session ?: return null
        if (s.timer.remainingMs(clock.nowMs()) <= 0) {
            finish(EndReason.TimerEnded, allowSleep = false, allowDismiss = allowDismiss)
            return null
        }
        return s
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
            // Resolution and buffering can outlast the ramp. First audio owns its start.
            if (s.night?.phase in setOf("starting", "resuming")) envelope.setTransport(0.0)
            else envelope.rampTransport(1.0, VolumeEnvelope.RESUME_RAMP_MS)
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
        val s = commandSession() ?: return
        if (s.night?.phase == "unavailable") return
        if (s.night != null) {
            invalidateResolution(s)
            s.night!!.skip()
            s.paused = false; s.playerPaused = false
            envelope.setTransport(0.0)
            resolveOnline(s)
            return
        }
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

    override fun setOnline(enabled: Boolean) {
        if (source.online == enabled) return
        stop(CommandSource.Menu)
        // Reset before the source can synchronously re-emit its downloaded collection.
        mutableState.value = SessionState(online = enabled, timerMinutes = store.timerMinutes,
            tvOff = store.tvOff, sleepRouteStatus = sleepRoute.status())
        source.setOnline(enabled)
        onCollection(source.collection.value)
    }

    override fun onServiceDestroyed() {
        if (session?.night != null) stop(CommandSource.System)
    }

    override fun cancelPendingStart() {
        pendingStart = false
        pendingClock = null
    }

    override fun startClockSession(request: ClockSessionRequest): String? {
        ClockSessions.refusal(request, wallClock.nowMs(), store.lastClockRequestId)?.let { return it }
        if (pendingStart || commandSession(allowDismiss = false) != null) return "a night is already playing"
        store.lastClockRequestId = request.requestId
        source.onLaunch()
        if (session?.night?.phase == "unavailable") finish(EndReason.Stopped)
        mutableState.update { it.copy(phase = Phase.Idle, closeActivity = false, endReason = null) }
        pendingClock = request
        val collection = source.collection.value
        if (collection == null) {
            pendingStart = true
            return null
        }
        startSession(collection)
        return null
    }

    override fun stopClockSession(requestId: String) {
        if (session?.clock?.requestId == requestId) finish(EndReason.Stopped)
    }

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
        val collection = s.onlineCollection ?: source.collection.value ?: PlayableCollection.EMPTY
        val eligible = eligibleIds(collection)
        playableCount = eligible.size
        s.night?.eligibleSelection = eligible.toSet()
        if (queue.revalidate(eligible) && s.night == null) port.setUpNext(queue.upNext?.let { item(collection, it.id) })
        s.nowPlaying = s.nowPlaying?.copy(liked = false)
        publish()
        skip(CommandSource.Menu)
    }

    override fun setTvOff(enabled: Boolean) {
        store.tvOff = enabled
        mutableState.update { it.copy(tvOff = enabled) }
    }

    override fun refreshSleepRoute() {
        mutableState.update { it.copy(sleepRouteStatus = sleepRoute.status()) }
    }

    override fun setTimerMinutes(minutes: Int) {
        val value = SleepTimer.sanitize(minutes)
        // A Clock night plays Clock's music minutes: a pick changes only that night, never the listener's timer.
        val clockNight = session?.clock != null
        if (!clockNight) store.timerMinutes = value
        val s = commandSession()
        if (s == null) {
            if (!clockNight) mutableState.update { if (it.isActive) it else it.copy(timerMinutes = value) }
            return
        }
        s.minutes = value
        s.timer.rearm(clock.nowMs(), value)
        publish()
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
        val s = callbackSession() ?: return
        if (s.night != null) { onlineError(s, code); return }
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
        if (session?.night != null) return
        if (pendingStart && session == null) {
            startSession(collection)
            return
        }
        val eligible = eligibleIds(collection)
        playableCount = eligible.size
        if (session == null) {
            mutableState.update { it.copy(collectionLoaded = true, playableCount = eligible.size, online = source.online,
                onlinePhase = if (source.online && eligible.isEmpty()) "unavailable" else it.onlinePhase) }
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
        setGain(if (s.night != null && s.night?.phase != "playing") 0f else gain * s.gain)
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

    private fun finish(reason: EndReason, allowSleep: Boolean = true, allowDismiss: Boolean = true) {
        val s = session ?: return
        s.night?.apply("timerExpired")
        invalidateResolution(s)
        session = null
        port.setListener(portListener)
        pendingStart = false
        tickJob?.cancel()
        tickJob = null
        setGain(0f)
        port.stopAndClear()
        queue.clear()
        mutableState.value = SessionState(
            online = source.online,
            onlinePhase = s.night?.phase,
            onlineFailure = s.night?.lastFailure?.code,
            phase = Phase.Finished,
            blackScreen = true,
            tvOff = tvOffFor(s),
            sleepRouteStatus = sleepRoute.status(),
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
        // A Clock night never turns the TV off: it closes, and Clock keeps the screen protected.
        if (s.clock != null) {
            mutableState.update { it.copy(closeActivity = allowDismiss) }
            return
        }
        // Only this live transition may sleep. State collection, resume and recreation cannot replay it.
        val ended = mutableState.value
        if (ended.tvOff && reason == EndReason.TimerEnded) {
            // Paused expiry may be the first tick after wake, even inside the grace period.
            val shouldLock = allowSleep && !s.paused && ended.timerEndLateMs <= MAX_SLEEP_LATENESS_MS
            if (!shouldLock) {
                mutableState.update { it.copy(closeActivity = allowDismiss && it.sleepRouteStatus != SleepRouteStatus.NotEnabled) }
                return
            }
            val slept = sleepRoute.sleep()
            if (!slept) events.sleepRouteFailed(s.id)
            mutableState.update {
                it.copy(closeActivity = slept, sleepRouteStatus = if (slept) it.sleepRouteStatus else SleepRouteStatus.NotEnabled)
            }
        }
    }

    // Resolution and Media3 callbacks share a session generation and an attempt token.
    private fun invalidateResolution(s: Session) {
        s.token++
        s.awaitingAudio = false
        resolveJob?.cancel(); resolveJob = null
    }

    private fun callbackSession(): Session? = session?.takeIf {
        it.night == null || it.timer.remainingMs(clock.nowMs()) > 0
    }

    private fun resolveOnline(s: Session) {
        invalidateResolution(s)
        envelope.setTransport(0.0)
        port.setListener(null)
        port.stopAndClear()
        setGain(0f)
        val night = s.night ?: return
        if (night.phase !in setOf("starting", "resuming")) { publish(); return }
        val collection = s.onlineCollection ?: return
        val id = night.currentTrack.takeIf { it in eligibleIds(collection) } ?: run {
            night.eligibleSelection = emptySet()
            s.nowPlaying = null
            night.apply(if (night.phase == "resuming") "resume" else "resolve", night.currentTrack, "unavailable")
            publish(); return
        }
        describe(s, collection, id)
        val track = s.onlineTracks[id] ?: return
        s.gain = track.gain
        val token = s.token
        val resuming = night.phase == "resuming"
        val position = if (resuming) night.resumePositionMs else 0L
        publish()
        resolveJob = scope.launch {
            val result = try {
                runInterruptible(resolverDispatcher) { resolver.resolve(track.videoId, NoSession) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                Failure(NewPipeResolver.reasonFor(error))
            }
            if (session !== s || token != s.token || callbackSession() !== s) return@launch
            if (result is Failure) {
                events.playbackError(s.id, result.reason.code)
                night.apply(if (resuming) "resume" else "resolve", night.currentTrack, result.reason.code)
                resolveOnline(s)
            } else if (result is Resolved) {
                s.awaitingAudio = true
                port.setListener(object : PlayerPort.Listener {
                    private fun deliver(action: () -> Unit) {
                        if (session === s && token == s.token && callbackSession() === s) action()
                    }
                    override fun onPlaying() = deliver { portListener.onPlaying() }
                    override fun onAdvanced() = Unit
                    override fun onEnded() = deliver { portListener.onEnded() }
                    override fun onPausedBySystem() = deliver { portListener.onPausedBySystem() }
                    override fun onError(code: String) = deliver { portListener.onError(code) }
                })
                port.load(QueueItem(id, null, track.track.title, track.track.artist, result, position), null, !s.paused)
                s.playerPaused = s.paused
                publish()
            }
        }
    }

    private fun onlineError(s: Session, code: String) {
        val night = s.night ?: return
        if (night.phase in setOf("unavailable", "finished")) return
        val reason = FailureReason.entries.find { it.code == code } ?: FailureReason.UNAVAILABLE
        events.playbackError(s.id, reason.code)
        when (night.phase) {
            "starting" -> night.apply("resolve", night.currentTrack, reason.code)
            "resuming" -> night.apply("resume", result = reason.code)
            "playing" -> if (reason == FailureReason.NETWORK) night.apply("drop", positionMs = port.positionMs.coerceAtLeast(0))
                else night.failPlaying(reason)
        }
        resolveOnline(s)
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
            online = source.online,
            onlinePhase = s.night?.phase,
            onlineFailure = s.night?.lastFailure?.code,
            phase = phase,
            tvOff = tvOffFor(s),
            sleepRouteStatus = mutableState.value.sleepRouteStatus,
            blackScreen = now - s.startedAtMs >= BLACK_AFTER_MS,
            sessionId = s.id,
            track = s.nowPlaying,
            trackRemainingSec = if (duration > 0) ceilSeconds(duration - port.positionMs) else 0,
            timerRemainingSec = ceilSeconds(s.timer.remainingMs(now)),
            timerMinutes = timerMinutesFor(s),
            endReason = null,
            collectionLoaded = true,
            playableCount = playableCount,
            timerEndAtMs = s.timer.endAtMs,
        )
    }

    private fun tvOffFor(s: Session): Boolean = s.clock == null && store.tvOff

    /** The menu's timer: a Clock night shows its own minutes, a normal night the listener's choice. */
    private fun timerMinutesFor(s: Session): Int = if (s.clock != null) s.minutes else store.timerMinutes

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
        const val BLACK_AFTER_MS = 10_000L
        const val MAX_SLEEP_LATENESS_MS = 30_000L
        const val TICK_MS = 50L
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val VOLUME_EPSILON = 1e-5f
    }
}
