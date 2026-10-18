# Release and release lock

The operator dispatches `.forgejo/workflows/release.yml` after its PR merges. Nothing in
normal CI creates a tag or release. The workflow runs only on `workflow_dispatch` on `main`,
on the `heavy` host runner, one release run at a time. Use a trusted runner with capacity 1;
the signing isolation and cancellation limits in [release-signing.md](release-signing.md)
apply here too. Never cancel a signing run merely to retry it.

## Dispatch

In **BeFeast/dremla → Actions → release → Run workflow**, select branch **main** and enter:

| Input | First release value |
| --- | --- |
| `source_sha` | The full 40-hex SHA of the reviewed, merged source commit on main |
| `version_name` | `0.1.0` |
| `version_code` | `1` |

Click **Run workflow** once. A branch name or abbreviated SHA is rejected. The selected
commit must include these release scripts and be an ancestor of the fetched main tip.
The workflow checks out that exact commit before verification and signing. Version names
must be SemVer (including valid optional prerelease/build identifiers); codes are decimal
integers from 1 through Android's maximum 2100000000, without leading zeroes.

`DREMLA_VERSION_CODE` and `DREMLA_VERSION_NAME`, when either is set, must both be valid.
`verify-outcome.sh` passes them as separate `-Pdremla.versionCode` and
`-Pdremla.versionName` arguments to its one Gradle invocation. This hook accepts no arbitrary
shell or Gradle arguments. It runs the same clean build, unit tests, golden scenes, lint,
inspection and repository gates as CI, with repo scope, `nice -n 19` and four workers.
A failing gate prevents signing and publication.

Only after verification does the workflow resolve signing material with the unchanged
Universal Auth scripts. It compares the keystore certificate to
`RELEASE_SIGNING_CERT_SHA256`, runs `zipalign -p -f 4` before signing, uses password files,
and verifies the final APK with `apksigner verify --print-certs -Werr`, alignment checking
and `DREMLA_APK_REQUIRE_SIGNED=1 scripts/inspect-apk.sh`. Publication independently checks
its versions and single signing certificate against the inputs and pinned digest.

## Publication and recovery

The automatic `${{ secrets.GITHUB_TOKEN }}` reaches only the preflight/publication steps
through an environment variable. Source validation, ancestry checking and checkout run in a
separate token-free step. Each token-bearing step runs only the Python helper (after
disabling shell tracing); no Git command runs in that shell. Python removes that variable before starting child tools,
keeps the token in process memory and sends it in an HTTPS authorization header. The helper
never prints HTTP response bodies or exception details, writes credentials to no file, and
refuses redirects for writes and redirects outside the HTTPS forge origin. It requires Python 3 (also used by the offline tests), in addition to the
existing runner tools. No additional actions or toolchain installations are used.

The helper checks `GET /repos/{owner}/{repo}/tags/{tag}`: only HTTP 404 means absent.
Authentication, permission and transport failures fail closed. Immediately before creating
anything, it checks absence again, HEAD equality and `git merge-base --is-ancestor` against
main. `POST /repos/{owner}/{repo}/tags` creates the tag at the explicit source SHA; a racing
or pre-existing tag is never overwritten. It then uses `POST /repos/{owner}/{repo}/releases`
with `draft: true`, uploads `dremla-<versionName>.apk` and `SHA256SUMS` to the release assets
endpoint, checks returned names/sizes and the complete asset list, and only then PATCHes
`draft: false`. API routes are under `/api/v1` (Forgejo 16 / Gitea-compatible API).

An upload failure leaves a draft, never a public release missing an asset. A failure after
tag creation can leave a reserved tag and possibly a draft. Automatic retries deliberately
refuse that tag. The operator must inspect the run and server state, remove an abandoned
draft/tag if appropriate, and then dispatch again. A timeout on the final publication
request may mean the complete release was published but its response was lost: inspect
before retrying. The script never automatically deletes published state.

If the automatic token returns 403 for release/tag creation or asset upload, stop and have
the operator provision a dedicated, repository-scoped release credential with the required
permissions, then review a workflow change mapping that operator-chosen secret to
`DREMLA_RELEASE_TOKEN`. No dedicated secret name or credential is assumed or created by this
implementation. Do not put a token in `run:` text, a command argument or a checked-in file.

The always-running cleanup reuses `signing-dir-remove.sh`. Step timeouts total 66 minutes
against a 75-minute job timeout. There are no artifact uploads; signing files live only in
the private runner temp directory. The signed APK and checksum are the only release assets.
Cancellation, runner death and host filesystem erasure limitations still apply.

