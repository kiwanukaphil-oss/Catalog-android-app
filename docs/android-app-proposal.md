# K-Line Android app: proposed approach and practical next steps

**Date:** 10 September 2026  
**Status:** Direction document; the capture-and-recovery prototype is implemented, with acceptance still open. Track current completion and remaining work in the [Android checklist and roadmap](android-roadmap-checklist.md).  
**Scope:** Android phones and tablets. iPhone development is excluded at the owner's request.

**Pilot update:** Both phones have been assessed: Samsung Galaxy S24+ (SM-S926U), Android 14, and Samsung A26 (SM-A266B), Android 16. Use the A26 as the lower-memory pilot baseline. See [device and workstation assessment](android-device-assessment.md).

## 1. Recommended direction

Build a native Android app with **Kotlin, Jetpack Compose and CameraX**, connected to the existing K-Line catalog and POS backend. Keep the website available for desktop administration and larger reviews.

The first release should make one complete journey excellent: **capture merchandise, upload reliably, review AI evidence, match products and sizes, confirm quantities and receive into POS**.

The app should reuse existing accounts, branch permissions, catalog categories, AI processing and stock rules. The backend remains the authority for prices, permissions and stock transactions. Avoid creating a second inventory database with competing business rules.

Compose is Android's recommended native UI toolkit and supports adaptive layouts, accessibility, motion and Material Design. CameraX provides the camera foundation. Use stable library releases, with versions pinned when the project is created. [Compose](https://developer.android.com/compose), [CameraX](https://developer.android.com/media/camera/choose-camera-library?hl=en)

## 2. Benefits that matter for this business

| Capability | Practical benefit |
| --- | --- |
| Capture and save without a network connection | Staff can continue photographing a delivery when connectivity drops. |
| Persistent upload queue | Each photo retains its original identifier, caption and destination through interruption and app restart. |
| Integrated camera and label capture | Product, brand, size and packaging evidence can be captured together, reducing later corrections. |
| Clear progress and notifications | Staff can see what is saved, uploading, ready for review or already received. |
| Fast quantity and size entry | A compact size grid replaces repeated editing of individual forms. |
| Visual comparison | Related products can be compared with their photos, captions and size variants visible together. |
| Tablet layouts | Photos and editing fields can appear side by side while phones remain comfortable for one-handed use. |

A native app does not automatically improve the AI model or repair backend failures. Its main contributions are better input, stronger local persistence and clearer recovery. AI extraction should continue on the server once the required images are uploaded.

## 3. First-release experience

### Home and navigation

Use a small navigation structure: **Deliveries, Review and Stock**, with a prominent **Capture** action. Show the active branch persistently. Put account and settings controls in a consistent secondary location.

The home screen should answer three questions: what arrived, what needs attention, and what is still synchronising. Avoid presenting every administrative function at once.

### Delivery capture

1. Select the branch, delivery name and full category path, such as `Clothing → Shirts → Formal`.
2. Capture or import several photos without repeatedly leaving the camera.
3. Review thumbnails and add or retain brand/size captions used for AI guidance.
4. Save the images to app-owned storage before showing them as saved.
5. Upload using each photo's original identifier, with individual progress and retry controls.

Retain the unaltered source image, including existing overlaid captions. Generate separate previews for display. Avoid filters or colour changes: corrections such as Kenneth Cole mint green and white demonstrate why colour fidelity matters.

Initially preserve the existing rule that each intake photo creates one lot. Supporting label photos should be added to that lot only after the backend explicitly supports multiple evidence images; do not silently interpret them as additional merchandise. Captions are evidence, not automatic authority for physical quantities.

### AI review and matching

Show the image beside extracted fields, highlight uncertain values and keep manual corrections easy. Let staff compare suggested matches and see differences before confirming product identity.

Distinguish these concepts throughout the interface:

- **Product:** the shared model or design.
- **Variant:** its sellable size/colour combination.
- **Lot:** the incoming merchandise record.
- **Photo:** evidence about the merchandise.
- **Units:** physical quantities being received.

Use summaries such as **12 products · 27 size variants · 45 units**, with drill-down to the underlying lots. Never imply that the number of photos equals the quantity received.

### Receiving and stock

