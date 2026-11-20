# M3D4C2B2B2C3 — Inactive, Restrictive External Witness Retrieval Contract

Date: 2026-11-20
Repository: `jeresaf/data-collector`
Branch: `codex/m3d4c2b2b2c3-restricted-witness-retrieval`
Status: **Draft, exact-head H2, PostgreSQL 15 and full CI required**.
Depends on fully qualified dual-witness cryptographic review Draft PR #21 and earlier unmerged Draft PRs #20–#7.

## What this milestone actually implements

`ExternalWitnessRetrievalPolicy` defines an **inert, testable server-controlled outbound evidence retrieval boundary** for three distinct roles: one externally operated log publisher, and two independent checkpoint witnesses.

This milestone **DOES NOT create a live network client, fetch actual checkpoints, register a production HTTPS endpoint, configure a production DNS resolver, publish outside-the-DB evidence, enroll trust keys or enable grants**. It provides a *bounded transport policy contract* and synthetic transport tests. A future separately reviewed implementation of `PinnedTlsTransport` MUST satisfy its connection, TLS and streaming requirements before production activation, followed by parsing, actual cryptographic verification using PR18–21 and independent trust/identity checks.

## Exact fixed endpoint registry

`ExternalWitnessRetrievalPolicy.Registry` accepts exactly three separate, server-operator-approved URI values with no duplicates by role or hostname:

- `PUBLISHER`: `https://<canonical-public-dns-name>/v1/ceremony-checkpoint`
- `WITNESS_A`: `https://<separately-approved-public-dns-name>/v1/checkpoint-witness`
- `WITNESS_B`: `https://<another-separately-approved-public-dns-name>/v1/checkpoint-witness`

The policy rejects any `http` scheme, IP-literal hostname, uppercase/noncanonical hostname, non-443 or explicitly supplied port, user info, query, fragment, wrong path, encoded or dot-segment path, abnormal authority, hidden slash, common internal/Tor/synthetic TLD, missing or repeated endpoint roles and repeated hostnames. **The URLs are not taken from request parameters, submission metadata, arbitrary JSON, database user input or untrusted redirects**. No operational endpoint list has been supplied/configured in this PR.

DNS preflight is an injected `DnsResolver` that may return **1–8 records** and must pass only globally routable addresses. A private, loopback, link-local, cloud metadata (including `169.254.169.254`), shared CGNAT, multicast, documentation, benchmark, IPv4-mapped/special IPv6 or other denied address **anywhere** in the result set blocks the entire lookup; absence/failure also blocks. The static CIDR policy covers common IANA special-purpose ranges but is not a replacement for continuously maintained IANA filtering and egress firewall rules in production.

## Security-critical pinned-TLS transport contract

`retrieve(registry, role, resolver, PinnedTlsTransport)` chooses an already validated numeric IP, then passes `PinnedTarget` containing:
- Exact approved original HTTPS URI/hostname and resolved **IP to dial**.
- Port **443 only**.
- Connection timeout **3 seconds** and read timeout **5 seconds**.
- Maximum streamed response body **16 KiB**.

A future audited transport MUST:
- Connect to **that exact numeric IP** and never re-resolve the hostname internally. If an ordinary HTTP client resolves a host separately after preflight, DNS rebinding creates a TOCTOU SSRF bypass; such an implementation is prohibited.
- Use the *original server-approved hostname* for TLS SNI and certificate chain/hostname verification while dialing the numeric IP, and independently observe the actual connected peer address. Do not accept arbitrary trust-all SSL contexts or user-added certificates as anchors.
- Disable automatic redirects, proxy environment variables, HTTP alternate service, retry to other endpoints, automatic decompression and cookie forwarding. Do not send user credentials or source XML.
- Bound response bytes **while streaming** (not after allocating a giant response), reject excessive Content-Length, wrong MIME type, non-200 responses, 30x Location, content encoding, wrong TLS peer/hostname, and connection timeout.
- Return `TransportResponse` from **observed channel facts**, not from copying requested fields as assertions. The policy itself also enforces exact final URI, peer == pinned DNS IP, verified TLS hostname == approved DNS hostname, no redirects, status 200, `application/json` or `application/octet-stream`, `identity` content encoding and 1–16,384 bytes.

The success response `FetchEvidence` contains defensive-copy bytes, byte count and SHA256 under state `BOUNDED_TRANSPORT_EVIDENCE_ONLY_UNVERIFIED_NO_TRUST`. It is **not** a verified signed checkpoint, witness receipt, public log publication, append-only proof, independent issuer, or permission to process any historical record. Later secure parsers must reject duplicate/unknown fields, enforce signed canonical bytes and bind exact proposal/log/witness and published timestamp to the PR18–21 verification chain.

No production network transport is included, so **no live egress is authorized or performed** by this slice. The injected resolver and transport used in unit tests are synthetic.

## Test qualification

`ExternalWitnessRetrievalPolicyTest` supplies a fake pinned connection, with **no real network**, and verifies:
- Valid exact three-origin policy, correct bounded pinned target and defensive copies, hard-false trust/issuance/transfer/submit flags.
- Denial of HTTP, local/IP/metadata/userinfo/redirect/query/fragment/ambiguous paths, explicit ports, repeated hostname or role.
- Denial of private or special IPv4/IPv6 addresses, IPv4 CGNAT and cloud metadata, mixed private+public DNS answers, empty DNS, >8 answers and resolver exception.
- Denial of 30x status, unexpected final URI or redirect, alternate TCP peer, wrong TLS-verified host, unexpected MIME, decompression, zero/excessive body or missing response; transport exceptions block.
- Missing configuration/resolver/transport cannot create any trusted evidence or positive rights.

The dedicated `.github/workflows/m3d4c2b2b2c3-restricted-external-retrieval.yml` runs all new policy tests plus earlier PR18–21 Ed25519 signing, Merkle inclusion, historical consistency, dual witness and JavaRosa/OpenRosa real HTTP/source-preservation regressions on H2 and PostgreSQL 15. The broad CI quality gate must pass full backend/frontend plus PostgreSQL migration/schema checks **on the same exact commit**.

## Mandatory next milestones / release blockers

1. Build and security-review a **real pinned-IP TLS transport adapter**, with egress firewall/DNS-pinning and certificate verification against separately approved production origins; no general-purpose URL fetcher or trust-all SSL implementation.
2. Independently authenticate and **fetch** genuinely published checkpoints and receipts from separately operated organizations, verify their external public keys against an independently governed catalog and check their revocation. Persist prior signed heads and witness signatures **outside application DB control**; perform cross-session append-only and anti-split-view monitor comparisons.
3. Verify actual human identities, independent signers and HSM custody and the signed external bundle. Separate positive Ed25519 key enrollment and owner authorization still do not exist.
4. Require explicit subject/data-owner consent and field-level semantic/JavaRosa native type, repeat/media and value compatibility certification, short-lived actor/project/work-specific signed grants, guarded source-answer retrieval, Android protected encrypted first-save and final server submission.
5. Rehearse real production Flyway V3–V7 histories, close the known default-admin P0 and complete user-deferred physical Android/Keystore/offline E2E before release.

**No PR merges, production migrations, source-answer transfers, external real fetches, APK signing, or deployments. Keep Draft.**