## Independently verify and create the lock

Run this on a trusted verification machine with Python 3, JDK 17 and Android build-tools
34.0.0. Use a fresh working tree for the follow-up lock PR. Set `DREMLA_FORGE_URL` to the
HTTPS forge origin (no path), `DREMLA_REPOSITORY=BeFeast/dremla`,
`DREMLA_RELEASE_TAG=v0.1.0`, and `RELEASE_SIGNING_CERT_SHA256` to the public pinned DER
certificate digest from repository configuration. Set `ANDROID_HOME` and `JAVA_HOME` for
the installed tools and put `$JAVA_HOME/bin` on `PATH`. Then run:

```bash
scripts/release-lock.sh
scripts/check-release-lock.sh
git diff -- delivery/release-lock.json
```

The no-argument generator obtains the published release and tag through the API, refuses a
draft and unexpected/duplicate assets, and independently downloads both assets into a fresh
temporary directory. It computes SHA-256 over the downloaded APK, checks the exact single
`SHA256SUMS` entry, runs signed APK inspection (package `com.befeast.sleepmusic`, SDK 28/34,
no `lib/**` entries), then runs `apksigner verify --print-certs -Werr` and
`aapt2 dump badging`. It requires exactly one signing certificate, matching the expected
public digest, a versionName matching the tag, and no `native-code:` badging. It derives
versionCode, versionName and certificate from those tool outputs, and tag SHA from the tag
API's resolved commit, never from the dispatch inputs or a local build. Temporary downloads
are removed on normal exit/failure; the lock is replaced atomically only after all checks.
This is the independent re-download procedure required after dispatch; inspect the generated
JSON for code **1**, name **0.1.0**, the reviewed source SHA and the expected certificate.

For a private repository, optionally supply `DREMLA_RELEASE_TOKEN` through a trusted secret
injection mechanism; never paste it into shell commands or logs. It is not needed for public
reads. Downloads may follow HTTPS GET redirects within the forge origin (attachment routes).
Cross-origin object storage and HTTP downgrades are deliberately unsupported and fail closed.

Commit only `delivery/release-lock.json` in a follow-up PR with the release/run links and
verification result. Do not commit the APK, downloaded checksum file or logs. The release
workflow does not create this PR automatically. Review the lock before any delivery.
AC-1 compares the installed identity with this lock through the read-only
[delivery device stage](delivery.md); AC-15 still requires operator-approved
upgrade verification on the device. Later releases must use a higher versionCode and the
same signing identity; this repository work performs no installation or device test.

## Lock schema (version 1)

`delivery/release-lock.json` is a JSON object with exactly these fields (no additional or
duplicate keys). The format version is this documented schema, not an extra JSON field.

| Field | Type and invariant |
| --- | --- |
| `tag` | String, `v` followed by `versionName` |
| `tagSha` | 40 lowercase hex digits; the tag's source commit |
| `assetName` | String, `dremla-<versionName>.apk` |
| `apkSha256` | 64 lowercase hex digits; downloaded APK SHA-256 |
| `versionCode` | JSON integer, 1–2100000000 |
| `versionName` | SemVer string |
| `certificateSha256` | 64 lowercase hex digits; DER signing certificate SHA-256 |
| `releaseUrlPath` | `/<owner>/<repo>/releases/tag/<tag>`; no origin, query or fragment |

The no-argument validator checks the schema, formats and cross-field consistency, then
uses the same read-only published-asset verifier as the generator. Set `DREMLA_FORGE_URL`
and `DREMLA_REPOSITORY` as above; CI supplies its server origin and repository automatically
and reads anonymously. The tag comes from the committed lock. The verifier resolves the
tag through the API, downloads the APK and SHA256SUMS, inspects the APK with aapt2 and
apksigner, and compares every committed field with the independently derived identity.
Any mismatch, missing configuration, fetch failure or tool failure fails the stage.
Validation never rewrites the lock and does not require a separate certificate variable:
the committed certificate digest is compared with the downloaded APK's signer.
Before the first release a missing lock explicitly passes with `no release lock yet`;
malformed JSON, duplicate keys, symlinks and invalid fields fail. `verify-outcome.sh` runs both `release-lock-self-test` and
`release-lock` stages. Runtime fixtures test validation, published-byte generation,
preservation on failure, draft publication ordering, refusal paths and the version hook,
without network calls or real signing credentials. The injected API source tests every
identity field, fetch/checksum failures and preservation of lock bytes. Tests also cover
the workflow token boundary, a racing tag conflict and an incomplete pre-publication
asset list; neither publication failure may make the draft public.
