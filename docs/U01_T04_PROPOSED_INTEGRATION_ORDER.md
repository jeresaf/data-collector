# U01-T04 — Proposed Integration Order and Blocked Release Gates

**Date:** 20 November 2026  
**Status:** **PROPOSAL ONLY. NO MERGE AUTHORIZATION.**  
**Owner:** Data Collector project.  
**Baseline:** `jeresaf/data-collector` and `jeresaf/collect`, with 44 modernization feature Draft PRs plus standalone roadmap Draft PR #23.

## Verified branch topology (GitHub compare)

| Relationship | GitHub status | Ahead | Behind | Consequence |
|---|---|---:|---:|---|
| Backend `main` → `codex/r0-playwright-fixture-reconciliation` (PR #2) | ahead | 3 | 0 | R0 can be reviewed as the first stack layer. |
| Backend `codex/r0-playwright-fixture-reconciliation` → `codex/m3d4c2b2b2c3-restricted-witness-retrieval` (Draft #22) | ahead | 156 | 0 | Existing backend stack #3–#22 is a straight descendant of R0. |
| Backend `main` → `codex/odk-2026-openrosa-qualification` (Draft #1) | ahead | 4 | 0 | PR #1 is independently based on main. |
| Backend `codex/odk-2026-openrosa-qualification` → current backend Draft #22 | **diverged** | 159 | 4 | PR #1 is **not an ancestor** of #22, despite its test/contract files already being byte-identical there. |
| Android `ubos-local` → `codex/odk-2026-forward-port` | **diverged** | 5,570 | 129 | Root ODK 2026 migration does **not** fast-forward the old custom branch; explicit feature reconciliation required. |
| Android `codex/odk-2026-forward-port` → latest modernization Draft #37 | ahead | 233 | 0 | New Android feature stack is linearly on top of forward-port root. |

*Ahead/behind counts use GitHub's compare view of the precise branch refs at inspection time and can change. Android counts include ODK upstream commits, not only custom changes.*

## Two-repository integration graph

```
BACKEND
main --------------------> PR #2 (R0) --> PR #3 --> ... --> PR #22 (GREEN DRAFT TIP)
   \----> PR #1 (4 commits, parallel; two substantive file blobs duplicated)
   \----> PR #23 (documentation-only, CI FAILED on main baseline)

ANDROID
ubos-local ----------------------\
                                  [explicit customization reconciliation needed]
                                  /
ODK 2026 forward-port root ------> PR #16 --> ... --> PR #37 (DRAFT TIP)
```

## Candidate integration sequence (not executed)

### Backend stage B0 — preflight

1. Snapshot exact `main`, PR #2, PR #1 and #22 commit IDs; freeze CI evidence and reviewers.
2. Keep PR #23 documentation separate from production code. Its **red main-based CI** must be reconciled, not hidden. `POST /submission` fails 400 with exact body `No project context available for this user`, and four browser form-flow tests time out. No production behavior can be weakened to make tests green.
3. **Resolve web/mobile principal separation** before enabling historic source reads. `UserDetailsServiceImpl` and `CurrentUserService` both search Web User before App User for equal usernames; a typed principal and web/mobile collision regression are required.

### Backend stage B1 — source and compatibility gate

4. Review PR #2 (R0 fixture/acceptance reconciliation), then its stacked descendants #3–#22 in reviewable batches: foundational OpenRosa contracts and longitudinal work (U02/U03), guarded typed mapping and Android-compatible API (U06), and inert trust/audit proofs (U07).
5. The **PR #1 test file and compatibility doc** are byte-identical in the tip, but its Git branch is diverged. Before owner-approved integration, prove no unique substantive functionality is lost and record PR #1 as **superseded by a reviewed integration commit** if appropriate. Do **not** cherry-pick it blindly or close it without owner review.
6. Re-run **whole H2 backend**, **actual PostgreSQL 15**, **frontend unit/build/Playwright**, and OpenRosa Basic auth integration using one pinned backend commit. Prove role/project assignment, missing context, Web User/App User username collision, revocation, form version, attachments and no source disclosure. Exact CI pass needed.

### Android stage A0 — preserve old owner behavior

7. Record a source-level manifest/provider/app-ID inventory and [all merged #1–#15 legacy PR behaviors](U01_T02_LEGACY_ANDROID_CHANGESET_LEDGER.md) against `ubos-local`. Distinguish replacement by modern ODK APIs from missing features.
8. Qualify the ODK 2026 root separately, then evaluate #16–#37 as 233 successive commits on that root. Do **not** rebase `ubos-local` onto ODK wholesale or force-push history.
9. Require positive parity for rejected/issue-confirmed editing, corrected-instance retry, native repeat constraints, admin-unit/reference refresh, app revocation and provider signing identity before claiming U02/U04/U05 accepted.

### Cross-repo stage X0 — the user’s actual functionality

10. Pin **one backend exact SHA + one Android exact SHA** in a compatibility fixture. Confirm generic single-URL build and authenticated formList, XForm/manifest/media and typed-list download (see [U05 protocol gap](U01_T02_ODK_ENTITY_LIST_SEAM.md)).
11. End-to-end amendment: assigned source form v1, target form v2, no automatic historic answer prefill, explicit consent and denied transfer, encrypted private draft crash/restart, preserve original source and instance ID.
12. End-to-end FOLLOW_UP: independent second visit, new visit/work/instance ID, optional periodic reference-data refresh, **no** replacement mutation of first visit, correct offline/online retry semantics.
13. Before *any* positive cross-version copy, separately review field semantics, consent, expiry/revocation and actual source-read/first-save/submit stage authorization. A signed synthetic witness receipt is not independent published trust.

### Production stage R0 — always withheld pending owner

14. Rehearse production Flyway V3–V7 against a sanitized copy of **real historical legacy schema**, not only H2/Testcontainers fresh databases. Fix default-administrator P0.
15. Complete owner-deferred **physical Android**/Keystore/offline/attachments/Android 11+ interoperability tests. Do not treat synthetic emulator tests as release approval.
16. Obtain explicit owner merge/deployment/APK signing approval **after** project milestones and G01–G06 pass. Stop and roll back on any source ID, authorization or data integrity regression.

## Conflict rules

- Never resolve a file conflict by taking the newest file without verifying old behavior, security negative tests and audit provenance.
- Never change the original **M0–M21** task list or legacy PR names simply to fit U milestone IDs.
- Never convert an untrusted media CSV into `type="entityList"` based on extension alone; require typed, schema-valid list metadata, safe access, and parser compatibility.
- Never grant historical answers for FOLLOW_UP by confusing it with AMENDMENT. No source XML on read-only record-browse endpoints.
- Keep existing merged legacy records and original submissions immutable; use additive migrations and transactional idempotency tests.

## Exit requirements for U01

U01 can become `DEV_ACCEPTED` only with:
- Complete old→new customization verdicts (including the exact U05 typed-manifest gap), **zero unreviewed lost features**.
- Audited backend PR #1 duplication, PR #2 ancestry, and Android upstream divergence with owner-approved consolidation plan.
- Representative cross-repo exact-head compatibility checks and secure failure tests.
- Either a reviewed integration candidate qualified with full CI, or an explicitly recorded/deferred owner decision about merges without inaccurately marking `INTEGRATED`.
- Production and physical-device gates remain pending until U09.

**No changes to live application source/Android APK/production database were performed in preparing this plan.**
