# M3D4B2A / 3B-4B-2A — Auditable reviewer recommendations (non-authoritative)

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4b2a-reviewer-recommendation-ledger`
Status: **DRAFT** (exact-head CI must pass). Depends on qualified M3D4B1 and M3D4A Draft PRs #8 and #7.

## Goal and constraints

An eligible independent web reviewer can submit a **non-authoritative recommendation**, linked to the exact historical submission, source and target XForm fingerprints, reviewer identity, and current READY work assignment. A recommendation is **not a mapping-rule approval or authorization to read/copy an answer**, and it is never accepted as a token by the Android client or OpenRosa server.

Two endpoints are provided only within the authenticated web API:

- `GET /api/projects/{projectId}/record-work-items/{workId}/typed-review-recommendations/context` — web JWT reviewer only. Returns current work/source identity and exact SHA-256 fingerprints of source XML, historical XForm, and target XForm. Does NOT return any XML, answers, form fields, or attachment content.
- `POST /api/projects/{projectId}/record-work-items/{workId}/typed-review-recommendations` — requires the same fresh web reviewer eligibility and a READY amendment. Takes a bounded `clientRequestId`, exact source+target fingerprints, `reasonCode`, and up to 30 typed-**advisory** `fieldDecisions`.

Allowed dispositions: `REENTER`, `LEAVE_EMPTY`, `ESCALATE_FOR_RULE_REVIEW`. There is deliberately no `COPY`, `APPROVE`, `TRANSFER`, `FINALIZE` or `UPLOAD` disposition.

Reason codes: `REENTER_REQUIRED`, `EXPLICIT_RULE_REVIEW_NEEDED`, `DO_NOT_TRANSFER`. At least one field must be escalated for `EXPLICIT_RULE_REVIEW_NEEDED`, and no other reason code may contain escalation.

Example request (all SHA hashes must be freshly obtained from the reviewer context endpoint):

```json
{
  "clientRequestId": "review-example-001",
  "reasonCode": "EXPLICIT_RULE_REVIEW_NEEDED",
  "fieldDecisions": {
    "/data/age": "ESCALATE_FOR_RULE_REVIEW",
    "/data/household_name": "REENTER"
  },
  "sourceXmlSha256": "<64 lowercase hex>",
  "sourceXFormSha256": "<64 lowercase hex>",
  "targetXFormSha256": "<64 lowercase hex>"
}
```

The 201 response contains a server-generated recommendation ID, work/project/reviewer IDs, an evidence SHA-256 fingerprint, `RECORDED_NON_AUTHORITATIVE`, and `canApproveMapping=false`, `canReadHistoricalAnswers=false`, `canTransfer=false`, `canSubmit=false`.

## Identity, integrity and concurrency

- Eligibility is checked using the existing JWT-only M3D4B1 service: active verified web user, collision denial with mobile AppUser, active project manager/admin membership and READY same-form amendment with known historical XForm. A mobile enumerator cannot submit a recommendation.
- An SQL row lock on the READY `record_work_items` row serializes recommendation writes against work completion. Source/form changes are **not** locked by this slice and must be rechecked again by any later grant authority.
- Reviewer-supplied fingerprints are matched to fresh server-computed hashes before writing.
- First commit stores one immutable *through the API* recommendation header plus normalized per-target-path dispositions. The evidence digest hashes a length-delimited canonical tuple (project/work/reviewer/record/instance/form version/source and target hashes/idempotency key/reason/ordered decisions).
- An exact idempotent retry returns the same recommendation ID. A reused key with different content is HTTP 409; a concurrent duplicate write also fails closed. A transaction ensures header and fields commit together.
- At most **10 recommendation events per reviewer per work item** can be inserted; replays of an existing exact request remain idempotent after the limit. The limit is checked under the same READY work row lock. Each source XML/XForm fingerprint input is bounded to 8 Mi characters.
- File paths are bounded ASCII absolute XForm-like paths, max 30 and depth 12; unknown question *meaning* is not trusted or inferred. The endpoint does **not** certify the path exists in the XForm or is semantically transferable.
- There are no API update/delete actions. A database administrator can still mutate rows, so **SQL append-only enforcement, trusted signatures, and independent audit anchoring are NOT implemented** and must not be claimed.

## Data model / migration

`V3__typed_mapping_review_recommendations.sql` is additive. It introduces `typed_mapping_review_recommendations` (header/idempotency/hashes) and `typed_mapping_review_fields` (per-path recommendation) only. No existing table or historical submission is changed. The H2 test-only `V1__initial_schema_test.sql` has equivalent definitions; the **production V1 and V2 files remain immutable**.

Before any production deployment: rehearse Flyway upgrade histories (including previous environments that may have customized migration checksums), verify unique constraints and locks on PostgreSQL, establish data retention/PII policy and a database-backed immutability mechanism, and resolve previously identified default administrator P0 exposure. Running H2/PostgreSQL CI on disposable clean schemas does not qualify upgrades from legacy production databases.

## Tests

Synthetic real Spring HTTP tests (inherited by PostgreSQL 15 Testcontainers) verify current reviewer JWT, context hash derivation, no historical-answer disclosure, exact idempotency, immutable conflicting retries, stored normalized fields, unknown dispositions/unsafe paths, stale source SHA rejection, Basic/mobile and anonymous denial, immediate membership revocation, and FOLLOW_UP rejection.

## Blocks still required

M3D4B2B: a separate final authorizer with verified human identity, project-specific segregation of duties and a signed/immutable rule decision; a reviewer recommendation alone is not authorization.

M3D4C+: scoped time-bounded server-issued rule ledger, revocation, replay prevention and authorization rechecks on every sensitive source read, Android first private save and submission intake. Audit and server enforcement must be independent from client-provided flags.

M4C-2D: complete native JavaRosa instance, media/repeats, dedicated upload and cross-process atomic recovery.

No APK is signed or distributed, no production records are accessed, no PR is merged, and device acceptance is still deferred (not waived).
