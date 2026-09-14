# K-Line Android capture pilot

Track completed work and remaining phases in the [Android checklist and roadmap](../docs/android-roadmap-checklist.md).

Native Kotlin / Jetpack Compose application for the first capture-and-recovery phase. The app is named **K-Line Pilot**, package `com.kline.catalog.pilot`, and requires Android 14 or later. It connects exclusively to an isolated loopback fixture service; there is no production endpoint selector.

The separate **K-Line Staging** build connects to the isolated hosted API, supports actual uploads, hosted Receiving, draft details/counts, reviewed Pricing and live Stock, and installs alongside Pilot with independent private data. See [staging setup and verification](../docs/android-staging.md).

## What is implemented

Current build: **0.1.3**, with web-aligned navigation, light/dark appearance, multi-photo selection and consecutive camera capture. The unavailable A26 still has 0.1.0. Version 0.1.1 introduced expired-session messaging and sign-out cancellation ordering.

- Fixture sign-in, authorised branch selection and Android Keystore-encrypted session storage.
- Full category breadcrumbs, cached by account and branch, and locally saved delivery drafts.
- CameraX camera and system photo picker; private original storage and a JPEG derivative capped at 5 MiB.
- Explicit photo review before upload; captions remain in the image, with no unimplemented typed-caption field.
- Room-backed queue, stable photo IDs, WorkManager retries, persisted server retry timing, delivery linking and receipt readback.
- Independent uploaded / needs review / needs attention states. No stock-receipt endpoint exists in the pilot service.
- Versioned Room schema with a non-destructive 1-to-2 migration.

Receiving, Pricing and Stock match the web workspace navigation. Pricing and Stock tabs explain the next increments. See the [UI alignment policy](../docs/android-web-alignment.md). They do not simulate working AI, matching or stock screens.

## Build

Use JDK 17, Android SDK 36, Build Tools 35.0.0, and the checked-in Gradle 8.13 wrapper. Create ignored `local.properties` with your SDK path, escaping the Windows drive colon, or use Android Studio to generate it.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. This is debug-signed, with no release signing key in the repository. The wrapper pins the distribution SHA-256. The tested AGP 8.13.2 / Kotlin 2.3.21 toolchain is deliberately pinned; dependency-update lint notices are reviewed separately. [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

## Run the isolated phone pilot

Start the service from the repository root:

```powershell
node android/pilot-server/server.mjs
```

In another terminal, with the intended phone selected through ADB:

```powershell
adb reverse tcp:5117 tcp:5117
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.kline.catalog.pilot/com.kline.pilot.MainActivity
```

For two connected phones, add `-s <device-id>` to each ADB command. Sign in with **pilot / pilot-only**. A second fixture account is **pilot2 / pilot-only**, for account-isolation testing. These credentials have no production access.

Create a delivery, choose its category, select photos or open the camera and take consecutive photos, choose **Done**, then open a saved photo, then choose **Add to Receiving**. Offline capture is available after sign-in and category caching. Upload requires the local service and USB forwarding. Disconnecting USB intentionally makes this build offline; it is not yet a shop-ready network deployment.

Sign-out locks the local queue without deleting photos. Reauthentication uses the same fixture account. Photo originals are retained; uninstalling/clearing app data removes Android's private storage. Do not uninstall during retention or update tests; use `adb install -r` with the same signing key.

## Verification

```powershell
node --test android/pilot-server/server.test.mjs
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Device tests use generated shirt images rather than personal photos. Install the application and test APK explicitly so the test runner does not remove the pilot afterward:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.kline.pilot.PilotDeviceTest#durableQueueAndRealTransfer com.kline.catalog.pilot.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.kline.pilot.PilotDeviceTest#upgradePreservesPendingPhoto com.kline.catalog.pilot.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.kline.pilot.PilotDeviceTest#interruptedRequestsKeepOneIntake com.kline.catalog.pilot.test/androidx.test.runner.AndroidJUnitRunner
```

Run `visibleDeliveryScreen` only with the phone unlocked, after the durable queue test signs in. Each durability test creates an explicitly named 50-photo test delivery and uploads one entry; it is not a throughput claim for 50 background uploads.

Additional checks in `PilotRemainingChecksTest`: `expiredSessionAndAccountSwitchPreserveQueue`, `contentUriAndInvalidPhotoHandling`, and `installedUpgradeRetainsPendingDraft`. Run the content-URI test before the upgrade check; the latter validates its existing unsent draft. The import test leaves one clearly labelled synthetic gallery image for the separate system-picker interaction. Session-expiry testing invalidates fixture sessions only.

The fixture service exposes loopback-only `/__test/fault` controls for dropped upload acknowledgement, failed linking, receipt races, reconciliation, membership conflict and session expiry. `/__test/state` exposes only fixture data for assertions. Local fixture data lives in ignored `pilot-server/data`. The service is a contract simulator, not the production POS backend; its tests do not establish production integration correctness.

## Limits before expanding scope

See the [prototype report](../docs/android-prototype-report.md) for the completed checks, including S24+ gallery import, session recovery and in-place app update. Complete the remaining real-phone matrix: A26 camera/gallery, flash/front camera, reboot, real network transitions, low storage and denied permissions. Confirm photo-retention policy, staff account usage and an isolated hosted integration environment before connecting to the real API. WorkManager reschedules persisted work; Android may defer it during sleep, and a manually force-stopped app must be reopened. [Persistent background work](https://developer.android.com/develop/background-work/background-tasks/persistent)

Signed distribution, production use, AI review and matching remain open. Hosted Receiving, count editing, Pricing and Stock are available in the separate staging build; see its verification report for device coverage. See the [screen specification](../docs/android-screen-flow.md) and [API contract](../docs/android-api-contract.md).
