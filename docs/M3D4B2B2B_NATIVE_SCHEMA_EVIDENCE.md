# M3D4B2B2B — JavaRosa native schema/type evidence (advisory, no grants)

Date: 2026-11-20
Backend branch: `codex/m3d4b2b2b-native-schema-proof`
Status: **DRAFT; exact-head H2, PostgreSQL 15 and broad CI required**.
Stacks on `codex/m3d4b2b2a-static-field-policy-gate` Draft PR #11 (qualified).

## Why this milestone exists

A field that appears identical under XML inspection may be parsed differently by JavaRosa 4.3.2. The client must not infer source-answer migration safety from an XForm `bind` string, Android operator review, or a reviewer recommendation. This milestone obtains an additional, independent **native runtime schema/type observation**, but still **cannot transfer or authorize historical values**.

## Endpoint

`GET /api/projects/{projectId}/record-work-items/{workId}/typed-native-candidates/{recommendationId}`

Only an active, independently authenticated web Project Admin can request the inspection. The existing static policy gate is called first; it enforces current JWT identity, separate reviewer/signer, READY same-form amendment, persisted source/target fingerprints, canonical reviewer recommendation digest and prior final denial veto. The native endpoint then refetches exact persisted historic and target XForms; a changed SHA-256 fingerprint rejects the request.

`JavaRosaFormLoader` parses two **fresh, distinct FormDef objects**, using the application's established global parser synchronization and module registration. The probe traverses each native main instance only for the already statically vetted paths and checks `TreeElement.getDataType()` against the static classifier and the other exact-version form. The accepted scalar set remains TEXT, INTEGER, DECIMAL and DATE. Ambiguous or repeated nodes, missing nodes, untyped fields, source/target type changes, static-native disagreements and parsing failures return blocked states.

The response includes only:
- project/work/recommendation IDs, originating reviewer and independent signer IDs;
- exact source and target XForm fingerprints;
- candidate field paths, static decision, native source and target type (when available), and **advisory state only**;
- `ADVISORY_NATIVE_SCHEMA_PROOF_ONLY_NO_GRANT` and **hard false** `canApproveMapping`, `canReadHistoricalAnswers`, `canIssueGrant`, `canTransfer`, `canFinalize` and `canSubmit`.

No historical **submission** XML, answers, attachments, draft values or target instance content is fetched, copied, returned or persisted. The endpoint is GET-only with `Cache-Control: no-store`. It is not a route usable by OpenRosa/mobile Basic authentication.

## Qualification

Focused `M3D4B2B2B JavaRosa Native Schema Qualification` GitHub Actions runs:
- independent JavaRosa two-version type tests, type change, unsupported types, missing paths and impossible rights;
- all earlier static XForm policy tests;
- real Spring HTTP work/review/signer tests, including no answer leak, reviewer/anonymous/mobile denial, final-denial veto and previous recommendation tamper detection;
- H2 and PostgreSQL 15 Testcontainers migrations and submission replacement regressions.

The repository's broader CI Quality Gate runs backend and frontend tests. Exact-head verification is mandatory; physical Android tests remain deferred, **not waived**.

## Critical exclusions / next milestone

This is **NOT complete semantic verification**, even when JavaRosa reports the same native type. Identical int/date/text types can represent different meaning, units, constraints, choice sets, calculated dependencies or consent scope. This probe never validates old values against target constraints or evaluates an entire target XForm instance.

Before any positive server grant or actual source-answer import:
1. Require separate, strongly verified reviewer and final authorizer identities; bind explicit field-level rationale and semantics, form versions, question text, unit/choice lists, restrictions and signed consent to the approval request.
2. Implement independent server-side time-bound **signed** field rule issuance and immutable/externally anchored audit, key custody, replay prevention, revocation, exact source/target hash binding and previous-final-denial veto.
3. Recheck server authorization at actual historical source retrieval, local protected first-save, and server submission intake; respect in-flight cancellation, expiry and post-save revocation.
4. Qualify historical data values through JavaRosa, atomic draft write, no-overwrite preservation and Android Keystore/native instance behavior. Complete media, repeat and complex control semantics separately.
5. Rehearse additive database migrations on real legacy Flyway histories, resolve default-administrator security P0, and perform device + real E2E tests.

No production migration, submission write, merge, APK signing, distribution, mobile transfer or source-record alteration is part of this milestone.
