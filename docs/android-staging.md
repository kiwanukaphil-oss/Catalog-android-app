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
- Refresh visible Stock every 30 seconds and on foreground entry. Retain the previous same-scope snapshot after transient errors; clear it after account/branch/filter changes or 401/403. No stock mutation is offered.

## Verification

`StagingIntegrationTest` is restricted to staging builds and asserts the isolated branch. Credentials are supplied at runtime as instrumentation arguments, never placed in source. Its upload check owns one generated photo and reuses its identity across repeated runs; it does not receive stock. Its Stock check compares the native product and variant UI with the live response and verifies signed photo loading.

The Stock test passed on the S24+ (including private photos); evidence is `verification/android-pilot/s24-staging-stock*.png` and `s24-staging-stock-report.json`. Pilot and staging JVM tests and lint pass; dependency-update warnings remain.

After deployment of the storage repair, the emulator HTTPS integration test passed: original photo identity retained, repeated upload/link produces one lot, original file retained, no stock received, and inaccessible branch rejected. The POS branch middleware returns HTTP 400 with its specific access-denied message. This verifies an invalid branch rejection, not a full role matrix. Evidence: `verification/android-pilot/staging-integration-report.json` and `staging-receiving.png`.

## Backend repair discovered by Android verification

The deployed September 6 POS branch still sent underscored `catalog_item_id` object metadata, causing Railway storage to reject catalog uploads with an unsigned-header error. The existing reviewed host patch changes both intake and photo handoff to `catalog-item-id`. Applied narrowly in an isolated POS worktree; commit `2e138ef` advances only `catalog/workspace-release`. Nine real-PostgreSQL intake/handoff tests passed using a dedicated local test database. Production master and unrelated working changes were not touched.

Both AWS checksum settings are `WHEN_REQUIRED` in staging. The request setting was already present; restoring it alone and redeploying did not fix the metadata error. A direct signed diagnostic object at `android-diagnostics/header-signing-check-20260914.txt` confirmed bucket writes work; retain it as a cleanup candidate. [AWS checksum configuration](https://docs.aws.amazon.com/sdkref/latest/guide/feature-dataintegrity.html).

## Remaining scope

Pricing, AI review, matching, quantities and stock receipt are not implemented natively. Stock movement history/POS deep links, full permissions and recovery acceptance, A26 acceptance and release signing remain open. A successful Stock read is not evidence of receipt correctness or production readiness.

The deployed workspace runtime is 0.16.0; the current web source expects 0.30.0 and additional migrations/host integration. AI-batch and matching routes return 404 on this older staging runtime. Upgrade and contract verification must precede native support for those operations.
