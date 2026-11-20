# M3D4A / M4C-2B-3C-3B-4A — Deny-Only Server Mapping Authority Boundary

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4a-typed-mapping-authority-deny`
Status: DRAFT — CI pending; no grant issuer, no production migration.

## Purpose

Android 3B-1/2/3A/3B reviews may determine that an old answer *looks* compatible with the new form. Those are **not server-issued mapping authorizations**. A client-controlled review flag, signed or otherwise, cannot permit a source XML import or upload.

This backend increment adds:

`GET /v1/projects/{projectId}/record-work-items/{workId}/typed-mapping-authority`

The endpoint reuses `RecordWorkService.prepare`'s authenticated AppUser / owner / assignment / project / target-form-version / READY-work checks. It returns **fingerprints and denial state only**, not source answers, old XML, target XForms, credentials, or a transferable approval token.

Example response shape for an authorized READY amendment whose original form version exists:

```json
{
  "workId": "<UUID>",
  "sourceSubmissionId": "<UUID>",
  "targetFormVersionId": "<UUID>",
  "sourceXmlSha256": "<lowercase SHA256 hex>",
  "sourceXFormSha256": "<lowercase SHA256 hex>",
  "targetXFormSha256": "<lowercase SHA256 hex>",
  "state": "DENIED_TRUSTED_RULE_ISSUER_NOT_CONFIGURED",
  "approvedPaths": [],
  "canReadHistoricalAnswers": false,
  "canTransfer": false,
  "canSubmit": false
}
```

When the original XForm is missing, `sourceXFormSha256=null` and the state is `DENIED_HISTORICAL_SCHEMA_UNAVAILABLE`. Follow-up visits return `DENIED_INDEPENDENT_VISIT`. Unauthenticated requests must get HTTP 401; unowned work gets HTTP 404; non-READY work is rejected by the existing service.

There is **no POST/PUT/PATCH issuance endpoint**, no trusted rule database migration, no privileged role workflow, and no client-side authority token. Merely retrieving this response never enables copying. `TypedMappingAuthority` rejects any attempt to construct a response with nonempty `approvedPaths` or true grant flags.

## Tests

The synthetic real-HTTP JUnit tests in `OpenRosaRealHttpIntegrationTest`, inherited by `OpenRosaRealHttpPostgresIT`, check:

- Owned READY amendment returns bound SHA256 values and no answer content.
- Browser/Android app username cannot create mapping grants through the endpoint.
- HTTP 401 for unauthenticated discovery and HTTP 404 for nonowned/missing work.
- Independent visits and legacy unknown historical schema fail closed.

CI runs the real HTTP suite against H2 and PostgreSQL 15 plus the established submission replacement regressions. This is **not** a physical device or full Android-to-Postgres end-to-end test.

## Release blockers / next engineering slices

1. **3B-4B**: separately authorized human reviewer/supervisor role, approval semantics, two-person separation, and field-level policy; no app-user self-approval.
2. **3B-4C**: append-only server-issued mapping rule ledger with scope (project, work, source hash/instance, exact source and target XForm SHA256, allowed paths and types), validity bounds, revocation, idempotent review, and audit provenance. Do not insert a Flyway migration before immutable rollout/reconciliation plan is qualified.
3. **3B-4D**: expiry/revocation revalidation at source retrieval, first-save and final submit; exact server rule IDs and target instance association. Reject stale grants and changed schema or ownership.
4. **3B-3C / M4C-2D**: integrated JavaRosa typed draft import, media/repeats, actual ODK native instance and dedicated server upload, including rollback and cross-process race qualification.
5. Production administrator/default credentials and Flyway migration-history compatibility remain independent P0 release gates; physical Android acceptance is owner-deferred, not waived.

**No production data, schema, migration, deployment, signed APK, release or existing record is altered by this slice.** 
