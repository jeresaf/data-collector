# U05-T01 — Native ODK Entity List importer gap review

Status: **PARTIALLY QUALIFIED — NOT RELEASE READY**  
Scope: `jeresaf/data-collector` Draft PR #28 and `jeresaf/collect` branch `codex/m4c2b3c3b3b-native-typed-value-proof`.  
This is a source review; it is not evidence of an Android instrumented or device execution.

## Source observations

1. Android `collect_app/src/main/java/org/odk/collect/android/formmanagement/ServerFormUseCases.kt` treats any non-null `MediaFile.type` as an Entity List, selects the list name using `filename.substringBefore(".csv")`, compares the manifest hash against the cached list hash and downloads the payload when changed.
2. Android `entities/src/main/java/org/odk/collect/entities/LocalEntityUseCases.kt` expects `name`, `label` and `__version` CSV columns. It parses `__version` as an integer and uses `name` as entity identity. Existing ONLINE entities absent from the new CSV are deleted when a list updates; do **not** equate reference-data refresh with harmless app metadata caching.
3. The same importer differentiates approval lists using `MediaFile.Type.APPROVAL_ENTITY_LIST`, marking the cached list as requiring approval. The XML `approvalEntityList` attribute parsing path and exact client compatibility still require an Android parser-level regression test.
4. Deleted **offline** entities are reconciled only when the manifest `integrityUrl` exists, through `EntitySource.fetchDeletedStates`. The backend currently produces typed manifest entries with `filename`, `hash`, and `downloadUrl`, **without `integrityUrl`**. Therefore full deletion reconciliation is not demonstrated.
5. The backend emits Entity Lists only for explicitly declared candidate media validated for CSV structure, canonical unique IDs, positive versions, size and recorded exact-byte MD5. The OpenRosa download route rechecks and snapshots bytes, but this is not an atomic snapshot between a manifest request and a later download. A mismatch causes fail-closed rejection rather than a stale success.
6. Existing web-side `EntityListDatasetService` CSV is `name,label,<properties>` without `__version`. Its output must **not** be relabeled as native typed Entity List data until transformed and tested.
7. Dataset ID/CSV filename controls list identity; a rename can create a separate local list. Define a stable list identity policy and explicit rename/revocation semantics before production.

## Next acceptance matrix

| Case | Required evidence | Current disposition |
| --- | --- | --- |
| Manifest parser maps `entityList` and `approvalEntityList` | Native parser unit test using server-shaped XML | BLOCKED |
| Exact CSV download/import | Android importer test from server-shaped manifest and bytes | BLOCKED |
| Updated versions overwrite correct ID and preserve intended local changes | Native repository-based update tests with version 1 → 2 | BLOCKED |
| Missing ONLINE rows trigger documented deletion | Explicit owner-approved deletion test | BLOCKED |
| Deleted OFFLINE reconciliation | Valid authenticated `integrityUrl`, server tombstones and client test | BLOCKED |
| Project/form assignment revoked between manifest and download | HTTP regression for both endpoints | BACKEND TESTED |
| Hash mismatch and mutated storage payload | HTTP tests assert no stale typed download | BACKEND TESTED |
| Approval Entity List cache flag | Native manifest parsing + import assertion | BLOCKED |
| Large dataset and empty-list deletion semantics | Bounded-memory/size and deletion-policy tests | BLOCKED |
| Independent periodic admin-unit refresh | Project-scoped reference version lifecycle + native cache refresh | NOT IMPLEMENTED |

## Inactive deletion-diff review helper

`EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(...)` compares the canonical entity identifiers in two **separately MD5-verified, structurally valid** CSV snapshots of the same explicit type **and exact canonical filename**. It also refuses transitions that regress the version of an entity retained in both snapshots. It returns an optional set of IDs missing from the newer snapshot. If either digest is wrong, either CSV invalid, or the types differ, comparison fails closed. The method is **not wired into the manifest, download route, reference update lifecycle, deletion approval, or any production action**.

This helper supports a future removal approval report; it cannot establish trusted historical snapshots, prove the client's currently cached inventory, interpret server tombstones, or make omitted IDs safe to delete. Until those controls and native importer behavior are qualified, an omission must not be interpreted as authority to delete a household or administrative entity.

## Declared row count cross-check

