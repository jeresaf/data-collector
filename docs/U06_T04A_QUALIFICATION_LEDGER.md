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
