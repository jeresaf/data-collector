# ODK-Compatible Spring Boot Platform — Strict Re-Audited Task Progress

**Re-audit date:** 15 August 2026

This tracker reconciles three sources: the original 279-row progress list, the latest 269-row audited tracker, and the authoritative revised task/specification with its acceptance criteria and required tests. It also incorporates the locally reported green work completed after the previous tracker snapshot (AUDIT-06 through AUDIT-10).

## Important Scope Correction

- The original condensed progress list contains **279 task rows**.
- The previous audited tracker contains **269 task rows**.
- **15 original task IDs disappeared** while **5 post-original task IDs were added**, and 24 IDs were retitled/repurposed, mainly in EPICs 19–21.
- This version restores the **original task IDs/titles** as the tracking backbone and keeps later corrective work under separate `AUDIT-*` entries.
- A task marked complete below means the implementation row itself is complete. Milestones may still remain partial when the authoritative specification requires additional browser, real-database, CI, or physical-client acceptance evidence.

## Status Legend

- ✅ Complete — implemented and supported by source/tests already reported green.
- 🔄 Partial — meaningful implementation exists, but the original/authoritative requirement is not complete end-to-end.
- ⏳ Not Implemented — required code/artifact is absent.
- 🧪 Manual Pending — code/automation exists, but physical/client certification remains.
- ⚠️ Corrective Gap — task/artifact drift discovered during this re-audit.

## Audit Summary — Original 279 Task Rows

- Complete: **265**
- Partial: **4**
- Not implemented: **1**
- Manual pending: **5**
- Corrective/artifact drift: **4**
- Additional specification-level acceptance gaps not represented by a unique original task row are listed separately below.

## Milestone Summary

| Milestone | Status | Strict audited state |
|---|---|---|
| M0 — Scope, Architecture, and Build Brief | ✅ Complete | Architecture, XML source-of-truth, OpenRosa and JavaRosa direction remain established. |
| M1 — Project Foundation | ✅ Complete | Backend/frontend/database/Docker/test foundation exists and local suites run. |
| M2 — Reference Documentation Setup | 🔄 Partial | Reference docs exist, but four legacy verification helper artifacts are absent from the current snapshot and the required CI documentation/file-presence gate does not exist. |
| M3 — Security and Access Control | 🔄 Partial | Security implementation and route guards are complete; strict acceptance still lacks a dedicated unauthenticated protected-route Playwright flow. |
| M4 — Project Management | 🔄 Partial | Project CRUD/settings/members UI is implemented; the authoritative required Playwright project-creation flow is still missing. |
| M5 — Form XML Upload and JavaRosa Validation | 🔄 Partial | XML upload/JavaRosa implementation is green; the authoritative Playwright XML-upload acceptance flow is not present in the current browser suite. |
| M6 — XForm Metadata Extraction | ✅ Complete | Metadata extraction and compatibility coverage are implemented and green. |
| M7 — Form Publishing and Assignment | ✅ Complete | Form lifecycle, publish history, mobile app-user assignment, browser user/role assignment, UI and access enforcement are green. |
| M8 — OpenRosa Form List and Download | 🧪 Manual Pending | Automated form-list/download contracts are green; physical ODK/Kobo form retrieval remains. |
| M9 — OpenRosa Manifest and Media Download | 🧪 Manual Pending | Manifest/media/hash automation is green; physical ODK/Kobo media download remains. |
| M10 — OpenRosa Submission Upload | 🧪 Manual Pending | Submission pipeline/validation/duplicate/replacement automation is green; physical ODK/Kobo submissions remain. |
| M11 — Submission Processing Pipeline | ✅ Complete | Raw XML, JSON, flattened answers, repeat paths and attachment-to-answer links are implemented and verified green. |
| M12 — Admin Submission Review UI | ✅ Complete | Submission review/detail fidelity, downloads, status APIs, frontend tests and current list→detail E2E are green. |
| M13 — Web Form Runtime MVP | 🔄 Partial | Runtime and controls are green, but the full user-facing form workflow shell from the authoritative UI spec (form catalog/start/review/success/drafts/submitted) is not found in the current snapshot/later artifacts. |
| M14 — Groups and Repeats | 🔄 Partial | Groups/repeats implementation is green; the specifically required Playwright repeat-form flow is still missing. |
| M15 — Advanced XForm Logic | 🧪 Manual Pending | Advanced automated logic is green; the specification still calls for selected manual comparison with ODK Collect. |
| M16 — Media and Geo Controls | 🔄 Partial | Media/signature/geo/barcode implementations are green; media/browser-device acceptance still lacks its dedicated Playwright flow and real-phone camera verification. |
| M17 — XLSForm Upload and Conversion | ✅ Complete | XLSForm upload/pyxform/JavaRosa/persistence/UI automated flows are green. |
| M18 — Generic Reference Data and Entities | ✅ Complete | Generic reference lists/entities, CSV and admin UI are green. |
| M19 — Generic Reporting and Exports | 🔄 Partial | Report engine/builder/CSV/XLSX are green; required Playwright report run/export coverage is missing. |
| M20 — Audit Trail | 🔄 Partial | Audit schema/UI and most lifecycle events are green; core user/app-user/global-role mutation events cannot be certified because those mutation endpoints are absent. |
| M21 — Testing and Compatibility Certification | 🔄 Partial | Automated suites are broadly green, but real-DB Testcontainers coverage, complete Playwright acceptance coverage, CI, and physical ODK/Kobo sign-off remain. |

## Highest-Priority Remaining Work

1. **AUDIT-12 / TASK 21.12 — CI quality gate:** add CI that runs backend tests, frontend Vitest/build, and Playwright gates.
2. **AUDIT-11 / TASK 21.2 — Real database integration:** add Testcontainers-backed PostgreSQL (preferred) integration certification instead of relying only on H2.
3. **AUDIT-13 — User web-form workflow:** add/complete user form catalog, start, review, success, drafts and submitted pages/routes around the already-green runtime.
4. **AUDIT-14 / TASK 21.9 — Browser acceptance coverage:** add protected-route redirect, project creation, report run/export, repeat flow and media flow tests.
5. **AUDIT-15 / TASK 20.4 — User/app-user/global-role lifecycle:** implement the missing administration mutation paths and emit/certify USER_CREATED, APP_USER_CREATED and USER_ROLE_CHANGED.
6. **Manual certification:** run physical ODK Collect/KoboCollect checklists plus selected advanced-logic comparison and real-device camera/media checks.

## Strict Specification Gaps Found in This Re-Audit

