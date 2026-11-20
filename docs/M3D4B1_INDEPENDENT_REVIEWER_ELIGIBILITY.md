# M3D4B1 — Independent reviewer eligibility (NO mapping approvals issued)

Date: 2026-11-20
Backend branch: `codex/m3d4b1-independent-reviewer-eligibility`
Status: **Draft / CI pending**. Stacked on M3D4A Draft PR #7.

## Goal

Prevent an Android enumerator from treating their own encrypted field-mapping review as a server-authorized migration. This slice is **reviewer eligibility only**; it does **not** create, sign or persist a permission to read, copy or submit historical answers.

### Read-only endpoint

`GET /api/projects/{projectId}/record-work-items/{workId}/typed-review-eligibility`

- Requires a genuine authenticated **web Bearer JWT** principal. Mobile OpenRosa Basic authentication is not accepted, even for an assigned AppUser.
- Resolves the current web user from the `users` table using the authenticated username. Existing JWT subjects only contain usernames, so ambiguous identities colliding across `users` and `app_users` are explicitly denied.
- Checks fresh active/verified web-user, active project, **active** `project_members` assignment, active role and `PROJECT_MANAGER` or `PROJECT_ADMIN` role scoped to this project. No implicit global ADMIN or SYSTEM_ADMIN bypass.
- Requires a READY **AMENDMENT** with an immutable source instance ID, still-associated original submission form version, exact persisted historical XForm, same source/target form, published target and nonempty target definition.
- Refuses obvious duplicate web/mobile human identity across username or email. This is not a substitute for authoritative identity proof across systems.
- Returns only project/work/reviewer identifiers, `ELIGIBLE_TO_REQUEST_REVIEW_ONLY`, `eligibleToRequestIndependentReview=true` and **hard false** for `canApproveMapping`, `canTransfer`, `canSubmit`. No historical answers, raw XML, field values, form contents, or approval tokens.
- Missing/revoked project membership, stale work, unknown schema and independent follow-up remain non-eligible. Non-members receive no work metadata.
- No POST/PUT/PATCH, review records, server grant issuer, rule audit ledger, schema migration or change to OpenRosa submission is introduced.

## Identity nuance

The current JWT authentication service uses a Spring Security `UserDetails` principal rather than an ORM `User`. The service must resolve fresh trusted web identity in SQL and never cast JWT principal to the JPA domain class. The token's username-only subject is an existing cross-account naming risk: this slice denies web/mobile username or email collisions, and a future identity-hardening phase should use immutable principal type and subject ID claims.

## Synthetic qualification

The existing Spring HTTP integration suite (run on both H2 and production Flyway PostgreSQL 15) now includes a token from the actual JWT service, actual project manager membership and revoked membership handling. Tests assert 200 with deny-only permissions for eligible web reviewer; 401/403 for anonymous/mobile Basic; 404 after role revocation or for FOLLOW_UP; no POST grant. The preceding M3D4A test suite also runs and preserves non-owner and original-schema behavior.

## Mandatory next steps

1. M3D4B2: trusted signed reviewer decision **request**, with server-side least-privilege, separate from the enumerator and from the ultimate authorizing signer; explicit semantic rule rationale, target scope, bounded expiry and independent approver.
2. M3D4C: append-only server audit ledger and distinct revocation. Requires a new separately qualified immutable Flyway migration, H2 parity and production upgrade rehearsal; do not edit V1 or V2.
3. End-to-end grant enforcement at every source access, native first-save and final submit. Keep migration disabled until this is validated against exact source/target XForms and actual work identity.
4. Production security hardening (especially default admin bootstrap), project-role reassignment abuse analysis, authenticated owner delegation, cross-human identity assurance, and physical Android/Keystore tests.

No production data or database migration changed. No PR merged or deployed. An eligible reviewer in this milestone **cannot approve a transfer**.
