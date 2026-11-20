# M3D4C2B2B2C2 — Two-Witness External Checkpoint Agreement (non-authorizing)

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2b2c2-dual-witness-checkpoint`
Status: **DRAFT — exact-head CI required**.
Parent: technically qualified Draft PR #20 (historical signed Merkle-log consistency). All upstream PRs #19–#7 remain Draft, unmerged.

## Purpose and strict trust boundary

Build a **pure cryptographic verifier** requiring **TWO different Ed25519 witness-signing identities** to sign a claim that they observed the same current external log checkpoint. This chains behind the already verified PR18 external ceremony attestation, PR19 V7 inclusion, and PR20 signed older-to-newer append-only consistency.

This milestone is NOT proof that the log was independently published. A compromised log publisher could fabricate two private keys and two plausible receipts. No real independent network witness, immutable externally governed key catalog, trusted outside-database checkpoint storage, external signed timestamp, public endpoint, TLS-fetch path, HSM signing, secure token issuer or signing-key activation is provided. The verifier's input trusts are synthetic dependencies. **The final result is non-authorizing even on cryptographic success.**

## Signed receipt format

`ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt` binds:
- Witness ID and Ed25519 verification-key ID (bounded 1–128 characters).
- Exact external log ID, log verification-key ID, signed Merkle tree size and root SHA256.
- SHA256 of the current canonical PR19 signed checkpoint **bytes** (the checkpoint's signature is independently checked by PR19/20).
- Whole-second signed checkpoint time, independent witness observation time (not before checkpoint, at most six hours after checkpoint), and witness receipt expiry (after observation, at most 24 hours later).

Each receipt is encoded with a **versioned, domain-separated, length-prefixed binary format** and signed with exactly 64 Ed25519 signature bytes, canonical unpadded Base64URL. Any mutation of observed tree, log identity, witness ID, key ID, checkpoint digest, timestamp or expiry breaks the signature. Observing a checkpoint after its signed validity window, in the future, or using an expired receipt is denied.

The caller must supply a `TrustedWitness` with witness/key IDs, Ed25519 public key and an expected externally pinned public-key SPKI SHA256. The verifier recomputes the fingerprint and checks it, but this does **not** establish that the supplied expected fingerprint was actually published by an independent authority. Future production must retrieve the public key, ID, SPKI fingerprint, and permitted witness organization from a separately administered authenticated trust store. A client-selected key or static false revocation callback is not acceptable for operational trust.

## Inspection sequence

1. Validate the exact **current PR20 historical consistency** of prior signed checkpoint, current signed inclusion and independent V7 attestation.
2. Validate each witness signer key ID, public SPKI pin, Ed25519 signature, observation/expiry times and server-owned *witness-key/proposal/checkpoint* revocation lookup. Both witnesses must use keys separate from the log signer and ceremony attestor.
3. Reject the same witness ID, the same witness key ID, or identical public SPKI bytes counted as two witnesses.
4. Compare two *validly signed* receipts. If both refer to the same external log/key/tree size but different roots, return `BLOCKED_SIGNED_WITNESS_EQUIVOCATION` instead of treating the pair as a successful quorum. A different log, key ID, or size returns `BLOCKED_SIGNED_WITNESS_HEAD_MISMATCH`.
5. Both signed receipts must bind the **exact current PR19 canonical checkpoint**, including the log/key IDs, tree size/root, checkpoint timestamp and canonical checkpoint SHA256. Unknown/malformed/stale/mismatched evidence blocks.

A valid pair returns only:

`TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST`.

The evidence may have `cryptographicWitnessAgreement=true` but **all operational and publication states remain false**:
`independentlyPublished=false`,
`antiSplitViewVerified=false`,
`trustedKeyRegistered=false`,
`canIssueGrant=false`,
`canReadHistoricalAnswers=false`,
`canTransfer=false`,
`canSubmit=false`.

A single conflicting signed pair is evidence of **possible equivocation within the supplied receipts**, not a proof of globally observed split-view attack. Distinct keys/accounts do not imply distinct operators, organizations or humans.

## Tests and CI

`ExternalCheckpointWitnessQuorumVerifierTest` composes the existing synthetic independently signed history, inclusion and Ed25519 attestation fixtures. It creates two **additional ephemeral, unrelated Ed25519 witness keys** in memory and checks:
- Two signed, current exact matching receipts with zero operational rights.
- Missing witness, wrong signer/key ID, wrong pinned SPKI, false signature.
- Validly signed conflicting roots at the same log/tree size -> equivocation rejection.
- Valid signed wrong log and wrong tree size -> head mismatch rejection.
- Reused witness ID, shared witness public key or verifier key ID, and witness key reused from log signer or attestor.
- Revoked witness, unavailable revocation provider, missing trust dependency, future and expired receipts.
- Unbounded observation/expiry windows, invalid signature encoding and structurally impossible positive trust/publication/transfer result.

Existing JavaRosa, static XForm, record-work HTTP/ODK, original source-preservation, Ed25519, ceremony proposal/reviewer/witness and PR19/20 proof regressions remain in the dedicated `.github/workflows/m3d4c2b2b2c2-dual-witness-checkpoint.yml` on H2 and PostgreSQL 15. Broad frontend/backend and PostgreSQL migration/schema CI must also pass **at the same exact head**. No production migrations or external services are exercised.

## Mandatory next milestones and release gates

1. Deploy an independently operated, **allowlisted HTTPS-only**, secure outbound checkpoint and receipt fetcher with pinning, private/reserved-IP/redirect/SSRF blocking, explicit timeouts/response-size limits and authenticated witness signing keys. No arbitrary user-supplied URLs. Acquire both receipts independently, rather than from an operator-controlled bundle.
2. Pin an old signed head **outside the application database** and re-fetch both witnesses independently across sessions, monitor append-only consistency and gossip to detect equivocation. Protect separate witness organizations and trust-key rotations; keep tamper-evident witness records outside the application's DB and audit key-revocation status at every check.
3. Independently verify trusted real humans and actual external HSM custody/attestor ceremony and signed external bundle; strengthen step-up privileged authentication and audit transparency.
4. Only after explicit owner approval consider a **separate** trusted Ed25519 public-key activation ceremony and field-specific consent and semantic JavaRosa compatibility qualification.
5. Production positive grant issuance and enforcement must check current revocation/work/actor, source-answer read, protected Android first-save/restart and final server submission; original record identity and distinct FOLLOW_UP visits must remain intact.
6. Production Flyway V3–V7 historic upgrade rehearsal, known default-admin P0 fix, and user-deferred physical Android/Keystore E2E remain blockers.

**Keep PR Draft. Do not issue grants, activate trust keys, copy original answers, sign/release APKs, merge PRs or deploy.**
