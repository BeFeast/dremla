package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.Track
import com.befeast.sleepmusic.playback.Clock
import com.befeast.sleepmusic.playback.LaunchEntry
import com.befeast.sleepmusic.playback.LaunchReason
import com.befeast.sleepmusic.playback.LaunchVerdict
import com.befeast.sleepmusic.playback.PlayableCollection
import com.befeast.sleepmusic.playback.PlaybackEngine
import com.befeast.sleepmusic.playback.PlaybackStore
import com.befeast.sleepmusic.playback.PlayerPort
import com.befeast.sleepmusic.playback.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.random.Random

/**
 * The real engine on a [CheckedTestScope]: the clock is the scheduler's virtual time, so the 50 ms tick
 * loop, the ramps and a whole night run without sleeping. The random source is seeded.
 * [sleepThrough] moves the clock without running ticks, like a box in deep sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineHarness(
    tracks: List<Track> = Tracks.many(6),
    selection: List<String>? = null,
    loaded: Boolean = true,
    val store: PlaybackStore = InMemoryPlaybackStore(),
    val seed: Int = 7,
    startMs: Long = 1_000_000L,
    /** A different port for the engine (the real ExoPlayerPort over a fake player); [port] then stays unused. */
    enginePort: PlayerPort? = null,
    sleepRoute: com.befeast.sleepmusic.playback.SleepRoute = com.befeast.sleepmusic.playback.SleepRoute.NONE,
) {
    val scope = CheckedTestScope()
    private var sleptMs = 0L
    val clock = Clock { scope.testScheduler.currentTime + sleptMs }
    val log = mutableListOf<String>()
    val port = FakePlayerPort(clock, log)
    val audio = FakeAudioControl(log)
    val host = RecordingHost(log)
    val events = RecordingEvents()
    val collection = PlayableCollection(tracks, selection)
    val source = FakeCollectionSource(if (loaded) collection else null)
    val engine: PlaybackEngine

    init {
        scope.testScheduler.advanceTimeBy(startMs)
        scope.testScheduler.runCurrent()
        scope.assertHealthy()
        engine = PlaybackEngine(scope, clock, enginePort ?: port, audio, store, source, host, events, Random(seed), sleepRoute)
        scope.testScheduler.runCurrent()
        scope.assertHealthy()
    }

    val state: SessionState get() = engine.state.value
    val now: Long get() = clock.nowMs()

    fun launcherStart() {
        engine.onUiEntry(LAUNCHER)
        settle()
    }

    fun advance(ms: Long) {
        scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
        scope.assertHealthy()
    }

    fun settle() {
        scope.testScheduler.runCurrent()
        scope.assertHealthy()
    }

    /** The box sleeps for [ms]: elapsed realtime moves on, but no tick and no coroutine runs. */
    fun sleepThrough(ms: Long) {
        sleptMs += ms
    }

    /** Ids of the tracks that became current, in order. */
    val played: List<String> get() = port.started

    companion object {
        val LAUNCHER = LaunchVerdict(LaunchEntry.Create, LaunchReason.LauncherStart)
    }
}
