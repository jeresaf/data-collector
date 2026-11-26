# U01-T02 — Android `ubos-local` → ODK 2026 Source-Parity Audit (initial verified slice)

**Snapshot:** 2026-11-20  
**New milestone:** U01 — Reconcile & Integrate Baselines.  
**Status:** **PARTIAL SOURCE ANALYSIS**, not functional/device acceptance.  
**Exact refs inspected:** Android `ubos-local`, root `codex/odk-2026-forward-port`, latest Draft tip `codex/m4c2b3c3b3b-native-typed-value-proof`. Where shown, blob hashes are actual GitHub contents hashes. No Android branch was changed.

## Confirmed code-presence and code-path findings

| Feature | Old `ubos-local` | Root ODK 2026 forward-port | Latest Draft tip | Verdict / action |
|---|---|---|---|---|
| Legacy admin-unit XML downloader | `openrosa/OpenRosaUserFetcher.java` (`getAdminUnitXML`, `fetchAdminUnits`, `httpInterface.fetchUnits`) present; blob `c7763a7c...` | Not at original path | Not at original path | **UNVERIFIED equivalent.** Old downloader path disappeared at forward-port root; audit any modern replacement. |
| Admin-unit-authenticated source | `openrosa/OpenRosaLoginSource.java` (`fetchAdminUnits`, configurable admin-unit API path) present; blob `bfa46638...` | Not at original path | Not at original path | **UNVERIFIED equivalent.** Cannot assume backward-compatible endpoint or token handling. |
| Asynchronous admin-unit refresh | `tasks/AdminUnitsTask.java` present, fetcher callback; blob `b9f7c48c...` | Not at original path | Not at original path | **UNVERIFIED equivalent.** New WorkManager/Repository service or another integration must be traced and tested. |
| Legacy reference-data DTO | `login/AdminUnitDetails.java` present; district/sub_county/village/parish fields and access status; blob `81b61a10...` | New exact path was not checked | Missing at original path on latest tip | **UNVERIFIED equivalent.** This type alone is not proof of service continuity. |
| Manual project refresh/revocation UI | `projects/ProjectSettingsDialog.kt` `refreshAdminUnitsButton`, `AdminUnitsTask`, `adminUnitsComplete`, revoked-access dialog; blob `70e53bf6...` | Same modern file blob `94ba331d...`, **no** old refresh/revocation handler | Exactly same blob `94ba331d...` | **Previous UI location lost in forward-port**, no later Draft reintroduced it there; replacement elsewhere **UNVERIFIED**. |
| Periodic main-menu refresh | `mainmenu/MainMenuActivity.kt` `adminUnitsCheck`, `refreshAdminUnits`; blob `34b62ef5...` | Same modern blob `2c3a0daf...`, no old scheduler/callback | Exactly same blob `2c3a0daf...` | **Previous scheduler location lost in forward-port**; alternate implementation **UNVERIFIED**. |
| Refresh button layout | `res/layout/project_settings_dialog_layout.xml` has `refresh_admin_units_button`; blob `f3178529...` | Modern blob `b366416d...`, no corresponding button | Same modern blob `b366416d...` | **Visible legacy control absent** in same screen at ODK root and Draft tip. |
| Configurable HTTPS project bootstrap | Old first-launch activity existed (blob `64e505db...`) | New `PreconfiguredProjectInitializer.kt` from Android Draft #16 | New file exists (blob `96e3439e...`): on fresh install, creates a project from supplied server URL when no project exists; does not provision credentials or overwrite projects | **CODE EXISTS**, not yet verified against real server or device. |
| One stable generic app package | Legacy `AndroidManifest.xml` different; blob `c5b3ac13...` | Isolated package/provider changes from Draft #17 | New `collect_app/build.gradle` sets default `org.jeresaf.datacollector.android`, accepts `COLLECT_SERVER_URL` over HTTPS and optional distinct app ID; modern manifest blob `f7ea182e...` | **CODE EXISTS**, signing/installation/provider isolation needs test and release acceptance. |
| Amendments vs independent visits | Old location of new `recordwork/RecordWorkContracts.kt` absent | Later modernization code | New file exists (blob `88227539...`) with explicit `RecordWorkType { AMENDMENT, FOLLOW_UP }`, visit key and protected project-scoped URLs | **CODE EXISTS**; actual existing-record read/edit and second-visit E2E still **UNVERIFIED**. |
| Typed historic-answer work | Not at new Draft code paths | Developed in newer stack | `recordwork/ApprovedHistoricalTextImporter.kt` (blob `697bcb2d...`) requires approved work, matching source/target fingerprints; `NativeTypedValueProofProbe.kt` (blob `6485ade1...`) explicitly says validation is **not authorization** | **INERT/CONTROLLED CODE EXISTS.** Must not infer that server grants or positive transfer are integrated. |

### Why the reference-data loss is attributed to the forward-port

