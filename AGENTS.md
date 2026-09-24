# Anki-Android Build Guide (Arlea1221 Fork)

## Environment
- **JDK**: Android Studio JBR at `C:\Program Files\Android\Android Studio\jbr` (JDK 21)
  - Set `$env:JAVA_HOME` before building if `java` is not on PATH
- **Gradle**: 9.7.1 (wrapper auto-downloads)
- **NDK**: 28.2.13676358, **CMake**: 3.22.1 (under `~/AppData/Local/Android/Sdk`)

## Build Commands

### Debug (fast, no signing needed)
```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :AnkiDroid:assemblePlayDebug -PabiFilter=arm64-v8a --console=plain
```
APK output: `AnkiDroid/build/outputs/apk/play/debug/AnkiDroid-play-arm64-v8a-debug.apk`

### Release (with fallback test keystore — no env vars needed)
```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :AnkiDroid:assemblePlayRelease -PabiFilter=arm64-v8a --console=plain
```
APK output: `AnkiDroid/build/outputs/apk/play/release/AnkiDroid-play-arm64-v8a-release.apk`

### Release (with production signing — set env vars first)
```
$env:KEYSTOREPATH='C:\path\to\your-release-keystore.jks'
$env:KEYSTOREPWD='your-store-password'   # or KSTOREPWD
$env:KEYALIAS='your-key-alias'
$env:KEYPWD='your-key-password'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :AnkiDroid:assemblePlayRelease -PabiFilter=arm64-v8a --console=plain
```

## Fallback Keystore
When `KEYSTOREPATH` is not set, the build uses `tools/fallback-release-keystore.jks`:
- storePassword: `Test@123`
- keyAlias: `my-key`
- keyPassword: `Test@123`

This produces a **test-signed** APK — fine for local use and sideloading, but **not publishable** to Play Store.

## Build Flavors
- `Play` — Google Play variant (default)
- `Amazon` — Amazon Appstore variant
- `Full` — Full/unrestricted variant
Always specify the flavor in the task name (e.g. `assemblePlayDebug`, not `assembleDebug`) — bare names are ambiguous.

## ABI Filter
Use `-PabiFilter=arm64-v8a` to build one ABI only (faster). Omit it to build all 4 ABIs + universal APK (much slower, especially with whisper.cpp native compilation).

## Native Build (whisper.cpp)
This fork includes whisper.cpp speech recognition via CMake/NDK:
- `AnkiDroid/src/main/cpp/CMakeLists.txt` — FetchContent pulls whisper.cpp v1.8.3
- `AnkiDroid/build.gradle` has `externalNativeBuild { cmake { cppFlags "-std=c++17" } }`
- CMakeLists.txt also sets `cxx_std_17` via `target_compile_features`
- First native build downloads whisper.cpp source (~few minutes), subsequent builds are cached

## Post-Merge Verification
After merging from upstream, common semantic conflicts to check:
1. `sharedPrefs` import path: `com.ichi2.anki.preferences.sharedPrefs` → `com.ichi2.anki.common.preferences.sharedPrefs`
2. `checkWebviewVersion` — removed by upstream; delete any calls/imports
3. `AnalyticsConstants.Actions` / `AnalyticsConstants.Category` — replaced by `LinkAction` enum in `:common`
4. `cppFlags` must use `-std=c++17` (not `-std-c++17` which is MSVC format)
