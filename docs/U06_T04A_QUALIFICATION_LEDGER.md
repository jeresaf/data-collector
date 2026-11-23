# U06-T04A qualification ledger — evidence at exact commits

## Backend host-side TLS + PostgreSQL preflight: VERIFIED

- Repository: `jeresaf/data-collector` Draft PR #32
- Backend commit: `9f685630ea4b480d0c4047757b6526f8b662efdd`
- Host preflight run: https://github.com/jeresaf/data-collector/actions/runs/45178731952 — **SUCCESS**
- Backend quality gate: https://github.com/jeresaf/data-collector/actions/runs/45178786110 — **SUCCESS**
- Scope: Java/Spring Boot production Flyway schema against disposable PostgreSQL 15, with temporary nginx HTTPS front end and CA-verified host curl; untrusted default host trust rejected.
- **NOT proven:** Android transport, authenticated records, image/repeat revisions, offline drafts, actual physical devices or release.

## Android actual emulator HTTP/TLS qualification: CI RUNNING / NOT YET ACCEPTED

- Repository: `jeresaf/collect` Draft PR #43
- Android commit: `ad629ccab486b7a488ea4aa9dc28749e6513434e`
- Emulator workflow: https://github.com/jeresaf/collect/actions/runs/45182644287
- Scope: Android API 35 test instrumentation to ephemeral Spring Boot via `https://10.0.2.2:8443/actuator/health` with a per-test trusted CA and normal Android hostname verification. Default Android trust must reject the ephemeral test CA.
- Classified **PENDING** unless an exact-head successful emulator test report is available. A running or failed workflow cannot be counted as connected proof.
- This is a health-only genuine network smoke, not authenticated `RecordWorkReadOnlyClient` access, server-seeded histories, 409 conflicts, submission, recovery, or end-to-end.

## Next mandatory gate — U06-T04B

After emulator health TLS succeeds, test the actual `RecordWorkReadOnlyClient` with real project-scoped synthetic credentials and server-seeded `/records` and `/record-work-items`. Require 200 and both V1 superseded/V2 current, plus wrong credentials 401, cross-project segregation, stale source 409 and current source 201 with durable DB postconditions. Keep offline amendment draft tests for U06-T04C.

Both PRs remain Draft. No merges, production database, real user data, release signing, APK distribution or physical-device signoff.

## U06-T04A exact-head closure and first U06-T04B negative admission probe (2026-11-23)

