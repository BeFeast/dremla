# Dremla — Product Requirements (v0)

Status: approved for implementation by the promotion plan that created this repository.
A disposable prototype and its written receipt informed this document; it is a delta on that evidence, not a copy of the prototype.

## Outcome

After an approved Forgejo release is installed on the bedroom SEI600GO Android TV box, launching Dremla with the remote immediately plays audible soft ambient or calm piano from the listener's offline collection, the picture goes black, the music fades out when the timer ends (two hours by default), and nothing restarts on its own.

## Users And Target

- v0 serves one listener on one device: the bedroom TV box (model SEI600GO, Android TV OS 14 / API 34, 32-bit `armeabi-v7a`, 1920x1080 at 320 dpi), audio through the TV over HDMI, Samsung panel. The box has no `android.software.device_admin` feature; HDMI-CEC power control is set to broadcast.
- Input is the TV remote: D-pad, OK, Back, volume and media keys.
- Out of scope for v0: phones, other devices, accounts, sync, public distribution, importing own files, an in-app catalogue with previews, crossfade.

## Product Contract

1. **One press.** A launcher start plays a random track from the personal selection with a 30 s fade-in. Only a launcher start autoplays; resume, wake, boot or process restart never start music.
2. **Household volume.** The app never raises the volume (Oleg's decision on #34, 2026-10-19). A session keeps `STREAM_MUSIC` exactly as the remote left it, muted or at 0 included, and fades only its own player gain within that level; there is no start-volume setting. Verification is at sound level: the stream index and mute state are the same before and after the start, and the playback position advances; `PlaybackState` alone is not evidence.
3. **Selection.** Tracks come from the published selection minus tracks marked never again. Shuffle-bag order: no repeats until the whole set has played. A blocked track never returns after restart or reboot. Right on the remote and the system next command follow the same rules.
4. **Timer.** Default 120 min, selectable in the menu and persisted. The last 10 min fade smoothly to silence without volume steps; playback and the service stop at the end.
5. **Screen.** About 10 s after start the picture becomes pure black and the music continues; no UI stays visible on the panel.
6. **End of night.** With `TV off` on (default) the box sleeps after the fade and the TV goes to standby through HDMI-CEC. The sleep route is the device-admin lock where that feature exists, otherwise an accessibility service performing `GLOBAL_ACTION_LOCK_SCREEN`. With `TV off` off the black screen stays and nothing restarts. Without an enabled sleep route the black screen stays and the menu says so.
7. **Morning.** Waking the box never starts music; media keys do not resume a finished session; the app does not stay above the launcher after a night with `TV off` on.
8. **Remote.** OK pauses and resumes, Right skips, the menu is reachable with the D-pad, focus is always visible, volume keys keep working.
9. **Likes.** Like and never again are stored locally and survive refresh, restart and reboot.
10. **Offline.** After the first download the app starts and plays without network or when the collection host is unreachable.
11. **Collection download.** The first run downloads the selection; each file is verified against the manifest SHA-256; an interrupted download continues file by file through `.part` files with whole-file retry (the host serves no `Range` or `Content-Length`); refresh adds new tracks and keeps likes and blocks; HTTPS only, redirects to HTTP are refused.
12. **Failure states.** No network on first run shows a clear screen with retry; a host outage on refresh keeps the old collection playing; system sleep, inattentive timeout and screensaver do not interrupt a session; a process killed at night does not restart music.
13. **Privacy.** No account, telemetry, analytics or crash-reporting service. Downloaded mode only contacts the collection host. The explicitly opted-in online source (#54,
    `docs/collection-client-contract.md`) also resolves and streams directly on the client; NoSession only. Preferences never leave the device.
14. **Night log.** A local rolling log (last 14 nights: session start and end, tracks played, errors) in app-private storage, readable over adb through `dumpsys` on the playback service, because release builds are not debuggable.
15. **Stability.** A full two-hour session produces no crash or ANR; after a reboot the app, preferences and collection are intact.
16. **Look.** The night UI follows `docs/design-contract.md`.

## Collection

- Manifest: `https://html.me.uk/t/dremla/collection/manifest.json` with `version`, `generated`, `tracks[]` and `selection[]`. Each track has `id`, `title`, `artist`, `category` (`ambient` or `piano`), `duration_sec`, `file`, `bytes`, `sha256`, `preview`, `license`, `source_url`, `loudness` and `flags`.
- v0 collection: 141 tracks (about 20.9 h), 47 in the selection, Opus normalised to -24 LUFS, licences CC0-1.0 or public-domain mark.

## Acceptance Checks

All checks run on the target box with an approved release unless stated otherwise.

| ID | Check |
|---|---|
| AC-1 | Installed package is `com.befeast.sleepmusic`; versionName, versionCode and signing-certificate SHA-256 equal the release lock of the approved release. |
| AC-2 | A launcher start with no other key press gives music within the fade-in at the unchanged household volume (contract 1-2), confirmed at sound level and by ear. |
| AC-3 | The app starts and plays offline (contract 10). |
| AC-4 | Collection integrity, resume and refresh (contract 11). |
| AC-5 | Selection rules including the system next command (contract 3). |
| AC-6 | Timer and fade (contract 4). |
| AC-7 | Black screen (contract 5). |
| AC-8 | End of night with both `TV off` settings (contract 6). |
| AC-9 | Morning behaviour (contract 7). |
| AC-10 | Remote (contract 8). |
| AC-11 | Failure states (contract 12). |
| AC-12 | Privacy (contract 13). |
| AC-13 | Two-hour session without crash or ANR; a reboot keeps state (contract 15). |
| AC-14 | Golden scenes match the accepted design; the operator confirms near-black and icon and banner legibility on the real panel. |
| AC-15 | The next release installs over the previous one through approved delivery and keeps preferences and collection. |
| AC-16 | Three consecutive evenings: the listener starts the app with the remote, music plays two hours with the fade, the screen goes dark, nobody intervenes. Evidence: confirmation by the listener or the operator plus the night log of each night. |

## Build

Toolchain pins are in `AGENTS.md`. After the scaffold issue:

- `./gradlew --max-workers=2 clean assembleDebug assembleRelease testDebugUnitTest lintDebug`
- `./gradlew --max-workers=2 verifyRoborazziDebug` (golden scenes; `recordRoborazziDebug` only in a reviewed design PR)
- `scripts/inspect-apk.sh`, `scripts/check-public-hygiene.sh`, `scripts/verify-outcome.sh`

## Release And Delivery

- SemVer, first release `v0.1.0`. The signed APK and `SHA256SUMS` are Forgejo release assets; `delivery/release-lock.json` pins tag SHA, asset name, APK SHA-256, versionCode, versionName and certificate SHA-256.
- Signing material is resolved in CI from the secret store at release time and never enters git or logs.
- Installation on the box happens only through an operator-approved delivery that runs `scripts/deliver.sh` (`adb install -r`). Enabling the accessibility service, tests that switch the TV off, uninstall or downgrade are separate operator decisions.
- Self-update is deferred until a second device or phones are in scope.

## Non-Goals

Sleep-stage or melatonin claims; speech, singing or abrupt dynamics in the music; a nature or noise mixer; alarms, tracking or meditation. Opt-in online references are an additive #54 slice; account sign-in remains deferred.
