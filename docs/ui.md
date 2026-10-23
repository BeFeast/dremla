# Night UI

`NightScene` renders injected `SessionState` and `CollectionStatus` at 960 × 540 dp
(1920 × 1080 at xhdpi). MainActivity observes the existing playback graph; menu
callbacks call PlaybackControls and DownloadCollectionSource.refresh directly.
No second player, timer, downloader or sleep action exists in the UI.

| Scene | State |
|---|---|
| Player | Current title, artist/category, rounded-up timer through remainingParts |
| Long player | Same flowing column; title limited to two lines with ellipsis |
| Menu | Explicit long OK; hides player, contains timer tiles and five action rows |
| Download | Loading or downloading, including explicit refresh; byte progress, file count |
| First-run error | Connection notice and focused Retry calling refresh |
| Refresh error | Existing collection retained; non-blocking notice in menu. Also an unfinished first download whose verified tracks already play (#83) |
| Black | Engine blackScreen after ten seconds; all content suppressed |
| Finished | Finished or closeActivity; all content suppressed, even an open menu |

The engine owns blackout timing. Blackout closes an open menu and suppresses all
content. Holding OK after blackout reopens the menu over black (offline and online), because
it is the only remote path to the timer, source and Never again; BACK closes it and the scene
is black again. Neither menu
activity nor timer edits reset the ten-second deadline. Completion always wins.
A first-run retry has the same focus fill and ring as every menu item.

## Remote and focus

- Short OK: pause/resume when the menu is closed; choose the focused item in the menu;
  retry on first-run failure. Holding OK opens the menu and never also pauses or retries.
- Right: existing skip command when the menu is closed. D-pad navigation in the menu
  never falls through to skip, including the last tile's right edge.
- Menu: left/right traverses all six existing timer choices, including 45 minutes;
  up/down reaches TV off, refresh, like/unlike and never again.
  Initial focus is 90 minutes; the ember dot identifies the currently selected timer (during a
  Clock night, that night's timer: Clock's music minutes, or a pick made in that night; while
  Clock's request waits for the collection, its music minutes, and picks change nothing).
- There is no start-volume row: the app never changes the system volume (#34).
  TV off toggles its persisted setting; the row also identifies the available sleep route. During
  a Clock night, or while Clock's request waits for the collection, it reads «off · Clock night»
  and changes nothing (golden `night-menu-clock.png`).
- BACK closes the menu. Outside it, normal activity BACK behavior applies, without
  stopping an active session. Volume/mute keys remain entirely with the system.
- Long OK on an already focused menu control consumes the hold without choosing it.
  There are no restart, up-like or down-block shortcuts in the merged playback contract.

Controls use Compose focus targets with an explicit solid BrandFocus fill, inset
2 dp ember border and the weight-600 font family. They have no default Material
colours, ripple, scale or glow. All text and surfaces use BrandColors. Shapes are
square except the kit's dots and mark. No pulse or marquee is present.

## Reference recording

Use the same builder, SDK and font environment as CI. Run one Gradle build at a time:

```sh
./gradlew --no-daemon --max-workers=4 recordRoborazziDebug
./gradlew --no-daemon --max-workers=4 verifyRoborazziDebug
DREMLA_OUTCOME_SCOPE=repo DREMLA_GRADLE_MAX_WORKERS=4 scripts/verify-outcome.sh
```

Copy only `app/src/test/screenshots/*.png` from the builder into the worktree.
References are native Compose recordings of the accepted kit's layouts and samples,
not copies of the browser images. Strict comparison permits zero changed pixels.
Every captured frame is also checked for size, blue channel <= 91 and absence of
white; black and finished must contain black pixels only. Keep the original scaffold
black reference as an additional regression check.

The binding menu requires more controls than the kit mockup. Its panel is taller
and starts higher, with six timer tiles instead of five; all rows fit without
scrolling above the hint row. The player hint row lists only implemented shortcuts.
Download captions use file counts because the source does not expose downloaded
hours. The percentage sign retains the kit's smaller type. Font rasterization and
line metrics differ between Chromium and Android; layout, tokens and type scale
come from the ported theme. The merged ten-second black state takes precedence over
the kit's optional fade animation. Real-panel legibility remains operator acceptance.

## Automated acceptance

NightSceneTest covers all seven golden scenes, D-pad traversal and action callbacks,
visible focus-fill pixels for every menu control, first-run retry and refresh failure.
BrandNightTest pins the night palette and proves all three platform fallback families
build without silently returning the plain Archivo resource family. Existing
RemainingTimeTest, MainActivityKeysTest, NightSessionTest and NightPlatformTest retain
the rounding, transport, ten-second blackout and end-of-night regression checks.
