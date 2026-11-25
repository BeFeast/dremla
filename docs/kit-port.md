# Brand-kit port

Issue #2 copies the accepted brand kit and the pure playback logic from the prototype baseline named in the issue. The copies are literal: same relative paths, same bytes.

## Files

`docs/kit-port.sha256` lists the 38 files with the SHA-256 given in the issue, in the issue's order:

- licence texts: `app/src/main/assets/licenses/OFL-Archivo.txt`, `OFL-Inter.txt`;
- pure logic: `Fades.kt`, `Library.kt`, `ShuffleBag.kt`, `StartVolume.kt`;
- brand constants: `ui/BrandTheme.kt`;
- resources: the TV banner (xhdpi, xxhdpi), the adaptive-icon foreground and monochrome layers, `mark_night.xml`, six fonts (Archivo and Inter, weights 300, 400 and 600), the launcher and round launcher icons (adaptive XML and one PNG per density), `colors.xml`, `strings_brand.xml`, `themes.xml`;
- tests: `FadesTest`, `LibraryTest`, `RemainingTimeTest`, `ShuffleBagTest`, `StartVolumeTest`.

Every file is byte-identical except `LibraryTest.kt` (next section).

Comments and one test name in the ported files name the brand kit's own build tooling or another identifier from the prototype:

- `ui/BrandTheme.kt` lines 2–3, 6 and 30;
- line 2 of `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml`, `drawable/mark_night.xml`, `values/colors.xml` and `values/strings_brand.xml`;
- `LibraryTest.kt` line 139, where a doc comment gives an example static host;
- `ShuffleBagTest.kt` line 14, a doc comment that names two classes of the prototype;
- `StartVolume.kt` line 37, a doc comment that mentions a debug extra of the prototype;
- `FadesTest.kt` line 52, the test name `shortDebugTimerWithShortFade`, which refers to a debug timer setting of the prototype (3 min with a 1 min fade).

None of that tooling, those classes or the debug settings are part of this repository. The lines stay because the issue asks for these files with their listed SHA-256, and any edit would change it. This is a deliberate exception to the issue's rule that no other prototype identifier enters the repository. A grep of the 38 files for the components under [Not ported](#not-ported), the kit's tooling and its token file finds no other line naming them.

## The LibraryTest.kt substitution

One line differs from the listed file: line 180, in `nextHopResolvesAndRefusesDowngrades`.

| | Line 180 passes this origin to `Library.nextHop` |
|---|---|
| listed | `URL("http://<private LAN address>:8790/a")` |
| committed | `URL("http://collection.example:8790/a")` |

The listed line names a private LAN address with a port. `AGENTS.md` forbids LAN addresses in this repository, and `scripts/check-public-hygiene.sh` fails on them. `collection.example` is a reserved documentation name (RFC 2606). The port, the path, the status code (301) and the expected result are unchanged, so the assertion still checks that a redirect from an `http://` origin to `https://` is followed. `java.net.URL` does not resolve the host here, and the test compares strings.

- Listed SHA-256: `22baee644fdea92d6347e9a0b16b539f83ff63e6b408ec87fd48d12733af4ded`
- Committed SHA-256: `9ac2dc3d890b5f4618cdef2ba6781bae6553c0b84704bf1a9f2043a9e9f49449`
- SHA-256 of the listed file with line 180 deleted: `e68af20f5ee74bc387fba51d1cfe6de39b1c915e008ca80f55871822de41a08b`, computed from the baseline when the file was ported. The committed file with line 180 deleted has the same SHA-256.

The listed line 180 is not in the repository, and the check never needs it.

## The check

`scripts/check-kit-port.sh` (no arguments) reads the manifest and fails if it does not list exactly 38 files, if a listed file is missing, or if a file's SHA-256 differs from the listed one. A listed path must be canonical: relative, without a `./` prefix and without a `.`, `..` or empty segment. A path listed twice, or two paths that differ only in case, fail too, so an alias of one file cannot take the line of another and keep the count at 38.

