# Data Collector — Rebaselined Roadmap v2.0

**Effective:** 20 November 2026  
**Status:** Proposed in a **standalone Draft documentation PR**; no feature branch is rebased, merged or deployed by this document.  
**Authoritative original tracker:** [`odk-platform-task-progress.md`](../odk-platform-task-progress.md), audited **15 August 2026**. Do not rewrite its 279 original task IDs.  
**Scope:** Spring Boot 3 / PostgreSQL / JavaRosa 4.3.2 platform and custom ODK Collect 2026 Android app.

## Why rebaseline

The original M0–M21 platform roadmap and its task IDs were reused for a distinct ODK 2026 migration stream. New security/record steps then accumulated names like `M3D4C2B2B2C3` and Android `M4C-2B-3C-3B-3B`. These are **legacy implementation labels**, not a sustainable roadmap. A green CI job on a Draft PR is not an integrated milestone, a physical-client certification, or release approval.

At this snapshot, `jeresaf/data-collector` has **22 open Draft PRs #1–#22**, and `jeresaf/collect` has **22 open Draft modernization PRs #16–#37**. There are **44 unmerged modernization PRs** across the two repositories. All Android #16–#37 and backend #1–#22 remain Draft, not merged. Existing earlier Android PR #15 is merged but does not certify the post-upstream-migration behavior.

A GitHub comparison on 20 November 2026 shows the last backend modernization Draft branch **159 commits ahead, 0 behind** backend `main` (94 reported changed files), and the latest Android modernization Draft branch **5,803 commits ahead and 129 behind** `ubos-local`. Android commit counts include large upstream ODK history: **do not interpret 129 as a measured number of missing customizations**. The old customization inventory and equivalent implementation check are still required. See [PR crosswalk](MODERNIZATION_PR_CROSSWALK.md).

## Numbering policy — permanent

1. **M0–M21 are frozen in meaning** and remain the canonical **original platform** roadmap. Reference original tasks as `TASK 15.8`, etc. Their 15 August audit states are historical snapshots, not automatically current.
2. **U01–U09 are the only top-level NEW modernization milestones.** U = ODK 2026 and longitudinal-record Upgrade; use fixed two-digit sequential numbers and descriptive names. Individual work items use `U01-T01`, `U01-T02` only; **never append nested milestones to a task**. Bug fixes use `FIX-####`, evidence uses commit/PR/workflow IDs, not new milestones.
3. **G01–G06 are cross-cutting gates**, not milestones. The next U milestone may start when its development dependencies are satisfied; a later production gate does not retroactively invalidate a passing development test.
4. Keep old PR titles, branch names, commit history and documentary labels as historical aliases. New Draft PRs/tickets use the new milestone ID; old PRs get a crosswalk rather than mass-renaming or history-rewriting.
5. A milestone is **ACCEPTED** only after its functional criteria, exact-commit evidence and integration/approval criteria have passed; `CODE EXISTS` or `CI GREEN IN DRAFT` is not `ACCEPTED`.

## Original platform roadmap M0–M21 (unchanged)

| IDs | Fixed scope |
|---|---|
| M0–M4 | Scope and architecture; foundation; reference documentation; security/access; project management |
| M5–M7 | XForm upload and JavaRosa validation; metadata; publishing and assignments |
| M8–M12 | OpenRosa form download; manifest/media; submission upload; processing; admin review |
| M13–M18 | Web-form runtime; groups/repeats; advanced logic; media/geo; XLSForm conversion; generic reference data/entities |
| M19–M21 | Generic reporting and exports; audit trail; testing, integration and interoperability certification |

**Important:** `odk-platform-task-progress.md` records the original 279-task baseline and 15 August 2026 statuses, including browser acceptance gaps, physical-device test deferral and incomplete audit operations. It must not be rewritten as “all completed” just because some Draft PR workflows now run PostgreSQL or Playwright. The August status is not a new real-time re-audit.

## Modernization roadmap — U01–U09