| Area | Finding | Why it remains open | Maps to |
|---|---|---|---|
| CI quality gate | CONFIRMED MISSING | No GitHub Actions/Jenkins/GitLab CI file is present; the authoritative specification requires tests to run in CI. | AUDIT-12 / TASK 21.12 |
| Real database integration gate | CONFIRMED PARTIAL | Testcontainers is part of the intended stack, but no PostgreSQLContainer/MariaDBContainer/@Testcontainers usage is present; current integration certification relies on H2/MockMvc. | AUDIT-11 / TASK 21.2 |
| User-facing form workflow shell | CONFIRMED PARTIAL | Direct web-form filling/resume works, but the specified /app/forms, start, review, submit-success, drafts and submitted user journey is not found in the current snapshot/later artifacts. | AUDIT-13 |
| Required Playwright coverage | CONFIRMED PARTIAL | Current 6 browser tests are green, but the authoritative spec also calls for protected-route, project creation, report run/export, repeat and media flows. | AUDIT-14 / TASK 21.9 |
| Report E2E certification | CONFIRMED PARTIAL | Reporting unit/API/frontend tests are green; Playwright report run/export is specifically required and missing. | TASK 19.15 |
| Core user/app-user/global role lifecycle audit | CONFIRMED PARTIAL | USER_CREATED, USER_ROLE_CHANGED and APP_USER_CREATED actions exist, but the mutation paths that should emit them do not. | AUDIT-15 / TASK 20.4 |
| Advanced logic physical comparison | MANUAL PENDING | Selected advanced XForm functions still require comparison with ODK Collect as required by the specification. | Phase 15 + ODK certification |
| Physical barcode/media/device behavior | MANUAL PENDING | Barcode camera and media controls are automated-test green, but camera/device behavior has not been physically certified. | AUDIT-10 + device certification |
| Physical ODK/Kobo compatibility | MANUAL PENDING | Form retrieval, media, submissions, duplicate/replacement and offline behavior still require real client execution and evidence. | TASK 8.12/8.13/9.10/10.17/10.18 |

## Corrective / Post-Original Audit Work

| ID | Priority | Area | Current status |
|---|---|---|---|
| AUDIT-01 | HIGH | Production audit schema alignment | ✅ Complete |
| AUDIT-02 | HIGH | Multi-form app-user assignment schema | ✅ Complete |
| AUDIT-03 | HIGH | Unified OpenRosa submission pipelines | ✅ Complete |
| AUDIT-04 | HIGH | Normalized submission persistence | ✅ Complete |
| AUDIT-05 | HIGH | Canonical sourceClient metadata | ✅ Complete |
| AUDIT-06 | HIGH | Frontend authentication shell and route protection | ✅ Complete |
| AUDIT-07 | MEDIUM | Project Settings + Users & Access | ✅ Complete |
| AUDIT-08 | MEDIUM | Form lifecycle/version/assignment administration + runtime access enforcement | ✅ Complete |
| AUDIT-09 | MEDIUM | Submission review fidelity and review/download/status tests | ✅ Complete |
| AUDIT-10 | MEDIUM | Barcode camera capture with manual fallback | ✅ Code/test complete; physical phone verification pending |
| AUDIT-11 | HIGH | Real PostgreSQL/MariaDB Testcontainers certification | ⏳ Not implemented |
| AUDIT-12 | HIGH | CI quality gate | ⏳ Not implemented |
| AUDIT-13 | HIGH | Full user-facing web-form workflow shell | 🔄 Partial — runtime/fill exists; catalog/start/review/success/drafts/submitted workflow is not found |
| AUDIT-14 | HIGH | Missing browser acceptance flows | 🔄 Partial — protected route, project create, report run/export, repeat and media flows remain |
| AUDIT-15 | HIGH | Core user/app-user/global role lifecycle administration + audit | 🔄 Partial — audit action constants exist, mutation endpoints/UI are absent |

---

# EPIC 0 — Scope, Architecture, and Build Brief ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 0.1 — Define system goal | ✅ Complete | HIGH | Spring Boot ODK-compatible platform with mobile + web clients |
| TASK 0.2 — Confirm ODK/XForm XML as source of truth | ✅ Complete | HIGH | All clients use the same form definition and submission pipeline |
| TASK 0.3 — Confirm OpenRosa compatibility requirement | ✅ Complete | HIGH | ODK Collect/KoboCollect must connect normally |
| TASK 0.4 — Confirm JavaRosa 4.3.2 backend-first approach | ✅ Complete | HIGH | Use JavaRosa wherever possible |
| TASK 0.5 — Confirm admin/user UI split | ✅ Complete | HIGH | Admin dashboard vs mobile-first user form interface |
| TASK 0.6 — Remove location-specific assumptions | ✅ Complete | HIGH | Use generic reference data instead |
| TASK 0.7 — Remove CI3 migration scope | ✅ Complete | MEDIUM | CI3 migration is out of scope |
| TASK 0.8 — Create revised AI task list | ✅ Complete | HIGH | Revised markdown task list created |

---

# EPIC 1 — Project Foundation ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 1.1 — Create repository structure | ✅ Complete | HIGH | backend, frontend, docs and scripts directories are present |
| TASK 1.2 — Create Spring Boot 3 backend | ✅ Complete | HIGH | Java 21, Spring Boot 3, Web, Security, JPA, Validation, Flyway, PostgreSQL |
| TASK 1.3 — Add backend dependencies | ✅ Complete | HIGH | Web, Security, JPA, Validation, Flyway, PostgreSQL driver, Lombok, MapStruct |
| TASK 1.4 — Configure backend profiles | ✅ Complete | MEDIUM | local, test, prod - done in application.yml |
| TASK 1.5 — Create Vue 3 frontend | ✅ Complete | HIGH | Vue 3, TypeScript, Vite |
| TASK 1.6 — Add frontend dependencies | ✅ Complete | HIGH | Vue Router, Pinia, Axios, Vitest and Playwright are present; Batch 3 Playwright ran locally. |
| TASK 1.7 — Add Docker Compose | ✅ Complete | HIGH | Backend, frontend, database, optional MinIO |
| TASK 1.8 — Add health endpoint | ✅ Complete | HIGH | Backend readiness check - Actuator configured |
| TASK 1.9 — Add base exception handling | ✅ Complete | MEDIUM | ApiError, ApiException, GlobalExceptionHandler created |
| TASK 1.10 — Add audit foundation | ✅ Complete | MEDIUM | AuditLog entity, AuditService, AuditAspect, Auditable interface, CurrentUserService, AsyncConfig |
| TASK 1.11 — Add README setup commands | ✅ Complete | MEDIUM | Local and Docker commands documented |
| TASK 1.12 — Add initial backend tests | ✅ Complete | HIGH | Startup, health, migration tests - OdkPlatformApplicationTests, HealthEndpointTest, MigrationTest created and passing |
| TASK 1.13 — Add initial frontend tests | ✅ Complete | HIGH | Frontend suite verified locally: 21 Vitest files / 50 tests, router tests, production Vite build, and browser certification. |

---

