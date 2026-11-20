# M3D4C1 — Inactive signed mapping-grant verification contract

Date: 2026-11-20
Backend: `jeresaf/data-collector`
Branch: `codex/m3d4c1-signed-grant-verification-contract`
Status: **Draft; no production grant issuer, source access or upload permission**.
Stacked on qualified M3D4B2B2B JavaRosa schema-proof Draft PR #12.

## Goal

Establish a deterministic, domain-separated **Ed25519 verification format** before any positive historical-field transfer is activated. An independent original reviewer and final signer, exact record/form identities, supported native field rules and short validity window must be cryptographically bound to the same evidence. This contract alone does **not** make that evidence a trusted, usable grant.

No application private Ed25519 key exists in this milestone. The only signer is a **synthetic in-memory unit-test fixture**. No API routes, signing configuration, issuer, key store, production key material, database table, migration, source-answer read, draft-write hook, Android integration, or OpenRosa submission bypass were added.

## Immutable envelope (version 1)

`SignedMappingGrantVerifier.Grant` contains:

- Unique grant, project, READY work, recommendation, source submission, target form-version, original reviewer and independent signer UUIDs.
- Original `uuid:` source instance ID and exact original XML SHA256, original source XForm SHA256, target XForm SHA256, and recommendation evidence SHA256 (lowercase 64-character hex).
- Trusted signing key ID, signed issue/not-before/expiry timestamps rounded to full seconds, with **maximum 24-hour lifetime** and no backdating of not-before earlier than issue.
- Between 1 and 30 **sorted unique** target paths, each with a scalar type `TEXT`, `INTEGER`, `DECIMAL` or `DATE`. Metadata, invalid/relative paths, repeated field definitions and unknown types fail closed.
- Signature is precisely 64 Ed25519 bytes encoded as canonical **unpadded Base64URL**, included in the separate `SignedGrant` wrapper.

The unsigned scope is encoded into deterministic binary bytes with a versioned domain string, fixed-width UUID limbs/timestamps and length-delimited UTF-8 strings and paths; JSON serialization order and object `toString()` are never signed. This avoids ambiguous concatenation/replay across protocol domains.

## Verification

`SignedMappingGrantVerifier.inspect` requires the signed record, a **trusted server-selected Ed25519 public key**, an independently reconstructed current-work expected context, the current clock and a live revocation decision dependency. It checks:

1. The signature under the selected public key.
2. Exact agreement with independently sourced project/work/recommendation/record/instance identity, original reviewer/final signer, source/source-XForm/target-XForm/recommendation SHA256, key ID, exact ordered field paths and native types.
3. `notBefore <= now < expiresAt`; **no** implicit expiry grace or offline validity.
4. Revocation lookup, including key revocation and final-denial veto in the eventual production implementation. A failed/absent revocation provider is rejected, not bypassed.

Every result, including `VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT`, carries *hard false* for `canReadHistoricalAnswers`, `canTransfer`, `canFinalize`, `canSubmit`. This status is **cryptographic evidence only**, not authority. No controller or downstream sender calls the verifier and no source-answer transfer API is enabled.

## Qualification

Synthetic Java 17/JUnit tests create ephemeral Ed25519 key pairs and prove: signature validity under the expected key; bad/wrong key; tampered field type; validly signed wrong work and key-ID context; expiry and future not-before; revoked grant/key and failed/absent revocation provider; unsorted/duplicate/metadata/relative paths; reviewer-signature identity overlap; lifetime >24h; invalid signature encoding; constructor refusal of positive transfer/upload flags. No private signing key is stored in source control, CI artifacts or logs.

Dedicated CI explicitly runs the new test class along with all prior JavaRosa/static/real HTTP/record-work tests under H2 and PostgreSQL 15. Broad Quality Gate separately runs the backend/frontend regression suites.

## Explicit remaining blockers

The following are **not implemented** and are mandatory before any operational grant issuance or transfer:

1. Secure, operationally managed server/HSM signing key configuration, key versions, rotation, trust anchors and logged/externally anchored issuer identity. No hardcoded or shared private key.
2. True independent human/supervisor authentication; reviewer and signer UUID separation alone does not establish two distinct people.
3. Full field meaning/rationale/units/constraints/choice/relevance/native JavaRosa value certification and explicit subject/data-owner consent.
4. Transactional, append-only server grant issuance ledger, trusted signing and auditable revocation/key-compromise/final-denial veto service, event provenance and offline replay handling.
5. **Fresh** ownership/role/READY work/digest/revocation checks at source read, Android encrypted private first-save and final server submission. A library callback supplied by the client or default `false` revocation is NOT an acceptable production provider.
6. Preventing cross-work, cross-actor and cross-project replay, user edits overriding imported data, and orphaned drafts after work cancellation.
7. Real historical source value acceptance against the complete target JavaRosa form, media/repeat handling, crash/restart recovery, physical Android/Keystore and end-to-end PostgreSQL tests.
8. Production Flyway-history V3/V4 upgrade rehearsals and default-administrator security P0 closure.

**Do not merge or deploy this milestone as a usable grant solution. No original records are modified.**
