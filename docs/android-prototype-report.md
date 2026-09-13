# Android capture prototype: implementation and validation

See the [Android checklist and roadmap](android-roadmap-checklist.md) for completed work, remaining acceptance checks and later phases.

**Date:** 10 September 2026.  
**Phase:** Authorised capture-and-recovery prototype. Implementation is available; the complete two-phone acceptance gate is still open.

## Delivered

The separate **K-Line Pilot** application has been built and installed on the Samsung A26 and S24+. It includes native sign-in, branch selection, searchable full category paths, local delivery drafts, CameraX capture, system photo import, image review, original-image retention, a Room queue and WorkManager background transfers.

Uploads retain their original account, branch, delivery, category, format and UUID. A failed response can be retried without generating another identity. Delivery conflicts trigger a receipt-state check; already-received work can finish its stale local upload, while cancellation, legacy reconciliation and unrelated delivery conflicts remain actionable. No Android stock-receipt operation has been implemented.

The phone visibly distinguishes photos saved locally from confirmed uploads. Photo review displays the prepared JPEG, allowing caption legibility to be checked after resizing. The original remains in app-private storage. Sign-out locks the previous account's drafts; session tokens are encrypted with an Android Keystore key. Cloud backup and device transfer are excluded for this pilot's private data.

The APK connects only to the workstation's isolated test service through USB forwarding. It contains no production credentials or endpoint switch. Test account: **pilot / pilot-only**. Production catalog, inventory quantities, prices and the website's saved upload queue were not modified.

## Evidence obtained

| Check | Result and scope |
| --- | --- |
| Debug APK and test APK | Build succeeds with pinned Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21 and JDK 17; minimum API 34, target/compile API 36. |
| JVM API/recovery tests | 10 pass: category ambiguity/cycles, multipart identity and branch headers, gateway error handling, retry timing, dropped acknowledgement, receipt race, cancellation, legacy reconciliation and unrelated conflict. |
| Isolated service HTTP test | Pass: acceptance survives a dropped response and service restart, cross-branch reads are denied, retried ID stays singular and stock mutations remain zero. |
| A26 local persistence | 50 generated photos saved with originals and prepared files; records survive reopening the database. Account and branch queries remain separated. |
| A26 full background batch while locked | 49 uploads completed; one deliberate HTTP 413 rejection remained in attention; all 50 originals retained. Measured transfer phase: 6,964 ms over local USB forwarding. This is not a shop-network benchmark. |
| A26 HTTP interruption cases | Dropped upload acknowledgement, transient linking failure, unrelated membership conflict and legacy reconciliation checked through the real Android HTTP client. No stock changes. |
| A26 database upgrade | An actual version-one SQLite database migrated to version two; queued state, file reference and identity were preserved. No destructive migration fallback. |
| S24+ Android 14 data checks | All four tests pass: 50-photo persistence with account/branch isolation, database upgrade, interrupted HTTP requests, and the full batch. 49 photos uploaded, one deliberate rejection remained actionable, and all originals were retained. Batch transfer measured 6,437 ms over USB forwarding. |
| S24+ force-stop/reopen | Pass: the queued photo completed under its original ID after process replacement; the original file remained present. |
| S24+ visible layout | Pass after unlocking: the delivery screen was inspected at the existing 600 dpi display override. Branch, category, capture controls and bottom navigation fit without clipped text. The first assertion failed while locked; the later screenshot confirms the visible layout. |
| S24+ live rear-camera capture | Pass with the owner's OXFORD shirt: camera permission, capture, saved review and upload completed. OXFORD and 3XL remain legible in the prepared JPEG. Original: 4,614,281 bytes; upload: 2,130,765 bytes. The test server's SHA-256 matches the prepared phone file, delivery linking succeeded, and the original remains on the phone. |
| Force-stop/reopen | Accepted identity and original file verified after process replacement on A26 and emulator. The first A26 90-second polling window timed out with the photo still queued; a later reopen/readback confirmed completion. Immediate background completion is not established. |
| Emulator UI | Delivery layout inspected; CameraX captured the emulator's synthetic scene and saved a reviewable photo. This does not substitute for the two Samsung cameras. |
| Android lint | No errors; 22 advisories remain, principally dependency updates and style/API suggestions. They are not silently suppressed. |
| S24+ session recovery | Pass: expiry pauses the queue; signing into another account does not expose or submit the original account's photo; reauthentication as its owner resumes it under the same ID. |
| S24+ gallery and invalid input | Pass: provider-backed import and the actual system picker preserve the synthetic source bytes. Invalid image input reports an error and creates no saved-photo entry. |
| S24+ interrupted test connection | Pass: removing USB test-port forwarding during submission produced Retry scheduled. Restoring forwarding allowed acceptance under the same photo ID, with delivery linking and matching upload bytes. This does not establish Wi-Fi/cellular transition behaviour. |
| S24+ in-place APK update | Pass: upgrading to 0.1.1 retained an existing unsent draft, its review state, original and prepared file. The A26 remains on 0.1.0 until it is available. |

