# K-Line Android app: delivery checklist and roadmap

**Last reviewed:** 10 September 2026.  
**Current position:** Capture-and-recovery prototype implemented; full two-phone acceptance remains open.  
**Latest build:** K-Line Pilot 0.1.1 on S24+; A26 remains on 0.1.0 while its owner is away.

This is the current progress tracker for the Android app. The [proposal](android-app-proposal.md) explains the direction; the [prototype report](android-prototype-report.md) records detailed evidence. Existing website features do not count as completed native Android features.

Checked boxes mean completed within the stated scope. Unchecked boxes mean work or verification remains. **Deferred** means waiting for a dependency, not cancelled. A phase is complete only when its acceptance gate is met. Obtain owner confirmation before starting the next phase and before committing changes.

## At a glance

| Area | Status | What remains |
| --- | --- | --- |
| Android direction, device assessment and initial flows | Documented | Validate remaining operating assumptions with staff |
| Native capture, local queue and test uploads | Implemented | Finish physical-device and failure-case acceptance |
| S24+ verification | Partially complete | Reboot, network, storage, permissions and broader usability checks |
| A26 verification | Partially complete; physical checks deferred | Install 0.1.1, camera/gallery and remaining acceptance checks |
| Actual backend integration | Not completed | Hosted isolated environment and real contract/permission validation |
| Native AI review, matching and receiving | Not implemented | Build and verify the full merchandise-to-POS journey |
| Release and shop rollout | Not started | Signed distribution, support preparation and controlled two-phone trial |

The current APK uses a workstation-only test service through USB forwarding. It is not a production-ready POS app. Review and Stock tabs are informational placeholders. No production inventory was changed by this prototype.

## 1. Direction and preparation

- [x] Choose native Kotlin, Jetpack Compose, CameraX, Room and WorkManager.
- [x] Document Android scope; exclude iPhone and retain the website for desktop administration.
- [x] Assess the S24+ and A26; identify the A26 as the lower-memory baseline.
- [x] Document initial screen flow and review the existing API contract.
- [x] Prepare the local Android toolchain, pinned build wrapper and isolated test service.
- [ ] Confirm whether phones are shared between staff or assigned individually; retain account and branch isolation in either case.
- [ ] Validate the proposed complete workflow with staff before implementing later screens.

Evidence: [device assessment](android-device-assessment.md), [screen flow](android-screen-flow.md), [API contract](android-api-contract.md).

## 2. Capture-and-recovery prototype — implemented, acceptance open

### Implemented capabilities

- [x] Native test sign-in, authorised branch selection and encrypted session storage.
- [x] Account- and branch-scoped drafts; sign-out locks the previous account's work.
- [x] Searchable full category paths and cached categories for offline capture after setup.
- [x] Local delivery drafts and delivery selection.
- [x] Camera capture, camera readiness feedback, front/rear selection and flash controls in code.
- [x] System photo picker and image review before uploading.
- [x] Retain private originals and prepare upload JPEGs within the 5 MiB limit.
- [x] Persistent Room queue with stable photo identity and non-destructive schema migration.
- [x] WorkManager transfers, retry backoff, persisted server retry timing and visible queue states.
- [x] Recover from a lost acknowledgement without creating a new upload identity.
- [x] Link uploads to deliveries and read back receipt state after conflicts.
- [x] Resolve stale uploads already received; leave unrelated conflicts actionable.
- [x] Pause on expired sessions and resume the original account's work after reauthentication.
- [x] Exclude pilot private data from cloud backup and device transfer.
- [x] Build 0.1.1 and install it on S24+ with pending draft preservation verified.

### Verified evidence