# EPIC 2 — Reference Documentation Setup 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 2.1 — Create docs/reference folder | ✅ Complete | HIGH | Included in Phase 2 patch |
| TASK 2.2 — Add ODK XLSForm Template.xlsx | ✅ Complete | HIGH | Stored under docs/reference/ |
| TASK 2.3 — Create XLSForm template notes | ✅ Complete | HIGH | xlsform-template-notes.md |
| TASK 2.4 — Create reference README | ✅ Complete | MEDIUM | docs/reference/README.md |
| TASK 2.5 — Create XLSForm development rules | ✅ Complete | HIGH | xlsform-development-rules.md |
| TASK 2.6 — Document observed workbook sheets | ✅ Complete | HIGH | survey, choices, settings, entities, helper sheets |
| TASK 2.7 — Document core survey columns | ✅ Complete | HIGH | type, name, label, required, relevant, constraint, etc. |
| TASK 2.8 — Document core choices columns | ✅ Complete | HIGH | list_name, name, label |
| TASK 2.9 — Document core settings columns | ✅ Complete | HIGH | form_title, form_id, version, instance_name, etc. |
| TASK 2.10 — Document core entities columns | ✅ Complete | HIGH | list_name, label |
| TASK 2.11 — Add JavaRosa-first rule | ✅ Complete | HIGH | Use JavaRosa 4.3.2 wherever possible |
| TASK 2.12 — Add generic reference data rule | ✅ Complete | HIGH | No hardcoded district/parish/village tables |
| TASK 2.13 — Add generic reporting rule | ✅ Complete | HIGH | No hardcoded gender/location reports |
| TASK 2.14 — Add verification script | ⚠️ Corrective Gap | HIGH | Legacy helper task is still part of the older 279-row tracker, but scripts/check_phase2_reference_docs.py is absent from the current source snapshot. The authoritative specification instead requires a documentation/file-presence check in CI. |
| TASK 2.15 — Add completion checklist | ⚠️ Corrective Gap | MEDIUM | Legacy PHASE_02_COMPLETION_CHECKLIST.md is absent from the current source snapshot. This is not a runtime blocker, but the tracker must not claim the artifact currently exists. |
| TASK 2.16 — Add Codex prompt | ⚠️ Corrective Gap | MEDIUM | Legacy CODEX_PHASE_02_PROMPT.md is absent from the current source snapshot. This is not a runtime blocker, but the tracker must not claim the artifact currently exists. |
| TASK 2.17 — Run verification script | ⚠️ Corrective Gap | HIGH | The legacy verification script cannot be rerun because TASK 2.14's script artifact is absent. Supersede this with the CI documentation/file-presence gate required by the authoritative specification. |

---

# EPIC 3 — Security and Access Control 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 3.1 — Create users table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 3.2 — Create roles table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 3.3 — Create permissions table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 3.4 — Create user_roles table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 3.5 — Create role_permissions table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 3.6 — Create app_users table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 3.7 — Create app_user_form_assignments table | ✅ Complete | HIGH | Already in V1__initial_schema.sql (as app_user_projects) |
| TASK 3.8 — Add password hashing | ✅ Complete | HIGH | BCryptPasswordEncoder in SecurityConfig |
| TASK 3.9 — Seed default admin user | ✅ Complete | HIGH | Local setup login - V2__seed_admin_user.sql created with admin/admin123 |
| TASK 3.10 — Implement web login | ✅ Complete | HIGH | /api/auth/login - AuthController created |
| TASK 3.11 — Implement logout | ✅ Complete | MEDIUM | /api/auth/logout - AuthController created |
| TASK 3.12 — Implement current user endpoint | ✅ Complete | MEDIUM | /api/auth/me - AuthController created |
| TASK 3.13 — Implement route protection | ✅ Complete | HIGH | Frontend auth store, login page, token/session restore, protected admin/web-form routes, role/permission guards and router tests are implemented. The authenticated Playwright regression is green. |
| TASK 3.14 — Implement OpenRosa Basic Auth | ✅ Complete | HIGH | OpenRosaBasicAuthFilter created |
| TASK 3.15 — Audit login attempts | ✅ Complete | HIGH | Success and failure - AuthenticationEventListener created |
| TASK 3.16 — Add security tests | ✅ Complete | HIGH | **COMPLETE** - Comprehensive unit + integration tests implemented: |

---

# EPIC 4 — Project Management 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 4.1 — Create projects table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 4.2 — Create project_members table | ✅ Complete | MEDIUM | Already in V1__initial_schema.sql |
| TASK 4.3 — Create project_settings table | ✅ Complete | MEDIUM | V3__project_settings.sql created |
| TASK 4.4 — Create Project entity/repository | ✅ Complete | HIGH | Project entity created |
| TASK 4.5 — Create ProjectService | ✅ Complete | HIGH | ProjectService.java with CRUD operations |
| TASK 4.6 — Create ProjectController | ✅ Complete | HIGH | ProjectController.java with REST endpoints |
| TASK 4.7 — Implement project CRUD API | ✅ Complete | HIGH | GET, POST, PUT, DELETE /api/projects |
| TASK 4.8 — Create Projects admin page | ✅ Complete | HIGH | Admin UI (frontend) - ProjectsView.vue, ProjectCard.vue, ProjectFormModal.vue |
| TASK 4.9 — Create Project Detail page | ✅ Complete | MEDIUM | Project Detail now contains working Settings and Users & Access administration alongside Forms, Submissions, Reference Data, Reports and Audit. Focused backend/frontend tests were reported green. |
| TASK 4.10 — Add project audit events | ✅ Complete | MEDIUM | Create/update/archive audit events (implemented in ProjectService) |
| TASK 4.11 — Add project tests | ✅ Complete | HIGH | MigrationTest verifies schema, ProjectServiceTest (20+ tests), ProjectControllerIntegrationTest (15+ tests) |

---

# EPIC 5 — Form XML Upload and JavaRosa Validation 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 5.1 — Create forms table | ✅ Complete | HIGH | Already in V1__initial_schema.sql (as forms) |
| TASK 5.2 — Create form_versions table | ✅ Complete | HIGH | Already in V1__initial_schema.sql (as form_versions) |
| TASK 5.3 — Create form_attachments table | ✅ Complete | HIGH | Form media files - V4__form_attachments.sql created |
| TASK 5.4 — Create form_external_datasets table | ✅ Complete | MEDIUM | CSV/XML/GeoJSON references - V5__form_external_datasets.sql created |
| TASK 5.5 — Create form_publish_history table | ✅ Complete | MEDIUM | Version lifecycle - V6__form_publish_history.sql created |
| TASK 5.6 — Add JavaRosa 4.3.2 dependency | ✅ Complete | HIGH | Already in pom.xml |
| TASK 5.7 — Create JavaRosaFormLoader | ✅ Complete | HIGH | Load XForm into JavaRosa - JavaRosaFormLoader.java created |
| TASK 5.8 — Create JavaRosaFormValidator | ✅ Complete | HIGH | Validate uploaded XForms - JavaRosaFormValidator.java created |
| TASK 5.9 — Implement XML upload endpoint | ✅ Complete | HIGH | /api/projects/{projectId}/forms/upload - Implemented in ProjectController |
| TASK 5.10 — Store raw XForm XML | ✅ Complete | HIGH | Must preserve source - Implemented in ProjectController |
| TASK 5.11 — Extract xmlFormId | ✅ Complete | HIGH | From instance/model - Implemented in ProjectController |
| TASK 5.12 — Extract form title | ✅ Complete | HIGH | Metadata - Implemented in ProjectController |
| TASK 5.13 — Extract version | ✅ Complete | HIGH | OpenRosa version - Implemented in ProjectController |
| TASK 5.14 — Extract instance root node | ✅ Complete | HIGH | Form structure - Implemented in ProjectController |
| TASK 5.15 — Generate MD5 hash | ✅ Complete | HIGH | OpenRosa form hash - Implemented in ProjectController |
| TASK 5.16 — Create upload UI | ✅ Complete | MEDIUM | Admin form upload page - FormUploadModal.vue, FormList.vue updated |
| TASK 5.17 — Add upload and validation tests | ✅ Complete | HIGH | Backend upload/validation tests plus FormUploadModal frontend tests are present and the full frontend suite is green. |