The first device test run also hit a test-method return-type error and a screen assertion while the A26 was locked. Those were separated from data assertions and corrected in the test harness. Camera automation initially attempted capture before readiness; the screen now explicitly displays **Opening camera…**, and the capture check waits for readiness. These initial failures are not counted as successful tests.

Evidence files: [A26 storage result](../verification/android-pilot/a26-device-test-report.json), [A26 batch result](../verification/android-pilot/a26-batch-test-report.json), [A26 restart](../verification/android-pilot/a26-restart-test-report.json), [emulator restart](../verification/android-pilot/emulator-restart-test-report.json), [delivery screenshot](../verification/android-pilot/emulator-deliveries.png), [photo-review screenshot](../verification/android-pilot/emulator-photo-review.png).

S24+ evidence: [storage result](../verification/android-pilot/s24-device-test-report.json), [batch result](../verification/android-pilot/s24-batch-test-report.json), [restart result](../verification/android-pilot/s24-restart-test-report.json), [delivery screen at current scaling](../verification/android-pilot/s24-pilot-deliveries.png). The earlier unsuccessful screenshot export is retained separately as diagnostic text.

The [live-camera result](../verification/android-pilot/s24-live-camera-report.json) records the real-shirt check. Camera access was granted while using the app and upload notifications were allowed. The live photo and preview screenshots are retained only in ignored local test storage, rather than added to repository evidence. This check used the isolated test delivery and did not receive stock or change production merchandise.

## Remaining acceptance checks

1. Unlock the A26 and verify the visible interface, camera/flash/front-back switching, gallery import and caption readability with the owner. No personal gallery photos were used in automated tests.
2. Complete the S24+ flash and front-camera checks. Its rear-camera capture, saved-image label legibility, gallery import and test upload now pass, alongside the Android 14 persistence, queue, migration, interruption, restart and delivery-screen layout checks.
3. Exercise reboot, Wi-Fi/cellular transitions, real low-storage failure and denied permissions end to end. Expired-session reauthentication and the interrupted USB test connection now pass, but the complete physical-device matrix remains open.
4. Review retention and recovery for unreferenced preparation files and duplicate camera source files before introducing cleanup. Nothing in the pilot automatically deletes saved originals.
5. Establish a hosted isolated integration environment and validate against the actual POS service before a production-enabled build. The local service mirrors the reviewed upload contracts but is a simulator.

An emulator was created specifically for layout and synthetic-camera checks because the A26 remained locked. This fallback allowed useful verification without changing its lock settings; it does not complete the outstanding physical-phone checks.

## Practical use and next gate

### Pilot 0.1.1 follow-up

The S24+ now has 0.1.1 installed. Two fixes accompany the additional checks: expired sessions are identified explicitly instead of being labelled offline, and sign-out waits for account-job cancellation before subsequent work is enqueued. Both were exercised through actual Android workers and the view model's sign-in/sign-out flow. Ten JVM tests still pass; the two additional recovery/import device tests and the separate in-place update test also pass.

Evidence: [session recovery](../verification/android-pilot/s24-session-recovery-report.json), [gallery import](../verification/android-pilot/s24-gallery-import-report.json), [connection recovery](../verification/android-pilot/s24-connection-recovery-report.json), [APK upgrade](../verification/android-pilot/s24-apk-upgrade-report.json).

The system-picker check selected only the generated `KLINE_PILOT_IMPORT_CHECK.jpg`, whose imported bytes were compared with the known fixture. That image remains in `Pictures/KLinePilotChecks` for repeatable verification; it is a cleanup candidate, not a personal gallery image. The app uses Android's selection-scoped picker rather than requesting access to the whole gallery. [Android photo picker](https://developer.android.com/training/data-storage/shared/photo-picker)

The owner deferred the A26 camera checks because its owner stepped away. That device's absence does not prevent the other prototype checks; it remains an explicit outstanding acceptance item.

Build, test and USB-forwarding instructions are in the [Android README](../android/README.md). The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. It is approximately 66.5 MiB and has not undergone release shrinking or release signing.

The current pilot needs the workstation test service running and the USB connection for uploads; it can keep capturing offline after initial sign-in/reference caching. It is not ready for ordinary shop operations. The website remains the operational merchandise workflow.

Finish the outstanding phone checks and review their results before requesting confirmation for AI review, matching, quantities/pricing, receiving and stock screens. No Git commit, production deployment or signed distribution was performed in this phase.
