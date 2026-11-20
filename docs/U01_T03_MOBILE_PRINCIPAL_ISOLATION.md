# U01-T03 — Typed Mobile Principal Isolation (Draft)

**Date:** 20 November 2026  
**Code branch:** `codex/u01-t03-mobile-principal-isolation`  
**Base:** qualified backend Draft #22 (`codex/m3d4c2b2b2c3-restricted-witness-retrieval`), NOT `main`.  
**Status:** implementation in Draft; H2/PostgreSQL and full exact-head CI required. **No feature PR merge or production activation.**

## Problem identified during U01-T03 audit

`OpenRosaBasicAuthFilter` previously used the shared `UserDetailsServiceImpl.loadUserByUsername`, which searches web `users` **before** mobile `app_users`. `CurrentUserService.getCurrentUser` also searched web identities before mobile by username. If both tables contain the same username, the Basic request may authenticate the wrong principal or fail an otherwise valid mobile account. Worse, a shared username is insufficient evidence that web/mobile users are the same person.

This is **separate from** the documented `main` CI 400 test failure. PR #2 already fixed that test fixture by clearing stale Spring Security contexts, as shown in its exact source diff. This Draft must not undo those fixes.

## Bounded implementation

1. `MobileAppUserDetails` is an immutable `UserDetails` identity carrying the **UUID** of the authenticated `AppUser`, plus username/hash and exactly `ROLE_APP_USER`; no web roles are inherited.
2. `UserDetailsServiceImpl.loadAppUserByUsername` searches **only `AppUserRepository`**, checks current `isActive` and never falls back to `UserRepository`. It is called **only by OpenRosa Basic filter**. The existing shared web/JWT lookup remains available to web auth.
3. `OpenRosaBasicAuthFilter` verifies mobile password against that mobile-only lookup. The JWT filter and Spring Security challenge behavior remain separately managed.
4. `CurrentUserService.getCurrentUser` checks the typed mobile principal, reloads the mobile user by **UUID**, verifies the username and active status and returns only that AppUser; no web username lookup occurs. `getCurrentWebUser` explicitly refuses a typed mobile principal.
5. Legacy plain `UserDetails` paths still use existing web/JWT behavior, to limit compatibility risk. This slice does **not** fully certify web JWT tokens created before a same-name App User was enrolled; JWT issuer/principal claims and authorization separation need a distinct gate.

## Security regression coverage

- Unit tests for **same username** web/mobile accounts: mobile-only lookup never calls web repository; no web password authenticates as mobile.
- JPA/current-user tests for typed App User UUID, revoked current account, swapped/renamed name and `getCurrentWebUser` non-impersonation.
- Real MockMvc HTTP tests under H2 for a colliding `users.username` and `app_users.username`: `GET /formList` works with the assigned mobile password and returns 401 for the distinct web password.
- Preserve existing unassigned/invalid/revoked app-user, OpenRosa Basic syntax, submission behavior and full H2 and PostgreSQL 15 real HTTP source-preservation tests.
- Dedicated workflow `.github/workflows/u01-t03-mobile-principal-isolation.yml`; broad Quality Gate must be green on the **same exact head** before reporting CI qualification.

## Non-goals / blockers

No new endpoint, privilege or database migration; no form answer disclosure, mapping-grant issuance, positive copy or protected submission enabled. This **does not approve integration** of Draft #1–#22 or owner-deferred physical Android tests. Independently verify web JWT login role collision and project assignments with real signed tokens before production rollout. Default-admin P0, protected form life-cycle and physical Android acceptance remain release blockers.

**Documentation root:** `docs/ROADMAP_2026_REBASELINE.md` in [roadmap Draft PR #23](https://github.com/jeresaf/data-collector/pull/23); proposed integration order `docs/U01_T04_PROPOSED_INTEGRATION_ORDER.md`.
