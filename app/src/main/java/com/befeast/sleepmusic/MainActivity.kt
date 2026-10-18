package com.befeast.sleepmusic

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.befeast.sleepmusic.playback.CommandSource
import com.befeast.sleepmusic.playback.LaunchClassifier
import com.befeast.sleepmusic.playback.LaunchEntry
import com.befeast.sleepmusic.playback.PlaybackControls
import com.befeast.sleepmusic.playback.PlaybackProvider
import com.befeast.sleepmusic.playback.RemoteCommand
import com.befeast.sleepmusic.playback.RemoteKeyMap
import com.befeast.sleepmusic.playback.launchSignal
import com.befeast.sleepmusic.ui.PlaceholderScene
import com.befeast.sleepmusic.ui.PlaceholderState

/**
 * Launcher entry point (`singleTask`, so a tile press on an existing task arrives in
 * [onNewIntent]). Only [onCreate] and [onNewIntent] consult the launch classifier; resume and wake
 * never do, so they never start music. The scaffold still shows only the black placeholder scene.
 */
class MainActivity : ComponentActivity() {
    private val controls: PlaybackControls
        get() = (application as PlaybackProvider).playback.controls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            PlaceholderScene(PlaceholderState())
        }
        controls.onUiEntry(LaunchClassifier.classify(launchSignal(intent, LaunchEntry.Create, savedInstanceState)))
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
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean =
        RemoteKeyMap.consumesLongPress(keyCode) || super.onKeyLongPress(keyCode, event)

    private fun run(command: RemoteCommand) {
        when (command) {
            RemoteCommand.TogglePause -> controls.togglePause(CommandSource.Remote)
            RemoteCommand.Play -> controls.play(CommandSource.Remote)
            RemoteCommand.Pause -> controls.pause(CommandSource.Remote)
            RemoteCommand.Skip -> controls.skip(CommandSource.Remote)
            RemoteCommand.Consume, RemoteCommand.TrackPress -> Unit
        }
    }
}