For explicitly typed datasets, backend manifest and download now reject a non-null `rowCount` that does not equal the number of parsed CSV data records (the header is excluded). Zero/negative counts are invalid. Missing `rowCount` retains compatibility with older qualified datasets. This limits accidental incomplete publishes **only when the uploader has provided a trustworthy count**; a maliciously or incorrectly revised `rowCount` can still match truncated data. A trusted immutable prior snapshot and explicit deletion authorization remain required before automatic refresh.

## Review-only snapshot evidence envelope

`EntityListSnapshotEvidence` creates a deterministic snapshot descriptor only after verifying typed CSV bytes, the recorded MD5 and a positive declared row count. The descriptor includes **project ID, form ID, canonical list filename, media type, monotonic revision, MD5, SHA-256 and row count**. Its review-only comparison recomputes both digests and denies cross-project, cross-form, cross-list, cross-type, stale-revision and altered-content transitions.

**Important trust boundary:** revision numbers and project/form identifiers are caller-supplied. This helper is NOT authoritative storage or proof of permission. An attacker or incorrect producer could construct internally consistent evidence for truncated content. Trusted snapshot persistence must assign revisions atomically, retain immutable original bytes and metadata, enforce per-project/form/list access, record creator/approval, and prohibit rewriting historical rows. No endpoint currently calls this helper, and no database schema or production data changes are introduced here.

Before publishing an `integrityUrl` or permitting any approved removal, establish an auditable transition ledger and deletion-decision lifecycle: proposed → reviewed → approved/rejected, with explicit owner and distinct verifier, maximum removal threshold, retention policy, and rollback/restore evidence. In particular, neither a merely missing CSV row nor an unapproved review report constitutes a server tombstone.

## Advisory transition review classifier (inactive)

`EntityListTransitionReview.inspect(...)` combines the verified snapshot envelope with a read-only classification of proposed changes. A trusted, valid transition with no missing IDs is reported as `NO_REMOVALS_OBSERVED` (**not** approved for publication). **Every** transition with missing IDs is labeled `REMOVAL_REVIEW_REQUIRED`; missing 10 or more IDs, or at least one quarter of the prior list, generates a `BULK_REMOVAL_REVIEW_REQUIRED` warning. These are diagnostic, deliberately non-configurable preliminary review thresholds, **not approval or denial limits**. Invalid or untrusted snapshot evidence produces no classification. The class has no storage, controller, tombstone or delete integration.

Authoritative ledger and deletion workflow remain pending: immutable project/form/list-scoped snapshots, trusted atomic revision allocation, append-only review decisions with identifiable approver and separation of duties, rollback records, and client-side native ingestion tests. A valid review report must never be treated as consent to publish, remove cached entities, or issue deleted-state responses.

## Inactive optimistic revision admission policy

`EntityListSnapshotRevisionPolicy.assess(...)` performs a **pure policy evaluation**, not an append. Given already verified evidence for a trusted current head and proposed bytes, it distinguishes `UNCHANGED` (exact descriptor retry), `READY_FOR_LEDGER_APPEND` (consecutive revision with no observed removals), `REQUIRES_REMOVAL_APPROVAL` (any missing IDs) and `CONFLICT_OR_UNTRUSTED` (stale/skipped revisions, scope/type mismatch, altered bytes or version rollback). It detects `Long.MAX_VALUE` revision exhaustion and does not expose an approval bypass. These statuses are *not* permissions to publish.

This is **not yet server-controlled revision assignment**. The head and proposed revision are supplied to the policy by its caller, and no database transaction, compare-and-swap lock, unique key, immutable byte archive or approval ledger currently calls or backs it. Production implementation must obtain the current head under an authorized project/list-scoped transaction, assign exactly `head.revision + 1` within that transaction, use a uniqueness constraint on (project, form, list, revision), persist original immutable bytes and chained digests, and reserve publication until an independently approved removal decision exists. Never trust a revision or authority supplied by an uploader.

## Unwired atomic ledger coordinator contract

`EntityListSnapshotLedgerCoordinator` now defines a narrowly scoped `Store` port with `currentHead` and **atomic** `compareAndAppend`, and a pure coordinator that derives candidate revision `head.revision + 1` rather than trusting a caller-provided proposal revision. It rejects uninitialized lists, invalid bytes/digests/counts, cross-list head conflicts and revision overflow; byte-identical retries return `UNCHANGED`, verified non-removal changes can request `compareAndAppend`, and every proposed missing entity results in `REMOVAL_APPROVAL_REQUIRED` without any append attempt. A test-only synchronized fake exercises concurrent proposals and verifies a single accepted append.

