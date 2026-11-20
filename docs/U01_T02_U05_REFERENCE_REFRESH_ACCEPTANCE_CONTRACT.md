# U01-T02 / U05-T01 — Reference Refresh Interoperability Acceptance Contract

**Date:** 2026-11-20  
**Status:** PROPOSED CONTRACT / SOURCE-VERIFIED GAP; NOT IMPLEMENTED OR ACCEPTED  
**Scope:** Data Collector Spring Boot backend + ODK Collect 2026 fork. Original M0–M21 task IDs unchanged.  
**Companion:** `ROADMAP_2026_REBASELINE.md`, `U01_T02_ANDROID_SOURCE_PARITY_AUDIT.md`, `U01_T02_ODK_ENTITY_LIST_SEAM.md`, issue #24.

## Source observations and verdict boundary

- Android `collect_app/src/main/java/org/odk/collect/android/formmanagement/ServerFormUseCases.kt` is present on `codex/m4c2b3c3b3b-native-typed-value-proof` with blob `d4c1469b393e736095d031cad40f6dcb62e825b0`. It imports `LocalEntityUseCases`, `EntitySource` and `EntitiesRepository`; the prior source audit traced its typed-media ingestion logic.
- Android `collect_app/src/main/java/org/odk/collect/android/projects/ProjectSettingsDialog.kt` is present at that ref with blob `94ba331d2bca78f97ddd9d45f0dd4e62f41d5bae`; legacy manual admin-unit refresh controls were not preserved at that location. This does not rule out other modern UI.
- Backend `OpenRosaController.appendManifestFile`, as inspected in the earlier U01-T02 evidence, advertises ordinary media without the `type` attribute expected for ODK Entity List ingestion.
- **Verdict:** typed-list manifest mismatch is source-verified; equivalence of independent project/admin-unit periodic refresh remains **UNVERIFIED**, not categorically absent. Both are separate requirements under U05-T01.

## Separate dataset classes — never infer from `.csv`

| Class | Manifest or API | Eligibility | Expected behavior |
|---|---|---|---|
| Ordinary form media | Existing untyped `<mediaFile>` | Published assigned form and active media reference | Download into form media; do NOT update EntitiesRepository |
| Typed Entity List | `<mediaFile type="entityList">` or `type="approvalEntityList"` | Explicit server-side type metadata, validated canonical ODK schema/IDs/version and current assigned project/form access | Native ODK list ingestion, hash-based change detection and appropriate approval semantics |
| Independent reference data | Versioned project-scoped authenticated sync contract, independently of form version | Explicit project/actor access and dataset entitlement | Offline admin hierarchy / other reference cache refresh, manual and scheduled, with revocation/project-switch isolation |
| Historical record answers | Protected AMENDMENT/FOLLOW_UP workflow only | U06/U07 approved field-level mapping and trust/consent | Never exposed through reference-data endpoints or typed-list auto-import |

## Suggested HTTP and manifest behavior (design, NOT existing endpoints)

1. Define an explicit reference dataset resource keyed by stable project ID, dataset ID and immutable revision/hash; do not overload record submission or source-answer endpoints.
2. Serve datasets only through current active App User Basic authentication and current project/assignment/entitlement checks on every request. Invalidate access on revocation and project switch. Never infer project from a supplied filename or URL alone.
3. For eligible typed Entity Lists, emit XML **attributes** `type="entityList"` / `type="approvalEntityList"` on `mediaFile`. Preserve existing `filename`, actual served-byte `md5:...` and authenticated download URL. Untyped CSV stays untyped.
4. A dataset content change must change manifest hash without changing the published XForm version. An unchanged hash must not trigger an unnecessary re-download; failed validation must leave the previous good offline cache intact.
5. Use `integrityUrl` only if a project-scoped authenticated endpoint with native ODK-compatible entity deletion/version semantics is implemented and tested. Missing/invalid entities must not delete unrelated project data.
6. Do not put historic record XML, source answers, protected drafts, mapping grants, attachment tokens or cross-project metadata into a reference dataset.
7. The initial generic UI should show explicit last-sync version/time/error and offer a manual retry. Periodic sync needs a bounded retry/backoff policy, clear offline behavior and account/project isolation. Preserve existing native ODK form media refresh behavior.

