# Device probe and approved delivery

The no-argument entrypoints are `scripts/probe-target.sh` and `scripts/deliver.sh`.
They never connect, pair, enable Wireless debugging, change settings, uninstall or
downgrade. The operator connects the target to the host's adb server beforehand.
Every operation selects the configured serial explicitly, checks live `get-state`
and the target model, and fails closed on an unavailable or unexpected device.
A stored record never establishes reachability.

## Private configuration

Create `${XDG_CONFIG_HOME:-$HOME/.config}/dremla/delivery.env` outside the repository.
Use a regular file with mode 0600; symlinks and group/world writable files are refused.
It is parsed as data, never sourced. Blank lines and `#` comments are accepted.
Use one `KEY=value` per line, without quotes, expansion or shell syntax. Duplicate
and unknown keys are rejected.

- `DREMLA_ADB_SERIAL`: required; the USB serial, mDNS adb name or network target
  shown by adb. Never commit this value. Reports do not print it.
- `DREMLA_TARGET_MODEL`: optional, defaults to `SEI600GO`.
- `DREMLA_FORGE_URL`: optional HTTPS forge origin for delivery. The same environment
  variable takes precedence. `DREMLA_REPOSITORY` defaults to `BeFeast/dremla`.

`ANDROID_HOME` supplies build-tools 34.0.0 and, when executable, platform-tools adb;
otherwise adb is found on PATH. Python 3 and the release verifier's JDK/tools are required.
Missing or non-executable apksigner fails with a build-tools 34.0.0 diagnostic before
any device call; delivery checks it before downloading as well.
The optional `DREMLA_RELEASE_TOKEN` follows [release.md](release.md): process memory
only, removed from child environments, never logged or written to a receipt.

## Read-only probe

`probe-target.sh` reports selected build properties, configured and effective power
timeouts, free space on `/data`, all Dremla package variants and their versions,
base APK paths and SHA-256/signing-certificate digests, accessibility enablement and
binding, music mute/volume/max, CEC enablement/power mode, and input device names.
APK inspection pulls only one validated `/data/app/…/base.apk` per package into a
private temporary directory. Unsupported paths and multiple signers fail without
following other device paths. Temporary APKs are removed on normal exit and failure.
Reports contain parsed fields rather than raw device dumps.
Android 9/10 package directories and Android 11+ two-level directories are accepted
only when the path names the queried package. Accessibility enablement comes from
settings and `dumpsys accessibility`; binding requires a matching service record with
connection evidence in `dumpsys activity services com.befeast.sleepmusic`. Ambiguous
records report UNKNOWN. Label-only accessibility dumps cannot establish binding.

The [night prerequisite](night.md#target-device-prerequisite) requires both attentive
and sleep timeouts to be disabled (non-positive) or at least **12,600,000 ms**
(180 minutes plus 30 minutes). Effective `dumpsys power` values take precedence over
settings. Missing, null or nonnumeric values are UNKNOWN. Screen-off timeout is
informational; confirm keep-screen-on and actual continuity on the device.
FAIL or UNKNOWN says `open a new P1 issue; do not change settings`.

| Exit | Meaning |
| --- | --- |
| 0 | All reads succeeded; prerequisite PASS |
| 4 | Reads succeeded; prerequisite FAIL or UNKNOWN |
| 1 | Unreachable/wrong target, failed read or unsupported installed identity |
| 2 | Usage or private configuration error |

`DREMLA_DELIVER_MODE=probe scripts/deliver.sh` performs exactly this probe. It needs
no approval id, downloads nothing, installs nothing and creates no receipt.

## Install mode

Unset `DREMLA_DELIVER_MODE`, or set it to `install`. Set `DREMLA_DELIVERY_APPROVAL`
to the operator's approval id (1–128 letters, digits, dots, underscores, colons or
hyphens). Missing/invalid approval and unknown modes fail with exit 2 before any
network or device call. Approval ids containing the configured serial, an endpoint
or the release token are also rejected before network/device access.

The exact order is mode check → approval id syntax → private config → approval id
privacy check → lock → download → reachability → probe/receipt → install.
Delivery validates the regular, committed, locally unchanged release lock, then
uses the existing published-release verifier to download the locked asset into a
private temporary directory. SHA-256 must match both the lock and `SHA256SUMS`;
versions, certificate and the remaining release identity must match the lock.
Only then does it check live reachability and record the full before-state probe.
A prerequisite FAIL/UNKNOWN prints a warning and is recorded; installation changes
no power settings and does not establish installation acceptance.

| Installed state | Decision |
| --- | --- |
| Package absent | Install |
| Same certificate, lower versionCode | Install |
| Higher versionCode | Refuse downgrade |
| Equal versionCode and identical APK SHA-256 | Verify; report `already installed` |
| Equal versionCode, different APK SHA-256 | Refuse |
| Different signing certificate | Refuse; uninstall is an operator decision |

The sole device mutation is `adb install -r` with the verified APK. Afterwards,
package, versionCode, versionName, base APK SHA-256 and certificate must all match
the lock. Mismatches fail with exit 1; there is no automatic uninstall or rollback.
A successful install or verified no-op exits 0.

After the before-state probe, delivery creates a private 0700 directory beneath
`${XDG_STATE_HOME:-$HOME/.local/state}/dremla/deliveries/`, named
`<UTC YYYYmmddTHHMMSSZ>-<tag>`. Symlinked parents and repository destinations are
refused. Files are written atomically through a temporary file in that directory and
replacement, with mode 0600. Before any install, `before.txt` and an initial
`receipt.json` with result `STARTED` are persisted. The JSON records
approval id, checkout HEAD, lock, downloaded digest, decision, result and UTC start
and finish times. Success writes `after.txt` and finalizes result `PASS`. Install
failures/timeouts and identity failures finalize `FAIL` with a controlled
`device-tools: FAIL` diagnostic, and write `after.txt` when identity can be read.
Before-state refusals (downgrade, certificate mismatch, equal code with different
bytes) produce `REFUSED` receipts without installing. These failures keep a nonzero
exit and print the receipt directory. Earlier failures (approval/config, lock,
download/identity, reachability) create no receipt. Probe mode creates none.
No serial, endpoint or token is recorded. Abrupt host termination can leave a
`STARTED` receipt or temporary files; normal failures remove temporary files.

Retain the previous accepted APK as the rollback candidate. Reinstallation uses
`adb install -r` only through an approved delivery. Uninstall or downgrade requires
Oleg's separate OK and is never performed by these scripts.

## Verification scopes

Full-scope `scripts/verify-outcome.sh` adds `device-identity`: a read-only AC-1
comparison of the installed package, versions, APK SHA-256 and certificate against
the validated committed lock, without downloading an asset. Missing private config
fails this stage. It is not an audio, playback, CEC or two-hour acceptance test.

`DREMLA_OUTCOME_SCOPE=repo` runs host gates only. Both scopes run `device-self-test`
(`scripts/test-device.sh`): offline fixtures with fake adb first on a minimal PATH,
a fake SDK, temporary home/config/state directories and a dead loopback adb-server
port. No SDK, credentials, network or real device is used by this self-test.