The substitution is pinned in the checker itself, like the count: the path of `LibraryTest.kt`, line 180, the SHA-256 of the listed file with line 180 deleted (`e68af20f…` above) and the SHA-256 of the committed line 180. The manifest must have exactly one `substituted` line, naming that path, that line and that SHA-256; a second one, one for another file or none at all fails. The manifest records the committed SHA-256. The check fails unless all three hold:

1. the committed file has the recorded committed SHA-256;
2. the committed file with line 180 deleted has the pinned SHA-256 of the listed file with line 180 deleted, so the two files differ on line 180 only;
3. line 180 is exactly the committed text, which passes `http://collection.example:8790/a` to `Library.nextHop`; the checker compares its SHA-256.

An edit elsewhere in the file fails the second check even if the committed SHA-256 is recorded again. Any other text on line 180 fails the third check, whether or not it still contains `collection.example`, and fails the first one too until the committed SHA-256 is recorded again. Accepting another substitution means editing the checker, which shows up in review.

`scripts/test-kit-port.sh` is its self-test on a scratch copy. The intact copy passes, and so does a later change recorded as the next section describes. These fail:

- a flipped byte in a font or an XML resource, a missing file, a dropped manifest line;
- a path alias in place of another file's line: a `./` prefix, a `..` segment, a duplicate path, a path that differs only in case;
- a `substituted` line for another file, a second one, none, one with another SHA-256 without the line, one with another line number;
- a malformed change record, and one that is not directly above a listed line;
- in `LibraryTest.kt`: an edit outside line 180 with the committed SHA-256 recorded again; another line-180 text without it (two problems: the committed SHA-256 and the line text); a gutted line 180 that still contains `collection.example`, and a line 180 without it, both with the committed SHA-256 recorded again.

Each broken copy must fail with exactly the expected problems, one unless noted. Both scripts run as stages of `scripts/verify-outcome.sh` (see [ci.md](ci.md)).

## Changing a ported file later

The 38 files stay byte-identical to the issue's list until another issue asks to change one. That change goes in its own reviewed pull request, which also edits the file's line in `docs/kit-port.sha256`: the line gets the new SHA-256, and a comment line directly above it names the issue and the SHA-256 listed in issue #2:

```
# was <SHA-256 listed in issue #2> (#<issue>)
<new SHA-256>  <path>
```

The gate then checks the new value and names the issue on the file's `ok` line. A comment that starts with `# was` in another form, or one that is not directly above a listed line, fails the check. The manifest still lists 38 files. A change to `LibraryTest.kt` also updates the substitution pinned in `scripts/check-kit-port.sh`, in the same pull request.

## Line endings

`.gitattributes` marks the listed files as not text (`-text`; the fonts `binary`; the PNGs were already binary) and keeps `docs/kit-port.sha256` at LF. Without these lines a checkout with `core.autocrlf=true` writes CRLF into the `.kt` and `.xml` files and into the manifest, and the check fails on bytes the repository never changed. The two OFL texts already mix CRLF and LF line endings, and the attribute keeps them as they are.

## remainingParts

`app/src/main/java/com/befeast/sleepmusic/ui/RemainingTime.kt` holds `remainingParts`, taken from the prototype's player screen with its doc comment, signature (`internal`) and body unchanged. Only the package line is new; the function needs no imports and no helper. `RemainingTimeTest` compiles against it unchanged.

## Build changes

None. The ported sources compile against the dependencies the scaffold already declares, and the tests run on the existing JUnit and Robolectric setup. `LibraryTest` runs under Robolectric for a real `org.json` and serves HTTP from a plain `ServerSocket` on loopback.

## Not ported

Later issues rewrite these from the PRD: the playback service, the engine, the media session, the sleep routes, the accessibility and device-admin components, `MainActivity`, the UI screens, preferences, autoplay, remote keys, selection code, the network security config and `values/strings.xml`.
