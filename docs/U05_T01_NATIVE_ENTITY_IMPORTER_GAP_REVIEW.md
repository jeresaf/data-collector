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

## Release gate

Keep PR #28 Draft until Android importer behavior, deletion policy, approval semantics, integrity endpoint, reference-data lifecycle and project authorization are qualified. Do not merge, deploy, publish signed APKs, or use live household data on the basis of backend CI alone. Physical device testing remains deferred at owner direction.
