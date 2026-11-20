# M3D4C2B2B2A — Inactive External Ed25519 Ceremony Attestation Verification Contract

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2b2-inactive-external-attestation-verifier`
Status: **Draft; exact-head CI required; no production external trust anchor configured.**
Depends on fully qualified V7 offline-ceremony commitments Draft PR #17, then prior unmerged #16–#7.

## Goal and boundary

Build a versioned **cryptographic verification contract** for an externally produced Ed25519 signature over the exact *current* V7 offline ceremony evidence packet. **This is only verification evidence**. No operator, client, or web service can activate a proposed signing key, issue a mapping grant, read historical answers, finalize drafts, or upload using this component.

There is **NO production external verifier key, signing private key, issuer route, signer configuration, trust-key onboarding, database record, migration, activation route or HTML/API controller** added by this milestone. Unit tests generate ephemeral Ed25519 key pairs in memory, strictly for synthetic qualification.

## Signed evidence envelope

`ExternalCeremonyAttestationVerifier.Attestation` contains:

- Exact project, proposal, proposer, independent reviewer, third witness and separate **external attestor** UUIDs. All six must be non-nil; all four human-role IDs must be distinct. Distinct UUIDs **do not certify** distinct humans.
- Proposed key ID and SHA256 of the V6 PUBLIC Ed25519 SPKI, original request digest, independent V6 review digest, external V7 bundle digest/reference, and exact V7 ceremony commitment digest.
- External verifier **key ID** and external checkpoint SHA256. These fields are signed, but the key itself is **never** read from client claims and the checkpoint digest's external publication has **NOT** been confirmed in this slice.
- Exact V7 `recordedAt` seconds **and fractional nanoseconds**; externally asserted attestation and expiration times (whole seconds), with a maximum seven-day validity period, no expiry grace and no issuance before the V7 timestamp.
- One canonical 64-byte Ed25519 signature encoded in unpadded Base64URL; malformed or noncanonical signatures are rejected.

The unsigned data is encoded in strict domain-separated, length-prefixed binary bytes `DATA_COLLECTOR_OFFLINE_CEREMONY_EXTERNAL_ATTESTATION_V1`. UUID limbs, V7 fractional time, all data hashes, reference, independent participant IDs, and external-key/checkpoint fields are signed; no JSON field order or unsafe string concatenation is relied upon. A signature cannot be replayed into the mapping-grant verification protocol or transferred to a different proposal, key or reviewer/witness evidence.

## Verification semantics

`ExternalCeremonyAttestationVerifier.inspect(...)` requires all five dependencies:

1. The signed attestation envelope (signed externally; not created by the server).
2. A **separately trusted Ed25519 public key** selected by the server from an independently protected trust store. An untrusted request/body-provided key cannot be authoritative.
3. An `ExpectedCeremony` created from a **fresh, authenticated server-returned** `TypedMappingOfflineCeremonyService.CeremonyPacket` (which already verified the V6 proposal, independent review, V7 witness commitment and live role separation), plus the expected external verifier key ID from independent policy.
4. Server current clock, checking `attestedAt <= now < expiresAt`.
5. Server-owned live revocation lookup for the external verifier key **and** specific ceremony proposal. Absent or failing lookup blocks. Test lambdas are **not** production revocation enforcement.

The verifier checks the Ed25519 signature **before** comparing the independently established exact V7 packet and expected external verifier key ID, then time bounds and revocation. It returns only an evidence state and the proposal ID. Any mismatch, missing public key, failed verification, stale evidence, invalid clock or revoked/unavailable lookup returns `BLOCKED_...`.

**A successful Ed25519 verification returns only:**

`SIGNATURE_VALID_ONLY_EXTERNAL_CHECKPOINT_UNVERIFIED_NO_TRUST`

All rights remain structurally and permanently false:
`independentlyAnchored=false`, `trustedKeyRegistered=false`, `canIssueGrant=false`, `canReadHistoricalAnswers=false`, `canTransfer=false`, `canSubmit=false`.

**A valid signature is NOT independent audit anchoring.** The external checkpoint must additionally be independently fetched or verified against an outside-the-DB published log with a trusted timestamp, inclusion proof, and revocation status. The library does not perform that step. It also does not prove physical attendance, HSM custody, certificate-chain validity, subject consent or identity personhood.

## Tests and CI

`ExternalCeremonyAttestationVerifierTest` proves against synthetic keys and a synthetic V7 packet:
- Exact signature and exact server packet produce cryptographic evidence **without** operational rights; canonical binary bytes are deterministic even with V7 microsecond precision.
- An unrelated public key, missing external key or missing server context fails closed.
- Tampered external bundle/checkpoint SHA causes signature rejection.
- A real signature over a different bundle or external verifier key ID cannot match the current V7 packet.
- Future or expired evidence, lifetime over seven days and invalid timestamps fail.
- Revoked external verifier/proposal or unavailable revocation backend fails closed.
- Self/overlapping attestor identities, invalid hash, noncanonical signature encoding, and forged positive permissions cannot construct a valid state.

Dedicated `.github/workflows/m3d4c2b2b2a-external-ceremony-attestation.yml` runs this synthetic unit suite plus all earlier JavaRosa/static/Ed25519/real Spring HTTP/OpenRosa replacement regressions with both H2 and PostgreSQL 15 Testcontainers. Broad CI Quality Gate must separately pass at the exact head. No production data, private signing keys, physical Android device, trusted hardware or external audit service is used.

## Further milestones REQUIRED before any actual activation

1. Provision an **independent externally managed attestation-verifier trust store**, certificate/key ID policy, key rotation, key and proposal revocation, secure custody, and access controls. No code path may accept an ad hoc public key from a caller as trust.
2. Verify actual signed offline attendance/custody bundle, distinct **human** attestor identities and possession of an appropriately protected signing private key. Require out-of-band identity checks and step-up reauthentication.
3. Independently verify the audit checkpoint **outside** the application's database (signed transparency log/inclusion/consistency proof or equivalent), prevent DBA deletion/rewrite and verify event sequence on replay/restore; V7 SHA256 alone is not an anchor.
4. A **separate owner-authorized trusted-key activation ceremony** may be considered only after these gates are externally verified. Activation must never follow automatically from this signature.
5. Later positive field-grant issuance still needs reviewed semantic/source-target/JavaRosa value compatibility, data-owner/subject consent, durable atomic issuance and revocation, actor-specific source read, Android protected first-save, final server submission and offline crash/retry handling.
6. Rehearse actual production Flyway V3–V7 upgrade histories, remediate the known default-administrator P0 and complete owner-deferred physical Android/Keystore E2E before release.

**Keep every PR Draft. Do not merge, sign an APK, deploy, import historical answers or alter any real records.**
