# M3D4B2B1 / 3B-4B-2B-1 — Independent final DENIAL only

Date: 2026-11-20
Backend branch: `codex/m3d4b2b1-independent-final-denials`
Status: **Draft; no positive grant issuer, physical device acceptance still blocked**.
Stacks on `codex/m3d4b2a-reviewer-recommendation-ledger` (Draft PR #9).

## Implemented: separation of duties for a safety rejection

A web user with *current* active PROJECT_ADMIN membership may independently reject a recommendation from a *different* eligible web reviewer. The checker resolves fresh JWT web identity through the prior M3D4B1 service, confirms current project admin membership (no global/system admin bypass), current READY same-form amendment, exact persisted source instance + original XForm, published target XForm and source/target hashes. The source record is never returned.

A different UUID alone does not prove different people. Reviewer and final authorizer must also have different usernames and emails; web/mobile identity collisions are rejected upstream. These are conservative checks, **not** strong independent person verification. Original reviewer must still have active/verified account and active project manager/admin membership.

### Web-only endpoints

- `GET /api/projects/{projectId}/record-work-items/{workId}/typed-review-final-denials/{recommendationId}/eligibility` — returns IDs, stored recommendation evidence SHA256, `ELIGIBLE_TO_DENY_ONLY` and **hard false** `canApproveMapping/canTransfer/canSubmit`.
- `POST /api/projects/{projectId}/record-work-items/{workId}/typed-review-final-denials/{recommendationId}` — takes only a bounded `clientRequestId`, `recommendationEvidenceSha256`, and one of `INSUFFICIENT_EVIDENCE`, `SCHEMA_UNSAFE`, `CONSENT_UNCLEAR`, `SOURCE_UNTRUSTED`, `POLICY_NOT_SATISFIED`. Stores a non-grant final rejection.

There is **no** `APPROVE`/`COPY`/`MIGRATE`/`SIGN` action, transfer token, or submission permission. The denial receipt has `DENIED_NO_MAPPING_GRANT`, `canApproveMapping=false`, `canReadHistoricalAnswers=false`, `canTransfer=false`, `canSubmit=false`.

### Transaction and evidence

- Locks the READY `record_work_items` row before fresh eligibility, exact recommendation-content hash matching and any insert, serializing this denial operation with work completion and other work-bound writes.
- New additive **Flyway V4** `V4__typed_mapping_final_denials.sql`. Does not rewrite production V1/V2/V3 or household submissions. H2 disposable `V1__initial_schema_test.sql` has an equivalent table.
- Server-side database CHECK prohibits `original_reviewer_user_id = final_authorizer_user_id`. The recommendation foreign key is UNIQUE: one final denial per recommendation. The evidence digest includes canonical length-prefixed project/work/recommendation, separate reviewer/signer IDs, source recommendation hash, idempotency key and denial reason.
- Exact retries return the same denial ID; changed request, signer or reason returns 409. Non-READY work and revoked active project membership fail closed. No API update/delete endpoint.
- **Not immutable against database administrators.** SHA256 of a record is not a signature. Production Flyway migration-history rehearsal and approval authority audit controls remain mandatory.
- A final denial here closes the specified recommendation *by ledger evidence only*; enforcement against future grant issuance is not yet wired, so a later grant issuer **MUST** check denial records as a hard veto before issuing anything.

### Qualification

Synthetic Spring HTTP suite exercises: separate web signer's genuine JWT, project admin membership, recommendation ownership and hashes, no answer disclosure, idempotent retry, mutated reason and evidence rejection, signer self-review rejection, mobile Basic/anonymous denial, role revocation and no grants. Runs in H2 and PostgreSQL 15 Testcontainers using real Spring HTTP. No device test or production database upgrade is attempted.

### Next required slices

1. A true final **positive** authorization requires an independent human/supervisor identity process, exact-field semantic verification against both XForms, auditable no-self-approval, time-bounded grants signed by trusted server authority, append-only/anchored audit, revocation and database enforcement. **Do not treat this denial as that capability.**
2. Ensure the future grant issuer checks `typed_mapping_final_denials` as an irrevocable veto, checks source and target content hash at issuance, use, offline first-save and server submission; prevent cross-work replay and privilege escalation.
3. Rehearse V3/V4 Flyway upgrades on disposable clones of actual legacy production histories, address default administrator P0, and qualify Android local encryption, physical device and real Postgres E2E workflows.

No migrations executed on production, no legacy source records or instances altered, and no PR merge, signing or distribution.
