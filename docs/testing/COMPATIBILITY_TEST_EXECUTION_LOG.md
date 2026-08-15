# Compatibility Test Execution Log

Use one row per certification run. Checklists define what must be tested; this log records what was actually executed.

## Automated certification baseline — verified 14 August 2026

- Backend EPIC 21 Batches 1 and 2: green.
- Frontend Vitest: 21 test files, 50 tests, all green.
- Frontend production Vite build: green.
- Playwright: 6/6 tests green across desktop and mobile Chromium.
- Physical ODK Collect and KoboCollect execution is still pending and must be recorded below; automated browser tests do not substitute for those client checks.


## Automated certification baseline — verified 15 August 2026

- AUDIT-09 submission review/detail backend and frontend regression gates: green.
- Frontend protected-route Playwright authentication regression fixed and rerun green.
- Playwright: 6/6 tests green across desktop and mobile Chromium.
- AUDIT-10 barcode control: manual entry + camera capture implementation, focused Vitest coverage, full frontend test/build, and Playwright regression suite reported green.
- Physical barcode-camera verification on a real phone/browser is still pending.
- Physical ODK Collect and KoboCollect execution is still pending; automated browser tests do not substitute for those client checks.

| Date | Client | Client version | Backend build/commit | Project / Form | Tester | Result | Evidence / issue |
|---|---|---|---|---|---|---|---|
| | ODK Collect | | | | | | |
| | KoboCollect | | | | | | |
