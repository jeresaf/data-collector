# EPIC 21 — Browser E2E Certification

This browser suite exercises the real Vue/Vite application with deterministic API fixtures intercepted by Playwright. It does not require a seeded backend database, so failures remain focused on browser routing, component rendering and user-flow wiring.

## Coverage

The suite currently certifies:

1. Projects render from the API and can be opened through the UI.
2. Project Detail exposes Forms, Submissions, Reference Data, Reports and Audit tabs.
3. Project Detail requests submissions with the selected `projectId` rather than loading every project submission.
4. Submission list rendering and navigation to Submission Detail.
5. Desktop and mobile-Chromium layouts use the same flows.
6. The mobile-first web form can start a session, answer a JavaRosa-backed question, navigate to end-of-form, submit, and return to the submissions view.

## One-time setup

From `frontend/`:

```bash
npm install
npx playwright install chromium
```

`npm install` updates `package-lock.json` after adding the pinned Playwright test dependency.

## Run the frontend certification gates

```bash
npm test -- --run
npm run build
npm run test:e2e
```

To see the browser:

```bash
npm run test:e2e:headed
```

## Failure artifacts

The Playwright configuration retains traces, screenshots and video on failures. Generated reports/results should not be committed unless intentionally retained as certification evidence.

## Final EPIC 21 gate

Browser E2E passing does not replace the physical-device compatibility checks. EPIC 21 can close only after the ODK Collect and KoboCollect checklists are executed against a reachable test deployment and their results are recorded in the compatibility execution log.
