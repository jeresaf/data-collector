# M3D4C2B2A — Untrusted Ed25519 public-key enrollment proposals and independent review

Date: 2026-11-20
Repo: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2a-public-key-enrollment-review`
Status: **Draft — exact-head CI pending**.
Stacked on the qualified revocation writer Draft PR #15, inactive registry PR #14, and earlier unmerged review/JavaRosa/Ed25519 PRs.

## Why this exists

A user-provided public key MUST NOT become trusted because a project administrator uploaded it, because a different account clicked Approve, or because a synthetically signed grant validated. Before any production key-registration ceremony exists, this slice establishes an **untrusted proposal and independent decision record only**. It intentionally exposes **no route that inserts into `typed_mapping_trust_keys`**. No private signing keys or signing secrets are accepted, persisted, or returned.

## Additive schema

Production `backend/src/main/resources/db/migration/V6__typed_mapping_key_enrollment_review.sql` adds:

- `typed_mapping_key_enrollment_requests`: project, proposed key ID, public SPKI bytes, SHA-256 digest, current web requester identity, client idempotency ID, restricted purpose, server-computed canonical request digest and timestamp. Unique project/key and (project, requester, request key) constraints.
- `typed_mapping_key_enrollment_reviews`: one reviewer decision per proposal, server-resolved independent reviewer ID, original request digest, canonical review digest and database timestamp. The only disposition values are `REJECTED` and `REQUIRES_OFFLINE_KEY_CEREMONY`.

No status marks a key as active/trusted. A favorable review means **the proposed key still needs an independently authenticated offline key custody ceremony** and explicit deployment authorization. The existing V5 trust registry remains empty by default. Production V1–V5 migrations are unchanged; only the disposable H2 `V1__initial_schema_test.sql` gets a test schema equivalent of V6.

## Web/JWT endpoints

- `POST /api/projects/{projectId}/typed-key-enrollment-requests` — current active verified project manager/admin web user only. Input: `clientRequestId`, `proposedKeyId`, Base64URL **public** Ed25519 X.509/SPKI bytes, and restricted purpose (`NEW_PROJECT_PILOT`, `ROTATION_REVIEW`, `COMPROMISE_REPLACEMENT`). Strict canonical 44-byte Ed25519 SPKI key encoding is validated via Java 17 `KeyFactory`. Key hashes and request digest are server-computed. At most 10 proposals per requester/project; exact idempotent repeats reuse the original record and changed repeats return 409.
- `GET /api/projects/{projectId}/typed-key-enrollment-requests/{requestId}/review-context` — only a **different** current verified web project admin with active project role and account. Current original requester also must remain active, verified and a project manager/admin; identities must differ by ID, username and email. Returns only identifiers and hashes; does **not** return raw public SPKI bytes or private key material.
- `POST /api/projects/{projectId}/typed-key-enrollment-requests/{requestId}/reviews` — same separate project admin checks plus project-scoped proposal row lock, exact server-side request-hash recomputation and caller-supplied digest match. Decision is `REJECTED` or `REQUIRES_OFFLINE_KEY_CEREMONY` only, never `APPROVE`, `TRUST`, `ISSUE` or `TRANSFER`. First transaction inserts immutable-*through-API* evidence. Identical retry returns original server timestamp. Different signer/decision/hash returns HTTP 409.

Each request uses a web **Bearer JWT** with current SQL identity resolved from Spring Security `UserDetails` (not a mobile OpenRosa Basic principal); no global admin bypass or web/mobile username/email collisions are allowed. A user cannot review their own proposal. Separate accounts are **not** proof of distinct human identity; the review is non-authorizing.

Response flags `trustedKeyRegistered=false`, `canIssueGrant=false`, `canTransfer=false`, `canSubmit=false` are structurally hard false, and no endpoint supplies a private signing key, signed grant, source XML answer, prefill, first-save or upload.

## Security properties and limitations

- Project row locking serializes the request quota/idempotency checks; proposal row locking serializes independent review writes.
- The public-key fingerprint, requester, project, purpose and idempotency key are bound into deterministic SHA-256 evidence using length-prefixed fields. Review evidence also binds proposal ID, both people, proposal SHA and disposition. A privileged DB edit to a proposal's key, purpose or identity is detected when reviewed.
- A proposed key ID already in the V5 trusted or revoked-key registry cannot enter review. A later trust-key addition makes a proposal no longer reviewable.
- The SQL database is **not DBA-immutable**; sha256 hashes are not signatures, anchors, trusted human identity proof, or evidence that key possession and custody have been independently attested.
- Request/review receipts are no-store and do not disclose the raw SPKI input. No operational authorization, signing-key activation or grant issuance occurs.
- There is no trusted-provisioning service, key ceremony/HSM connection, production MFA, human identity re-verification, immutable external audit, malicious DBA protection, or first-use grant enforcement.

## Qualification and next gates

Focused synthetic H2 and PostgreSQL 15 real HTTP tests exercise successful independent proposal and review, active registry still empty, exact replay, changed intent/decision rejection, self-review, stale proposal evidence, deactivated requester, invalid SPKI, forged positive decision, anonymous/mobile Basic denial and cross-project access. Earlier grant revocation, JavaRosa/schema/Ed25519 and OpenRosa preservation regressions are included. Broader repository backend/frontend Quality Gate must also pass on the same exact commit.

The next milestone must define **externally controlled key custody and independent human/supervisor authentication** before public keys can be registered into production trusted-key tables. Grant issuance further requires semantically verified field/rule scope, signed consent, immutable audit anchoring, state/revocation rechecks on source access, Android protected first-save and server submission, and offline replay/crash recovery.

Production Flyway V3–V6 histories require upgrade rehearsal. Default-admin P0 and physical Android/Keystore tests remain release blockers. No PR merge, production data migration, APK release, or deployment is authorized by this draft.
