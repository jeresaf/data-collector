# U06-T03 — authenticated real-HTTP recovery contract, PostgreSQL 15

**Dependency:** Draft PR #30 U06-T02, exact head `7c029eded1fecf4fa18517b56fde470b08089669`, [CI #45158378181 SUCCESS](https://github.com/jeresaf/data-collector/actions/runs/45158378181). Android baseline is Draft PR #42 at `1a5b9e17bd11314107dbd24ed37da0ead3af12e8`, [emulator CI #45169361936 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45169361936).

## Boundary under qualification

The PostgreSQL 15/Flyway integration test `PostgreSqlContainerMigrationIT.authenticatedHttpV1ToV2AmendmentPreservesIndexedRepeatsOnPostgreSql` starts a real embedded Spring Boot servlet listener on a random loopback port. A standard Java `HttpClient` with redirects refused makes actual TCP requests to `http://127.0.0.1:<random>/v1/projects/<synthetic project>`. This is **isolated HTTP-loopback testing on CI**, not a production URL or a change to Android's HTTPS-only project policy.

The test provisions synthetic owner credentials and two published versions of one household form in an ephemeral PostgreSQL Testcontainer. An authenticated late-V1 submission with repeated children and an image is amended to V2 with a separate source/replacement and immutable historic media. It then tests:

1. Authenticated GET `/records` returns both historical V1 (`amendmentSuperseded=true`) and current V2 (`amendmentSuperseded=false`) in actual on-the-wire serialized JSON.
2. Unauthenticated GET on the same listener returns **401** with no historical answer values or instanceID in its body.
3. Authenticated POST `/record-work-items` against already-amended V1 returns **409** through real servlet security without inserting a new work row.
4. Authenticated POST against current V2 returns **201** and a distinct server-owned work ID, whose exact-source `prepare` and later amendment-submission tests complete successfully.
5. Existing MockMvc checks continue to cover stale offline work, independent FOLLOW_UP and indexed repeat/attachment preservation, all against the same production Flyway schema.

This is a modest increment from MockMvc to **real HTTP for a subset of the backend reconciliation lifecycle**. It is **not** Android-to-backend E2E: the Android CI still uses synthetic network responses, and no HTTPS gateway/emulator-bridged TLS or UI-driven old-draft reconciliation has been established. Physical Android tests remain deferred. Missing trusted typed answer mapping, release signing, production rehearsal, server multi-instance stress and lost-ack recovery block release.

## Acceptance
Qualify only after the new exact-head CI passes the H2, frontend and PostgreSQL 15 jobs (including real listener test). Keep all PRs draft and unmerged; no production data or devices.