The three legacy UI/resource files `ProjectSettingsDialog.kt`, `MainMenuActivity.kt` and `project_settings_dialog_layout.xml` have **identical blob SHA values** between the ODK 2026 forward-port root and the latest Draft tip. None contains the prior admin-unit refresh callback/scheduler/button identifier. The three examined old downloader/task classes are absent at their legacy paths already in the forward-port root and remain absent on the latest tip.

Therefore the **original implementation paths ceased to exist at the initial ODK 2026 forward-port**, not as a regression introduced by later Draft PRs #16–#37. **A repository-wide modern service search is still necessary before concluding that reference-data refresh itself has no replacement.**

Old merged Android [PR #15](https://github.com/jeresaf/collect/pull/15) changed `OpenRosaUserFetcher.java`, `OpenRosaLoginSource.java`, `ProjectSettingsDialog.kt`, `MainMenuActivity.kt`, and other files to support reference-data refresh and access revocation. The owner did **not** approve retiring those capabilities. They are migration requirements.

## Additional PR-scope audit: Android Drafts #16–#37

All **22 individual Draft PR changed-filename lists** were inspected via the GitHub PR API (180 changed-file occurrences, counting files again when changed in multiple stacked PRs). None of those changed filenames explicitly identifies an admin-unit refresh module, entity-list refresh service, or replacement reference-data downloader. PR #25 does touch `OpenRosaServerInstanceUploader.kt`, but that concerns guarded submission rather than a proven replacement for administration-unit download.

**Interpretation:** The 2026 modernization Draft stack does not visibly introduce a dedicated reference-data refresh file/feature by filename. It cannot establish the absence of a replacement in the **ODK upstream forward-port** or in an existing general-purpose entity/dataset client. A repository-wide source/symbol search and functional run are still necessary. This raises the **priority of U05**, but it is not yet a `MISSING` verdict for all possible implementations.

## Native ODK Entities architecture and backend protocol qualification

The latest Android source **does** contain a native ODK Entity List store, download and refresh path. This narrows the initial "missing reference-data" question: it is **not** correct to say ODK 2026 has no general entity synchronization at all.

- ODK parses the `<mediaFile type="entityList">` and `type="approvalEntityList"` **attributes** in `Kxml2OpenRosaResponseParser.kt`.
- `ServerFormUseCases.kt` checks `mediaFile.type != null`, then hashes/downloads/imports the list through `LocalEntityUseCases` and `EntitiesRepository`. `FormsDataService.downloadUpdates` and `matchFormsWithServer` provide scheduled/form-triggered refresh mechanisms.
- The Data Collector backend `OpenRosaController.appendManifestFile` currently writes plain `<mediaFile>` nodes **without the Entity List type attribute** for uploaded form external datasets or media. Those files are recognized as ordinary media, not sent through native ODK Entity List ingestion.
- The backend also has web/admin reference-list CSV and form external-dataset services, but **no demonstrated independent mobile Basic-auth administration-unit refresh equivalent** to the old `ubos-local` path.

**Verified result:** **the current backend and native Entities importer have a protocol mismatch**, even though both possess useful constituent pieces. See the full [U05-T01 integration seam and safe proposal](U01_T02_ODK_ENTITY_LIST_SEAM.md). **Do not mark U05 fully missing or fully implemented**; ordinary CSV media may work, while typed Entity List ingestion requires a validated metadata contract.

Also audited all merged legacy Android PRs #1–#15; prior entry/issue lifecycle, retry-current-XML and repeat-constraint changes were introduced before the ODK forward-port and need independent 2026 behavioral proof. See [merged-change source ledger](U01_T02_LEGACY_ANDROID_CHANGESET_LEDGER.md).

## Required U01-T02 follow-up verification

1. Search the full forward-port and latest tip for modern entity-list download, background sync, project-role revocation, offline dataset cache and equivalent event paths. A missing *old filename* does not prove no modern implementation.
2. Trace the **server** dataset endpoint(s) and their permissions. The old `/api/v1/adminunits` is specific to the old app, and a generic application needs a project-scoped, form-independent, versioned reference-data contract.
3. Record replacement source symbols/tests. If absent, mark U05 `MISSING` and open **U05-T01**, restoring capability via modern ODK 2026 primitives rather than copying obsolete AsyncTask or login classes.
4. Add synthetic/emulator tests for login/revocation/refresh, offline cached access, cross-project isolation, updating value lists and repeat visit downloads without accidentally writing amendments.
5. Preserve original custom capabilities unless owner explicitly authorizes retirement. Do not bind U05 to field mapping grants for historical source answers; periodic reference updates are a separate data product.

## Important unresolved audit categories

This is only an initial source evidence slice, not all original `ubos-local` customizations. The [U01 checklist](U01_INTEGRATION_QUALIFICATION.md) retains open trace work for rejection/correction, other account roles, login failure modes, submission retries, network headers, external content-provider authorities, old/new choice/repeat/media behavior, encryption/crash recovery and manifest/permission compatibility. No physical client certification has occurred.

**No Android source, branch, APK, signing key, data, production service or deployment was changed to produce this report.**
