# M3D4C2B2B2C1 — Historical Signed Checkpoint Consistency Proof (non-authorizing)

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2b2c1-log-consistency-proof`
Status: **Draft; exact-head focused and broad CI must pass**.
Parent: qualified M3D4C2B2B2B checkpoint-inclusion Draft PR #19. Prior PRs #18–#7 remain unmerged.

## Scope and truth-in-labeling

Add a **read-only, pure-cryptographic** verifier that a *later* signed external log checkpoint can be demonstrated as an append-only extension of a *previously trusted/pinned* signed checkpoint.

The previous milestone verified inclusion of the exact V7 ceremony commitment under a signed Merkle root; it could not distinguish a log that honestly appends events from one that silently rewrites old history. This new verifier reconstructs **both** checkpoint roots under a bounded RFC6962-style consistency audit path, detects mathematical forks/incompatible historical heads, and rejects signed newer checkpoints that roll back or fail to advance the tree size.

However, the library **does not independently obtain or authenticate any old pinned checkpoint**. Its `HistoricalAnchor.externallyPinnedCheckpointSha256` is a dependency that must be supplied by an external controlled service in a later milestone; a malicious app/database/client can invent a matching hash and matching signed historical head if it controls the verifier key. It also does not fetch the newer log head, establish public publication, verify independent monitor signatures, obtain a trustworthy checkpoint history, detect all log split views, or provide a production trust-store and revocation provider. It cannot claim independent publication, strong anti-split-view assurance, trusted key activation or source-answer transfer.

No database migration, HTTP controller, signer, network caller, production signing key, grant route, Android draft update, source-answer exposure or deployment is included.

## Signed and pinned inputs

`ExternalCeremonyLogConsistencyVerifier.CurrentInclusion` includes the same exact current V7 packet, independently signed PR18 external ceremony attestation, separately pinned PR19 log public key (SPKI SHA-256), new signed checkpoint, Merkle inclusion path, and current attestor/log revocation callbacks.

`HistoricalAnchor` includes a **previously signed log checkpoint** and its supposedly externally pinned SHA-256 over **canonical checkpoint bytes** (not a free-form JSON serialization). The pin equality is cryptographically checked, but the service cannot prove independent pin provenance. The old checkpoint is Ed25519-verified under the **same separately pinned log public key** as the new checkpoint. Any key rotation between checkpoints is denied until a separate signed rotation-chain ceremony is implemented.

Both checkpoints must refer to precisely the same log and log verifier key ID; the older tree size must be positive and **strictly less** than the newer one, with strictly earlier signed creation time. A smaller current tree is a rollback; an equal-sized head is denied as non-progress even if it repeats the same root, and a same-sized divergent root is also denied. Old and new roots must be correctly authenticated by the log key; external log revocation is checked for **both** roots, as are PR18 attestor key/proposal revocation and PR19 newer root revocation. Missing, unavailable or revoked dependencies block.

Old checkpoints are historic evidence. Their original signed time window is verified by the Checkpoint record but is **not required to cover the current date**; an independently witnessed old checkpoint may have expired after originally being published. This does not extend its validity as a source of operational authority.

## RFC6962-style consistency

A `ConsistencyProof` contains a bounded list of at most 64 canonical lowercase 32-byte SHA256 sibling hashes. The verifier follows the `SUBPROOF` model and the RFC6962/RFC9162 consistency check, with special initialization for old tree sizes that are or are not powers of two. It recalculates **both** old and new hashes under the existing PR19 node hashing `SHA256(0x01 || left32 || right32)`, including rightmost odd-tree folds. It rejects incorrect size bounds, missing/excess proof nodes, wrong order, wrong root and inconsistent prefixes. Mathematical equal-sized consistency may be checked by the pure helper, but the production-facing read-only `inspect` does **not** treat replaying an unchanged head as fresh append-only progress.

The cryptographic root check runs **only after** the current newer PR19 inclusion and PR18 signed attestation are verified, preventing an unrelated signed log head from being accepted as a valid record-specific ceremony evidence.

Success returns only:

`CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST`

The response can set `cryptographicConsistencyVerified=true` but all other fields remain **hard false**:

`independentlyPublished=false`, `antiSplitViewVerified=false`, `trustedKeyRegistered=false`, `canIssueGrant=false`, `canReadHistoricalAnswers=false`, `canTransfer=false`, `canSubmit=false`.

This is mathematical evidence, **not authorization**.

## Qualification

New `ExternalCeremonyLogConsistencyVerifierTest` uses ephemeral, distinct process-only Ed25519 attestor and log keys. It generates signed synthetic 3→5 and 4→8 logs, independently computes canonical roots and RFC6962 `SUBPROOF` paths, and exhaustively checks every strict prefix from 1 through 39 of every tree through 40 leaves. It checks tampered proof nodes, missing/excess/reordered siblings, swapped/forked old roots even when signed by the same synthetic key, incorrect old log key/ID, bad old externally pinned digest, forged old signature, signed older checkpoint dated after a newer checkpoint, same-size replay, rollback to a smaller tree, revoked old log root or unavailable revocation service, invalid current attestor trust and structurally impossible claims of publication, trust or transfer.

The dedicated `.github/workflows/m3d4c2b2b2c1-log-consistency.yml` preserves previous JavaRosa/XForm/Ed25519/real HTTP record-work tests and runs the new test under both H2 and PostgreSQL 15. The broad CI Quality Gate tests the complete backend, frontend unit/build/browser and PostgreSQL migration/schema profile against the exact commit. All checks must pass before calling the milestone technically qualified.

## Remaining release gates

1. An **independently operated, authenticated log fetcher** capable of retrieving checkpoints, inclusion and consistency proofs from an external publisher and a separately trusted independent witness; pin and attest prior heads outside the main database with verified timestamp and durable storage.
2. Strong anti-rollback and anti-split-view controls: external monitored gossip/cross-logging, at least two independent witnesses or equivalent trusted transparency mechanism, confirmation that previously witnessed heads remain consistent across sessions, signed key-rotation chain and external key revocation.
3. External attestor/verifier trust-store lifecycle, separate custodians, cryptographically verifiable HSM private-key possession and real-world human identity/step-up authentication. Hash commitments are not enough.
4. A separate **owner-authorized** operation for trusted signing-key activation, then explicit consent, semantic type/unit/choice/value review and short-lived field-scoped positive signed grants.
5. Transactional reauthorization at sensitive use stages: source-answer retrieval, protected Android encrypted first-save/restart/restore, and final server submission; preserve original source identity and distinct FOLLOW_UP visits.
6. Production Flyway V3–V7 historical upgrade rehearsal, existing default-administrator P0 security closure, physical Android/Keystore E2E deferred by owner (not waived).

**Do not merge, deploy, activate signing keys, issue grants, copy historical values, or distribute APKs. All PRs remain Draft.**
