# Android and web UI alignment

The web catalog is the source of truth for Android's UI and UX. New features and changes must follow its navigation, terminology, visual hierarchy and task sequence. Native adaptations should preserve the same user understanding and outcomes.

## Reference baseline

Compared on 14 September 2026 with the local `C:/Projects/Kline Image Catalog` checkout at commit `97cb2cb9fac7c407ced469aa278e347e09a65bea`. This is a source comparison, not a claim that the deployed website runs that exact revision.

| Web source | Android counterpart |
| --- | --- |
| `app/app/page.tsx` | Workspace branding, Receiving / Pricing / Stock navigation, appearance control |
| `app/app/globals.css` workspace overrides | `CatalogTheme.kt` light/dark colors and restrained corners |
| `app/components/receiving.tsx` | Receiving heading, New delivery action and delivery context |
| `app/components/upload-category-picker.tsx` | Searchable full category paths and explicit photo destination |
| `app/components/photo-intake.tsx` | Open camera / Choose photos actions |
| `app/components/upload-delivery.tsx` | Delivery name and Add to Receiving terminology |
| `app/components/draft-editor.tsx` | ServerDraftEditor details, physical counts and stale-edit protection |
| `app/components/pricing.tsx` | PricingScreen selection, shared/size prices, review, apply and undo |
| `app/components/delivery-checkout.tsx` | ReceiptScreen signed review, destination and retained receipt progress |

## Implemented in this increment

- Receiving replaces the top-level Deliveries label. Photo review remains inside Receiving. Pricing replaces the former Review placeholder; Stock keeps its web name.
- Blue-grey light and dark palettes replace the separate green identity. Headings, spacing, corners and action wording follow the web workspace more closely.
- Camera and system picker retain native capture/import. Saved-photo review uses Add to Receiving, with the test environment clearly labelled.
- System Back from photo review returns to Receiving. Local queue, original retention, upload acknowledgement and account/branch isolation remain intact.

## Explicit remaining differences

The loopback pilot remains a capture/upload fixture. The separate staging build now implements hosted Receiving, category-defined draft details, physical size counts, reviewed Pricing and Stock. Pricing preserves the web's fill/revise intent, overrides, review-before-apply and undo. Receipt review follows the same signed delivery contracts. AI fill, matching, full failure acceptance and production integration remain open; this is not complete web feature parity.

The pilot saves a named local delivery before category selection before photo review. Multi-photo selection (up to 100 per picker session) and consecutive camera capture now follow the web interaction; each photo is prepared sequentially and stays in review until explicitly submitted. The web combines name, category and multiple photos in New delivery. The Android local selector is marked in code as a candidate for replacement during server Receiving integration. Do not delete saved deliveries or queue entries to change this presentation.

Android uses native scalable system text, touch targets and bottom navigation. Desktop sidebar geometry and browser font rendering are not copied literally. Review displays the prepared upload and requires explicit submission because offline storage/recovery is already implemented independently of the browser queue.

## Required checks for future changes

1. Identify the current web screen/component and reference revision before implementing its Android counterpart. Review relevant web changes since the last reference; do not assume the repositories synchronize automatically.
2. Keep destinations, labels, action order, category paths and status meanings consistent. Document any native adaptation and its user-facing reason here.
3. Compare equivalent web/mobile and Android states: sign-in, empty Receiving, populated delivery, category selection, photo review, errors, and available destinations. Inspect light/dark appearance and enlarged text; verify long labels and paths remain readable.
4. Verify saved work, Back navigation, offline recovery and account/branch isolation when affected. Never equate uploaded photos with received stock.
5. Include screenshot evidence and any remaining differences in the change review. Significant departures need the owner's confirmation before implementation. Do not auto-commit.

Visual similarity does not imply feature completeness. Record missing capabilities explicitly rather than inventing a separate Android workflow.

## Verification of this increment

Debug APK, test APK, unit tests and lint passed on 14 September 2026. Lint retains dependency-update warnings. Installed in place on the attached S24+ (SM-S926U), retaining its existing Gallery import check delivery and two photos. Verified Receiving, Pricing, Stock, light/dark appearance, category dialog, saved-photo review and system Back at the owner's existing enlarged display setting. Capture labels fit on one line; dark status/navigation icons have matching contrast. No phone photo was submitted during these UI checks.

The `webWorkspaceAlignment` emulator test passed, checking the three destinations, appearance toggle, review action, Back navigation and original-file retention. Initial attempts exposed an emulator System UI stall and an incorrect test selector for Compose navigation; the corrected test uses visible navigation text.

Phone screenshots are in `verification/android-pilot/s24-alignment-*.png`. `s24-alignment-current.png` and `alignment-current.png` are diagnostic captures from before the final corrections, retained as removal candidates rather than silently deleted. The reference comparison used local web source; a live deployed-web screenshot comparison and complete web feature parity remain outstanding.
