# MeshChat — offline mesh chat over a Wi-Fi hotspot

Native Android (Kotlin) app that lets phones on a Wi-Fi hotspot with **no
internet** chat with each other. One phone hosts the hotspot; every phone runs
this app. No server, no signaling, no cloud at runtime.

## How it works

- **Autodiscovery** — each phone advertises `_meshchat._tcp.` over NSD
  (Android's mDNS/DNS-SD wrapper) and discovers the others.
- **Transport** — each phone runs a `ServerSocket` on an ephemeral port. On
  discovering a peer it resolves the service and opens a plain TCP socket.
- **Full mesh broadcast** — every typed message goes to all connected peers,
  and every received message is relayed to all *other* peers, so everyone sees
  everything. A per-message UUID in a "seen" set breaks relay loops.
- **Single connection per pair** — both phones discover each other, but only
  the lexicographically smaller service name dials out, so you get one socket
  per pair instead of two.

## Build (no laptop needed)

The APK is built in the cloud via GitHub Actions:

1. Push to any branch (or run the **Build APK** workflow manually).
2. The workflow sets up JDK 17, generates the Gradle wrapper (8.4), runs
   `assembleDebug`, and uploads `app-debug.apk` as an artifact.
3. Download the artifact from the workflow run.

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

1. On each phone, enable **Install unknown apps** for your file manager /
   browser (the APK is an unsigned debug build).
2. Sideload `app-debug.apk` onto each phone and open MeshChat.
3. One phone turns on its Wi-Fi hotspot; the others join that hotspot's Wi-Fi.
4. Wait **10–20 seconds** — NSD resolution can be slow/flaky. Watch the peer
   count in the status bar. Tap **Rescan peers** if a phone doesn't show up.

## Files

```
app/src/main/java/com/meshchat/app/MainActivity.kt   all logic + UI (programmatic)
app/src/main/AndroidManifest.xml                     permissions
app/build.gradle                                     module build config
build.gradle                                          root plugin versions
settings.gradle                                       modules + repos
gradle.properties                                     Gradle/AndroidX flags
.github/workflows/build.yml                           cloud build
```