## Required automated fixture matrix

| ID | Test fixture | Expected result | Gate |
|---|---|---|---|
| REF-01 | Ordinary media CSV on assigned published form | Untyped manifest, regular media download, no Entity List import | Backend HTTP + Android |
| REF-02 | Valid typed entity dataset assigned to form | Correct typed manifest, exact-byte MD5, authenticated download and native importer update | H2 / PostgreSQL 15 + Android |
| REF-03 | Same dataset revision/hash, refresh again | No redundant list download, offline values unchanged | Android |
| REF-04 | Dataset rows/version changed, XForm unchanged | New hash, fresh downloaded values, no form-version bump necessary | Backend HTTP + Android |
| REF-05 | Invalid CSV: missing IDs, duplicate IDs, invalid version/approval | Reject typed publication/import; preserve last good cache | H2 / PostgreSQL 15 + Android |
| REF-06 | App User unassigned or revoked between manifest and media GET | Fail closed; no bytes or cross-project metadata leak | H2 / PostgreSQL 15 |
| REF-07 | Same dataset ID in projects A and B | No cache, URL, auth or choice-value bleed across projects | Backend + Android |
| REF-08 | Offline after successful reference sync | Cached values available; no forced network access | Android |
| REF-09 | Switch project/account then retry queued update | Old user/project data not surfaced; stale update denied | Android + backend |
| REF-10 | Approval entity list and deletion/integrity case | Correct approval flag; deletion only by valid scoped integrity proof | Backend + Android |
| REF-11 | Admin-unit update independently of form revision | New district/subcounty/parish/village values refresh, form untouched | End-to-end |
| REF-12 | New independent FOLLOW_UP work after refresh | Fresh visit/instance identity; earlier visit immutable; no source-answer transfer | Cross-repo |
| REF-13 | Lost HTTP response mid-download / invalid digest | Retry idempotently, no partial corrupt list committed | Backend + Android |
| REF-14 | JWT web-only user or colliding username attempts mobile Basic | Denied unless active App User credentials/authorization independently validate | Backend H2 / PostgreSQL 15 |

## Implementation slicing

**U01-T02 closeout:** locate modern independent reference-data replacement across the full Android repo; trace legacy PR #15 auth, project-change, periodic trigger and UI behaviors; classify each as PRESERVED, REIMPLEMENTED, MISSING or UNVERIFIED with executable evidence.

**U05-T01-A (backend):** introduce typed dataset metadata and strict eligibility validation on a feature Draft stacked on a qualified backend integration candidate. Do not mark every CSV as an Entity List; keep backward compatibility for ordinary media.

**U05-T01-B (client):** use native ODK importer for eligible typed lists and implement separate generic project-reference refresh if the modern repo lacks equivalent functionality. No legacy AsyncTask transplant.

**U05-T01-C (qualification):** run REF-01..14 on pinned backend/Android SHAs; real PostgreSQL 15, H2, Android unit/emulator, failed/revoked HTTP cases and offline round-trip. Physical devices remain U09 release gate.

## Integration / stop conditions

- Keep this contract separate from U03 AMENDMENT/FOLLOW_UP, U06 semantic mapping and U07 historical-value trust grants.
- Do not merge Android modernization PRs #16–#37 solely because the branch builds.
- Stop and preserve existing offline cache on schema/hash mismatch, auth loss, unexpected deletion, or project/actor collision.
- No production schema migration, APK release, positive historical-value copying or integration merge is authorized by this document.
- **Acceptance cannot be claimed** until actual HTTP and native ODK tests demonstrate the behavior above on a pinned cross-repo commit pair.
