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

## Release gate

Keep PR #28 Draft until Android importer behavior, deletion policy, approval semantics, integrity endpoint, reference-data lifecycle and project authorization are qualified. Do not merge, deploy, publish signed APKs, or use live household data on the basis of backend CI alone. Physical device testing remains deferred at owner direction.