---

# EPIC 6 — XForm Metadata Extraction ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 6.1 — Extract model structure | ✅ Complete | HIGH | Data tree - XFormBindExtractor |
| TASK 6.2 — Extract instance structure | ✅ Complete | HIGH | XML structure - XFormBodyExtractor |
| TASK 6.3 — Extract binds | ✅ Complete | HIGH | type, required, constraint, relevant, calculate - XFormBindExtractor |
| TASK 6.4 — Extract body controls | ✅ Complete | HIGH | UI order - XFormBodyExtractor |
| TASK 6.5 — Extract groups | ✅ Complete | HIGH | Sections - XFormBodyExtractor |
| TASK 6.6 — Extract repeats | ✅ Complete | HIGH | Repeat groups - XFormBodyExtractor |
| TASK 6.7 — Extract choices | ✅ Complete | HIGH | Select options - XFormChoiceExtractor |
| TASK 6.8 — Extract IText/translations | ✅ Complete | HIGH | Multilingual rendering - XFormITextExtractor |
| TASK 6.9 — Extract appearances | ✅ Complete | MEDIUM | Web control hints - XFormBodyExtractor |
| TASK 6.10 — Extract media references | ✅ Complete | HIGH | Images/audio/video - FormMetadataExtractor |
| TASK 6.11 — Extract external list references | ✅ Complete | MEDIUM | CSV/lookups - FormMetadataExtractor |
| TASK 6.12 — Extract entity references | ✅ Complete | MEDIUM | Generic entities - FormMetadataExtractor |
| TASK 6.13 — Store metadata JSON | ✅ Complete | HIGH | schema, binds, body, choices, itext, media - FormMetadataExtractor.storeMetadata() |
| TASK 6.14 — Add metadata extraction tests | ✅ Complete | HIGH | Dedicated extractor tests exist for binds/body/choices/IText/metadata; EPIC 21 also added stable golden XForm fixtures. |

---

# EPIC 7 — Form Publishing and Assignment ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 7.1 — Implement form statuses | ✅ Complete | HIGH | FormStatus enum with DRAFT, VALIDATED, PUBLISHED, DEPRECATED, ARCHIVED |
| TASK 7.2 — Implement publish endpoint | ✅ Complete | HIGH | /api/projects/{projectId}/forms/{formId}/publish - Implemented in ProjectController |
| TASK 7.3 — Implement archive endpoint | ✅ Complete | MEDIUM | /api/projects/{projectId}/forms/{formId}/archive - Implemented in ProjectController |
| TASK 7.4 — Ensure one current published version | ✅ Complete | HIGH | Version rule - Implemented in publish endpoint |
| TASK 7.5 — Create form assignments | ✅ Complete | HIGH | FormAssignmentService CRUD + V20/JPA/H2 multi-form uniqueness correction verified green. |
| TASK 7.6 — Assign forms to app users | ✅ Complete | HIGH | App-user assignment endpoints + multi-form persistence regression verified green. |
| TASK 7.7 — Assign forms to web users/roles | ✅ Complete | MEDIUM | Browser web-form assignments to project users and roles are implemented through web_form_assignments, separately from mobile app-user assignments, with runtime access enforcement. |
| TASK 7.8 — Create form detail UI | ✅ Complete | MEDIUM | Dedicated Form Detail route/view provides lifecycle/version history and management actions. Lifecycle backend/frontend tests are green. |
| TASK 7.9 — Create assignment UI | ✅ Complete | MEDIUM | FormAssignmentsPanel plus store/API support user/role assignment and removal. Assignment and runtime authorization regressions are green. |
| TASK 7.10 — Audit publishing and assignments | ✅ Complete | HIGH | Publish/archive lifecycle history is wired through FormPublishHistoryService; FORM_PUBLISHED/FORM_ASSIGNED/FORM_UNASSIGNED lifecycle/audit regressions are green. |
| TASK 7.11 — Add publishing tests | ✅ Complete | HIGH | ProjectControllerAuditTest, FormAssignmentServiceTest, FormPublishHistoryServiceTest and broader integration tests are present and locally green. |

---

# EPIC 8 — OpenRosa Form List and Download 🧪 Manual Pending

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 8.1 — Implement OpenRosa formList endpoint | ✅ Complete | HIGH | /v1/projects/{projectId}/formList - OpenRosaController created |
| TASK 8.2 — Add /formList compatibility alias | ✅ Complete | MEDIUM | Older/simple clients - compatibility endpoint added |
| TASK 8.3 — Add X-OpenRosa-Version header | ✅ Complete | HIGH | Must be 1.0 - setOpenRosaHeaders() method |
| TASK 8.4 — Generate valid formList XML | ✅ Complete | HIGH | OpenRosa namespace - formList() method |
| TASK 8.5 — Filter by app user assignment | ✅ Complete | HIGH | Security - AppUserProject integration implemented |
| TASK 8.6 — Include formID/name/version/hash | ✅ Complete | HIGH | Required form metadata - included in formList XML |
| TASK 8.7 — Include downloadUrl | ✅ Complete | HIGH | Blank form download - included in formList XML |
| TASK 8.8 — Include manifestUrl | ✅ Complete | HIGH | Media/downloads - included in formList XML |
| TASK 8.9 — Implement form XML download | ✅ Complete | HIGH | /forms/{xmlFormId}.xml - formDownload() method |
| TASK 8.10 — Return raw published XML unchanged | ✅ Complete | HIGH | No mutation - returns raw XML from FormVersion |
| TASK 8.11 — Add OpenRosa contract tests | ✅ Complete | HIGH | Integration coverage includes formList/download/aliases/headers/auth and form hash assertion; local backend suite reported green |
| TASK 8.12 — Test with ODK Collect | 🧪 Manual Pending | HIGH | Run the ODK Collect device/emulator checklist against a reachable backend. |
| TASK 8.13 — Test with KoboCollect | 🧪 Manual Pending | HIGH | Run the KoboCollect device/emulator checklist against a reachable backend. |

---

