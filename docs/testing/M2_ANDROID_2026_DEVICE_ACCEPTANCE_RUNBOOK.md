# M2 — Android-to-Data Collector Device Acceptance

**Prepared:** 19 November 2026  
**Gate:** Device-level qualification **DEFERRED BY OWNER DECISION (2026-11-19), NOT RUN**; do not claim mobile/server integration solely from automated HTTP tests. Deferral allows M3 development and isolated CI but DOES NOT grant production release approval.  
**Android build:** `jeresaf/collect` `codex/odk-2026-app-identity`, stacked on `codex/odk-2026-url-bootstrap`.  
**Server tests:** `jeresaf/data-collector` `codex/m2-odk2026-integration-gate`.

## Scope and safety

Use only a **disposable HTTPS deployment** of Data Collector with an isolated PostgreSQL database, one project, one synthetic published XForm, and a **test App User assigned to exactly that form**. Do not expose any Data Collector deployment until the seeded default administrator risk is remediated. No real household/health/identity records, shared field device, live credentials, or production migrations.

No script in this runbook deletes project data, resets databases, uninstalls existing apps, changes signing keys, or modifies a production environment. Do not use `adb uninstall` or `docker compose down -v` on a real installation.

## Build and acquire an Android test APK

1. Visit [Branded Android build workflow](https://github.com/jeresaf/collect/actions/workflows/odk-2026-branded-build.yml).
2. Select **Run workflow** on `codex/odk-2026-app-identity`.
3. Set `server_url` to `https://<test-host>/v1/projects/<real-test-UUID>` **without** credentials, query parameters or fragments.
4. Keep `application_id=org.jeresaf.datacollector.android`, unless deliberately qualifying a second independently installed branded client.
5. After a green build, download and extract **`data-collector-debug-apk`**. The ordinary push-triggered APK has a placeholder `collector.example.org` URL and **cannot** contact a real project.
6. Record the workflow run ID, commit SHA, downloaded artifact SHA-256, Android build version, source URL (without any secret), app ID and signing certificate fingerprint.

Recommended checks on an isolated test machine with Android SDK platform tools installed:

```bash
sha256sum path/to/data-collector-debug.apk
aapt dump badging path/to/data-collector-debug.apk | grep -E '^(package:|application-label:)'
adb devices -l
adb shell pm list packages | grep -E '(org.odk.collect.android|org.jeresaf.datacollector.android)' || true
# Only on an approved test device with no Data Collector user data:
adb install path/to/data-collector-debug.apk
adb shell pm path org.jeresaf.datacollector.android
```

If `adb install` reports an incompatible certificate or downgrade, **stop**. Never uninstall/reinstall to bypass it before preserving the existing app's data and establishing a controlled migration plan.

## Android device test matrix

| ID | Procedure | Acceptance evidence |
|---|---|---|
| D01 | Install Data Collector beside official ODK Collect on a disposable Android 11+ device. | Both installed; distinct package IDs and provider authorities, no conflict. |
| D02 | Fresh launch without QR or hidden configuration, observe the configured project. | Exactly the selected HTTPS project URL, no bundled username/password. |
| D03 | Use wrong App User credentials then valid, form-assigned test credentials. | 401 challenge then successful authentication; no token or password in screenshots/logs. |
| D04 | Download published synthetic XForm and inspect form ID/version/hash. | Correct form count, version, XML bytes and successful form open. |
| D05 | Download optional photo / CSV media where the synthetic test form provides them. | Hash and filename match, no 404, safe file path resolution. |
| D06 | Enter draft answers, disable networking, save and force-close the app; then reopen offline. | Exact answers, instance identity and media retained. |
| D07 | Complete an offline form with sample repeat entries and a synthetic photo; restart before submitting. | Calculations, repeat values, paths and media preserved. |
| D08 | Reconnect, submit once and inspect server via read-only SQL/admin screen. | HTTP 201, stored `instance_id`, normalized answers, attachments, source and audit event. |
| D09 | Emulate a **lost success acknowledgement** on a disposable test submission; retry without changing instance ID. | No second stored record; 409 or explicitly agreed idempotent success, correct client feedback. |
| D10 | Revoke test app-user assignment and repeat formList, media download and submission attempts. | No unauthorized access or submitted record. |
| D11 | Publish version 2 of the synthetic form; verify form-update behavior, existing draft and old version semantics. | No silent overwrite of drafts, no incorrect form ID/version or lost answers. |
| D12 | Confirm logout/project-switch behavior and co-installation after reboot. | No credential leakage, ODK unaffected, cached data remains bound to its project. |

Test at minimum Android **11/API 30** and one newer API level, on clean data. Note make/model, Android version, device time zone and network conditions in evidence.

## Read-only server evidence

Record the synthetic instance ID returned by the client, then query the **disposable** PostgreSQL database using a read-only account:

```sql
SELECT id, instance_id, status, submitted_at, metadata
FROM submissions
WHERE instance_id = :synthetic_instance_id;

SELECT COUNT(*) AS normalized_answers
FROM submission_answers
WHERE submission_id = :synthetic_submission_uuid;

SELECT COUNT(*) AS attachment_count
FROM submission_attachments
WHERE submission_id = :synthetic_submission_uuid;
```

Use parameterized queries in tools; never copy credentials or actual personal data into CI reports. Also save sanitized screenshots, network status codes, ODK source version, hash of downloaded XForm and server/app commit hashes. Logs may contain identifiers or authorization headers: redact before circulation.

## Existing automated evidence and remaining blockers

- Original ODK v2026.4.0 and one-URL bootstrap Gradle builds: PASS.
- Branded package + provider assertions and APK artifact publication: PASS ([Actions #44573378674](https://github.com/jeresaf/collect/actions/runs/44573378674)).
- Five focused project/OpenRosa contract assertions: PASS.
- Synthetic HTTP server GET/HEAD/POST + duplicate retry on H2: PASS.
- Production PostgreSQL 15 + Flyway + actual HTTP smoke: see latest CI on `codex/m2-odk2026-integration-gate`; **do not claim PASS until confirmed**.
- **D01–D12 device matrix: NOT EXECUTED.** Need a physically installed device/emulator, a permitted test server URL and synthetic credentials configured on the device.
- Production app signing, administrator bootstrap security, Flyway upgrade path and historical-record correction protocol: **BLOCKED / UNSPECIFIED**, separate release gates.

## Release decision

Android/server integrated certification is **BLOCKED** until D01–D12 evidence is reviewed at the exact Android and backend commits. A successful debug APK, mocked Playwright test or HTTP-only smoke may never be described as field acceptance.

## Approved deferral and continuation boundary (2026-11-19)

The project owner explicitly deferred physical Android acceptance to a later date. This is a **schedule decision**, not a technical waiver or acceptance result.

- **Permitted to continue:** branch-isolated implementation, unit/integration tests, disposable PostgreSQL and Spring Boot HTTP qualification, OpenRosa compatibility, backend security remediation, the generic remote-record correction design, and advanced form-builder work using synthetic/approved form fixtures.
- **Still blocked:** production APK signing and field rollout, replacing installed PDMIS/Kobo clients, claims of native-device compatibility, migration of live local instance databases, any production deployment using seeded development-admin credentials, and final release certification.
- **Required future evidence:** all D01–D12 checks (including offline restart, local data preservation, media, retry, project revoke and multi-version behavior), exact APK/server hashes and reviewer acceptance. Reopen the device gate before any deployment.
- **Next engineering package:** M3 remote record listing, authorization, historical XForm retrieval, edit/correction audit and lossless storage. Implement only after API and threat-model review; never copy the legacy unsafe `finally`-deletion behavior into ODK 2026.
