# Catalog server and online client playback (issue #43, contract draft v1)

Status: **direction and client platform decided, contract draft.** The client is the Dremla APK on
the TV ([Client platform](#client-platform)). The catalog fixture vectors and their checker exist, and
the server catalog and playlist management is implemented by #48. The opt-in, NoSession TV client
is implemented by #54; see [Implemented client slice](#implemented-client-slice-54) for its verification limits.

## Direction

Oleg's decisions of 2026-10-20 (after checking the fixture preview):

- **The server is catalog and playlist management only.** "The files themselves are not needed on
  the server at all; we only need the catalog and playlist management." It holds references to
  YouTube tracks, their metadata, the household's playlists (included tracks, order, exclusions) and
  the catalog versions. It holds **no audio, no previews, no stream URLs and no YouTube session**.
  There is no media proxy, relay or upload path to the server.
- **The client plays online.** The user signs in to their own YouTube account on the client. The
  client resolves each selected reference to a stream and plays it directly. Credentials, cookies,
  resolved stream URLs and media never leave the client. Online playback is the main scenario of
  the first slice.
- **Offline is optional and later.** Downloading or normalizing whole files, a local media cache and
  offline readiness are not required before the first playback and are not part of the first
  slice. They may come later as an optional client feature ([Later, optional](#later-optional)).

This supersedes the server-side acquisition, processing and publishing of
[collection-service.md](collection-service.md) for real YouTube material. That fixture service is not
changed by this contract; it stays the reference for versioned snapshots and review. Client-side
placement is an architecture choice, not a statement about licensing or terms of service; this
contract adds no compliance gate.

## Roles and data

| | Management server | Client |
| --- | --- | --- |
| Track references (`yt-<videoId>`, canonical URL) and metadata | owns | reads |
| Playlists: included tracks, order, exclusions, active playlist | owns, versioned | reads |
| YouTube session (login, cookies, tokens) | never | owns, local only |
| Resolved stream URLs and media bytes | never | transient, local only |
| Playback, sleep timer, fade, volume | no | the TV app, unchanged rules |

## Catalog document (`dremla.catalog/1`)

The server publishes one JSON document per household. Every change produces a new integer
`version` (previous + 1).

```json
{
  "schema": "dremla.catalog/1",
  "version": 7,
  "generated": "2026-10-20T18:00:00Z",
  "tracks": [
    {"id": "yt-fxAmbient01", "source": {"kind": "youtube", "videoId": "fxAmbient01",
     "url": "https://www.youtube.com/watch?v=fxAmbient01"},
     "title": "Still Lantern", "artist": "Fixture Ensemble", "category": "ambient",
     "durationSec": 90, "gainDb": -3.0}
  ],
  "playlists": [{"id": "night", "title": "Night", "trackIds": ["yt-fxAmbient01"]}],
  "excluded": [],
  "active": "night"
}
```

Rules, checked by `contracts/check-catalog.py`:

- **Track IDs.** `tracks[].id` is `yt-<videoId>` and unique. `videoId` is 11 characters of
  `[A-Za-z0-9_-]`. The URL is rebuilt from the ID as `https://www.youtube.com/watch?v=<videoId>`,
  never stored as a free-form URL.
- **No media or credential fields.** The catalog has no `file`, `sha256`, `bytes`, `preview` or stream
  URL, and no cookie or credential field anywhere.
- **Gain.** `gainDb` is an optional manual per-track trim, `≤ 0`, applied as player gain. With no
  server-side audio there is no measured loudness, so the trim is only a manual correction; it does
  not normalize loudness between different sources. Because it can only attenuate, the trim itself
  never raises the household stream volume.
- **Playlists.** `playlists[].trackIds` reference known tracks, without duplicates, and their order is
  the playlist order.
- **Exclusions.** `excluded` lists tracks that must not play in any playlist (the catalog-level "never
  again").
- **Selection.** `active` names the playlist the TV plays. The **selection** is the active playlist
  minus `excluded`, and it must not be empty.

The TV's existing likes and blocks stay local preferences, as today; this contract does not send
them to the server.

## Online client playback

The client flow for the first slice:

1. Fetch the public catalog export over HTTPS from the fixed collection origin. Keep the last valid catalog locally. If
   the fetch fails, use the last valid catalog. A new catalog is used only after it validates.
2. Compute the selection and the play order. The existing shuffle-bag and never-again rules apply to
   the selection's track IDs, which keep their stable `yt-<videoId>` identity.
3. For each track to play, a **resolver** turns the reference into a playable stream on the client,
   using the client-local session boundary (currently NoSession only). Stream URLs are short-lived and are never stored
   beyond the session or sent anywhere.
4. The existing player engine plays the stream with the existing semantics:
   - launcher start only;
   - 30 s fade-in;
   - default 120 min sleep timer with the 10 min fade;
   - black screen;
   - the end-of-night sleep route;
   - no restart in the morning.
   The stream volume is never raised (#34). `gainDb` applies to the player gain and can only
   attenuate.

### Failure semantics

These are pinned by `contracts/fixtures/playback-*.json` and recomputed by the checker.
The vectors use a fixed round-robin order over a unique, unchanging selection; that order
cannot revisit a failed track before visiting every other selected track. A separate checker
regression supplies a shuffle-bag boundary order (A ends, B fails, C fails, B fails, then A)
to verify distinct-ID counting and trackEnd reset; the app also tests fresh and persisted bags:

- **A resolve or network error is never a successful play.** The track is marked failed (fixed reason
  codes: `login-required`, `unavailable`, `age-restricted`, `network`, `live-or-playlist`), the state
  shows it, and the engine moves to the next selected track.
- **One budget per night for retries and resumes.** A network error while starting a track gets
  one retry. A stream that drops mid-track gets one resume attempt at the last position. Both draw
  on one per-night budget of three. The per-track allowance resets when the next track starts. When
  the allowance or the budget is used up, the track counts as failed and the engine moves on.
- **Persistent failure stops sound, not the night.** The failure streak counts distinct failed track IDs
  since the last track that played to its end. Repeated failures of the same ID across a shuffle-bag
  boundary count once. When the streak covers the current eligible selection, the session enters
  `unavailable`:
  - no sound;
  - an error message on the menu;
  - **the sleep timer keeps running.**

  Errors never cancel or extend the timer. When the timer expires the night ends normally (black
  screen, sleep route), as if music had played. Nothing restarts on its own afterwards. Repeated
  start-and-drop cycles therefore end in `unavailable`, not in a retry storm.
- **No autoplay on recovery.** Network recovery after `unavailable` does not resume playback by
  itself; only a new launcher start does.

### Compatibility with the installed app

The installed APK (v0.1.1) plays a downloaded collection from `html.me.uk` and must keep doing so.
This contract is an **additive** client capability:

- it is a new catalog source and a new stream-resolving player input;
- it needs its own client issue and an explicit evolution of the app's network origin rules and
  privacy gate: the catalog host and the YouTube endpoints, both of which the current app refuses;
- the existing manifest, the downloaded collection and its offline playback are neither migrated nor
  removed.

## Fixture vectors

`contracts/fixtures/` holds platform-neutral vectors that any client implementation must pass:

- `catalog-v1.json`, `catalog-v2.json`: consecutive catalog versions (a track added, one excluded,
  the active playlist changed), with their expected selections in `selection-v1.json` and
  `selection-v2.json`.
- `invalid/`: catalogs that must be rejected: media or stream fields, cookie fields, free-form URLs, a
  positive `gainDb`, unknown or duplicate playlist tracks, an empty selection.
- `playback-*.json`: event sequences (resolve results, stream drops, resumes, timer expiry) with the
  expected states. The checker replays each one through a small reference state machine. The
  sequences cover:
  - failure is never "playing";
  - the timer never stops on errors;
  - one start retry and one resume per track, both drawing on the shared per-night budget;
  - repeated drops end in `unavailable`;
  - no autoplay on recovery.

`scripts/check-catalog-contract.sh` runs `contracts/check-catalog.py` as a stage of
`scripts/verify-outcome.sh`.

## Next minimal slice

Step 1 is implemented by #48; see [Catalog management](collection-service.md#catalog-management-48).

1. **Server.** Add catalog and playlist management to the existing service: track references
   (reusing its URL validation and stable IDs), playlists, exclusions, the active playlist,
   `gainDb`, versioning and the `dremla.catalog/1` export. The server-side media pipeline is not used
   for YouTube material. The fixture preview on the service host keeps running until a reviewed
   deploy replaces it.
2. **Client (the TV APK).** A stream resolver behind an interface with a fixture
   resolver (no network), the catalog fetch, and the failure semantics above in the existing engine.
   It is proven by the playback vectors, then by an operator check with a real account on the
   chosen client.

## Later, optional

A client-side cache or download for offline nights, and local loudness analysis to replace the
manual `gainDb`. These are optional and never required before playback.

## Client platform

Decided by Oleg on 2026-10-20 (question `dremla-client-platform-20261020`): **the Dremla APK on the
TV.** The TV gets and plays the stream by itself, and no computer is needed. There is no desktop
companion, and none is a prerequisite.

Still to be verified before live use:

- a YouTube resolver that runs on the 32-bit Android TV target;
- the user's sign-in on the TV.

This is an architecture choice, not a permission for device tests; those need their own approval.
The host-only resolver assessment is tracked separately.

## Implemented client slice (#54)

The TV APK now offers **Music source** in the existing D-pad menu. Downloaded remains the
persisted default. Choosing Online opts in; choosing Downloaded restores the existing source.
A source change stops the current session and does not autoplay: the next launcher start starts
that source. Offline audio, manifest, likes/blocks and the existing night journal are not migrated.
Online shuffle progress uses separate preference keys. The historical resolver spike is unchanged.

Online launch starts synchronously from the last valid local catalog when one exists; the
bounded refresh is used on the next launch. Without a local catalog, launch waits for that attempt.
The refresh fetches `https://html.me.uk/t/dremla/catalog/current.json` with bounded transport
timeouts, no redirects and a 2 MiB limit. Only a strict `dremla.catalog/1` document can replace
`files/online-catalog/current.json`, using atomic replacement; lower versions are refused.
A failed fetch/validation keeps the last valid catalog. With none, online mode becomes silent and
unavailable (needs catalog), with the normal sleep timer. It never falls back to downloaded audio.
An active night retains its catalog snapshot. There is no polling or recovery autoplay.

`StreamResolver`, `FixtureResolver`, the NewPipe v0.26.5 adapter and `SessionProvider` are in the
app. **Only `NoSession` is supplied. Sign-in is not implemented.** Blocking extraction runs on IO;
cancellation and session/attempt tokens discard stale work. URLs and request headers are transient
player input, never journal, preference or MediaSession metadata. MediaItems expose only an
opaque per-attempt URI; the data source substitutes the real URL and headers inside the process.
The downloader merges scoped Cookie headers at the boundary, but there is no cookie acquisition/storage/provider. Redirects fail closed as unavailable.
The adapter prefers original/unspecified audio over dubbed tracks, then AAC and bitrate.

The real engine drives `OnlineNight` with resolver and player events. A successful resolution is
not playing until the player reports first audio. A start network/expired-media error gets one
re-resolution, and a mid-track network drop gets one re-resolution with the last position. Both
spend the shared budget of three. Failure streak resets only at track end. Unavailable keeps the
night's original timer, a silent foreground service and a partial wake lock until the night ends.
Only a new launcher start can recover it. The normal 30-second fade, sleep fade, black screen,
sleep route and no-morning-restart rules still apply. `gainDb` multiplies player gain by
`10^(gainDb/20)`; system volume remains read-only.

### Catalog delivery proposal (future operator step; not executed)

The service can now deliver on Publish when configured; see [Catalog delivery](collection-service.md#catalog-delivery-optional). Use the manual commands below only when delivery is not configured: while it is, the service must be the URL's only writer.

The catalog service remains loopback-only. The operator first commits/publishes a catalog version
in that service, reviews the export for public-safe references/metadata, then exports and uploads
only `current.json`. Proposed commands, run with the operator's existing service address and
publication credential supplied in the environment (never placed in the document):

```sh
set -eu
set +x
: "${DREMLA_CATALOG_SERVICE:?loopback collection-service origin required}"
: "${POMOI_TOKEN:?existing publication token required}"
curl --fail --silent --show-error --max-time 15 \
  "$DREMLA_CATALOG_SERVICE/catalog/current.json" --output current.json
# Review current.json before the following public upload.
curl --fail-with-body --silent --show-error --max-time 120 \
  -X PUT -H "Authorization: Bearer $POMOI_TOKEN" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -H 'private: false' -H 'expire: never' \
  --data-binary @current.json https://html.me.uk/t/dremla/catalog/current.json
```

This is a proposal, not a deployment. The endpoint may still return 404. No catalog publication,
real extraction, login, OAuth, cookies, live network probe, installation or device action was done
for this slice.

### Verification boundary

Host verification covers the strict catalog fixtures, last-valid/monotonic storage, opt-in and
switch-back, all six playback vectors through the actual engine with fake resolver/player/clock,
cancellation and stale callbacks, Media3 error/header handoff with constructed fakes, attenuation,
offline regressions, lint, golden scenes, APK inspection and existing repository gates.

**Not verified:** real extraction/challenge handling, sign-in, real media/network playback,
API 28–32 or ARM32 runtime behavior, on-device foreground/wake behavior, audible fades and CEC.
Host compilation/desugaring does not establish any of those. Live checks remain a separate task.
