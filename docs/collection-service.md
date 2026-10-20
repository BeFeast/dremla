# Collection service (issue #43, first slice)

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

   Publish happens only on an explicit confirmation that names the parent version. It is refused
   when:
   - the selection is empty;
   - an approved track's job is not `ready`;
   - any job is still running for an approved track;
   - the parent version is no longer current;
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
   and a rename.
4. Replace `manifest.json` atomically.

A failure before step 4 leaves the previous `manifest.json` and every file it references
unchanged. Nothing is ever deleted by a publish or a rollback. Retention is a later decision.
If a failure strands the next immutable snapshot record before the current manifest commits,
retry with the original proposed content. The record and its generated time are reused;
different content returns `snapshot-conflict` rather than overwriting that record. History
only lists versions committed by the current manifest, including after a restart.

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
| `GET /api/review` | ready results, current collection, proposed selection, diff |
| `POST /api/review/<track>` | `approved`, `inSelection`; approval also requires `job: <jobId>` |
| `POST /api/publish` `{parentVersion, confirm: true}` | explicit publish |
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
write leaves the previous state and records `storage-failed`; the worker continues with later
jobs. If even the failure state cannot be persisted, a queued job is held until restart (or cancel
and retry). The API rejects non-UTF-8 request strings before changing state.

SIGTERM, SIGHUP and Ctrl-C stop the HTTP server and cancel processing before waiting for idle
HTTP connections. Media subprocesses run in separate sessions and are terminated, then killed
if needed, and reaped. A job stopped during processing becomes `interrupted` and can be retried.
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
`processor-rejected`, `unapproved`, `stale-parent`, `confirmation-required`, `invalid-manifest`,
`asset-conflict` and `snapshot-conflict`. Stale parents and review conflicts return HTTP 409;
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
- the full HTTP flow with the guards.

The `ffmpeg` processor test runs on generated audio when ffmpeg is on `PATH` (the builder and CI).
It checks that the shipped Opus measures −24 ± 1 LUFS and that a high-LRA input is rejected. The
test prints an explicit `SKIPPED (no ffmpeg)` line otherwise. `verify-outcome.sh` runs the stage in
both scopes.

## Container image

The image packages the unchanged service, its static UI, Python 3 and ffmpeg from Debian 13
(`trixie-slim`, pinned by digest). Its build context is `collection-service/`; tests, Android
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

Mount two separate writable directories or volumes, owned by UID/GID `1000:1000`, at `/data/state`
and `/data/published`. These are the defaults of `DREMLA_COLLECTION_DATA` and
`DREMLA_COLLECTION_DEST`. Defaults also set `DREMLA_COLLECTION_ACQUISITION=fixture`,
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
stop timings, verifies no application root writes (allowing Docker's injected init mountpoint),
and removes its containers and volumes. Self-build mode also removes its image and isolated
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
