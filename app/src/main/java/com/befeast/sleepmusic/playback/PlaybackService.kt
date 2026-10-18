package com.befeast.sleepmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.media.session.MediaSession as PlatformSession
import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.R

/**
 * Android host of a night session: the foreground `mediaPlayback` service and the MediaSession.
 * [ServicePlaybackHost] starts and stops it with a night. A diagnostic start can also keep it
 * alive for a bounded dump window without playback. The engine and player are process-scoped.
 *
 * The MediaSession is built on demand, from a normal `onStartCommand` or `onGetSession`, as
 * soon as a session is active, so an instance created while idle and kept alive by a bind still
 * gets it when a night starts. Without an active session the service touches no player, offers
 * no session and stops itself; it is never restarted by the system.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    internal var mediaButtonGuardLookup: (MediaSession) -> Pair<PlatformSession, PlatformSession.Callback> =
        PlatformMediaButtonGuard::lookup

    private val graph: PlaybackGraph get() = (application as PlaybackProvider).playback

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var diagnostic = false
    private val diagnosticTimeout = Runnable {
        diagnostic = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val session = mediaSession
        if (session == null || !graph.controls.state.value.isActive) stopSelf()
        else onUpdateNotification(session, session.player.isPlaying)
    }
    private val nightLog by lazy {
        (application as? com.befeast.sleepmusic.SleepMusicApp)?.nightLog ?: NightLog(this)
    }

    // onCreate deliberately touches neither graph nor player: the next intent may be diagnostics.
    override fun onCreate() { super.onCreate() }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        nightLog.dump(writer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (diagnostic) null else if (ensureSession()) mediaSession else { stopIdle(); null }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DIAGNOSTICS) {
            diagnostic = mediaSession == null
            // Every foreground-service start owes a promotion, including a demoted paused session.
            startForeground(IDLE_NOTIFICATION_ID, idleNotification())
            handler.removeCallbacks(diagnosticTimeout)
            handler.postDelayed(diagnosticTimeout, DIAGNOSTIC_WINDOW_MS)
            return START_NOT_STICKY
        }
        diagnostic = false
        // Keep any diagnostic deadline: a normal start must not strand its notification.
        // Build the session first, so a media button in this intent finds it.
        val active = ensureSession()
        super.onStartCommand(intent, flags, startId)
        if (!active) stopIdle()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!diagnostic) graph.controls.stop(CommandSource.System)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(diagnosticTimeout)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /** True while a session is active, with the MediaSession built and added; false otherwise. */
    private fun ensureSession(): Boolean {
        val graph = graph
        if (!graph.controls.state.value.isActive) return false
        if (mediaSession == null) {
            val session = MediaSession.Builder(this, graph.sessionPlayer())
                .setId(SESSION_ID)
                .setCallback(graph.sessionCallback())
                .setSessionActivity(showUiIntent())
                .build()
            // Own the session before installing the optional guard so destruction always releases it.
            mediaSession = session
            PlatformMediaButtonGuard.install(session, graph.controls, mediaButtonGuardLookup)
            addSession(session)
        }
        return true
    }

    /**
     * Stops a service that has no session to host. Below API 31 Media3 gives the platform session
     * a `PendingIntent.getForegroundService` to this service as its media-button receiver. A
     * session that was never released (the process died during a night) leaves it behind, and the
     * next media key then starts this service with `startForegroundService` in a process without a
     * session. Stopping without entering the foreground would make the system crash the app, so
     * the service enters it with a silent notification and leaves it at once.
     */
    private fun stopIdle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                startForeground(IDLE_NOTIFICATION_ID, idleNotification())
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: RuntimeException) {
                // Never crash on the idle path; the stop below still runs.
                Log.w(TAG, "idle playback service could not enter the foreground: ${e.message}")
            }
        }
        stopSelf()
    }

    private fun idleNotification(): Notification {
        val channel = NotificationChannel(
            IDLE_CHANNEL_ID,
            getString(R.string.playback_idle_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, IDLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun showUiIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).setAction(LaunchClassifier.ACTION_SHOW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_DIAGNOSTICS = "com.befeast.sleepmusic.action.DUMP_NIGHT_LOG"
        const val DIAGNOSTIC_WINDOW_MS = 60_000L
        const val SESSION_ID = "night"

        /** Only for the instant foreground of [stopIdle]; Media3's media notification uses its own. */
        const val IDLE_CHANNEL_ID = "idle_stop"
        const val IDLE_NOTIFICATION_ID = 7_301
        private const val TAG = "SleepMusic"
    }
}
