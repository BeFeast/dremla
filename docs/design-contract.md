# Dremla — Design Contract (v0)

Mode: `literal-port` of the accepted brand kit (accepted by the operator on 2026-10-18).
Target viewport: 1920x1080 at 320 dpi (Android TV).
Status: accepted.

## Binding Scenes

| Scene | Binding details |
|---|---|
| player | black ground; large track title; remaining time in the `1 h 47 min` style (whole minutes, rounded up); warm dim text |
| player, long title | the title wraps or ellipsizes without overlapping the remaining time |
| menu | timer length, start volume, `TV off`, refresh collection, like and never again; every item reachable with the D-pad; focus fill visible |
| download | progress of the first download or a refresh; error with retry |
| finished | pure black, every pixel `#000000` |
| black | after about 10 s of playback: pure black |

The JVM golden harness (Roborazzi) renders each scene at 1920x1080 with injected state; a failing comparison emits actual and diff images. No bespoke pixel-crop checks.

## Colour Tokens

Only `brand_night_*` colours appear inside the app; `brand_launcher_*` colours appear only on the launcher icon and the TV banner.

| Token | Value |
|---|---|
| `brand_night_ground` | `#000000` |
| `brand_night_panel` | `#15100F` |
| `brand_night_line` | `#2B1C18` |
| `brand_night_key` | `#4A3229` |
| `brand_night_track` | `#2A1512` |
| `brand_night_ember` | `#B8443C` |
| `brand_night_text` | `#AD7A5B` |
| `brand_night_focus_fill` | `#2C1715` |
| `brand_launcher_navy` | `#163065` |
| `brand_launcher_white` | `#FFFFFF` |
| `brand_launcher_dot` | `#D9776D` |

Rules: no blue or white inside the app; the peak text colour is `#AD7A5B`; the blue channel inside the app never exceeds 91 of 255.

## Typography And Assets

Archivo for Latin with Inter as the Cyrillic fallback, weights 300, 400 and 600, both under SIL OFL 1.1 with licence texts shipped in `app/src/main/assets/licenses/`. Launcher icon: adaptive (foreground mark, navy background, monochrome layer). TV banner: 320x180 (xhdpi) and 480x270 (xxhdpi). The product name lives only in `values/strings_brand.xml` (`app_name`).

## Focus And Remote

Focus is a filled `brand_night_focus_fill` shape that is always visible on the focused control; keycap hints name the remote keys; OK, Right, Back and volume behave as in the PRD.

## States

Loading (first download), empty (no network on first run, with retry), error (host unreachable on refresh: the old collection keeps playing and the menu shows a non-blocking notice), finished (pure black).

## Verification

- `verifyRoborazziDebug` runs in CI. References are recorded from the accepted kit only in the UI issue and reviewed in its PR.
- Human acceptance on the real panel: near-black legibility and icon and banner readability, by the operator during the install issue.
