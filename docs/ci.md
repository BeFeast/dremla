# CI

The merge gate is `.forgejo/workflows/ci.yml`. It runs on every pull request and on pushes to `main`. A newer push to the same pull request cancels the older run. A `main` build that is already running is not interrupted by a newer push; a `main` run still waiting in the queue may be replaced by the newer one.

## Runner

- Forgejo Actions, one job, `runs-on: heavy`: the org's host-mode runner (label `heavy`). Jobs run directly on the runner host, not in a container.
- Timeout 45 minutes.

## Toolchain

The toolchain comes from the runner environment, not from the workflow:

- `ANDROID_HOME=/opt/android-sdk` with `platforms/android-34` and `build-tools/34.0.0`.
- `JAVA_HOME` pointing to JDK 17.
- Gradle 8.7 through the wrapper; `gradle/wrapper/gradle-wrapper.properties` pins `distributionSha256Sum` to the official checksum of `gradle-8.7-bin.zip`.
- The Gradle and Maven caches in the runner user's home persist between runs.

Jobs never install SDK packages, never run `sdkmanager` and never accept licences. The first workflow step only prints and checks `ANDROID_HOME`, `JAVA_HOME`, the Java version, the platform and the build tools. If something is missing, the job fails with a message; report it on the issue instead of changing the workflow. No `local.properties` is needed: the Android Gradle Plugin reads `ANDROID_HOME`.

`gradle.properties` sets `android.builder.sdkDownload=false`. Without it the Android Gradle Plugin downloads a missing SDK component (a platform or build tools) on its own whenever the licences are accepted on the host; with it, a missing component fails the build instead.

## CPU caps

The runner host also serves other CI, and one unrestricted build there once pushed the load average to about 270. So:

- The workflow runs `scripts/verify-outcome.sh` under `nice -n 19`; every Gradle process inherits that priority.
- The workflow sets `DREMLA_GRADLE_MAX_WORKERS=4`, and every Gradle call in the scripts passes `--max-workers=${DREMLA_GRADLE_MAX_WORKERS:-2}`.
- `gradle.properties` turns off the Gradle daemon and runs the Kotlin compiler in-process, so nothing outlives the build. Unit tests run in one JVM (`maxParallelForks = 1`, 2 GB heap).

Locally, run one Gradle build at a time under `nice -n 15`; the scripts default to 2 workers.

## What the job runs

`DREMLA_OUTCOME_SCOPE=repo scripts/verify-outcome.sh`, which runs every stage, prints a summary table and exits non-zero if any stage failed:

1. `gradle-build-test-golden-lint`: `./gradlew --no-daemon --max-workers=N --continue clean assembleDebug assembleRelease verifyRoborazziDebug lintDebug writeRuntimeDependencies`. One Gradle call, so the unit tests run once: `verifyRoborazziDebug` depends on `testDebugUnitTest` and runs it in verify mode, where a golden mismatch fails its scene test. Lint aborts on errors; `assembleRelease` also runs `lintVitalRelease`. `--continue` keeps the other tasks running after a failure, so a failing unit test, golden scene or `lintVitalRelease` still leaves the `lintDebug` report for the artifact, and the call still exits non-zero.

   After this stage the script prints one line per test class from the JUnit XML in `app/build/test-results/testDebugUnitTest` (tests, failures, errors, skipped, time) and a total, so the job log shows what ran. It prints only the class name and the counts; the XML also holds the runner's host name and the test output. The table is a report, not a stage: a failing test already fails stage 1. Without XML results (for example when compilation failed) it says so.
2. `scripts/inspect-apk.sh` on the unsigned release APK: applicationId `com.befeast.sleepmusic`, minSdk 28, targetSdk 34, no `lib/**` entries; prints versionCode, versionName and the signing certificate (`none (unsigned APK)` for the release build).
3. `scripts/inspect-apk.sh` on the debug APK, which must be signed (debug key).
4. `scripts/test-hygiene.sh`: self-test of the hygiene check against fixtures generated at runtime.
5. `scripts/check-public-hygiene.sh`, over the content of tracked text files and the path names of all tracked files, binary ones included:
   - no home-directory paths (the home root followed by a slash anywhere, URLs included; content only);
   - no RFC 1918 addresses: a full 10/8 address, and 192.168/16 or 172.16/12 as soon as the third octet starts, so partial addresses and dotted hosts in the nip.io style count;
   - no device endpoints: an IPv4 address with any port, and a host name or bracketed IPv6 literal with the adb port or a five-digit port (wireless debugging on Android 11 and later uses random five-digit ports). Each endpoint on a line is checked on its own; loopback (127/8, `0.0.0.0`, `localhost`, `[::1]`, `[::]`) is allowed;
   - no private orchestrator state paths (its dot-directory as a path segment, its database file), in content or in names.
