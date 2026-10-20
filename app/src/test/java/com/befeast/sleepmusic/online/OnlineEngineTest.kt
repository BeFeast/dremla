package com.befeast.sleepmusic.online

import com.befeast.sleepmusic.Track
import com.befeast.sleepmusic.playback.*
import com.befeast.sleepmusic.playback.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/** Controls the real blocking resolver boundary independently of the engine's main dispatcher. */
internal class ResolutionDispatcher : CoroutineDispatcher() {
    val pending = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
    fun runOne() { check(pending.isNotEmpty()); pending.removeFirst().run() }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class OnlineHarness(ids: List<String> = listOf("yt-fxAmbient01", "yt-fxRain00001", "yt-fxFail00001"), gain: Double = -6.0,
    resolverOverride: StreamResolver? = null, dispatcherOverride: CoroutineDispatcher? = null,
    random: Random = object : Random() { override fun nextBits(bitCount: Int) = 0 },
    val store: InMemoryPlaybackStore = InMemoryPlaybackStore(),
    sourceOverride: CollectionSource? = null, sleepRoute: SleepRoute = SleepRoute.NONE) {
    val scope = CheckedTestScope()
    var now = 0L
    val clock = Clock { now }
    val port = FakePlayerPort(clock, mutableListOf())
    val audio = FakeAudioControl(mutableListOf())
    val events = RecordingEvents()
    val io = ResolutionDispatcher()
    var result: Resolution = Resolved("https://media.example/audio", "audio/mp4", headers = mapOf("X-Fixture" to "memory-only"))
    val requests = mutableListOf<String>()
    val tracks = ids.map { CatalogTrack(Track(it, it, "Fixture", "ambient", 600, "", 0, "", "", "", null, emptyList()), it.removePrefix("yt-"), gain) }
    val source = sourceOverride ?: object : CollectionSource {
        private var enabled = true
        override val online get() = enabled
        override val collection = MutableStateFlow<PlayableCollection?>(PlayableCollection(tracks.map { it.track }, ids))
        override fun onlineTrack(id: String) = tracks.find { it.track.id == id }
        override fun fileFor(track: Track): File = error("Online must never read offline files")
        override fun setOnline(enabled: Boolean) { this.enabled = enabled }
    }
    val engine = PlaybackEngine(scope, clock, port, audio, store, source, RecordingHost(mutableListOf()), events,
        random = random, sleepRoute = sleepRoute,
        resolver = resolverOverride ?: StreamResolver { id, session -> assertSame(NoSession, session); requests += id; result },
        resolverDispatcher = dispatcherOverride ?: io)
    init { settle(); engine.onUiEntry(EngineHarness.LAUNCHER); settle() }
    val state get() = engine.state.value
    fun settle() { scope.testScheduler.runCurrent(); scope.assertHealthy() }
    fun resolve(code: String = "ok") {
        if (code != "ok") result = Failure(FailureReason.entries.find { it.code == code } ?: FailureReason.UNAVAILABLE)
        else result = Resolved("https://media.example/audio", "audio/mp4", headers = mapOf("X-Fixture" to "memory-only"))
        io.runOne(); settle()
    }
    fun expire() { now = state.timerEndAtMs; scope.testScheduler.advanceTimeBy(50); settle() }
    fun close() { engine.stop(CommandSource.System); scope.cancel() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineEngineTest {
    @Test fun runningResolverIsOffMainInterruptedAndItsLateResultIsDiscarded() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val mainThread = Thread.currentThread()
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val resolver = StreamResolver { _, _ ->
            assertNotSame(mainThread, Thread.currentThread())
            entered.countDown()
            try { java.util.concurrent.CountDownLatch(1).await() }
            catch (_: InterruptedException) { interrupted.countDown() }
            returned.countDown()
            Resolved("https://media.example/late", "audio/mp4")
        }
        val h = OnlineHarness(resolverOverride = resolver, dispatcherOverride = dispatcher)
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            h.engine.stop(CommandSource.System)
            assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(returned.await(5, java.util.concurrent.TimeUnit.SECONDS))
            h.settle()
            assertEquals(Phase.Finished, h.state.phase)
            assertTrue(h.port.started.isEmpty())
        } finally { h.close(); dispatcher.close() }
    }

    @Test fun noCatalogStillStartsSilentNightAndExpiresNormally() {
        val h = OnlineHarness(emptyList())
        try {
            assertEquals("unavailable", h.state.onlinePhase)
            assertTrue(h.state.isActive); assertFalse(h.port.playing)
            assertTrue(h.io.pending.isEmpty()); assertEquals(0, h.state.playableCount)
            h.expire(); assertEquals(EndReason.TimerEnded, h.state.endReason)
        } finally { h.close() }
    }

    @Test fun everyPlaybackVectorThroughResolverAndRealEngine() {
        val files = File(System.getProperty("fixtures")!!).listFiles()!!.filter { it.name.startsWith("playback-") }
        assertEquals(6, files.size)
        for (file in files) {
            val vector = JSONObject(file.readText())
            val ids = vector.getJSONArray("selection").let { a -> (0 until a.length()).map(a::getString) }
            val h = OnlineHarness(ids)
            try {
                val end = h.state.timerEndAtMs
                val steps = vector.getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    when (step.getString("event")) {
                        "resolve" -> {
                            assertEquals(step.getString("track"), h.state.track?.id)
                            h.resolve(step.getString("result"))
                        }
                        "resume" -> h.resolve(step.getString("result"))
                        "drop" -> { h.port.fail("network"); h.settle() }
                        "trackEnd" -> { h.port.completeCurrentTrack(); h.settle() }
                        "timerExpired" -> h.expire()
                        "networkRecovered" -> h.settle() // no network observer may grant autoplay
                    }
                    val expected = step.getJSONObject("expect")
                    val label = "${file.name} step $i"
                    assertEquals(label, expected.getString("phase"), h.state.onlinePhase)
                    assertEquals(label, expected.getBoolean("sound"), h.port.playing)
                    assertEquals(label, expected.getString("timer"), if (h.state.phase == Phase.Finished) "expired" else "running")
                    assertEquals(label, end, h.state.timerEndAtMs)
                }
            } finally { h.close() }
        }
    }