This is **NOT durable trusted history**. There is no production `Store` implementation, authorization, bootstrap, SQL locking, audit trail, approval path, endpoint or refresh integration. The fake is process-local and has no crash, restart, multi-node or transaction durability. An actual database implementation must atomically compare current revision and digest, reserve an immutable revision per project/form/list, keep payloads and evidence durable and append-only, reject race losers, and reconcile storage/database transaction failures. Crucially, mere `READY_FOR_LEDGER_APPEND` is not publication authority; the API must enforce independent authentication and permissions.

## Isolated SQL contract qualification (test only)

`EntityListJdbcLedgerContractTest` exercises the inactive coordinator through a **test-only H2 JDBC adapter**. It creates temporary `head` and append-only `history` tables inside an isolated in-memory database. A compare-and-swap `UPDATE head ... WHERE revision=? AND sha256=?` and historical `INSERT` execute in the same transaction; a failed CAS rolls back. Tests verify that concurrent proposals append exactly one new revision, old bytes remain readable, an identical retry does not duplicate the revision, and an unapproved removal never reaches SQL append.

**This is not a production migration, repository implementation, or proof of PostgreSQL durability and crash recovery.** It has no Spring bean, authorization, trusted initialization, secure immutable blob storage, approval/tombstone service, or restart evidence. Before release, repeat equivalent integration tests on PostgreSQL with separate connections/nodes, enforce project/form/list uniqueness and role authorization, preserve full audit metadata, and verify rollback after simulated transaction and storage failures.

## PostgreSQL Testcontainers ledger contract — pending exact-head CI

`EntityListPostgresLedgerContractIT` replicates the isolated ledger contract against a **disposable PostgreSQL 16 Testcontainer**, using a fresh per-test schema and native `BYTEA` snapshot payloads. It verifies conditional transactional head update plus append-only revision insert, concurrent requests, preserved original payload, idempotent retry, and rejection of unapproved removal. The explicit PostgreSQL CI test selection now includes this class.

This is a **test-only** SQL fixture, not a production repository, Flyway migration or deployment. It does not yet demonstrate process restart, multi-node service authorization, crash between object storage/database commit, approved deletion audit, or the native Collect integrity URL protocol. The first run on the exact commit must pass before PostgreSQL behavior is considered qualified.

## PostgreSQL transactional failure injection

The disposable PostgreSQL ledger contract now exercises a **history INSERT unique-key failure after a conditional head UPDATE**. The SQL adapter must roll back the complete transaction; the test checks that the head still points at the original revision and original bytes remain readable. This tests one real database failure mode, not power-loss durability or a crash between an external object-store write and SQL commit. No live tables, migration, deletion approval, or application endpoint is introduced.

## PostgreSQL scoped-CAS isolation qualification (pending CI)

The disposable PostgreSQL `Store` fixture now refuses a conditional append if the supplied `Key`, expected snapshot, and proposed snapshot disagree on **project ID, form ID or canonical filename**, or if the proposed revision is not exactly one beyond the expected revision. A negative test attempts forged cross-project, cross-form, cross-list and foreign-key appends, and checks that the original head and history remain unchanged. The production `Store` port documentation mandates independently enforcing this boundary, rather than relying on the coordinator alone.

This is a test-side adapter, not an authorization system. Real user/project permissions, project reassignment, secure bootstrap, immutable evidence history and a production PostgreSQL implementation remain open. A successful test does not authorize downloads or removals.

## Opt-in JDBC store (no production wiring)

`JdbcEntityListSnapshotStore` is a reusable JDBC implementation of the ledger `Store` contract, with conditional revision/hash CAS and historical revision insertion in the **same SQL transaction**. It is deliberately **not a Spring bean**, creates no tables, has no Flyway migration, and cannot bootstrap its own first revision. It expects separately provisioned `entity_list_snapshot_heads` and `entity_list_snapshot_history` tables. A new disposable PostgreSQL test exercises its own read/append/retry path, historical payload preservation, project lookup separation and denial of removals. To guard against direct store calls bypassing the coordinator, the JDBC adapter independently requires a validated `READY_FOR_LEDGER_APPEND` review before writing.

