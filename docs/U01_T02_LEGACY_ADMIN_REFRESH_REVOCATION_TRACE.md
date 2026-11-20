# U01-T02 — Legacy Administration-Unit Refresh and Revocation: Source-Level Behavior Trace

**Audit date:** 2026-11-20  
**Status:** Source evidence verified at named files; whole-repository alternative replacement and runtime parity **NOT VERIFIED**.  
**Reviewed branches:** `jeresaf/collect:ubos-local` and `jeresaf/collect:codex/m4c2b3c3b3b-native-typed-value-proof`.  
**Purpose:** Preserve exact owner-observable behavior while migrating to ODK Collect 2026, without reintroducing obsolete implementation APIs.

## Legacy original behavior — directly inspected

### Manual project-settings refresh

Source: `collect_app/src/main/java/org/odk/collect/android/projects/ProjectSettingsDialog.kt` on `ubos-local`, blob `70e53bf6940b19d108d503159f5d5e86b405c8dc`.

- Lines ~87–88 wire `refreshAdminUnitsButton` to `refreshAdminUnits()`.
- Lines ~114–127 cancel an existing `AdminUnitsTask`, fetch the username from `ProjectKeys.KEY_METADATA_PHONENUMBER`, configure `/api/v1/adminunits` and start the task.
- Lines ~130–156 handle `UserNotAllowedAccess` or literal response `User not allowed access to system` by prompting project deletion; on success store `KEY_DISTRICT`, `KEY_SUB_COUNTY`, `KEY_PARISH`, `KEY_VILLAGE` into **unprotected settings**, or show a mapped error.
- Lines ~167–175 detach and cancel the task on view destruction.

### Periodic main-menu refresh and access revocation

Source: `collect_app/src/main/java/org/odk/collect/android/mainmenu/MainMenuActivity.kt` on `ubos-local`, blob `34b62ef5c06dad8159d8d0d8aa8e91eba15f5323`.

- Lines ~90–97 register a Handler-backed `adminUnitsCheck` runnable; its body calls `refreshAdminUnits()` and posts another delayed check.
- Lines ~180–185 schedule/remove the callback on activity lifecycle hooks.
- Lines ~378–391 guard against concurrent task/no current project/blank username and use `/api/v1/adminunits`.
- Lines ~394–419 prompt once for revocation or persist assigned district/sub-county/parish/village and refresh the visible metadata.
- Lines ~423–457 `deleteProject()` delegates to `projectDeleter.deleteCurrentProject()`; deletion may be blocked for unsent instances or running jobs, and redirects to another project or first launch on success.
- Constant `ADMIN_UNITS_CHECK_INTERVAL_MS = 5 * 60 * 1000L` (approximately every five minutes **while this activity's callback is active**). It is **not** evidence of an OS-level guaranteed background schedule.

### Modern source at old locations

- `ProjectSettingsDialog.kt`, blob `94ba331d2bca78f97ddd9d45f0dd4e62f41d5bae`: no old manual-refresh handler or revocation callback in that file.
- `MainMenuActivity.kt`, blob `2c3a0daf8db8400b1491ccf3d4437d5522ba8a58`: no old periodic `adminUnitsCheck`, `refreshAdminUnits` or `adminUnitsComplete` symbols in that file.
- `ServerFormUseCases.kt`, blob `d4c1469b393e736095d031cad40f6dcb62e825b0`: lines ~162–179 treat `mediaFile.type != null` as Entity List, download when absent or hash changed, otherwise skip; lines ~222–258 invoke local ODK Entity ingestion and optional integrity cleanup. This is form-manifest-triggered Entity List synchronization, not a proven implementation of legacy independent admin-unit refresh.
- Backend manifest omission of typed `mediaFile` attributes is recorded separately in `U01_T02_ODK_ENTITY_LIST_SEAM.md`.

## Precise verdict matrix

| Capability | Verdict | Evidence / limitation | Migration acceptance requirement |
|---|---|---|---|
| Manual refresh button | **MISSING AT LEGACY UI LOCATION**; alternative UNVERIFIED | Old view binding+handler absent in modern `ProjectSettingsDialog` | Explicit user-triggered project-reference refresh with success/error feedback |
| Activity-scoped five-minute polling | **MISSING AT LEGACY CALLBACK LOCATION**; alternative UNVERIFIED | Old `Handler` loop absent in modern `MainMenuActivity` | Bounded modern scheduler with lifecycle, backoff, offline, active-credential checks |
| District/subcounty/parish/village update | **UNVERIFIED equivalent** | Old keys populated from admin endpoint; modern exact old path absent | Stable project-scoped versioned data and metadata, no cross-project bleed |
| Revoked access notification | **UNVERIFIED equivalent** | Legacy fetch explicitly handled denied response; modern equivalent elsewhere not yet traced | Fail-closed authorization, user-visible revoked state and safe pending-instance handling |
| Native typed Entity List ingestion | **CODE EXISTS; INTEGRATION GAP** | Modern typed-media+hash branch exists; backend manifest lacks requisite type for current dataset advertisement | Eligible typed-only manifest, exact hash, native importer, version and deletion tests |
| Independent reference refresh | **UNVERIFIED / NOT CERTIFIED** | Form Entity List transport alone cannot prove old independent admin-unit contract | Separate project-scoped contract or proven equivalent; no historic answer leakage |

## Security and data-integrity correction to original design

Do **not** reproduce the old revocation algorithm literally. A dialog followed by project deletion **can fail if unsent instances or jobs exist**, and the old mutable admin-unit keys were written to unprotected settings. The modern contract should:

1. Deny all *new authenticated requests and protected source-work access* immediately when project/App User authorization is revoked, including retries.
2. Quarantine any pending local protected work and isolate it from different accounts/projects; do not silently delete valuable unsent user data.
3. Display revocation status clearly. Permit only a safe, explicitly authorized export/recovery or disposal policy after review; do not bypass server denial to upload.
4. Store reference-cache data scoped by stable project and actor credentials. For public reference choices, encryption requirements depend on classification, but protected source/record data must never be copied into the same cache.
5. Handle offline uncertainty explicitly: cached reference data may be displayed under policy, but must not imply current server authorization for sensitive protected work.

## Next executable work

- **Audit:** inspect current ODK project settings, background form synchronization, entity store, revocation/login and project lifecycle APIs beyond the old locations. Search with a complete checkout/index; GitHub default-branch code search cannot certify the modernization tip's entire tree.
- **Backend:** implement U05-T01 eligible typed datasets and independent project-scoped reference-data version/hash API only after compatible integration base is chosen and explicitly reviewed.
- **Android:** implement manual + bounded scheduled refresh using modern ODK project/account repositories; add revocation-safe suspension and UI status (not deletion as an auth control).
- **Tests:** exercise REF-01..REF-14 in `U01_T02_U05_REFERENCE_REFRESH_ACCEPTANCE_CONTRACT.md` on pinned backend/Android SHA pair; real PostgreSQL 15 plus Android emulator required.
- Preserve old capabilities; no retirement, merging, deployment, APK signing or production database change authorized.

**Evidence boundary:** This report proves selected original and new source behavior, not a whole-tree equivalence search, CI run, physical device test or integrated feature completion.
