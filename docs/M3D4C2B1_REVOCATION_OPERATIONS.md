# M3D4C2B1 — Project-admin grant/key revocation writer (no issuance)

Date: 2026-11-20
Repo: `jeresaf/data-collector`
Branch: `codex/m3d4c2b1-revocation-writer`
Status: **DRAFT; exact-head CI required**.
Stacked on qualified M3D4C2A inactive trust registry Draft PR #14.

## Scope

Adds **server-owned, authenticated, write-once revocation operations** for grants and signing keys already present in the inactive V5 trust registry. Does NOT create any grant/key registration, approval, signing, source-record access, answer copying, first-save or upload capability.

### Endpoints

Both are web Bearer/JWT only, and require an active verified `users` identity with current active `PROJECT_ADMIN` project membership and active role/project. The server resolves JWT `UserDetails` to SQL identity at request time, disallows username/email collisions with mobile AppUsers and has **no global admin or enumerator bypass**.

- `POST /api/projects/{projectId}/typed-mapping-revocations/grants/{grantId}`
  - Body: `{"reasonCode":"CONSENT_WITHDRAWN"}`. Allowed reasons: `SECURITY`, `CONSENT_WITHDRAWN`, `SCHEMA_DRIFT`, `WORK_CANCELLED`, `SOURCE_CHANGED`, `SIGNER_REVOKED`.
- `POST /api/projects/{projectId}/typed-mapping-revocations/keys/{keyId}`
  - Body: `{"reasonCode":"KEY_COMPROMISE"}`. Allowed reasons: `KEY_COMPROMISE`, `KEY_ROTATION`, `SECURITY`, `OPERATOR_REQUEST`.

Every request:
- Resolves current trusted **web** project-admin identity and fails closed before disclosing whether a target exists.
- Acquires a PostgreSQL/H2 row lock (`SELECT ... FOR UPDATE`) on the exact project-scoped grant or public signing-key registry row.
- Checks the existing V5 revocation record; if identical actor and reason were previously recorded, returns the same original evidence timestamp without another insert. Changed actor/reason cannot overwrite an existing revocation (HTTP 409).
- Otherwise inserts a unique revocation row transactionally with the server-resolved actor, allowlisted reason and database timestamp.
- Returns only project, target type/ID, server actor, reason, timestamp and `REVOKED_PERMANENT_NO_GRANT` with **hard false** `canReadHistoricalAnswers/canTransfer/canFinalize/canSubmit`.

There are no `GET` source-answer, PUT, PATCH, DELETE, un-revoke, grant registration, issue, key import, or positive authorization routes. Existing V5 revocation tables provide the database unique keys, server actor, timestamp and constrained reason. No new migration is needed; production V1–V5 remain unchanged.

**Emergency revocation is intentionally independent of the work being READY or the signing key still being valid.** Administrators must be able to revoke compromised keys and dormant grants even if the underlying work was cancelled, the original reviewer was disabled or a form changed.

## Tests

Synthetic H2 and PostgreSQL 15 real Spring HTTP tests construct inert, test-only registered Ed25519 grants and validate:
- A current project administrator can revoke a registered grant; registry validation immediately returns `BLOCKED_REVOKED_OR_DENIED`.
- Identical replays return original database timestamp and do not create duplicate rows; a second reason is 409.
- A signing-key compromise can be recorded after work cancellation; no grant or upload capability is ever returned.
- Anonymous callers, mobile Basic, a PROJECT_MANAGER reviewer, wrong project, revoked admin membership, unknown target, and unknown reason are rejected.
- Original source XML/answer payload never appears in a revocation receipt.

The existing native JavaRosa, static XForm, Ed25519 signature, server registry, reviewed mapping and replacement tests remain in the focused H2/PostgreSQL workflows. Broad CI checks full backend/frontend.

## Security limitations and next milestones

- V5 tombstones are **write-once through these HTTP APIs**, NOT immutable against PostgreSQL superusers/DBAs or unreviewed manual SQL/migrations. An externally anchored append-only audit/cryptographic signing mechanism and restrictive DB permissions remain a mandatory release gate.
- This authorizer relies on a JWT username principal, with explicit web/mobile collision rejection. **Human identity assurance, privileged reauthentication/step-up MFA and incident-response separation are not yet implemented**; the absence of a global-admin bypass is not proof of trusted key-custody governance.
- No key registration, key storage/HSM, signing ceremony, final positive approval, grant issuance, semantic/value compatibility approval, consent evidence or operational transfer is introduced.
- Neither the static nor JavaRosa compatibility preview is sufficient to migrate historical values. Use-stage verification must be rechecked under suitable isolation when source XML is fetched, at Android protected draft first-save and final submission.
- No private Ed25519 key is ever written. The new handler cannot create signing keys or register grants.
- Production Flyway V3–V5 upgrade rehearsal, default-administrator security P0, actual Android/Keystore tests and end-to-end cancellation/race tests remain release blockers. Physical tests were deferred by the owner, **not waived**.

Do not merge/release this as a production signing/transfer system.
