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
