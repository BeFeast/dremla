# Collection and night log (issue #4)

Implements PRD 10–14, AC-3, AC-4, the collection portion of AC-11, and AC-12.
Sleep routes and screensaver handling belong to #5; the finished download/menu visuals to #6.

## Collection state and playback

`AppPlaybackGraph.downloads` exposes `DownloadCollectionSource.status`, `collection`, and
`refresh()` (also retry). The engine consumes the existing `CollectionSource` interface.

- Loading: read app-private `files/collection/manifest.json` on the IO dispatcher. If selected
  local files exist, publish them and Ready immediately, with no network request. Refresh is explicit.
- First download: publish Downloading and per-file progress, retaining null `collection` until
  the entire selection has downloaded and the manifest commits. Null keeps the engine's pending
  launcher request waiting. FirstRunError keeps null and offers retry. The temporary placeholder
  says what failed; OK retries. No exception text or network address appears in the UI.
- Refresh: keep the current playable collection throughout Downloading. Publish the new selection
  only after success. RefreshError leaves the old collection and active playback alone; #6 can
  render the non-blocking notice and call `refresh()` from its menu.
- Only a launcher start grants a pending start. Leaving the activity cancels that pending request
  through `cancelPendingStart()`, without ending an active night. Download completion after leaving,
  process recreation, diagnostic starts and passive entries never grant a new start.
- Startup and refresh are serialized. A refresh requested while one is running is ignored. Likes,
  blocks, shuffle progress and playback preferences live in the separate playback store; downloading
  never writes them. Blocked selected tracks still download, but the engine excludes them.

The manifest includes the whole catalogue, but production passes `selectionOnly = true` to
`Library.refresh`. Missing, empty or unknown selection IDs fail closed. The original all-track
library entry remains for the ported regression tests; production never calls that mode.

## Integrity and failure recovery

The only production base is `https://html.me.uk/t/dremla/collection/`. Every connection, including
redirect hops and absolute file references, must be HTTPS to that exact host on the standard port,
without user info. Downgrades fail before opening the redirected connection. `source_url` and
`preview` are metadata and are never fetched. The manifest disables cleartext traffic. The library's
literal loopback exception exists for the local HTTP fixture tests; production exposes no configurable
URL. `nextHop` retains its ported generic redirect-resolution contract, while `open` enforces origin.

Every selected track requires a 64-digit SHA-256. Local filenames contain validated IDs, content
hashes and extensions; new revisions cannot overwrite files used by the old manifest or player.
Downloads overwrite `.part` files, stream until EOF, calculate SHA-256, check the manifest size when
present, and rename only verified files. No Range or Content-Length is required. Already verified
complete files are rehashed and reused, so a killed process retries only unfinished files from byte
zero. A bad digest is removed; a caught IO failure removes its `.part`; process death can leave one for whole-file retry.

