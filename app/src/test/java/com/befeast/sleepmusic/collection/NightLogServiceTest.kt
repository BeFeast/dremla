package com.befeast.sleepmusic.collection

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Looper
import com.befeast.sleepmusic.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration

/** A diagnostic start must not even request the playback graph (which can download or play). */
class DiagnosticsApp : Application(), PlaybackProvider {
    override val playback: PlaybackGraph get() = error("diagnostics touched playback graph")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DiagnosticsApp::class)
class NightLogServiceTest {
    @Test fun coldDiagnosticStartDumpsPersistedLogAndStopsWithoutGraphOrSession() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        NightLog(app) { 1000 }.apply {
            sessionStarted(1, 120, StartVolumeResult(25, 0, false, 15, 15, false, false, null))
            trackStarted(1, "a")
            sessionEnded(1, EndReason.TimerEnded)
        }
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(PlaybackService.ACTION_DIAGNOSTICS), 0, 1))
        assertEquals(PlaybackService.IDLE_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(service.sessions.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
        val text = StringWriter()
        PlaybackService::class.java.getDeclaredMethod("dump", FileDescriptor::class.java, PrintWriter::class.java, Array<String>::class.java)
            .apply { isAccessible = true }.invoke(service, FileDescriptor(), PrintWriter(text), emptyArray<String>())
        assertTrue(text.toString().contains("reason=TimerEnded"))
        assertTrue(text.toString().contains("track=a"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlaybackService.DIAGNOSTIC_WINDOW_MS))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }
}
