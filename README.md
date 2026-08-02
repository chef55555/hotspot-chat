# Twenty48 — a 2048 puzzle game for Android

Native Android (Kotlin) implementation of the classic 2048 sliding-tile
puzzle. Swipe to slide the tiles; equal tiles merge; reach **2048** to win
(and keep going for a high score).

No dependencies beyond AndroidX basics, no assets. The whole game — logic,
custom Canvas rendering, animations, UI — lives in a single Kotlin file.

## Features

- Smooth slide, merge-pop, and spawn animations (custom `View` + Canvas)
- Swipe gestures anywhere on the board
- Score + persistent best score, with floating "+N" score-gain animations
- Haptic feedback on merges
- **Dark theme** — follows the system setting, toggleable in-app (☾/☀)
- One-step **Undo**
- Game state survives app restarts (pick up where you left off)
- Win and game-over overlays
- **In-app auto-update**: on launch the app checks `version.json` on the
  repo's latest GitHub Release; if CI has published a newer build, a banner
  offers a one-tap download + install (this is why the app has the
  `INTERNET` and `REQUEST_INSTALL_PACKAGES` permissions)
- Classic 2048 color palette, adaptive launcher icon

## Build (no laptop needed)

The APK is built in the cloud via GitHub Actions:

1. Push to any branch (or run the **Build APK** workflow manually).
2. The workflow sets up JDK 17 and Gradle 8.4, runs `assembleDebug`, and
   uploads `app-debug.apk` as an artifact **and** publishes it as a GitHub
   Release — on a phone, grab it from
   `https://github.com/<owner>/<repo>/releases/latest`.

### Versions (the usual first-build failure point)

| Component | Version |
|-----------|---------|
| AGP       | 8.1.4   |
| Kotlin    | 1.9.10  |
| Gradle    | 8.4     |
| JDK       | 17      |
| compileSdk / targetSdk | 34 |
| minSdk    | 24      |

## Install & run

1. On the phone, enable **Install unknown apps** for your browser / file
   manager (the APK is a debug build signed with a committed throwaway key).
2. Download `app-debug.apk` from the latest Release (or the workflow
   artifact) and install it.
3. Play. Swipe up/down/left/right; tiles with the same number merge.

The signing key is committed to the repo (`app/twenty48-debug.keystore`) so
every CI build shares one signature and newer builds install over older ones
without an uninstall.

## Files

```
app/src/main/java/com/twenty48/app/MainActivity.kt   game logic + view + UI
app/src/main/AndroidManifest.xml                     app manifest (no permissions)
app/build.gradle                                     module build config
build.gradle                                          root plugin versions
settings.gradle                                       modules + repos
gradle.properties                                    Gradle/AndroidX flags
.github/workflows/build.yml                          cloud build + release
```
