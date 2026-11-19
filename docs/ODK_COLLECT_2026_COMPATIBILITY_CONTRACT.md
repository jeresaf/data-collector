# ODK Collect 2026 ⇄ Data Collector — OpenRosa Compatibility Contract

**Prepared:** 2026-11-19  
**Android source:** `jeresaf/collect` at official ODK `v2026.4.0`, commit `d6c4ecd0f1d470b501bcdbaaf43294820deeb61f`.  
**Server baseline:** `jeresaf/data-collector` `main`, commit `73ef37e54d3c6f3141b4b2b117ca8771e06e341d`.  
**Contract-test branch:** `codex/odk-2026-openrosa-qualification`.

## One URL per project

The Android build embeds **one** project base URL (without password):

`https://collector.example.org/v1/projects/<UUID>`

ODK Collect's `OpenRosaClient` appends `/formList` and a `deviceID` query parameter. The `OpenRosaServerInstanceUploader` uses `/submission` for the default form submission endpoint.

This is **not** the PDMIS legacy `/api/v1/formlist`, `/api/v1/entrylist` or `/api/v1/submission` protocol.

## Expected request-response contracts

| Client operation | HTTP | URL relative to project base | Expected status/content |
|---|---|---|---|
| Discover assigned forms | GET | `/formList?deviceID=<device>` | 200 `text/xml`, `X-OpenRosa-Version: 1.0`; each `xform` includes `formID`, `version`, `hash`, `downloadUrl` and `manifestUrl` |
| Fetch form XML | GET | URL from `downloadUrl` | 200 `text/xml`, exact published XML for the specified version |
| Fetch supporting file list | GET | URL from `manifestUrl` | 200 `text/xml`, `mediaFile` entries with filename, hash and HTTPS URL |
| Fetch form media | GET | URL from manifest | 200 with file bytes and content type, scoped to assigned project/form |
| Preflight submission | HEAD | `/submission?deviceID=<device>` | 204 and `X-OpenRosa-Accept-Content-Length` |
| Submit XML and attachments | POST | `/submission?deviceID=<device>` | 201/202 OpenRosa response, multipart field `xml_submission_file` plus attachments |
| Unauthenticated call | any | OpenRosa endpoints | 401 `WWW-Authenticate: Basic realm=...` and OpenRosa version header |

The server's `/formList` and `/submission` compatibility aliases also exist but are not a substitute for explicit project URLs when supporting multiple projects.

## Security and data invariants

- All credentials are user-supplied after installation. Never embed them in Gradle properties, source, example APKs, QR codes distributed publicly, or logs.
- Reject requests by nonassigned or revoked users, including form XML/media and submissions.
- Return externally visible **HTTPS** URLs when behind proxy; do not accidentally return `http://localhost:8080`, internal Docker hosts or untrusted hostnames.
- The `formID`, version, file MD5 and XML form contents must be internally consistent. Forwarded URL and XML encoding require testing with unusual identifiers.
- XML submission must remain unique across retries and corrections. `instanceID` and `deprecatedID` relationships and attachments must be preserved.
- Device installation ID is a client parameter, not an authorization credential.

## Outstanding scope

**This branch adds test coverage; it does not change the server's API.**

- Actual on-device tests of OpenRosa 2026 against a disposable backend with real PostgreSQL: **NOT RUN**.
- JavaRosa 4.3.2 backend vs JavaRosa 7.0.0 Android semantic parity: **NOT VERIFIED** for PDMIS nested repeats, dates, calculations, external lists, media, and old form versions.
- Remote retrieval and correction of server-side records: **NOT IMPLEMENTED in Data Collector API under a verified contract**. Must be designed with pagination, scopes, historical version, source/revision IDs, and safe replacement.
- Existing default admin seeded into the database by `V1__complete_platform_schema.sql`: **P0 SECURITY RELEASE BLOCKER** until remediated and any deployed credentials rotated/disabled.
- Fresh-install-only Flyway V1 baseline: verify upgrade strategy for any existing installation.
- The original `main` frontend Playwright CI remains red at its latest historical commit; independent from these OpenRosa tests.
- Android packaging/signature, distinct application ID and provider authorities require verified APK and device testing.

## Qualification evidence

Added integration cases inside `OpenRosaBasicAuthIntegrationTest`:
- `odk2026FormList_WithDeviceId_ReturnsRequiredOpenRosaFields`
- `odk2026FormList_UnauthenticatedDeviceGetsBasicChallenge`
- `odk2026FormList_ProxyHeadersProducePublicHttpsUrls`
- `odk2026SubmissionHead_WorksWithDeviceIdQuery`
- `odk2026CompatibilityAlias_WithDeviceId_ReturnsFormList`

The existing `ci.yml` runs backend H2 integration tests, PostgreSQL Testcontainers, and frontend tests; report separate job results honestly. No pass is claimed until Actions completes.

## Next engineering work

1. Confirm green backend OpenRosa tests at this exact commit.
2. Compile and verify branded Data Collector APK, then perform local device clean install and provider checks.
3. Use a disposable backend/project with synthetic credentials and a published form to test real Android login, form download, media manifest, offline save and submit.
4. Design and implement optional remote-record discovery/editing as a separate capability; do not copy `ubos-local`'s data-loss-prone replacement code.
5. Qualify PDMIS household and farm complex forms with approved baseline test vectors before implementation of visual Form Builder.