This is **not authorized production infrastructure**: the caller must still enforce permissions and trusted initialization, the backing tables require independently reviewed migration and immutable audit constraints, and disaster recovery/approval/tombstone integration are incomplete. The test creates tables only in a disposable PostgreSQL schema. Do not enable publication or reference refresh on this evidence alone.

## Candidate PostgreSQL schema constraints — test resource only

A **non-migrating** schema candidate now lives at `backend/src/test/resources/entity-list-ledger-schema-postgres.sql`. It proposes a composite project/form/list/revision history key; positive revisions and row counts; typed media, canonical filename, MD5 and SHA-256 format checks; a *deferred* head-to-history foreign key so conditional head CAS and revision insertion can commit together; and an append-only history trigger rejecting SQL UPDATE/DELETE. The new `EntityListPostgresSchemaContractIT` executes that candidate in a disposable PostgreSQL schema, tests the opt-in JDBC store against it, rejects attempted history rewrites/deletions, and verifies an orphan head cannot commit. CI explicitly selects this test.

The trigger protects against ordinary SQL writes in that database role; it does **not** prove protection from a superuser, deliberate DDL, backup loss, external object-storage failure, or compromise of the trusted administrative process. It is not an installed Flyway migration and does not establish signed approval, database role separation, startup/upgrade compatibility, recovery, or safe production activation.

## Opt-in first-snapshot initialization — pending CI

`JdbcEntityListSnapshotBootstrap` is a **non-registered**, explicitly constructed initialization component. It requires a caller-supplied authenticated actor ID and an authorization callback that must confirm permission for the exact project/form/canonical list key. It rejects invalid CSV, incorrect recorded MD5 or declared row count; assigns revision **1** itself, rather than trusting a client revision; and inserts immutable history and the corresponding head in one SQL transaction. PostgreSQL uniqueness conflicts are reported as `ALREADY_INITIALIZED` after rollback, without rewriting history. Disposable PostgreSQL tests cover denied actors, invalid evidence, a single authorized first write, repeated initialization, and multiple racing initializers.

This callback is **not itself a wired authentication system**. No live Spring Security identity lookup, tenant roles, project/form assignment revocation, tamper-evident actor audit, approval authority or deployment migration exists yet; the application never instantiates this class. Before production wiring, resolve the trusted principal from server context, use a centrally enforced project-scoped authorization service, record actor/reason/decision in immutable history, and validate privilege revocation inside the transactional boundary.

## Opt-in existing web-permission adapter — pending CI

`EntityListSnapshotWebAuthorization` now implements the earlier `JdbcEntityListSnapshotBootstrap.Authorization` interface using actual application interfaces. It requires the supplied actor UUID to match `CurrentUserService.getCurrentWebUser()`, a current active web user, an active project, a currently active form belonging to that exact project, and `WebFormAssignmentService.canManageProjectForms(project, actor)`. It deliberately grants no blanket mobile-account or ordinary form-assignment permission, and it does not infer administrator privileges from client input. Unit tests cover valid manager, spoofed actor, missing/inactive principal, missing project/form, and revoked manager rights.

**Not active:** This adapter has no `@Service`, no injected live bootstrap or new route. The caller-supplied actor still needs binding to trusted request context. The `canManageProjectForms` service is checked before opening a separate JDBC transaction, so a privilege change between check and commit is not yet prevented. A production bootstrap must revalidate under one authorized transaction/lock, capture an immutable actor decision and verify actual user/project role scope before activation.

## Optional same-transaction bootstrap permission gate

`JdbcEntityListSnapshotBootstrap.initializeWithTransactionAuthorization(...)` introduces an explicit opt-in callback that receives the **same JDBC connection and transaction** used to append the first historical snapshot and head. It runs after the existing web-user authorization preflight and before either insert; a rejected permission returns `DENIED` with no snapshot mutation, while an authorization SQL failure rolls the transaction back. A disposable PostgreSQL test demonstrates a permission revoked before the write and a locked `SELECT ... FOR UPDATE` recheck; another simulates a failing authorization query.

