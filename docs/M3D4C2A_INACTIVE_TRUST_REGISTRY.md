# M3D4C2A — Inactive Server-Owned Mapping Trust & Revocation Registry

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2a-inactive-trust-revocation-registry`
Status: **Draft; exact-head CI and production migration rehearsal required**.
Stacked on qualified M3D4C1 Draft PR #13 and earlier Draft PRs #12–#7.

## Scope

This milestone adds a **deny-by-default database verification foundation** for the *inactive* Ed25519 mapping envelope defined in M3D4C1. It DOES NOT enable positive grant issuance, historical-answer migration, first-save, finalization or submission.

### Database schema — additive Flyway V5

`V5__typed_mapping_inactive_trust_registry.sql` creates five tables:

- `typed_mapping_trust_keys`: project-specific Ed25519 **PUBLIC** key SPKI bytes, key ID, validity window and registration evidence. No private keys. **No keys are seeded.**
- `typed_mapping_signed_grant_registry`: strictly scoped grant metadata and canonical-envelope/signature SHA256 digests, source/work/form/reviewer/signer identities, original XML+both XForm SHA256 fingerprints and validity dates. **No grants are seeded.**
- `typed_mapping_registered_grant_fields`: per-grant exact target path and native scalar type.
- `typed_mapping_grant_revocations`: uniquely keyed grant veto events.
- `typed_mapping_key_revocations`: uniquely keyed signing-key veto events.

No DML routes for key registration, issuing, publishing or revoking grants are introduced. Production V1–V4 migrations are not changed. Disposable H2 `V1__initial_schema_test.sql` receives an equivalent V5 test schema; its changes **do not alter production V1**. There is no private signing key, secret, source answer, or device data in the new tables.

**This schema is not DBA-immutable.** Privileged database operators can alter it, and an unanchored SHA256 is not a signature or audit anchor. Do not deploy without independent immutable audit, restricted migration/DB permissions and rehearsed production Flyway V3–V5 histories.

## Internal-only read-only verification

`ServerOwnedMappingGrantRegistry.inspect(signed, now)` is a Spring service with **no caller-facing API/controller**. It rejects missing signed evidence, unregistered keys, missing grant row, invalid key validity, unknown work, non-READY work, changed source/form/target, unexpected reviewer/signer, changed recommendation and grant field inventory, untrusted Ed25519 signatures, expired/future validity, revoked grant/key and an earlier independent final-denial veto.

Every evidence check is **server-owned**:

1. Select the exact project-scoped SPKI **public** key from the database, not the client signature or browser input.
2. Require an existing registered grant with the exact current READY **AMENDMENT** record, source instance, published source/target form, recommendation and separate reviewer/signer IDs.
3. Require the original reviewer and independent final Project Admin to remain active/verified, separately assigned, and non-colliding with mobile AppUser identities.
4. Recompute hashes of the current original submission XML, historical XForm, target XForm and canonical V1 signed bytes; compare with the registry and immutable envelope metadata. Historical XML is **read internally for hashing only**, not returned, logged, imported or persisted into a new draft.
5. Recompute the original recommendation's length-prefixed evidence hash from normalized per-path decisions; require exact `ESCALATE_FOR_RULE_REVIEW` decisions for the registered field inventory.
6. Call the M3D4C1 Ed25519 verifier with the **database selected** trusted key, independent expected server context and a server-owned revocation/final-denial query. Missing/unavailable DB or revocation records cause a failed check, not an allow.

The only successful status is `VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT`. **All** results enforce `canReadHistoricalAnswers=false`, `canTransfer=false`, `canFinalize=false`, `canSubmit=false` including synthetic registered and valid signatures.

### Qualification

Synthetic Spring real database + HTTP fixture tests on disposable H2 and PostgreSQL 15 Testcontainers cover:

- Signed but unregistered key denied; registered key without a grant denied.
- Synthetic in-memory Ed25519 key and exact registered envelope with one advisory field yields **non-authorizing cryptographic evidence**, not a transferable grant.
- Expired envelope, grant revocation, key compromise, revoked reviewer membership, changed original source XML, and independent final denial each fail closed.
- Existing M3D4C1 key/scope/expiry tests, native JavaRosa schema tests, recommendation/signer/denial real HTTP cases, submission replacement preservation tests remain in focused workflow.
- Broader repository CI Quality Gate separately checks backend and frontend. All exact-head checks are required before technical qualification.

Synthetic test **private** keys exist only in test process memory and are not seeded into database, build artifacts or production. No production key management exists.

## Limitations / release blockers

The contract and registry cannot be used to authorize data access. **No operational issuance, role signer ceremony, trusted HSM/secrets, signed independently anchored audit, production-key registration, revocation writing endpoint, trusted semantics/consent certification or migration grant is implemented.** Caller-side or client-provided test keys and always-false revocation are not sufficient.

This read-only check is not yet a serializable/locked **use-stage** authorization. An authorized reviewer/signer or source/record may change immediately after verification. Future issuance and source access/Android encrypted first-save/submit must re-check work state, actor ownership, revocation/consent, schemas, and target JavaRosa value validation in the relevant atomic transaction. Signed proof with a happy-path status is NEVER sufficient.

Physical Android/Keystore, crash/retry/recovery, repeat/media, final native instance and real device tests remain owner-deferred, not waived. Production default-administrator P0 and Flyway-history upgrade compatibility remain independent release blockers.

No source submission, published form, device, production database, APK or PR merges are changed by this milestone.
