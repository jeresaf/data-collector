# U01-T02 / U05-T01 — ODK Entity List / Data Collector Manifest Compatibility Seam

**Date:** 2026-11-20. **Status:** Verified **protocol-source mismatch**, not yet repaired or qualified.  
**Backend inspected:** `jeresaf/data-collector` at `codex/m3d4c2b2b2c3-restricted-witness-retrieval` (latest qualified Draft PR #22).  
**Android inspected:** `jeresaf/collect` at `codex/m4c2b3c3b3b-native-typed-value-proof` (latest modernization Draft).  
**Milestones:** **U01-T02** source-parity audit and prospective **U05-T01** reference-data interoperability. No production changes made.

## Actual mechanism in ODK Collect 2026

ODK's built-in **Entity Lists** and **form media CSVs** are different formats/paths. The new ODK client already has a real Entity List importer:

1. `open-rosa/src/main/java/org/odk/collect/openrosa/parse/Kxml2OpenRosaResponseParser.kt` (blob `5f730d341548e1497c5ba67048d8cd570c4d5492`), around lines **173** and **229–235**, reads the **`type` XML ATTRIBUTE of `<mediaFile>`**, not a `<type>` child. It maps `type="entityList"` and `type="approvalEntityList"` to enum variants.
2. `forms/src/main/java/org/odk/collect/forms/MediaFile.kt` declares `Type.ENTITY_LIST` and `Type.APPROVAL_ENTITY_LIST` (otherwise `type = null`).
3. `collect_app/src/main/java/org/odk/collect/android/formmanagement/ServerFormUseCases.kt` (blob `d4c1469b393e736095d031cad40f6dcb62e825b0`), lines **162–180**, checks `mediaFile.type != null`. Only typed media is compared against `EntitiesRepository.getList(name).hash` and queued as `EntityListDownload.Update`.
4. Its `ingestEntityListsFromDownload` (lines **221–258**) invokes `LocalEntityUseCases.updateLocalEntitiesFromServer` and optionally checks `integrityUrl` for deleted offline entities.
5. `entities/src/main/java/org/odk/collect/entities/LocalEntityUseCases.kt` reads Entity List CSV with native JavaRosa `SecondaryInstanceCSVParserBuilder`, merges by entity ID/version, reconciles local offline state, deletes missing online entities and persists the list MD5 hash and approval flag. `entities/src/main/java/org/odk/collect/entities/storage/EntityList.kt` includes `hash`, `needsApproval` and `lastUpdated`.
6. `FormsDataService.kt` exposes `downloadUpdates` / `matchFormsWithServer`, and `ServerFormDownloader.kt` calls the ingestion path. **Thus a built-in, versioned, offline-capable Entity List refresh mechanism exists in current ODK.** However it is tied to correct OpenRosa manifest metadata and server-side entity semantics.

## Data Collector backend counterpart

`backend/src/main/java/org/odk/platform/openrosa/OpenRosaController.java` (blob `c55e2fe486065265984719c8042b31be029f6e79`) exposes authenticated project-scoped form list, form XML, manifest, and attachment URLs.

- At approximately lines **306–355**, `addAvailableFormFilesToManifest` iterates ordinary `FormAttachment` and active `FormExternalDataset` and calls a common `appendManifestFile`.
- `appendManifestFile` outputs **`<mediaFile><filename>...</filename><hash>md5:...</hash><downloadUrl>...</downloadUrl></mediaFile>`**. **It never emits the `type="entityList"` or `type="approvalEntityList"` attribute**; it does not emit an entity integrity URL.
- `FormExternalDatasetService.java` computes uploaded dataset hashes using **MD5**, matching the transport hash format, and makes active CSV/XML datasets available as attachments by their configured `datasetId`. **These are ordinary form-version-scoped external datasets**, not necessarily ODK Entity Lists.
- `EntityListDatasetService.java` can materialize project-scoped `EntityList` objects as CSV for the **JavaRosa web runtime**, but this is not evidence that `OpenRosaController.formManifest` currently advertises them as typed downloadable Entity Lists.
- `ReferenceDataController.java` provides web-role-protected list/item administration and CSV import/export under `/api/projects/{projectId}/reference-lists`. That is not the old mobile Basic-auth `/api/v1/adminunits` refresh route and no project-wide mobile refresh contract has been confirmed from this code.

### Exact behavior this mismatch implies

- Android's manifest parser **accepts** the backend's XML and can treat an attached CSV as **ordinary form media**.
- Because `mediaFile.type == null`, it **will not take the ODK Entity List ingestion path** for the same CSV, so `EntitiesRepository` list hash/version/cache and deletion reconciliation are not updated by this manifest.
- This is a source-level protocol mismatch at the integration seam. We have **not** run a full Android/device/server E2E demonstrating the resulting UI symptoms; the outcome follows the inspected explicit branch condition.
- This does **not** mean that all `select_one_from_file` CSV choices fail: ordinary media CSV is a valid separate mechanism. Nor does it prove that old admin-unit refresh was replaced; the legacy independent UI/periodic schedule remains unverified.

## Safe U05-T01 implementation proposal — not yet applied

**Do not globally add `type="entityList"` to every CSV.** Many are ordinary choice/lookup media and may lack required Entity `name`, `label`, `__id`/version columns or approval semantics; misclassification risks incorrect entity creation/deletion.

Recommended bounded contract:

1. Distinguish **ordinary media**, **form-scoped typed Entity Lists**, and **independently refreshed project reference datasets** explicitly in server metadata. A file extension is **not** proof of typed Entity List eligibility.
2. Add an explicit project/form version authorization and typed list eligibility proof; validate canonical CSV headers, identifiers, unique names and versions with the same semantics consumed by ODK/JavaRosa. Derive cache hash from actual served bytes; require published form assignment and active project/App User access for each download.
3. Advertise only genuinely typed lists as `<mediaFile type="entityList">...` or `<mediaFile type="approvalEntityList">...`; include `integrityUrl` only when implemented as a project-scoped, authenticated endpoint with correct deleted-state semantics. Preserve ordinary media file output without type changes.
4. Guarantee form-manifest hash update when the underlying list changes, **even without a new XForm version**; ensure ODK `FormsDataService.downloadUpdates` and `matchFormsWithServer` pick it up, and that offline cached values are replaced/reconciled only after verified download.
5. If user-required admin-unit updates are **independent of forms**, define a generic, assigned project-scoped versioned reference-data download/sync contract distinct from historical source-answer grants and from FOLLOW_UP/AMENDMENT. Use ODK repository primitives where appropriate but don't force all lookups into Entity Lists.
6. Add exact Android parser and ODK EntitiesRepository tests (typed vs untyped media; hash change/unchanged, approval/no approval, offline deleted entity, malformed CSV, repeat choices, multi-project isolation); add Spring real HTTP H2/PostgreSQL tests for assigned/unassigned users, update URL/hash, no cross-project leakage and original forms.
7. Start with a **Draft U05-T01 protocol qualification PR**, stacked on a known compatible integration candidate rather than mutating the `main`-based roadmap docs branch. No synthetic CSV fixture should be treated as successful real mobile login/refresh until E2E.

**Release gates still apply:** no production Flyway migration, merge, positive historical answer-copy grant, or Android release until integrated U01–U09 acceptance. The old default-admin P0, physical device tests, current trust roots, and production upgrade rehearsal remain outstanding.
