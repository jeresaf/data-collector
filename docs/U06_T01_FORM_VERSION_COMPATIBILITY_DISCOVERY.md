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

## U06-A02 / U06-A08 intake-route regressions — CI pending

Added two JUnit/Mockito regression tests on `OpenRosaSubmissionServiceTest`:

- **Amendment of an old V1 submission against V2:** explicit V2 selects the V2 parser; a new `instanceID` plus a `deprecatedID` referring to the V1 source resolves the existing submission and routes through `SubmissionReplacementService.createReplacement`, with REPLACED persistence/audit and the original submission supplied to `RecordWorkService.completeIfPresent`. The test confirms the source object's original ID/XML are unchanged by the mocked intake path.
- **Second periodic visit with V2:** a new `instanceID` without `deprecatedID` routes through ordinary new-submission persistence, not the replacement service, with RECEIVED audit and `sourceBeingReplaced=null` passed to the server-owned work service. The visit is represented as a separate submission, not an amendment.

**Limits:** These tests establish the intake *routing and service invocation contracts* with mocked storage. They do **not** yet demonstrate durable `submission_versions` history, exact `record_links` state, rollback, version-change answer mapping, attachment/repeat preservation or Android offline editing. Those must be verified with PostgreSQL-backed integration and client tests before accepting the full scenarios.

## PostgreSQL V1 archive regression — CI qualification pending

Added a database-backed test to `PostgreSqlContainerMigrationIT` which runs the **real production Flyway chain in an isolated Testcontainers PostgreSQL instance**. The synthetic fixture creates one project/form, V1 and V2 form-version records, and a durable V1 submission. It invokes the real `SubmissionReplacementService.createReplacement` archive SQL through `JdbcTemplate`, while mocking only the repository save for the new submission. It inspects `submission_versions` for the exact source XML, source instance ID, source form-version ID and sequential version numbers, and checks the original `submissions` row still holds its V1 identity and XML.

**Limitation:** This isolates the history SQL and does not yet exercise the whole HTTP intake transaction, actual replacement-row persistence, real work-item completion or the `record_links` relationship. It must not be counted as satisfying second-visit durable lineage; a further PostgreSQL integration slice is required. The first CI attempt failed test compilation due to a missing `java.util.UUID` import, which has been corrected. Await exact-head CI before qualifying this archive test.

## PostgreSQL second-visit lineage regression — exact-head CI pending

The real PostgreSQL Testcontainers/Flyway suite now includes `followUpWorkPersistsDistinctSubmissionAndDurableRecordLinkOnPostgreSql`. It provisions synthetic V1/V2 form versions, an original V1 submission and separate V2 visit, a scoped READY FOLLOW_UP work item, and invokes the **real `RecordWorkService.completeIfPresent`** using JDBC tables from production Flyway V2. It confirms the READY→COMPLETED state and `completed_submission_id`, the committed `record_links` relationship (`FOLLOW_UP`, visit key, original ID, new child ID, work item), two distinct submission rows, the original V1 XML/version untouched, no replacement-version archive for the original, and denial of replay without a duplicate link.

**Boundary:** This is real PostgreSQL relationship persistence but uses mocked repository lookups and explicitly inserted synthetic submissions rather than the entire authenticated HTTP/JavaRosa intake pipeline. The test invokes the service directly, so it does not prove Spring-proxied transactional rollback on a failed link insert, cross-device offline synchronization or real mapping approval. The complete end-to-end acceptance remains pending.

## First bounded implementation step

Before changing production code, locate existing version/replacement/record-work tests on this safe branch and add **server-side regressions for U06-A04/A05/A06**, with explicit version resolution and no partial persistence on rejection. Then add V1 → V2 amendment and second-visit matrix using synthetic XLSForms and submit/replacement fixtures. Maintain both negative and positive evidence. Android physical tests remain deferred; collect automated emulator evidence separately if available.

## Release controls

- U06 is a discovery branch. **Do not equate this document with completed tests.**
- Do not merge or deploy U05 or U06 without review.
- No production migrations, live submissions or APK releases are performed.
- Keep U05 marked **PARTIAL / RELEASE-BLOCKED** until the independent trust-root, production-governance and Entity publish/remove gates are closed.
