# U06-T01 — Form-version compatibility discovery and acceptance plan

**Date:** 2026-11-22  
**Branch:** `codex/u06-t01-form-version-compatibility-discovery`  
**Base:** `codex/u01-t03-openrosa-auth-fail-closed` — deliberately not based on U05's experimental custody branch.  
**Status:** DISCOVERY / ACCEPTANCE CRITERIA NOT YET VERIFIED.  
**Related U05 checkpoint:** `docs/U05_ACCEPTANCE_CHECKPOINT_AND_U06_HANDOFF_2026-11-22.md` lives on the separate U05 Draft PR #28 branch.

## Source inspection (observations, not acceptance claims)

1. `backend/src/main/java/org/odk/platform/openrosa/OpenRosaController.java` advertises the latest published `FormVersion` in `/formList` and supplies that latest form XML, attachments and manifest for download. It requires compatibility tests when a client's existing local record was created with an older version.
2. `backend/src/main/java/org/odk/platform/service/OpenRosaSubmissionService.java` calls `resolveFormVersion(form, envelope.version())`: a named version resolves by form+version; an unknown named version is rejected with 400 instead of silently validating against the latest form; absent version falls back to latest. This is a source-observed safeguard, **not** a claim of full cross-version editing compatibility.
3. The intake service recognizes `instanceId` and `deprecatedId` and invokes `SubmissionReplacementService` for replacements. The replacement service writes versioned history to `submission_versions`. The record-linking semantics across version changes still need an end-to-end regression.
4. `OpenRosaSubmissionService` also calls `recordWorkService.completeIfPresent(..., X-DataCollector-Work-Item-Id)`, which distinguishes a new follow-up observation from an amendment when an optional server-owned work item exists. Verify that **second visits are distinct submissions**, not destructive edits of the source.
5. Attachments are stored during submission processing; verify media/repeat preservation and re-download when versions diverge. Do not infer that client rehydration or migration is already correct.

## Acceptance matrix — each item must get explicit automated or device evidence

| ID | Acceptance scenario | Expected invariant | Evidence |
|---|---|---|---|
| U06-A01 | Original V1 instance submitted; form upgrades to V2 | V1 remains readable; source raw XML and history preserved | Pending |
| U06-A02 | Amend V1 record using V2 form (known version) | Stable source linkage; new revision; V2 schema validation; audit trail | Pending |
| U06-A03 | V2 removes/renames/retypes V1 question | No silent remapping or loss of old answers; explicit compatibility decision or fail closed | Pending |
| U06-A04 | Old version explicitly submitted after V2 published | Validate against the submitted version, never latest by assumption | Pending |
| U06-A05 | Unknown explicit form version | Deterministic rejection; no partially persisted submission | Pending |
| U06-A06 | Submission without version | Document latest-version fallback policy and its backward-compatibility limits | Pending |
| U06-A07 | Instance ID replay, deprecatedID replacement, work-item retry | No duplicate source or revision, stable linkage and exactly-one audit semantics | Pending |
| U06-A08 | Second periodic visit | New observation ID with reference to earlier visit, never overwrite original | Pending |
| U06-A09 | Repeats, attachments and media across V1/V2 amendment | No orphaning, dropped attachments or repeat order corruption | Pending |
| U06-A10 | Latest form/manifest download while existing draft is V1 | Client can continue or deliberately migrate draft with safe conflict handling | Pending |
| U06-A11 | Offline edits then server V2 update and sync | No silent client-side migration of answers; conflict/version policy tested | Pending |
| U06-A12 | Authorization failure, inactive assignment, wrong project | No exposure or mutation of old records, work items or version metadata | Pending |

## First regression implementation — exact-head CI pending

Three focused Mockito/JUnit intake regressions were added to `backend/src/test/java/org/odk/platform/service/OpenRosaSubmissionServiceTest.java`:

- **U06-A04:** an explicit older V1 answer selects the exact V1 `FormVersion` when V2 exists; the latest-version repository method and V2 parser path must not be used.
- **U06-A05:** an explicit unknown `retired-v3` version returns HTTP 400 before instance lookup, JavaRosa validation, submission persistence, replacement, attachment storage, record-work completion or audit writes.
- **U06-A06:** a missing version selects the latest V2 definition by the server's documented fallback behavior; it must not parse with V1.

These checks are at **service/unit-test level using mocked persistence**, and cannot demonstrate database rollback after downstream side effects. A PostgreSQL-backed transaction/integration proof and actual V1-to-V2 amended record remain pending. A versionless XML form is inherently ambiguous; it must not be treated as safe automatic V1-to-V2 migration.

**Exact-head CI must be green before these three tests are counted as verified.** The remaining cases A01–A03 and A07–A12 are still pending.

## First bounded implementation step

Before changing production code, locate existing version/replacement/record-work tests on this safe branch and add **server-side regressions for U06-A04/A05/A06**, with explicit version resolution and no partial persistence on rejection. Then add V1 → V2 amendment and second-visit matrix using synthetic XLSForms and submit/replacement fixtures. Maintain both negative and positive evidence. Android physical tests remain deferred; collect automated emulator evidence separately if available.

## Release controls

- U06 is a discovery branch. **Do not equate this document with completed tests.**
- Do not merge or deploy U05 or U06 without review.
- No production migrations, live submissions or APK releases are performed.
- Keep U05 marked **PARTIAL / RELEASE-BLOCKED** until the independent trust-root, production-governance and Entity publish/remove gates are closed.
