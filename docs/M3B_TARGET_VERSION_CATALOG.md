# M3B — Authorized target-version catalog

Date: 2026-11-20  
Status: Implementation/automated qualification, **not released**.  
Stacked on `codex/m3-longitudinal-workflows` (Draft PR #4).

## Additional HTTP operation

`GET /v1/projects/{projectId}/records/{sourceSubmissionId}/target-versions?workType=AMENDMENT`

`GET /v1/projects/{projectId}/records/{sourceSubmissionId}/target-versions?workType=FOLLOW_UP`

Example JSON response:

```json
[
  {
    "targetFormVersionId": "<published form-version UUID>",
    "formId": "<assigned form UUID>",
    "formTitle": "Household survey",
    "version": "2",
    "latest": false,
    "sameFormAsSource": true
  }
]
```

No XForm XML, submission XML, household answers, media, credentials or secrets are returned. Entries are capped to 100, ordered by form title and latest/published date; **if more than 100 versions become eligible, implement pagination** rather than silently relying on this endpoint for full version discovery.

## Policy

1. Authenticated active, verified **App User** only. No JWT admin user can read this catalog.
2. Source must be a submission owned by that App User and in the specified active project; the source form must still be actively assigned.
3. Candidate versions require an active, published form in that project, an individually published and nonempty XForm version and a current active app-user form assignment.
4. AMENDMENT only returns versions of the source's **same form** (including earlier published versions); FOLLOW_UP can include versions of different assigned forms within that same project.
5. Revoked assignments disappear immediately from subsequent responses. No special access is granted by knowing a source UUID. A source owned by another enumerator is indistinguishable from nonexistent data (404).
6. The existing M3 `POST /record-work-items` and `GET /prepare` endpoints **recheck authorization**, so a catalog response is never an authorization token.

## Acceptance

- Real Spring Boot HTTP tests cover source ownership, both workflow scopes, alternative assigned questionnaire, missing work type, and revocation.
- Run on disposable H2 **and** PostgreSQL 15/Testcontainers. Report exact-commit pass/fail.
- Physical Android tests remain deferred by owner and remain release-blocking.
- Keep this PR and its prerequisites Draft; no production Flyway changes or APK rollout.

## Mobile integration

The Android M4B-2 picker fetches this catalog after selecting source and workflow type, never guesses target IDs, and binds its selection to that source/project. `POST /record-work-items` idempotency keys must be persisted for retries when the mobile editor is implemented; M4B-2 itself must not submit or mutate source records.
