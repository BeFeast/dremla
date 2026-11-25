package com.befeast.sleepmusic.playback

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.playback.testing.CheckedTestScope
import com.befeast.sleepmusic.playback.testing.FakeAudioControl
import com.befeast.sleepmusic.playback.testing.FakeCollectionSource
import com.befeast.sleepmusic.playback.testing.FakePlayerPort
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.RecordingHost
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import com.befeast.sleepmusic.playback.testing.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** PRD 1, 7 / AC-2, AC-9: the activity asks the classifier on create and new intent only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class MainActivityLaunchTest {
    private val checkedScopes = mutableListOf<CheckedTestScope>()

    @After
    fun checkCoroutineFailures() = checkedScopes.forEach { it.assertHealthy() }

    private val controls = RecordingControls()
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun graph() {
        TestApp.graph = TestGraph(controls)
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setClassName(app, MainActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private val reasons get() = controls.verdicts.map { it.entry to it.reason }

    @Test
    fun aLauncherStartOnANewTaskStartsOnce() {
        Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
        assertEquals(listOf(LaunchEntry.Create to LaunchReason.LauncherStart), reasons)
    }

    @Test
    fun resumeNeverAsksForASession() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
        // Something covers the activity for a moment (a system dialog, the home button and back).
        activity.pause().resume()
        activity.pause().stop().restart().start().resume()
        assertEquals(1, controls.verdicts.size)
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun wakeNeverAsksForASession() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
        // The box sleeps (screen off: the activity stops) and wakes in the morning with the activity on top.
        activity.pause().stop()
        activity.get().onWindowFocusChanged(false)
        activity.restart().start().resume()
        activity.get().onWindowFocusChanged(true)
        assertEquals(1, controls.verdicts.size)
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun aConfigurationChangeRecreationIsPassive() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
        activity.recreate()
        assertEquals(LaunchEntry.Create to LaunchReason.RestoredState, reasons.last())
        assertEquals(1, controls.verdicts.count { it.isLauncherStart })
    }

    @Test
    fun aProcessRestartRecreationIsPassive() {
        // After process death the system re-creates the activity with saved state and the old launcher intent.
        Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).create(Bundle()).start().resume()
        assertEquals(listOf(LaunchEntry.Create to LaunchReason.RestoredState), reasons)
    }

    @Test
    fun theTilePressedWhileTheTaskExistsStarts() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
        activity.newIntent(launcherIntent())
        assertEquals(LaunchEntry.NewIntent to LaunchReason.LauncherStart, reasons.last())
    }

    @Test
    fun theMediaSessionOpenUiIntentIsPassive() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
        activity.newIntent(Intent(LaunchClassifier.ACTION_SHOW).setClassName(app, MainActivity::class.java.name))
        assertEquals(LaunchEntry.NewIntent to LaunchReason.NotMain, reasons.last())
    }

    @Test
    fun aRelaunchFromRecentsIsPassive() {
        val intent = launcherIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertEquals(listOf(LaunchEntry.Create to LaunchReason.FromHistory), reasons)
    }

    @Test
    fun anExplicitStartWithoutActionIsPassive() {
        Robolectric.buildActivity(MainActivity::class.java, Intent().setClassName(app, MainActivity::class.java.name)).setup()
        assertEquals(listOf(LaunchEntry.Create to LaunchReason.NotMain), reasons)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun withTheRealEngineOnlyALauncherStartLoadsThePlayer() {
        // The passive paths of the tests above, end to end: activity, classifier and the real engine
        // over a loaded collection. Only the launcher start at the end may reach the player.
        val scope = CheckedTestScope().also { checkedScopes += it }
        val log = mutableListOf<String>()
        val clock = Clock { scope.testScheduler.currentTime }
        val host = RecordingHost(log)
        val engine = PlaybackEngine(
            scope = scope,
            clock = clock,
            port = FakePlayerPort(clock, log),
            audio = FakeAudioControl(log),
            store = InMemoryPlaybackStore(),
            source = FakeCollectionSource(PlayableCollection(Tracks.many(6), null)),
            host = host,
        )
        scope.testScheduler.runCurrent()
        TestApp.graph = TestGraph(engine)
        fun settle() {
            scope.testScheduler.advanceTimeBy(60_000)
            scope.testScheduler.runCurrent()
        }
        fun loads() = log.count { it.startsWith("port.load") }

        // Process restart: re-created with saved state and the old launcher intent.
        Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).create(Bundle()).start().resume()
        // Recents, then a configuration change of that activity, then resume and wake.
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).setup()
        activity.recreate()
        activity.pause().stop().restart().start().resume()
        // The MediaSession's open-UI intent and an explicit start without action.
        activity.newIntent(Intent(LaunchClassifier.ACTION_SHOW).setClassName(app, MainActivity::class.java.name))
        Robolectric.buildActivity(MainActivity::class.java, Intent().setClassName(app, MainActivity::class.java.name)).setup()
        settle()
        assertEquals(Phase.Idle, engine.state.value.phase)
        assertEquals(0, engine.state.value.sessionId)
        assertTrue("no player, audio or service call: $log", log.isEmpty())
        assertEquals(0, host.started)

        // The control: the tile pressed on the existing task starts exactly one session.
        activity.newIntent(launcherIntent())
        settle()
        assertEquals(Phase.Playing, engine.state.value.phase)
        assertEquals(1, loads())
        assertEquals(1, host.started)
        // A configuration change during the night neither restarts nor stops it.
        activity.recreate()
        settle()
        assertEquals(Phase.Playing, engine.state.value.phase)
        assertEquals(1, loads())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun withTheRealEngineAnEmptyCollectionKeepsThePlaceholderWithoutCrashing() {
        val scope = CheckedTestScope().also { checkedScopes += it }
        val log = mutableListOf<String>()
        val clock = Clock { scope.testScheduler.currentTime }
        val engine = PlaybackEngine(
            scope = scope,
            clock = clock,
            port = FakePlayerPort(clock, log),
            audio = FakeAudioControl(log),
            store = SharedPrefsPlaybackStore(app),
            // The real local source over the empty app-private directory of a fresh install.
            source = LocalCollectionSource(app, scope, Dispatchers.Unconfined),
            host = RecordingHost(log),
        )
        TestApp.graph = TestGraph(engine)
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup().get()
        scope.testScheduler.advanceTimeBy(60_000)
        scope.testScheduler.runCurrent()
        assertEquals(Phase.Idle, engine.state.value.phase)
        assertTrue(engine.state.value.collectionLoaded)
        assertEquals(0, engine.state.value.playableCount)
        assertTrue("no player, audio or service call: $log", log.isEmpty())
        assertFalse(activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue(content.getChildAt(0) is ComposeView)
    }
}
