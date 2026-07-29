# Marketplace screenshot pipeline

Captures listing screenshots from a sandbox PhpStorm without manual interaction, mirroring the
hotpath-heatmap pipeline. Requires: macOS **Screen Recording permission** for the terminal app
(already granted on this Mac).

## Scene

`/Users/neoralphy/VCS/phpmagic-testproject/src/demo.php` (committed in that separate project, not
in this plugin repo) - one `Money` class that declares every magic method PHP calls implicitly
(`__toString`, `__invoke`, `__get`, `__set`, `__call`), followed by seven compact usage lines that
each trigger a gutter marker: `echo $money`, `(string) $money`, `"total: $money"` interpolation,
`$money(1, 2)` invoke, `$money->currency = ...` (`__set`), `$money->currency` read (`__get`), and
`$money->format(1, 2)` (`__call`). It is laid out so all gutter icons cluster in one viewport.

## Run

```bash
# 1. Launch the sandbox IDE (first run may reuse the cached PhpStorm 2025.3 or download it, ~15 min).
#    The properties file only sets idea.trust.all.projects so the trust dialog needs no click.
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PHPSTORM_PROPERTIES="$(pwd)/scripts/screenshots/phpstorm-shot.properties"
./gradlew runPhpStorm --args="/Users/neoralphy/VCS/phpmagic-testproject /Users/neoralphy/VCS/phpmagic-testproject/src/demo.php" &

# 2. Wait for the window (needs Screen Recording permission to see window names):
swift scripts/screenshots/winlist.swift        # lists windowID  owner  WxH  title

# 3. Capture ONLY the IDE window (never the full desktop - privacy):
screencapture -x -l <windowID> marketing/assets/screenshot-editor-dark.png
```

Wait for indexing to finish before capturing: the gutter markers paint after the daemon pass, so
give it ~30-60 s after the editor shows. Inspect the PNG, adjust font size / window size / scene
file, re-capture. Final assets belong in `marketing/assets/`.

## First-run gotcha: the license flow must be allowed to show

PhpStorm is commercial: on the sandbox's FIRST run expect a license/agreement dialog window. It
needs ONE manual click by the owner - **Log In to JetBrains Account** in the Manage Licenses dialog,
which is a browser OAuth round-trip ("Waiting for login in browser..."). That requires the owner's
credentials at an unlocked screen and cannot be automated. The choice then persists in
`build/idea-sandbox/.../config_runPhpStorm`, so every later capture run is fully unattended.

Do NOT try to suppress this flow with consent/config properties. hotpath's notes record that doing
so deadlocked the IDE headless, looping "awaiting LicensingFacade.getInstance()" in `idea.log` with
no window at all. If that symptom appears: `pkill -9 -f "PhpStorm-2025.3-aarch64"`, confirm this
properties file has not grown suppression flags, relaunch.

Window-hunting notes (from hotpath): the sandbox IDE's `winlist.swift` owner name is **`Main`** (not
"PhpStorm"), the splash window (~575x182) reports "could not create image" from `screencapture -l`
(capture the main ~1400x1000 window instead), and the license dialog is ~860x580.
`pgrep -f PhpStorm-2025.3` + matching `kCGWindowOwnerPID` is the reliable way to find the windows.

## Theme flip (light variant) - only while the IDE is stopped

Persistent sandbox config lives under `build/idea-sandbox/.../config_runPhpStorm/` and the IDE
rewrites it on exit, so edit it ONLY while the IDE is stopped. To capture a light-theme variant
(mirroring hotpath): write `options/laf.xml`
`<component name="LafManager" autodetect="false"><laf themeId="ExperimentalLight" /></component>`
**and delete `options/colors.scheme.xml`** so the editor scheme follows the LaF; then relaunch and
capture to `marketing/assets/screenshot-editor-light.png`. Back to dark: restore
`colors.scheme.xml` and set `themeId="Islands Dark"` in `laf.xml`.

## crop.swift

`swift crop.swift in.png out.png x y w h` (pixel coords) crops a PNG - used for a gutter close-up
asset from the full-window capture.
