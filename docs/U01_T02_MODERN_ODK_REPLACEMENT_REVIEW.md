# U01-T02 — Modern ODK 2026 Replacement Review

**Date:** 20 Nov 2026. **Status:** PARTIAL source audit; no Android E2E acceptance.
**Android ref:** jeresaf/collect (remove space) `codex/m4c2b3c3b3b-native-typed-value-proof`.

## Evidence

| Current source and blob | Behavior confirmed from source | Verdict |
|---|---|---|
| `formmanagement/FormsDataService.kt` (`20bc1a614db089a8b1b91df746c15b52ec76215e`) | Project-scoped `downloadUpdates` and `matchFormsWithServer`; downloads changes when `KEY_AUTOMATIC_UPDATE` is set, otherwise notifies user. | CODE EXISTS for **form updates**, not independent administration-unit refresh. |
| `formmanagement/ServerFormUseCases.kt` (`d4c1469b393e736095d031cad40f6dcb62e825b0`) | Detects typed media via `mediaFile.type != null`; compares Entity List hashes and ingests updated lists through `LocalEntityUseCases`. | Native Entity List code exists; backend OpenRosa manifest type gap remains. |
| `recordwork/RecordWorkBrowserActivity.kt` (`c85bcaa96c4e20e1cb2661a21eb8c67d9a9d5ed1`) | Read-only assigned-record browser validates active session; routes selected work to preparation activity with target version. | PARTIAL: not evidence of complete rejected/corrected edit flow. |
| `instancemanagement/send/OpenRosaServerInstanceUploader.kt` (`84fc406dd59e15fb7db3745063521421ad736b13`) | Calls `QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed` before ordinary instance upload. | CODE EXISTS; retry/current XML and protected upload finalization unverified. |
| `projects/ProjectSettingsDialog.kt` (`94ba331d2bca78f97ddd9d45f0dd4e62f41d5bae`) | Has add/switch project UI but no old manual admin-unit refresh handler. | Legacy UI path MISSING; alternative UNVERIFIED. |
| `mainmenu/MainMenuActivity.kt` (`2c3a0daf8db8400b1491ccf3d4437d5522ba8a58`) | Fragment-based menu without prior five-minute admin-unit Handler callback. | Legacy callback path MISSING; alternative UNVERIFIED. |

## Migration requirements

1. Reuse ODK form-update/native typed Entity List components when schemas and authentication match. Ordinary CSV media must remain untyped.
2. Do not mistake form update for independent reference-data refresh. The legacy `/api/v1/adminunits` behavior remains unverified in ODK 2026.
3. Verify rejected status/reason, deliberate edit confirmation, target version, source ID and instance retry. A read-only browser alone is not equivalent.
4. Verify correction and follow-up uploads separately from ordinary upload; quarantine guard alone is insufficient for U08 acceptance.
5. Server revocation must fail closed even if pending submissions prevent project deletion.

## Next qualification

Full Android checkout and effective manifest/provider audit; U05-T01 typed metadata with H2 and PostgreSQL tests; Android emulator refresh/offline/authorization tests; then exact-SHA cross-repo amendment, FOLLOW_UP, corrected submission, and retry tests.

**Evidence boundary:** Android repo-wide alternative implementation search and functional parity are still incomplete. Backend U01-T03 PR #27 CI passed on `312c67c4a51b7ec7867531af5de3526f14939451` (run #44792561117), but that did not qualify the Android branch. No production or APK change.