**Important remaining limitations:** the test permission table is synthetic, not the actual `projects`, `users`, `project_members`, `roles` and `xforms` permission graph. The default `initialize(...)` remains an inactive legacy preflight-only path. Nothing is wired to an HTTP endpoint. Production must make the transactional gate mandatory, derive actor identity from the actual security context, lock every relevant row (including revocation writers), and retain actor/decision audit in the same transaction. Merely adding a callback does not by itself prevent every revocation race.

## Real table permission gate — isolated PostgreSQL tests, pending CI

`JdbcEntityListSnapshotPermissionGate` implements the opt-in **transaction-scoped** authorization interface against real application SQL table names `users`, `projects`, `xforms`, `project_members` and `roles`. It requires a current transaction (`autoCommit=false`), locks the actor and active project/form using `FOR UPDATE`, and permits an active project owner/creator or an active project member with an active `PROJECT_ADMIN`/`PROJECT_MANAGER` role. The member/role path locks both rows with `FOR UPDATE OF pm,r`. It never trusts a role name or actor identity submitted by the client.

A disposable PostgreSQL test creates a **minimal table-shaped fixture using those application table names** and verifies denied membership/role revocations, inactive form/user, valid manager initialization, foreign-project form denial, inactive project and autocommit refusal. It does **not** yet run the new gate against the complete, seeded production Flyway schema and does not bind the actual request principal to this transaction. Owner/creator writes or role/membership revocations must obey a documented common lock order; immutable actor audit and signed approval are absent. The component is not registered as a bean, endpoint or deployed permission service.

## Full Flyway-schema authorization integration — pending exact-head CI

`PostgreSqlContainerMigrationIT.snapshotAuthorizationUsesFullMigratedProjectAndRoleTables` now exercises the opt-in `JdbcEntityListSnapshotPermissionGate` **inside the PostgreSQL Testcontainer where the actual production Flyway migrations and seed data run**. It creates an uncommitted test owner, project, form, user and manager membership against the actual schema, verifies owner/manager acceptance and foreign-project denial, then soft-revokes membership and the form and checks denial. All test fixture writes are rolled back on the same connection. This closes the earlier gap between simplified table-shaped tests and migrated schema compatibility.

This test does **not** install the Entity List ledger schema, activate the bootstrap, add an HTTP endpoint, prove cross-connection revocation races, or record immutable actor audit evidence. Actual request identity, lock-order enforcement across all revocation writers, approved production migration and deletion/tombstone decisions remain blocked.

## Opt-in immutable actor audit — exact-head CI pending

`JdbcEntityListSnapshotBootstrap.initializeWithTransactionAudit(...)` adds a **strict opt-in** entry point requiring both an in-transaction authorization callback and an in-transaction audit callback. `JdbcEntityListSnapshotAuditWriter` records the actor ID, project/form/list scope, server-assigned revision 1, SHA-256 digest and `INITIALIZED` event on the **same JDBC connection and transaction** as snapshot history and head. If audit insertion fails, the first snapshot is rolled back. The disposable PostgreSQL schema adds an audit table with a historical revision foreign key and append-only update/delete trigger. Integration tests cover denied initialization (no audit), successful initialization (one event), identical retry (no extra event), mutation rejection and audit write failure (no head/history). None of these components is registered as a Spring service or Flyway migration.

**Scope limit:** This provides successful-initialization evidence only, not a full append/review/denial audit trail or a cryptographically signed tamper-evident log. Rollback and rejection attempts are not represented in the same committed audit table. The actor still requires binding to actual request authentication and the real-row permission gate; the transaction uses test-only audit schema. A separate security event channel is needed for denied/invalid requests. No production DB, deletion approval, integrity protocol or Android client behavior is changed.

## Subsequent-revision actor audit (opt-in; pending exact-head CI)

`JdbcEntityListSnapshotStore.compareAndAppendAudited(...)` provides an **additional, inactive** append path with a required current actor UUID, same-connection transactional permission callback and same-transaction immutable `APPENDED` audit callback. The normal `Store.compareAndAppend` remains intentionally unaudited and unregistered; no runtime handler calls either method. The previously introduced `JdbcEntityListSnapshotAuditWriter` now handles both `INITIALIZED` (revision 1) and `APPENDED` (revisions >=2) records in the disposable audit schema. Approval-required deletions still fail closed via the existing revision policy. The candidate SQL audit constraint accepts only the two success event types.

