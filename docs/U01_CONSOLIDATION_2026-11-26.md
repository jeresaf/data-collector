# U01 — Consolidation checkpoint, 26 November 2026

Status: development baselines created from qualified heads; **not integrated or release accepted**. No existing PR closed, force-pushed or merged. No deployment, production migration, signing or physical-device certification.

## Exact paired seed baselines

| Repository | development seed | Evidence |
|---|---|---|
| data-collector | `2292709bc4951c62a0adf12779adab9e79744f25` | PR #34; dedicated H2/PostgreSQL 45683860526 attempt 2 and general gates 45683875406/45683860349 |
| collect | `a7740e8f4ead8e2943b0f7a41f7a453e1de0ab36` | PR #100; 45771369719 all four jobs PASS (normal, lost-response, interrupted ingress and upstream) |

Preserve backend main 73ef37e54d3c6f3141b4b2b117ca8771e06e341d; Android master 3fde5d5ab6b37e750560a3c91f718525e0c013e4 and ubos-local 951dafd3d893003493db3c2de2d7568153889165. Backend seed is 225 ahead / 0 behind main; Android seed diverges from ubos-local (6003 ahead / 129 behind) and master (1696 ahead / 329 behind). Upstream history counts are not missing-feature counts.

## Inventory and exception dispositions

118 open drafts before this ledger: 33 backend, 85 Android. Declared PR dependency chains: 27 backend, 82 Android. Verify every exact PR head is an ancestor before closing it; do not infer that from the declared base ref alone. Nine divergent PRs:

| PR | Ahead/behind vs seed | Disposition |
|---|---:|---|
| backend #1 | 4/225 | compare contract/workflow/auth tests; never replay blind |
| backend #23 | 32/225 | documentation covered by descendant #26; verify content |
| backend #26 | 35/225 | stage 12 documentation files from exact head 53c6853f28b3d2dcd5a7ef240cd206b84a2eb1bb; root roadmap links updated |
| backend #28 | 212/51 | independent U05 functionality; reconcile code + test-only ledger and common PostgreSQL migration test; no activate trust or production migration |
| backend #32 | 54/1 | independent HTTPS preflight workflow and two docs; compare with current emulator gate and preserve evidence |
| backend #33 | 1/1 | alternate completion evidence; compare with qualified #34 before supersession |
| Android #41 | 4/167 | compare standalone offline recovery instrumentation/workflow/docs with selected chain |
| Android #71 | 3/40 | compare alternate image-binding implementation/test/workflow |
| Android #73 | 3/40 | compare alternate image-binding implementation/test/workflow |

U05 PR #28 earlier implementation CI 44964304472 at 77bf79f516e4727e09fd18c230da883201ae64c3 passed disposable schema only; current head efb92f20e852944d66d758823800575ff1353128 remains separately unverified. Both U05 and selected backend modify PostgreSqlContainerMigrationIT.java; require careful reconciliation and real PostgreSQL execution. No native importer or production custody claim.

## Consolidated execution order (proposed 26 November)

1. U01: preserve paired seed, reconcile divergent PRs, verify exact ancestry, source parity, and full CI at integration heads; owner approval before default-branch merges and PR cleanup.
2. Complete submission/recovery lifecycle (U03/U04/U06/U08): explicit safe retry authority, concurrency, graphical recovery, queue, offline synchronization, amendment lifecycle.
3. Cross-version editing/mapping (U03/U04/U06/U07): compatibility matrix, consent, actual approved trust and audit, unsupported mappings require re-entry.
4. Native editor completeness (U04/U06): audit all necessary widget types, reuse native ODK, encrypted persistence.
5. Periodic reference data and work management (U03/U05): scoped typed Entity Lists, refresh/expiry/revocation, offline behavior and scheduled independent visits.
6. Generic client/legacy parity (U01/U02): configurable deployment, restore verified missing old customizations.
7. Web/admin completeness (original M2-M20): assess current code and qualify operational workflows.
8. Comprehensive integration/security qualification (U01-U08).
9. Release readiness (U09): physical tests remain deferred for development only, migration/restore/security/pilot and signed release approval.

M0-M21 historical task IDs and U01-U09 canonical milestones unchanged. G01-G06 remain gates. One cohesive feature branch per package per affected repository, logical commits, focused tests plus exact-head package qualification. Never imply that a passing draft or new development branch is integrated or released. Current safe-retry authorization and positive historical-value grants remain disabled.

## Next actions

Verify exact ancestry of all 109 chain heads, reconcile #1/#33 and Android alternatives by file/behavior evidence, integrate #28 with schema/test conflict review and #32 with provenance. Add CI triggers for consolidated branches, repin Android to the qualified integrated backend, run full cross-repository tests. Close/mark superseded old PRs only after evidence and owner approval. No production release before all applicable gates pass.

## Batch 2 — exact ancestry and U05 source integration

All 27 backend chain heads found in complete paginated 225-commit comparison from main to seed; all 82 Android chain heads found in complete paginated 433-commit comparison from forward-port d6c4ecd0f1d470b501bcdbaaf43294820deeb61f to seed. No missing heads. This proves ancestry, not behavioral acceptance.

U05 exact head efb92f20e852944d66d758823800575ff1353128 merged into this candidate with explicit second parent. Compared both sides of common ancestor 312c67c4a51b7ec7867531af5de3526f14939451: 44 U05 changed files, 43 non-overlapping paths reused as exact source blobs; one overlapping PostgreSqlContainerMigrationIT.java reconciled by verifying U05 is insertion-only relative to common ancestor, adding its imports, injected fields and all three test methods/helpers to the selected baseline without removing existing U06 tests. No production schema migrations or trust activation added by this integration. Full CI remains required; source branch and old drafts preserved.

## Batch 3 — backend alternatives

Combined U05/U06 head 8a5ea267775b438e889e43c849a15537be65de9e passed both quality gate runs 45776734006 and 45776727860 (H2, PostgreSQL Testcontainers and frontend/browser). PR #1: five original ODK2026 test method bodies byte-for-byte present in the combined candidate, contract document identical, focused workflow retains the same test command; preserve later security and typed Entity List changes, add consolidation/development triggers. PR #32: import host-only TLS PostgreSQL workflow and two historical qualification documents, add consolidation/development triggers. The older ledger is historical, not current Android status. PR #33: compare its alternate completed-evidence implementation with selected #34: selected canonical /completion-evidence is consumed by Android and adds total-link uniqueness, answer ownership and filename binding, streaming bounded hash, null-size denial and strict normalized path checks. Preserve selected protocol; do not install parallel endpoint. Port no-auth, revoked assignment and raw storage path denial assertions to canonical real HTTP test. #33's alternate projectId and targetXFormSha256 response fields are not adopted; future contract changes require paired consumer review. #33 source branch preserved. All changes require exact-head CI; no old PR closure or release.
