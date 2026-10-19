package com.befeast.sleepmusic.playback

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import com.befeast.sleepmusic.playback.testing.CheckedTestScope
import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeCollectionSource
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.Tracks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class ProductionAdaptersTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    @Config(shadows = [RecordingAudioManager::class])
    fun systemAudioOnlyReadsAndNeverChangesAnyStream() {
        val manager = app.getSystemService(AudioManager::class.java)
        val shadow = shadowOf(manager) as RecordingAudioManager
        ShadowAudioManager.ALL_STREAMS.forEach { manager.setStreamVolume(it, 2, 0) }
        for ((index, muted) in listOf(1 to false, 0 to false, 3 to true)) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            shadow.setIsStreamMute(AudioManager.STREAM_MUSIC, muted)
            val before = ShadowAudioManager.ALL_STREAMS.associateWith { manager.getStreamVolume(it) to manager.isStreamMute(it) }
            shadow.calls.clear()
            val result = StartVolumeSequence.observe(SystemAudioControl(manager))
            assertEquals(emptyList<String>(), shadow.calls)
            assertEquals(before, ShadowAudioManager.ALL_STREAMS.associateWith { manager.getStreamVolume(it) to manager.isStreamMute(it) })
            assertEquals(muted, result.wasMuted)
            assertEquals(muted, result.mutedAfter)
            assertEquals(result.before, result.after)
        }
    }

    @Test
    fun serviceHostStartsResumesAndStopsPlaybackService() {
        val host = ServicePlaybackHost(app)
        val component = ComponentName(app, PlaybackService::class.java)
        host.sessionStarted()
        assertEquals(component, shadowOf(app).nextStartedService.component)
        host.sessionResumed()
        assertEquals(component, shadowOf(app).nextStartedService.component)
        host.sessionEnded()
        assertEquals(component, shadowOf(app).nextStoppedService.component)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun appGraphGuardsNextAndTaskRemovalEndsTheActiveEngine() {
        val scope = CheckedTestScope()
        val inner = FakeMediaPlayer()
        val graph = AppPlaybackGraph(app, scope, FakeCollectionSource(PlayableCollection(Tracks.many(4), null)), Clock { scope.testScheduler.currentTime }) { inner }
        TestApp.graph = graph
        scope.testScheduler.runCurrent()
        graph.controls.onUiEntry(EngineHarness.LAUNCHER)
        val wrapper = graph.sessionPlayer()
        assertTrue(wrapper is SessionPlayer)
        assertSame(wrapper, graph.sessionPlayer())
        val first = graph.controls.state.value.track!!.id
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            wrapper.seekToNext()
            scope.testScheduler.advanceTimeBy(1_000)
            scope.testScheduler.runCurrent()
            assertNotEquals(first, graph.controls.state.value.track!!.id)
            assertEquals(1, graph.controls.state.value.sessionId)
            service.get().onTaskRemoved(Intent())
            assertEquals(Phase.Finished, graph.controls.state.value.phase)
            assertEquals(EndReason.Stopped, graph.controls.state.value.endReason)
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            val finished = graph.controls.state.value
            wrapper.seekToNext()
            wrapper.play()
            assertEquals(finished, graph.controls.state.value)
            assertEquals(ComponentName(app, PlaybackService::class.java), shadowOf(app).nextStoppedService.component)
        } finally {
            service.destroy()
            inner.release()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            scope.assertHealthy()
        }
    }

    /** Retains Robolectric's real AudioManager behavior, adding observation of the flags. */
    @Implements(AudioManager::class)
    class RecordingAudioManager : ShadowAudioManager() {
        val calls = mutableListOf<String>()
        @Implementation
        public override fun adjustStreamVolume(stream: Int, direction: Int, flags: Int) {
            calls += "adjust:$stream:$direction:$flags"
            super.adjustStreamVolume(stream, direction, flags)
        }
        @Implementation
        public override fun setStreamVolume(stream: Int, index: Int, flags: Int) {
            calls += "set:$stream:$index:$flags"
            super.setStreamVolume(stream, index, flags)
        }
    }
}
