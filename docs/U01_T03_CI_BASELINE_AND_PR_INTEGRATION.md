# U01-T03 — Baseline CI and Parallel-PR Integration Evidence

**Evidence date:** 20 November 2026  
**Scope:** Backend repository `jeresaf/data-collector` (`main`, standalone OpenRosa PR #1, fixture reconciliation PR #2, Draft stack tip PR #22, standalone roadmap PR #23).  
**Status:** **PARTIAL — RED `main` baseline must be reconciled; no feature branch merged.**

## PR topology and duplicate code

- Original backend `main` is the base of independent [Draft PR #1](https://github.com/jeresaf/data-collector/pull/1) (ODK 2026 OpenRosa tests) and [Draft PR #2](https://github.com/jeresaf/data-collector/pull/2) (R0 browser/fixture reconciliation). PR #3–#22 are then stacked sequentially on PR #2 and later.
- The five `odk2026*` tests introduced by PR #1 in `backend/src/test/java/org/odk/platform/security/OpenRosaBasicAuthIntegrationTest.java` are **all present** in PR #22's Draft branch.
- PR #1 and latest PR #22 **have identical test file blob SHA `c7fccfbeacbb3a67e374ae4ca382d9dce5d72fd2`**; `main` blob is `24b4ce415d15e90471e4081744bccc56b3695795`.
- PR #1 and PR #22 also share identical `docs/ODK_COLLECT_2026_COMPATIBILITY_CONTRACT.md` blob SHA `3d3e4275cfe52b934e7e40f83c8328da319d372a`.
- Their `.github/workflows/odk-2026-contract-qualification.yml` differs only by an extra `codex/m2-odk2026-integration-gate` branch trigger in the later tip.

**Integration decision:** DO NOT blindly replay/reapply PR #1 onto the #2–#22 tip; source/contract payload is already represented. Before closing/rebasing PR #1, verify actual Git commit ancestry/patch identity and preserve PR reviewer audit provenance.

## Documentation-only roadmap PR #23 baseline CI failure

Draft [PR #23](https://github.com/jeresaf/data-collector/pull/23) was intentionally branched directly from **`main`** and changes **documentation files only**, including `ROADMAP.md` and `docs/U01*.md`. Its [CI Quality Gate #44769998562](https://github.com/jeresaf/data-collector/actions/runs/44769998562) **FAILED** at exact SHA `6e59e9f04ad058a8f3cebe5cce43b7f41bd7eaa2`, with two independent failed jobs:

| Job | Observed failure | Interpretation |
|---|---|---|
| Backend H2 suite | `608` tests, `1` failure. `OpenRosaBasicAuthIntegrationTest.compatibilitySubmissionPostEndpoint_WithValidBasicAuth_ReturnsCreated` at line 608: expected HTTP `201` but received `400`. | Existing `main` code/fixture baseline, not an intentional backend change in roadmap PR. Needs fixture/handler reconciliation and exact-head rerun. |
| Frontend browser Playwright | `4` failures across desktop/mobile `web-form-advanced` and `web-form-mobile-first`; `toBeVisible()` locator timed out after 5 seconds, including retries. | Existing `main` web-form acceptance fixture/baseline, distinct from documentation changes. Test selectors/fixture/actual runtime need validated repair. |
| PostgreSQL Testcontainers | Skipped after upstream failures. | **NOT QUALIFIED** at PR #23's failing exact head. |

Do **not** report the roadmap PR CI as green; failures are real. Documentation files are not runtime code, so these are *baseline defects observed by a documentation branch*, but a reproduction against `main` or isolated corrective PRs is still required before proving root cause.

### Already available corrective branches / source comparisons

- PR #2, `codex/r0-playwright-fixture-reconciliation`, changes both `frontend/e2e/web-form.spec.ts` and `frontend/e2e/web-form-advanced.spec.ts` against `main`. Exact frontend blob hashes at latest backend Draft tip match PR #2:
  - `web-form.spec.ts`: main `affdc374ac5fee37a55fb2039ed3277a17d530e9`; PR #2 + tip `c863cb02103d67d8f4389884a04bac2cbb3fd350`.
  - `web-form-advanced.spec.ts`: main `155b2e3b0301602aac8d1b8161a706802941b325`; PR #2 + tip `2cdd7e4dc7f06e0485c65ce4fc3858c10d9dcfe2`.
- The backend Draft tip #22 at `007fd511270b02846ebcb853ca7340a9c726151e` passed both [focused H2/PostgreSQL 15 workflow #44764143723](https://github.com/jeresaf/data-collector/actions/runs/44764143723) and [full quality gate #44764142857](https://github.com/jeresaf/data-collector/actions/runs/44764142857) on the previous exact head. Those successes **do not transfer automatically** to a separate `main`-based documentation PR.
- PR #2 also changes `OpenRosaBasicAuthIntegrationTest.java` from main blob `24b4ce41...` to `e654c646...`; the specific one-test HTTP 201→400 failure must be traced through fixture initialization and subsequent integration changes, not "fixed" by simply changing the expected status to 400.

## Exact backend `main` 400 cause and identity-separation review

A full inspection of the failed [Backend H2 job #136407412769](https://github.com/jeresaf/data-collector/actions/runs/44769998562) shows the actual `MockHttpServletResponse` **body** of the failing `POST /submission`:

```xml
<OpenRosaResponse xmlns="http://openrosa.org/http/response">
  <message>No project context available for this user</message>
</OpenRosaResponse>
```

This is **not evidence of bad XForm validation**. The compatibility alias `OpenRosaCompatibilityController.getAppUserProjectId()` rejects the request **before** invoking `OpenRosaSubmissionService.receive`: it requires the security-resolved current principal to be an `AppUser` and to have an `ACTIVE` assignment to an active project. The test `ensureOpenRosaFixture` explicitly uses JDBC to create an `appuser` and an active assignment; the discrepancy must be isolated, not bypassed.

Two source-level identity hazards deserve regression coverage:

- `UserDetailsServiceImpl.loadUserByUsername` (in the latest backend tip) loads a **web user first**, then an App User only when no web user with the same username is found. `OpenRosaBasicAuthFilter` uses this shared details service, even for mobile OpenRosa Basic credentials.
- `CurrentUserService.getCurrentUser()` also looks up a **web user first** by the `UserDetails` username before trying `AppUserRepository`. Thus the existing implementation does **not** strongly bind the resolved principal type to the authenticated login type when usernames collide between the two tables. This is a **potential cross-identity/security regression** to test under **U01-T03 / G03**; it is **not yet proven** to be the cause of this CI failure.

**Required qualification:** test collision where `users.username == app_users.username`, independent passwords and roles, revoked/disabled mobile account, missing/active/multiple project assignments, and both JWT web and OpenRosa Basic entry points. Authentication must never resolve a Web User for a mobile App User session or vice versa; the security context needs a genuinely typed authenticated identity. Do not "fix" the 400 by bypassing assignment checks or accepting ambiguous identities.

### Actual browser failure locators

[Frontend job #136407413143](https://github.com/jeresaf/data-collector/actions/runs/44769998562) reports **16 passed / 4 failed**. The desktop repeat-flow test fails at `web-form-advanced.spec.ts:84`, waiting for `getByText('Instance 1 of 1')`, despite the test heading `Repeat Acceptance` being visible; the mobile version fails the same flow. The two other failures are the desktop/mobile `web-form.spec.ts` finish-and-submit flow.

PR #2's changed fixture files are present byte-for-byte in the qualified Draft tip. Before any patch, compare fixture setup/expected instance states with the live runtime instead of lengthening timeouts or deleting checks. The green Draft stack and red `main` PR must be qualified on a **reviewed integration candidate**, not conflated.

## U01-T03 next actions

1. Before merging or closing anything, establish exact ancestry/patch IDs for PR #1 versus the #2–#22 linear stack, and confirm whether any CI metadata needs preserving when consolidating. A shared blob does **not** prove identical full branch ancestry.
2. Reproduce the isolated `main`-baseline test failures with the exact same runner/test setup; isolate fixture or production handler bugs, then apply evidence-based fixes in a **separate, clearly scoped development PR**, or integrate the already qualified corrective stack after owner authorization.
3. Review actual old production schema data and Flyway migration history before integrating V3–V7; test additive migration and rollback using sanitized copies, not production database mutations.
4. Lock one backend/Android compatible SHA pair after the old `ubos-local` feature delta has been assessed; run end-to-end OpenRosa form download, records, amended/follow-up identities, protected drafts, media and upload bypass negative tests.
5. **No automated merge** of 44 modernization Draft PRs, no rebase/force-push losing audit, no APK release or production rollout.

**Current verdict:** U01-T03 **OPEN / integration risk identified**. #23 CI failure is transparently recorded, and current green Draft feature-tip CI does not resolve the unintegrated `main` baseline.
