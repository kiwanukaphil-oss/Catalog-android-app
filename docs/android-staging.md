# Android staging integration

The `staging` build installs as **K-Line Staging**, package `com.kline.catalog.pilot.staging`, alongside the loopback **K-Line Pilot**. It has independent encrypted sessions, Room storage, private files and WorkManager jobs. Neither build has a production endpoint selector. Staging uses HTTPS and the existing isolated Railway project `kline-catalog-staging`.

Build:

```powershell
cd android
.\gradlew.bat :app:assembleStaging :app:assembleStagingAndroidTest :app:testStagingUnitTest :app:lintStaging -PtestBuildType=staging
```

The app APK is `android/app/build/outputs/apk/staging/app-staging.apk`. Install with `adb -s <device> install -r <apk>`. Use staging credentials; fixture credentials do not work here. No credentials are embedded in the APK or committed. Production stock is outside this build's scope.

The API root is `https://pos-api-production-07c3.up.railway.app/api`. The staging project ID is `9ce0cab6-9ab1-4da3-854b-afcf4cfa914b`, environment `f5099338-7b6a-425b-8a78-fea771ff5809`; Railway calls this environment production, but it belongs to the isolated staging project. The expected test branch is `Staging Main` (`a1c58076-0210-4582-ba91-6da346ea602e`).

## Implemented

- Authenticate, read `/auth/me`, then load catalog capabilities with the default branch context required by the web contract.
- Use the real reference, delivery, upload, link and receipt-readback endpoints with the existing native queue.
- Read Stock with the web's search, stock-state, category, brand and size filters, pagination, signed product photos, variant quantities and exact decimal retail prices.
- Refresh visible Stock every 30 seconds and on foreground entry. Retain the previous same-scope snapshot after transient errors; clear it after account/branch/filter changes or 401/403. The Stock screen is read-only; stock receipt has a separate explicit review.
- Browse hosted deliveries and merchandise in Receiving while preserving the independent local capture queue.
- Edit category-defined details and confirm physical size counts with the web's signed revision contract. Preserve historical attributes, keep size edits separate from detail edits, and protect unsaved changes when navigating.

- Review shared and per-size selling prices/costs with complete workspace selection, signed revisions, retained plan recovery, apply and undo.
- Review final delivery quantities, prices and destination before sending to POS; retain signed reviews and per-product completion for safe retry.
- Select unreceived lots across pages, retaining explicit membership and clearing it when the receiving scope changes.
- Submit durable AI batches, retain uncertain acceptance across reopening, read server progress, stop after the current photo and require explicit consent before retrying unresolved work.
- Compare original photos, save or retire manual product matches, and review delivery-scoped suggestions with exclusions, destination selection and conflict resolution. Matching does not receive stock.
- Inspect private original photos at up to 4x zoom, with larger decoding confined to the open inspection view.

## Verification

`StagingIntegrationTest` is restricted to staging builds and asserts the isolated branch. Credentials are supplied at runtime as instrumentation arguments, never placed in source. Its upload check owns one generated photo and reuses its identity across repeated runs; it does not receive stock. Its Stock check compares the native product and variant UI with the live response and verifies signed photo loading.

The Stock test passed on the S24+ (including private photos); evidence is `verification/android-pilot/s24-staging-stock*.png` and `s24-staging-stock-report.json`. Pilot and staging JVM tests and lint pass; dependency-update warnings remain.

After deployment of the storage repair, the emulator HTTPS integration test passed: original photo identity retained, repeated upload/link produces one lot, original file retained, no stock received, and inaccessible branch rejected. The POS branch middleware returns HTTP 400 with its specific access-denied message. This verifies an invalid branch rejection, not a full role matrix. Evidence: `verification/android-pilot/staging-integration-report.json` and `staging-receiving.png`.

`StagingDraftTest` passed on both the emulator and the S24+: native details save, unsaved-navigation protection, stale revision rejection, rapid consecutive size/quantity edits and authoritative confirmation of two units. This test owns the existing `Android HTTPS integration check` lot and does not receive stock. Reports and screenshots are `staging-draft-*` and `s24-staging-draft-*` in the verification directory.

`StagingPricingTest` passed on the S24+: selected the owned synthetic lot, reviewed before apply, recovered the same plan after reopening, and undid to the original prices without receiving stock. Evidence: `s24-staging-pricing-report.json` and review/undo screenshots. Both build variants pass 21 JVM tests and lint; the fixture HTTP test passes.

`StagingReceiptTest` passed on the S24+: a separate generated `Android receipt check SM-S926U` delivery was reviewed and received through the native UI. Replaying the identical signed review returned already received and Stock remained exactly three units. A repeat run recovered the received delivery and again verified exactly three units. This test intentionally added three synthetic units in staging; it did not touch production. Evidence: `s24-staging-receipt-report.json` and review/Stock screenshots.

`StagingPreparationTest` verifies native AI acceptance using an already-received generated photo: its persisted submission key survives reopening, acknowledgement replay returns the same batch and the server skips inference. Manual matching and unmatching on the S24+ preserve two generated lots, their original photos and quantities without receiving stock. The suggested-group test passed on the same phone: exclude/restore membership, recorded conflict resolution and explicit identity confirmation produced a signed group without receiving stock. It uses the existing Trousers test category, which supports model codes; the shirt test category does not. Reports and screenshots use the `s24-staging-ai-*`, `s24-staging-matching-*` and `s24-staging-suggestions-*` prefixes. Both variants pass 26 JVM tests and lint, and the existing draft edit/stale-revision/navigation check passes again on S24+.

## Backend repair discovered by Android verification

The deployed September 6 POS branch still sent underscored `catalog_item_id` object metadata, causing Railway storage to reject catalog uploads with an unsigned-header error. The existing reviewed host patch changes both intake and photo handoff to `catalog-item-id`. Applied narrowly in an isolated POS worktree; commit `2e138ef` advances only `catalog/workspace-release`. Nine real-PostgreSQL intake/handoff tests passed using a dedicated local test database. Production master and unrelated working changes were not touched.

Both AWS checksum settings are `WHEN_REQUIRED` in staging. The request setting was already present; restoring it alone and redeploying did not fix the metadata error. A direct signed diagnostic object at `android-diagnostics/header-signing-check-20260914.txt` confirmed bucket writes work; retain it as a cleanup candidate. [AWS checksum configuration](https://docs.aws.amazon.com/sdkref/latest/guide/feature-dataintegrity.html).

## Remaining scope

Native AI and matching are implemented in 0.1.4. Live extraction/stop/resume under connection loss, full receipt failure/recovery acceptance and staff usability acceptance remain open. Stock movement history/POS deep links, full permissions and recovery acceptance, A26 acceptance and release signing remain open. A successful Stock read is not evidence of receipt correctness or production readiness.

The staging runtime was upgraded from 0.16.0 to 0.30.0 in POS staging commit `2cb7101`, deployment `0175021f-47af-462e-b450-9fc13ae70863`. All 24 packaged files match Catalog source `97cb2cb`. Canonical migrations 109–113 passed after a fresh readable backup; branch stock balances were unchanged and the temporary database proxy was closed. AI-batch, matching, Stock and delivery reads now return HTTP 200. The local workspace suite passed 26 checks. Of 272 backend tests, 268 passed initially; four outdated policy/package expectations were corrected and all 36 tests in those three suites then passed. Evidence is `verification/android-pilot/staging-{current-contracts,package-verification,workspace-local-integration,workspace-migration}.json`.
