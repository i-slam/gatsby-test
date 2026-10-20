# RAM Cleaner (Android)

Sideloaded Android tool (minSdk 29, targetSdk 35) that monitors RAM, kills
background processes of non-whitelisted user apps, and shows a floating
bubble with a live RAM graph and the top memory eaters.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```sh
cd RamCleaner
./gradlew :app:assembleDebug :app:assembleRelease
```

APKs land in `app/build/outputs/apk/{debug,release}/`. The release APK is
signed with the debug key so it installs directly.

CI (`.github/workflows/build-apk.yml` at the repo root) builds both APKs on
every push that touches `RamCleaner/` and uploads them as the
`RamCleaner-apk` artifact.

## Install & permissions

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
# Optional, for exact per-app MB in the bubble:
adb shell pm grant com.example.ramcleaner android.permission.DUMP
```

Without `DUMP`, grant Usage access from the app to get the
"recently active" fallback list. The bubble needs "Display over other apps".