- [x] Build debug app and instrumentation APK; pass 10 JVM tests and the isolated service HTTP test.
- [x] Android lint: zero errors; 22 advisories recorded.
- [x] Both phones: persist 50 generated photos, retain originals and isolate account/branch queries.
- [x] Both phones: background batch completes 49 uploads while one deliberate rejection remains actionable.
- [x] Both phones: exercise interrupted HTTP requests and version-one to version-two database migration.
- [x] Both phones: confirm queued work completes after force-stop and reopening under its original identity.
- [x] S24+: inspect delivery layout at its existing enlarged display setting.
- [x] S24+: capture a real OXFORD shirt; confirm brand and 3XL caption legibility in the prepared image and matching server hash.
- [x] S24+: test actual system picker with a synthetic image and reject invalid image input.
- [x] S24+: verify expired-session recovery and account switching without exposing another account's draft.
- [x] S24+: interrupt USB forwarding, restore the connection and confirm the same photo completes.
- [x] S24+: verify 0.1.0 to 0.1.1 APK upgrade retains an unsent draft and original bytes.
- [x] Emulator: inspect basic layout and verify synthetic capture/reopen behaviour.

These checks use an isolated service. Batch times are local USB measurements, not shop-network benchmarks. Force-stop recovery was checked after reopening; continuous execution while force-stopped is not promised. Screen-lock batch checks do not establish reboot recovery or unrestricted background execution.

Evidence: [build summary](../verification/android-pilot/build-summary.json), [A26 device checks](../verification/android-pilot/a26-device-test-report.json), [S24+ device checks](../verification/android-pilot/s24-device-test-report.json), [live camera](../verification/android-pilot/s24-live-camera-report.json), [session recovery](../verification/android-pilot/s24-session-recovery-report.json), [gallery import](../verification/android-pilot/s24-gallery-import-report.json), [connection recovery](../verification/android-pilot/s24-connection-recovery-report.json), [APK upgrade](../verification/android-pilot/s24-apk-upgrade-report.json).

### Remaining prototype acceptance

- [ ] **Deferred — A26 unavailable:** install 0.1.1 and verify upgrade preserves existing drafts.
- [ ] **Deferred — A26 unavailable:** physically test camera, gallery, caption readability and visible layouts.
- [ ] Verify flash and front-camera behaviour on both phones; S24+ rear-camera capture has passed.
- [ ] Verify device reboot recovery and queue state after restarting the phone.
- [ ] Test real Wi-Fi/cellular transitions, prolonged offline periods and interrupted shop-network transfers.
- [ ] Test permission denial/revocation and camera unavailable states; permission-allow paths alone are insufficient.
- [ ] Test low-storage/write failures using a controlled method that does not fill personal phones.
- [ ] Verify interrupted image preparation and recovery of partially prepared files.
- [ ] Define bounded retention and cleanup rules; originals are currently retained.
- [ ] Review the retained synthetic gallery test image as a cleanup candidate before any removal.
- [ ] Test TalkBack, larger text, dark mode, reduced motion, rotation and lifecycle transitions.
- [ ] Measure responsiveness, memory, battery use and representative shop batches, especially on A26.
- [ ] Review the 22 lint advisories and record resolutions or accepted reasons before release.
- [ ] Record outstanding defects and obtain owner acceptance of the capture-and-recovery phase.

**Gate:** Both phones meet capture, preservation, recovery and usability acceptance, or the owner explicitly accepts a documented exception. A26-dependent checks stay deferred while useful independent work continues within the authorised phase.

## 3. Actual backend integration — pending phase confirmation

- [ ] Prepare an isolated hosted test environment with HTTPS, restricted test accounts and a test branch.
- [ ] Validate actual authentication, reference data, upload and delivery contracts against the hosted backend.
- [ ] Validate branch permissions, account isolation, session expiry and restricted cost visibility against real endpoints.
- [ ] Verify upload identities, retries, conflict readback and already-received handling against the real service.
- [ ] Capture sanitised contract fixtures for AI, matching, pricing and receiving.
- [ ] Decide whether token refresh/revocation needs additive backend support; current pilot recovery uses reauthentication.
- [ ] Confirm caption guidance requirements: captions in image pixels are retained; a typed guidance field is not implemented.
- [ ] Document any required backend changes and verify they preserve the website's existing workflows.
- [ ] Pass the integration gate before enabling a production endpoint in an app build.