| New ID | Outcome and primary work | Current evidence / status | Development exit criteria |
|---|---|---|---|
| **U01 — Reconcile & Integrate Baselines** | Inventory ODK 2026 upstream delta, `ubos-local` customizations, two repository Draft stacks and branch dependencies; reconcile independent backend PR #1. Establish clean integration order and exact-commit regression baseline. | **IN PROGRESS.** PR inventory and comparison captured; no integration merge. | Document missing/retained/replaced fork changes and app ownership; no unreviewed source/feature loss; CI green at integration heads; owner approves merge/rebase plan before any actual merge. |
| **U02 — Generic ODK 2026 Client** | User-configured or build-configured unique server URL, project setup, ODK compatible form download, app identity, no hardcoded geography/forms, baseline credentials and revocation. | **CODE IN DRAFT** Android #16–#17 plus earlier upstream-forward-port branch; backend #1–#3. | Demonstrate clean build, URL/hostname security, OpenRosa formList, authenticated form download, versioned forms; verify old `ubos-local` customizations against new implementation. |
| **U03 — Longitudinal Server Records** | Version-aware **AMENDMENT** of existing records versus independent periodic **FOLLOW_UP**/second visits, source identity, immutable original, auditable revision links, current project/actor authorization. | **CODE IN DRAFT**, backend #4–#6; Android #18–#20 are consumers. | Real HTTP and PostgreSQL tests for both work types, differing source/target form versions, permissions, duplicate/rollback protection and no original overwrite; no source answer disclosure without grant. |
| **U04 — Protected Native Record Browser & Editor** | Select assigned server work; read-only record/context; JavaRosa-backed user-entered text/number/date/choices/repeats/media; private encrypted draft, revisit/crash restore, zero unauthorized upload. | **CODE IN DRAFT**, Android #18–#20 and #25–#32, with portions of #21–#24; no release acceptance. | Emulator/synthetic integration verifies protected first-save, repeat row and attachment identity, current version validation, draft resume, revocation/recovery and no ordinary upload bypass. |
| **U05 — Periodic Reference Data Refresh** | Refresh centrally supplied admin units and other form-dependent lookup datasets independently from re-editing a household; support scheduled second-visit downloads, refresh/version/TTL and clear provenance. | **NEEDS RE-QUALIFICATION** from merged legacy Android #15 and original M18; no modern end-to-end refresh acceptance. | Authorized project/role-scoped dataset download + version/expiry and offline replay/refresh tests; independent FOLLOW_UP work remains distinct from changes to prior submissions. |
| **U06 — Cross-Version Mapping & Value Compatibility** | Old and target XForm schema/choice/repeat/media mapping, review of field semantics, typed JavaRosa values, per-field consent and transfer policy; unsupported transformations require re-entry. | **PARTIAL CODE IN DRAFT** backend #7–#12, Android #21–#24 and #33–#37; full positive transfer remains blocked. | Explicit field-by-field compatibility and choice/repeat/media rules; reviewer independence and source-owner consent; no accidental prefill; exact source and target version binding; mapping survives restart without unauthorized reuse. |
| **U07 — Server Mapping Authority & Audit** | Issue, expire/revoke and verify narrowly scoped rules only after separate trust and identity approval; enforce at source reads, controlled transfers, first-save and submit. Include audited key lifecycle and external evidence where truly necessary. | **DENY-ONLY / INERT CODE IN DRAFT** backend #13–#22; signatures, revocation and witness proof contracts exist, but **no trusted production key, independent publication or positive grant issuance**. | Minimal approved trust/consent policy, independently verified operator/key custody, reviewer separation, replay/revocation gates, actual audited external evidence if activated. No “infinite security submilestones”; implementation tasks must have a real release gate. |
| **U08 — Safe Native Submission & Backend Enforcement** | Complete approved amended/visit instances from secure Android workspaces, send via OpenRosa or explicitly scoped dedicated API, preserve revision/instance identity and media ownership, reconcile retries and server acknowledgment. | **NOT YET END-TO-END ACCEPTED.** Ordinary OpenRosa exists; protected new-work submit/grant pathway is disabled. | PostgreSQL and Android emulator E2E for edit→save→restart→finalize→upload, retries/offline/crash/media, authorization on all stages, no duplicate submission/original overwrite, FOLLOW_UP isolation. |
| **U09 — Release Certification & Operations** | CI integration, security hardening, actual production Flyway history rehearsal, deployment/upgrade/rollback, actual-device ODK Collect/KoboCollect/Android tests and staged pilot sign-off. | **BLOCKED** by upstream Draft integration, default-admin P0, external authority and owner-deferred device tests. | Owner-approved merges and release, reproducible builds, mobile signed artifact, full PostgreSQL/browser/mobile tests, physical device/Keystore sign-off, production safety evidence. |

**Priorities are dependency-driven, not merely number-driven.** U01 is next. U02 and U03 may be integration-qualified in parallel afterward; U04/U05 may develop against deny-only work; **positive historical-value copying** needs U06+U07; **protected submission** needs U08; rollout requires U09. The goal is a functioning generic collection system, not prolonged speculative attestation work.

## Cross-cutting acceptance gates (NOT milestone numbers)

