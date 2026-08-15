# EPIC 21 — Testing and Compatibility Certification Matrix

This matrix records what is already covered and what still needs certification. It is intentionally based on the repository's actual test files rather than assuming one test class per production class.

## Current automated baseline

At the start of EPIC 21 the verified project contains:

- All backend service classes now have direct same-name unit coverage, supplemented by specialized compatibility/integration suites.
- All controller/OpenRosa controller classes now have direct or dedicated integration coverage.
- 27 standard repository interfaces plus the audit-detail repository, but only one dedicated repository integration test before this EPIC.
- 17 frontend Vitest files covering stores, components, web-form utilities/views, application mounting, and route resolution.
- Playwright browser suite added in Batch 3 and verified locally green across desktop and mobile Chromium.

Run `python3 scripts/epic21-test-inventory.py` after each testing batch to refresh the structural inventory.

## Task certification matrix

| Task | Current evidence | Status after Batch 1 |
|---|---|---|
| 21.1 Service unit tests | Batch 2 direct same-name service coverage + specialized integration suites | Complete |
| 21.2 Controller integration tests | Existing integration suite + direct FormController/OpenRosaCompatibilityController coverage | Complete |
| 21.3 Repository tests | Entity List repository test + all-repository query/schema smoke test | Complete |
| 21.4 OpenRosa contract tests | Basic auth suite, headers, formList/download/hash, manifest/media and submission integration tests all passed in the latest full Maven run | Complete |
| 21.5 JavaRosa compatibility tests | Advanced XPath/repeat/media/geo tests + stable golden XForm fixtures | Complete |
| 21.6 Frontend component tests | 21 Vitest files / 50 tests green + successful production build | Complete |
| 21.7 Frontend route tests | `src/router/index.test.ts` verifies project/submission and new/resume web-form routes and was included in the previously green frontend suite | Complete |
| 21.8 Browser e2e tests | 6/6 Playwright tests green across desktop/mobile Chromium | Complete |
| 21.9 ODK Collect checklist | Checklist created in this batch | Complete (checklist creation) |
| 21.10 KoboCollect checklist | Checklist created in this batch | Complete (checklist creation) |

## Required regression gates

Before closing EPIC 21, all of the following must be green on the developer machine:

```bash
cd backend
mvn clean test

cd ../frontend
npm test -- --run
npm run build
npm run test:e2e
```

All automated EPIC 21 gates are now green. EPIC 21 remains open only until the ODK Collect and KoboCollect checklists are executed against a reachable deployment and recorded in the compatibility execution log.
