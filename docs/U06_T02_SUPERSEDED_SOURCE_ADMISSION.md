# U06-T02 — Superseded amendment source admission and Android record listing contract

**Base:** U06-T01 Draft PR #29 (`0afb337887876e12d985d280eb89d846ce469b8f`, CI #45143663235 SUCCESS). **This increment is draft/unmerged; no production data or APK deployed.**

## Why
A source `submissions` row remains immutable and readable even when a newer submission has replaced it. U06-T01 already rejects work **created before** the source was amended: the work item's database timestamp is older than the archived revision. However, an enumerator could subsequently create a **new** AMENDMENT work item targeting that already-superseded source. Such a newly created work item would have a later timestamp and could fork history.

## Server contract
1. `POST /v1/projects/{projectId}/record-work-items` with `workType=AMENDMENT`: after the authenticated actor/project/form and source-owner checks, lock the original `submissions` row `FOR UPDATE` inside the same Spring transaction used for creating the work item. Reject with **HTTP 409** if `submission_versions` already contains an archive for that source. This lock serializes with the OpenRosa replacement transaction. Do not create any new work row or shift `deprecatedID` automatically.
2. A **fresh** AMENDMENT against the newest unreplaced submission is still allowed and must preserve the server-issued work ID, target form version, exact source instance, audit semantics and existing V1/V2 archive.
3. **FOLLOW_UP** stays separate. A second visit may explicitly reference an old source; it must not mutate or archive that source, and still needs a distinct new `instanceID`, `visitKey` and owned work item.
4. `GET /v1/projects/{projectId}/records` includes a new JSON Boolean `amendmentSuperseded` per record. `true` means at least one archived amendment exists for the exact source. This is a **point-in-time read-only hint**, never a client-issued grant; concurrent server changes can occur after the listing. Older servers do not return this field, so Android must treat missing/invalid status as **UNKNOWN**, not as proof that amendment is safe.
5. List remains project-scoped, source-owner-only and form-assignment-gated. No historical XML, media, answers, or another user's record is added to its response.

## Regression
The real PostgreSQL/Flyway authenticated V1→V2 repeat + photo integration test asserts: V1 source is `amendmentSuperseded=true`, its new V2 replacement is false; new work against old source returns 409 and writes no work row; a separate FOLLOW_UP against old source is admitted without changing the original archive; a NEW AMENDMENT against V2 is admitted by HTTP (not raw SQL) and can complete into a distinct V3 revision. Historical repeat indexes, source answer values and media files remain immutable.

Android side (separate stacked PR) should parse the exact Boolean, classify absent status as UNKNOWN, visually distinguish superseded/unknown records, and refuse to offer the AMENDMENT creation button unless the listing asserted `false`; the server admission guard is still the authority. Client may still offer FOLLOW_UP on historical records. The integration between repositories is represented by matching HTTP/JSON contract tests until actual emulator+backend tests are authorized.

## Gates
No trusted typed answer-mapping issuer, automatic field migration, offline sender, signed APK, crash/STATUS_UNKNOWN reconciliation, multi-device race stress or physical Android acceptance is implied. Keep all PRs Draft and do not deploy until full signoff.
