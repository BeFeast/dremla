# Night screen and morning behaviour (issue #5)

Implements PRD 5–7 and the session-survival portion of 12 (AC-7, AC-8, AC-9,
AC-11). Playback and collection contracts remain in [playback.md](playback.md)
and [collection.md](collection.md). Full player/menu scenes belong to #6.

## Black screen

The engine records the elapsed-realtime moment of the night's first audio (the player's first
`onPlaying`, when the 30 s fade-in starts), independently of the timer deadline. 10,000 ms after it,
the next 50 ms tick publishes blackScreen (#88). Until the first audio the track screen stays on and
`startingAudio` makes its eyebrow read STARTING (PAUSED and CONNECTING take precedence), so a slow first
load never shows black silence. A night that cannot play (online unavailable) is black 10,000 ms after
its start, and any night is black 60,000 ms after its start at the latest, so a paused or stalled first
load never keeps the panel lit.
Pause, skip, timer edits, activity recreation and wake do not reset that time. Every finished phase
is black. A new explicit launcher night resets it.

nightPlaceholder removes all content, including collection-error and missing-route
messages, from black and finished scenes. These render through the existing pure
black placeholder and its accepted 1920×1080 golden. The activity hides system bars
and draws edge to edge. System overlays, volume panels and vendor firmware remain
outside the app's pixel guarantee. Before blackout, the placeholder reports a
missing sleep route; #6 can display sleepRouteStatus in its menu.

## Preferences and routes

PlaybackControls.setTvOff synchronously persists tv_off in the existing private
playback preferences, default true, and publishes SessionState.tvOff. Changes
apply at the current night's end; no preference change itself requests sleep.
A Clock night never turns the TV off: during it, and while Clock's accepted
request still waits for the collection, setTvOff changes nothing (#69, #70).

AndroidSleepRoute rechecks capability at the point of use:

1. When the device-admin feature exists and this app's admin is active, call
   DevicePolicyManager.lockNow.
2. Otherwise use this app's currently connected accessibility service to perform
   GLOBAL_ACTION_LOCK_SCREEN (available from API 28).
3. Otherwise return failure and keep the scene black.

The optional admin receiver requires BIND_DEVICE_ADMIN and declares only the
force-lock policy. The accessibility service requires BIND_ACCESSIBILITY_SERVICE,
requests no event types, flags or window contents, and does nothing on connection,
events or interruption. It clears its process-local connection on unbind/destruction.
Neither component enables itself or starts playback. An enabled but not yet connected
service is unavailable. Any RuntimeException from capability lookup or a lock action,
a missing policy manager, and a rejected global action fail closed. Each sleep attempt snapshots the
policy manager once. A failed attempt records sleepRoute=Failed once in the ended
night journal, without exception text; it is never retried. Route availability is
refreshed on activity resume and can be refreshed by #6 with refreshSleepRoute; this never performs an action.

The reference device lacks device-admin support, so the separately approved operator
accessibility setup is necessary there. The system lock is expected to sleep the box;
the configured CEC broadcast then places the panel in standby. JVM tests cannot prove
vendor CEC behaviour or whether a platform-accepted action actually slept the panel.

## End of night and morning

Only the engine's live TimerEnded transition may invoke a sleep route, after gain
zero, stop/clear, finished-state publication, journal end and service stop. It invokes
at most once. Stopped, failed, empty, initial, restored and resumed states do not sleep.
There is no state collector that replays a lock request.

The rule for delayed timer delivery is inclusive: timerEndLateMs <= 30,000 may sleep;
anything later only ends playback without locking. A paused expiry never locks,
even within that grace period, regardless of whether the tick or activity resume
arrives first. Thirty seconds tolerates ordinary scheduling delay without treating a timer delivered minutes or hours after its deadline
as a bedtime request. No delayed retry or next-resume action is queued. A transport
command arriving before the first post-sleep tick ends an expired session without sleep, so it cannot briefly
resume expired audio or lock the box in response to a wake-time key. A timer-menu
edit also cannot revive an expired night. An explicit launcher entry first ends a
stale expired night without sleep, then starts a fresh night as the one-press contract
requires.

With TV off disabled, no sleep call occurs. With no working route, black remains.
After a successful request the engine sets closeActivity; the activity calls
finishAndRemoveTask, including if it is stopped when the request completes. A
recreated activity in the same process also consumes that close state without
repeating the sleep action. A new launcher session resets it. A suppressed lock
(paused, command-delivered or late expiry) still dismisses the finished TV-off task when a route is available, exposing the launcher without another
lock request. With TV off disabled or no route available it retains black. Dismissal
is synchronous session state, not a queued action; a new launcher night resets it.

The five finished-session defences from #3 remain intact: engine command guards,
activity media-key consumption, SessionPlayer routing, disabled Media3 resumption,
and no cold start through the non-sticky service or a media-button receiver. The
framework media-button guard also remains in place. Only a classified
launcher entry starts a session. Resume/wake never classify the original intent;
recreation with saved state is passive. A new process has no session to restore.
The documented #3 residual ambiguity of an OS redelivery of a launcher intent without
saved state remains; ordinary process recreation is tested with saved state.

## Two-hour survival

While any night is active (including pause and the pure black scene), the activity
sets FLAG_KEEP_SCREEN_ON. A visible black window therefore still prevents the
normal display idle timeout and screensaver. It does **not** prevent API 34
attentive/inattentive sleep, whose power-management timeout is independent of ordinary
screen wake locks. An ordinary app cannot override that timeout with this flag.
This flag is cleared at completion. It cannot keep a background window visible or override an explicit
operator/system power action.

Media3's existing local wake mode holds the CPU while audio plays, and the existing
foreground mediaPlayback service keeps playback independent of activity visibility.
The two-item queue preserves foreground playback across track changes. Paused audio
holds no player wake lock; if the box sleeps while paused/backgrounded, elapsed realtime
still expires the timer and the paused-expiry rule prevents a morning lock. No alarm, boot
receiver, restart worker, new wake permission or sticky restart is introduced.

The fake-clock two-hour test proves engine continuity and end order, not firmware
survival. The install issue must confirm actual two-hour audio continuity, foreground
service and wake-lock behaviour, screensaver/inattentive settings, black panel output,
CEC and launcher exposure on wake.

## TARGET-DEVICE PREREQUISITE

Before installation acceptance, attentive/inattentive timeout and sleep_timeout must
both be disabled or longer than the longest supported night plus a margin (at least
30 minutes). The install issue's probe must read, without writing settings:

- `settings get secure attentive_timeout`
- `settings get secure sleep_timeout`
- `settings get system screen_off_timeout`

Interpret disabled values according to the target firmware; missing, unreadable or
ambiguous values do not establish compliance. Confirm screen_off_timeout together
with the visible window's keep-screen-on flag and actual continuity. If this probe
fails the prerequisite, the install issue opens a new P1 issue instead of changing
settings. This app never changes power settings or requests privileged overrides.
Session start records these three observed timeout values through public Settings
reads in the private night log (`powerTimeouts`, milliseconds; null means missing,
unreadable or nonnumeric). This diagnostic snapshot is not proof of compliance and
cannot replace the install probe or the two-hour audio test.

## Verification

- NightSessionTest: 10-second UI state; pause/resume blackout; two-hour engine run;
  end-before-sleep; TV-off and route failure; inclusive late threshold; no restart;
  process replacement; new-night reset; paused wake in either callback order; late
  dismissal and immediate-observer protection during a new launcher start.
- NightLogTest: timeout snapshots on every session start, missing values and
  unchanged public settings; one persisted route failure after session end.
- NightPlatformTest: SDK 28 and 34 capability/admin/accessibility selection, actual
  lockNow invocation, a single nullable policy snapshot, rejected and throwing routes;
  SDK 34 persisted preference, window keep-on,
  activity finish while stopped and unavailable-route retention.
- ManifestTest: exact permissions and service/receiver set, binding permissions
  and metadata; no boot/media-button entry point.
- PlaceholderSceneGoldenTest: active-black and finished output use the unchanged
  accepted black reference, suppressing even error messages.
- Existing launch, finished-session, media-button, service and process-recreation tests
  remain regression gates.

No device, accessibility activation, admin activation, adb or release operation is
part of implementation verification.

## Passive observations for an operator test box

These are later operator checks, not commands run by this issue. They neither enable
accessibility nor lock the screen:

- After a normal launcher start, use adb shell dumpsys window to inspect the Dremla
  window's keep-screen-on flag; observe the panel turning black around ten seconds after the music starts.
- Use adb shell dumpsys power and adb shell dumpsys media_session while music plays
  to observe the player wake lock and active playback, then repeat after idle completion.
- Use adb shell dumpsys activity service com.befeast.sleepmusic/.playback.PlaybackService
  during the night to inspect the private journal and session end evidence.
- With no sleep route enabled, observe timer completion leaving black, then wake/resume
  normally and verify silence. Use adb shell dumpsys activity activities to inspect
  activity/task state. Actual successful-lock task removal is a separate install check.

A two-hour run and listening/advancing playback position are needed; dumps alone do
not establish audible continuity.
