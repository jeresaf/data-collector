# ODK 2026 Modernization — Pull Request Crosswalk

**Snapshot:** 20 November 2026  
**Authority:** [Rebaselined U01–U09 roadmap](ROADMAP_2026_REBASELINE.md) and historical GitHub PR metadata.  
**This is a traceability inventory, NOT a claim of code merge, feature acceptance or release readiness.** Existing legacy PR names are intentionally preserved. This count excludes the standalone documentation-only roadmap [Draft PR #23](https://github.com/jeresaf/data-collector/pull/23), which was opened during this rebaseline; thus the backend can show 23 open Draft PRs when #23 itself is counted.

## Current backlog accounting

- **Backend `jeresaf/data-collector`:** 22 open Draft PRs, #1–#22.
- **Android `jeresaf/collect`:** 22 open Draft modernization PRs, #16–#37.
- **Total:** 44 open Draft modernization PRs. No PR in these two groups was merged as of the snapshot.
- Android #1–#15 are **older merged legacy PRs**, not part of the open modernization stack. In particular, Android [#15](https://github.com/jeresaf/collect/pull/15) ("Add admin unit refresh and access revocation logout") is relevant to U05, but **post-forward-port parity must be revalidated**.
- All dates/statuses here are a point-in-time snapshot. Re-check GitHub before any branch rebase/merge.

### Backend PRs — original legacy labels → new stable U milestone

| Legacy PR | New milestone | Existing source branch | Existing base branch | Status |
|---|---|---|---|---|
| [#1](https://github.com/jeresaf/data-collector/pull/1) | U02 | `codex/odk-2026-openrosa-qualification` | `main` | Draft (not merged) |
| [#2](https://github.com/jeresaf/data-collector/pull/2) | U01 | `codex/r0-playwright-fixture-reconciliation` | `main` | Draft (not merged) |
| [#3](https://github.com/jeresaf/data-collector/pull/3) | U02 | `codex/m2-odk2026-integration-gate` | `codex/r0-playwright-fixture-reconciliation` | Draft (not merged) |
| [#4](https://github.com/jeresaf/data-collector/pull/4) | U03 | `codex/m3-longitudinal-workflows` | `codex/m2-odk2026-integration-gate` | Draft (not merged) |
| [#5](https://github.com/jeresaf/data-collector/pull/5) | U03 | `codex/m3b-target-version-catalog` | `codex/m3-longitudinal-workflows` | Draft (not merged) |
| [#6](https://github.com/jeresaf/data-collector/pull/6) | U03 | `codex/m3c-source-schema-prepare` | `codex/m3b-target-version-catalog` | Draft (not merged) |
| [#7](https://github.com/jeresaf/data-collector/pull/7) | U06 | `codex/m3d4a-typed-mapping-authority-deny` | `codex/m3c-source-schema-prepare` | Draft (not merged) |
| [#8](https://github.com/jeresaf/data-collector/pull/8) | U06 | `codex/m3d4b1-independent-reviewer-eligibility` | `codex/m3d4a-typed-mapping-authority-deny` | Draft (not merged) |
| [#9](https://github.com/jeresaf/data-collector/pull/9) | U06 | `codex/m3d4b2a-reviewer-recommendation-ledger` | `codex/m3d4b1-independent-reviewer-eligibility` | Draft (not merged) |
| [#10](https://github.com/jeresaf/data-collector/pull/10) | U06 | `codex/m3d4b2b1-independent-final-denials` | `codex/m3d4b2a-reviewer-recommendation-ledger` | Draft (not merged) |
| [#11](https://github.com/jeresaf/data-collector/pull/11) | U06 | `codex/m3d4b2b2a-static-field-policy-gate` | `codex/m3d4b2b1-independent-final-denials` | Draft (not merged) |
| [#12](https://github.com/jeresaf/data-collector/pull/12) | U06 | `codex/m3d4b2b2b-native-schema-proof` | `codex/m3d4b2b2a-static-field-policy-gate` | Draft (not merged) |
| [#13](https://github.com/jeresaf/data-collector/pull/13) | U07 | `codex/m3d4c1-signed-grant-verification-contract` | `codex/m3d4b2b2b-native-schema-proof` | Draft (not merged) |
| [#14](https://github.com/jeresaf/data-collector/pull/14) | U07 | `codex/m3d4c2a-inactive-trust-revocation-registry` | `codex/m3d4c1-signed-grant-verification-contract` | Draft (not merged) |
| [#15](https://github.com/jeresaf/data-collector/pull/15) | U07 | `codex/m3d4c2b1-revocation-writer` | `codex/m3d4c2a-inactive-trust-revocation-registry` | Draft (not merged) |
| [#16](https://github.com/jeresaf/data-collector/pull/16) | U07 | `codex/m3d4c2b2a-public-key-enrollment-review` | `codex/m3d4c2b1-revocation-writer` | Draft (not merged) |
| [#17](https://github.com/jeresaf/data-collector/pull/17) | U07 | `codex/m3d4c2b2b1-offline-ceremony-commitments` | `codex/m3d4c2b2a-public-key-enrollment-review` | Draft (not merged) |
| [#18](https://github.com/jeresaf/data-collector/pull/18) | U07 | `codex/m3d4c2b2b2-inactive-external-attestation-verifier` | `codex/m3d4c2b2b1-offline-ceremony-commitments` | Draft (not merged) |
| [#19](https://github.com/jeresaf/data-collector/pull/19) | U07 | `codex/m3d4c2b2b2b-checkpoint-inclusion-proof` | `codex/m3d4c2b2b2-inactive-external-attestation-verifier` | Draft (not merged) |
| [#20](https://github.com/jeresaf/data-collector/pull/20) | U07 | `codex/m3d4c2b2b2c1-log-consistency-proof` | `codex/m3d4c2b2b2b-checkpoint-inclusion-proof` | Draft (not merged) |
| [#21](https://github.com/jeresaf/data-collector/pull/21) | U07 | `codex/m3d4c2b2b2c2-dual-witness-checkpoint` | `codex/m3d4c2b2b2c1-log-consistency-proof` | Draft (not merged) |
| [#22](https://github.com/jeresaf/data-collector/pull/22) | U07 | `codex/m3d4c2b2b2c3-restricted-witness-retrieval` | `codex/m3d4c2b2b2c2-dual-witness-checkpoint` | Draft (not merged) |

**Backend topology:** #1 (OpenRosa compatibility) targets `main` independently, #2 (R0 browser fixture reconciliation) also targets `main`, and #3–#22 follow the successive #2–#21 Draft branch chain. The backend current tip is `codex/m3d4c2b2b2c3-restricted-witness-retrieval`; GitHub compare against `main` reported **159 ahead, 0 behind**, with 94 changed files visible in the compare. The branch is **NOT merged**, and #1's independent changes need explicit comparison/integration.

**Initial U01-T03 overlap result:** PR #1 and latest Draft tip have **identical** `OpenRosaBasicAuthIntegrationTest.java` and `ODK_COLLECT_2026_COMPATIBILITY_CONTRACT.md` Git blob SHAs, including all five added `odk2026*` HTTP tests. The tip's qualification workflow differs from PR #1 only by an extra branch trigger. **Do not mechanically merge/apply PR #1 a second time**; retain provenance and review commit ancestry/patch IDs before resolving this parallel Draft. Full note in [U01 integration qualification](U01_INTEGRATION_QUALIFICATION.md).

**Backend #22 exact-head qualification evidence:** SHA `007fd511270b02846ebcb853ca7340a9c726151e`; focused [H2 + PostgreSQL 15 workflow #44764143723](https://github.com/jeresaf/data-collector/actions/runs/44764143723) **SUCCESS**, broad [CI Quality Gate #44764142857](https://github.com/jeresaf/data-collector/actions/runs/44764142857) **SUCCESS**. This proves tests on that Draft tip only; no source-data transfer or real external witness fetch was enabled.

### Android PRs — original legacy labels → new stable U milestone

| Legacy PR | New milestone | Existing source branch | Existing base branch | Status |
|---|---|---|---|---|
| [#16](https://github.com/jeresaf/collect/pull/16) | U02 | `codex/odk-2026-url-bootstrap` | `codex/odk-2026-forward-port` | Draft (not merged) |
| [#17](https://github.com/jeresaf/collect/pull/17) | U02 | `codex/odk-2026-app-identity` | `codex/odk-2026-url-bootstrap` | Draft (not merged) |
| [#18](https://github.com/jeresaf/collect/pull/18) | U03 / U04 | `codex/m4-record-work-contract` | `codex/odk-2026-app-identity` | Draft (not merged) |
| [#19](https://github.com/jeresaf/collect/pull/19) | U03 / U04 | `codex/m4b-remote-record-browser` | `codex/m4-record-work-contract` | Draft (not merged) |
| [#20](https://github.com/jeresaf/collect/pull/20) | U03 / U04 | `codex/m4b2-record-selection-prepare` | `codex/m4b-remote-record-browser` | Draft (not merged) |
| [#21](https://github.com/jeresaf/collect/pull/21) | U06 | `codex/m4c1-cross-version-assessment` | `codex/m4b2-record-selection-prepare` | Draft (not merged) |
| [#22](https://github.com/jeresaf/collect/pull/22) | U06 | `codex/m4c2-historical-schema-review` | `codex/m4c1-cross-version-assessment` | Draft (not merged) |
| [#23](https://github.com/jeresaf/collect/pull/23) | U06 | `codex/m4c2b-javarosa-staging-gates` | `codex/m4c2-historical-schema-review` | Draft (not merged) |
| [#24](https://github.com/jeresaf/collect/pull/24) | U06 | `codex/m4c2b2-native-scalar-transfer` | `codex/m4c2b-javarosa-staging-gates` | Draft (not merged) |
| [#25](https://github.com/jeresaf/collect/pull/25) | U04 | `codex/m4c2b3-quarantined-work-drafts` | `codex/m4c2b2-native-scalar-transfer` | Draft (not merged) |
| [#26](https://github.com/jeresaf/collect/pull/26) | U04 | `codex/m4c2b3b-native-private-text-editor` | `codex/m4c2b3-quarantined-work-drafts` | Draft (not merged) |
| [#27](https://github.com/jeresaf/collect/pull/27) | U04 | `codex/m4c2b3c1-native-typed-fields` | `codex/m4c2b3b-native-private-text-editor` | Draft (not merged) |
| [#28](https://github.com/jeresaf/collect/pull/28) | U04 | `codex/m4c2b3c2-static-native-choices` | `codex/m4c2b3c1-native-typed-fields` | Draft (not merged) |
| [#29](https://github.com/jeresaf/collect/pull/29) | U04 | `codex/m4c2b3c2b1-private-image-intake` | `codex/m4c2b3c2-static-native-choices` | Draft (not merged) |
| [#30](https://github.com/jeresaf/collect/pull/30) | U04 | `codex/m4c2b3c2b2a-repeat-row-identity` | `codex/m4c2b3c2b1-private-image-intake` | Draft (not merged) |
| [#31](https://github.com/jeresaf/collect/pull/31) | U04 | `codex/m4c2b3c2b2b-repeat-answer-drafts` | `codex/m4c2b3c2b2a-repeat-row-identity` | Draft (not merged) |
| [#32](https://github.com/jeresaf/collect/pull/32) | U04 | `codex/m4c2b3c2b2c-repeat-media-ownership` | `codex/m4c2b3c2b2b-repeat-answer-drafts` | Draft (not merged) |
| [#33](https://github.com/jeresaf/collect/pull/33) | U06 | `codex/m4c2b3c3a-durable-approved-text-transfer` | `codex/m4c2b3c2b2c-repeat-media-ownership` | Draft (not merged) |
| [#34](https://github.com/jeresaf/collect/pull/34) | U06 | `codex/m4c2b3c3b-typed-mapping-contract` | `codex/m4c2b3c3a-durable-approved-text-transfer` | Draft (not merged) |
| [#35](https://github.com/jeresaf/collect/pull/35) | U06 | `codex/m4c2b3c3b2-typed-review-evidence` | `codex/m4c2b3c3b-typed-mapping-contract` | Draft (not merged) |
| [#36](https://github.com/jeresaf/collect/pull/36) | U06 | `codex/m4c2b3c3b3a-transfer-preflight` | `codex/m4c2b3c3b2-typed-review-evidence` | Draft (not merged) |
| [#37](https://github.com/jeresaf/collect/pull/37) | U06 | `codex/m4c2b3c3b3b-native-typed-value-proof` | `codex/m4c2b3c3b3a-transfer-preflight` | Draft (not merged) |

**Android topology:** #16's base `codex/odk-2026-forward-port` is an important upstream modernization root that itself is **not shown as a PR in the #16–#37 set**. #17–#37 form an extensive stacked Draft chain. The current tip is `codex/m4c2b3c3b3b-native-typed-value-proof`; compare against legacy `ubos-local` reported **5,803 ahead and 129 behind**. GitHub's compare returned 300 changed paths (API view may truncate at 300); the large ahead/behind comes from upstream ODK history plus custom work, and is **NOT** proof that precisely 129 custom features are missing.

**Initial U01-T02 source evidence:** Compared original `ubos-local` with latest Android Draft tip at old admin-unit refresh paths. `OpenRosaUserFetcher.java`, `OpenRosaLoginSource.java`, `AdminUnitsTask.java`, and `AdminUnitDetails.java` exist in old source but are absent at those paths in the new tip. `ProjectSettingsDialog.kt`, `MainMenuActivity.kt` and the project-settings layout no longer contain the old refresh/periodic-admin-unit handlers or refresh button. This is a **verified previous-code-path gap**, **not definitive proof** that the feature was not replaced elsewhere. See [U01 evidence](U01_INTEGRATION_QUALIFICATION.md).

**Mandatory next audit:** Compare the actual `ubos-local` patch family and resource/assets/security/intent manifests against modernized source at file/symbol/test level. For each old customization record **PRESERVED / REIMPLEMENTED / RETIRED_WITH_APPROVAL / MISSING / UNVERIFIED**, with code and acceptance evidence. Also establish the authoritative ODK 2026 upstream commit/tag pinned by the root branch, app package/provider identity, and how ODK upstream updates are replayed safely.

## Functional acceptance state by modernization milestone

| Milestone | Source progress | Integration/acceptance truth |
|---|---|---|
| **U01** | Inventory and comparisons recorded in this document | **IN PROGRESS**; customization delta, branch conflict review and owner-approved integration still missing |
| **U02** | Android #16–#17; backend #1–#3 Draft | **UNINTEGRATED**; ODK 2026 and generic URL build/actual-server integration not yet accepted |
| **U03** | Backend #4–#6, Android #18–#20 Draft | **UNINTEGRATED**; versioned amendment/FOLLOW_UP E2E not yet accepted |
| **U04** | Android #18–#20 and #25–#32 Draft | **UNINTEGRATED**; secure native edit/restart/repeat/media full acceptance not yet completed |
| **U05** | Legacy Android #15 merged before forward-port; original M18 reference data | **RECONCILIATION GAP**: original seven refresh-related classes/handlers/layout locations are absent or changed at expected paths in 2026 Draft tip; equivalent replacement not yet proven |
| **U06** | Backend #7–#12, Android #21–#24 and #33–#37 Draft | **PARTIAL / FAIL-CLOSED**; no general authorized positive cross-version transfer |
| **U07** | Backend #13–#22 Draft | **CI-QUALIFIED INERT CONTRACTS ONLY**; no production independent trust root, actual signed log publication, trusted-key activation or positive grant |
| **U08** | Original OpenRosa submission exists; new protected work integration remains unapproved | **NOT END-TO-END ACCEPTED** |
| **U09** | Earlier automated CI exists; original M21 physical tests deferred | **BLOCKED FOR PRODUCTION** until owner acceptance, historical migrations, security and actual devices |

## U01-T02 customization reconciliation worklist (not yet certified)

Review each against actual `ubos-local` and ODK 2026 source and tests, rather than inferring from PR titles:

| Feature area | Source of expected behavior | Current status |
|---|---|---|
| App identity, provider authorities, content URI isolation and custom signing | Legacy fork + Android #16/#17 | **UNVERIFIED parity** |
| Configurable URL/project and OpenRosa formList/manifest/media auth | Legacy settings + Android #16; backend #1–#3 | **UNVERIFIED parity** |
| Existing record browser, source ID and safely reopening older records | Legacy server-entry UI + Android #18–#20 | **UNVERIFIED parity** |
| Amendment across published XForm versions and original preservation | Backend #4–#6; Android #21–#37 | **UNVERIFIED E2E** |
| Separate second-visit FOLLOW_UP and periodic new work downloads | Backend #4–#6; Android #18–#20 | **UNVERIFIED E2E** |
| Admin-unit and generic entity reference data refresh | Legacy Android merged #15; original platform M18 | **UNVERIFIED 2026 forward-port** |
| JavaRosa native answers, choice filtering, repeats and attachments | Android #23–#37 | **UNVERIFIED integrated behavior** |
| Encrypted drafts, attachments, repeat row identity and crash restore | Android #25–#32 | **UNVERIFIED integrated behavior** |
| Rejected/corrected entry and retries/duplicate acknowledgments | Legacy fork; backend M10–M12 | **UNVERIFIED 2026 parity** |
| Authentication, mobile revocation, offline storage and cross-account isolation | Backend + legacy/mobile fork | **UNVERIFIED integrated security** |
| Positive field mapping authorization, consent and safe uploading | Backend #7–#22; Android #33–#37 | **NOT ENABLED / intentionally DENY** |

## Cross-repo integration rules

1. Do not merge 22 backend and 22 Android Drafts blindly, rebase by force, or rewrite prior audit/source proof to fit a roadmap.
2. Review backend #1 independent compatibility branch alongside #2–#22 linear stack; ensure the ODK 2026 form-download and token contracts have not diverged.
3. Audit Android forward-port root and `ubos-local` via file/symbol-level feature inventory; ODK upstream commits do not count as custom feature restorations unless tested.
4. Maintain per-repo **owner-reviewed integration plan** with exact base/head SHAs, expected changed files, conflict resolutions, CI build and rollback before any actual merge.
5. Keep **G04 trust** blocked for positive source-answer transfer and **G05 devices** deferred for development only, never claimed passed for release.
6. Future PRs use `Uxx` and `Uxx-Tnn` naming. Legacy PR branches/titles remain historical aliases; CI evidence stays attached to the original exact head.

**Immediate next executable action:** `U01-T02`, inspect and document the `ubos-local` versus ODK 2026 custom patch gap, then `U01-T03` integration conflict/CI audit; no production merges before explicit owner authorization.
