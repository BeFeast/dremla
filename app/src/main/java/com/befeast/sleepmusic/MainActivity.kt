package com.befeast.sleepmusic

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import com.befeast.sleepmusic.playback.CollectionPhase
import com.befeast.sleepmusic.playback.CommandSource
import com.befeast.sleepmusic.playback.LaunchClassifier
import com.befeast.sleepmusic.playback.LaunchEntry
import com.befeast.sleepmusic.playback.PlaybackControls
import com.befeast.sleepmusic.playback.PlaybackProvider
import com.befeast.sleepmusic.playback.RemoteCommand
import com.befeast.sleepmusic.playback.RemoteKeyMap
import com.befeast.sleepmusic.playback.launchSignal
import com.befeast.sleepmusic.ui.NightScene
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import com.befeast.sleepmusic.playback.SharedPrefsPlaybackStore

/**
 * Launcher entry point (`singleTask`, so a tile press on an existing task arrives in
 * [onNewIntent]). Only [onCreate] and [onNewIntent] consult the launch classifier; resume and wake
 * never do, so they never start music. The night scene observes the same session state.
 */
class MainActivity : ComponentActivity() {
    private val menuOpen = mutableStateOf(false)
    private val startVolume = mutableStateOf(15)
    private val controls: PlaybackControls
        get() = (application as PlaybackProvider).playback.controls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        controls.onUiEntry(LaunchClassifier.classify(launchSignal(intent, LaunchEntry.Create, savedInstanceState)))
        lifecycleScope.launch {
            controls.state.collect { state ->
                // Normal idle/screensaver only; inattentive sleep is an install prerequisite (night.md).
                if (state.isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (state.blackScreen || state.phase == com.befeast.sleepmusic.playback.Phase.Finished) menuOpen.value = false
                if (state.closeActivity) finishAndRemoveTask()
            }
        }
        startVolume.value = SharedPrefsPlaybackStore(this).startVolumeLevel
        setContent {
            val session = controls.state.collectAsState().value
            val downloads = (application as PlaybackProvider).playback.downloads
            val status = downloads?.status?.collectAsState()?.value
            BackHandler(enabled = menuOpen.value) { menuOpen.value = false }
            NightScene(session, status, menuOpen.value, startVolume.value,
                onTimer = controls::setTimerMinutes,
                onVolume = { startVolume.value = it; controls.setStartVolumeLevel(it) },
                onTvOff = { controls.setTvOff(!session.tvOff) },
                onRefresh = { downloads?.refresh(); menuOpen.value = false },
                onLike = controls::toggleLike, onNever = controls::neverAgain,
                onOpenMenu = { menuOpen.value = true; controls.refreshSleepRoute() })
        }
    }

    override fun onResume() {
        super.onResume()
        controls.refreshSleepRoute()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controls.onUiEntry(LaunchClassifier.classify(launchSignal(intent, LaunchEntry.NewIntent, null)))
    }

    /**
     * The key hooks (no `dispatchKeyEvent` override, so #6's focused controls see keys first).
     * Volume and mute keys return `super`, so the system keeps changing STREAM_MUSIC.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuOpen.value && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return true
        val command = RemoteKeyMap.onKeyDown(keyCode, event.repeatCount) ?: return super.onKeyDown(keyCode, event)
        if (command == RemoteCommand.TrackPress) event.startTracking() else run(command)
        return true
    }

    /** OK acts on its release, so a long press (#6's menu) never pauses first. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val command = RemoteKeyMap.onKeyUp(keyCode, event.isTracking, event.isCanceled) ?: return super.onKeyUp(keyCode, event)
        run(command)
        return true
    }

    /** A long press of OK cancels its release; #6 opens the menu here. */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!RemoteKeyMap.consumesLongPress(keyCode)) return super.onKeyLongPress(keyCode, event)
        if (!controls.state.value.blackScreen && controls.state.value.phase != com.befeast.sleepmusic.playback.Phase.Finished) {
            menuOpen.value = true
            controls.refreshSleepRoute()
        }
        return true
    }

    override fun onStop() {
        // Leaving during the first download cancels the launch intent, never an active night.
        controls.cancelPendingStart()
        super.onStop()
    }

    private fun run(command: RemoteCommand) {
        val downloads = (application as PlaybackProvider).playback.downloads
        if (command == RemoteCommand.TogglePause && downloads?.status?.value?.phase == CollectionPhase.FirstRunError) {
            downloads.refresh()
            return
        }
        when (command) {
            RemoteCommand.TogglePause -> controls.togglePause(CommandSource.Remote)
            RemoteCommand.Play -> controls.play(CommandSource.Remote)
            RemoteCommand.Pause -> controls.pause(CommandSource.Remote)
            RemoteCommand.Skip -> controls.skip(CommandSource.Remote)
            RemoteCommand.Consume, RemoteCommand.TrackPress -> Unit
        }
    }
}
