# M3D4C2B2B2B — Inactive Signed Checkpoint & Merkle Inclusion Proof

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2b2b-checkpoint-inclusion-proof`
Status: **Draft, exact-commit H2, PostgreSQL 15 and broad CI required.**
Depends on fully qualified M3D4C2B2B2A External Ed25519 Attestation Draft PR #18 and earlier unmerged Draft PRs #17–#7.

## Security purpose and exact limitations

Verify that the exact current V7 offline key-ceremony **commitment** is cryptographically included in a log tree represented by an independently Ed25519-signed checkpoint. The log verification public key must be separately **pinned by a real external trust authority**, and the preceding external attestation signature (PR #18) must itself pass verification against the fresh server V7 evidence packet.

**This slice does not fetch any checkpoint from an independently operated server, prove it was ever publicly published, confirm historic append-only consistency, establish externally trusted identity, or enroll an operational Ed25519 signing key.** The current library accepts a `TrustedLogKey` descriptor as a dependency and checks its public SPKI SHA256 against the descriptor's expected pin. It does **not** establish that this pin was independently distributed. The caller in tests is trusted synthetic code; a future production wrapper MUST obtain both key ID and fingerprint from a separately administered, authenticated external trust store, not caller JSON. There is no production trust-store provider, key seed, log publisher, network fetch, controller, DB migration, signing key or positive-grant issuance path.

A mathematically valid inclusion proof for a *privately fabricated* log tree is possible, therefore success MUST be interpreted as **cryptographic inclusion only**; it is NOT independently anchored evidence.

## Protocol / domain separation

`ExternalCeremonyCheckpointVerifier.Checkpoint` (version 1) binds:

- Bounded externally scoped log ID and Ed25519 verifier key ID.
- Nonempty Merkle tree size (1 through 2^50).
- Exactly 32-byte SHA256 root in 64-digit lowercase hex.
- Signed checkpoint creation and validity timestamps, whole UTC seconds, with validity no longer than 48 hours.

The checkpoint is canonically binary-encoded with length-delimited protocol domain `DATA_COLLECTOR_EXTERNAL_CEREMONY_LOG_CHECKPOINT_V1`, log ID, verifier ID, fixed-width tree size, raw root bytes, and 64-bit creation/expiry seconds. The signature is exactly 64 bytes of Ed25519 in canonical **unpadded Base64URL**, with **no private signing key** in the application.

Its canonical SHA256 digest must exactly equal the `externalCheckpointSha256` field of the **already independent-Ed25519-signed** ceremony attestation, closing the link between PR #18 attestor signature and this log checkpoint. A checkpoint cannot be dated after the attestation referencing it, or before the exact V7 witness recorded timestamp. Its validity must include the current server time.

### Why the log leaf is the V7 commitment, not the attestation

The external attestation already contains its checkpoint digest. If the checkpoint's Merkle tree instead contained the entire signed attestation, the signed attestation and checkpoint would be mutually dependent (cryptographic circularity). The canonical leaf is therefore the **underlying V7 commitment**:

`length(domain DC_V7_CEREMONY_COMMITMENT_LOG_LEAF_V1), project UUID, proposal UUID, raw 32-byte V7 commitment SHA256, raw 32-byte proposed public-key SPKI SHA256`.

The canonical V7 commitment hash already binds the preceding proposer, reviewer, witness and external bundle details through V6/V7 normalized evidence. The same current V7 server packet is checked in PR #18 before this log proof is accepted.

RFC6962-style SHA256 tree hashing:

- Leaf: `SHA256(0x00 || canonicalV7LeafBytes)`
- Internal node: `SHA256(0x01 || left32 || right32)`.
- Inclusion audit path: bounded to 64 SHA256 siblings (hex), exact 0-based index smaller than signed tree size, traversed with proper rightmost/odd-tree semantics, exact sibling consumption and final signed root comparison.

Proofs with wrong sibling order, missing/extraneous nodes, invalid index or tree size, or inconsistent root fail closed. This verifies membership under the particular checkpoint **only**. It does not prove append-only consistency against prior signed checkpoints or eliminate split views.

## Read-only verification requirements

`ExternalCeremonyCheckpointVerifier.inspect` requires the exact signed PR18 attestation, independently trusted attestor public key and current V7 server packet, attestor revocation decision, independent signed log checkpoint, Merkle proof, separately pinned log public Ed25519 key and SPKI hash, current server clock, and log key/root/proposal revocation decision.

It first invokes PR18 attestation verification to require a current independent signature, exact project/proposal/form-era V7 evidence and external attestor-key ID, then checks the log's key identity/pin, checkpoint's canonical digest matching the PR18 attestation, causal and expiry times, **separate** Ed25519 log key (cannot reuse attestor signing key), signed checkpoint authenticity, Merkle inclusion, and live external log revocation. Null, wrong or unavailable dependencies and invalid signatures/proofs reject; no callback from a client is acceptable in a future production wrapper.

On cryptographic success the only state is:

`CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST`.

Only `cryptographicInclusionVerified=true` is permitted; all other rights MUST remain false:

`independentlyPublished=false`,
`historicalConsistencyVerified=false`,
`trustedKeyRegistered=false`,
`canIssueGrant=false`,
`canReadHistoricalAnswers=false`,
`canTransfer=false`,
`canSubmit=false`.

No historical submission answers, media, Android instances, private signing keys, or server-issued mapping tokens are read/produced. There is no new API/controller or production Flyway migration. All existing V1–V7 scripts remain unchanged.

## Synthetic qualification

`ExternalCeremonyCheckpointVerifierTest` uses two **different ephemeral in-memory Ed25519 key pairs**: an independent external attestor and a separately pinned log signer. It constructs a three-leaf uneven RFC6962 tree from the V7 ceremony commitment and other synthetic leaves, signs a checkpoint, signs a PR18 attestation binding its canonical checkpoint SHA and then independently checks inclusion.

It tests success without operational rights; one- and two-leaf tree traversal; order-sensitive three-leaf proofs; wrong/missing/extraneous siblings or index; unrelated key, bad pin, shared attestor/log key and wrong log identity; mutated checkpoint hash despite valid log signature; causally late checkpoint, expiry, invalid bounds and tree size; external attestor revocation; log key/root/proposal revocation or unavailable revocation service; structurally impossible trust, publishing, consistency and transfer permissions.

Dedicated `.github/workflows/m3d4c2b2b2b-external-checkpoint-inclusion.yml` runs all new synthetic tests plus earlier JavaRosa, XForm, Ed25519 signature, V6/V7 independent reviewer/witness HTTP and preserved OpenRosa amendment/submission tests under **both H2 and PostgreSQL 15**. Broad Quality Gate independently runs full backend/frontend and PostgreSQL schema/migration tests on the exact head. Physical Android testing remains deferred by the owner, NOT waived.

## Mandatory next milestones / launch blockers

1. **Independently fetched** signed log heads and inclusion/consistency proofs over authenticated TLS from an external operator; verify that the log actually published and *kept* this checkpoint. Monitor gossip/split-view attacks, immutable outside-DB anchor and checkpoint age. Don't claim publication based solely on a signed head supplied by the same DB/operator.
2. Independently operated Ed25519 verifier/attestor public-key trust catalogs and revocation, audited key rotation and owner-approved governance. Verify separately managed keys and human identities/privileged reauthentication. No signing or activation solely because the same operator supplied a key and proof.
3. The real external ceremony, third-party authenticated custodian/HSM evidence and independently verifiable signed bundle: no supplied hash is proof of ceremony attendance, distinct humans, or hardware key protection.
4. A **distinct owner-authorized** trusted public-key activation process, then field-level semantic, original/target JavaRosa value and explicit consent validation, signed time-bounded grants, immutable anchored ledger and transactional revocation.
5. Current actor authorization at every actual sensitive use stage: source-answer retrieval, Android encrypted first-save/restore and final server submission. Ensure no duplicate/orphaned drafts, source instance identity preservation and separate FOLLOW_UP visits.
6. Rehearse actual production Flyway V3–V7 legacy histories, resolve known default-admin P0, qualify crash/restart/offline and deferred physical Android/Keystore/device E2E.

**Keep the stacked PR Draft, do not merge, deploy, issue grants, sign an APK, write production databases, or copy historical answers.**
