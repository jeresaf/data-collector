# U06-T04 — Connected Android → Spring Boot → PostgreSQL acceptance gate

Status: **SPECIFIED / NOT EXECUTED**. This is an incremental, non-release qualification contract. It does not supersede the verified U06-T03 or Android M4C-3B3F evidence.

## Exact inputs (immutable until independently requalified)

- Backend: `jeresaf/data-collector`, Draft PR #31, commit `e1ef46967d05e4cdf82c088199f5fc76fc065c34` (PostgreSQL 15/Flyway real-loopback evidence).
- Android: `jeresaf/collect`, Draft PR #42, commit `1a5b9e17bd11314107dbd24ed37da0ead3af12e8` (API 35 Android emulator, 3 synthetic-transport tests).
- U06-T04 must use **actual app network requests** against a **real Spring Boot process** and an **ephemeral PostgreSQL 15 database**. MockWebServer, mocked HttpURLConnection, MockMvc and backend-only Java HttpClient are not substitutes for this gate.

## Required isolated topology

1. Run both repositories at pinned source revisions on a disposable CI runner. Start an ephemeral PostgreSQL 15 instance, apply production Flyway migrations, then start a real backend servlet listener. Do not connect to any production/staging database.
2. Seed only synthetic project, actor, published V1/V2 XForms, V1 source submission (repeat rows and binary photo), and corrected V2 submission. Use backend-supported provisioning APIs or isolated seed fixtures; never hardcode a privileged production account.
3. Expose the server to Android API 35 emulator over a **short-lived trusted HTTPS origin**. Configure app trust through a test-only trust mechanism that does not disable hostname/certificate validation, weaken production network security config, or add a cleartext exception. Emulator host reachability (e.g. 10.0.2.2) alone does not establish TLS trust. Cert SAN must match the test origin.
4. Point the debug-only Data Collector build to that origin and the synthetic project. Preserve per-project auth and app identity. Destroy certificates, credentials, PostgreSQL volume, emulator storage and generated media on completion.
5. Capture redacted request/response trace with request IDs and CI logs. Do **not** log Basic Authorization, full record answers, XML, private draft content, image bytes, AES keys or bearer credentials.

## Executable acceptance matrix

| ID | Real-device-to-real-server assertion | Expected |
| --- | --- | --- |
| U06-T04-01 | Android authenticates to `/v1/projects/{projectId}/records` over verified TLS | HTTP 200, synthetic V1 marked superseded and V2 marked current; no cross-project rows |
| U06-T04-02 | Remove/replace credentials and request the same resource | 401/403, no source XML or answer leakage |
| U06-T04-03 | Android attempts to create a fresh AMENDMENT against superseded V1 | HTTP 409, no work item inserted, no editor access or automatic retargeting |
| U06-T04-04 | Android creates AMENDMENT against current V2 and invokes exact work `prepare` | HTTP 201; owned work ID and exact-source revision binding |
| U06-T04-05 | Android stages a private draft, loses network, restarts process and recovers | Ciphertext remains private, actor/project-bound; no speculative submission or data loss |
| U06-T04-06 | Another actor revises the server record while Android is offline; original actor reconnects | Stale work is denied, private draft retained/quarantined, no silent overwrite or retry |
| U06-T04-07 | An approved current-version amendment is submitted and re-fetched | Distinct immutable revision; indexed repeat rows and photo retained; source history remains readable by owner |
| U06-T04-08 | A separate FOLLOW_UP representing a second household visit is submitted | Distinct linked visit; does not mutate the V1/V2 amendment chain or older observations |
| U06-T04-09 | Simulate lost acknowledgement after server commit and retry same client request | One durable work/submission/revision outcome; no duplicate attachments or audit events |
| U06-T04-10 | Attempt outdated form version answer carry-over without signed mapping authorization | Default deny/quarantine; no unreviewed typed-answer transfer |
| U06-T04-11 | Exercise application UI from discovery through error/recovery | No historical answer in toast/log; no editing of a superseded work item |
| U06-T04-12 | Build artifacts and source identities recorded | Exact backend+Android SHAs, successful backend PostgreSQL jobs and emulator instrumentation, retained sanitized evidence |

## Gate classification

- **PASS** only if every mandatory test above runs against the connected, real backend and its PostgreSQL store, with assertions and retained evidence at exact immutable commits.
- **BLOCKED** if HTTPS identity/trust, emulator reachability, synthetic seeding, or test orchestration cannot be established. Never replace missing connected tests with simulated responses and call them PASS.
- **FAIL** on substantive assertion failure. Preserve logs and fixtures sanitized; do not bypass the failing check.
- Keep PRs Draft/unmerged. No production deployment, public APK, release signing, API key trust grant, device rollout or destructive database operation. Physical-device acceptance is expressly deferred.

## Implementation order (small, independently auditable increments)

- **U06-T04A**: disposable dual-repository CI runner, pinned SHAs, PostgreSQL/Flyway and short-lived TLS endpoint; prove Android fetches backend health/record-list endpoint with verified hostname. No release.
- **U06-T04B**: instrument real authenticated list, 401 isolation, 409 stale amendment and 201 current amendment with SQL postconditions.
- **U06-T04C**: restart/offline recovery, revisions, repeats, photo, independent follow-up and idempotent lost-ack replay.
- **U06-T04D**: UI-driven stale-work recovery, evidence audit and release-blocker report. Physical devices remain out of scope.

## Existing independent evidence (not connected)

- Backend PR #31: https://github.com/jeresaf/data-collector/pull/31 ; CI: https://github.com/jeresaf/data-collector/actions/runs/45174310831
- Android PR #42: https://github.com/jeresaf/collect/pull/42 ; CI: https://github.com/jeresaf/collect/actions/runs/45169361936

**No U06-T04 PASS is claimed by creating this specification.**