    @Test fun playerStartFailuresRetryBeforeAudioAndResumeSeeks() {
        val h = OnlineHarness()
        try {
            h.port.reportPlayingOnPlay = false
            h.resolve()
            assertEquals("starting", h.state.onlinePhase)
            h.port.fail("network"); h.settle()
            h.resolve(); h.port.becomeAudible()
            assertEquals("playing", h.state.onlinePhase)
            h.now = 42_000
            h.port.fail("network"); h.settle()
            assertEquals("resuming", h.state.onlinePhase)
            h.resolve()
            assertEquals(42_000L, h.port.current!!.startPositionMs)
            h.port.becomeAudible()
            h.port.fail("network"); h.settle()
            assertEquals("starting", h.state.onlinePhase)
            assertEquals("yt-fxRain00001", h.state.track?.id)
        } finally { h.close() }
    }

    @Test fun cancellationAndOldCallbacksCannotReviveSkipStopExpiryModeOrDestroy() {
        for (action in listOf<(OnlineHarness) -> Unit>(
            { it.engine.skip(CommandSource.Remote) }, { it.engine.stop(CommandSource.System) },
            { it.expire() }, { it.engine.setOnline(false) }, { it.engine.onServiceDestroyed() })) {
            val h = OnlineHarness()
            try {
                h.resolve()
                val old = h.port.listener!!
                h.port.fail("network"); h.settle() // pending resume
                action(h); h.settle()
                val before = h.state
                h.io.runOne(); h.settle() // only the cancelled resume, not the new skip attempt
                old.onPlaying(); old.onError("network"); old.onEnded()
                assertEquals(before.onlinePhase, h.state.onlinePhase)
                assertEquals(before.sessionId, h.state.sessionId)
                assertFalse(h.events.errors.any { it.contains("https") })
            } finally { h.close() }
        }
    }

    @Test fun gainOnlyAttenuatesAndHouseholdVolumeIsUntouched() {
        for ((db, expected) in listOf(0.0 to 1f, -6.0 to 0.5011872f, -20.0 to 0.1f, -40.0 to 0.01f)) {
            val h = OnlineHarness(gain = db)
            try {
                assertEquals(expected, h.tracks.first().gain, 0.000001f)
                h.audio.systemChange(0, muted = true)
                h.resolve(); h.now = 60_000
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertEquals(expected, h.port.volume, 0.000001f)
                assertTrue(h.port.volumes.all { it.second <= 1f })
                assertEquals(0, h.audio.current); assertTrue(h.audio.muted)
            } finally { h.close() }
        }
    }