**Gate:** Real API behaviour, permissions and failure recovery are verified in isolation, with no unintended production writes.

## 4. Complete native merchandise-to-POS workflow — not implemented

### AI extraction and review

- [ ] Submit server-side AI batches with stable submission identities and visible progress.
- [ ] Recover status after app closure, screen lock or reauthentication; make ambiguous paid retries explicit.
- [ ] Display image evidence, uncertainty and editable extracted fields, including brand, size and sleeve type.
- [ ] Validate captions guide extraction correctly and do not silently override contradictory evidence.

### Product matching, quantities and prices

- [ ] Provide visual comparison and matching suggestions for the same model across sizes.
- [ ] Support manual matching, unmatched products and explicit confirmation of meaningful differences.
- [ ] Make product, variant, photo, lot and sellable-unit counts clearly distinguishable.
- [ ] Build size/quantity entry and validate totals before receiving.
- [ ] Support permitted pricing/cost fields and protect against stale concurrent edits.

### Receiving and stock

- [ ] Build receipt review with clear product, size, quantity, price and destination branch summaries.
- [ ] Implement receipt submission using the backend's stock transaction rules.
- [ ] Read back uncertain outcomes before retrying to prevent duplicate stock.
- [ ] Distinguish successful stock receipt from pending product-photo synchronisation.
- [ ] Provide actionable photo-sync retry without receiving the same stock again.
- [ ] Build receipt history, stock search, variants and relevant stock movement views.
- [ ] Verify the entire flow reconciles photos, variants and actual sellable units correctly.

**Gate:** Staff can complete capture → AI review → matching → quantities/pricing → receipt → stock verification on both phones, including failures and retries, without duplicate inventory.

## 5. Release quality and controlled rollout — not started

- [ ] Complete visual polish, empty/loading/error states and staff usability review.
- [ ] Complete performance and accessibility acceptance on both phones.
- [ ] Confirm release application identity and ownership of the signing key; store its encrypted backup outside Git.
- [ ] Produce and verify a signed release build, including release optimisation checks.
- [ ] Verify app updates preserve queued work, originals and account isolation.
- [ ] Establish trusted HTTPS APK distribution and a clear update process.
- [ ] Check applicable Android distribution/developer verification requirements before release.
- [ ] Prepare installation, permissions, recovery and troubleshooting instructions.
- [ ] Establish privacy-conscious diagnostics, support ownership and a rollback/recovery procedure.
- [ ] Obtain owner approval for a controlled shop trial and production access.
- [ ] Run the two-phone trial and reconcile received quantities, variants, prices and photo sync against POS.
- [ ] Monitor retry failures, completion times, battery impact and staff feedback; resolve release blockers.
- [ ] Obtain rollout acceptance and record the released version and evidence.
- [ ] Obtain explicit confirmation before committing the Android work.

**Gate:** Approved signed build, safe update path, support readiness and successful two-phone shop acceptance.

## Practical next steps

1. Finish independent prototype checks on S24+ and the controlled test environment: reboot recovery, permissions, network interruption and image-preparation recovery.
2. When A26 returns, install 0.1.1 and finish its physical capture/import and usability checks.
3. Record results here and in the prototype report; close or explicitly accept remaining prototype issues.
4. Obtain phase confirmation, then connect to the isolated hosted backend and validate the real contracts.
5. Build the native AI, matching and receiving journey before preparing a signed shop pilot.

## Decisions and optional scope

- iPhone is excluded. Checkout, payments and receipt-printer integration are outside the current merchandise-to-POS scope.
- Chunked upload resume, multiple evidence photos per intake and push registration are not implemented. Decide whether they are needed before adding them to the committed release scope.
- No percentage-complete estimate is used: a working capture prototype does not establish production readiness of the full workflow.

## Updating this checklist

For each completed item, record the date, app version, device/environment and evidence in the linked report. Keep implementation and verification separate: a control existing in code does not prove it works on both phones. Update this document at each acceptance gate; do not mark deferred or untested work complete.