- Android U06-T04A exact head `25aa29ef0e0496ef67222b8fd8233d6f85961cab`: [CI #45184592487 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45184592487). Real API 35 emulator reached genuine HTTPS Spring Boot/PostgreSQL 15 health endpoint with test-only CA and default hostname verification; negative untrusted-CA test passed. **U06-T04A closed PASS** (health/TLS only).
- Initial head `ad629ccab486b7a488ea4aa9dc28749e6513434e`: [CI #45182644287 FAILURE](https://github.com/jeresaf/collect/actions/runs/45182644287), because the Actuator response's vendor JSON media type failed an overly strict `application/json` prefix assertion after HTTP 200; corrected without relaxing TLS.
- Android Draft [PR #44](https://github.com/jeresaf/collect/pull/44) U06-T04B-00, exact head `5b05561120656b233bef5663a93a9b0454b886a8`, [CI #45187187209](https://github.com/jeresaf/collect/actions/runs/45187187209): **RUNNING at evidence capture**. Tests unauthenticated `/records` over real verified Android TLS with expected 401 and no private answer/XML in response. Not an authenticated-list success test. Do not promote U06-T04B to PASS until synthetic project actor and V1/V2 records are seeded and exercised on Android against the real backend.

## Verified connected Android record-work milestones (2026-11-23)

- **U06-T04B-00 PASS:** [CI #45187187209](https://github.com/jeresaf/collect/actions/runs/45187187209), anonymous records refused HTTP 401.
- **U06-T04B-01 PASS:** [CI #45190908084](https://github.com/jeresaf/collect/actions/runs/45190908084), actual Android authenticated record client sees synthetic V1 superseded/V2 current on PostgreSQL 15, wrong credentials refused.
- **U06-T04B-02 PASS:** [CI #45194441987](https://github.com/jeresaf/collect/actions/runs/45194441987), real Android stale V1 create 409, current V2 create 201, exact idempotent replay, durable PostgreSQL counters.
- **U06-T04C-01 PASS:** [CI #45196605539](https://github.com/jeresaf/collect/actions/runs/45196605539), real Android creates independent FOLLOW_UP from historic V1, replays idempotently; no premature submission or revision mutation.
- **U06-T04C-02A PENDING:** Android Draft [PR #47](https://github.com/jeresaf/collect/pull/47), head `f50c95ed5d3c3a4f160b47d9c596afe222cd4223`, [CI #45200049092](https://github.com/jeresaf/collect/actions/runs/45200049092) was RUNNING at this checkpoint. Isolated archived-source stale-work fixture; Android's real HTTPS prepare must return 409; Android Keystore ciphertext retained after injected network-outage branch and after live conflict, without editor/upload authority. **Not** a real airplane-mode test, process-restart test, or complete offline editor validation.

All tests use synthetic temporary data. Actual follow-up submission, source/media/repeat preservation, lost acknowledgement reconciliation, and full offline + UI recovery remain BLOCKED until separately evidenced. Draft-only; no production data, releases, merges or physical devices.

## U06-T04C protected stale recovery and process restart (2026-11-23)

- **U06-T04C-02A PASS:** Android Draft [PR #47](https://github.com/jeresaf/collect/pull/47), exact head `f50c95ed5d3c3a4f160b47d9c596afe222cd4223`, [CI #45200049092 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45200049092). Real Android HTTPS prepare against PostgreSQL/Flyway stale source returns 409; recovery denies editor/save/upload/retry and preserves actor-bound Android Keystore AES-GCM ciphertext; network outage branch uses an injected transport exception.
- **U06-T04C-02B PENDING:** Android Draft [PR #48](https://github.com/jeresaf/collect/pull/48), exact head `cb756d51c6b8de665c3c77ca1b958cc7944d52d6`, [CI #45204231612](https://github.com/jeresaf/collect/actions/runs/45204231612) in progress when logged. Phase 1 writes only Android Keystore AES-GCM ciphertext under noBackup; phase 2 runs in a **separate AndroidJUnitRunner process** after app force-stop without reinstall, proves original ciphertext and actor binding survive, and exercises the real backend stale 409 with no editor/upload authorization. No PASS claimed until exact-head CI success.
- **Still unqualified:** actual ODK editor and EncryptedPrivateTextDraftStore restart restoration, airplane-mode network outage, physical device reboot, new follow-up submissions, repeat/image preservation through upload and lost acknowledgement idempotency. These remain separate milestone gates.

No production, release signing, merges, or physical-device signoff.

## U06-T04C-02B process restart closure and C-03A follow-up intake (2026-11-23)

- **U06-T04C-02B PASS** at Android exact head `cb756d51c6b8de665c3c77ca1b958cc7944d52d6`: [GitHub Actions #45204231612 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45204231612). Two distinct AndroidJUnitRunner invocations with application force-stop in between retained synthetic Android Keystore AES-GCM ciphertext in noBackup; actor binding survived and stale server prepare remained HTTP 409 after restart. Not full native ODK editor restart.
- **U06-T04C-03A PENDING**, [Android Draft PR #49](https://github.com/jeresaf/collect/pull/49), exact head `92b953c18119bd9f05e3ce933a4a1bd0b773a6cf`, [CI #45207031294](https://github.com/jeresaf/collect/actions/runs/45207031294) in progress when checked. Minimal real Android HTTPS multipart follow-up from historical V1 to V2, with a server-issued independent work item and new instanceID (no deprecatedID); expected HTTP 201, duplicate replay HTTP 409, one completed link, untouched source and archive, three total synthetic submissions. PostgreSQL durable checks embedded in workflow. **No exact-head PASS claimed yet**.
- Still out of scope: Android production ODK InstanceSubmitter transport, true airplane-mode handling, native editor restoration, media and repeat reconciliation, lost-ack semantics, and real physical device qualification.

All changes remain Draft and no production databases, deployments, release signing or merges are authorized.

## U06-T04C-03A qualified; U06-T04C-03B under CI (2026-11-23)

- **U06-T04C-03A PASS:** Android stacked Draft PR [#49](https://github.com/jeresaf/collect/pull/49), exact commit `92b953c18119bd9f05e3ce933a4a1bd0b773a6cf`, [CI #45207031294 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45207031294). Real Android emulator TLS multipart follow-up creates one new observation with server-issued work ID, no deprecatedID, duplicate retry 409; persisted link and historical source/archive integrity asserted in PostgreSQL. Not native InstanceSubmitter or lost-ack qualification.
- **U06-T04C-03B PENDING:** Android stacked Draft [PR #50](https://github.com/jeresaf/collect/pull/50), exact head `693cbf8fdefd2fcbadb511c5650925b96656b30e`, [CI #45209871013](https://github.com/jeresaf/collect/actions/runs/45209871013) RUNNING at checkpoint. Synthetic V2 target XForm includes a binary photo and repeated child name/age fields; actual Android HTTPS multipart submits two repeats and disposable JPEG-signature bytes. PostgreSQL postflight requires exact indexed normalized answers, attachment linked to its binary answer, physical bytes and SHA-256 match, no altered source/archive and duplicate HTTP 409. **Do not claim PASS until exact-head CI succeeds**.
- Still pending: full native ODK InstanceSubmitter/media repeat editing workflow, real offline and lost-ack post-commit reconciliation, and U06-T04D native UI evidence. No production merge or release.

## U06-T04C-03B qualified; U06-T04C-03C lost acknowledgement under CI (2026-11-23)

- **U06-T04C-03B PASS:** Android Draft [PR #50](https://github.com/jeresaf/collect/pull/50), exact head `693cbf8fdefd2fcbadb511c5650925b96656b30e`, [CI #45209871013 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45209871013). Android API35 real TLS multipart to the PostgreSQL/Flyway backend included two indexed children and synthetic JPEG media; the real database postflight asserted normalized child answers, linked binary-answer metadata, physical bytes and SHA-256, preserved V1 archive/source, and denied duplicate replay.
- **U06-T04C-03C PENDING:** Android Draft [PR #51](https://github.com/jeresaf/collect/pull/51), exact head `b099b4fd654dcbffbdace3db1962d74c1f1395f7`, [CI #45213196424](https://github.com/jeresaf/collect/actions/runs/45213196424) in progress when recorded. After server's real HTTP 201 commit, test-only boundary intentionally discards acknowledgment and raises IOException to Android caller. The test re-queries owned records by stable instanceID and completed-work state, then deliberately retries identical work+multipart and requires 409 and same submissionID; durable PostgreSQL counts require one child/link/work/media. **This is an injected post-commit acknowledgement exception, not real network packet loss or native InstanceSubmitter automatic retry behavior.** Do not call PASS before exact-head CI success.
- **Still pending:** full native ODK form editor and InstanceSubmitter, uncontrolled network interruption/process death during upload, automatic post-ACK-loss reconciliation, physical devices and U06-T04D user-interface acceptance. Draft-only; no production data or release.