| Gate | Required proof | Can be deferred? |
|---|---|---|
| **G01 — Provenance & Scope** | Exact branch/SHA, preserved original task IDs, old-fork customization crosswalk, no hidden destructive scope; explicit owner review of merge order. | No, before integration |
| **G02 — Automated Behavioral CI** | Unit/integration/HTTP tests on exact head, H2 + **real PostgreSQL 15**, frontend/unit/build/Playwright, Android build/emulator/synthetic gates as applicable. | No, before claiming integrated development acceptance |
| **G03 — Record Invariants** | Source immutability, current authorization, separate AMENDMENT/FOLLOW_UP, idempotent media/submission revisions, no source-answer disclosure or first-save/submit bypass. | No for any sensitive code |
| **G04 — Trust & Consent** | Verified people/keys, positive source/target semantic review, consent, signed grants, current revocation and actual use-stage enforcement for every **positive transfer**. | Yes for inert, deny-only development; NO before enabling transfer |
| **G05 — Device Certification** | Real ODK Collect/KoboCollect and physical Android/Keystore, offline/crash/restart, camera/media and actual-server interoperability. | Owner deferred **for development only**; NO for production release |
| **G06 — Production Upgrade & Security** | Additive Flyway history checks against real legacy DB, default-admin P0 fixed, secrets/custody/audit/deployment/rollback/owner authorization. | Yes in Draft development; NO for production |

## Status language — enforce in every PR

`UNASSESSED` → `CODE_IN_DRAFT` → `CI_VERIFIED_DRAFT` → `INTEGRATED` → `DEV_ACCEPTED` → `RELEASE_ACCEPTED`.

- `CI_VERIFIED_DRAFT` means the *exact head* has green specified checks, NOT merged or deployed.
- `INTEGRATED` means actual reviewed stack consolidation (with explicit owner approval for merge), NOT just stacked ancestry.
- `DEV_ACCEPTED` means functional gates passed with device gate explicitly remaining release-pending where deferred.
- `RELEASE_ACCEPTED` requires G01–G06 and owner sign-off.
- `BLOCKED` and `PARTIAL` may annotate any status with missing gates; do not misrepresent “green” as release-ready.

## Immediate execution queue: U01

1. **U01-T01: Inventory and freeze a baseline manifest** of every Draft PR, its base/head branch, current passing CI evidence, and its original legacy label. **Initial inventory done** in [crosswalk](MODERNIZATION_PR_CROSSWALK.md). Both repositories remain unmerged.
2. **U01-T02: Build source-level `ubos-local` → ODK 2026 feature diff** including original app-user permissions, form download, notifications, source instance ownership, admin unit refresh, offline queues, version-specific editing and attachment handling. Flag each `PRESERVED`, `REIMPLEMENTED`, `INTENTIONALLY_RETIRED`, `MISSING`, or `UNVERIFIED`, with file/test evidence.
3. **U01-T03: Audit branch topology, integration conflicts and CI for exact backend/android tips.** Backend Draft #1 is parallel to the #2–#22 stack; Android #16 depends on forward-port root and was built against divergent `ubos-local`. Do **not** merge 44 PRs blindly, force-push old provenance or silently delete work.
4. **U01-T04: Propose and qualify an integration sequence** with conflict/duplicate-commit resolution, required squashes where needed, review checkpoints, and rollback. Seek the owner's explicit approval **before** actual integration/merge or release.
5. **U01-T05: Once integration is approved, repeat G01–G03 exact-tip tests** and prepare U02/U03 functional acceptance work. Do not claim U01 accepted until actual integration is proven.

**Decision rule:** No more alphabet-soup security milestones. The next code change should address U01-T02/T03 audit or a clearly scoped U02/U03 functional gap. The inert retrieval layer from Draft PR #22 may remain unchanged until genuine independently operated endpoints, owners and trust roots exist.

## Preserved requirements

- XForm XML remains the single form source of truth; JavaRosa 4.3.2 remains the authoritative mobile-compatible evaluation/validation engine. Do not silently substitute a different parser for compatibility.
- One generic Android application; only the unique server URL/project setting changes per deployment. No hardcoded district/location, custom form IDs, or unrelated branding.
- Web Users (JWT) and mobile App Users (OpenRosa Basic) remain distinct identities; privilege separation and consent are enforced server-side.
- Existing records may be amended with a **new published form version**; independent periodic visits are **new FOLLOW_UP work**, not edits overwriting the prior record.
- Data refresh and offline availability are first-class features; attachments, repeat rows and original record identity survive retries and crash recovery.
- Historical values never transfer without explicitly approved field-level mapping, current source/target authorization and protected lifecycle checks. A deny-only prototype is not a grant.
- Physical device testing is postponed by owner request, **not waived**. No production migration/merge/deployment/APK release is implicitly authorized by this roadmap.

## Maintaining this document

Add at most **one milestone U-number** when a truly new end-user capability or release phase is identified. All work under an existing milestone receives a `Uxx-Tnn` task ID and evidence links. Update the crosswalk when PRs are merged/closed; replace old roadmap identifiers only in future work titles, not retroactively in historical evidence.
