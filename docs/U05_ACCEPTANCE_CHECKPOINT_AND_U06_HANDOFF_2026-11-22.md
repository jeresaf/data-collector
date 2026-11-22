# Data Collector — U05 acceptance checkpoint and milestone handoff

**Checkpoint date:** 2026-11-22  
**Working branch:** `codex/u05-t01-typed-entity-validation`  
**Review:** [Draft PR #28](https://github.com/jeresaf/data-collector/pull/28)  
**Verified implementation HEAD before checkpoint:** `77bf79f516e4727e09fd18c230da883201ae64c3`  
**Quality gate:** [CI #44964304472](https://github.com/jeresaf/data-collector/actions/runs/44964304472) — **SUCCESS** (backend H2, frontend unit/build/Playwright, PostgreSQL Testcontainers).

## Decision

**U05 Entity Lists: PARTIAL / RELEASE-BLOCKED.** The prototype safeguards, PostgreSQL schema-contract tests, and custody authorization experiments are **verified for their disposable test environment**. That does **not** qualify the Entity List integration for production use. Freeze feature expansion of the experimental custodian workflow except for a demonstrated correctness or security defect. Keep PR #28 in **Draft**, and do not merge, activate, or deploy experimental removal features.

The design must not be mistaken for an app-level end-to-end implementation: the custody code is unregistered; no production tables or live permission changes were authorized.

## Evidence already achieved

| Area | Scope of verified evidence | Gate |
|---|---|---|
| Entity List typed validation and snapshot/ledger contract | Backend contract and integration checks on this working branch | Tested, not released |
| Independent reviewer/executor roles | Delegated role guard with transaction locks, revocation/denial checks | Tested, not released |
| Role-grant administration | Atomic grant/revoke/regrant with immutable transition audit | Tested, not released |
| Administrator custody | Separate custodian appointment/revocation prototype with atomic audit | Tested, not released |
| Three-person custody proposals | Proposer plus two independent witnesses; evidence only | Tested, not released |
| Readiness receipt | Separate validator, 24-hour expiry, one-use immutable receipt | Tested, not released |
| Custody executor | Separately delegated execution officer, single-use atomic state+audit, minimum four active custodians | Tested only in disposable schema |
| Concurrency/recovery | Row-lock ordering, denied revoked actors, interrupted uncommitted transaction rollback, one-use races, unknown commit acknowledgment reconciliation | PostgreSQL Testcontainers passed |
| Two recovery readers | Two independently constructed JDBC clients observing rollback and subsequent retry | Same-process only; not multi-host |

The CI above verifies **the exact stated commit**, not future changes. A green test suite cannot authorize production schema additions or role administration.

## Blocking acceptance criteria (no implicit waivers)

**U05-REL-01 — Trusted authority root.** Identify the human/system owners who can provision the first custodians and execution officers; specify out-of-band bootstrap, rotation, revocation, two-person emergency recovery, and sign-off. An existing custodian quorum cannot be its own trust root.

**U05-REL-02 — Production-ready identity and consent.** Bind all actor roles to real active identities, scoped project/form grants, cryptographically authenticated approvals, authority changes and expiry. Verify all admin operations against the actual application security context.

**U05-REL-03 — Approved production schema and API.** Separate schema migration and HTTP controller review; roll-forward/rollback, audit retention, concurrency behavior, and zero implicit activation. No test DDL promoted without design review.

**U05-REL-04 — Actual Entity publish/removal protocol.** Wire approval consumption to exactly one authorized Entity List publication/removal transaction, resolve import revision identity, build tombstones and offline semantics, and verify idempotence and audit of retries. Custody approval alone is not an Entity deletion authorization.

**U05-REL-05 — End-to-end interoperability.** Check real ODK Collect 2026-compatible client behavior: list downloads, offline sync, form version changes, amended records, separate revisit records, media/repeats, sync conflicts. Physical-device acceptance remains explicitly **deferred** until authorized.

**U05-REL-06 — Fault injection and rollout.** Test actual independent worker processes, dropped commit acknowledgments, DB interruption/failover, revocation races with production writers, rollback/retry and distributed reconciliation. Collect sanitized evidence and an explicit release witness approval.

## Milestone numbering and handoff

Use the already discussed high-level stream rather than inventing new U05 submilestone numbers:

| Milestone | Scope | Checkpoint interpretation |
|---|---|---|
| U01 | Authentication and security baseline | Prior work exists; **re-verify evidence** before declaring closed |
| U02 | Configurable backend URL and generic Collect build | Prior work exists; **re-verify latest client/base evidence** |
| U03 | Edit existing records with later form versions; second visit is a distinct record | **Acceptance/reconciliation needed**; do not mark done by inference |
| U04 | Repeats, media and attachment fidelity | **Acceptance/reconciliation needed** |
| U05 | Typed Entity Lists and controlled lifecycle | **Prototype evidence green, production blocked** — this checkpoint |
| U06 | Form version compatibility and migration behavior | **Next bounded planning/qualification workstream**; verify existing code and tests first |
| U07 | Authorization integration | Pending production-wiring and acceptance audit |
| U08 | Retry, idempotence and synchronization | Partial related U05 test evidence; overall acceptance pending |
| U09 | PostgreSQL/Android E2E, physical acceptance | Physical tests deferred; do not infer pass |

**Next execution task:** Create an **independent U06 discovery/acceptance slice** (not another U05 custody expansion) against the actual server and Android branches. First inventory implementation/CI for the original vs upgraded XLSForm, deleted/renamed question paths, repeat/media preservation, edits of old submissions, and additional visits. Define evidence-backed pass/fail and required regressions. Do not merge U05 to unblock this work; a separate branch from the agreed safe baseline is appropriate.

## Safety and change control

- This checkpoint is documentation and evidence classification only.
- PR #28 remains Draft and **unmerged**.
- No production DB migration, deployment, Entity List deletion/tombstones, live privilege provisioning, APK release, or production data modification.
- Missing release approvals are **BLOCKED**, never silently marked PASS.
