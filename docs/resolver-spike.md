# Resolver spike — issue #50

Decision date: 2026-10-20. Scope: two candidates, one isolated Android build, fake-only
host tests. This is compile/source research, **not approval for shipping or proof of
current YouTube playback**. No YouTube requests, login, devices, installs or deployment
were used. The app and its main CI gate are unchanged.

## Recommendation and target

Choose the **NewPipeExtractor adapter for the next separately approved live experiment**.
It keeps the resolver inside the APK, uses JVM bytecode instead of an embedded Python
runtime, and fits the existing no-native-library constraint. Whether its current
clients accept the intended user's session and return playable audio remains an open
product blocker. Do not infer that cookie injection implements authenticated YouTube
support. If that experiment fails, record the failed client/token/session condition;
do not silently introduce a server resolver, companion or second extractor.

The executable bounds are [AGENTS.md](../AGENTS.md), [app build](../app/build.gradle.kts),
[PRD target and Build](PRD.md), [APK inspection](../scripts/inspect-apk.sh),
[privacy gate](../scripts/check-privacy.sh), [reviewed dependencies](runtime-dependencies.txt)
and [collection origins](collection.md):

- Android TV 14 / API 34, **32-bit `armeabi-v7a`**; minSdk 28, compileSdk/targetSdk 34.
- AGP 8.5.2, Kotlin 2.0.21, Gradle 8.7 with the existing checksum-verified wrapper, JDK 17.
- NewPipeExtractor v0.26.5 [documents](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/README.md#usage)
  that Android projects with minSdk below 33 require core library desugaring using
  `desugar_jdk_libs_nio`. The spike enables it with exact pin **2.1.5** (source [6]).
- Shipping APK has **zero `lib/**` entries**. This is stricter than “supports ARM32”.
- Shipping source currently permits only the fixed `https://html.me.uk` collection origin;
  the reviewed runtime graph is exact. This spike changes neither rule.
- [Online client contract](collection-client-contract.md) requires client-local credentials,
  transient stream URLs, no server media relay, shared nightly retry/resume budget of three,
  a running sleep timer through failures, and no autoplay after recovery.

## Pins and primary evidence

All following sources were read on 2026-10-20. Release pages are versioned; mutable docs
and package-index observations are explicitly dated. No upstream code was executed.

1. **NewPipeExtractor `v0.26.5`**, [release](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5)
   published 2026-08-15; source commit
   `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef`.
   JitPack coordinate: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.5`.
   Read [dependencies](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/gradle/libs.versions.toml),
   [extractor build](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/build.gradle.kts),
   [downloader API](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/downloader),
   [audio builder](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/stream/AudioStream.java),
   [exceptions](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/exceptions).
2. NewPipe [YouTube stream extractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java),
   [JavaScript player manager](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptPlayerManager.java),
   [Rhino interpreter wrapper](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/utils/JavaScript.java),
   [PoTokenProvider](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/PoTokenProvider.java).
3. **yt-dlp `2026.08.19`**, [release](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19),
   [README](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/README.md#dependencies),
   [pyproject](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/pyproject.toml): Python `>=3.10`,
   and matching `yt-dlp-ejs==0.8.0` in the default dependency group.
   [EJS guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS) and
   [PO Token guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide)
   (latter page last edited 2026-07-12; both observed 2026-10-20).
4. **Chaquopy 17.1.0**, [versioned Android guide](https://chaquo.com/chaquopy/doc/17.1/android.html),
   [native packaging source](https://github.com/chaquo/chaquopy/blob/17.1.0/product/gradle-plugin/src/main/kotlin/PythonTasks.kt)
   (`registerJniLibsTask`, `JniLibsTask`, `libchaquopy_java.so`).
5. JS runtime sources: [Deno v2.9.7 assets](https://github.com/denoland/deno/releases/tag/v2.9.7)
   (2026-09-17), [Node 22 platform/build rules](https://github.com/nodejs/node/blob/v22.x/BUILDING.md#android),
   [QuickJS-NG v0.17.0 assets](https://github.com/quickjs-ng/quickjs/releases/tag/v0.17.0)
   (2026-09-18), [Bun v1.4.3 assets](https://github.com/oven-sh/bun/releases/tag/bun-v1.4.3)
   (2026-10-10). Android package evidence:
   [Termux ARM package index](https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-arm/Packages.gz),
   [Node recipe](https://github.com/termux/termux-packages/blob/master/packages/nodejs-lts/build.sh),
   [QuickJS-NG recipe](https://github.com/termux/termux-packages/blob/master/packages/quickjs-ng/build.sh),
   observed 2026-10-20. Only metadata/source was fetched; no packages installed or binaries run.

6. **`com.android.tools:desugar_jdk_libs_nio:2.1.5`**, the newest release in
   [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs_nio/maven-metadata.xml)
   observed 2026-10-20. [Android's library-desugaring documentation](https://developer.android.com/studio/write/java8-support#library-desugaring)
   links to Google's [version changelog](https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md):
   2.0.0 requires AGP 7.4.0-alpha10+, 2.0.1–2.0.4 require AGP 7.4.0+,
   and the 2.1 series requires AGP 8.0.0+ (introduced in 2.1.0; no higher minimum
   documented through 2.1.5). Thus 2.1.5 fits pinned AGP 8.5.2. The changelog
   identifies the `nio` variant as including `java.nio` desugaring; 2.1.5 adds
   `Stream.toList()` support. Both spike modules enable
   `isCoreLibraryDesugaringEnabled = true` with this exact artifact; the library setting
   also supplies desugaring metadata for library lint, as the Android docs describe.

## Candidate comparison

| Dimension | NewPipeExtractor v0.26.5 | Embedded yt-dlp 2026.08.19 + Chaquopy 17.1.0 |
| --- | --- | --- |
| ARM32 runtime | Java/Kotlin dex is ABI-independent; Rhino runs interpreted Java. ARM32 device execution is unverified. | Documented `armeabi-v7a` with Python 3.10/3.11 only. Python 3.12+ is 64-bit only. Python 3.11 meets this yt-dlp release's requirement. |
| Older Android APIs | Upstream requires `desugar_jdk_libs_nio` for minSdk <33; enabled at 2.1.5. API 28–32 runtime behavior remains unverified. | Chaquopy documents minSdk 24; no embedded runtime was built or executed here. |
| Native library gate | Proof APK has no native entries; see measured facts below. | Python is native. Chaquopy emits JNI libraries including `libchaquopy_java.so` and target Python libraries into ABI folders. Conflicts with the existing `lib/**` gate. Removing them would break Python. |
| JS challenges | Source contains signature/throttling deciphering and interpreted Rhino 1.8.1. No external Node/Deno executable required by this adapter. Source presence does not prove current challenge handling. | EJS replaces the old JSInterp path for YouTube; a compatible JS engine/executable plus matching EJS scripts is required for full support. Python embedding alone is insufficient. |
| PO tokens | `YoutubeStreamExtractor.setPoTokenProvider` accepts a client-specific provider; no generator is supplied here. Upstream explicitly calls tokenless extraction best effort. | `po_token` extractor argument or a provider plugin; EJS solving is separate from PO-token attestation. Cookies alone do not supply PO tokens. |
| Session boundary | `SessionProvider.headersFor(exactHttpsUrl)` feeds the downloader. Only `NoSession` ships in the spike. Mobile InnerTube client authentication and media request headers remain unverified. | In a future embedding, `YoutubeDL` cookie jar / `cookiefile` and extractor arguments would be fed by client-local session storage. No cookie export or Python bridge implemented. |
| APK cost | Measured debug proof below includes adapter, Kotlin and all extractor transitives; it is not the shipping app's incremental APK delta. | Not built. Chaquopy docs say several MB per ABI plus native requirements; Python stdlib, yt-dlp, EJS and a native JS runtime add further cost. No invented APK total. |
| Maintenance | Exact pin; upstream fixes change client behavior. Update by reviewing release/source/dependency diff, bumping the spike pin, rerunning fake contracts, dex/lint/APK checks, then a separately approved live probe. | Pin yt-dlp, Python/Chaquopy, EJS, JS runtime and any token provider together. yt-dlp warns on releases older than 90 days; EJS compatibility can change with updates. More independent update surfaces. |
| Evidence here | Isolated compilation, JVM fakes, debug dexing, dependency inspection and lint. No installation. | Versioned source/docs and Android package metadata only. No Python/JS runtime build or execution. |

### Embedded Python and JS runtime facts versus inference

**Documented:** Chaquopy 17.1.0 supports AGP 7.3–9.4 and minSdk 24, compatible with our
AGP 8.5.2/minSdk 28 on paper. It offers Python 3.10 (default), 3.11, 3.12, 3.13, 3.14,
3.15; only 3.10/3.11 support `armeabi-v7a` and x86, while arm64-v8a/x86_64 support newer
versions. A matching build-machine Python major/minor is needed for some features.
The plugin's native packaging is direct evidence of the gate conflict. No Chaquopy
build was attempted: it is unnecessary to establish that conflict and could require
additional Python tooling outside this task.

**Documented as of the EJS guide inspection:** Deno >=2.3.0 is recommended; Node >=22.0.0;
QuickJS >=2023-12-9 or any QuickJS-NG; Bun 1.2.11–1.3.14 only (deprecated; newer Bun is
not supported by the inspected guide). Old QuickJS/QuickJS-NG versions can take minutes
on a challenge; QuickJS-NG >=0.12.0 includes the noted optimizations.

- Deno v2.9.7 release assets contain x86_64/aarch64 desktop builds, **no ARM32 Android build**.
- Node upstream documents Android as unsupported, with an Android cross-build route;
  its Linux armv7 glibc binary is not an Android binary. Termux's index **does** publish
  `nodejs-lts 24.18.0-1`, architecture `arm`, with Android-specific dependencies.
- QuickJS-NG v0.17.0 upstream publishes `qjs-linux-armv7`, not an Android-specific asset.
  Termux's index **does** publish `quickjs-ng 0.17.0`, architecture `arm`; its recipe
  builds shared libraries. Thus an Android ARM32 build route exists for QuickJS-NG as
  well as Node. Neither package was downloaded or executed.
- Bun v1.4.3 publishes Android aarch64/x64 assets but **no ARM32 Android asset**, and is
  newer than the EJS guide's supported Bun range.

**Inference:** a custom APK could integrate the ARM32 Node/QuickJS route, but Termux
package availability does not prove standalone app embedding, executable loading under
Android 14, acceptable resource usage, or EJS operation. That would be a separate native
packaging/runtime project. None solves the Python no-`lib/**` conflict. A native executable
hidden as an asset would evade the literal gate without satisfying its purpose and is not
recommended. ffmpeg is not needed merely to resolve an audio URL; no media conversion
pipeline is proposed here.

**Documented:** yt-dlp's PO-token guide distinguishes GVS (media), player and subtitles
contexts; tokens can bind to a video or session, expire, and cannot be interchanged across
Web/Android/iOS clients. It recommends automatic providers instead of manually copied
per-video tokens. NewPipe's provider docs say Web tokens need BotGuard and a JS engine
with a good DOM; Android uses DroidGuard. **Inference:** merely embedding Rhino, Python,
EJS or a cookie header does not establish a usable attestation/session flow. This spike
contains no token generator, remote provider or token acquisition.

## Minimal adapter and contract behavior

The isolated [build](../spikes/resolver/settings.gradle.kts) is not included by the root
settings. `:resolver` exports `StreamResolver`, `Resolved`/`Failure`, `FixtureResolver`,
`SessionProvider`/`NoSession`, `OnlineNight`, and catalog parsing/selection. `:proof-app`
is a separate application with a no-op activity referencing `NewPipeResolver`; it has
no launcher and no INTERNET permission. `assembleDebug` dexes the complete runtime
classpath at minSdk 28 using D8 and L8 core library desugaring, without application
shrinking; it is not an R8 release/shrinker proof or proof of runtime compatibility.

`NewPipeResolver` fetches one canonical video ID, requests `audioStreams`, accepts only
normal video/audio stream types and direct HTTPS audio URLs, prefers AAC/M4A and then
reported bitrate. It rejects live/post-live types, playlists/URLs passed as IDs, manifests,
inline content and a missing audio stream. It returns bitrate in bits/second and parses
an optional bounded `expire` timestamp; neither URLs nor responses are persisted or logged.
This is a deliberately limited progressive-audio adapter; HLS/DASH fallback is absent.

The downloader implements NewPipe `Downloader.execute(Request)` using
`HttpURLConnection`, request method/body/headers, UTF-8/gzip response handling, fixed
15-second connect/read timeouts and `finally` disconnect. It has no retry of its own.
Redirects fail closed rather than forwarding session headers implicitly. Session providers
must scope headers to the exact HTTPS URL. NewPipe's process-global downloader is protected
by a shared lock during resolution and reset to `NoSession` afterwards; a later app must
own all NewPipe calls and establish cancellation, origin policy and media-header handoff.
No persistent session provider is implemented.

Exception mapping cites the v0.26.5 classes in source [1]:

- `AgeRestrictedContentException` → `age-restricted` (before its parent).
- `SignInConfirmNotBotException`, `ReCaptchaException` → `login-required`.
  This contract code means an authentication/challenge condition, not a promise that login fixes it.
- `ContentNotAvailableException` and subclasses such as `PrivateContentException`,
  `PaidContentException`, `GeographicRestrictionException` → `unavailable`.
  Private content is not assumed accessible merely because someone signs in.
- `ContentNotSupportedException`, parse/extraction failures without a more specific cause
  → `unavailable`; wrapped `IOException` → `network`.
- Transport HTTP 401/429 → `login-required`, 403/404/410 → `unavailable`, other failed
  statuses/timeouts/IO → `network`. HTTP 403 alone cannot identify a PO-token failure.
- `live-or-playlist` comes from input/type validation, not from guessing an exception message.

No response or exception-message substring is exposed as a reason. Mapping tests throw real
pinned library exception objects through a fake extraction boundary. Stream tests use the
library's `AudioStream.Builder`; downloader tests use constructed `HttpURLConnection`
objects, not even a loopback server. No tests call the production extraction path.

`OnlineNight` ports [the Python reference](../contracts/check-catalog.py): phases
`starting`, `playing`, `resuming`, `unavailable`, `finished`; one network start retry and
one resume per track sharing a nightly budget of three; failure streak resets only at
`trackEnd`; timer remains running through errors; recovery never starts playback. It also
retains a supplied last playback position for a future resume and a fixed failure code.
It models the timer/player decisions, not a real clock, decoder, fade or power route.
Catalog parsing enforces fields, IDs/canonical URLs, reference integrity, exclusions,
nonempty selection and attenuating gain, and rejects duplicate JSON keys on Android as
well as on the host. Existing fixtures remain the authority and are not modified.

## Future sign-in boundary: source evidence only

Read-only source: `BeFeast/karaoke` commit
`4ad5247b044fb09a6ef3b281ae36d5ae0d6b682f`, all below `mobile/android/`:

- `app/src/main/java/com/befeast/karaoke/submitter/SignInActivity.kt`, `onCreate`,
  `chromeLikeUserAgent`, `onDestroy`: WebView sign-in, JavaScript/DOM storage enabled,
  user-agent changes, requested-with header suppression, cookie acceptance and flushing.
  Its comments report practice and a possible Google block, not verification in this spike.
- `app/src/main/java/com/befeast/karaoke/submitter/WebViewCookies.kt`, `current`:
  `CookieManager.getCookie` for specific origins, returning empty if WebView is absent.
- `app/src/main/java/com/befeast/karaoke/submitter/core/CookieHeaderParser.kt`,
  `QUERY_URLS`/`synthesize`: converts header strings into cookies with synthesized attributes.
- `app/src/main/java/com/befeast/karaoke/submitter/Prefs.kt`, `prefs`/`pastedJar`:
  MasterKey AES256-GCM, `EncryptedSharedPreferences` AES256-SIV keys/AES256-GCM values.
  This stores the optional pasted jar; it does **not** mean WebView's own cookie store is
  encrypted by those preferences.

A later Dremla implementation could read `CookieManager.getCookie(requestUrl)` locally
and return an origin-scoped `Cookie` header via `SessionProvider`; explicit user-supplied
session material, if retained at all, needs a local protected store and sign-out deletion.
Do not copy the karaoke server-submit/export behavior: Dremla's contract forbids sending
credentials off the client. Account UI, WebView availability on TV, expiry, renewal,
InnerTube authorization headers, token binding and signed media requests need their own
implementation and live evidence. None is supplied by `NoSession`.

## Build and APK evidence

Verification ran only on the approved build host in an rsynced `mktemp` checkout under `nice -n 19`,
with the preinstalled SDK/JDK and `--max-workers=4`. The temporary checkout was removed.
The root wrapper was reused; no shipping/shared build or gate file changed. Commands:

```sh
nice -n 19 ./gradlew -p spikes/resolver --no-daemon --max-workers=4 \
  :resolver:testDebugUnitTest :proof-app:assembleDebug :proof-app:lintDebug < /dev/null
nice -n 19 ./gradlew -p spikes/resolver --no-daemon --max-workers=4 \
  :proof-app:dependencies --configuration debugRuntimeClasspath < /dev/null
nice -n 19 ./gradlew -p spikes/resolver --no-daemon --max-workers=4 \
  :resolver:dependencies --configuration coreLibraryDesugaring \
  :proof-app:dependencies --configuration coreLibraryDesugaring < /dev/null
```

The operator's untracked evidence logs are named `i50-build-r2.log` and
`i50-apk-r2.log`; raw logs, APKs and host paths are deliberately not committed.
The dependency report includes Kotlin stdlib 2.0.21 / JetBrains annotations 13.0,
NewPipeExtractor v0.26.5, nanojson `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996`,
jsoup 1.22.2, jsr305 3.0.2, protobuf-javalite 4.35.1, Rhino and rhino-engine 1.8.1.
No OkHttp runtime dependency is present. The separate `coreLibraryDesugaring`
configuration in each module resolves `com.android.tools:desugar_jdk_libs_nio:2.1.5`
and its `desugar_jdk_libs_configuration_nio:2.1.5` dependency; those reports are also
recorded in the APK evidence log.

- `testDebugUnitTest`, `assembleDebug`, `lintDebug`: **PASS**.
- **29 tests**, zero failures/errors/skips: 19 fixture cases (2 catalog/selection pairs,
  11 invalid catalogs, all 6 playback vectors), 6 NewPipe/transport tests, 4 night/parser
  integration tests. Exception and stream tests contain multiple subcases.
- Lint: **0 errors, 3 warnings**, no NewApi finding in the spike's own sources.
  Lint does not inspect the library jars for NewApi; this is not dependency-runtime
  compatibility evidence. Warnings are `OldTargetApi` for the
  mandated target 34, `DataExtractionRules`, and `MissingApplicationIcon` in the no-op
  proof app. No lint suppression or shipping gate change was made. SDK tooling also
  emitted its XML-version compatibility warning; no SDK installation was performed.
- Proof APK: **6,298,949 bytes (6.01 MiB)**; **zero `lib/**` entries**.
  SHA-256: `863a2ee5b2cdadb2cc2f0b15f3865f07455ec90688512fc5345ba3496a6b3b90`.
- SDK `aapt2 dump badging`: package `com.befeast.dremla.resolverproof`, minSdk **28**,
  targetSdk **34**. No INTERNET permission. No ABI filters/native exclusions were used.
- DEX `method_ids_size`: **38,177 + 266 + 3 + 3 + 1,067 = 39,516** across five dex files.
  This is the sum of method references, not deduplicated defined methods.
- Public hygiene check: **PASS** with the new files staged; only this spike and this
  decision record are changed. The full shipping gate was not run because no shared
  files or shipping code were touched.

Enabling NIO core library desugaring adds **109,298 bytes** to the previous
6,189,651-byte proof APK and **1,040** summed method references to the previous
38,476, with a fifth DEX for the desugared library. Native entries remain zero and
minSdk/targetSdk remain 28/34. The previous build omitted upstream-required
desugaring and is superseded by this result; neither build establishes API 28–32
runtime compatibility. Toolchain pins and adapter/test sources are unchanged.

During the original verification, the first dependency-report invocation finished its
task but hung on Gradle forwarding the remaining remote shell script into stdin. Its
own process was stopped; the temporary checkout was removed, and verification was
repeated with stdin closed. This was host invocation behavior, not a resolver test or
compilation failure. The Kotlin compiler also reports three platform-nullability warnings
in host test fixture discovery (the fixture property and parent directories are supplied
by the test task); these are not NewApi errors or failed tests.

## Compile/source-supported (proven here)

- Exact NewPipe release/source and its downloader, audio, exception, Rhino and PO-token APIs.
- Pinned Android compilation, debug D8/L8 with core library desugaring, minSdk/targetSdk
  inspection, 29 passing tests, and zero native APK entries. D8/L8 proves dexing, not
  runtime behavior on API 28–32 or actual playback; D8 does not reject every API
  missing on a runtime platform.
- Lint passes with no NewApi finding in the spike's own sources only; library jars
  are not covered by that NewApi analysis, even with `checkDependencies = true`.
- Catalog selection and rejection rules; every existing playback vector; fake resolver failure
  never becoming playing; shared budget, resume position and streak behavior in host tests.
- A concrete `HttpURLConnection` adapter and session-header boundary tested without sockets.
- Chaquopy's documented ARM32/Python constraints and native packaging conflict; yt-dlp's
  Python/EJS requirements; the specific runtime release/package availability above.
- Karaoke sign-in/storage source pattern, without reading an account or executing the flow.

## Not verified (needs live check)

- Successful YouTube extraction or playback for any video/account/client; current JS challenges,
  token requirements, 403 diagnosis, age/private gating and geo behavior.
- Authenticated Android/TV WebView login, cookie/header completeness, token generation,
  signed-in InnerTube compatibility, expiry and renewal.
- Runtime compatibility of NewPipeExtractor and its dependencies on **API 28–32**,
  including class loading, desugared API calls and Rhino execution on 32-bit ARM.
  API 34 execution is also unverified; lint/dexing are not runtime execution.
  Redirects, Media3 decoder support and any required media request headers.
- Target latency/memory, audio continuity, timed sleep/fade and real network-drop recovery.
- Shipping APK size delta, R8 release shrinking, production integration and gate changes.
- Any embedded Python/JS build, install or execution.

## Exact remaining live check — future separate approval

Create a separate, reviewed probe issue/build. **Do not run this as part of #50.** The
no-op proof APK is not the live probe: it lacks both a resolving UI and INTERNET permission.

The API 34 live check below **does not cover API 28–32 runtime compatibility**. Keeping
minSdk 28 in a shipping integration requires separately scoped and approved runtime
checks covering API 28–32, or an explicit minSdk decision in its own issue.

1. Use a **dedicated expendable Android TV API 34 device with 32-bit `armeabi-v7a`
   userspace**, representative of the target. Do not use the family TX10 or Anton's SEI
   box. Record ABI/API/WebView version without recording a device address in the repo.
2. Use a **dedicated non-Premium adult test YouTube account**, owned and expressly
   authorized by the operator, with no personal history/cookies imported. Start with
   `NoSession`, then the same samples with a separately reviewed client-local WebView
   `SessionProvider`. No OAuth, household account or desktop cookie import. If sign-in is
   blocked, report that result; do not work around it or install a companion.
3. With the same NewPipe pin and bounded adapter, resolve **two operator-owned or otherwise
   explicitly authorized, non-live, non-age-restricted audio samples**, one public and one
   unlisted, one at a time. Render only success/failure code, elapsed time, MIME/bitrate and
   expiry-present flag; never print URLs, cookies, tokens, response bodies or exception text.
   Hand a resolved URL directly to an in-process Media3 audio player, with no file download,
   and play each for 60 seconds at the device's existing volume or lower. First prove the
   adapter's progressive stream works; do not silently enable HLS/DASH fallback.
4. Verify a playlist input is rejected before a request and an operator-controlled private
   sample inaccessible to this account returns a failure without becoming playing. Make
   **at most one retry** per failed starting sample and honor the shared budget of three.
   A login/bot/PO-token failure is a recorded failure, not permission for external token
   services, browser extraction, repeated requests or client changes.
5. On the test device only, interrupt the probe's connectivity after a known playback
   position; permit one resume there. Use two failing fixture-selected tracks to exhaust
   the night into unavailable, verify silence and running timer, restore connectivity and
   verify no autoplay. Set a two-minute probe timer and confirm expiry ends the session
   without restart. Do not toggle household network infrastructure or power/CEC devices.
6. Pass only if both real samples resolve/play on the target, the account/session path works
   locally, failures and budget/timer/recovery match the contract, and no native/API/JS
   exception occurs. Treat token demand, unusable media headers, blocked login, persistent
   403, missing progressive audio, unsupported APIs or budget/timer deviations as **fail**
   with sanitized reason evidence. Successful anonymous playback alone does not pass the
   signed-in client requirement. Discard transient URLs/session and sign out after testing.

The future approval must name that test target, account type, probe APK revision and bounded
steps. It must not authorize installing on either household box, changing system volume
upward, server-side resolution, media/credential upload, OS/SDK packages, a release,
deployment or automatic nightly use. No such approval is requested or implied here.

## Shipping follow-ups, not changes made here

- Separate implementation issue for catalog fetch/last-valid retention, session lifecycle,
  background/cancellable resolution, stream/header handoff and integration with existing
  shuffle/never-again, fades and sleep routes; preserve downloaded/offline collections.
- For minSdk 28, enable `coreLibraryDesugaringEnabled` (Kotlin DSL:
  `isCoreLibraryDesugaringEnabled = true`) in the shipping build and include the exact
  `com.android.tools:desugar_jdk_libs_nio:2.1.5` pin in the
  `docs/runtime-dependencies.txt` review alongside NewPipe, its full transitive graph
  and licenses. NewPipe declares GPL-3.0-or-later; record distribution obligations
  in that integration review. Alternatively, make an explicit minSdk decision in
  its own issue. No shipping dependency/gate pin is changed by this spike.
- Plan API 28–32 runtime coverage if retaining minSdk 28; the specified API 34 live
  check cannot validate those older platforms, and a successful D8/L8 build cannot
  replace their runtime checks.
- Explicitly evolve the collection-only privacy/network rules for the approved catalog,
  YouTube API/player-JS/media and sign-in origins, including redirects and session scoping.
  Do not widen the existing source endpoint gate indiscriminately or log sensitive URLs.
- For the recommended pure-Java path, retain the no-`lib/**` APK gate and rerun it on the
  integrated app. Embedded Python would require an explicit native-library policy/toolchain
  decision; it cannot be integrated under today's gate by excluding required libraries.
- Add reproducible dependency verification/locking and approved update/live-probe cadence
  when adopting a shipping resolver. The isolated spike does not alter the main CI gate.
