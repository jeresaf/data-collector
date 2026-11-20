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

## U01-T02 initial source-level finding: legacy administration-unit refresh

**Evidence checked 20 November 2026:** file contents (or verified absence at the exact path) in `jeresaf/collect` refs `ubos-local` and `codex/m4c2b3c3b3b-native-typed-value-proof`. This is a source-location comparison, not a full-repository symbol index or functional E2E proof.

| Legacy file / behavior | `ubos-local` evidence | Latest Draft tip evidence | Verdict |
|---|---|---|---|
| `openrosa/OpenRosaUserFetcher.java` | Exists; `getAdminUnitXML`, `fetchAdminUnits`, `httpInterface.fetchUnits` | Exact path not found | **UNVERIFIED replacement / missing legacy path** |
| `openrosa/OpenRosaLoginSource.java` | Exists; `fetchAdminUnits`, `/api/v1/adminunits` URL builder | Exact path not found | **UNVERIFIED replacement / missing legacy path** |
| `tasks/AdminUnitsTask.java` | Exists; asynchronous fetch and listener callback | Exact path not found | **UNVERIFIED replacement / missing legacy path** |
| `login/AdminUnitDetails.java` | Exists; district/sub-county/village/parish/message structure | Exact path not found | **UNVERIFIED replacement / missing legacy path** |
| `projects/ProjectSettingsDialog.kt` | Includes `refreshAdminUnitsButton`, `AdminUnitsTask`, `adminUnitsComplete`, access-revocation handling | File exists, different blob; no matching admin-unit/refresh/revocation handlers in file | **PARITY GAP AT PREVIOUS UI LOCATION** |
| `mainmenu/MainMenuActivity.kt` | Includes periodic `adminUnitsCheck` and `refreshAdminUnits` | File exists, different blob; no matching admin-unit scheduler or callback in file | **PARITY GAP AT PREVIOUS UI LOCATION** |
| `res/layout/project_settings_dialog_layout.xml` | Contains `refresh_admin_units_button` | File exists, no matching refresh control identifier | **PARITY GAP AT PREVIOUS UI LOCATION** |

These behaviors were introduced/modified in **merged legacy Android PR #15**; the 2026 forward-port does **not automatically inherit** these original file paths. The evidence is sufficient to **block claiming U05 reference-refresh parity**, but is NOT sufficient to assert that no equivalent modern module exists elsewhere.

**U01-T02 next investigation:** inspect the new 2026 identity/login, project settings, dataset fetch and profile-refresh flow; determine whether an alternative service replaces these old APIs. If none does, record a **MISSING** outcome and implement a *generic*, form-independent versioned reference-data refresh under **U05-T01**, with actor-scoped auth, server-controlled URL and offline cache/revocation tests. Do not paste old Java classes into new ODK without reviewing API/auth compatibility.

## Backend integration graph

1. Root `main` has an independently proposed OpenRosa compatibility Draft PR **#1**.
2. Separate browser-fixture Draft PR **#2** also targets `main`.
3. Backend #3–#22 form a long stacked branch chain. Because #1 is not visibly on this chain, compare it for function/contract/test duplication and conflict before integration.
4. Recorded exact backend top SHA: `007fd511270b02846ebcb853ca7340a9c726151e`, with focused H2/PostgreSQL 15 and broad quality gates both **SUCCESS** (see crosswalk).
5. Production Flyway V1/V2 must not change; V3–V7 additions on the Draft chain need **real historical production-schema migration rehearsal** and rollback, not just disposable Testcontainers.

## U01-T03 initial backend PR #1 overlap audit

**Evidence checked 20 November 2026:** compared actual GitHub blob SHA/content at backend PR #1 branch `codex/odk-2026-openrosa-qualification`, current stacked Draft tip `codex/m3d4c2b2b2c3-restricted-witness-retrieval`, and `main`.

- `backend/src/test/java/org/odk/platform/security/OpenRosaBasicAuthIntegrationTest.java` has **the identical blob SHA `c7fccfbeacbb3a67e374ae4ca382d9dce5d72fd2`** in PR #1 and latest Draft tip. All five new `odk2026*` test methods introduced by PR #1 are present in the tip (and are absent in the older `main` blob `24b4ce41...`).
- `docs/ODK_COLLECT_2026_COMPATIBILITY_CONTRACT.md` is also **byte-identical** (blob SHA `3d3e4275cfe52b934e7e40f83c8328da319d372a`) in PR #1 and the latest Draft tip.
- `.github/workflows/odk-2026-contract-qualification.yml` differs only by an **additional `codex/m2-odk2026-integration-gate` push trigger** in the tip; the test command and other workflow steps are unchanged.

**Finding:** PR #1 is separate in PR topology but its functional source/tests/doc payload has already been reproduced in the stacked tip. This is a **duplicate-change integration hazard**, not proof that the entire PR stack is ready to merge. U01-T03 should mark these three overlapping files as **already represented in candidate integration tip**, compare commit ancestry/patch IDs, and avoid reapplying PR #1 mechanically. The owner must still review how to retain PR #1 provenance and closure before any merge/close/rebase action.

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
