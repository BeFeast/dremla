package com.befeast.sleepmusic.playback

import android.content.Intent
import android.media.session.MediaSession as PlatformSession
import android.media.session.MediaSessionManager.RemoteUserInfo
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Pair
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = TestApp::class)
class PlatformMediaButtonGuardTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun platform(session: MediaSession): PlatformSession {
        val compat = MediaSession::class.java.getDeclaredMethod("getSessionCompat")
            .apply { isAccessible = true }.invoke(session)!!
        return compat.javaClass.getMethod("getMediaSession").invoke(compat) as PlatformSession
    }

    // Exercise the real framework message handler, including current-controller context.
    // Only Binder delivery is replaced: a local caller supplies the system's message payload.
    private fun dispatch(platform: PlatformSession, packageName: String, kind: String, payload: Any?) {
        val handler = ReflectionHelpers.getField<Handler>(platform, "mCallback")
        val what = ReflectionHelpers.getStaticField<Int>(handler.javaClass, kind)
        val message = Message.obtain(handler, what, Pair(RemoteUserInfo(packageName, 123, 2000), payload))
        try {
            handler.handleMessage(message)
        } finally {
            message.recycle()
        }
    }

    private fun button(key: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0, 0, action, key, repeat))

    @Test
    fun unguardedPinnedMedia3ReproducesEmptyPackageCrash() {
        val h = EngineHarness()
        val session = MediaSession.Builder(app, SessionPlayer(FakeMediaPlayer(), h.engine)).build()
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                dispatch(platform(session), "", "MSG_MEDIA_BUTTON", button(KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            assertEquals("packageName should be nonempty", error.message)
        } finally {
            session.release()
        }
    }

    @Test
    fun pinnedMedia3RealInstallSucceeds() {
        val h = EngineHarness()
        val session = MediaSession.Builder(app, SessionPlayer(FakeMediaPlayer(), h.engine)).build()
        try {
            assertTrue(PlatformMediaButtonGuard.install(session, h.engine))
        } finally {
            session.release()
        }
    }

    @Test
    fun lookupFailuresKeepServiceSessionUsableAndReleaseOnDestroy() {
        listOf(
            NoSuchMethodException("lookup"),
            NoSuchFieldException("lookup"),
            IllegalAccessException("lookup"),
            ClassCastException("lookup"),
            NullPointerException("lookup"),
            SecurityException("lookup"),
            NoClassDefFoundError("lookup"),
        ).forEach { failure ->
            val h = EngineHarness()
            h.launcherStart()
            TestApp.graph = TestGraph(h.engine, FakeMediaPlayer())
            val service = Robolectric.buildService(PlaybackService::class.java)
            var originalCallback: Handler? = null
            var lookups = 0
            service.get().mediaButtonGuardLookup = { session ->
                lookups++
                originalCallback = ReflectionHelpers.getField(platform(session), "mCallback")
                throw failure
            }
            ShadowLog.clear()
            service.create()
            service.get().onStartCommand(Intent(), 0, 1)
            val session = service.get().sessions.single()
            try {
                assertSame(originalCallback, ReflectionHelpers.getField<Handler>(platform(session), "mCallback"))
                val warnings = ShadowLog.getLogsForTag("SleepMusic").filter {
                    it.msg == "media-button guard unavailable; retaining Media3 callback"
                }
                assertEquals(1, warnings.size)
                assertSame(failure, warnings.single().throwable)
                assertEquals(android.util.Log.WARN, warnings.single().type)
                service.get().onStartCommand(null, 0, 1)
                assertSame(session, service.get().sessions.single())
                assertEquals(1, lookups)
                val expected = h.port.upNext!!.trackId
                dispatch(platform(session), app.packageName, "MSG_NEXT", null)
                shadowOf(Looper.getMainLooper()).idle()
                h.advance(1_000)
                assertEquals(expected, h.state.track!!.id)
            } finally {
                service.destroy()
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(session, "isReleased"))
        }
    }

    @Test
    fun validPackageMediaButtonSkipsThroughGuard() {
        val h = EngineHarness()
        h.launcherStart()
        TestApp.graph = TestGraph(h.engine, FakeMediaPlayer())
        val service = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        try {
            val expected = h.port.upNext!!.trackId
            dispatch(platform(service.get().sessions.single()), app.packageName, "MSG_MEDIA_BUTTON",
                button(KeyEvent.KEYCODE_MEDIA_NEXT))
            h.advance(1_000)
            assertEquals(expected, h.state.track!!.id)
            assertEquals(2, h.played.size)
        } finally {
            service.destroy()
        }
    }

    @Test
    fun emptyPackageButtonsAfterTimerExpiryDoNothing() {
        val h = EngineHarness()
        h.launcherStart()
        TestApp.graph = TestGraph(h.engine, FakeMediaPlayer())
        val service = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        try {
            val platform = platform(service.get().sessions.single())
            h.sleepThrough(h.state.timerEndAtMs - h.now)
            h.advance(50)
            assertEquals(EndReason.TimerEnded, h.state.endReason)
            val finished = h.state
            h.log.clear()
            listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT).forEach {
                dispatch(platform, "", "MSG_MEDIA_BUTTON", button(it))
            }
            h.advance(1_000)
            assertEquals(finished, h.state)
            assertTrue(h.log.isEmpty())
        } finally {
            service.destroy()
        }
    }

    @Test
    fun liveServiceEmptyPackageNextUsesEngineAndFinishedButtonsDoNothing() {
        val h = EngineHarness()
        h.launcherStart()
        TestApp.graph = TestGraph(h.engine, FakeMediaPlayer())
        val service = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        try {
            val platform = platform(service.get().sessions.single())
            val expected = h.port.upNext!!.trackId
            dispatch(platform, "", "MSG_MEDIA_BUTTON", button(KeyEvent.KEYCODE_MEDIA_NEXT))
            h.advance(1_000)
            assertEquals(expected, h.state.track!!.id)
            assertEquals(2, h.played.size)
            h.engine.stop(CommandSource.System)
            val finished = h.state
            h.log.clear()
            listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT).forEach {
                dispatch(platform, "", "MSG_MEDIA_BUTTON", button(it))
            }
            h.advance(1_000)
            assertEquals(finished, h.state)
            assertTrue(h.log.isEmpty())
        } finally {
            service.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun upRepeatAndUnsupportedButtonsAreConsumedWithoutEngineEffects() {
        val h = EngineHarness()
        h.launcherStart()
        TestApp.graph = TestGraph(h.engine, FakeMediaPlayer())
        val service = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        try {
            val platform = platform(service.get().sessions.single())
            val before = h.state
            h.log.clear()
            listOf(
                button(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP),
                button(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 1),
                button(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
                Intent(Intent.ACTION_MEDIA_BUTTON),
            ).forEach { dispatch(platform, "", "MSG_MEDIA_BUTTON", it) }
            h.settle()
            assertEquals(before, h.state)
            assertTrue(h.log.isEmpty())
        } finally {
            service.destroy()
        }
    }

    @Test
    fun validPlatformTransportNextStillUsesMedia3AndEngine() {
        val h = EngineHarness()
        h.launcherStart()
        TestApp.graph = TestGraph(h.engine, FakeMediaPlayer())
        val service = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        try {
            val expected = h.port.upNext!!.trackId
            dispatch(platform(service.get().sessions.single()), app.packageName, "MSG_NEXT", null)
            shadowOf(Looper.getMainLooper()).idle()
            h.advance(1_000)
            assertEquals(expected, h.state.track!!.id)
        } finally {
            service.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