Review the destination branch, product matches, sizes, quantities, prices and costs before receiving. Final stock receipt should require an online server acknowledgement in the first release.

When the response is interrupted, display **Checking receipt status** and read the saved result before offering another attempt. An already-received item must not be moved to another delivery or received again merely because a device still has an old upload entry.

Include searchable stock and product details. Barcode lookup can follow once supported devices and existing barcode formats are confirmed. Full checkout, payments, cash drawers and printer integrations are outside the initial release.

## 4. A polished, dependable interface

Create a K-Line design system using Material 3 foundations with restrained brand colours, consistent spacing and strong typography. Product imagery should be prominent and status colours should always have text equivalents.

Design requirements:

- Comfortable touch targets, readable text and support for larger system font sizes.
- Primary actions within easy thumb reach; preserve position when returning from a detail screen.
- Subtle haptic feedback for capture and confirmed completion; avoid distracting animation.
- Light and dark appearances with sufficient contrast and TalkBack labels.
- Clear loading, empty, offline and error states on every major screen.
- Separate **Saved on this phone** from **Uploaded** and **Received**.
- Show the reason and next action for a failure rather than a generic error alone.
- Keep cached stock visibly labelled with its last successful refresh time.

Use adaptive layouts on tablets rather than stretching a phone interface. [Android adaptive UI](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)

## 5. Technical architecture and recovery

| Layer | Proposed implementation |
| --- | --- |
| User interface | Kotlin, Compose, ViewModels and observable state. |
| Capture | CameraX and the Android system photo picker; copy selected files into app-owned storage. |
| Local persistence | Room for delivery drafts, identifiers, queue state and cached records; files stored separately from database rows. |
| Network access | A typed HTTPS API client sharing the existing backend contracts. |
| Background work | WorkManager for persistent deferred work; appropriate user-initiated transfer jobs for longer uploads on supported Android versions. |
| AI and inventory | Existing server workers, authentication, permissions and POS transaction services. |
| Notifications | Local upload progress; server completion notifications if a push service is introduced. Push is optional, and cannot be the only way to reconcile state. |

