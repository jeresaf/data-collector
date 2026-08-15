# Compatibility Test Execution Log

Use one row per certification run. Checklists define what must be tested; this log records what was actually executed.

## Automated certification baseline — verified 15 August 2026

- Backend Maven/H2 regression suite: green.
- PostgreSQL Testcontainers migration/schema certification: green.
- GitHub Actions CI quality gate: green.
- Frontend Vitest and production build: green.
- AUDIT-13 full user-facing `/app/...` web-form workflow shell: green.
- AUDIT-14 expanded Playwright acceptance coverage: green.
- AUDIT-15 web-user/app-user/global-role lifecycle administration and audit coverage: green.
- Physical ODK Collect and KoboCollect execution remains pending.
- Physical web barcode/media behavior remains pending.
- Selected advanced-XForm ODK-vs-web comparison remains pending.

## Physical client runs

| Date | Client | Client version | Backend commit/build | Project / Form | Device / Android | Tester | Result | Evidence / issue |
|---|---|---|---|---|---|---|---|---|
| | ODK Collect | | | | | | | |
| | KoboCollect | | | | | | | |

## Device/browser certification

| Date | Browser/device | Scenario | Backend commit/build | Tester | Result | Evidence / issue |
|---|---|---|---|---|---|---|
| | | Barcode camera | | | | |
| | | Image/file/signature | | | | |
| | | Advanced XForm comparison | | | | |

## Final manual gates

- [ ] TASK 8.12 — ODK Collect compatibility
- [ ] TASK 8.13 — KoboCollect compatibility
- [ ] TASK 9.10 — ODK/Kobo media download
- [ ] TASK 10.17 — ODK Collect submission
- [ ] TASK 10.18 — KoboCollect submission
- [ ] TASK 21.9 — ODK Collect physical checklist execution
- [ ] TASK 21.10 — KoboCollect physical checklist execution
- [ ] Advanced XForm ODK-vs-web comparison
- [ ] Physical barcode/camera/media browser certification