New PostgreSQL integration tests exercise authorization rejection (no snapshot/audit), successful revision 2 append (exactly one actor/revision/digest event), stale retry (no duplicate event), append-only audit protection, and audit-table failure after head/history writes (full rollback). These tests do **not** prove trusted HTTP request identity, a production transaction-wide permission gate, immutable records of rejected/failed requests, a production Flyway migration, or deletion approval. The inactive `Store` interface still allows an unaudited method: **production must use a mandatory audited path and prohibit bypass** before registering any service.

## Audited revision publication facade — pending exact-head CI

`EntityListAuditedRevisionService` is a new **inactive/unregistered** wrapper for later production wiring. It derives the current actor from `CurrentUserService.getCurrentWebUser()` (not a supplied actor UUID), requires an active current web user and the opt-in `EntityListSnapshotWebAuthorization` manager policy, then invokes `EntityListSnapshotLedgerCoordinator.propose(...)` using a **scoped adapter** whose sole mutation method routes to `JdbcEntityListSnapshotStore.compareAndAppendAudited(...)`. That write requires an in-transaction `JdbcEntityListSnapshotPermissionGate` and `JdbcEntityListSnapshotAuditWriter`. A missing principal, inactive actor or denied preflight returns an invalid/denied result with no writes. Disposable PostgreSQL regression tests use a spy to confirm the facade never calls `JdbcEntityListSnapshotStore.compareAndAppend` and that successful revision-2 append records the actor exactly once; missing principal and management rights make no history entries.

**Caveat:** This creates a safer publication entry point, **not** an absolute database-level prohibition on the older public `compareAndAppend` method, which continues to exist for inactive contracts and tests. Production must make unauthorized/unaudited routes inaccessible through dependency wiring or remove them before activation. Some in-memory legacy qualification paths also remain unaudited; they are not release paths. Permission revocation across separate transactions and immutable denial records are still pending. No production service, endpoint or migration has been registered.

## JDBC unaudited append disabled — pending CI

The concrete `JdbcEntityListSnapshotStore.compareAndAppend(...)` now **always returns false without writing**. That closes the direct unaudited JDBC mutation path even when it is called via the generic `EntityListSnapshotLedgerCoordinator.Store` interface. Authorized publication must use `compareAndAppendAudited(...)`, which requires the actor, same-transaction authorization, and immutable audit callbacks. The inactive facade `EntityListAuditedRevisionService` already routes coordinator writes exclusively through that audited method. The generic `Store` port and separate test-only fake implementations retain their original behavior for contract qualification.

Disposable PostgreSQL tests were adjusted to require the audited method for legitimate revision writes, and explicitly verify the concrete plain method leaves the original head untouched. The separately constructed authorization/audit callbacks still need trusted production wiring and real role revocation race checks. This is not a production service or migration.

## Cross-connection permission revocation race — CI qualification pending

`EntityListPostgresRevocationRaceIT` adds two deterministic concurrency tests against **separate connections** to a disposable PostgreSQL database with actual-named project, user, form, role and membership tables plus the isolated test-only snapshot/audit schema. The first opens a revocation transaction, updates and locks the active membership row, starts an audited publisher on another connection, observes its PostgreSQL lock wait, then commits revocation; the publisher must deny without a revision or append audit. The second holds the membership lock during an authorized audited append; a revocation UPDATE on another connection must block until publication commits and can then revoke, preserving an exact audit trail. Both explicitly use `pg_stat_activity.wait_event_type = 'Lock'`, bounded timeouts, and cleanup.

**Scope limit:** the revocation writer is test-controlled SQL against minimal real-named tables, **not** application-level revocation service integration. The fixture is not the full production Flyway schema; those are independently tested by `PostgreSqlContainerMigrationIT`. These experiments establish lock behavior when revocation uses UPDATE on the same row but cannot prove every revocation/role reassignment path follows the lock protocol. No production migration, endpoint or mobile deletion behavior is active.

## Release gate

Keep PR #28 Draft until Android importer behavior, deletion policy, approval semantics, integrity endpoint, reference-data lifecycle and project authorization are qualified. Do not merge, deploy, publish signed APKs, or use live household data on the basis of backend CI alone. Physical device testing remains deferred at owner direction.