Android can pause work for power, network or system reasons. A force-stop is not equivalent to simply locking the screen. Promise safe recovery, not uninterrupted execution under every condition. On reopening the app, reconcile persisted jobs against the server. [Offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent?hl=en), [User-initiated transfers](https://developer.android.com/develop/background-work/background-tasks/uidt?hl=en)

### Required queue behaviour

Track transport and business status separately. A photo can be uploaded while its lot still needs AI review; a lot can already be received while an old device acknowledgement remains unresolved.

- Persist a stable client identifier, account, branch, category, delivery and file reference before uploading.
- Retry transient failures with bounded backoff; pause authentication failures until sign-in succeeds.
- Allow unrelated photos to progress when one photo has a recoverable item-specific problem. Pause the queue for shared outages or invalid authentication.
- Clear a pending entry only after confirming the server has the correct item and its required delivery association, or that the item is already received.
- Reconcile ambiguous responses instead of blindly repeating writes.
- Keep received delivery assignments immutable.
- Preserve saved work across application updates and tested local database migrations.
- Keep pending work scoped to its original account and branch. Signing in as another user must not upload or expose someone else's queued files.
- Explain that uninstalling the app or clearing its data can remove photos that have not reached the server. Do not treat local storage as a permanent backup.

Initially, retry an interrupted individual image from the start under the same stable identifier. True byte-level resume requires a separate multipart/chunked upload contract and should be added only if measured file sizes and network conditions justify it.

### Backend and security prerequisites

Document the current upload, item readback, delivery linking, AI batch, matching and receipt APIs, including response fields and retry semantics. Add contract tests around the recent storage-signing and already-received resume failures before building the native client against those contracts.

Audit the current sign-in flow for secure native session renewal and revocation. Do not embed database credentials, storage keys or AI provider keys in the APK. Protect device-held session material with Android platform facilities, require HTTPS and enforce branch permissions on the server.

Database and API changes should be additive and compatible with the current website. Test locally or in an isolated environment; use production merchandise only for an agreed pilot.

## 6. Distribution, updates and ownership

**Recommended pilot:** a signed release APK downloaded from a K-Line-controlled HTTPS page. Staff install it on a small number of approved phones. Android supports direct APK distribution, although users must permit installation from that source. [Alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution?hl=en)

Keep the package name and signing identity stable. Store the release signing key outside Git, with an encrypted backup controlled by the business. Test upgrading over the installed version with queued photos still present. Loss of the signing key can prevent normal updates. [App signing](https://developer.android.com/studio/publish/app-signing?authuser=6)

For direct distribution, provide an in-app update notice and a verified download link. Ordinary staff-owned devices should be expected to require user confirmation to install updates; do not promise silent updates. Google Play can be considered later if the installation base makes manual distribution burdensome.

Android distribution rules are changing. Google's published verification rollout begins on 30 September 2026 in Brazil, Indonesia, Singapore and Thailand. Uganda is not in that initial country list. Check applicable registration and verification requirements again before release; do not assume direct APK distribution will remain free of all registration requirements. [Android developer verification](https://developer.android.com/blog/posts/android-developer-verification-building-a-safer-ecosystem-together?hl=en)

There is no iPhone membership cost in this Android-only plan. Development, device testing, hosting, AI usage, storage, maintenance and any chosen distribution service still have costs. Staff can use the app free of charge.

## 7. Build phases and acceptance gates

| Phase | Deliverable | Gate before proceeding |
| --- | --- | --- |
| 1. Discovery and contracts | Device list, first-release scope, API contract and screen flow. | Confirm supported phones, branch rules and upload/receipt semantics. |
| 2. Design prototype | A realistic phone prototype of capture, queue progress, AI review and receipt confirmation. | A staff member can understand saved versus uploaded versus received without assistance. |
| 3. Reliability prototype | An installable Android build with sign-in, local capture, upload queue and server reconciliation. | Recovery tests pass on real phones before the remaining features are added. |
| 4. Complete first release | AI review, matching, sizes, quantities, pricing, receiving and stock lookup. | End-to-end test delivery reconciles exactly with POS. |
| 5. Pilot and polish | Signed APK, update process, support guide and measured staff feedback. | Installation, update and daily workflow pass on the agreed device range. |

The reliability gate must cover: network loss mid-upload; screen lock; leaving the app; process termination; reboot; manual force-stop followed by reopening; token expiry; low storage; app upgrade; server restart; one failed photo among successful photos; lost upload acknowledgement; receipt completed on another device; and a received item remaining in an old upload queue.

Use a representative batch of 50 images as an initial test target. Acceptance means no lost queued image, no duplicate intake for a retried identifier, and no duplicate stock receipt in the tested scenarios. Measure responsiveness and battery/network use on the actual lowest-spec pilot phone before setting final performance thresholds.

## 8. Practical next steps

1. **Confirm device usage:** hardware assessment is complete for the two phones above. Confirm whether they are shared or individually assigned. Android 14 is the proposed minimum for this pilot.
2. **Confirm the first-release boundaries:** merchandise intake through POS receiving, with stock lookup; retain desktop administration on the website.
3. **Prepare the Android development environment:** inspect Android Studio, SDK, JDK, emulator and device connectivity on the existing Windows workstation. Keep release keys and production secrets outside the repository.
4. **Screen flow and API contract completed:** see the [screen-flow specification](android-screen-flow.md) and [API contract](android-api-contract.md). These identify the current upload, session and caption constraints and define the recovery acceptance matrix.
5. **Build the reliability prototype first:** capture a photo offline, save it, interrupt the upload, reopen the app and reconcile it successfully without duplicates.
6. **Pilot on two or three real phones:** include the oldest supported phone and at least two device manufacturers if available. Staff should test under their ordinary shop connectivity.

**Recommended immediate next phase:** the capture-and-recovery prototype defined in the screen-flow specification, following owner confirmation. Device discovery and contract review are complete; toolchain compatibility, a reproducible build and recovery behaviour still need practical validation. Review the phone flows during this prototype, then extend to AI, matching and receiving after its reliability gate passes. A fixed delivery estimate should follow that validation.

This document does not authorise production database changes, publishing an APK or changing the existing website. Follow the owner's phase-confirmation and release-approval preferences as implementation proceeds.
