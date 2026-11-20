# M3D4B2B2A — Independent final-signer static field policy pre-authorization

Date: 2026-11-20
Backend branch: `codex/m3d4b2b2a-static-field-policy-gate`
State: **DRAFT** (CI must pass on exact branch head). Stacked on M3D4B2B1 Draft PR #10.

## Purpose

Before defining any **positive** server-signed mapping rule, evaluate whether a previously escalated historical field even qualifies for further policy review. This is a deliberately narrow, **read-only advisory gate**, not a positive approval or grant.

`GET /api/projects/{projectId}/record-work-items/{workId}/typed-rule-candidates/{recommendationId}`

The request requires an authenticated web Bearer JWT with current active project-specific **PROJECT_ADMIN** membership and a different original reviewer; the service reuses the independent final-denial eligibility boundary. Original reviewer must still have active project reviewer membership, the same READY amendment, source instance and exact source XML/historical XForm/target XForm fingerprints. A previous independent `typed_mapping_final_denials` row is a **hard veto**.

The server independently reloads the exact stored reviewer recommendation and field dispositions, recomputes its canonical length-prefixed SHA256 digest and denies any mismatch. It reads only persisted historical and target XForm definitions; it **does not retrieve old XML answers**, return values or touch draft/submission tables.

## Static qualification

`StaticTypedMappingFieldPolicy` parses both persisted XForms with a namespace-aware secure XML parser, disabled DTD/external entities and 8 Mi character bounds. It compares only same absolute target/source paths in the inline instance and exactly one static input control, with identical scalar data type:
- `TEXT` (including XForms default string),
- `INTEGER`,
- `DECIMAL`,
- `DATE`.

The policy fails closed for type changes, missing/ambiguous paths or controls, instance metadata, repeats, external/multiple instances, dynamic bind expressions, non-static values, XForm actions/outputs, unsupported selects, and malformed documents.

**A green finding means only** `STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY`. It is not JavaRosa validation, semantic equivalence, evidence of subject consent, proof of an identity, or authority to read/migrate/write/upload any historical answer. The response always has `state=ADVISORY_STATIC_FIELD_REVIEW_ONLY_NO_GRANT` and `canApproveMapping/canReadHistoricalAnswers/canIssueGrant/canTransfer/canSubmit=false`. No POST/PUT/PATCH route is exposed for approval issuance.

## Tests

Standalone tests verify strict static type equivalence and implicit string binding, integer/decimal mismatch, unsafe calculation, repeat, duplicate input, unknown/metadata paths, XXE/doctype rejection and bounded paths. Real HTTP synthetic tests run the full prior reviewer/signer workflow through JWT with active project memberships and prove:
- An independent final signer sees only the single escalated path/type finding, never original answer XML.
- Original reviewer, mobile enumerator Basic and anonymous principals cannot perform candidate inspection.
- Recommendation field-row tampering is caught by canonical evidence rehash.
- An independent prior final denial vetoes candidate inspection, even for a different signer.
- No POST grant route exists.

H2 and PostgreSQL 15 Testcontainers CI run previous OpenRosa/record-work replacement regressions. The general CI Quality Gate includes backend and frontend quality. Device tests and production migration upgrades are not run.

## Remaining hard gates

1. **M3D4B2B2B** requires strong independent human/signer identity, certified semantics/rationale and explicit full native JavaRosa target-rule evaluation, not a static XML comparison.
2. **M3D4C** must implement a server-owned, signed/anchored, immutable approval grant ledger with time bounds, generation/revocation, nonces, allowed exact paths and values, source/reviewer/signer IDs and exact XForm SHA256 hashes. The final-denial ledger MUST veto grant issuance and use.
3. **M4C client and submission enforcement** must revalidate actual grants at each sensitive read, native private draft first-save and server intake; never trust client-supplied decisions as authority. Prevent replay, identity transfer, and repeated edits without fresh consent.
4. Source-form byte drift, repeat groups, media, select choices, JavaRosa native behavior, cross-process recovery and physical-device tests still require dedicated acceptance.
5. Production Flyway V3/V4 histories need upgrade rehearsal and immutable ledger operations. Existing default admin credential/security P0 remains open. No production change, PR merge, signed APK or distribution occurs here.

Do not use this endpoint or its green findings as a source-answer transfer capability.
