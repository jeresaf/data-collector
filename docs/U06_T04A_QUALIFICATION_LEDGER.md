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

## U06-T04C-03C exact-head corrective compile gate (2026-11-23)

- Android Draft [PR #51](https://github.com/jeresaf/collect/pull/51), first head `b099b4fd654dcbffbdace3db1962d74c1f1395f7`, [CI #45213196424 FAILED](https://github.com/jeresaf/collect/actions/runs/45213196424) at `:collect_app:compileDebugAndroidTestKotlin` **before instrumentation execution**. The generated test contained literal `\\n` sequences inside a Kotlin `upload` function signature, causing compilation errors. None of the new lost-ACK/reconciliation assertions executed.
- Corrected Android commit `d14a9ab277a3c395bdb437d53d228ad10011f791` replaces literal characters with actual line breaks. Exact-head [CI #45215717017](https://github.com/jeresaf/collect/actions/runs/45215717017) **IN PROGRESS when checked**. **U06-T04C-03C remains PENDING**; no U06-T04D final acceptance authority until this run passes.
- Passing prior U06-T04C-03B exact-head evidence remains [CI #45209871013 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45209871013). Never interpret compile failure as a real-backend lost-ack behavioral failure.

## U06-T04C-03C PASS and U06-T04D-01 rendered UI pending (2026-11-23)

- **U06-T04C-03C PASS:** Android Draft [PR #51](https://github.com/jeresaf/collect/pull/51) corrected exact head `d14a9ab277a3c395bdb437d53d228ad10011f791`, [CI #45215717017 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45215717017). Real Android HTTPS multipart first committed a synthetic V2 follow-up on PostgreSQL; test-only post-commit exception discarded the acknowledgement. Android independently re-queried the stable instance ID and completed work, identical retry returned 409, and durable original/repeat/media/link counts remained unchanged. This is injected ACK loss, not real network packet loss or production ODK auto-reconciliation.
- **U06-T04D-01 PENDING:** Android Draft [PR #52](https://github.com/jeresaf/collect/pull/52), exact head `cf846e7c2d35710fe5968548a467828438eeec07`, [CI #45218386161](https://github.com/jeresaf/collect/actions/runs/45218386161) RUNNING at checkpoint. Actual Android API35 ActivityScenario tests assert quarantined conflict UI without active actor binding has FLAG_SECURE, suppressed metadata and hidden/disabled navigation even after Activity recreation; invalid preparation intent hides mutation controls. **No UI PASS until exact-head CI succeeds.**
- **Outstanding release boundaries:** native ODK form filling and InstanceSubmitter end-to-end, true network partition/interruption, edit/restart/full encrypted draft stores, accessibility/localization and representative physical devices. No merges, deployments or signing.

## U06-T04D rendered recovery UI admission (2026-11-23)

- **U06-T04D-01 PASS:** Android Draft [PR #52](https://github.com/jeresaf/collect/pull/52), exact head `cf846e7c2d35710fe5968548a467828438eeec07`, [CI #45218386161 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45218386161). Actual API35 ActivityScenario checks conflict screen without original actor binding uses Android FLAG_SECURE, displays no private answers/work metadata, hides and disables navigation before and after Activity recreation, and invalid project/source hides preparation mutation controls.
- **U06-T04D-02 PENDING:** Android Draft [PR #53](https://github.com/jeresaf/collect/pull/53), exact head `14a4974f4b89d2eea8bf2c63da019b398ca22d07`, [CI #45221963532](https://github.com/jeresaf/collect/actions/runs/45221963532) RUNNING at the last check. Additional actual Activity tests reject forged project UUIDs, positive-but-untrusted source amendment hints and work identifiers; hide all amendment/follow-up/create controls and avoid showing forged recovery metadata. U06-T04D-02 is not PASS until the exact-head workflow succeeds.
- **Remaining U06-T04D gates:** Positive authenticated record-browser UI, successful preparation UI and manual mapping review, native ODK editor/save/restart lifecycle, actual ODK submitter, real network interruption and physical device validation are not established by negative UI tests. Keep all Draft and unmerged.

## U06-T04D-02 closed; U06-T04D-03A record row rendering in CI (2026-11-23)

- **U06-T04D-02 PASS:** Android Draft [PR #53](https://github.com/jeresaf/collect/pull/53), exact head `14a4974f4b89d2eea8bf2c63da019b398ca22d07`, [CI #45221963532 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45221963532): actual API35 ActivityScenario rejects forged project/actor context, forged sourceMayAmend=true and unauthorized work identifiers before and after recreate, with no metadata leakage.
- **U06-T04D-03A PENDING:** Android Draft [PR #54](https://github.com/jeresaf/collect/pull/54), exact head `da972920af9323a238b8e8ce004711428b0f3b42`, [CI #45224493041](https://github.com/jeresaf/collect/actions/runs/45224493041) RUNNING at checkpoint. Actual browser's shared Android Button renderer receives authenticated real Spring/PostgreSQL V1/V2 metadata through `RecordWorkReadOnlyClient`. Tests require superseded V1 historical warning, current V2 recheck warning, correct click-selected server record, no source XML content in labels, and fail-closed unknown revision. **Component UI rendering, not a complete authenticated browser Activity navigation test.**
- Outstanding: real active-project browser→prepare Activity journey, successful work selection, native editor/save/upload, controlled actual network interruption, accessibility/device review and operational release authority. Do not mark U06-T04D or U06 overall complete; all PRs remain Draft.

## U06-T04D-03A passed; U06-T04D-03B1 Android navigation bridge pending (2026-11-23)

- **U06-T04D-03A PASS:** Android stacked Draft [PR #54](https://github.com/jeresaf/collect/pull/54), exact commit `da972920af9323a238b8e8ce004711428b0f3b42`, [CI #45224493041 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45224493041). The same Android row factory used by the real browser rendered authenticated PostgreSQL V1 historical and V2 current metadata with the correct warning and click-selected identity. Unknown amendment revision fails closed, and answer text is not displayed.
- **U06-T04D-03B1 PENDING:** Android stacked Draft [PR #55](https://github.com/jeresaf/collect/pull/55), exact head `e246d94f6eba1f7ed57763771b46d62b7006145a`, [CI #45227586369](https://github.com/jeresaf/collect/actions/runs/45227586369) RUNNING when checked. The real browser's record and pending-work button callbacks now use the same tested, metadata-only `RecordWorkNavigation` Intent builder. New genuine-HTTPS authenticated V1/V2 Android instrumentation assertions check the exact project/source/current-vs-superseded amendment hint, pending server work IDs/targets/types, rejection of malformed IDs, and non-leakage of XML/credentials. **This is an Intent contract, not complete Activity-to-Activity navigation with configured ODK project.**
- **U06-T04D not complete:** positive actual browser Activity → prepared-work UI, native ODK form editor, InstanceSubmitter, real network interruptions, accessibility/localization and representative physical-device testing remain unqualified. No production releases, merges, or signing.

### U06-T04D-03B1 latest exact-head clarification
The previous `e246d94...` run is superseded by Android commit `ece54cc2a6ede568f2ebf7dd2871a7804ffb4ba4`. The pending-work Intent test now always checks a synthetic known-shape pending reference plus any actual server pending work, avoiding a vacuous pass when parallel tests have not created work. [Exact-head CI #45227777478](https://github.com/jeresaf/collect/actions/runs/45227777478) QUEUED at the last check. Do not mark PASS until this run succeeds.

## U06-T04D-03B1 exact-head CI retry due to external Maven Central rate limiting (2026-11-23)

- Latest Android PR [#55](https://github.com/jeresaf/collect/pull/55) exact head `ece54cc2a6ede568f2ebf7dd2871a7804ffb4ba4`, [CI #45227777478 attempt 1 FAILED](https://github.com/jeresaf/collect/actions/runs/45227777478) **before Android compile or tests**. Failure at the genuine backend `mvn -B -ntp -DskipTests package` step: Maven Central returned HTTP 429 for imported Testcontainers 1.21.4 and Spring Boot 3.2.5 dependency BOMs. Derived unresolved POM/version errors do not indicate an application failure. No instrumentation assertion ran on this head.
- A failed-jobs-only rerun was requested for **the unchanged exact SHA** via GitHub Actions; run #45227777478 attempt 2 was IN PROGRESS when recorded. Previous head `e246d94f...` [CI #45227586369 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45227586369), but the newer nonvacuous pending-work assertion still requires its own successful exact-head execution.
- Maintain U06-T04D-03B1 PENDING until attempt 2 succeeds. No source changes to bypass Maven checks or reduce acceptance scope.