# EPIC 9 — OpenRosa Manifest and Media Download 🧪 Manual Pending

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 9.1 — Implement manifest endpoint | ✅ Complete | HIGH | /v1/projects/{projectId}/forms/{xmlFormId}/manifest - formManifest() method |
| TASK 9.2 — Generate valid manifest XML | ✅ Complete | HIGH | OpenRosa namespace - formManifest() method |
| TASK 9.3 — Include media filenames | ✅ Complete | HIGH | CSV/images/audio/video - form XML + media references in manifest |
| TASK 9.4 — Include media MD5 hashes | ✅ Complete | HIGH | Manifest entries use attachment/dataset content MD5 values for OpenRosa client verification; regression coverage is green. |
| TASK 9.5 — Include media download URLs | ✅ Complete | HIGH | Collect/Kobo downloads - downloadUrl in manifest |
| TASK 9.6 — Implement attachment download endpoint | ✅ Complete | HIGH | /attachments/{filename} - with path traversal prevention |
| TASK 9.7 — Prevent path traversal | ✅ Complete | HIGH | Security - filename validation in attachment download |
| TASK 9.8 — Enforce assignment permissions | ✅ Complete | HIGH | Only assigned users - AppUserProject integration |
| TASK 9.9 — Add manifest tests | ✅ Complete | HIGH | OpenRosaManifestIntegrationTest.java with 30+ tests covering XML structure, auth, authorization, path traversal, content validation, headers, media types, and edge cases |
| TASK 9.10 — Manual ODK/Kobo media download test | 🧪 Manual Pending | HIGH | Physical ODK Collect/KoboCollect manifest/media download verification remains. |

---

# EPIC 10 — OpenRosa Submission Upload 🧪 Manual Pending

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 10.1 — Implement HEAD submission endpoint | ✅ Complete | HIGH | Content-length support - submissionHead() method |
| TASK 10.2 — Implement POST submission endpoint | ✅ Complete | HIGH | Multipart upload - submissionPost() method |
| TASK 10.3 — Require xml_submission_file | ✅ Complete | HIGH | ODK standard - validated in submissionPost() |
| TASK 10.4 — Accept attachments | ✅ Complete | HIGH | Photos/audio/video/files - implemented in submissionPost() |
| TASK 10.5 — Parse submission XML safely | ✅ Complete | HIGH | Secure namespace-aware parser with DTD/external-entity rejection is used by both submission routes; security/integration regression verified green. |
| TASK 10.6 — Extract xmlFormId | ✅ Complete | HIGH | Match form - extractFormId() method |
| TASK 10.7 — Extract instanceID | ✅ Complete | HIGH | Technical uniqueness - extractInstanceId() method |
| TASK 10.8 — Extract deprecatedID | ✅ Complete | MEDIUM | Replacement/update flow - extractDeprecatedId() method |
| TASK 10.9 — Validate form exists | ✅ Complete | HIGH | Error if unknown - form lookup in submissionPost() |
| TASK 10.10 — Validate submitter access | ✅ Complete | HIGH | Security - AppUserProject integration - validateSubmitterAccess() |
| TASK 10.11 — Validate submission using JavaRosa | ✅ Complete | HIGH | Restored-instance JavaRosa validation plus lexical typed-value integrity checks run before persistence; regression verified green. |
| TASK 10.12 — Store raw XML | ✅ Complete | HIGH | Preserve original - Submission entity |
| TASK 10.13 — Store attachments | ✅ Complete | HIGH | Linked to submission - storeSubmissionAttachments() |
| TASK 10.14 — Detect duplicate instanceID | ✅ Complete | HIGH | Explicit findByFormIdAndInstanceId pre-check now returns 409 and emits SUBMISSION_DUPLICATE before save. |
| TASK 10.15 — Return OpenRosaResponse XML | ✅ Complete | HIGH | 201 Created - OpenRosaResponse XML returned |
| TASK 10.16 — Add multipart and OpenRosa tests | ✅ Complete | HIGH | Multipart/OpenRosa integration coverage, compatibility aliases, JavaRosa validation and duplicate/replacement regressions were rerun green. |
| TASK 10.17 — Test submission with ODK Collect | 🧪 Manual Pending | HIGH | Physical ODK Collect submission test remains. |
| TASK 10.18 — Test submission with KoboCollect | 🧪 Manual Pending | HIGH | Physical KoboCollect submission test remains. |

---

# EPIC 11 — Submission Processing Pipeline ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 11.1 — Create submissions table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 11.2 — Create submission_versions table | ✅ Complete | MEDIUM | Replacement history - V9__submission_versions.sql created |
| TASK 11.3 — Create submission_attachments table | ✅ Complete | HIGH | Already in V1__initial_schema.sql |
| TASK 11.4 — Create submission_answers table | ✅ Complete | HIGH | V8__submission_answers_and_events.sql created |
| TASK 11.5 — Create submission_events table | ✅ Complete | MEDIUM | V8__submission_answers_and_events.sql created |
| TASK 11.6 — Convert XML to nested JSON | ✅ Complete | HIGH | SubmissionXmlParser implemented |
| TASK 11.7 — Flatten answers | ✅ Complete | HIGH | Flattened answers persist as typed submission_answers for OpenRosa and web submissions; regression verified green. |
| TASK 11.8 — Preserve repeat structure | ✅ Complete | HIGH | SubmissionXmlParser handles repeats as arrays |
| TASK 11.9 — Link attachments to answers | ✅ Complete | HIGH | Concrete submission_attachments.answer_id linkage resolves normalized filename answers within the same submission and persists the foreign-key relationship. Focused/full regression was reported green. |
| TASK 11.10 — Store source client | ✅ Complete | MEDIUM | ODK/Kobo/OpenRosa/Web use metadata.sourceClient; SubmissionController reads sourceClient with legacy source fallback; regression verified green. |
| TASK 11.11 — Add processing tests | ✅ Complete | HIGH | XML, JSON, flattening, repeats (20 tests passing) |

---

# EPIC 12 — Admin Submission Review UI ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 12.1 — Create submissions list API | ✅ Complete | HIGH | Filters and pagination - Backend done |
| TASK 12.2 — Create submission detail API | ✅ Complete | HIGH | XML, JSON, answers, attachments - Backend done |
| TASK 12.3 — Create XML download endpoint | ✅ Complete | MEDIUM | Raw submission XML - Backend done |
| TASK 12.4 — Create JSON download endpoint | ✅ Complete | MEDIUM | Parsed submission JSON - Backend done |
| TASK 12.5 — Create attachment download endpoint | ✅ Complete | MEDIUM | Secure downloads - Backend done |
| TASK 12.6 — Create admin submission list page | ✅ Complete | HIGH | Data table - Frontend Complete |
| TASK 12.7 — Add filters | ✅ Complete | HIGH | Project, form, status, date, source, submitter - Frontend Complete |
| TASK 12.8 — Create submission detail page | ✅ Complete | HIGH | Submission detail provides Answers, Raw XML, JSON, Attachments, Audit/Events and Metadata tabs plus XML/JSON/attachment downloads and review summary. |
| TASK 12.9 — Add submission review tests | ✅ Complete | HIGH | Dedicated backend review/download/status integration coverage plus frontend detail tests and desktop/mobile Playwright list→detail flow are green. |

