# U06-T01 — Offline amendment conflict contract (server-side qualification)

**Scope:** Backend branch `codex/u06-t01-form-version-compatibility-discovery`, Draft PR #29. **Status: automated qualification pending exact-head CI; Android client integration not implemented here.** No live data, migration, merge, or APK release.

## Record-work operations

- **AMENDMENT:** correction of one exact prior submission. The app must first request a server-owned READY work item for its source submission and chosen published form version. `GET /v1/projects/{projectId}/record-work-items/{workId}/prepare` returns the original submission XML, original form definition, target form definition, read-only field/repeat delta and `mappingApprovalRequired=true`. The app stores the work ID with its offline draft. It must not automatically transfer renamed or retyped answers: typed-mapping authority remains **DENIED** until the trusted approver workflow is implemented.
- **FOLLOW_UP:** a new, independent periodic visit, with a distinct `instanceID`, no `deprecatedID`, and its own server-owned work ID and `visitKey`. This does not replace or archive the original household visit. Do not apply the amendment revision watermark to FOLLOW_UP.
- **Legacy OpenRosa without a work ID:** historic `deprecatedID` routing is still supported for the original enumerator, with the source-owner check and serialized archive allocation. It is **not** certified as offline conflict-protected; do not market it as such or silently omit the work header.

## Safe mobile state machine (intended; not yet implemented in an Android APK)

1. **Online admission:** authenticate as the original, active, assigned App User; discover target versions; create/reuse a READY work ID with a stable `clientRequestId`; GET `prepare` and persist the exact source instance/version and target definition securely. A missing historical XForm is an unresolved mapping blocker, not permission to substitute the latest.
2. **Offline draft:** maintain exact work ID and source instance, a **new** submission `instanceID`, explicit target `version`, repeat indices and media bytes; never mutate the stored original. Separate a true AMENDMENT from a FOLLOW_UP visit. Do not infer mappings based on similar question names.
3. **Reconnect preflight:** reauthenticate; GET `prepare` for the same work ID. **HTTP 409 means STALE/COMPLETED:** preserve the local draft for human reconciliation and do not post it automatically. HTTP 403/404 means assignment/access denial: no cached historical data should be exposed to a different user. If target version/constraint changes, require explicit form review.
4. **Submit:** POST `/v1/projects/{projectId}/submission` multipart with the new instance XML, `deprecatedID` pointing to the **exact source** for AMENDMENT, media parts, and `X-DataCollector-Work-Item-Id`. The backend locks the original source row before archiving, rechecks the server-owned work snapshot, then persists the new revision and durable link transactionally. The client must not switch `deprecatedID` to a different source or modify the work ID to bypass the check.
5. **Conflict or uncertain transport result:** HTTP 409 leaves the local draft available for explicit re-review; no automatic overwrite, silent retries with a new instance ID, or attempt to transfer to a newer XForm. On a dropped acknowledgement, query server state or retry with **the same** instance ID/work ID; a duplicate-instance 409 is not proof of failed persistence. Reconcile with the server before deciding whether to resubmit.
6. **New revision:** after confirmed success, use the newly created submission as the source for a subsequent AMENDMENT. An old READY work item whose source acquired a later archive entry is stale and cannot be reused as an alternative correction path.

## Backend guard and evidence

The server uses `record_work_items.created_at` as an immutable DB timestamp watermark and `submission_versions.created_at` as the committed modification indicator. For a server-linked AMENDMENT, a prior archived revision at or after work creation causes HTTP 409 before writing another archive, submission, normalized answer or media file. A PostgreSQL source-row `FOR UPDATE` lock serializes concurrent replacement operations; the snapshot check executes while that lock is held in the intake transaction. Preparing stale AMENDMENT work also responds 409. The same-source ownership and form-assignment checks remain independently enforced.

Automated acceptance scenarios include late V1 submission after V2 publication, indexed repeats and distinct media by version, source-owner denial, lost/duplicate submit acknowledgement, stale draft preparation and POST denial, preservation of existing historical versions and media, and successful fresh amendment of the latest revision. **Only claim individual scenarios pass when the exact commit's H2 and PostgreSQL jobs succeed.**

## Boundaries / unresolved tasks

- No Android source or emulator test was changed in this backend PR. The separately visible GitLab repository `jeresaf/collect` currently exposes `main` only; the expected customized `ubos-local` line was not established there. **Do not rebase or replace a mobile application without identifying its authoritative fork and migration baseline.**
- The timestamp watermark applies to **server-owned AMENDMENT work**; unlinked legacy replacements and independently scheduled FOLLOW_UP visits do not obtain that guarantee. A stale work item can remain listed as READY; `prepare` and submission enforce the rejection. A later bounded enhancement can expose an explicit `STALE` state.
- Source XML is immutable by design. This guard detects intervening archived amendments, not every conceivable external SQL write or admin-side mutation. Operational migrations must protect the audit/archive tables from out-of-band updates.
- Physical Android offline drafts, reconnect retry UX, partial connectivity, restored media, conflicting editors, multi-instance server concurrency/load testing, cryptographic approval of typed field mappings, and production schema/upgrade rehearsal remain release blockers.
