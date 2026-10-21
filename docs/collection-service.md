# Collection service (issue #43, first slice)

> **Direction update, 2026-10-20.** The server is catalog and playlist management only. YouTube acquisition, audio and previews stay on the client. See [collection-client-contract.md](collection-client-contract.md). The fixture service below remains the reference for normalization, previews, versioned snapshots and review; it is not the path for real YouTube material.

A host-only web tool for one household collection. It turns a track or playlist URL into a
reviewed, versioned collection snapshot in the manifest format the TV app already downloads
([collection.md](collection.md)). The first slice runs end to end on fixtures. Live audio
acquisition, hosting and publishing to the serving host are separate decisions (see
[Out of scope](#out-of-scope)).

The TV app does not change. It keeps its explicit refresh, its fixed HTTPS collection origin and
its offline behaviour. A snapshot published by this service is a directory tree. Copying it to the
serving path is a later, separately approved step.

## Flow

1. **Add.** The operator pastes a URL. The acquisition adapter resolves it to metadata only: the
   kind (track or playlist) and the items, each with a source ID, title, artist, duration and
   canonical source URL. No audio is fetched at this step.
2. **Draft.** Every item becomes a draft entry with a stable track ID, an include flag (default on),
   a category (`ambient` or `piano`, default `ambient`) and an editable title. Items whose track ID
   is already in the current collection are marked `in collection` and excluded by default.
   All item fields lock once that draft item has any job, including a terminal job. Create a new
   draft to change metadata; PATCH on a locked item returns 409 `job-exists`.
3. **Process.** Each included entry becomes a job: `queued` → `acquiring` → `processing` → `ready`,
   or `failed` (with a fixed reason code and a short message) or `cancelled`. The operator can
   cancel a queued or running job and retry a failed, cancelled or interrupted one. A job that was
   running when the service stopped is marked `interrupted` on the next start and can be retried.
   Processing a draft again skips items with a ready or active job in that draft and queues only
   remaining items. If all included items are ready or active, it returns an empty job list.
   A new draft can still process a new revision of the same track; active jobs in another draft
   continue to block duplicate processing of that track.
4. **Review.** Each `ready` job shows the normalized result:
   - a playable preview;
   - the measured loudness (integrated LUFS, LRA, true peak) and the duration;
   - the processor's flags.

   The operator approves or rejects each one. A processor rejection (the prototype's thresholds)
   shows as `rejected by processing` and cannot be approved. Approval must name the reviewed job ID;
   that job must belong to the track and be ready, with no queued or running sibling. A later
   failed, cancelled or interrupted job does not block approving an earlier ready result. Publish
   uses the bound result. Rejecting a revision restores current selection membership unless the
   operator explicitly set its selection checkbox.
5. **Publish.** The review page shows the proposed snapshot:
   - the current collection plus the approved tracks;
   - a selection checkbox for every track;
   - the diff against the current version: `added` (new tracks), `addedToSelection` (existing tracks
     newly selected), `removedFromSelection`, and `replaced` (existing IDs with changed audio or
     preview content revisions), all repeated in the confirmation;
   - the version number that will be written.

   Publish happens only on an explicit confirmation that names the parent version and the
   `proposalRevision` returned with the reviewed state. It is refused
   when:
   - the selection is empty;
   - an approved track's job is not `ready`;
   - any job is still running for an approved track;
   - the parent version is no longer current;
   - the proposal changed since the operator reviewed it;
   - an interrupted publication is awaiting explicit completion;
   - the authoring validator fails.
6. **History and rollback.** Every published version stays listed. A rollback republishes an old
   version's manifest as a **new** version: the version number keeps increasing, and the history
   records `rollbackOf`.

## Identity

- Track ID: `yt-<video id>` for YouTube sources. Video IDs are 11 characters of `[A-Za-z0-9_-]`, so
  the ID satisfies the app's `[A-Za-z0-9_-]+` rule. The same video always gets the same ID across
  drafts, playlists and re-imports, so likes and blocks on the TV stay attached to it. Fixture
  sources use the same scheme with fixture video IDs.
- A re-processed source with different bytes keeps its ID and gets a new content revision. Its asset
  path contains the new SHA-256, so the old file stays valid for clients on the old manifest.
- Duplicate IDs inside one snapshot are refused.

## Accepted URLs

The URL is accepted only if it:

- uses HTTPS without userinfo or an explicit port;
- has one of these hosts: `www.youtube.com`, `youtube.com`, `m.youtube.com`, `music.youtube.com`
  or `youtu.be`;
- is one of three shapes: a track (`/watch?v=<id>`, `youtu.be/<id>`), a playlist
  (`/playlist?list=<id>`) or a track inside a playlist (`/watch?v=<id>&list=<id>`). The last is
  treated as the track; importing the playlist needs its own URL.

Anything else is refused before any adapter call. The canonical source URL stored in the manifest
`source_url` is rebuilt from the parsed ID, never copied from the input.

## Adapters

All three are plain Python interfaces, selected by configuration and replaced by fakes in tests.

| Adapter | Implementations in this slice | Contract |
| --- | --- | --- |
| Acquisition | `fixture` | `resolve(url) -> Source` (metadata only); `fetch(item, path, cancelled)` writes source audio to `path`. The fixture adapter knows a fixed set of fixture video and playlist IDs and synthesizes deterministic audio with the standard library. It makes no network access. |
| Processing | `fixture`, `ffmpeg` | `process(input, item, workdir, cancelled) -> Result` with the normalized audio, the preview, the duration, the loudness, the flags and the reject reasons. |
| Storage | `filesystem` | `current()`, `history()`, `put_asset()`, `publish(snapshot)`, `rollback(version)` on a local destination directory. |

There is no live acquisition adapter. Selecting any acquisition name other than `fixture` fails at
startup with `live acquisition is not implemented (separate decision)`. A live adapter must never
use account login, personal cookies or anti-bot circumvention.

### Processing parameters

The `ffmpeg` processor reuses the prototype catalog builder (`build_catalog.py`, SHA-256
`06260f7ebdfc8b5c1173cdeae82fab8aec7ec6730d2952b780bd133abc99c5b5`) with the same constants.

**Analysis.** `ebur128` measurement (integrated loudness, LRA, true peak, short-term maximum,
edge loudness, longest mid-track silence).

**Encode.** Two-pass linear `loudnorm` to I = −24 LUFS, TP = −2 dBTP (LRA setting 20), resampled
to 48 kHz, then:

- 3 s fade-in and 5 s fade-out;
- stereo Opus 96 kbps VBR in Ogg;
- no source metadata copied.

The shipped file is measured again.

**Preview.** 60 s from about 25 % of the track, 2 s fades, MP3 128 kbps.

**Reject** when LRA > 12 LU or when the short-term maximum is more than 10 LU above integrated.

**Flags:**

| Flag | Condition |
| --- | --- |
| `lra-high` | LRA > 8 |
| `loud-peaks` | short-term maximum > 6 LU above integrated |
| `abrupt-start` / `abrupt-end` | edge loudness within 6 LU of integrated |
| `mid-silence` | ≥ 8 s |
| `short` | < 300 s |
| `loudnorm-dynamic` | loudnorm fell back from linear mode |

The piano leveler of catalog v2 and the machine QA classifiers are not part of this slice.

The `fixture` processor needs no external tools. It is the default for tests and quick local runs.
It writes WAV:

- it scales the source to a fixed RMS target;
- it applies short fades;
- it cuts a 20 s preview;
- it reports deterministic loudness.

It is not a normalization reference.

Media processing runs only on the builder or in CI, never on the operator's workstation host.

## Published layout

The destination mirrors the serving path, so a later upload is a plain copy:

```text
<dest>/
  audio/<id>.<sha256>.<ext>        immutable; one file per content revision
  previews/<id>.<sha256>.<ext>     immutable
  snapshots/v<N>/manifest.json     immutable record of version N
  snapshots/index.json             history: version, generated, manifest sha256, counts, parent, rollbackOf
  manifest.json                    the current version, written last
```

Order of a publish:

1. Write every asset to a temporary file in its directory, flush it and rename it. An existing asset
   path is reused only if its bytes hash to the same SHA-256. Different bytes at an existing path
   fail the publish.
2. Validate the complete snapshot against the files on disk.
3. Write `snapshots/v<N>/manifest.json`, then `snapshots/index.json`, each through a temporary file
   and a rename. For rollback, first persist the new entry in the existing index so its
   `rollbackOf` survives an interruption immediately after the snapshot write. History hides
   uncommitted entries. If no snapshot was written, that entry reserves no version and is
   replaced by the next publication.
4. Replace `manifest.json` atomically.

A failure before step 4 leaves the previous `manifest.json` and every file it references
unchanged. Nothing is ever deleted by a publish or a rollback. Retention is a later decision.
History only lists versions committed by the current manifest, including after a restart.

### Media publication recovery and reviewed proposals (#52)

`GET /api/review` returns `proposalRevision`, a SHA-256 of the proposed manifest content without
`version` and `generated`. Canonical encoding uses sorted object keys, compact JSON and UTF-8;
array order is preserved. The confirmation dialog sends the revision of exactly the displayed
proposal along with `parentVersion` and `confirm: true`. Another tab changing approvals or
selection without publishing causes `409 stale-proposal` and writes nothing. Refresh and review
again to publish the changed proposal. If the proposal returns to exactly the same content, its
revision is valid again; this is a content check, not an edit lock. When the proposal cannot be
computed, `proposed` and `proposalRevision` are null and `error` explains why.

If `snapshots/v<N+1>/manifest.json` exists while current remains N (including N=0 before the
first publication), the review returns `pending` with `version`, `generated`, the SHA-256 of the
stored record bytes, `counts: {tracks, selection}`, `parent`, `rollbackOf` (null unless rollback),
ordered `selection: [{id, title}]`, and `matchesProposal`. Otherwise `pending` is null. The page
shows an **Interrupted publication** panel and disables normal publish. Normal publish and
rollback both return `409 pending-publication`, even when the proposal still matches. The stored
record is never rewritten, moved, deleted or reused implicitly. A failure after asset copying
but before the snapshot write leaves no pending record; content-addressed assets remain reusable.

Review the panel and choose **Make version N+1 current**. This sends
`POST /api/publish/pending/complete {sha256, confirm: true}`. No pending record returns
`404 not-found`; a different SHA returns `409 stale-pending`; absent or non-true confirmation
returns `400 confirmation-required`. Unknown fields return `400 invalid-request`. Completion
validates the stored manifest against the published asset tree, including both audio and preview
hashes, then writes history and current **last** from its exact bytes, without consulting jobs or
staging assets. A malformed record, invalid recovery index entry or missing/corrupt published
asset returns the fixed `400 invalid-base` error without committing. Startup also validates any
pending record and refuses invalid pending data with `invalid-base`.

Completing an interrupted publish keeps later review decisions. Review those changes against the
recovered current and publish them as N+2. Completing an interrupted rollback retains its
`rollbackOf` target and clears the review, as an uninterrupted rollback does. The page
refreshes after `stale-*`, `pending-publication`, `not-found` and `local-failure` responses and
shows the returned message. The manifest contract, content-addressed assets and published layout
are unchanged.

## Manifest written

Exactly the fields of the app contract:

- **Root:** `version` (integer, previous + 1, starting at 1 for an empty destination),
  `generated` (UTC ISO-8601), `tracks[]` and `selection[]`.
- **Each track:**
  - `id`, `title`, `artist`, `category`, `duration_sec`;
  - `file` (`audio/<id>.<sha256>.<ext>`), `bytes`, `sha256`;
  - `preview` (`previews/…`), `license`, `source_url`;
  - `loudness` (`i_lufs`, `lra`) and `flags`.

The `license` field comes from the acquisition metadata. Fixture sources declare `CC0-1.0`. What a
live adapter declares is part of its separate decision. A source without a known license gets the
flag `license-unverified`.

The authoring validator is stricter than the app parser. It requires:

- unique, valid IDs;
- a nonempty selection of known IDs;
- 64-hex lowercase SHA-256 and `bytes > 0` for every track;
- category in `ambient` / `piano`;
- an integer `duration_sec > 0` (whole seconds, as the prototype builder writes);
- safe relative `audio/` and `previews/` paths;
- a numeric `loudness.i_lufs`;
- an HTTPS `source_url`;
- every referenced file present with that size and hash.

The validator runs before every publish and on any manifest the service reads as its base.

## State and API

State is a directory of JSON files (drafts, jobs and review decisions), each written with a
temporary file and a rename under one process lock. It belongs to one household, so there are no
accounts.

The server binds to loopback only. It refuses requests whose `Host` is not a loopback name with the
configured port, which guards against DNS rebinding. State-changing requests must be JSON with the
header `X-Dremla-Request: 1`, so a cross-origin form cannot trigger them.

| Method and path | Purpose |
| --- | --- |
| `GET /` | the single-page UI |
| `POST /api/drafts` `{url}` | resolve and create a draft |
| `GET /api/drafts` | saved drafts for reopening in the UI |
| `GET /api/drafts/<id>` | draft with items |
| `PATCH /api/drafts/<id>/items/<item>` | `include`, `category`, `title` |
| `POST /api/drafts/<id>/process` | queue jobs for included items |
| `GET /api/jobs` | `{jobs: [...], workerAlive: boolean}`; UI shows an error banner when the worker is not alive |
| `POST /api/jobs/<id>/cancel`, `/retry` | job control |
| `GET /api/review` | ready results, current collection, proposed selection, diff, `proposalRevision`, `pending` |
| `POST /api/review/<track>` | `approved`, `inSelection`; approval also requires `job: <jobId>` |
| `POST /api/publish` `{parentVersion, proposalRevision, confirm: true}` | explicit publish of the reviewed proposal; missing revision or unexpected fields return `400 invalid-request` |
| `POST /api/publish/pending/complete` `{sha256, confirm: true}` | explicitly make the reviewed interrupted snapshot current |
| `GET /api/snapshots` | history |
| `POST /api/snapshots/<n>/rollback` `{parentVersion, confirm: true}` | rollback as a new version |
| `GET /media/...` | previews of ready jobs and published assets |

## Running locally

Requires Python 3.8+ (standard library only); the ffmpeg processor additionally needs ffmpeg and
ffprobe on PATH. Run it on the builder (or another host where media processing is allowed) through an
SSH tunnel:

```bash
DREMLA_COLLECTION_DATA=/tmp/dremla-collection/state \
DREMLA_COLLECTION_DEST=/tmp/dremla-collection/published \
DREMLA_COLLECTION_PROCESSOR=fixture \
nice -n 19 scripts/collection-service.sh
```

The defaults are port `8765`, processor `fixture` and acquisition `fixture`.
Set `DREMLA_COLLECTION_PORT` to override the port (`0` chooses an ephemeral port for verification).
`DREMLA_COLLECTION_ACQUISITION` selects acquisition. Without explicit directories, state and
output live in the gitignored `.collection-service/state` and `.collection-service/published`.
State and destination must be separate directory trees. Exclusive directory locks prevent
concurrent service processes from sharing either one. Saved drafts, jobs and review decisions
are in `drafts.json`, `jobs.json` and `review.json`; each transition is flushed and atomically
replaced under the process lock before applying a worker transition in memory. A failed state
write records `storage-failed`; the worker continues with later jobs. If even the failure state
cannot be persisted, the finished attempt becomes `failed` in memory (`interrupted` during stop),
so it can be retried through the normal job control once storage recovers. The next successful
jobs write persists this state too; a restart recovers stale running state from disk as interrupted.
The API rejects non-UTF-8 request strings before changing state.

SIGTERM, SIGHUP and Ctrl-C stop the HTTP server and cancel processing before waiting for idle
HTTP connections. Media subprocesses run in separate sessions and are terminated, then killed
if needed, and reaped. A job stopped during processing becomes `interrupted` and can be retried.
Cancellation is requested before waiting for the HTTP serving loop; directory locks remain held
until request threads and the worker finish. A media child that exits by signal (or ffmpeg's signal
exit code 255) waits up to one second for concurrent service cancellation before reporting failure.
An inherited ignored SIGHUP stays ignored, preserving `nohup` behaviour.
Abrupt termination is recovered on the next start. A destination containing a manifest must have the matching
service-written snapshot record and history, and must pass asset validation at startup.
`DREMLA_COLLECTION_PROCESSOR=ffmpeg` uses the real normalization. The fixture URLs listed on the
start page exercise a single track, a playlist with one failing item and one item that the processor
rejects. The destination must be an empty or previously used service directory, never the live
collection.

### First run with fixtures

1. Run the command above on the builder and open its printed loopback URL through an SSH tunnel.
   Preserve the same port on the local end of the tunnel: the Host guard requires the configured port.
2. Add `https://www.youtube.com/watch?v=fxAmbient01` (Still Lantern), process it, listen to the
   preview and approve it. Publish with the confirmation dialog: version 0 → 1.
3. Add `https://www.youtube.com/playlist?list=PLFixtureCalm01`. Still Lantern is now excluded by
   default. The other items are Paper Moonlight (`fxPiano0001`), Missing Lantern (`fxFail00001`,
   deterministic acquisition failure), and Uneven Horizon (`fxQuiet0001`, processing rejection).
   Each fixture ID also works as a single-track watch URL. Approve Paper Moonlight and publish
   version 1 → 2. Failed/rejected items are never included without approval.
4. Restore version 1 in History. Confirm to publish its content as version 3, recording `rollbackOf: 1`.

The fixture processor emits WAV audio and a 20-second WAV preview. The ffmpeg processor emits
Opus and MP3, and still generates a review preview for rejected audio so the rejection can be
inspected; rejected audio cannot be approved or published. Fixture loudness is deterministic
and illustrative; only the ffmpeg processor measures the encoded audio.

The API returns `{error, message}` for failures, with fixed codes such as `invalid-url`,
`unknown-fixture`, `acquisition-failed`, `processing-failed`, `not-ready`, `job-running`,
`processor-rejected`, `unapproved`, `stale-parent`, `stale-proposal`, `confirmation-required`,
`invalid-request`, `invalid-manifest`, `invalid-base`, `asset-conflict`, `pending-publication`,
`stale-pending`, `not-found` and `local-failure`. Stale parents/proposals and publication conflicts return HTTP 409;
Host/CSRF failures return 403. Diagnostics never include raw tool exceptions or local paths.

## Verification

`scripts/test-collection-service.sh` (no arguments) runs the unit and HTTP tests on a temporary
directory and loopback port. It covers:

- URL validation;
- stable IDs and dedup;
- the job lifecycle with failure, retry, cancel and restart;
- the review and publish gates;
- immutable assets, the manifest written last (with a failure injected between the steps), checksums,
  history and rollback;
- interrupted media publication and rollback, explicit completion after review changes and restart,
  invalid pending assets, and content-bound confirmation across two tabs;
- the full HTTP flow with the guards.

The `ffmpeg` processor test runs on generated audio when ffmpeg is on `PATH` (the builder and CI).
It checks that the shipped Opus measures −24 ± 1 LUFS and that a high-LRA input is rejected. The
test prints an explicit `SKIPPED (no ffmpeg)` line otherwise. `verify-outcome.sh` runs the stage in
both scopes.

## Container image

The image packages the unchanged service, its static UI, Python 3, ffmpeg and the system CA
bundle (`ca-certificates`, for catalog delivery over HTTPS) from Debian 13 (`trixie-slim`, pinned
by digest). Its build context is `collection-service/`; tests, Android
sources and audio are excluded. Build with a full 40-hex commit SHA:

```bash
nice -n 19 docker build --build-arg "DREMLA_REVISION=$(git rev-parse HEAD)" \
  -t dremla-collection:local collection-service
```

Missing or malformed revisions fail the build. `/app/REVISION` and the OCI
`org.opencontainers.image.revision` label contain that SHA; the source and title labels identify
the repository and `dremla-collection`. The base digest pins the filesystem; Debian packages
are resolved at build time, so use the published image digest for an exact runtime artifact.

Run with `--init --read-only --tmpfs /tmp --user 1000:1000`. Docker's init (tini) forwards SIGTERM
and reaps orphaned children. The packaging gate also verifies the Python PID-1 path without
`--init`; use init operationally as a child-reaping safeguard. The real service entrypoint is
`python3 -I /app/collection-service/run.py`, and `STOPSIGNAL` is SIGTERM. Allow at least 20 seconds
to stop before forcing termination. Interrupted jobs remain available for retry after restart.

Mount separate writable directories or volumes, owned by UID/GID `1000:1000`, at `/data/state`
and `/data/published`, and at `/data/catalog` when publishing catalogs. These are the defaults of
`DREMLA_COLLECTION_DATA`, `DREMLA_COLLECTION_DEST` and `DREMLA_CATALOG_DEST`. The empty catalog
directory is included in the image, so the fixture media flow can still start with just its two
existing mounts. Defaults also set `DREMLA_COLLECTION_ACQUISITION=fixture`,
`DREMLA_COLLECTION_PROCESSOR=ffmpeg`, `DREMLA_COLLECTION_PORT=8765`, `TMPDIR=/tmp` and
`PYTHONDONTWRITEBYTECODE=1`. Media scratch goes to the tmpfs; state and output go to the mounts.
The service still binds only to loopback **inside its container**. Publishing a Docker port does
not make that listener reachable; network access and deployment are separate work. Tests use
`--network none` and execute their HTTP probes inside the container.

`.forgejo/workflows/collection-image.yml` gates publication with
`nice -n 19 scripts/test-collection-image.sh` on the Docker build runner. CI builds the release
image once, records its image ID, and supplies that ID via `DREMLA_IMAGE_UNDER_TEST` to the
no-argument gate. The gate retains caller-owned images; without that variable it builds and
removes a unique local image and also checks missing/malformed revision rejection.
It checks provenance and runtime contents, runs the existing ffmpeg
smoke (versions 1 → 2 → 3 rollback and −24 ± 1 LUFS), and checks HTTP health, active-processing
shutdown, interruption recovery and retry both with and without init. It prints exit codes and
stop timings and verifies no application root writes (allowing Docker's injected init mountpoint).
Then, in a separate read-only container with a third writable volume at `/data/catalog`, it
publishes catalog version 1, restarts, checks that the draft edit and version 1 persisted, publishes
version 2 and checks version 1 is byte-for-byte unchanged. The media-only modes keep only their two
mounts. The gate removes its containers and volumes, including the catalog volume. Self-build mode also removes its image and isolated
build cache. It needs Docker with Buildx, Python 3, git and `setsid`. TERM is forwarded to the
host driver and its clients before cleanup. CI bounds the gate at 25 minutes (TERM, then KILL
after 60 seconds), inside a 30-minute step, and an `always()` step removes leftover containers
and volumes bearing that job's unique test label. Runner failure can still prevent cleanup.
`nice -n 19` lowers only host script/client priority, not container workloads: test containers
use `--cpu-shares 2 --cpus 2`, and the self-build builder uses `cpu-shares=2`.
The daemon's release builder does not inherit the Docker client's niceness.

CI publishes only from `main`, on relevant pushes or manual dispatch, using the existing
registry credential. Tags are `git.oklabs.uk/befeast/dremla-collection:<sha12>` and `:latest`
(pushed last from the tested image ID). CI checks the image ID again immediately before each
push. SHA tags are write-once: an authenticated check for an existing SHA prints
`ALREADY_PUBLISHED` and its existing `DIGEST=sha256:...`, skips both pushes (including `latest`),
and succeeds. Lookup errors other than a missing manifest fail publication. The digest printed
for a published SHA never changes; rebuilding dependencies for publication requires a new
commit. The full `DIGEST=sha256:...` is printed in the publishing step log and job summary.
No registry login, publishing or deployment is performed by the local test script.

## Out of scope

Each of these needs its own decision or issue:

- live YT audio acquisition and its license handling;
- hosting and deployment of the service;
- copying a snapshot to the serving host;
- asset retention and pruning;
- multiple users or collections;
- segmenting long mixes;
- machine QA classifiers and the piano leveler;
- any TV app change, including automatic refresh.

## Catalog management (#48)

`/catalog` is the separate catalog and playlist page, linked from the collection header. It starts
empty. Add a fixture track (its metadata is prefilled) or a single-track YouTube URL with manual
`title`, `artist`, positive integer `durationSec` and `category` (`ambient` or `piano`). The same URL
parser and canonical `yt-<videoId>` identity rules apply. Only playlists known to the fixture
catalogue can be imported; other playlist URLs return `unsupported-url`. Imports are atomic:
any already-present track returns `track-exists` without adding the rest of a fixture playlist.
No acquisition adapter is called. Catalog tracks contain references and metadata only, with no
media, previews, resolved streams, credentials or session data. The existing fixture media flow
is unchanged and has independent drafts and versions.

Edit metadata and the optional manual `gainDb` trim (−40 through 0 dB), create and rename playlists,
add/remove tracks and reorder with Up/Down. Exclude removes a track from the effective selection of
every playlist. Removing a track also removes its playlist memberships and exclusion. Deleting the
active playlist clears `active`. Empty drafts, empty playlists and a null active playlist can be
saved; publishing requires an active playlist with at least one non-excluded track. Draft validation
explains the first blocking error. The publish confirmation shows the ordered selection and is
bound to the draft revision on screen: if another tab or client changed the draft since that view,
publishing returns `stale-draft`, the page reloads the current draft, and the operator reviews it
and publishes again. The revision is a SHA-256 of the draft content, so this is a confirmation check,
not a lock on edits.

| Method and path | Body / result |
| --- | --- |
| `GET /api/catalog` | `{draft, draftRevision, currentVersion, selection, validation: {valid, errors}, pending, fixtures}`; `pending` is `null` or the interrupted next version (`version, generated, sha256, counts, selection: [{id, title}], matchesDraft, document`); fixtures contain reference-only metadata for form prefill |
| `POST /api/catalog/tracks` | `{url, title?, artist?, durationSec?, category?, gainDb?}`; non-fixtures require all four metadata fields |
| `PATCH /api/catalog/tracks/<id>` | any metadata fields above, without `url`; `gainDb: null` clears the trim |
| `DELETE /api/catalog/tracks/<id>` | `{}`; removes memberships and exclusions too |
| `POST /api/catalog/playlists` | `{id, title, trackIds?}`; ID is a lowercase alphanumeric/hyphen slug, 1–64 characters, beginning with an alphanumeric |
| `PATCH /api/catalog/playlists/<id>` | `{title?, trackIds?}`; ordered known IDs, no duplicates |
| `DELETE /api/catalog/playlists/<id>` | `{}` |
| `PUT /api/catalog/excluded` | `{excluded: [trackId, ...]}` replaces the exclusion list; known IDs, no duplicates |
| `PUT /api/catalog/active` | `{active: playlistId}` or `{active: null}` |
| `POST /api/catalog/publish` | `{parentVersion, draftRevision, confirm: true}`; `draftRevision` from the reviewed view; returns the published document |
| `POST /api/catalog/pending/complete` | `{sha256, confirm: true}`; makes the reviewed interrupted version current, byte for byte; returns it |
| `GET /api/catalog/versions` | committed history, oldest first |
| `GET /catalog/current.json`, `GET /catalog/v<N>.json` | `application/json` exports; unpublished or interrupted versions return 404 |

Draft mutations return the same view as `GET /api/catalog`. All routes use the existing loopback
Host guard. All writes, including DELETE, require a JSON object and `X-Dremla-Request: 1`.
Unexpected fields are refused. Fixed error codes include `invalid-request`, `invalid-url`,
`unsupported-url`, `metadata-required`, `track-exists`, `playlist-exists`, `not-found`,
`invalid-catalog`, `storage-failed`, `confirmation-required`, `stale-parent`, `stale-draft`,
`pending-publication` and `stale-pending`.
Duplicate identities and publication conflicts return 409. No raw storage diagnostics are returned.

Set `DREMLA_CATALOG_DEST` for the export directory. Its default is the gitignored
`.collection-service/catalog`. It must be a separate directory tree from both
`DREMLA_COLLECTION_DATA` and `DREMLA_COLLECTION_DEST`; none may contain another. All three directories
are exclusively locked across service processes, and catalog operations use the service process
lock. A failed draft write leaves the previous draft in memory and on disk. The draft is stored in
`catalog-draft.json` in the state directory. Existing draft and publication files must not be
symlinks. When embedding `CollectionService`, `catalog_destination` is optional and defaults to a
`catalog` sibling of the supplied state directory.

```text
<state>/catalog-draft.json       tracks, playlists, excluded, active
<catalog-dest>/v<N>.json         immutable dremla.catalog/1 document
<catalog-dest>/index.json        version, generated, sha256, counts, parent
<catalog-dest>/current.json      complete current document, written LAST
```

Publish validates the document with the runtime contract validator, increments the current version
(starting at 1), and uses a UTC `generated` time ending in `Z`. Writes use flushed temporary files
and atomic rename: immutable version, then history, then current. History counts tracks, playlists
and selected tracks. A failure before the last step leaves the previous current document intact.
If a publish stops after writing `v<N+1>.json` but before `current.json`, version N stays current
and history and export endpoints keep hiding the interrupted record. `GET /api/catalog` reports it
as `pending`, and the page shows an "Interrupted publication" panel with its time, counts, ordered
selection and the full stored document, also after a restart and after later draft edits. A normal publish then returns
`pending-publication`; the record is never rewritten, removed or reused implicitly, even if the draft
still matches it. The operator reviews the panel and confirms "Make version N+1 current", which sends
the record's SHA-256 (`stale-pending` if it no longer matches) and writes history, then current, from
the stored bytes without reading the draft. Later draft edits are kept and publish as N+2. Startup
checks the current record, committed history hashes and any interrupted record. No published version
is overwritten or removed.

For catalog publishing in a read-only container, supply a third separate writable mount at the
image default `/data/catalog` (or set `DREMLA_CATALOG_DEST` to another separately mounted container
path). The empty image directory permits media-only startup without this mount. This is
configuration for a future deployment, not a change to any running service.

### Catalog delivery (optional)

Without configuration, Publish writes only the local files above. The service uploads a published
catalog to the URL the TV app reads only when both variables are set:

| Variable | Value |
| --- | --- |
| `DREMLA_CATALOG_DELIVERY_URL` | the one public catalog URL the app reads, `https://html.me.uk/t/dremla/catalog/current.json` (HTTPS, a `.json` path, no credentials or query; plain HTTP only to loopback for local test targets) |
| `DREMLA_CATALOG_DELIVERY_TOKEN_FILE` | a server-side file holding the existing publication bearer token for that origin |

Setting only one of them refuses to start (`invalid-config`). The token is read from the file for
each upload and sent only in the PUT's `Authorization` header. It never appears in the UI, API
responses, logs, the catalog or `catalog-delivery.json`.

After a publication commits (`current.json` written last), or after an interrupted one is completed,
the service delivers **that version**, outside the service lock. Deliveries take a delivery lock and
run one at a time:

1. Under the lock, check the version is still current. If a newer version became current, nothing is
   sent for this one; the newer publication delivers itself.
2. Anonymous GET of the URL. If it holds a catalog with a **higher** `version`, the delivery fails
   with `remote-newer` and nothing is uploaded. If it already holds the same bytes, it is recorded as
   delivered without an upload. Any status other than 200 or 404 fails (`remote-check-http-<status>`).
3. Check again that the version is still current (the GET took network time), then PUT the exact
   `current.json` bytes with `private: false` and `expire: never` (`upload-http-<status>` on failure).
4. Anonymous GET again. It must return 200 and the identical bytes (`verify-http-<status>`,
   `verify-mismatch`).

`delivered` means an anonymous fetch of the URL returned these exact bytes at the recorded time. It is
not a receipt from a TV. A TV fetches the catalog at its next online launch and uses it from the launch
after that; only a TV with no valid catalog yet uses it at once (see the
[client contract](collection-client-contract.md)).

**The service must be the URL's only writer while delivery is configured.** GET then PUT is not a
compare-and-swap at the origin. A manual upload or any other writer between the pre-check and the PUT
is overwritten, and the pre-check only sees what the URL held at that moment. The delivery lock orders
this service's own uploads, and each one sends a version that was current just before its PUT. A
newer local publication can still commit while an older version's PUT is in flight; the newer one's
delivery runs next and replaces it. With delivery configured, do not use the manual upload in the
[client contract](collection-client-contract.md).

Transport errors are recorded as `network`, with no detail. Redirects are not followed, and requests
send no `User-Agent` header. Each step has a 15 s timeout. The result is stored in
`<state>/catalog-delivery.json` (url, version, sha256, `delivered`/`failed`, error code, UTC time) and
returned as `delivery` in `GET /api/catalog`.

A failed delivery never undoes the local publication. `POST /api/catalog/delivery/retry
{version, confirm: true}` makes one attempt for that version if it is still current, checked under
the delivery lock, and creates no new version. Without configuration it returns 404 `not-found`. If
the version is not current, or a newer one becomes current before the upload, it returns 409
`stale-delivery` and uploads nothing. There is no background retry or scheduler. The page's *Catalog
delivery* panel shows one of three states: local only (not configured); published here and available
for the TV at the URL (version, URL, check time, and when a TV picks it up);
or published here but not available (last error and a Retry button).

The collection-service test suite includes catalog CRUD, URL/manual-metadata gates, cascades,
ordered selection, all catalog contract fixtures, publication failures before current commits,
interrupted-publication review and completion after draft edits and restart, stale-draft refusal for
a second tab, directory locks, concurrent publish, and real HTTP requests on ephemeral loopback
ports. Catalog exports are also checked by the independent contract checker. No YouTube requests
are made by these tests or by the catalog feature.
