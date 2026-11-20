# M3D4C2B2B1 — Offline Ed25519 Key Ceremony Evidence Commitments (non-authorizing)

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2b1-offline-ceremony-commitments`
Status: **Draft**. Depends on qualified M3D4C2B2A Draft PR #16 and earlier unmerged backend PRs.

## Objective

Preserve evidence that a **third, separately authenticated web Project Admin** recorded a SHA-256 commitment to a *separately held* offline ceremony bundle for a proposed Ed25519 PUBLIC key. This deliberately **does not verify that the physical ceremony occurred** and **does not mark the proposed key trusted**. Neither the proposer nor the independent reviewer can be the ceremony witness.

The preceding review MUST have disposition `REQUIRES_OFFLINE_KEY_CEREMONY`. A rejected proposal cannot be witnessed. An existing trusted/revoked key ID cannot be witnessed. The original proposer and reviewer must still have verified active accounts and current distinct project assignments. All three must have different user IDs, usernames and emails; collisions with mobile AppUsers are denied. These controls prevent simple account self-review, but **do not prove distinct human identity** and are not a substitute for external physical identity checks and MFA.

## Additive schema: PostgreSQL Flyway V7

`V7__typed_mapping_key_ceremony_commitments.sql` adds only `typed_mapping_key_ceremony_commitments`, keyed uniquely by the prior V6 proposal ID. It contains project ID, independent reviewer/witness IDs, unique witness idempotency key, exact prior proposal/review evidence SHA-256 digests, observed public-key hash, external evidence bundle SHA-256, bounded external reference, server-computed canonical commitment hash and database timestamp. A SQL constraint forbids witness=reviewer. No private key, public key activation status, external evidence bytes, source answers or grants are stored.

The disposable H2 test-only schema mirrors V7. **Production migrations V1–V6 are unchanged.** No production migration is run.

## Web/JWT endpoints

`GET /api/projects/{projectId}/typed-key-ceremony-commitments/{proposalId}/context` — current third-person active project-admin witness only. Returns the exact proposal, original review, and proposed public-key fingerprints; no raw public/private key and no source data.

`POST /api/projects/{projectId}/typed-key-ceremony-commitments/{proposalId}` — same witness-only boundary. Required input:

```json
{
  "clientRequestId": "offline-witness-20261120-001",
  "proposalEvidenceSha256": "<exact lowercase SHA-256 from context>",
  "reviewEvidenceSha256": "<exact lowercase SHA-256 from context>",
  "observedKeySha256": "<exact lowercase SHA-256 from context>",
  "externalBundleSha256": "<lowercase SHA-256 of externally retained evidence bundle>",
  "externalReference": "offline-evidence-bundle-001"
}
```

The witness's input is **a claim and an unverified external content digest**. This API cannot independently inspect the external bundle or attest HSM possession, ceremony attendance, custody, real-world personhood, or consent. No raw external bundle is stored. Exact original/review evidence hashes are **recomputed from the V6 rows** under proposal and reviewer row locks, and the witness-provided hashes must match. Server computes an unambiguous length-prefixed SHA-256 over domain `DC_OFFLINE_KEY_CEREMONY_COMMITMENT_V1`, project and proposal IDs, key ID, proposer/reviewer/witness IDs, original proposal and review hashes, proposed-key hash, idempotency ID, external bundle hash and external reference. This digest can be independently recomputed from the returned fields, but is **not a digital signature or trusted time-stamp**.

There is exactly one API-written commitment per proposal. Exact same-witness/same-content retry returns the original database timestamp; altered witness, idempotency ID, external digest/reference, or any proposal-review evidence produces HTTP 409.

`GET /api/projects/{projectId}/typed-key-ceremony-commitments/{proposalId}/evidence-packet` — the original third-person witness can retrieve a bounded, no-store audit packet with each digest, IDs, reference, database timestamp, and canonical commitment SHA. Server recomputes all stored evidence and fails closed on drift. It does not disclose either source answers or the key material. No update/delete/un-witness endpoint exists.

Every returned packet has `state=EVIDENCE_COMMITTED_PENDING_EXTERNAL_ATTESTATION_NO_TRUST`, `independentlyAttested=false`, `trustedKeyRegistered=false`, `canIssueGrant=false`, `canTransfer=false`, `canSubmit=false`. Context is likewise non-authorizing.

## Qualification

Synthetic Spring real-HTTP tests on H2 and PostgreSQL 15 validate three active distinct web identities, exact reviewed proposal and witness hashes, non-authorizing successful evidence recording, deterministic idempotent replay, changed-intent conflict, prohibited reviewer self-witness and proposer bypass, anonymous/mobile/wrong-project denial, invalid observed key/review hash, revoked proposer membership, altered earlier review, tampered committed external hash, no trusted-key insertion and no accidental grant.

Earlier Ed25519 key proposal/review, revocation, registry, native JavaRosa, static XForms, historical amendment/FOLLOW_UP separation, and source-preservation tests remain in the focused workflow. Broader backend/frontend CI is a separate qualification gate.

## What is still BLOCKED

1. An external offline ceremony genuinely witnessed by verified humans, independent key custodian and a separately validated signed evidence bundle; key possession/attestation under an HSM or hardware-backed device, real-world identity verification and reauthentication.
2. A trusted **independent** audit anchor/timestamp/signature outside the application database, plus restricted DB roles and tested DBA-tampering detection. SHA digests stored inside the same database alone are not independent.
3. A separately approved **trusted** public-key activation process. V7 commitments never write V5 `typed_mapping_trust_keys`; no private signing key or HSM secret is ever imported.
4. Field-specific human semantic and data-subject consent review, server-issued time-limited signed grants, change/revocation rechecks under the relevant transaction on source retrieval, Android encrypted private first-save, and final server submission.
5. Media/repeat/attachment semantics, off-network recovery, actual device and Keystore E2E. User-deferred physical tests remain mandatory before release.
6. Historical production Flyway V3–V7 upgrade rehearsal and existing default-administrator P0 security blocker.

**No production records, migrations, keys, grants, PR merges, signed APKs or deployments are touched.** Do not treat this evidence receipt as permission for historical-answer copying.
