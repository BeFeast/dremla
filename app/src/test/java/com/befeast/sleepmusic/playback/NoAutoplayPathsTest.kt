package com.befeast.sleepmusic.playback

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.media3.session.MediaSession
import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** PRD 1, 12 / AC-2: boot and process restart never start music. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class NoAutoplayPathsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun nothingRunsAtBoot() {
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach {
            val receivers = app.packageManager.queryBroadcastReceivers(Intent(it).setPackage(app.packageName), 0)
            assertTrue("no receiver for $it", receivers.isEmpty())
        }
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test
    fun theSystemNeverRestartsThePlaybackService() {
        TestApp.graph = TestGraph(RecordingControls(SessionState(phase = Phase.Playing)), FakeMediaPlayer())
        val controller = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(Intent(), Service.START_FLAG_REDELIVERY, 2))
        controller.destroy()
    }

    @Test
    fun aServiceCreatedWithoutASessionTouchesNoPlayerAndStopsItself() {
        val graph = TestGraph(RecordingControls(), FakeMediaPlayer())
        TestApp.graph = graph
        val controller = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        val service = controller.get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, graph.sessionPlayerRequests)
        assertTrue(service.sessions.isEmpty())
        val info = MediaSession.ControllerInfo.createTestOnlyControllerInfo("system", 0, 0, 0, 0, false, Bundle.EMPTY)
        assertNull(service.onGetSession(info))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        controller.destroy()
    }

    @Test
    fun aNewProcessOverAPopulatedStoreStaysIdle() {
        val store = InMemoryPlaybackStore().apply {
            saveBag(BagState(setOf("amb-001", "amb-002"), "amb-002"))
            setLiked("amb-003", true)
            block("amb-004")
            timerMinutes = 45
        }
        val h = EngineHarness(store = store)
        h.advance(3 * 3_600_000L)
        // Stray player callbacks and every command from every source.
        h.port.listener!!.run {
            onPlaying()
            onAdvanced()
            onEnded()
            onPausedBySystem()
            onError("ERROR_CODE_IO_UNSPECIFIED")
        }
        CommandSource.entries.forEach {
            h.engine.play(it)
            h.engine.togglePause(it)
            h.engine.skip(it)
        }
        h.source.emit(h.collection.copy(selection = listOf("amb-001")))
        h.advance(60_000)
        assertEquals(Phase.Idle, h.state.phase)
        assertEquals(0, h.state.sessionId)
        assertTrue(h.log.isEmpty())
        assertEquals(0, h.host.started)
        assertTrue(h.events.verdicts.isEmpty())
    }
}