    @Test fun repeatedBrokenTrackAcrossBagBoundaryDoesNotHideWorkingTrack() {
        for (persisted in listOf(false, true)) {
            val store = InMemoryPlaybackStore()
            if (persisted) store.saveOnlineBag(BagState(setOf("yt-fxAmbient01", "yt-fxRain00001", "yt-fxFail00001"), "yt-fxFail00001"))
            // First cycle A,B,C, then B,A,C: B fails twice before A is retried.
            val draws = ArrayDeque(listOf(0, 0, 0, 1, 0, 0))
            val random = object : Random() {
                override fun nextBits(bitCount: Int) = 0
                override fun nextInt(until: Int) = (draws.removeFirstOrNull() ?: 0).coerceAtMost(until - 1)
            }
            val h = OnlineHarness(random = random, store = store)
            try {
                val working = h.state.track!!.id
                h.resolve(); h.port.completeCurrentTrack(); h.settle()
                val failures = mutableListOf<String>()
                repeat(6) {
                    if (h.state.track!!.id != working) {
                        failures += h.state.track!!.id
                        h.resolve("unavailable")
                        assertEquals("starting", h.state.onlinePhase)
                    }
                }
                assertEquals(working, h.state.track!!.id)
                assertEquals(listOf("yt-fxRain00001", "yt-fxFail00001", "yt-fxRain00001"), failures)
                h.resolve(); assertEquals("playing", h.state.onlinePhase)
            } finally { h.close() }
        }
    }

    @Test fun unavailableLikesAndBlocksTheDisplayedTrack() {
        val h = OnlineHarness()
        try {
            repeat(3) { h.resolve("unavailable") }
            assertEquals("unavailable", h.state.onlinePhase)
            val shown = h.state.track!!.id
            h.engine.toggleLike(); assertEquals(setOf(shown), h.store.liked())
            h.engine.neverAgain(); assertEquals(setOf(shown), h.store.blocked())
            assertTrue(h.io.pending.isEmpty())
        } finally { h.close() }
    }

    private fun transportLevel(h: OnlineHarness): Double =
        (PlaybackEngine::class.java.getDeclaredField("envelope").apply { isAccessible = true }
            .get(h.engine) as VolumeEnvelope).transportLevel

    @Test fun pauseResumeWhileAwaitingOnlineAudioKeepsTransportSilent() {
        for (skip in listOf(false, true)) {
            val h = OnlineHarness(gain = 0.0)
            try {
                h.resolve(); h.now = 60_000
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertEquals(1f, h.port.volume, 0.00001f)
                if (skip) h.engine.skip(CommandSource.Remote) else h.port.fail("network")
                h.settle()
                assertEquals(if (skip) "starting" else "resuming", h.state.onlinePhase)
                h.engine.togglePause(CommandSource.Remote)
                h.now += 50; h.scope.testScheduler.advanceTimeBy(50); h.settle()
                h.engine.togglePause(CommandSource.Remote)
                // The resolver remains pending for longer than a full transport ramp.
                h.now += VolumeEnvelope.RESUME_RAMP_MS + 50
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertEquals(0f, h.port.volume, 0f)
                assertEquals(0.0, transportLevel(h), 0.0)
                h.port.reportPlayingOnPlay = false
                h.resolve()
                h.now += VolumeEnvelope.RESUME_RAMP_MS + 50
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertEquals(0.0, transportLevel(h), 0.0)
                h.port.becomeAudible()
                h.now += 50; h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertTrue("first audio tick must start near silence", h.port.volume < 0.01f)
                assertEquals(0.025, transportLevel(h), 0.000001)
                val firstGain = h.port.volume
                h.now += VolumeEnvelope.RESUME_RAMP_MS
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertEquals(1.0, transportLevel(h), 0.0)
                assertTrue(h.port.volume > firstGain)
            } finally { h.close() }
        }
    }

    @Test fun resumeAndSkipStartSilentAndRampAtFirstAudio() {
        for (skip in listOf(true, false)) {
            val h = OnlineHarness(gain = 0.0)
            try {
                h.resolve(); h.now = 60_000
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertEquals(1f, h.port.volume, 0.00001f)
                if (skip) h.engine.skip(CommandSource.Remote) else h.port.fail("network")
                h.settle(); assertEquals(0f, h.port.volume, 0f)
                h.resolve()
                // Check transport directly so the skip track-edge fade cannot mask a full-gain jump.
                h.now += 50
                h.port.duration = 600_000
                h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertTrue("first tick must ramp", h.port.volume < 0.01f)
                assertEquals(0.025, transportLevel(h), 0.000001)
                h.now += 2_000; h.scope.testScheduler.advanceTimeBy(50); h.settle()
                assertTrue(h.port.volume > 0f)
            } finally { h.close() }
        }
    }

