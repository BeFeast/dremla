package com.befeast.sleepmusic.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.SessionEntryActivity
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Clock's explicit entry: only Clock may call it, the engine decides, refusals go back to Clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SessionEntryActivityTest {
    private val controls = RecordingControls()
    private val app = RuntimeEnvironment.getApplication()
    private val clock = ComponentName("com.befeast.tx10clock.preview", "com.befeast.tx10clock.MainActivity")

    @Before
    fun graph() {
        TestApp.graph = TestGraph(controls)
    }

    private fun request() = Intent(ClockSessions.ACTION_START)
        .setClassName(app, SessionEntryActivity::class.java.name)
        .putExtra(ClockSessions.EXTRA_MODE, "day")
        .putExtra(ClockSessions.EXTRA_MUSIC_MINUTES, 45)
        .putExtra(ClockSessions.EXTRA_WINDOW_END, 1_800_000_000_000L)
        .putExtra(ClockSessions.EXTRA_REQUEST_ID, "req-1")

    private fun run(intent: Intent, caller: ComponentName?): SessionEntryActivity {
        val controller = Robolectric.buildActivity(SessionEntryActivity::class.java, intent)
        shadowOf(controller.get()).setCallingActivity(caller)
        return controller.create().get()
    }

    @Test
    fun anAcceptedRequestReachesTheEngineAndShowsTheNightPassively() {
        val activity = run(request(), clock)
        assertEquals(listOf(ClockSessionRequest("day", 45, 1_800_000_000_000L, "req-1")), controls.clockRequests)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val shown = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, shown.component?.className)
        assertEquals(LaunchClassifier.ACTION_SHOW, shown.action)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun anyOtherCallerIsRefusedBeforeTheEngine() {
        val activity = run(request(), ComponentName("com.example.other", "com.example.other.Main"))
        assertTrue(controls.clockRequests.isEmpty())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("unknown caller", shadowOf(activity).resultIntent.getStringExtra(ClockSessions.EXTRA_REFUSAL))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun anEngineRefusalGoesBackToClockAndShowsNothing() {
        controls.clockRefusal = "repeated request"
        val activity = run(request(), clock)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("repeated request", shadowOf(activity).resultIntent.getStringExtra(ClockSessions.EXTRA_REFUSAL))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