Before downloading, the validated refresh manifest is saved atomically as `manifest.pending.json`.
A partial download does not commit a manifest. Completed files remain for the next retry, including
after process death on first run or during refresh. Startup retains files of either the committed or
pending selection by revisioned name and listed size, without hashing them: a file gets its
`ID.SHA256.ext` name only after its digest was verified, and re-hashing the whole collection delayed
the first sound of every cold start (#81). A track without a listed size is kept only by its digest;
a track without a valid SHA-256 has no revisioned name and its file is removed. Corrupt or
unreadable pending state is treated as absent. The
manifest is written to its own `.part`, and a failed rename is an error. Production refresh never prunes audio: an idle observation can race a launcher start.
A successful commit removes the pending manifest. The next process startup prunes obsolete revisions
against both manifests before publishing
any collection, when no playback session can yet select a file. Failed downloads,
manifest writes and renames preserve the prior manifest and its audio. The Library.kt change record
in `kit-port.sha256` names the latest deliberate Library change (#81; the earlier #4 change is in git
history); LibraryTest is unchanged.

## Private night journal

`NightLog` implements `SessionEvents`; the application shares one instance with playback and dumps.
`files/night-log.json` is written with Android `AtomicFile` after each event. It retains the last
14 **sessions** (one night per start), including an active session, rather than calendar dates.
Each records epoch-millisecond start/end timestamps, timer minutes, numeric volume evidence, track
IDs and fixed error codes. No artist, device identifier, user preference list, exception message,
URI or file path is recorded. No data leaves the device, and backup remains disabled.

A process interrupted before the end leaves an open entry. The next process marks it
`ProcessInterrupted` at observation time: this is not a claim about the precise time of death and
never resumes playback. Storage errors do not crash playback; the dump reports `storage=error`.
Each night is capped at 4096 events, with an explicit truncation marker, to bound private storage.
Writes are synchronous and small in normal use; device latency remains an on-device check.

Release and debug use the same `PlaybackService.dump` implementation. Example format:

```text
Dremla night log v1 (last 14 nights; timestamps epoch-ms)
storage=ok nights=1
night start=1000 end=3000 reason=TimerEnded timerMinutes=120
  volume={"before":5,"after":15,"mutedAfter":false,"fixed":false}
  2000 track=amb-001
  2000 error=ERROR_CODE_IO_UNSPECIFIED
```

During playback, use `adb shell dumpsys activity service com.befeast.sleepmusic/.playback.PlaybackService`.
After the service has stopped, first use:

```sh
adb shell am start-foreground-service -n com.befeast.sleepmusic/.playback.PlaybackService -a com.befeast.sleepmusic.action.DUMP_NIGHT_LOG
adb shell dumpsys activity service com.befeast.sleepmusic/.playback.PlaybackService
```

The diagnostic action holds a silent foreground notification for 60 seconds, then removes it and
stops itself. It creates no playback graph, collection client, player, media session or night. It
returns `START_NOT_STICKY` and is never restarted automatically. Every diagnostic start promotes the service, including an existing paused/demoted session.
At timeout it removes the diagnostic notification and returns notification ownership to Media3 for
an active session, or stops a finished service. It never changes transport or replaces a session. This action is exported along
with the existing service, but exposes no journal through the intent or binder; reading the dump
requires the platform's dumpsys privileges. Normal starts and binds retain the idle-stop behavior;
`onCreate` alone now waits for the start intent or bind to distinguish diagnostics from playback.
These commands are operator verification instructions, not executed by this implementation task.

## Privacy gate and verification

`writeRuntimeDependencies` emits the union of resolved debug/release runtime and core-library-desugaring coordinates, including
versions. The latter includes both the NIO library and its configuration artifact at 2.1.5.
`check-privacy.sh` requires an exact match to the reviewed `runtime-dependencies.txt` allowlist and
fails on unreviewed local file dependencies (including JAR/AAR files and file trees) and
rejects telemetry/analytics/crash-reporting identifiers even if added to that list. The list contains
AndroidX UI/media/support libraries, Kotlin/coroutines, Guava, annotations, and the explicitly
reviewed NewPipe v0.26.5 graph (nanojson, jsoup, protobuf-javalite, jsr305, Rhino/rhino-engine).
`docs/runtime-dependencies.txt` records exact resolved versions; changing one requires review. AndroidX tracing is
local platform tracing, not a network reporting SDK. A dependency addition requires explicit review.
The same gate checks literal endpoints in shipping Kotlin, Java and XML; the HTTPS collection
host, XML schema declarations, and one exact canonical YouTube watch-URL builder in
`online/NewPipeResolver.kt` are accepted. Other YouTube literals/paths/files remain forbidden. It is a static check, not a proof against
obfuscated or dynamically assembled endpoints. Runtime origin enforcement complements it.
`test-privacy.sh` covers clean input, foreign hosts, cleartext, a host-name suffix attack, WebSocket,
an unreviewed transitive, forbidden telemetry, missing graph evidence, and executable local JAR, AAR and fileTree rejection fixtures. Both run in verify-outcome.

New Robolectric tests use an EOF-delimited loopback fixture, fake clocks and virtual coroutine time:
`CollectionDownloadTest`, `CollectionSourceTest`, `NightLogTest`, `NightLogServiceTest`. Existing
LibraryTest, playback no-autoplay tests, permission checks and golden scenes remain gates. The short
operator test is a real first download, an offline launcher start, a refresh outage during playback,
and dumps before and after night completion. Verify by ear and advancing position, not playback
state alone. No device or adb operation is part of this change's automated verification.

## Opt-in online source (#54)

Downloaded collection remains the default and keeps its origin enforcement. Online mode uses a
separate catalog repository and a lazy downloaded-source factory: online launch never invokes
the offline loader/pruner/downloader. Source selection is explicit and persisted in the menu;
switching stops the night and waits for a launcher start. Online shuffle progress is separate.
Only the resolver/player online path can contact extraction and media endpoints. No collection
origin rule or zero-native-library APK check is relaxed. See the
[client contract](collection-client-contract.md#implemented-client-slice-54) for failure behavior,
the unexecuted catalog delivery proposal, and host/runtime verification limits.