---

# EPIC 13 — Web Form Runtime MVP 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 13.1 — Create web_form_sessions table | ✅ Complete | HIGH | V12__web_form_sessions.sql migration created |
| TASK 13.2 — Create web_form_answers table | ✅ Complete | HIGH | V12__web_form_sessions.sql migration created |
| TASK 13.3 — Create web_form_attachments table | ✅ Complete | MEDIUM | V12__web_form_sessions.sql migration created |
| TASK 13.4 — Create JavaRosaRuntimeSession | ✅ Complete | HIGH | FormDefinition.java serves this purpose |
| TASK 13.5 — Start web session from form version | ✅ Complete | HIGH | WebFormController with /api/web/forms/{formVersionId}/sessions |
| TASK 13.6 — Return current screen/question state | ✅ Complete | HIGH | WebFormController GET /api/web/sessions/{sessionId}/current |
| TASK 13.7 — Save answer updates | ✅ Complete | HIGH | WebFormController POST /api/web/sessions/{sessionId}/answers |
| TASK 13.8 — Re-evaluate relevance | ✅ Complete | HIGH | Implement XPath evaluation in WebFormRuntimeService |
| TASK 13.9 — Re-run calculations | ✅ Complete | HIGH | Implement XPath evaluation in WebFormRuntimeService |
| TASK 13.10 — Validate required/constraints | ✅ Complete | HIGH | Implement XPath evaluation in WebFormRuntimeService |
| TASK 13.11 — Finalize to ODK-style XML | ✅ Complete | HIGH | Finalization uses persisted JavaRosa instance serialization; local backend regression suite reported green |
| TASK 13.12 — Create mobile form layout | ✅ Complete | HIGH | Frontend - WebFormView.vue |
| TASK 13.13 — Create basic web controls | ✅ Complete | HIGH | Core controls plus media, signature, geo and barcode camera capture are implemented. Manual barcode entry remains as fallback. Focused/full frontend and Playwright regressions were reported green. |
| TASK 13.14 — Add sticky form actions | ✅ Complete | MEDIUM | Frontend - Back/Next/Save/Submit in WebFormView.vue |
| TASK 13.15 — Add draft save/resume | ✅ Complete | HIGH | Frontend/Backend - saveDraft endpoint and UI |
| TASK 13.16 — Add web runtime tests | ✅ Complete | HIGH | Backend web-form tests, frontend WebFormView/component tests and desktop/mobile Playwright submit flow are locally green. |

---

# EPIC 14 — Groups and Repeats 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 14.1 — Render groups as sections/cards | ✅ Complete | HIGH | User form UI - WebFormGroup.vue |
| TASK 14.2 — Render repeat groups | ✅ Complete | HIGH | RepeatControl - WebFormGroup.vue |
| TASK 14.3 — Add repeat instance | ✅ Complete | HIGH | Runtime + UI - createNewRepeat in store/controller |
| TASK 14.4 — Delete repeat instance | ✅ Complete | HIGH | Runtime + UI - deleteRepeat in store/controller |
| TASK 14.5 — Support repeat_count | ✅ Complete | HIGH | JavaRosa-backed cardinality/validation implementation and integration coverage; local backend suite reported green |
| TASK 14.6 — Support nested groups | ✅ Complete | MEDIUM | Full JavaRosa group hierarchy + nested UI/breadcrumb context verified green locally |
| TASK 14.7 — Support simple nested repeats | ✅ Complete | MEDIUM | Exact instancePath preserves every repeat multiplicity; nested targeting tests verified green locally |
| TASK 14.8 — Serialize repeats into XML | ✅ Complete | HIGH | Finalization delegates to JavaRosa and dedicated two-instance serialization regression test is verified green |
| TASK 14.9 — Flatten repeat answers | ✅ Complete | HIGH | Repeat-aware XML→JSON→flatten pipeline, including nested/single repeats and template exclusion, verified green locally |
| TASK 14.10 — Add repeat tests | ✅ Complete | HIGH | API-level nested-repeat round-trip and WebFormView→Pinia→HTTP integration coverage rerun locally and reported green |

---

# EPIC 15 — Advanced XForm Logic 🧪 Manual Pending

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 15.1 — Support selected() | ✅ Complete | HIGH | JavaRosa-backed compatibility test rerun locally and reported green |
| TASK 15.2 — Support count-selected() | ✅ Complete | HIGH | JavaRosa-backed compatibility test rerun locally and reported green |
| TASK 15.3 — Support indexed-repeat() | ✅ Complete | HIGH | JavaRosa-backed repeat lookup compatibility test rerun locally and reported green |
| TASK 15.4 — Support position() | ✅ Complete | MEDIUM | XPath evaluation is scoped to the exact current question/repeat context; position tests rerun locally and reported green |
| TASK 15.5 — Support count() | ✅ Complete | MEDIUM | JavaRosa XPath count() compatibility test rerun locally and reported green |
| TASK 15.6 — Support jr:choice-name() | ✅ Complete | MEDIUM | FormDef function handlers are retained during wrapper evaluation; compatibility test rerun locally and reported green |
| TASK 15.7 — Support pulldata() | ✅ Complete | HIGH | Request-local rewrite to JavaRosa-native instance() lookup + active CSV resolution; local regression suite reported green |
| TASK 15.8 — Support choice_filter | ✅ Complete | HIGH | External CSV instances are inlined per form version and JavaRosa itemsets perform cascading choice filtering; local regression suite reported green |
| TASK 15.9 — Support instance() lookups | ✅ Complete | MEDIUM | Inline + uploaded CSV/XML secondary-instance resolution verified green locally |
| TASK 15.10 — Support entity list lookups | ✅ Complete | MEDIUM | Project-scoped generic Entity Lists materialize as ODK name/label/property datasets and feed JavaRosa; local regression suite reported green |
| TASK 15.11 — Warn on unsupported logic | ✅ Complete | HIGH | Missing/unsupported sources use safe empty runtime instances with visible compatibility warnings; entity-property preflight coverage reported green |
| TASK 15.12 — Add one test form per function | ✅ Complete | HIGH | Advanced XPath, external-data, Entity List and compatibility-preflight fixture packs were rerun locally and reported green |

---