    @Test fun endedBeforeAudioFailsStartingAndResumingAttempts() {
        for (resume in listOf(false, true)) {
            val h = OnlineHarness()
            try {
                if (resume) { h.resolve(); h.port.fail("network"); h.settle() }
                h.port.reportPlayingOnPlay = false; h.resolve()
                val old = h.state.track!!.id
                h.port.completeCurrentTrack(); h.settle()
                assertEquals("starting", h.state.onlinePhase)
                assertNotEquals(old, h.state.track!!.id)
                assertEquals(1, h.io.pending.size)
            } finally { h.close() }
        }
    }

    @Test fun staleErrorAfterSkipChangesNoResolveBudgetAllowanceOrPortState() {
        val h = OnlineHarness()
        try {
            h.resolve(); val stale = h.port.listener!!
            h.engine.skip(CommandSource.Remote); h.settle(); h.resolve()
            val before = h.state
            val item = h.port.current
            val volumes = h.port.volumes.toList()
            val starts = h.port.started.toList()
            val errors = h.events.errors.toList()
            repeat(4) { stale.onError("network") }; h.settle()
            assertEquals(before, h.state); assertSame(item, h.port.current)
            assertEquals(volumes, h.port.volumes); assertEquals(starts, h.port.started)
            assertEquals(errors, h.events.errors); assertTrue(h.io.pending.isEmpty())
            // All three genuine resume allowances still have their shared budget available.
            repeat(3) {
                h.port.fail("network"); h.settle(); assertEquals("resuming", h.state.onlinePhase)
                h.resolve(); h.port.completeCurrentTrack(); h.settle(); h.resolve()
            }
            h.port.fail("network"); h.settle(); assertEquals("starting", h.state.onlinePhase)
        } finally { h.close() }
    }

    @Test fun expiredOnlineCallbackWaitsForTickAndSleepRoute() {
        var sleeps = 0
        val h = OnlineHarness(sleepRoute = object : SleepRoute {
            override fun status() = SleepRouteStatus.DeviceAdmin
            override fun sleep(): Boolean { sleeps++; return true }
        })
        try {
            h.resolve(); h.now = h.state.timerEndAtMs + 1
            h.port.fail("network")
            assertTrue(h.state.isActive); assertTrue(h.io.pending.isEmpty())
            h.scope.testScheduler.advanceTimeBy(50); h.settle()
            assertEquals(1, sleeps); assertTrue(h.state.closeActivity)
        } finally { h.close() }
    }

    @Test fun offlineErrorJustAfterDeadlineStillLetsTickSleep() {
        var sleeps = 0
        val h = EngineHarness(sleepRoute = object : SleepRoute {
            override fun status() = SleepRouteStatus.DeviceAdmin
            override fun sleep(): Boolean { sleeps++; return true }
        })
        try {
            h.launcherStart(); h.sleepThrough(h.state.timerEndAtMs - h.now + 1)
            h.port.fail(); assertTrue(h.state.isActive)
            h.advance(50); assertEquals(1, sleeps)
            assertEquals(EndReason.TimerEnded, h.state.endReason)
        } finally { h.engine.stop(CommandSource.System); h.scope.cancel() }
    }

    @Test fun blockingLastTrackLeavesSilentTimerAndDoesNotResolveItAgain() {
        val h = OnlineHarness(listOf("yt-fxAmbient01"))
        try {
            h.resolve(); val end = h.state.timerEndAtMs
            h.engine.neverAgain(); h.settle()
            assertEquals("unavailable", h.state.onlinePhase)
            assertFalse(h.port.playing)
            assertTrue(h.io.pending.isEmpty())
            assertEquals(end, h.state.timerEndAtMs)
            h.expire(); assertEquals(EndReason.TimerEnded, h.state.endReason)
        } finally { h.close() }
    }

    @Test fun blocksAndShuffleNeverReturnExcludedTrack() {
        val h = OnlineHarness()
        try {
            h.resolve(); val blocked = h.state.track!!.id
            h.engine.neverAgain(); h.settle()
            repeat(8) { h.resolve(); assertNotEquals(blocked, h.state.track?.id); h.port.completeCurrentTrack(); h.settle() }
            assertTrue(blocked in h.store.blocked())
        } finally { h.close() }
    }
}
