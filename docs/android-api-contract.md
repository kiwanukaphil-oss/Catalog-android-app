# Android API contract and implementation gaps

**Reviewed:** 10 September 2026.  
**Basis:** Current repository code and the prepared/deployed POS source snapshot, not speculative endpoint designs. No live writes were performed for this review.

## 1. Transport and authentication

Production API root: `https://inventorypos-production.up.railway.app/api`. The prototype must use an isolated test backend, supplied through build configuration. APKs must contain no database, storage or AI-provider credentials.

| Operation | Existing request | Response / rule |
| --- | --- | --- |
| Sign in | `POST /auth/login` with `{username,password}` | `{success,message,token,user}`; JWT bearer authentication. |
| User information | `GET /auth/me` | Existing POS user response; use catalog session for workspace capabilities. |
| Catalog session | `GET /catalog/session` | `{data: ...}` with account, branch access and capability flags. |
| Categories and fields | `GET /catalog/reference-data` | `{data: ...}`; retain hierarchy and category field definitions. |

Authenticated requests send `Authorization: Bearer <token>`; branch-scoped requests send `X-Branch-Id: <uuid>`. Do not embed browser sessionStorage assumptions in Android. Store session material with Android platform protection and scope local work by account and branch.

The reviewed auth router exposes login, registration and current-user endpoints; it has no refresh-token or server logout endpoint. Token expiry defaults to seven days in source but is configurable: use the actual token expiry, not that default. Pilot behaviour is explicit reauthentication on expiry. Do not claim that local logout invalidates a bearer token already copied elsewhere. A native refresh/revocation design is a later backend enhancement, not a prerequisite for the isolated reliability prototype.

Backend permissions remain authoritative. Workspace routes generally require `catalog.view` and an active branch, then add `catalog.upload`, `catalog.edit`, `catalog.publish`, `products.view` or `inventory.view` as appropriate. A hidden button is not an authorisation boundary.

## 2. Response conventions and client models

The existing API has mixed envelopes. `/catalog` controllers commonly return `{success,data,message}`; workspace routes generally return their payload directly. Implement separate typed adapters, not a universal `.data` unwrap.

Minimum native models: Session, Branch, Category/Field, LocalDelivery, PendingPhoto, CatalogItemSummary, ItemDetail, VariantLine, AiBatch, MatchSuggestion, MatchPlan, ReceiveReview, Receipt and StockPage. Parse identifiers as strings/UUIDs and treat revisions as opaque strings.

`GET /catalog-workspace/items/:id` returns top-level `revision`, `publication_revision`, `fields`, `blockers`, and `item`. The item includes `is_published`, cancellation/reconciliation flags, photo handoff, image URL, attributes, AI evidence, price, quantity source and variant lines. Do not assume its fields exactly match the upload response or list projection.

Use decimal-safe money handling. Preserve unknown fields when forwarding an explicitly reviewed draft model, but never send arbitrary local metadata as merchandise attributes. Refresh expired private image URLs through the API rather than storing them as permanent asset identifiers.

## 3. Delivery and upload contract

| Operation | Existing endpoint and body | Retry semantics |
| --- | --- | --- |
| Create delivery | `POST /catalog-workspace/batches`, `{id,title}` | Persist client UUID first; retry with the same identity. Title is required and bounded to 120 characters by the router. |
| Upload one photo | `POST /catalog/items`, multipart `id`, `category_id`, `status=draft`, `image` | 201 for created item; 200 for a matching existing item. Response includes `data` and a message. |
| Link delivery | `PUT /catalog-workspace/batches/:batchId/items/:itemId` | `{added:true}` on success; conflicts must be reconciled. Received items cannot change delivery. |
| List delivery history | `GET /catalog-workspace/history/deliveries?page=1&search=...` | Paginated, read-only; prefer this over the legacy unpaginated batches route. |
| List lots | `GET /catalog-workspace/items?batch_id=...&page=1&task=all` | `{items,total,...}`; current page limit 48. |
| Read one lot | `GET /catalog-workspace/items/:id` | Current revision and receipt state for recovery. |

Upload limits are **5 × 1024 × 1024 bytes**, one image per request, and JPEG/PNG/WebP. Oversize returns 413; malformed/unsupported input returns 400. Camera output such as HEIC must be converted before upload. No byte-level multipart resume or multi-evidence-photo upload endpoint was found.