# EPIC 16 — Media and Geo Controls 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 16.1 — Implement image upload control | ✅ Complete | HIGH | XForm mediatype drives image picker; byte upload, attachment transfer and XML filename serialization rerun locally and reported green |
| TASK 16.2 — Implement audio upload control | ✅ Complete | MEDIUM | XForm mediatype drives audio picker and common attachment/finalization path rerun locally and reported green |
| TASK 16.3 — Implement video upload control | ✅ Complete | MEDIUM | XForm mediatype drives video picker and common attachment/finalization path rerun locally and reported green |
| TASK 16.4 — Implement file upload control | ✅ Complete | HIGH | Generic file picker + safe byte storage + final submission attachment transfer rerun locally and reported green |
| TASK 16.5 — Implement signature control | ✅ Complete | MEDIUM | Mobile-friendly signature canvas uploads PNG through the certified attachment pipeline; frontend + JavaRosa metadata/XML tests rerun locally and reported green |
| TASK 16.6 — Implement geopoint control | ✅ Complete | HIGH | Manual + browser geolocation capture canonicalizes ODK lat/lon/altitude/accuracy values; frontend + JavaRosa serialization tests rerun locally and reported green |
| TASK 16.7 — Implement geotrace control | ✅ Complete | LOW | Sequential GPS capture, manual semicolon values, remove/clear actions and live geometry preview rerun locally and reported green |
| TASK 16.8 — Implement geoshape control | ✅ Complete | LOW | Sequential GPS capture + explicit polygon closure + live geometry preview rerun locally and reported green |
| TASK 16.9 — Store web form attachments | ✅ Complete | HIGH | Unique safe temporary storage + transfer into submission_attachments + temporary cleanup rerun locally and reported green |
| TASK 16.10 — Serialize media filenames into XML | ✅ Complete | HIGH | Browser answer stores original filename and JavaRosa final XML preservation rerun locally and reported green |
| TASK 16.11 — Add media/geo tests | ✅ Complete | HIGH | Full frontend geo/signature suite, backend JavaRosa geo/signature serialization tests and production build rerun locally and reported green |

---

# EPIC 17 — XLSForm Upload and Conversion ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 17.1 — Implement XLSForm upload endpoint | ✅ Complete | HIGH | /api/projects/{projectId}/forms/upload-xlsform implemented; local integration suite reported green |
| TASK 17.2 — Accept .xlsx files | ✅ Complete | HIGH | .xlsx extension, empty-file and 10MB limit preflight implemented; local tests reported green |
| TASK 17.3 — Validate workbook sheets | ✅ Complete | HIGH | survey required; settings warning; choices dependency and optional entities/helper sheets checked with Apache POI |
| TASK 17.4 — Validate core headers | ✅ Complete | HIGH | Template-aware required headers for survey/settings/choices/entities implemented |
| TASK 17.5 — Preserve extra columns | ✅ Complete | HIGH | All sheet headers + extra-column catalog stored in FormVersion XLSForm metadata and original XLSX retained Base64 |
| TASK 17.6 — Generate warnings for unsupported columns | ✅ Complete | HIGH | Unknown catalog columns are preserved, passed to pyxform and surfaced as structured admin warnings |
| TASK 17.7 — Integrate pyxform service/subprocess | ✅ Complete | HIGH | Configurable xls2xform subprocess with timeout/error capture; pyxform 4.5.0 added to Docker and local setup script |
| TASK 17.8 — Validate generated XML with JavaRosa | ✅ Complete | HIGH | Generated XML passes JavaRosaFormValidator before persistence; failures returned as blocking XLSForm errors |
| TASK 17.9 — Store generated XForm as form version | ✅ Complete | HIGH | New form or same form_id/new version supported; source XLSX + generated XML + conversion metadata stored |
| TASK 17.10 — Display conversion result in UI | ✅ Complete | MEDIUM | Upload modal now supports XLSForm/XML modes and displays conversion errors, warnings, info and preserved extra columns |
| TASK 17.11 — Add XLSForm tests | ✅ Complete | HIGH | Workbook preflight, authoritative template, endpoint/persistence, optional real-pyxform smoke and frontend upload tests added; local backend/frontend suites reported green |

---

# EPIC 18 — Generic Reference Data and Entities ✅ Complete

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 18.1 — Create reference_lists table | ✅ Complete | HIGH | V17 project-scoped generic reference_lists verified by local backend suite |
| TASK 18.2 — Create reference_items table | ✅ Complete | HIGH | V17 ordered generic reference_items verified locally |
| TASK 18.3 — Create reference_item_properties table | ✅ Complete | MEDIUM | Arbitrary key/value properties verified locally |
| TASK 18.4 — Create reference_item_relationships table | ✅ Complete | MEDIUM | Cross-list generic relationships verified locally |
| TASK 18.5 — Create entity_lists table | ✅ Complete | MEDIUM | V16 project-scoped generic Entity List foundation verified by local migration/repository/runtime tests |
| TASK 18.6 — Create entity_items table | ✅ Complete | MEDIUM | V16 generic entity_items with stable entity_id/name + label verified locally |
| TASK 18.7 — Create entity_item_properties table | ✅ Complete | MEDIUM | V16 flexible Entity properties verified locally through entity lookup/regression suite |
| TASK 18.8 — Add CSV import/export | ✅ Complete | MEDIUM | UTF-8 import/export, hierarchy columns and arbitrary properties verified locally |
| TASK 18.9 — Create reference data admin UI | ✅ Complete | MEDIUM | Project Reference Data tab verified by frontend suite |
| TASK 18.10 — Add reference data tests | ✅ Complete | HIGH | Backend/API/security and frontend tests reported all green |

---

# EPIC 19 — Generic Reporting and Exports 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 19.1 — Create report_definitions table | ✅ Complete | HIGH | V18 configurable project/form report definitions verified green |
| TASK 19.2 — Create report_fields table | ✅ Complete | HIGH | Generic ANSWER/SYSTEM fields, grouping and aggregation configuration verified green |
| TASK 19.3 — Create report_filters table | ✅ Complete | HIGH | Saved generic operators EQ/NE/CONTAINS/range/empty/IN verified green |
| TASK 19.4 — Create report_runs table | ✅ Complete | MEDIUM | Run status, parameters, counts, duration and failure history verified green |
| TASK 19.5 — Create report_exports table | ✅ Complete | MEDIUM | CSV/XLSX export history verified green |
| TASK 19.6 — Build report query service | ✅ Complete | HIGH | Dynamic report query/runner logic operates on typed submission_answers with system fields, repeat normalization, generic filters, grouping and aggregations. |
| TASK 19.7 — Support date range filters | ✅ Complete | HIGH | Generic date/range filtering is implemented in saved filters/report execution. |
| TASK 19.8 — Support answer-based filters | ✅ Complete | HIGH | Generic answer-based filtering operators are implemented and report API/frontend tests are green. |
| TASK 19.9 — Support answer-based grouping | ✅ Complete | HIGH | Answer-based grouping is implemented against generic report fields and normalized answers. |
| TASK 19.10 — Support numeric aggregations | ✅ Complete | MEDIUM | Generic numeric summaries/aggregations are implemented and covered by reporting tests. |
| TASK 19.11 — Support categorical breakdowns | ✅ Complete | MEDIUM | Categorical grouping/count breakdowns are supported through the generic grouping/aggregation model. |
| TASK 19.12 — Implement CSV export | ✅ Complete | HIGH | UTF-8 CSV report export is implemented with export history. |
| TASK 19.13 — Implement Excel export | ✅ Complete | MEDIUM | Apache POI XLSX/Excel report export is implemented with export history. |
| TASK 19.14 — Create report builder UI | ✅ Complete | MEDIUM | Project Reports admin UI provides report definition, builder, run and export workflow. |
| TASK 19.15 — Add report tests | 🔄 Partial | HIGH | Backend report API/query/export/security tests and frontend report-builder tests are green, but the authoritative revised task list explicitly requires a Playwright report run/export flow; none exists in the current six-test browser suite. |

