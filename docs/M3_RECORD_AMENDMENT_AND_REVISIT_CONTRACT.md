# M3 — Updated-Form Amendments and Longitudinal Repeat Visits

**Date:** 2026-11-19  
**Status:** implementation qualification; not physical-device certified.  
**Backend branch:** `codex/m3-longitudinal-workflows`, stacked on green M2 `codex/m2-odk2026-integration-gate`.  
**Physical tests:** DEFERRED by owner; still required for release.

## First-class workflows

**AMENDMENT — correcting an existing record**: The operator downloads an existing authorized submission and chooses a **published form version belonging to the same form**. The new XForm may contain added/removed questions. The original data is only source material for a proposed transformation: unmapped values, repeats, constraints, selections and media demand explicit review. The amended submission gets a **new** `instanceID`, and uses `deprecatedID` equal to its source submission instanceID. The server stores a new submission, audit history, source XForm version snapshot and an `AMENDMENT` record link. The source is **not overwritten**.

**FOLLOW_UP — a second/third/etc visit**: The operator downloads a previously collected authorized record (e.g. Visit 1), picks a **published target form version** (which may belong to a different but assigned questionnaire in the same project), and assigns a **unique visitKey**, e.g. `2027-Q1-visit-2`. A new, independent observation is created with a **new** `instanceID` and **without** `deprecatedID`. A `FOLLOW_UP` link ties it to the source, preserving the older visit and attachments untouched. `plannedFor` can schedule a future observation; the app can list `READY` work items when connectivity is available.

Both workflows use **explicit, server-owned work items** to prevent mixing observations with corrections. An `idempotencyKey` (field `clientRequestId`) prevents duplicate work creation; a unique source/visitKey constraint prevents scheduling the same visit twice. A work item can only be consumed once, and intake will atomically roll back any new submission if its work item is invalid, already completed or does not match the source/target form version.

## HTTP interface (current backend implementation)

| Operation | HTTP | Example relative to project URL |
|---|---|---|
| List owned, form-assigned records | GET | `/records` |
| List assigned pending work | GET | `/record-work-items` |
| Create correction or visit | POST | `/record-work-items` |
| Download original XML + target XForm for mapping | GET | `/record-work-items/{workId}/prepare` |
| Submit mapped/answered new instance | POST | `/submission` with `X-DataCollector-Work-Item-Id: <workId>` |

Request example (amendment):
```json
{
  "sourceSubmissionId": "<UUID of original submission>",
  "targetFormVersionId": "<UUID of newly published form version>",
  "workType": "AMENDMENT",
  "clientRequestId": "deviceA-operation-001"
}
```

Request example (periodic second visit):
```json
{
  "sourceSubmissionId": "<UUID of Visit 1 submission>",
  "targetFormVersionId": "<UUID of follow-up questionnaire version>",
  "workType": "FOLLOW_UP",
  "clientRequestId": "deviceA-visit2-001",
  "visitKey": "2027-Q1-visit-2",
  "plannedFor": "2027-01-10T09:00:00Z"
}
```

**No credentials** belong in the URL, mobile configuration, logs or work-item JSON. All endpoints require an authenticated, active and verified App User, an active project and assigned form(s). The initial safe policy permits only records **originally submitted by the same App User**. Cross-enumerator delegation and household-wide multi-user assignment are a separate future authorization feature, not assumed from project membership.

### Form upgrades and mapping policy

The `prepare` response contains both the exact **source submission XML** and selected **target form-version XForm XML** plus an explicit `mappingApprovalRequired=true`. This is enough for a client or browser editor to inspect an old record while rendering an updated form, but it is **NOT** automatic correct field migration.

The next Android/client slice must implement a mapping manifest with explicit:
- Stable source/target field paths, renamed-question mapping and choice value translations.
- Repeat-group cardinality/parentage and entity-list or administrative-unit migration.
- Preserved source values that no longer have a target question, in immutable audit provenance.
- Re-evaluation of `relevant`, `required`, `constraint`, `calculate` and attachment references **under the target XForm and JavaRosa 7**.
- A user review/accept step before finalizing; no guessed values for new required fields.
- Explicit version 1→2 test vectors from the PDMIS household questionnaire and nested-repeats farm fixture.

**Avoid a dangerous shortcut:** do NOT feed raw source XML to a later XForm parser and assume field equivalence. The backend now rejects explicitly unknown XForm versions rather than silently falling back to the latest.

### Data provenance and reporting

`V2__longitudinal_record_work.sql` adds `record_work_items` (scheduled/prepared activity) and `record_links` (result lineage), plus `source_form_version_id` on the existing `submission_versions` snapshots. It leaves all existing submissions untouched.

- **Amendment:** report revisions in one observation history, preserving original source details and old form version.
- **Follow-up:** count as a NEW visit, not a corrected Visit 1; report visit date/sequence separately and track changes over time where field mapping is explicitly defined.
- The source submission is the initial lineage anchor. A separate stable household/subject entity and delegation model will be required for assigning visits across enumerators, facilities, seasons or projects.
- The current `clientRequestId` and `visitKey` use simple token syntax to prevent malformed identifiers. They must be scoped and uniqueness-tested under concurrent requests.

## Explicit scope limits and release blockers

- **Implemented in this branch:** PostgreSQL/H2 schema; authorized listing; work creation/preparation; distinct amendment and follow-up submission semantics; transactional linkage; snapshot of original form-version ID; synthetic HTTP regression tests.
- **NOT implemented:** ODK 2026 mobile entry-list UI, actual draft prefill and mapping approval UI, offline work package caching, cross-enumerator assignment, visit notification/scheduler, full household entity index, real-device acceptance, conflict UI and all attachment migration flows.
- **DO NOT RELEASE:** backend default administrator bootstrap must be remediated, real Flyway upgrades reviewed, existing-device data preservation certified and physical Android acceptance completed.
- **Migration note:** Production V1 is a squashed, clean-install-only baseline; V2 is additive but **must not** be applied to any legacy database without first identifying its actual Flyway history and taking restorable backups.

## Automated gates

`m3-record-work-qualification.yml` runs the same HTTP path on H2 and PostgreSQL 15; `ci.yml` runs full H2/Playwright/PostgreSQL suites. Record the exact commit, pass/fail and sanitized evidence. The M3 branch and any PR remain **Draft** until all tests pass, including failed-intake rollback, access revocation and form-version preservation.
