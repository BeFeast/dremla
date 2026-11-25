# Resolver host-only spike

See [the decision record](../../docs/resolver-spike.md) for evidence, limitations,
source citations and the separately approved live-check specification.

This is an independent Gradle build. It is **not** included in Dremla's root settings,
and neither module belongs in the shipping app. The proof app has no launcher or
INTERNET permission. Do not install or run it on a device.

NewPipeExtractor v0.26.5 requires core library desugaring for minSdk below 33. Both
modules enable it with `com.android.tools:desugar_jdk_libs_nio:2.1.5`, compatible with
pinned AGP 8.5.2; see the decision record for primary sources. D8/L8 success proves
dexing only. Lint NewApi covers the spike's own sources, not library jars; API 28–32
runtime compatibility remains unverified, and the proposed API 34 live check does
not cover it.

For this issue, run Gradle only in an rsynced `mktemp` checkout on the approved build
host, with its existing Android SDK and JDK 17 selected by `ANDROID_HOME` and
`JAVA_HOME`. Remove that checkout afterwards. No toolchain/package installation:
SDK auto-download is disabled. Run one Gradle command at a time, from the copied repo
root, reusing its wrapper:

```sh
nice -n 19 ./gradlew -p spikes/resolver --no-daemon --max-workers=4 \
  :resolver:testDebugUnitTest :proof-app:assembleDebug :proof-app:lintDebug < /dev/null
nice -n 19 ./gradlew -p spikes/resolver --no-daemon --max-workers=4 \
  :proof-app:dependencies --configuration debugRuntimeClasspath < /dev/null
```

Redirecting stdin is especially useful when driving the remote shell with a script;
Gradle must not consume the following inspection commands as interactive input.

Inspect `proof-app/build/outputs/apk/debug/proof-app-debug.apk` with the existing SDK's
`aapt2 dump badging`, list ZIP `lib/**` entries, and record size and DEX method-reference
counts. Keep logs/APKs outside git. The source-tree hygiene check is still
`scripts/check-public-hygiene.sh`; the shipping app's gates are unchanged.

Host tests discover the original `contracts/fixtures` relative to this build root:
2 catalog/selection pairs, 11 invalid catalogs, 6 playback sequences, and focused
adapter/night tests. They use an injected extraction function, constructed library
objects and an in-memory connection. No sockets, real service calls, OAuth, login,
cookie store or device are involved. Normal Gradle dependency resolution is allowed.

`StreamResolver.resolve` is blocking; it must run off the UI thread in any future
integration. `NoSession` is the only supplied session implementation. Do not call the
production resolver during this spike or mistake a returned URL for verified playback.