---

# EPIC 20 — Audit Trail 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 20.1 — Create audit_logs table | ✅ Complete | HIGH | audit_logs schema, production alignment migration and H2 mirror are verified green. |
| TASK 20.2 — Create audit_log_details table | ✅ Complete | HIGH | audit_log_details schema/entity/repository and optional old/new detail storage are verified green. |
| TASK 20.3 — Audit authentication events | ✅ Complete | HIGH | Login success/failure and logout auditing are implemented with actor/request context, IP and user agent. |
| TASK 20.4 — Audit user and role changes | 🔄 Partial | HIGH | Stable USER_CREATED, USER_ROLE_CHANGED and APP_USER_CREATED action names exist, but the current backend has no core user/app-user creation or global role-change mutation endpoints to emit/certify those lifecycle events. Project-member role assignment exists separately. |
| TASK 20.5 — Audit project changes | ✅ Complete | MEDIUM | Project create/update/archive and project settings changes are audited. |
| TASK 20.6 — Audit form uploads | ✅ Complete | HIGH | XML and XLSForm upload actions emit FORM_UPLOADED and relevant lifecycle events. |
| TASK 20.7 — Audit JavaRosa validation results | ✅ Complete | MEDIUM | FORM_VALIDATED is emitted for upload/validation flows, including validation outcome coverage. |
| TASK 20.8 — Audit form publishing | ✅ Complete | HIGH | FORM_PUBLISHED lifecycle audit is wired and locally verified green. |
| TASK 20.9 — Audit OpenRosa downloads | ✅ Complete | MEDIUM | Project-scoped and compatibility OpenRosa form downloads emit OPENROSA_FORM_DOWNLOADED. |
| TASK 20.10 — Audit submission receives | ✅ Complete | HIGH | OpenRosa and web-form finalization submission receives emit SUBMISSION_RECEIVED. |
| TASK 20.11 — Audit duplicate/replacement handling | ✅ Complete | HIGH | SUBMISSION_DUPLICATE and SUBMISSION_REPLACED are implemented and verified with duplicate/replacement flows. |
| TASK 20.12 — Audit report exports | ✅ Complete | MEDIUM | Report creation/update/delete/export auditing is implemented; REPORT_EXPORTED is filterable/displayed in audit UI tests. |
| TASK 20.13 — Create audit log UI | ✅ Complete | MEDIUM | Filterable admin audit trail UI/API shows actor, action, target, request context, IP, user agent and detail rows. |
| TASK 20.14 — Add audit tests | ✅ Complete | HIGH | Audit service/repository/controller/UI/login/form/OpenRosa/submission lifecycle regressions were reported green. |

---

# EPIC 21 — Testing and Compatibility Certification 🔄 Partial

| Task | Status | Priority | Re-audited Notes |
|---|---|---|---|
| TASK 21.1 — Create backend unit test suite | ✅ Complete | HIGH | Backend service/unit coverage inventory and direct service tests were completed and locally reported green. |
| TASK 21.2 — Create backend integration test suite | 🔄 Partial | HIGH | Spring Boot/MockMvc integration coverage is extensive and green, but the authoritative specification requires Testcontainers with a real PostgreSQL/MariaDB container. Testcontainers dependencies exist, but no container-backed test usage is present in the current snapshot. |
| TASK 21.3 — Create repository tests | ✅ Complete | HIGH | Repository query/schema smoke certification was verified green and exposed/fixed mapping/query defects. |
| TASK 21.4 — Create JavaRosa compatibility tests | ✅ Complete | HIGH | Core, repeat, geo/media and advanced JavaRosa compatibility/golden-form tests are green. |
| TASK 21.5 — Create OpenRosa contract tests | ✅ Complete | HIGH | OpenRosa headers, XML structures, Basic Auth, hashes, manifest/media, multipart submission and status contracts are green. |
| TASK 21.6 — Create multipart upload tests | ✅ Complete | HIGH | Multipart submission/media coverage includes required XML file, attachments, duplicate/replacement and validation paths. |
| TASK 21.7 — Create frontend component tests | ✅ Complete | HIGH | Frontend component/store/view suites are green, including authentication, project administration, form lifecycle/assignments, submission review and web controls. |
| TASK 21.8 — Create frontend route tests | ✅ Complete | MEDIUM | Vue router resolution/auth-guard tests are implemented and green. |
| TASK 21.9 — Create Playwright E2E tests | 🔄 Partial | HIGH | The current Playwright suite is green across desktop/mobile Chromium, but it only covers project list/detail, submission list/detail and a basic web-form submit. Required/spec flows still missing include protected-route redirect, project creation, report run/export, repeat flow and media flow where feasible. |
| TASK 21.10 — Create manual ODK Collect checklist | ✅ Complete | HIGH | ODK Collect compatibility checklist exists. Physical execution/sign-off remains a separate manual certification gate. |
| TASK 21.11 — Create manual KoboCollect checklist | ✅ Complete | HIGH | KoboCollect compatibility checklist exists. Physical execution/sign-off remains a separate manual certification gate. |
| TASK 21.12 — Add CI quality gate | ⏳ Not Implemented | HIGH | No .github/workflows, Jenkinsfile or .gitlab-ci.yml exists in the current source snapshot. The authoritative acceptance criterion that tests run in CI is therefore not satisfied. |

---

# Manual Certification Pending

- **ODK Collect:** execute the existing compatibility checklist on a real device/emulator and record evidence.
- **KoboCollect:** execute its separate checklist; do not infer Kobo compatibility from ODK results.
- **OpenRosa media:** physically verify manifest/media downloads and hashes.
- **OpenRosa submissions:** physically verify standard, attachment, repeat, geopoint, duplicate and replacement submissions.
- **Advanced XForm logic:** compare selected advanced forms with ODK Collect behavior.
- **Barcode/media device behavior:** verify camera permission, rear-camera selection, scanning/capture, cleanup and fallback on real mobile browsers.

# Final Closure Gates

The platform should not be declared fully complete until all of the following are green:

```bash
cd backend
mvn clean test

cd ../frontend
npm test -- --run
npm run build
npx playwright test
```

And additionally:

1. PostgreSQL Testcontainers integration suite passes.
2. CI runs the required backend/frontend/browser gates automatically.
3. Missing Playwright acceptance flows pass.
4. ODK Collect manual checklist passes and is recorded.
5. KoboCollect manual checklist passes and is recorded.
6. Selected advanced-logic/device media/barcode checks are recorded.

## Re-Audit Conclusion

The major implementation gaps previously listed in AUDIT-06 through AUDIT-10 are now closed and green. The platform is functionally advanced, but strict completion still requires infrastructure/test-quality work (real DB + CI + additional browser coverage), completion of the full end-user form workflow shell, user/app-user/global-role lifecycle administration/auditing, and physical ODK/Kobo/device certification.
