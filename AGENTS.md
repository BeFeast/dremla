# AGENTS.md — Dremla

Executable product requirements live in the repository and its issue tracker.

## Scope And Architecture

- One Android TV app module (`app/`): Kotlin, Jetpack Compose for TV, Media3. Package and applicationId `com.befeast.sleepmusic`.
- Pinned toolchain: AGP 8.5.2, Gradle 8.7 (wrapper with `distributionSha256Sum`), Kotlin 2.0.21, Compose for TV 1.0.0, Media3 1.4.1, compileSdk and targetSdk 34, minSdk 28, JDK 17. Changing a pin is its own issue.
- Stable entrypoints live in `scripts/`; `verify-outcome.sh` and `deliver.sh` take no arguments.

## Build And Verification

- Use the commands in `docs/PRD.md` (Build). Pass `--max-workers=${DREMLA_GRADLE_MAX_WORKERS:-2}` to Gradle and run one heavy build at a time.
- CI in `.forgejo/workflows/` is the merge gate: clean build, unit tests, lint, golden scenes, APK inspection, public-path hygiene.

## Pull Request Contract

- Work only on the assigned issue: one issue, one PR.
- Open a ready PR, not a draft, with verification evidence and `Refs #<issue>`.
- Do not deploy, run adb against any device, mutate target devices or runtimes, create releases or secrets,.
- Anything noticed in passing becomes a proposed new issue, not part of the current PR.

## Hygiene

- No secrets, private paths, LAN addresses, device endpoints, raw design packages, logs, build outputs, or user data.
- Collection audio is never committed; the app downloads it at runtime.
