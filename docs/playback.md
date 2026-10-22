# Playback (issue #3)

Issue #5 integration: [night.md](night.md) defines black-screen timing, TV-off preferences,
sleep routes, late-timer guards and activity removal. It supersedes the corresponding
pending seams and placeholder behaviour below.

Issue #4 integration: [collection.md](collection.md) supersedes the local-only source, logcat-only
events and pending-start/service-dump gaps described below. INTERNET is now deliberate. Service
creation waits for a start or bind; a bounded diagnostic start never creates playback.

Design of the playback service, the MediaSession and one-press autoplay for PRD contract items 1-4, 8, 9, the media-key part of item 7 and the preference part of item 13 (acceptance AC-2, AC-5, AC-6, AC-10). Later issues plug into the seams in [Seams](#seams-for-later-issues): #4 (collection download, refresh, night log, `dumpsys`), #5 (night screen, sleep routes, morning, `TV off`) and #6 (Compose TV UI).

The ported pure logic (`Fades`, `ShuffleBag`, `StartVolume`, `Library`, `ui/RemainingTime.kt`) is used unchanged (`StartVolume.kt` stays byte-identical to the kit but is no longer used by the session start since #34); new code wraps it from new files (see [kit-port.md](kit-port.md)).

## Overview

```
 remote keys ──► MainActivity.onKeyDown ──► RemoteKeyMap ─┐
 launcher    ──► MainActivity.onCreate/onNewIntent        │
                   └─► LaunchClassifier ──► onUiEntry ────┤
                                                          ▼
 system controllers, media buttons ──► MediaSession ──► SessionPlayer ──► PlaybackControls
                                        (PlaybackService)   (guard + router)   = PlaybackEngine
                                                                                 │
             PlayQueue (ShuffleBag) ◄────────────────────────────────────────────┤
             VolumeEnvelope + SleepTimer (Fades) ◄───────────────────────────────┤
             AudioControl (STREAM_MUSIC), PlaybackStore, CollectionSource ◄──────┤
             PlayerPort ──► ExoPlayer (the same instance SessionPlayer wraps) ◄──┘
```

- `PlaybackEngine` is the only place that decides anything: whether a session starts, what plays next, pause, skip, stop, the timer and the volume envelope. It is plain Kotlin (no Android imports) and runs on the main thread; every dependency is an interface, so it is tested on the JVM with fakes and virtual time.
- Every command reaches the engine through `PlaybackControls`, whatever its origin: the activity's key handler, the MediaSession (system controllers, media buttons) or, in #6, the menu. There is no second path to the player.
- `PlaybackService` is the Android host: foreground `mediaPlayback` service, MediaSession, `dumpsys` (#4). It runs exactly while a session is active.
- The engine and its collaborators live in a process-scoped `PlaybackGraph` held by the `Application`, not inside the service, because the launch decision in `onCreate` needs the session state synchronously, the finished state must outlive the service (the service stops at the end of the night, the black screen does not), and tests construct the engine without a service.

## Package layout

New code goes to `com.befeast.sleepmusic.playback` (main) unless noted.

| File | Responsibility |
|---|---|
| `SleepMusicApp.kt` (root package) | `Application` implementing `PlaybackProvider`; builds `PlaybackGraph` lazily on first access. `onCreate` does nothing else, so Robolectric tests (golden scenes, `LibraryTest`) are not affected. |
| `PlaybackGraph.kt` | Wiring: store, audio, collection, ExoPlayer (lazy), port, engine, host; `sessionPlayer()` and `sessionCallback()` for the service. `PlaybackProvider` interface (`val playback: PlaybackGraph`) so tests supply a graph with fakes through `@Config(application = TestApp::class)`. |
| `PlaybackControls.kt` | `PlaybackControls` interface, `CommandSource` |
| `PlaybackEngine.kt` | Session lifecycle, commands, tick loop, state publishing |
| `SessionEvents.kt` | `SessionEvents` (session milestones, the night-log seam) and `LogcatSessionEvents` |
| `SessionState.kt` | `Phase`, `EndReason`, `NowPlaying`, `SessionState` |
| `LaunchClassifier.kt` | Pure launcher-start rule: `LaunchSignal`, `LaunchVerdict` (entry and reason), `LaunchClassifier` |
| `LaunchSignals.kt` | `launchSignal(intent, entry, savedInstanceState)`: the Android adapter, kept out of the pure file |
| `RemoteKeyMap.kt` | Pure key code to `RemoteCommand` mapping; volume keys map to nothing |
| `PlayQueue.kt` | Shuffle-bag order with a one-track lookahead; commits bag progress when a track really starts |
| `SleepTimer.kt` | Timer choices and default, countdown, sleep-fade target |
| `VolumeEnvelope.kt` | Player gain per tick: session fade-in, track edges, sleep fade (slew-limited upwards), loudness, transport ramp |
| `Clock.kt` | `Clock` interface, `ElapsedRealtimeClock` |
| `AudioControl.kt` | Read-only `AudioControl` interface over `STREAM_MUSIC`, `StartVolumeResult`, `StartVolumeSequence` (the per-session observation; nothing is written, #34) |
| `SystemAudioControl.kt` | `AudioControl` over `AudioManager`, reads only |
| `PlaybackStore.kt` | `PlaybackStore` interface, `BagState` |
| `SharedPrefsPlaybackStore.kt` | `PlaybackStore` over the `playback` SharedPreferences file |
| `CollectionSource.kt` | `CollectionSource` interface, `PlayableCollection` |
| `LocalCollectionSource.kt` | The app-private collection the ported `Library` reads (`files/collection`), loaded off the main thread |
| `PlayerPort.kt`, `ExoPlayerPort.kt` | Narrow player interface for the engine; ExoPlayer adapter |
| `SessionPlayer.kt` | `ForwardingPlayer` given to the MediaSession: routes transport to `PlaybackControls`, refuses everything else |
| `SessionCallback.kt` | `MediaSession.Callback`: granted commands, no playback resumption, no external queue edits |
| `PlaybackHost.kt` | `PlaybackHost` interface |
| `ServicePlaybackHost.kt` | `PlaybackHost` that starts and stops `PlaybackService` |
| `PlaybackService.kt` | `MediaSessionService` host; builds the MediaSession on demand; the idle stop |
| `MainActivity.kt` (root package, scaffold file) | Launch classification, key mapping, `volumeControlStream`; still renders `PlaceholderScene(PlaceholderState())` |
| `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` | Exclude everything from cloud backup and device transfer |
| `res/values/strings_playback.xml` | Name of the notification channel of the idle stop (API 30 and lower) |

Media3 unstable APIs (`ForwardingPlayer`, and `SimpleBasePlayer` in tests) are used with `@OptIn(UnstableApi::class)` (`androidx.annotation.OptIn`) on the class; lint `UnsafeOptInUsageError` stays enabled.

## Key interfaces

```kotlin
fun interface Clock { fun nowMs(): Long }                     // SystemClock.elapsedRealtime(); tests: virtual time

interface AudioControl {                                       // STREAM_MUSIC only, read only (#34)
    val maxIndex: Int
    val isFixed: Boolean
    fun index(): Int
    fun isMuted(): Boolean
}
data class StartVolumeResult(
    val max: Int, val before: Int, val wasMuted: Boolean,
    val after: Int, val mutedAfter: Boolean, val fixed: Boolean,
)

interface PlaybackStore {
    fun liked(): Set<String>
    fun blocked(): Set<String>
    fun setLiked(id: String, liked: Boolean)
    fun block(id: String)                                      // also removes the like
    fun bag(): BagState
    fun saveBag(state: BagState)
    var timerMinutes: Int                                      // sanitised to SleepTimer.CHOICES, default 120
}
data class BagState(val played: Set<String>, val lastId: String?)

interface CollectionSource {
    val collection: StateFlow<PlayableCollection?>            // null = not loaded yet
    fun fileFor(track: Track): File
}
data class PlayableCollection(val tracks: List<Track>, val selection: List<String>?)

data class QueueItem(val trackId: String, val file: File, val title: String, val artist: String)
interface PlayerPort {
    val positionMs: Long
    val durationMs: Long                                       // -1 when unknown
    fun load(current: QueueItem, upNext: QueueItem?, play: Boolean = true) // replace the playlist, prepare; play from 0, or stay paused
    fun setUpNext(upNext: QueueItem?)                          // replace, add or drop the item after the current one
    fun advance()                                              // move to the queued item now
    fun play()
    fun pause()
    fun stopAndClear()                                         // stop() + clearMediaItems()
    fun setVolume(gain: Float)                                 // player volume, linear 0..1; kept for a player created later
    fun setListener(listener: Listener?)
    interface Listener {
        fun onPlaying()                                        // isPlaying became true
        fun onAdvanced()                                       // the queued item became current (end of track or advance())
        fun onEnded()                                          // the current item ended with nothing queued
        fun onPausedBySystem()                                 // playWhenReady false for a reason other than a user request
        fun onError(code: String)
    }
}

interface PlaybackHost {
    fun sessionStarted()                                       // startService
    fun sessionResumed()                                       // startService again: idempotent, the system may have stopped it while paused
    fun sessionEnded()                                         // stopService
}

enum class CommandSource { Remote, MediaSession, System, Menu }
interface PlaybackControls {
    val state: StateFlow<SessionState>
    fun onUiEntry(verdict: LaunchVerdict)                      // the only way a session starts
    fun togglePause(source: CommandSource)
    fun play(source: CommandSource)                            // resumes a paused session, never starts one
    fun pause(source: CommandSource)
    fun skip(source: CommandSource)                            // Right, media NEXT and MediaSession next
    fun stop(source: CommandSource)                            // ends the session for good
    fun toggleLike()
    fun neverAgain()
    fun setTimerMinutes(minutes: Int)
}

enum class Phase { Idle, Playing, Paused, Fading, Finished }
enum class EndReason { TimerEnded, Stopped, NothingToPlay, PlaybackFailed }
data class NowPlaying(val id: String, val title: String, val artist: String, val category: String, val liked: Boolean)
data class SessionState(
    val phase: Phase = Phase.Idle,
    val sessionId: Int = 0,                                    // +1 at every session start
    val track: NowPlaying? = null,
    val trackRemainingSec: Long = 0,
    val timerRemainingSec: Long = 0,                           // render with remainingParts()
    val timerMinutes: Int = SleepTimer.DEFAULT_MINUTES,
    val endReason: EndReason? = null,                          // set in Finished
    val collectionLoaded: Boolean = false,
    val playableCount: Int = 0,                                // 0 = nothing to play, the placeholder shows
    val timerEndAtMs: Long = 0,                                // engine clock (elapsed realtime) of the timer end; kept in Finished
    val endedAtMs: Long = 0,                                   // engine clock of the end; Finished only
) {
    val isActive: Boolean get() = phase == Phase.Playing || phase == Phase.Paused || phase == Phase.Fading
    val timerEndLateMs: Long                                   // endedAtMs - timerEndAtMs for TimerEnded, else 0
}
```

`Fading` means playing inside the last 10 min; a paused session shows `Paused` even inside the fade. Remaining times are whole seconds, and `StateFlow` drops equal values, so the 20 Hz tick emits only when the phase, the track or a whole-second value changes.

## Launcher start

`LaunchClassifier.classify(LaunchSignal)` is pure; the activity only builds the signal.

A **launcher start** is an intent with action `ACTION_MAIN` and category `CATEGORY_LEANBACK_LAUNCHER` or `CATEGORY_LAUNCHER`, without `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, delivered either

- to `onCreate` with no saved instance state (a new task, not a re-creation after process death or a configuration change), or
- to `onNewIntent` of the running activity (the listener pressed the app's tile while the task existed).

Anything else is passive. The verdict carries a reason (`NoIntent`, `NotMain`, `NoLauncherCategory`, `FromHistory`, `RestoredState`) for the night log in #4.

| Path | What reaches the classifier | Verdict |
|---|---|---|
| Launcher tile, no task | `onCreate`, MAIN + LEANBACK_LAUNCHER, no saved state | launcher start |
| Launcher tile, task exists | `onNewIntent`, MAIN + LEANBACK_LAUNCHER | launcher start |
| Resume, wake (screen on, TV on, activity back on top) | nothing: `onStart`/`onResume` never consult the classifier | none |
| Recents | `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` | passive |
| Process restart, activity re-created | `onCreate` with saved state (the system re-delivers the launcher intent) | passive |
| Configuration change | `onCreate` with saved state | passive |
| MediaSession "open UI" | `onNewIntent`/`onCreate` with action `com.befeast.sleepmusic.action.SHOW` | passive |
| Explicit component without action | action null | passive |
| Boot | no component runs: no boot receiver, no `RECEIVE_BOOT_COMPLETED` | none |
| Service restarted by the system | `onStartCommand` returns `START_NOT_STICKY`; a service created without an active session stops itself and never touches the player (below API 31 after entering and leaving the foreground, see [layer 5](#finished-and-stopped-sessions-stay-finished)) | none |

`MainActivity` gets `android:launchMode="singleTask"`. With the default launch mode a tile press on an existing task only brings it to the front and delivers no intent, so the second evening after a night with `TV off` off would not autoplay.

`PlaybackEngine.onUiEntry(verdict)` starts a session only for a launcher start while no session is active (a playing session keeps playing, untouched; a paused session resumes through `play(Remote)`, restarting the host before the paused player). If the collection has not loaded yet, the start waits for the first load (one pending start, dropped by `stop`); an empty collection leaves the engine `Idle` with `playableCount = 0`, touches neither the player, the audio nor the service, and the activity keeps the placeholder. The engine never restores a session from storage: a new process is `Idle` until a launcher start.

Residual risk: a crash that makes the system re-create the activity from its original launcher intent without saved state looks like a launcher start. #5's end-of-night `finishAndRemoveTask` makes this rare; it is documented, not detected.

For on-device evidence, a shell start counts as a launcher start only with the launcher action and category, for example `am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n com.befeast.sleepmusic/.MainActivity`. `am start -n` alone carries no action and is passive by design.

## Session start: one press, audible

On a launcher start with something eligible, in this order (one test asserts the order on a shared event log):

1. Player volume 0, written at every start whatever the last session left. `ExoPlayerPort` keeps the value and applies it to the player it creates, so the first session of a process (the player does not exist before the first load and would start at volume 1) is silent from its first sample.
2. `AudioControl` (`StartVolumeSequence.observe`): read the `STREAM_MUSIC` index and mute state. Nothing is written (#34, Oleg's decision): the app never raises the volume, never lowers it and never unmutes, so the household level the remote left is the session level, and a muted stream or index 0 stays silent until the listener turns the volume up. The interface has no write methods, so no code path can change the stream. The `StartVolumeResult` (before and after the start step) goes to `SessionEvents` (#4 logs it; it is the sound-level evidence of PRD item 2). Until v0.1.0 this step unmuted the stream and set a start level (default 15 of 25); on a 15-step box at 1 that gave 9, loud enough to wake the room.
3. `PlayQueue.begin`: first track drawn and committed, lookahead drawn; timer armed: `endAt = now + timerMinutes`, fade `min(10 min, timer)`; `sessionId + 1`.
4. `PlayerPort.load(first, upNext)`, play.
5. Phase `Playing` published, so a service created next sees an active session.
6. `PlaybackHost.sessionStarted()` (`startService`; the activity is in the foreground, so this is allowed; Media3 promotes the service to the foreground once the player is ready and playing).
7. Tick loop started.

The 30 s session fade-in (`Fades.SESSION_FADE_IN_MS`, cubic) counts from the first `onPlaying`, so a slow first load does not shorten it. Every session repeats steps 1-2; no step, command or fade ever writes the stream volume.

## Selection, the lookahead and the MediaSession

### Queue

`PlayQueue` keeps `current` and one uncommitted lookahead `Pick` from the ported `ShuffleBag`:

- Eligibility comes from `ShuffleBag.eligibleIds(collection ids, collection.selection, store.blocked())`, recomputed on every draw. The engine converts `selectionFellBack` to an empty eligible list: an empty, unmatched or fully blocked published selection must never select other library tracks. This leaves an idle start untouched with `playableCount = 0`, or ends an active session with `NothingToPlay` when it next skips or exhausts its current track. The ported helper stays byte-identical.
- The lookahead is drawn with `played` and `lastId` as they would be once `current` has played, but is committed (`store.saveBag`) only when it becomes current. A track that was queued and never started does not count as played.
- A block of the lookahead, a refresh that drops it, or any change of the eligible set that excludes it redraws it. A like changes only future weights and keeps the lookahead.
- With an empty eligible set there is no lookahead; when the current track then ends or is skipped the session finishes with `NothingToPlay`. A single eligible track repeats (the bag's own cycle rule).

The engine mirrors the queue into ExoPlayer as a two-item playlist `[current, upNext]`. When ExoPlayer moves to the second item (end of track, reason `AUTO`; or `advance()`, reason `SEEK`), the port drops the first item and reports `onAdvanced`; the engine commits the pick, draws a new lookahead and `setUpNext`s it. Both cases go through the one `onAdvanced` handler.

### One path for next

Right on the remote, media key NEXT and every MediaSession next call `PlaybackControls.skip(source)`:

- `MainActivity.onKeyDown(DPAD_RIGHT)` -> `RemoteKeyMap` -> `skip(Remote)`.
- `SessionPlayer.seekToNext()`, `seekToNextMediaItem()` and the deprecated `next()` and `seekToNextWindow()` -> `skip(MediaSession)`. Media3 calls these for `MediaController.seekToNext()`, the platform session's `onSkipToNext` and service-intent media key NEXT (`MediaSessionStub.seekToNextForControllerInfo`). They never forward to ExoPlayer's own next.

`skip`: ignored when no session is active or a skip is already in flight; the transport ramp takes the gain to 0 over 0.6 s; the lookahead is re-validated (redrawn if no longer eligible); `PlayerPort.advance()`, called once per skip even if the player reports the move late; the new track starts at gain 0 under its 4 s edge fade-in; a paused session resumes. No lookahead -> finish with `NothingToPlay`. OK pressed during the skip ramp applies to the new track: it starts paused.

- Framework media key NEXT -> `PlatformMediaButtonGuard` -> `RemoteKeyMap` -> `skip(MediaSession)`, before any legacy controller identity lookup.

Resume: whenever the engine plays a paused player again (launcher tile, OK, media PLAY, a skip out of a pause), it first calls `PlaybackHost.sessionResumed()`. While paused Media3 leaves the foreground, and with the app in the background the system may stop the started service and with it the MediaSession; `ServicePlaybackHost` starts it again (a start of a running service only delivers another `onStartCommand`), so the resumed music has its service, session and foreground back. A resume inside the pause ramp, before the player paused, needs none.

Eligibility changes (never again, a refresh in #4) re-validate the lookahead at once and replace the queued player item, so when a track ends on its own the player always moves to the item the queue expects; `onAdvanced` then promotes the lookahead without re-validating it.

### Why a guarding ForwardingPlayer with a one-track lookahead

- The known defect: the session exposed only the current item and the stock next, so system next bypassed the shuffle bag. Routing next to the engine makes Right and system next the same function, so no-repeat and never-again hold for both by construction.
- The bag decides when a track is about to start: likes, blocks and #4 refreshes apply without rebuilding a playlist, and only tracks that really played enter `played`.
- The lookahead keeps ExoPlayer out of `STATE_ENDED` between tracks. Media3 1.4.1 keeps the service in the foreground only while the player is `READY` or `BUFFERING` with `playWhenReady`; a one-item player would leave the foreground at every track change and need to re-enter it, which Android 12+ restricts from the background.
- The session sees a real next item, so system UIs enable next.
- A full shuffled timeline was rejected: it needs a rebuild on every like, block and refresh, duplicates the bag's cycle rule, counts queued tracks as played, and invites controllers to jump the queue.

### SessionPlayer

`SessionPlayer(inner = the ExoPlayer, controls)` is the only player the MediaSession knows. The engine drives the same ExoPlayer through `ExoPlayerPort`, never through `SessionPlayer`, so the guard does not block the engine itself. State getters (timeline, metadata, position, playing) are forwarded unchanged.

| Calls from outside | Effect |
|---|---|
| `getAvailableCommands()`, `isCommandAvailable()` | the fixed `SessionPlayer.COMMANDS` (the granted set below), whatever the wrapped player offers |
| `play()`, `setPlayWhenReady(true)` | `controls.play(MediaSession)`: resumes a paused session, otherwise nothing |
| `pause()`, `setPlayWhenReady(false)` | `controls.pause(MediaSession)` |
| `seekToNext()`, `seekToNextMediaItem()`, `next()`, `seekToNextWindow()` | `controls.skip(MediaSession)` |
| `stop()` | `controls.stop(MediaSession)` |
| `prepare()`, every `seekTo*` and `seekToPrevious*`, `seekBack()`, `seekForward()`, `seekToDefaultPosition*` | nothing (`Util.handlePlayButtonAction` calls `prepare()` and `seekToDefaultPosition()` on a stopped or ended player) |
| media item edits, shuffle, repeat, speed, `setVolume`, device volume, audio attributes, `release()` | nothing: the playlist, the player volume and the lifecycle of the process-scoped player belong to the engine |

### SessionCallback

- `onConnect`: accept with player commands `PLAY_PAUSE`, `STOP`, `SEEK_TO_NEXT`, `SEEK_TO_NEXT_MEDIA_ITEM`, `GET_CURRENT_MEDIA_ITEM`, `GET_TIMELINE`, `GET_METADATA` (the same constant as `GET_MEDIA_ITEMS_METADATA`), `GET_AUDIO_ATTRIBUTES`, `GET_VOLUME`, `GET_DEVICE_VOLUME`, and no session commands. Service-intent media buttons go through the same per-controller grant (`MediaSessionStub.*ForControllerInfo`). Framework media buttons use the restricted engine path below. Device volume is never granted: volume belongs to the remote and the system.
- `onPlaybackResumption`: an immediately failed future, explicitly (the default does the same; the override makes it a tested contract).
- `onAddMediaItems`, `onSetMediaItems`: failed futures.
- Session activity: `PendingIntent` to `MainActivity` with action `com.befeast.sleepmusic.action.SHOW`, `FLAG_IMMUTABLE` (passive by the classifier).

### Empty-package framework media buttons (Media3 1.4.1)

The cached 1.4.1 AAR has no sources JAR. Inspection of its `classes.jar` with `javap -c -p` confirms this synchronous path on API 28–34:

`MediaSessionCompat.Callback.MediaSessionCallbackApi21.onMediaButtonEvent` -> `MediaSessionLegacyStub.onMediaButtonEvent` -> `MediaSessionCompat.getCurrentControllerInfo` -> `MediaSessionImplApi28.getCurrentControllerInfo` -> legacy `RemoteUserInfo(frameworkInfo)`.

That constructor throws `IllegalArgumentException("packageName should be nonempty")` for an empty package supplied by the framework. It runs before `MediaSessionImpl.onMediaButtonEvent`, hence before the public `MediaSession.Callback.onMediaButtonEvent` or `onConnect`: neither public hook can guard it. A platform token represents a controller endpoint, not the owning session, so it cannot install a callback. Removing the media-button receiver only affects cold starts; flags cannot reliably disable live buttons on API 28+.

`PlatformMediaButtonGuard.install` runs immediately after session construction, on the player looper, before the service adds the session. It obtains the owning platform session and existing framework callback through reflection on the **pinned Media3 library only** (`getSessionCompat`, `mImpl`, `getCallback`, `mCallbackFwk`, `getMediaSession`). It uses public Android `MediaSession.setCallback` with the same player looper; it neither reads nor writes hidden framework fields. The compat callback remains installed in its own implementation, retaining its identity and controller routing. All non-button framework callbacks delegate unchanged.

For media-button intents the guard never calls the legacy callback or resolves a controller package. Initial, uncancelled key downs route through `RemoteKeyMap` to `PlaybackControls` with source `MediaSession`; ups, repeats, unsupported keys and intents without a key are consumed without commands. No command can start an idle or finished session. This is preferable to catching a message-string exception after entering Media3, or disabling working remote transport altogether. It also needs no process-wide exception handler. Only this narrow button route bypasses Media3's controller grant; it exposes the same restricted commands available to the activity's remote handler, never queue edits or raw player access.

The reflection names are a deliberate dependency on 1.4.1 and are exercised against live service sessions by the tests. Builds currently do not minify; a future shrinker or Media3 upgrade must preserve or replace these names and rerun the tests. Installation catches reflection failures, runtime lookup failures (including null, cast and access failures), and linkage errors before changing the callback. It logs one warning and returns false, retaining the normal Media3 session so playback remains available; the empty-package workaround is unavailable in that fallback. The service owns the session before installation and releases it on destruction regardless of the result. A CI tripwire asserts that real installation returns true against the pinned library, so a broken reflection contract fails CI rather than silently losing the guard.

`PlatformMediaButtonGuardTest` reproduces the unguarded exception under Robolectric SDKs 29 and 34 using the actual framework `CallbackMessageHandler` with an empty-package `RemoteUserInfo`, then exercises the installed service guard through the same handler. Binder delivery is replaced with an in-process message; the framework callback and Media3 lookup are real. It verifies next follows the engine's lookahead, finished buttons do nothing, repeats/ups/unsupported buttons do nothing, valid-package media-button next reaches the engine, empty-package buttons after timer expiry do nothing, and valid platform transport next still delegates through Media3. Injected reflection, runtime and linkage failures verify the unchanged callback, one warning, usable service session and release on destruction. This is not device validation of every API or vendor remote.

Follow-up for a separate toolchain issue: the upstream [release notes](https://github.com/androidx/media/blob/release/RELEASENOTES.md), checked through 1.11.1, contain no identified fix for empty controller packages. The current release-branch `MediaSessionLegacyStub.onMediaButtonEvent` and legacy `RemoteUserInfo` constructor still contain the same lookup and empty-name rejection. Do not assume upgrading fixes it; confirm with this regression first. Media3 stays at 1.4.1 and compile/target SDK at 34; evaluating 1.5+ and SDK 35 belongs in that follow-up.

## Finished and stopped sessions stay finished

A session ends with the timer (`TimerEnded`), `stop` (`Stopped`: MediaSession stop, task removed), `NothingToPlay` or `PlaybackFailed` (three consecutive player errors; a single error skips to the next track, and a paused session stays paused: the next track is loaded paused and silent, since an error can surface while paused, for example from the preloaded lookahead). The finish sequence: tick loop cancelled, player volume 0, `stopAndClear()`, queue cleared, phase `Finished` with the reason, `SessionEvents.sessionEnded`, `PlaybackHost.sessionEnded()` (`stopService`; the service releases the session in `onDestroy`).

Nothing but a launcher start leaves `Finished`. Layers, each tested:

1. **Engine**: `play`, `togglePause`, `skip` and `stop` do nothing unless `state.isActive`, whatever the source.
2. **Activity**: media keys (`MEDIA_PLAY_PAUSE`, `MEDIA_PLAY`, `MEDIA_PAUSE`, `MEDIA_NEXT`; `MEDIA_STOP`, `MEDIA_PREVIOUS`, `MEDIA_REWIND`, `MEDIA_FAST_FORWARD`, `MEDIA_SKIP_*`, `MEDIA_STEP_*` to none) go to the guarded engine commands and are always consumed, on key down and key up, while the activity is in front, so they cannot fall through to another app's session.
3. **SessionPlayer**: the only object the MediaSession can call; play, prepare and seeks go to the engine or nowhere.
4. **No resumption**: in Media3 1.4.1 (`MediaSessionImpl.handleMediaControllerPlayRequest`) a play request takes the playback-resumption branch only if the player has no current item and offers `COMMAND_SET_MEDIA_ITEM` or `COMMAND_CHANGE_MEDIA_ITEMS`. `SessionPlayer` offers neither, so a play request on the emptied player becomes `Util.handlePlayButtonAction`, which ends in `SessionPlayer.play()` and layer 1. `onPlaybackResumption` also fails, should a later version reach it.
5. **No cold start**: no `androidx.media3.session.MediaButtonReceiver` in the manifest and no `MEDIA_BUTTON` filter on the service. Media3 1.4.1 (`MediaSessionLegacyStub` constructor) then falls back to the service: it builds a `PendingIntent.getForegroundService` to `PlaybackService`, addressed to the session's URI, but hands it to the platform session only below API 31. On API 31+ (the target box runs 34) no media-button receiver is registered at all, not even at runtime, so a media button reaches only a live session and cannot start a dead process. The service returns `START_NOT_STICKY`, `onGetSession` returns no session unless one is active, and a service started without an active session stops itself.

   Below API 31 the platform session holds that `PendingIntent`. A clean end releases the session, and `MediaSessionLegacyStub.release()` clears the receiver first. Only a session that was never released (the process died during a night: killed, crashed, reinstalled; the platform can also keep the receiver across a reboot) leaves it behind; the next media key then calls `startForegroundService` on `PlaybackService` in a process with no session. The service must not stop without `startForeground`, or the system crashes the app; so the idle stop below API 31 enters the foreground with a low-importance notification (channel `idle_stop`), leaves it at once with `STOP_FOREGROUND_REMOVE`, and stops. No session is built, nothing plays.

## Timer and fade loop

- Choices `SleepTimer.CHOICES = 30, 45, 60, 90, 120, 180` min, default 120, stored as `timer_min`; a stored value outside the choices reads as 120. The sleep fade is `min(Fades.DEFAULT_SLEEP_FADE_MS, timer)`, so 10 min for every choice.
- The timer runs on `Clock` (elapsed realtime, which counts deep sleep) and keeps running while paused. A paused session at the end finishes like a playing one. While playing, ExoPlayer's wake lock keeps the tick running; a paused player holds none, so a box that sleeps while paused ends the night only at the first tick after it wakes, possibly in the morning. `SessionState` therefore carries `timerEndAtMs` and, when finished, `endedAtMs`; `timerEndLateMs` (for `TimerEnded` only) is normally under one tick and lets #5 keep the sleep route away from a late end.
- `setTimerMinutes` persists; during a session it re-arms the countdown from now. If the fade had begun, the sleep factor climbs back to 1 slew-limited (full scale in 3 s), never in one jump.
- A Clock night (#65) runs Clock's music minutes (capped at the window end, see below), and `SessionState.timerMinutes` shows that night's chosen minutes while it plays, pauses and fades. A pick during that night re-arms the same night from now, like any pick, and `timerMinutes` then shows the pick; it never writes `timer_min`, and the night stays Clock's (same request, no TV off, closes for Clock). When the night ends, the menu shows the stored choice again. The TV off row of a Clock night shows that night's «off» (a Clock night never turns the TV off) and a pick there changes nothing, never `tv_off` (#69). The same holds while an accepted Clock request still waits for the collection on first run: the menu already shows the waiting night's minutes and «off», a pick writes neither `timer_min` nor `tv_off`, and the request keeps its own minutes; a cancelled request or a later launcher start shows the stored settings again (#70). `SessionState.clockNight` marks both cases, so the TV off row reads «off · Clock night» (#73). Clock's stop for a request that still waits cancels it: no night starts after the person woke (#72). Dremla's own screen stays as it was (the download or an idle scene); unlike a playing night it does not close itself, because no night ever started. A waiting request is checked against its window end again when the collection arrives: once Clock's sleep window has ended (the same rule as at acceptance, `windowEndMs <= now` on the wall clock), it is dropped and no night starts (#75). The same check runs when the menu opens, on a menu pick and when Clock sends a new request, so after the window end the menu shows the stored settings and Clock's next request is accepted even before the collection arrives. A Clock night's music never runs past Clock's window end, the planned wake-up: its countdown is `min(music minutes, time left to the window end)`, a pick during the night is cut the same way (a pick that cannot move the capped end leaves the running fade alone), and the fade (the last `min(10 min, timer)`) therefore ends by the window end; after the music, Clock's protected screen stays until «Я проснулся» (#77). The menu's «current» and dot keep showing the night's chosen minutes (Clock's music minutes or a pick); the night screen's countdown shows the capped time left, and the night log records the capped minutes. Clock's acceptance reads the wall clock once: a request that passed the window check also starts, and a request that still cannot start its night now (nothing to play, offline or online) is refused to Clock with the reason instead of being accepted silently, without spending its request id (#78). Refusal texts returned to Clock: `unknown mode`, `invalid music duration`, `sleep window already ended`, `sleep window more than a day away`, `missing request id`, `repeated request`, `a night is already playing`, `nothing to play`; `SessionEntryActivity` returns them in `EXTRA_REFUSAL` with `RESULT_CANCELED`.
- Tick: every 50 ms while a session is active (a `delay` loop in the engine's scope; `Dispatchers.Main.immediate` in production, a `TestScope` in tests with `Clock` = virtual time). Times come from the clock, not from counting ticks, so a late tick does not stretch the night.
- Volume: only the **player** volume moves (`ExoPlayer.volume`, linear amplitude), never the stream volume. Per tick:

  `gain = Fades.combined(sessionFadeIn, trackEdge, sleep, loudness, transport)`

  - `sessionFadeIn = Fades.fadeIn(now - firstPlaying, 30 s)`, 0 before the first `onPlaying`;
  - `trackEdge = Fades.trackEdgeGain(position, duration)` (4 s in, 6 s out), duration from the player or the manifest;
  - `sleep = Fades.sleepGain(remaining, fade)`, applied downwards exactly and upwards slew-limited;
  - `loudness = Fades.loudnessGain(track.iLufs)`;
  - `transport`: the pause (1.2 s out), resume (2 s in) and skip (0.6 s out) ramps on the same cubic curve.

  `setVolume` is called only when the value changes by more than 1e-5.
- No steps: the largest change of the cubic sleep curve per 50 ms tick is 3 x 50 / (10 x 60 x 1000) = 2.5e-4 of full scale, near unity. The test bound is 1e-3 per tick, the curve must be non-increasing and the last value exactly 0.
- At `remaining <= 0` the gain is already 0; the finish sequence runs in the same tick, so playback and the service stop at the end.

## Remote keys

`RemoteKeyMap` (`onKeyDown(keyCode, repeatCount)`, `onKeyUp(keyCode, tracked, canceled)`, `consumesLongPress(keyCode)`) is pure; `MainActivity.onKeyDown`, `onKeyUp` and `onKeyLongPress` are the only key hooks (no `dispatchKeyEvent` override), so #6's focused Compose controls see D-pad keys first and the transport mapping applies only to keys the UI did not consume.

| Key | Command |
|---|---|
| `DPAD_CENTER`, `ENTER`, `NUMPAD_ENTER` | down: tracked, consumed; release of a tracked, uncancelled press: `togglePause(Remote)`; long press: consumed, no command (#6's menu) |
| `MEDIA_PLAY_PAUSE` | `togglePause(Remote)` |
| `MEDIA_PLAY` / `MEDIA_PAUSE` | `play(Remote)` / `pause(Remote)` |
| `DPAD_RIGHT`, `MEDIA_NEXT` | `skip(Remote)` |
| `MEDIA_STOP`, `MEDIA_PREVIOUS`, `MEDIA_REWIND`, `MEDIA_FAST_FORWARD`, `MEDIA_SKIP_*`, `MEDIA_STEP_*` | consumed, no command in #3 |
| `VOLUME_UP`, `VOLUME_DOWN`, `VOLUME_MUTE`, `MUTE` | none: `onKeyDown` returns `super.onKeyDown`, the system changes `STREAM_MUSIC` |
| anything else | none (`super`) |

OK acts on its release: `onKeyDown` calls `event.startTracking()`, and `onKeyUp` toggles only when the release `isTracking` and is not `isCanceled`. `onKeyLongPress` consumes a long press of OK, which makes the framework cancel the release, so a long press never pauses on the way to #6's menu (#6 opens the menu in that hook). A release whose press went to a focused control (the press was not tracked here) does nothing. Media keys are also consumed in `onKeyUp`, so a lone key-up never reaches another app's session. Auto-repeat (`repeatCount > 0`) of a mapped key is consumed without a command. `volumeControlStream = AudioManager.STREAM_MUSIC`. OK in `Idle` or `Finished` does nothing in #3. Back keeps the system default; #6 maps it.

## Likes and never again

- `toggleLike()` flips the like of the current track (weight 2 in later draws), persisted at once.
- `neverAgain()` persists the block (and drops the like), redraws the lookahead if needed and skips the current track through the same `skip` path. Blocked ids are filtered out of every draw, so they never play again, also after a restart or reboot. Unblocking is out of scope for #3.

## Persistence

`SharedPrefsPlaybackStore`: one `SharedPreferences` file `playback` (`MODE_PRIVATE`, app-private storage).

| Key | Type | Default | Write |
|---|---|---|---|
| `schema` | int | 1 | with the first write |
| `liked` | string set | empty | `commit` |
| `blocked` | string set | empty | `commit` |
| `bag_played` | string set | empty | `apply` |
| `bag_last` | string | none | `apply` |
| `timer_min` | int | 120 | `commit` |
| `start_volume_level` | int | (v0.1.0 only) | no longer read or written since #34; a stored value stays as left |

User decisions use `edit(commit = true)` (core-ktx), so a process killed right after a block still has it; bag progress uses `apply`, where a lost write only allows one repeat. Returned sets are copied, never mutated. #5 adds `tv_off` (default true) to the same file.

## Privacy

Preferences never leave the device (PRD item 13): `android:allowBackup="false"` stays, and the application also gets `android:fullBackupContent="@xml/backup_rules"` (API 30 and lower) and `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+, `tools:targetApi="31"`). Both exclude the `root`, `file`, `database`, `sharedpref` and `external` domains (and the `device_*` domains in the extraction rules), in both `cloud-backup` and `device-transfer`, with no `include`. `allowBackup="false"` alone does not stop device-to-device transfer on Android 12+.

## Manifest

- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` (ExoPlayer `C.WAKE_MODE_LOCAL` keeps the CPU awake while playing local files). No `INTERNET` (#4 adds it), no `POST_NOTIFICATIONS` (Media3's media notification is exempt, and TV hides it), no `RECEIVE_BOOT_COMPLETED`. Media3 1.4.1 merges `ACCESS_NETWORK_STATE` from `media3-common` and `media3-exoplayer`; it carries no traffic and stays. AndroidX Core also merges the app-scoped `com.befeast.sleepmusic.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `ManifestTest.permissionsArePlaybackOnly` asserts exactly these five permissions as well as explicit readable absence checks.
- `<application android:name=".SleepMusicApp" ...>`.
- `MainActivity`: `android:launchMode="singleTask"`, launcher filter unchanged.
- `<service android:name=".playback.PlaybackService" android:exported="true" android:foregroundServiceType="mediaPlayback">` with the intent filter `androidx.media3.session.MediaSessionService`. Exported so system controllers can bind; lint's `ExportedService` warning is expected. No other components.
- ExoPlayer: `AudioAttributes(USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC)` with `handleAudioFocus = true` (focus loss pauses; the engine sees `onPausedBySystem` and shows `Paused`), `setWakeMode(C.WAKE_MODE_LOCAL)`, created on the main thread.
- `PlaybackService`: the MediaSession (id `night`, over `graph.sessionPlayer()` with `SessionCallback`) is built and added on demand, from `onCreate`, `onStartCommand` (before `super`, so a media button in the intent finds it) or `onGetSession`, as soon as a session is active; so an instance created while idle and kept alive by a bind still hosts the night that starts later. Without an active session nothing is built and the service stops itself (below API 31 after entering and leaving the foreground, layer 5); `onGetSession` returns the session only while a session is active; `onStartCommand` returns `START_NOT_STICKY`; `onTaskRemoved` calls `controls.stop(System)`; `onDestroy` releases the session (the ExoPlayer is process-scoped and outlives it).

## Seams for later issues

- **#4 collection**: implements `CollectionSource` over the download and refresh (`collection` emits the new set; the engine recomputes eligibility and redraws a lookahead that dropped out; the playing track keeps playing, `Library.refresh(keepId = state.track.id)`). The pending launcher start covers "first run downloads, then plays". The night log implements `SessionEvents` (`uiEntry(verdict)`, `sessionStarted(sessionId, timerMinutes, StartVolumeResult)`, `trackStarted`, `sessionEnded(reason)`, `playbackError`); production logs them to logcat (tag `SleepMusic`) until then. `dumpsys` hangs off `PlaybackService.dump`; the service runs only during a session, so #4 has to decide how the log is reachable after the night.
- **#5 night**: observes `Phase.Finished` with `EndReason.TimerEnded` to run the sleep route, and checks `timerEndLateMs`: an end that came long after `timerEndAtMs` means the box slept through it while paused and woke later, possibly in the morning; adds `tv_off` to `PlaybackStore`; uses `sessionId` and `phase` for the black screen 10 s after start; the morning rules build on the launch verdicts and the finished-session guards. The session already survives a black or sleeping screen through the foreground service and the wake lock; screensaver and inattentive sleep handling stay with #5.
- **#6 UI**: renders `SessionState` (`track.title`, `track.artist`, `timerRemainingSec` through `remainingParts`, `phase`, `playableCount`); the menu calls `setTimerMinutes` (`SleepTimer.CHOICES`), `toggleLike`, `neverAgain` with `CommandSource.Menu`; Back and the menu key are mapped there; a long press of OK opens the menu from `MainActivity.onKeyLongPress`, which already consumes it, so OK never pauses on the way.

## Dependencies

All within the pins; no SDK packages, no version changes of anything already resolved.

| Configuration | Coordinates | Note |
|---|---|---|
| `implementation` | `androidx.media3:media3-exoplayer:1.4.1` | catalog entry exists (`libs.androidx.media3.exoplayer`) |
| `implementation` | `androidx.media3:media3-session:1.4.1` | catalog entry exists (`libs.androidx.media3.session`); Guava 33.0.0-android comes with `media3-common` (API scope) for `Futures` |
| `implementation` | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` | new catalog entry; the version Compose and lifecycle already resolve, made explicit |
| `testImplementation` | `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3` | virtual time for the tick loop |

No `media3-test-utils`: the engine tests use `FakePlayerPort`, the session tests a small `SimpleBasePlayer` fake (`FakeMediaPlayer`) as the player the session wraps; the production-wiring tests run the real `ExoPlayerPort` over that fake. Neither library ships native code or Compose path iteration.

## Test plan

Test sources in `app/src/test/java/com/befeast/sleepmusic/playback/`; shared fakes in its `testing/` subpackage: `FakePlayerPort` (records calls into an event log, position from the clock, `completeCurrentTrack()`), `FakeAudioControl`, `InMemoryPlaybackStore`, `FakeCollectionSource`, `RecordingHost`, `RecordingEvents`, `RecordingControls`, `FakeMediaPlayer` (`SimpleBasePlayer` playlist player), `EngineHarness` (engine on a `CheckedTestScope`, the clock is virtual time, seeded `Random`, optionally the real `ExoPlayerPort`), `TestApp` and `TestGraph` (`Application` + `PlaybackProvider`), `MergedManifest` (the merged manifest AGP hands to Robolectric), `Tracks`. JVM tests need no Android; Robolectric tests run with `sdk = 34`. No real sleeping anywhere.

| Test class | Kind | What it checks | PRD / AC |
|---|---|---|---|
| `LaunchClassifierTest` | JVM | launcher start for LEANBACK_LAUNCHER and LAUNCHER (create, new intent); passive for saved state, history flag, no category, no action, `SHOW`, null intent | 1 / AC-2 |
| `MainActivityLaunchTest` | Robolectric | fresh launcher create -> one start; resume (pause, resume) -> no further start; wake (stop, restart, focus back) -> none; config-change recreate -> none; create with saved state (process restart) -> none; recents and explicit start without action -> none; `onNewIntent(SHOW)` -> none; `onNewIntent(launcher)` -> start; with the real engine over a loaded collection all those passive paths load nothing and only the tile pressed afterwards loads the player, once (a configuration change during the night neither restarts nor stops it); with the real engine and the real local source over an empty app directory a launcher start leaves it `Idle` and the activity shows the placeholder without a crash | 1, 7 / AC-2, AC-9 |
| `NoAutoplayPathsTest` | Robolectric | no receiver for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` or `MY_PACKAGE_REPLACED`, no `RECEIVE_BOOT_COMPLETED`; `onStartCommand` returns `START_NOT_STICKY`; a service created with an idle engine never touches the player and stops itself; a fresh engine over a populated store is `Idle` with no port, audio or host calls | 1, 12 / AC-2 |
| `SessionStartTest` | JVM | order: player volume 0, then load and play, then host start, with no stream write; 30 s fade-in from the first `onPlaying`; every passive verdict (each non-launcher reason, on create and on new intent, collection loaded or still loading) leaves the real engine `Idle` with no pending start; active session ignores a second launcher start; launcher start before the collection loads starts after the load; empty collection -> `Idle`, no calls | 1, 2 / AC-2 |
| `StartVolumeSessionTest` | JVM | #34: a stream far below the old default (1 of 15, 1 and 3 of 25) stays put; a high level is not lowered; muted stays muted, 0 stays 0, the session still starts; observed at every session start and never changed by pause, resume, skip, timer edits or the fade; before/after recorded | 2 / AC-2 |
| `PlayQueueTest` | JVM | lookahead not committed until it starts; no repeat within a cycle; block of the lookahead redraws it; empty eligible -> no lookahead; state round trip through the store | 3 / AC-5 |
| `SystemNextRegressionTest` | Robolectric | `SessionPlayer.seekToNext`, `seekToNextMediaItem`, `next`, `seekToNextWindow` call `skip(MediaSession)` and never the inner player's next; the track order equals the Right-key order for the same seed; no repeats in a cycle, blocked tracks never chosen (known defect: system next ignored the bag) | 3 / AC-5 |
| `MediaSessionRoutingTest` | Robolectric | real `MediaSession(SessionPlayer)` with an in-process `MediaController`, the engine on the real `ExoPlayerPort`: granted commands; `seekToNext` -> engine skip to the bag's lookahead; pause and play; `play` after the end -> nothing; queue edits refused; `onPlaybackResumption`, `onAddMediaItems`, `onSetMediaItems` fail; the service hosts the session; media-button intents through `PlaybackService.onStartCommand`, both without a URI (through `onGetSession`) and addressed to the live session by its URI (as the platform sends them below API 31): NEXT during a session skips, PLAY, PLAY_PAUSE and NEXT after the timer end do nothing | 3, 7 / AC-5, AC-9 |
| `FinishedSessionGuardTest` | Robolectric | after the timer end and after `stop`: `SessionPlayer.play`, `setPlayWhenReady(true)`, `prepare`, `seekToNext`, `seekToDefaultPosition` and `togglePause`/`play`/`skip` from every source leave `Finished`, with no port, audio or host calls (known risk: media buttons restarting a finished session) | 7 / AC-9 |
| `SleepTimerTest` | JVM | default 120, choices, invalid stored value -> 120, fade 10 min; curve non-increasing, exactly 0 at the end, max step per 50 ms tick <= 1e-3; re-arm inside the fade climbs back slew-limited | 4 / AC-6 |
| `TimerFadeEngineTest` | JVM | 120 min on virtual time with one long track: player volume over the last 10 min non-increasing, max step <= 1e-3, last value 0; stream volume written only at the start; at the end `Finished(TimerEnded)`, `stopAndClear`, host stop, loop cancelled; paused at the end still finishes; persisted timer 45 used by the next session | 4, 15 / AC-6, AC-13 |
| `TransportTest` | JVM | OK pauses after the 1.2 s ramp and resumes with the 2 s ramp; Right skips after the 0.6 s ramp; skip while paused resumes; a second skip in flight is ignored; skip with nothing eligible finishes with `NothingToPlay`; a resume or skip out of a paused player calls `sessionResumed` before `play`; a player error while paused or inside the pause ramp loads the next track paused and silent | 8 / AC-10 |
| `RemoteKeyMapTest` | JVM | the key table above; OK toggles on a tracked, uncancelled release only and its long press is reserved; volume and mute keys map to nothing; repeats ignored | 8 / AC-10 |
| `MainActivityKeysTest` | Robolectric | `onKeyDown(VOLUME_UP/DOWN/MUTE)` returns false and sends nothing; `volumeControlStream == STREAM_MUSIC`; OK toggles on release, Right skips on press, also through the window's real key dispatch; a long press of OK through the window never toggles; an OK release whose press went elsewhere does nothing; media keys consumed | 8, 7 / AC-10, AC-9 |
| `LikesAndBlocksTest` | JVM | like toggles and persists; never again persists, drops the like, redraws the lookahead and skips a playing track; blocked ids never drawn over many sessions with a new engine on the same store | 3, 9 / AC-5 |
| `SharedPrefsPlaybackStoreTest` | Robolectric | defaults; likes, blocks, bag and timer survive a new store instance; invalid timer sanitised | 9, 15 / AC-13 |
| `SessionStateTest` | JVM | phases Idle -> Playing -> Paused -> Playing -> Fading -> Finished(TimerEnded); title, artist, remaining seconds; no emission without a visible change; `timerEndAtMs` published, an on-time end at most one tick late, an end after the box slept through it while paused hours late, only a timer end can be late | 4, 8 (seam for #5, #6) |
| `PrivacyConfigTest` | Robolectric | `FLAG_ALLOW_BACKUP` unset; both rule files exclude every domain in every section with no `include`; the manifest references both | 13 / AC-12 |
| `ManifestTest` | Robolectric | `PlaybackService` exported, type `mediaPlayback`, resolves the `MediaSessionService` action; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` requested; no `INTERNET`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`; no `MEDIA_BUTTON` receiver or filter; `MainActivity` is `singleTask` | 1, 7, 13 |
| `ExoPlayerPortTest` | Robolectric | no player before the first load; a volume set before the player exists applies to the player the load creates; the first session of a process, real engine over the real port, starts at player volume 0 and only rises under the 30 s fade-in curve; `[current, upNext]` mirroring; a skip and a natural track end both arrive as one `onAdvanced` with the played item dropped; end with nothing queued; only pauses the engine did not ask for count as system pauses; errors; stop and clear | 1, 3 / AC-2, AC-5 |
| `PlaybackServiceTest` | Robolectric | below API 31 (`SDK_INT` set to 29 on the API 34 runtime) an idle service enters the foreground, leaves it removing the notification and stops, building no session; from API 31 it stops without a foreground; an instance created while idle builds its session once on the next start or bind after a night starts; no session for anyone after the end | 7, 15 / AC-9 |
| `LocalCollectionSourceTest` | Robolectric | a fresh install loads an empty collection; only tracks whose file is present are playable; an unreadable manifest loads empty | seam for #4 |

The in-process `MediaController` connects to the session under Robolectric 4.13, so `MediaSessionRoutingTest` needs no fallback. What a system controller or the remote does on the real box (the platform session, the media notification, the foreground state) remains on-device evidence.

Existing gates stay green: the placeholder golden scene is unchanged (the activity still renders `PlaceholderScene(PlaceholderState())`), lint has no new disabled checks, and hygiene, forbidden-API and kit-port checks pass.

## Known gaps

- **API 28-30 media buttons after a lost session.** Below API 31 a media key after a process died mid-night starts the service in a process without a session; the idle stop enters and leaves the foreground, so the app does not crash and nothing plays (layer 5). Robolectric does not model the system's foreground-start check, so the absence of the crash is on-device evidence on an API 28-30 box; the target box runs API 34, where the path does not exist.
- **Background start of the service.** `ServicePlaybackHost` uses `startService`, allowed because a launcher start, and a resume from the remote, happen while the activity is in front. A start that waits for the collection (#4's first download) and fires after the listener left the app, or a resume through a still-living session while the app is in the background after the system stopped the service, would be refused; the refusal is logged and the music plays without the foreground service. #4 decides whether a pending start survives the activity leaving.
- **Paused through a sleep.** A paused player holds no wake lock, so the night can end late (see [Timer and fade loop](#timer-and-fade-loop)); the state reports how late, the decision is #5's.
- **Seeding the local collection.** `LocalCollectionSource` reads `files/collection` once per process. After seeding the directory on a device (before #4's download exists), force-stop the app before the launcher start, or a running process keeps its empty collection and plays nothing.
- **Timer on the device before #6.** Without the menu the timer can only change through the stored preference, so an on-device check of the end of the night takes the default 120 min (or a debug build with a seeded preference).

## Not in this issue

Collection download and refresh, the night log and `dumpsys` output (#4); black screen, sleep routes, `TV off`, morning close, screensaver and inattentive-sleep handling (#5); every visible scene, the menu, Back, Left and what a long press of OK opens (#6); unblocking; crossfade (PRD non-goal). Until #4 lands a device has no collection, so on-device checks of this issue need a debug build whose app-private collection directory the operator seeds, followed by a force-stop of the app.

### Review regression coverage

`CheckedTestScope` uses a supervisor, a standard test dispatcher and a `CoroutineExceptionHandler` that records failures. `EngineHarness.advance` / `settle` and construction check failures; the two activity test scopes check them in `@After`. Standalone `TestScope` is unsuitable here: without `runTest` it does not fail the test, and it refuses a custom exception handler. `CoroutineFailureTest` deliberately throws in a child to prove both harness checks fail with the original cause.

`ProductionAdaptersTest` runs on SDK 34: `SystemAudioControl` only reads the real shadowed music stream (index 1, 0 and muted cases); its recording shadow proves no `setStreamVolume` or `adjustStreamVolume` call reaches any stream and every stream keeps its index and mute state. `ServicePlaybackHost` starts, resumes and stops the correct service component. `AppPlaybackGraph` retains its production wiring while accepting an internal test scope, clock, collection and player factory; its guarding player drives engine skip, and a live service's task removal ends that engine with `Stopped`. Production defaults remain the main dispatcher, elapsed-realtime clock, local collection and ExoPlayer.

New methods in `SessionStartTest` cover launcher resume without a new session, track or timer, the host-before-play order, rejection of empty/unmatched/blocked selection fallback, and `NothingToPlay` after blocking the last selected track. These map respectively to PRD 1/8, 3 and 3/9. The platform guard tests map to PRD 3/7/8/15, adapter tests to 2/3/7/8/15, exact manifest permissions to 13, and coroutine failure detection to 15.

## Online playback (#54)

The menu's Music source choice persists opt-in; Downloaded remains default. Changing source
stops playback, leaves offline files and preferences intact, and requires a new launcher start.
`QueueItem` accepts a local file or a transient resolved stream plus request headers and resume
position. Media3 data-source requests receive those headers only in memory. Online resolution
runs on IO, guarded by session and attempt tokens; skip, stop, expiry, source changes and service
destruction invalidate pending work and stale player callbacks.

`OnlineNight` is driven by real engine callbacks, with first audio confirming resolve/resume
success. Start retry and position-preserving resume share three attempts per night. The streak
counts distinct failed track IDs since the last track end; repeated failures of an ID count once.
Covering the current eligible selection becomes silent unavailable while the same timer continues;
only track end clears the streak. Media HTTP 403/410 maps to network for bounded re-resolution.
No network recovery observer starts playback. The silent online service remains foreground and
holds a per-night partial wake lock, including while unavailable, so errors do not abandon the
timer. The lock releases when the night ends or stops (including a source switch), even if a
controller keeps the service bound; a new online night reacquires it. Destruction also releases it.
The menu remains explicitly reachable during online blackout; opening it is a remote action.

Catalog gain only attenuates the existing envelope, and AudioControl still has no system-volume
write API. Host fake tests establish transitions and handoffs, not real decoding, audibility,
wake behavior or API/ABI runtime support. See the [client contract](collection-client-contract.md).
