# U01-T02 — Legacy Android Change-Set Migration Ledger

**Reviewed:** 20 November 2026  
**Source:** merged Android [PRs #1–#15](https://github.com/jeresaf/collect/pulls?q=is%3Apr+is%3Amerged), source ref `ubos-local`; ODK 2026 root `codex/odk-2026-forward-port`; modernization tip `codex/m4c2b3c3b3b-native-typed-value-proof`.  
**Status:** PARTIAL, evidence-based **source-location** audit. No assertion of functional parity without tests.

## Legacy PR inventory and migration disposition

| Merged PR | Original behavior / changed files | ODK 2026 evidence | U milestone / verdict |
|---|---|---|---|
| [#1](https://github.com/jeresaf/collect/pull/1) | Saving edited server entries as instances; `SaveFormToDisk.java` | That Java class remains but its blob differs; old `updateEntryStatus` hook is absent. New private draft code exists elsewhere. | **U04/U08 — UNVERIFIED parity** |
| [#2](https://github.com/jeresaf/collect/pull/2), [#3](https://github.com/jeresaf/collect/pull/3) | Form-filling and external entry URI behavior; `FormFillingActivity.java`, `FormUriActivity.kt`, `SaveFormToDisk.java` | Form filling persists via newer upstream class; old entry-specific integration must be mapped into new record-work flow. | **U04 — UNVERIFIED parity** |
| [#4](https://github.com/jeresaf/collect/pull/4) | Entries-with-issues count and submitted entries removal; `EntryProvider.java`, MainMenu UI/model | Legacy `EntryProvider.java` absent already at forward-port root and latest Draft; new read-only remote browser exists, but different workflow. | **U03/U04/U08 — UNVERIFIED parity** |
| [#5](https://github.com/jeresaf/collect/pull/5), [#6](https://github.com/jeresaf/collect/pull/6) | Entry completion/submitted status and issue-confirmation before edits; `EntryChooserList.java`, `SaveFormToDisk.java`, `InstanceUploader.java`, `Entry.java` | Legacy `EntryChooserList.java` absent in forward-port and latest tip; `RecordWorkBrowserActivity.kt` describes itself as read-only and does **not** directly open ODK form-filling or import historic answers. | **U03/U04/U08 — UNVERIFIED rejected/corrected workflow** |
| [#7–#11](https://github.com/jeresaf/collect/pulls?q=is%3Apr+is%3Amerged) | kxml2/XMLPullParser/R8 Gradle dependency corrections and subsequent reversion; #8–#10 show no changed paths in current PR file-list API | ODK 2026 uses different JavaRosa/parser dependencies. Need Gradle native-release compatibility acceptance, not blind dependency cherry-picks. | **U02/U09 — UNVERIFIED final build parity** |
| [#12](https://github.com/jeresaf/collect/pull/12) | Reuse current instance XML when retrying a failed submission; legacy `upload/InstanceServerUploader.java` and test | Old Java uploader absent; new `instancemanagement/send/OpenRosaServerInstanceUploader.kt` exists and includes `QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed`, but this is not a proof of retry/current-XML semantics. | **U08 — UNVERIFIED retry parity** |
| [#13](https://github.com/jeresaf/collect/pull/13), [#14](https://github.com/jeresaf/collect/pull/14) | Enforce constraint checks when adding repeat through Add button/swipe and `addRepeatFromCurrentScreen` | Old `FormEntryViewModel.addRepeatFromCurrentScreen` symbol is absent at the current `FormEntryViewModel.java` path; `FormFillingActivity.java` old call also absent. New native repeat probes exist. That does **not** prove current JavaRosa handling is wrong; equivalent event flow requires behavioral tests. | **U04/U09 — UNVERIFIED repeat-validation parity** |
| [#15](https://github.com/jeresaf/collect/pull/15) | Administration-unit download, manual/periodic refresh and access-revocation logout | Old admin-unit fetch/task/UI paths absent at ODK 2026 root and latest tip; **ODK now has Entity Lists**, but backend manifest is not currently emitting the marker needed to import form CSV as an Entity List (see [U05 seam](U01_T02_ODK_ENTITY_LIST_SEAM.md)). | **U05/U02 — VERIFIED legacy path loss; replacement incomplete/unverified** |

## Additional file-identity evidence

The following three old-vs-forward-port-vs-modern checks prove the divergence occurred at **the upstream forward-port**, not later in the 233-commit modernization chain:

- `activities/FormFillingActivity.java`: old `db930d06...`; forward-port **and latest Draft** same `908cb70a...`. Old custom `addRepeatFromCurrentScreen` usage no longer appears in the corresponding latest form-filling class.
- `tasks/SaveFormToDisk.java`: old `4fb95791...`; forward-port **and latest Draft** same `fa97a84d...`; old `updateEntryStatus` and `manageFilesAfterSavingEncryptedForm` not found under those method names.
- `formentry/FormEntryViewModel.java`: old `30c13887...`; forward-port **and latest Draft** same `22a59ed2...`; `addRepeatFromCurrentScreen` not present by name.
- `external/EntryProvider.java`, `activities/EntryChooserList.java`, and `upload/InstanceServerUploader.java`: present in old `ubos-local`, absent at identical paths at both ODK 2026 root and current modernization Draft tip.
- New `recordwork/RecordWorkBrowserActivity.kt` (U03/U04) provides server-scoped **read-only** source listings and work selection. New `recordwork/RecordWorkPreparationClient.kt` prepares versioned work while holding raw XML in memory only in tightly guarded workflows. This is architectural replacement work, **not equivalent acceptance** of the old entry status/issue lifecycle.

## Decisions / required tests

1. **Preserve** original rejection reason visibility, explicit operator confirmation before edit, source/revision identity, immutable original, correct complete/submitted statuses and failure-retry/current-XML behavior, adapted to newer work-item data structures.
2. Under U04, repeat/add-button/swipe tests must prove constraint, relevance, required choice and repeat-row identity behavior using **current JavaRosa native APIs**, not historical method names.
3. Under U05, use existing ODK 2026 Entity Lists **when standard form-manifest semantics fit**. Preserve project-scoped **generic reference data** that may not be a form Entity List (e.g., independent administration-unit refresh, dropdown hierarchies), with explicit offline version/cache and mobile authorization.
4. Under U08, test retries after server response loss, exact XML/media bytes, duplicate-ack handling, corrected records, revoked account, offline crash and user/project switch. The presence of `QuarantinedUploadBoundary` alone is not sufficient.
5. Do **not** retire an old workflow implicitly because its Java class was deleted by upstream. Verdicts become `REIMPLEMENTED` only after test evidence; `RETIRED_WITH_APPROVAL` requires user approval.

**Limitations:** This matrix inventories merged PRs #1–#15 and compares selected symbols/files. It is not a full old-`ubos-local` Git patch search, not a generated binary diff, and does not certify Android device behavior. No release, source-answer transfer, branch merge or DB mutation is authorized.
