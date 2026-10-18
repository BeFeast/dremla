# Dremla

Android TV bedtime-music app. One press on the remote starts soft ambient or calm piano from an offline personal collection, the picture goes black, and the music fades out when a timer ends (two hours by default).

- Product contract: [docs/PRD.md](docs/PRD.md)
- Design contract: [docs/design-contract.md](docs/design-contract.md)
- Agent rules: [AGENTS.md](AGENTS.md)
- Build, CI and golden scenes: [docs/ci.md](docs/ci.md)

## Build

JDK 17 and an Android SDK with platform 34 and build-tools 34.0.0 (`ANDROID_HOME`) are required.

```bash
DREMLA_OUTCOME_SCOPE=repo scripts/verify-outcome.sh
```

runs the clean build, unit tests, lint, golden scenes, APK inspection, the hygiene check and the forbidden-API check.

License: MIT. Bundled fonts (Archivo, Inter) are under the SIL Open Font License 1.1. Collection audio is CC0 or public-domain mark; the app downloads it at runtime and it is never stored in this repository.
