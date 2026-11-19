# M3C — Exact historical source XForm for cross-version work preparation

Date: 2026-11-20. Status: Draft, validation pending.

Adds a nullable `sourceXFormXml` field to the authenticated project-scoped
`GET /v1/projects/{projectId}/record-work-items/{workId}/prepare` JSON response.
Existing fields and endpoint semantics are unchanged.

The server loads the historical `form_versions.xml_content` referenced by the
owner's source submission, **not** the target or current XForm, and only if the
version's form and project match the source record. The server validates the
active App User, source ownership, source form assignment, active project, READY
work state and authorized target version before examining historical content.

An imported record without a source form-version association, missing original
version or version that no longer has XML gets `sourceXFormXml: null` explicitly.
Android must then **block cross-version answer mapping**. There is no
fallback to a newer questionnaire; a mapping manifest cannot be inferred by
field-name coincidence.

The archived original form definition is accessible only through the same
owner-authorized preparation endpoint. This is not a public form catalog. No
changes to household data, work state, record links, Flyway, source ownership,
permissions or OpenRosa submission behavior.

Integration tests use ephemeral, synthetic PostgreSQL 15 and H2 databases to
check exact source XML, distinction from target XForm, and nullable legacy
source version. Physical Android acceptance is deferred but required before
release. No production deployment or migration.
