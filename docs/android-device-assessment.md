# Android pilot device assessment

**Checked:** 10 September 2026  
**Pilot scope:** Two phones, as specified by the owner. Both have now been assessed through authorised ADB connections.

## Phone 1: Samsung Galaxy S24+

| Check | Observed result |
| --- | --- |
| Device name reported by Windows | Samsung Galaxy S24+ |
| Model reported by Android | SM-S926U |
| Android version | 14 |
| Android API level | 34 |
| CPU ABI | arm64-v8a |
| Physical display resolution | 1440 × 3120 |
| Display density | Physical 450 dpi; current override 600 dpi |
| Memory reported by Android | 11,337,740 kB |
| Internal data storage | Approximately 223 GB total, 208 GB used, 15 GB available |
| Developer connection | ADB connected and authorised |

These are direct device observations, not inferred specifications. The device serial is intentionally omitted from this document.

The phone is suitable for the initial native-app prototype. Its current display scaling should be included in UI checks. Storage is 94% occupied, so queue capacity checks and clear low-storage behaviour are especially relevant. Do not alter the owner's display settings or remove phone data for testing.

## Phone 2: Samsung A26

| Check | Observed result |
| --- | --- |
| Device name reported by Windows | Daphne's A26 |
| Model reported by Android | SM-A266B |
| Android version | 16 |
| Android API level | 36 |
| CPU ABIs | arm64-v8a, armeabi-v7a, armeabi |
| Physical display resolution | 1080 × 2340 |
| Display density | 450 dpi; no override reported |
| Memory reported by Android | 5,557,264 kB |
| Internal data storage | Approximately 105 GB total, 58 GB used, 47 GB available |
| Developer connection | ADB authorised after the owner unlocked the phone |

Use this phone as the pilot's lower-memory test device. It also provides Android 16 coverage, complementing the first phone's Android 14 installation. Both support arm64, so an arm64 pilot APK can serve both devices. Actual capture speed, upload recovery and power-management behaviour still require testing on each phone.

## Development workstation

- Android Debug Bridge is installed and can communicate with the phone.
- Android Studio is installed in the standard Windows location.
- Android SDK platform 36 is installed.
- Build Tools 35.0.0, 36.0.0 and 36.1.0 are installed.
- The Java executable on PATH reports OpenJDK 17.0.17.

This confirms the main development tools are present. A Gradle build and APK installation have not yet been tested. Select and verify compatible Android Gradle Plugin, Gradle, JDK and Kotlin versions when scaffolding the project.

## Practical implications

1. Use both phones for the first capture, offline persistence and upload-recovery prototype.
2. Use the A26's lower reported memory as the pilot performance baseline; also test the S24+'s display scaling and limited remaining storage.
3. Recommend Android 14 / API 34 as the minimum for this two-phone pilot, with Android 16 / API 36 as the proposed compile/target SDK. Validate build-tool compatibility and version-specific behaviour during project setup; broader device support would need a separate decision.
4. Test with the owner's current display scaling and ordinary shop network conditions.
5. Exercise low-storage handling using controlled test conditions rather than filling the owner's remaining storage.
6. Use a separate development application ID and isolated test data so the prototype does not disturb the existing catalog web app or its saved photo queue.

## Next step

Device discovery, the [screen-flow specification](android-screen-flow.md) and [API contract](android-api-contract.md) are complete. The authorised capture prototype is now installed on both the A26 and S24+. Both passed persistence, batch, migration, interruption and restart checks. See the [prototype report](android-prototype-report.md) for measurements and outstanding screen/camera checks. The broader merchandise workflow follows after its reliability checks pass.

The original hardware assessment was read-only. The subsequent authorised prototype phase installed the separate pilot APK; production merchandise remains unchanged.