6. `scripts/test-forbidden-apis.sh`: self-test of the forbidden-API check against sources generated at runtime.
7. `scripts/check-forbidden-apis.sh`: no Compose path iteration in `app/src/main` (see [Native libraries](#native-libraries)).
8. `scripts/test-kit-port.sh`: self-test of the brand-kit port check on a scratch copy of the listed files. A flipped byte, a missing file, a dropped manifest line, a path alias, a changed or extra `substituted` line, a malformed change record and edits to the substituted file, inside and outside its substituted line, must each fail; a later change with its change record must pass.
9. `scripts/check-kit-port.sh`: the manifest `docs/kit-port.sha256` lists exactly 38 files under canonical, distinct paths, and every one exists with its listed SHA-256. The checker pins the one substitution (`LibraryTest.kt` line 180): the manifest must have exactly one `substituted` line, with that path, that line and the pinned SHA-256 of the listed file without that line. The file must have the committed SHA-256 the manifest records; with line 180 deleted it must have the pinned SHA-256; and line 180 must be exactly the committed text, which names `collection.example`. A later change to a ported file carries a `# was <sha256> (#N)` record above its line (see [docs/kit-port.md](kit-port.md)).
10. `scripts/test-signing.sh`: self-test of the release signing scripts (`scripts/signing-dir-create.sh`, `scripts/resolve-signing.sh`, `scripts/signing-cert-digest.sh`, `scripts/signing-dir-remove.sh`). It creates a throwaway PKCS12 keystore with `keytool` (from `$JAVA_HOME/bin` when `JAVA_HOME` is set, otherwise from `PATH`) and runs the resolver against a stub `curl` placed first on `PATH`, so it makes no network call and uses no real secret. It checks the success path, including the certificate SHA-256, that every failure fails closed with an empty signing directory (among them a private key that does not decrypt; that fixture is built with the `java` next to `keytool`), and, through logging wrappers for `jq` and `keytool`, that no secret value reaches the argv of `curl`, `jq` or `keytool`, the environment of `curl` or `keytool`, or any output. The signing directory scripts run against scratch `RUNNER_TEMP` directories: create must give a 0700 directory recorded in `GITHUB_ENV`, also under a setgid parent; remove must delete a filled directory, also when `shred` fails or is missing, refuse foreign paths and symbolic links, and fail when the directory survives `rm`. It needs `jq` and `shred`. See [release signing](release-signing.md).

11. `scripts/test-privacy.sh` and `scripts/check-privacy.sh`: endpoint and resolved runtime dependency checks, with adversarial fixtures. The Gradle stage writes `app/build/reports/privacy/runtime.txt`; the gate requires the reviewed module allowlist in `docs/runtime-dependencies.txt`. See [collection.md](collection.md).

The default scope (`full`, used outside CI) adds a device-identity stage. It prints `device identity: not configured (enabled by the install issue)` and does not fail until the install issue wires it up. Unknown scope values fail.

## Artifacts

- `verify-outcome-log`, always: the full output of the script, including the APK inspection and the unit-test table. Kept 7 days.
- `unit-test-reports`, always: the HTML unit-test report (`app/build/reports/tests/testDebugUnitTest`). The JUnit XML is not uploaded because it records the runner's host name; the per-class table in the log is built from it. A missing report is skipped, for example when compilation failed. Kept 7 days.
- `failure-evidence`, only when the job fails: `app/build/outputs/roborazzi` (for every changed scene `<name>_actual.png` and `<name>_compare.png`, which shows reference, diff and new side by side) and `app/build/reports` (lint, unit-test and Roborazzi reports; the unit-test report is also in `unit-test-reports`). Kept 7 days.

## Golden scenes

- Scenes are JVM tests: Robolectric with native graphics, `sdk = 34`, qualifiers `w960dp-h540dp-land-television-xhdpi`, which is 1920x1080 px. The harness (`app/src/test/java/com/befeast/sleepmusic/ui/Goldens.kt`) asserts that the captured frame is exactly 1920x1080 before handing it to Roborazzi.
- State is injected: a scene composable takes its state as a parameter, and the test passes a fixed state.
- References are committed under `app/src/test/screenshots/`. `verifyRoborazziDebug` compares strictly: a pixel counts as changed if its colour differs at all, and one changed pixel fails the scene. Actual and compare images go to `app/build/outputs/roborazzi`, never next to the references.
- Plain `testDebugUnitTest` runs the scene tests without comparing images; `verify-outcome.sh` runs them only through `verifyRoborazziDebug`.
- `recordRoborazziDebug` rewrites the references. Run it only in a reviewed design PR, commit the new PNGs and show the diff in that PR.

## Native libraries

Compose depends on `androidx.graphics:graphics-path`, which ships `libandroidx.graphics.path.so` for every ABI. The APK must not contain `lib/**` entries, so `app/build.gradle.kts` excludes the library with `packaging.jniLibs.excludes`.

Consequence on every API level, the API 34 target box included: `androidx.graphics.path.PathIteratorImpl` calls `System.loadLibrary("androidx.graphics.path")` in its static initializer, whichever implementation the device gets, and its conic conversion is native code too. So any Compose path iteration throws `UnsatisfiedLinkError` at runtime on every device: `Path.iterator()` (and the `PathSegment` values it yields), `PathEasing`, `PathHitTester`, `Path.toSvg()` and `Path.divide()`. App code must not use them.

`scripts/check-forbidden-apis.sh` enforces this with a token grep over the Kotlin and Java sources in `app/src/main`: `PathEasing`, `PathHitTester`, `PathIterator`, `PathSegment`, `androidx.graphics.path`, `toSvg(` and `.divide(` (on any receiver). It cannot see a library that iterates paths internally, so check a new dependency for these APIs too.

## Versions

`versionCode` 1 and `versionName` 0.1.0 are defaults. The release workflow overrides them with `-Pdremla.versionCode=<int>` and `-Pdremla.versionName=<semver>`.

## Running locally

```bash
nice -n 15 ./gradlew --no-daemon --max-workers=2 --continue clean assembleDebug assembleRelease verifyRoborazziDebug lintDebug
DREMLA_OUTCOME_SCOPE=repo nice -n 15 scripts/verify-outcome.sh
```

## Other workflows

- `.forgejo/workflows/signing-smoke.yml`: the release signing smoke test, manual only (`workflow_dispatch`, and only on `main`). It never runs on pull requests or pushes. Every step has a timeout, and the step timeouts add up to less than the 15 minute job timeout, so the cleanup step that removes the signing directory runs unless the run is cancelled or the runner dies (see the limits in release signing). See [release signing](release-signing.md).