`createCatalogItemFromImage` checks cancellation and category, derives a stable object path, and returns an existing item only when branch, category and image path match. Consequently, persist the upload format/extension as well as the ID: switching a retry from JPEG to WebP can change the derived image path and conflict. This contract prevents duplicate records for a retried UUID; it does not deduplicate separately created UUIDs or compare the newly supplied bytes with an existing item's content.

### Required recovery algorithm

1. Persist the local delivery, photo UUID and prepared bytes before any network write.
2. Create/reconcile the delivery using its original UUID.
3. Upload with the original photo UUID, branch, category and deterministic file format.
4. If the successful upload response has `data.pos_product_id`, read the current workspace item. If `requires_pos_reconciliation` is set, retain an attention state for review; otherwise confirm receipt and finish only the stale local upload entry. Never reassign the received delivery.
5. For an unreceived item, attempt the delivery link.
6. On a link conflict, reread item state. Confirmed received state can complete the stale queue entry, subject to the reconciliation flag above. Other conflicts retain the saved photo and require attention.
7. On interrupted responses, reconcile before repeating business mutations. The same-ID upload endpoint remains the safe fallback for uncertain intake creation.
8. Mark transport complete only after confirmed delivery association or resolved receipt state. Do not delete the original image as an incidental consequence of retry.

The website recently gained equivalent core recovery for already-received queue entries. Android should also expose the existing legacy reconciliation flag rather than presenting those records as fully resolved.

## 4. Review, AI and matching

| Operation | Existing contract |
| --- | --- |
| Save draft | `PATCH /catalog-workspace/items/:id`: `expected_revision`, `name`, `brand`, `category_id`, `attributes`; consult category fields and current web editor for optional review flags. |
| Confirm counts | `PATCH /catalog-workspace/items/:id/count`: `{expected_revision,entries:[{variant_attributes:{size:"42"},quantity:1}]}`. |
| Start AI batch | `POST /catalog-workspace/ai-batches`: `{submission_key,item_ids}`. Persist the submission key and selection before sending. Router accepts 1–1000 distinct IDs; additional service limits apply. |
| List/read AI | `GET /catalog-workspace/ai-batches` and `GET /catalog-workspace/ai-batches/:id`. |
| Stop/resume AI | `POST /catalog-workspace/ai-batches/:id/stop` or `/resume`; use `confirm_retry:true` only after explicit consent to uncertain paid retries. |
| Discover matches | `GET /catalog-workspace/match-suggestions?batch_id=...`; response includes suggestions, coverage and discovery timing. |
| Review/confirm suggestion | `POST /catalog-workspace/match-suggestions/review` or `/confirm`; send selected `item_ids` and the server's `expected_revision` where required. Confirmation also includes product/brand names and `confirm_identity:true`, with explicit difference-resolution fields when needed. |
| Save manual match | `POST /catalog-workspace/product-matches`; item IDs, product/brand names, evidence note, optional target product, expected revision and shared colour/fit defaults. |
| Read match plans | `GET /catalog-workspace/product-matches`. |

Draft/count saves return a refreshed success result, not necessarily a full updated item. Reread to obtain the next revision. Never reuse an edit revision as a publication revision.

AI batch states are `active`, `paused`, `stopped`, `done`; entry states are `queued`, `running`, `done`, `attention`, `skipped`. Recover an uncertain submission by repeating the submit request with the same submission key, account, branch and identically ordered item IDs. The service returns the existing batch for that identity; a different selection conflicts. Persist the returned batch ID and read its status. The list/read responses do not expose the submission key, so list polling alone cannot reconcile that identity. Do not silently start a second paid batch. Poll only at a bounded interval while useful and refresh on foreground entry. Native push-token registration is not present in the reviewed workspace routes and is deferred.

The full matching and pricing DTOs must be extracted into fixtures before implementing those later screens. Their routes are identified here; this document does not pretend that a shortened table replaces their complete service validation rules.

## 5. Pricing, receiving and stock

| Operation | Existing contract |
| --- | --- |
| Read item pricing | `GET /catalog/items/:id/pricing`; protected cost visibility remains server-controlled. |
| Price workspace/preview | `POST /catalog/pricing/workspace` and `/pricing/preview`; use the existing persisted pricing-plan flow. |
| Read/apply price plan | `GET /catalog/pricing/plans/:id`, `POST /catalog/pricing/plans/:id/apply`; port exact payloads from the current pricing client before this increment. |
| Review single receipt | Read `/catalog-workspace/items/:id`; use `blockers` and `publication_revision`. |
| Receive single lot | `POST /catalog-workspace/items/:id/receive` with `{expected_revision:<publication_revision>}`. |
| Review/receive matched group | `POST /catalog-workspace/product-matches/:id/review` then `/receive`; submit the matching review's expected revision. |
| Retry POS photo copy | `POST /catalog-workspace/items/:id/photo`; this is separate from receiving stock. |
| Receipt history | `GET /catalog-workspace/history/receipts?page=1&batch_id=...`. |
| Current stock | `GET /catalog-workspace/stock?page=1&search=...&state=all`; optional size/category/brand filters. |
| Stock movements | `GET /catalog-workspace/stock/:id/movements`; confirm identifier semantics in service fixtures before use. |

