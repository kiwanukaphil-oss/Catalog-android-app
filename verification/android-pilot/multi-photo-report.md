# Multi-photo capture increment — 14 September 2026

Build: 0.1.2-pilot, version code 3. Installed in place on S24+ SM-S926U; existing app storage retained.

## Changes

- System picker accepts up to 100 selected photos per invocation, matching the web intake limit.
- Sequential preparation bounds decoded-image memory. Each successful photo is committed separately in review state. Failed selections are reported without discarding successful selections.
- Progress shows the current selection and final saved count. UI actions claim the busy state synchronously to avoid overlapping preparation.
- Camera remains open after capture. Committed delivery photo count is visible; Done returns to Receiving without capturing or uploading another photo.

## Verification

- Gradle debug APK, instrumentation APK, JVM tests and lint passed. Dependency-update advisories remain.
- Emulator `multiplePhotoImportKeepsSuccessfulSelections`: valid / invalid / valid selection produced two distinct saved photo identities, retained both originals, left both in review, and reported two of three saved.
- Emulator `captureAndReviewOnEmulator`: two consecutive synthetic camera captures saved two photos without reopening the camera; Done returned to Receiving; saved-photo review opened successfully.
- Both instrumentation tests passed together: `OK (2 tests)`, 16.482 seconds.
- S24+: verified installed version, system picker launch, camera Done and return to Receiving. No new phone photo was captured, selected or uploaded during these checks. Existing delivery and saved-photo count remained visible.
- `git diff --check` passed.

## Remaining work

Multiple actual picker selections on the physical phone, camera flash/front-camera acceptance, process interruption during preparation, low storage, A26 testing and hosted backend integration remain open. The selection list itself is not a durable pending-import queue; photos committed before interruption survive, while unprepared selections must be chosen again. Failed preparation files remain retention/cleanup candidates and are not silently deleted.

Pricing, stock, AI and receipt functionality are unchanged. This build still uses the isolated workstation service. It is not a production release.
