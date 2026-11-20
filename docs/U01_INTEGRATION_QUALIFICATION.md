# U01 — Baseline Reconciliation and Integration Qualification

**As of:** 20 November 2026  
**Milestone status:** **IN PROGRESS — NOT ACCEPTED**  
**Rule:** No Android package release, PR merge, branch rewrite, database upgrade or historical source-answer transfer is authorized by these checks.

## Goal

Make the 22 backend and 22 Android open modernization Draft PRs safely integrable and traceable, without losing any original `ubos-local` customization or accidentally treating CI-qualified deny-only prototypes as active production behavior. Keep original platform M0–M21 task IDs unchanged.

## Work items and evidence

| Task ID | Work | Evidence as of 2026-11-20 | Status |
|---|---|---|---|
| **U01-T01** | Enumerate PR stacks, their dependency edges, rename aliases, known CI and tip branches | [Crosswalk](MODERNIZATION_PR_CROSSWALK.md): backend #1–#22, Android #16–#37, 44 Drafts total | **INVENTORY RECORDED**, recheck immediately before integration |
| **U01-T02** | Trace original `ubos-local` custom code, resources, workflows and permissions into the 2026 forward-port and Draft tip | Upstream Android history diverged; 5,803 ahead / 129 behind relative to `ubos-local`. Existing PR titles alone do NOT prove parity | **OPEN: source-level assessment required** |
| **U01-T03** | Compare independent backend PR #1 against #2–#22 stack, model split branch roots, identify actual merge conflicts and exact-tip acceptance | Backend Draft tip 159 ahead / 0 behind `main`, 94 paths in compare; PR #1 targets main separately | **OPEN: graph/patch integration analysis required** |
| **U01-T04** | Produce clean owner-reviewed merge/rebase/squash order, migration rehearsal dependencies and rollback | No merges authorized; current crosswalk is only an inventory | **BLOCKED ON T02/T03** |
| **U01-T05** | Execute integration after explicit owner approval, rerun all CI and prove original feature parity | No integration performed | **BLOCKED ON OWNER APPROVAL** |

## Source-level `ubos-local` preservation audit: required matrix

For each custom behavior, record source path+symbol+base commit, desired behavior/test, new path+symbol+head commit, equivalence verdict, and action/PR. Permitted verdicts:

- **PRESERVED** — feature exists and exact-parity tests pass.
- **REIMPLEMENTED** — feature exists differently, same required outcome proven by tests.
- **RETIRED_WITH_APPROVAL** — user expressly approved removing it and the reason is documented.
- **MISSING** — required behavior not implemented or known broken.
- **UNVERIFIED** — code search or test evidence insufficient.

All entries below currently **UNVERIFIED**, not “already migrated”:

| Scope | Questions to answer |
|---|---|
| App/package identity and signing | Are Android authorities, provider URIs, package names and signed release identity isolated correctly from ODK/Kobo and legacy apps? |
| Projects/server URL | Can deployment change only the unique URL/project setting, without hardcoding forms, geographic units or app variants? Is URL validation resilient against redirects and cleartext downgrade? |
| Mobile auth/revocation | Are App User credentials, assignment controls, logout/revocation and account changes compatible with the backend OpenRosa Basic flow? |
| Forms | Can ODK 2026 download latest published form XML, historical XForms, choices, instances and manifests correctly? Does JavaRosa 4.3.2 process them consistently? |
| Original/rejected records | Are original instance IDs, revision ancestry, rejected/corrected work and failure retries preserved without duplicate uploads or source overwrites? |
| Separate period visits | Can an independently assigned second visit be downloaded and finished without pretending it amended the original household submission? |
| Editing newer forms | Does an amendment always bind to a published target version and active source version? Unsupported fields must be left blank/re-entered until authorized. |
| Reference data | Is merged legacy Android #15's administration-unit refresh functionality actually present and secure in the upgraded app? Does refresh work offline and across version changes? |
| Native controls | Text/integer/decimal/date, select-one/multiple, groups/repeats, media, location, barcode, calculated values, `relevant`, `required` and `constraint` work with the authoritative XForm/JavaRosa evaluator? |
| Draft protection | Is persisted data AES-GCM-protected with correct source/work/actor/target binding, row and attachment ownership and crash/restart recovery? |
| Submission isolation | Is ordinary OpenRosa submission unable to bypass protected first-save, source authorization, field rules and final-work transaction? |
| Web/app identity separation | Can JWT Web Users never become mobile App Users by name/email collisions or wrong project role? |

## Backend integration graph

1. Root `main` has an independently proposed OpenRosa compatibility Draft PR **#1**.
2. Separate browser-fixture Draft PR **#2** also targets `main`.
3. Backend #3–#22 form a long stacked branch chain. Because #1 is not visibly on this chain, compare it for function/contract/test duplication and conflict before integration.
4. Recorded exact backend top SHA: `007fd511270b02846ebcb853ca7340a9c726151e`, with focused H2/PostgreSQL 15 and broad quality gates both **SUCCESS** (see crosswalk).
5. Production Flyway V1/V2 must not change; V3–V7 additions on the Draft chain need **real historical production-schema migration rehearsal** and rollback, not just disposable Testcontainers.

## Android integration graph

1. Legacy customization reference is `ubos-local`, with older user-requested changes; the new modernization root is `codex/odk-2026-forward-port` and must be tied to an **exact upstream ODK 2026 commit/tag**.
2. Modernization PR **#16** targets that forward-port root; #17–#37 are Draft dependent layers.
3. GitHub compare from `ubos-local` to latest modernization tip reports **5,803 ahead, 129 behind** and 300 paths in the API result. Those are **history divergence signals**, not a measured count of missing functions. The visible 300-file set can be truncated by compare API; do not claim it exhausts all patches.
4. A source-level old-vs-new customization ledger and effective manifest/resources comparison is mandatory. Include a reproducible build matrix and (at least) emulator checks. Physical device/Keystore certification remains owner-deferred for development but is a release gate.

## Planned integration order (proposal only — not merge authorization)

- First reconcile backend PR #1 compatibility contract versus #2–#22, then qualify one reviewed backend integration head with current OpenRosa and PostgreSQL tests.
- Review `ubos-local` file-level customizations vs the new ODK 2026 root before integrating Android #16–#37. Only after recorded parity verdicts, qualify the Android CI tip and emulator journeys using an isolated app ID/project.
- Validate cross-repo backend/Android compatibility against **one pinned pair of exact SHAs**, not moving branch names.
- Explicitly review security and source data invariants before any positive mapping grant or source-answer copy.
- Seek owner approval before actual merges, deployment, migration or signed APK. No "automatic next milestone" may waive this gate.

## Status changes forbidden until evidence exists

- `U01-T02` cannot be marked complete based on PR titles, commit counts or synthetic-only tests.
- `U01-T04` cannot be marked complete merely because branch histories look linear.
- `U02–U08` are not complete merely because code appears in one or more Draft PRs.
- `U09` stays production-blocked while default-admin P0, physical Android tests and real historical Flyway rehearsals remain.

**Next task to execute:** U01-T02 and U01-T03 source and branch integration audit. This work can proceed without production access or risky merges.