Receipt commits before photo handoff. A failed photo copy cannot be treated as a failed stock receipt. After timeout or connection loss, read current receipt/item/group state; only unreceived work remains eligible. For grouped receipt, reconcile all members rather than assuming that one item's outcome represents the entire request.

For the first Android receiving increment, use explicit review and online confirmation. Offline stock mutations, automatic matching and silent replay of uncertain receipt requests are excluded.

## 6. Error handling policy

| Result | Native behaviour |
| --- | --- |
| 400 / 413 / validation failure | Keep local evidence; show the exact corrective action; no automatic retry loop. |
| 401 | Pause network work and request sign-in; retain the original account's queue securely. |
| 403 | Refresh capabilities and show access restriction; do not move work to another branch. |
| 404 | Determine whether the item/delivery/category is unavailable or inaccessible; preserve local evidence until resolved. |
| 409 | Refresh the relevant state; distinguish stale revision, existing membership, cancellation and received-item cases. |
| 429 | Honour `Retry-After` when supplied; otherwise use bounded backoff and communicate the pause. |
| Network / 5xx | Persist uncertainty, reconcile accepted operations, then retry only safe work with backoff. |

Parse structured errors when possible and tolerate a non-JSON gateway response. Do not drive business logic solely by matching English error text. Current workspace errors return `{message,details}`; stable machine-readable error codes are a proposed additive backend improvement.

## 7. Gaps and decisions before each increment

| Gap | Decision for prototype | Later enhancement |
| --- | --- | --- |
| No session refresh/revocation flow | Reauthenticate; pause queue on expiry. | Define rotating refresh tokens and revocation if operationally necessary. |
| One image per intake, 5 MiB limit | Preserve original locally; upload prepared supported-format image. | Multi-evidence images and/or larger upload contract after measurement. |
| Separate intake and delivery link | Explicit persistent state machine with readback. | Atomic intake/delivery acceptance endpoint if justified. |
| Caption text is not a documented intake field | Preserve overlaid caption pixels; store newly typed notes locally with an explicit “local only” label. | Add an audited caption/evidence field and AI consumption contract before advertising server-side typed-caption guidance. |
| No native push registration | Foreground status refresh and local transfer notifications. | Optional push completion notification, with readback remaining authoritative. |
| Mixed envelopes and error formats | Separate adapters and fixtures. | Additive versioned/mobile contract and machine-readable errors. |
| Existing browser queue belongs to the web app | Finish it through the website; keep Android app data separate. | Explicit migration/export only if needed. |

## 8. Contract tests and source references

Before the prototype connects to production, run isolated tests for login expiry, branch isolation, duplicate-ID upload, stable format on retry, upload size/type limits, lost acknowledgement, failed delivery link, concurrent receipt, legacy reconciliation, cancellation, private-image URL expiry and denied permissions. Do not run mutation-capable production test scripts as generic smoke tests.

Relevant repository sources:

- [Workspace routes](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/server/workspace-router.cjs) and [services](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/server/workspace-service.cjs).
- [Website API types and transport](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/app/lib/catalog-api.ts).
- [Current upload recovery](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/app/components/upload-delivery.tsx).
- [Background AI contracts](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/app/lib/ai-batches.ts) and [server implementation](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/server/ai-batches.cjs).
- [Pricing client](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/app/components/pricing.tsx), [draft/count editor](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/app/components/draft-editor.tsx), [matching service](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/server/product-matching.cjs).
- [Upload recovery regression](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/app/tests/upload-resume-received.mjs) and [storage metadata integration](https://github.com/kiwanukaphil-oss/Kline-image-catalog/blob/ead1678539b8710da4d0a21ee615ac5e6343e1ce/server/host-integration/apply-storage-metadata.cjs).

The POS host files inspected for authentication, multipart limits and intake idempotency are in the retained `.test-data/storage-signing-pos/backend/src` release snapshot. They are not part of this repository's public runtime package. Produce sanitised fixtures from those contracts during prototype development; do not make the Android build depend on an ignored local snapshot.
