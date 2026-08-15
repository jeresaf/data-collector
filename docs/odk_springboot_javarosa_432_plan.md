# Spring Boot ODK-Compatible Web Forms Platform

## Revised AI Build Task List and System Specification

**Version:** 2.0  
**Purpose:** This document is intended to be passed to an AI coding agent such as Codex to generate the complete application step by step.

---

# 0. Summary of Required Changes

This revised plan updates the previous build brief with the following decisions:

1. The UI must have two clearly different experiences:
   - **Admin dashboard:** management-oriented, data-table-heavy, desktop-friendly, responsive.
   - **User form interface:** form-oriented, mobile-first, touch-friendly, optimized for filling and editing forms.

2. The backend must use **JavaRosa 4.3.2** as much as possible to avoid reinventing ODK/XForms behavior.

3. The uploaded **`ODK XLSForm Template.xlsx`** must be treated as an authoritative reference template maintained by the ODK team alongside the XLSForm standard and documentation.

4. Remove hardcoded location structures such as district, parish, village, and sub-county. Any hierarchy/reference-data implementation must be generic.

5. Reporting must be generic and form-driven. Do not hardcode reporting around location coverage, gender coverage, or fixed domain assumptions.

6. Remove CI3 migration support from the implementation plan.

7. Add strong emphasis on unit tests, integration tests, frontend tests, OpenRosa compatibility tests, and manual mobile compatibility tests.

---

# 1. Project Goal

Build a complete **Spring Boot 3 ODK-compatible data collection platform** that supports:

1. **ODK Collect**
2. **KoboCollect**
3. **Custom mobile-first web forms**
4. **Admin management dashboard**

The system must expose an **OpenRosa-compatible API** so ODK Collect and KoboCollect can connect, download forms, download media, and submit completed forms.

The system must also expose a separate REST API for the custom web application.

The most important rule is:

> **ODK XForm XML is the single source of truth.**

All clients must work from the same form definition and submit into the same backend pipeline.

```text
XLSForm / XForm XML
        ↓
Backend form storage and JavaRosa validation
        ↓
OpenRosa mobile API + Web form runtime API
        ↓
Raw XML submission
        ↓
Submission JSON
        ↓
Flattened answers
        ↓
Generic reports and exports
```

---

# 2. Non-Negotiable Requirements

## 2.1 ODK compatibility

The system must support ODK-compatible mobile workflows:

```text
ODK Collect / KoboCollect
  → authenticate
  → list available forms
  → download blank form XML
  → download form media/CSV files
  → fill forms offline
  → submit completed forms
  → submit attachments
```

## 2.2 JavaRosa 4.3.2 usage

Use **JavaRosa 4.3.2** within the backend as the primary engine wherever possible.

Do not manually recreate ODK behavior unless JavaRosa cannot provide the needed capability.

JavaRosa must be used or wrapped for:

```text
XForm loading
XForm parsing
FormDef handling
IText/translations
Bind evaluation
XPath evaluation
Required checks
Constraint checks
Relevant/skip logic
Calculate expressions
Repeat group behavior
Submission validation
ODK-compatible form behavior verification
```

Any custom code around XForms must be treated as a wrapper, adapter, serializer, or fallback — not as the primary form engine.

## 2.3 XLSForm template authority

The uploaded file:

```text
ODK XLSForm Template.xlsx
```

must be included in the project as an authoritative reference document.

Store it under:

```text
docs/reference/ODK XLSForm Template.xlsx
```

Also create:

```text
docs/reference/xlsform-template-notes.md
```

The template contains key sheets such as:

```text
survey
choices
settings
entities
👋 Start here
⚙️ Types
👀 Appearances
🔀 Relevance
🔒 Constraints
🌐 Translations
🔍 List lookups
✅ Additional columns
```

The system must not blindly assume only `survey`, `choices`, and `settings`. It should be designed to evolve with the XLSForm standard and ODK template guidance.

## 2.4 UI split

The UI must be split conceptually into:

```text
Admin side
  Management dashboard
  Tables
  Settings
  Users
  Form publishing
  Reports
  Audit logs

User side
  Form filling
  Form editing
  Drafts
  Validation
  Mobile-first flow
  Large touch controls
  Minimal dashboard clutter
```

## 2.5 Generic hierarchy and reference data

Do not create hardcoded tables such as:

```text
districts
sub_counties
parishes
villages
```

Instead, support generic reference data and generic hierarchies.

Example:

```text
reference_lists
reference_items
reference_item_relationships
entity_lists
entity_items
entity_properties
```

This allows the system to work in Uganda, Kenya, health surveys, education programs, agriculture programs, elections, markets, or any other domain without changing the database structure.

## 2.6 Generic reporting

Do not hardcode reporting around:

```text
location coverage
gender coverage
specific administrative levels
specific business domains
```

Reporting must be generic and configurable from submitted form data.

Reports should support:

```text
submission counts
completion status
form version
submitter
date range
answer-based grouping
answer-based filters
selected field summaries
numeric aggregations
categorical breakdowns
export to CSV/Excel
```

## 2.7 Remove CI3 migration

Do not implement CI3 migration features in this project.

CI3 references are out of scope for this build.

## 2.8 Testing emphasis

Every major module must have tests.

Testing is not optional.

The project must include:

```text
Unit tests
Integration tests
Repository tests
Service tests
Controller/API tests
OpenRosa XML contract tests
Multipart upload tests
JavaRosa compatibility tests
Frontend component tests
Frontend route tests
End-to-end browser tests
Manual ODK Collect test checklist
Manual KoboCollect test checklist
```

---

# 3. Target Architecture

## 3.1 High-level architecture

```text
ODK Collect / KoboCollect
        |
        | OpenRosa API
        |
Spring Boot Backend
        |
        |--------------------------------------------------
        | JavaRosa 4.3.2 Engine Layer
        | Form Management
        | XLSForm/XForm Import
        | OpenRosa Gateway
        | Web Form Runtime
        | Submission Pipeline
        | Generic Reference Data
        | Generic Reporting
        | Users / Roles / Permissions
        | Audit Logs
        |--------------------------------------------------
        |
PostgreSQL / MariaDB
        |
File Storage / MinIO / S3-compatible storage


Vue 3 Frontend
        |
        | REST API
        |
Spring Boot Backend
```

## 3.2 Backend stack

Use:

```text
Java 21 or Java 17
Spring Boot 3.x
Spring Web MVC
Spring Security
Spring Data JPA
Flyway or Liquibase
PostgreSQL preferred
MariaDB acceptable
JavaRosa 4.3.2
JUnit 5
Mockito
MockMvc
Testcontainers
Local file storage first
MinIO/S3-compatible storage later
```

## 3.3 Frontend stack

Use:

```text
Vue 3
TypeScript
Vite
Pinia
Vue Router
Axios
Vitest
Vue Test Utils
Playwright
IndexedDB later for offline drafts
Leaflet or MapLibre later for map/geospatial controls
```

---

# 4. UI/UX Design Requirements

## 4.1 Admin dashboard UI

The admin dashboard is for management.

It should be optimized for:

```text
Desktop and tablet use
Data management
Tables
Search
Filters
Bulk actions
User administration
Form publishing
Submission review
Report configuration
Audit review
```

Recommended admin pages:

```text
/dashboard
/projects
/projects/:projectId
/forms
/forms/upload
/forms/:formId
/forms/:formId/versions
/forms/:formId/assignments
/submissions
/submissions/:submissionId
/reports
/reports/:reportId
/users
/app-users
/roles
/reference-data
/audit-logs
/settings
```

Admin dashboard design rules:

```text
Use a sidebar layout on desktop.
Use a collapsible drawer on mobile.
Use tables with filters and pagination.
Use clear status badges.
Use cards for metrics.
Use confirmation dialogs for destructive actions.
Use detailed audit trails for important changes.
Do not make the admin interface look like the form filler.
```

Admin dashboard components:

```text
AdminLayout.vue
AdminSidebar.vue
AdminTopbar.vue
DataTable.vue
FilterBar.vue
StatusBadge.vue
ConfirmDialog.vue
FormVersionTimeline.vue
SubmissionDetailPanel.vue
AuditEventList.vue
ReportConfigBuilder.vue
```

## 4.2 User form interface UI

The user side is for filling and editing forms.

It must be **mobile-first**.

It should be optimized for:

```text
Phones
Touch input
Large buttons
Simple navigation
Low distraction
One question or one small group at a time
Draft saving
Clear validation messages
Offline-friendly behavior later
```

User-side pages:

```text
/app/forms
/app/forms/:formId/start
/app/sessions/:sessionId/fill
/app/sessions/:sessionId/review
/app/sessions/:sessionId/submit
/app/drafts
/app/submitted
```

User form design rules:

```text
Use large touch targets.
Avoid dense admin tables.
Use step-by-step form navigation.
Show progress.
Show required indicators clearly.
Show validation messages near the field.
Use sticky Next/Back/Save buttons on mobile.
Support autosave.
Support resume draft.
Keep the layout readable on small screens.
Make media and GPS controls simple and obvious.
```

User form components:

```text
MobileFormLayout.vue
FormProgressHeader.vue
QuestionCard.vue
QuestionGroup.vue
RepeatGroupCard.vue
StickyFormActions.vue
ValidationMessage.vue
DraftStatusIndicator.vue
ReviewAnswersPage.vue
SubmitSuccessPage.vue
```

## 4.3 Responsive behavior

Minimum responsive targets:

```text
360px wide phone screen
390px wide phone screen
768px tablet
1024px laptop
1440px desktop
```

Acceptance checks:

```text
Admin dashboard remains usable on laptop and tablet.
User form filler is excellent on phones.
No form control should require horizontal scrolling on mobile.
Primary form actions must be reachable with thumb-friendly controls.
```

---

# 5. Backend Package Structure

Create this backend package structure:

```text
com.example.odkplatform
  ├── OdkPlatformApplication.java
  │
  ├── common
  │   ├── config
  │   ├── exceptions
  │   ├── responses
  │   ├── xml
  │   ├── json
  │   ├── utils
  │   └── validation
  │
  ├── security
  │   ├── config
  │   ├── auth
  │   ├── jwt
  │   ├── basic
  │   ├── users
  │   ├── roles
  │   └── permissions
  │
  ├── projects
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   └── controllers
  │
  ├── forms
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   ├── xlsform
  │   ├── xform
  │   └── controllers
  │
  ├── javarosa
  │   ├── JavaRosaFormLoader.java
  │   ├── JavaRosaFormValidator.java
  │   ├── JavaRosaSubmissionValidator.java
  │   ├── JavaRosaRuntimeSession.java
  │   ├── JavaRosaRuntimeService.java
  │   ├── JavaRosaRepeatService.java
  │   ├── JavaRosaITextService.java
  │   ├── JavaRosaXPathService.java
  │   └── JavaRosaCompatibilityTestSupport.java
  │
  ├── openrosa
  │   ├── controllers
  │   ├── services
  │   ├── xml
  │   ├── dto
  │   ├── filters
  │   └── testsupport
  │
  ├── submissions
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   ├── xml
  │   ├── json
  │   └── controllers
  │
  ├── webforms
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   ├── runtime
  │   └── controllers
  │
  ├── attachments
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   └── controllers
  │
  ├── referencedata
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   └── controllers
  │
  ├── reports
  │   ├── domain
  │   ├── dto
  │   ├── repositories
  │   ├── services
  │   ├── exporters
  │   └── controllers
  │
  └── audit
      ├── domain
      ├── dto
      ├── repositories
      ├── services
      └── controllers
```

---

# 6. Database Design

Use Flyway or Liquibase migrations.

## 6.1 Security tables

```text
users
roles
permissions
user_roles
role_permissions
app_users
app_user_form_assignments
```

Notes:

```text
users = admin and web users
app_users = ODK Collect/KoboCollect users
roles = web/admin roles
permissions = granular permissions
app_user_form_assignments = controls what mobile users can download/submit
```

## 6.2 Project tables

```text
projects
project_members
project_settings
```

## 6.3 Form tables

```text
forms
form_versions
form_attachments
form_external_datasets
form_publish_history
form_assignments
```

Minimum `forms` fields:

```text
id
project_id
xml_form_id
name
description
current_version_id
status
created_by
created_at
updated_at
```

Minimum `form_versions` fields:

```text
id
form_id
version
title
xform_xml
xform_hash
schema_json
bind_json
body_json
choices_json
itext_json
entities_json
lookups_json
media_json
default_language
status
created_at
published_at
```

## 6.4 Submission tables

```text
submissions
submission_versions
submission_attachments
submission_answers
submission_events
```

Minimum `submissions` fields:

```text
id
project_id
form_id
form_version_id
xml_form_id
instance_id
deprecated_id
submitter_type
submitter_id
device_id
status
raw_xml
submission_json
source_client
received_at
created_at
updated_at
```

Minimum `submission_answers` fields:

```text
id
submission_id
path
field_name
field_type
value_text
value_number
value_date
value_boolean
value_json
repeat_path
repeat_index
created_at
```

## 6.5 Generic reference data tables

Do not hardcode locations.

Create:

```text
reference_lists
reference_items
reference_item_properties
reference_item_relationships
entity_lists
entity_items
entity_item_properties
entity_item_relationships
```

Example uses:

```text
Administrative areas
Facilities
Schools
Markets
Farmer groups
Households
Departments
Categories
Service points
Any custom list used by a form
```

## 6.6 Reporting tables

```text
report_definitions
report_fields
report_filters
report_runs
report_exports
dashboard_widgets
```

Reporting must be based on configurable form fields and generic submitted answers.

## 6.7 Audit tables

```text
audit_logs
audit_log_details
```

Audit logs should record:

```text
who
what action
target type
target ID
before value if applicable
after value if applicable
IP address
user agent
timestamp
```

---

# 7. OpenRosa Compatibility Requirements

## 7.1 Required endpoints

Implement:

```text
GET  /v1/projects/{projectId}/formList
GET  /v1/projects/{projectId}/forms/{xmlFormId}.xml
GET  /v1/projects/{projectId}/forms/{xmlFormId}/manifest
GET  /v1/projects/{projectId}/forms/{xmlFormId}/attachments/{filename}
HEAD /v1/projects/{projectId}/submission
POST /v1/projects/{projectId}/submission
```

Also implement compatibility aliases:

```text
GET  /formList
GET  /forms/{xmlFormId}.xml
GET  /forms/{xmlFormId}/manifest
HEAD /submission
POST /submission
```

## 7.2 Required OpenRosa headers

Include:

```text
X-OpenRosa-Version: 1.0
Date: server date
Content-Type: text/xml; charset=utf-8
```

For submission endpoints include:

```text
X-OpenRosa-Accept-Content-Length: 104857600
```

## 7.3 Form list XML

Return:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xforms xmlns="http://openrosa.org/xforms/xformsList">
  <xform>
    <formID>sample_form</formID>
    <name>Sample Form</name>
    <version>2026-07-13-v1</version>
    <hash>md5:FORM_HASH_HERE</hash>
    <downloadUrl>https://server.com/v1/projects/1/forms/sample_form.xml</downloadUrl>
    <manifestUrl>https://server.com/v1/projects/1/forms/sample_form/manifest</manifestUrl>
  </xform>
</xforms>
```

## 7.4 Manifest XML

Return:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest xmlns="http://openrosa.org/xforms/xformsManifest">
  <mediaFile>
    <filename>example.csv</filename>
    <hash>md5:FILE_HASH_HERE</hash>
    <downloadUrl>https://server.com/v1/projects/1/forms/sample_form/attachments/example.csv</downloadUrl>
  </mediaFile>
</manifest>
```

## 7.5 Submission upload

The backend must accept:

```text
multipart/form-data
xml_submission_file
zero or more attachments
```

Return success XML:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<OpenRosaResponse xmlns="http://openrosa.org/http/response" items="0">
  <message>Submission received successfully.</message>
</OpenRosaResponse>
```

Use status codes:

```text
201 Created        successful submission
400 Bad Request    invalid XML or missing xml_submission_file
401 Unauthorized   missing/invalid credentials
403 Forbidden      user not allowed to access this form
404 Not Found      unknown form
409 Conflict       duplicate instance conflict
413 Payload Too Large
500 Server Error
```

---

# 8. Admin REST API

Implement:

```text
POST   /api/auth/login
POST   /api/auth/logout
GET    /api/auth/me

GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}
PUT    /api/projects/{id}
DELETE /api/projects/{id}

GET    /api/projects/{projectId}/forms
POST   /api/projects/{projectId}/forms/upload-xml
POST   /api/projects/{projectId}/forms/upload-xlsform
GET    /api/forms/{formId}
GET    /api/forms/{formId}/versions
POST   /api/forms/{formId}/publish
POST   /api/forms/{formId}/archive
POST   /api/forms/{formId}/assignments

GET    /api/projects/{projectId}/submissions
GET    /api/submissions/{submissionId}
GET    /api/submissions/{submissionId}/attachments
GET    /api/submissions/{submissionId}/download-xml
GET    /api/submissions/{submissionId}/download-json

GET    /api/reference-lists
POST   /api/reference-lists
GET    /api/reference-lists/{id}
POST   /api/reference-lists/{id}/items
GET    /api/reference-lists/{id}/items

GET    /api/projects/{projectId}/reports
POST   /api/projects/{projectId}/reports
GET    /api/reports/{reportId}/run
GET    /api/reports/{reportId}/export

GET    /api/audit-logs
```

---

# 9. Web Form Runtime API

The web form must not create a separate form standard.

It must use the stored XForm XML and JavaRosa-backed runtime behavior.

Implement:

```text
POST /api/web/forms/{formVersionId}/sessions
GET  /api/web/sessions/{sessionId}
POST /api/web/sessions/{sessionId}/answers
POST /api/web/sessions/{sessionId}/repeat-groups/{path}/instances
DELETE /api/web/sessions/{sessionId}/repeat-groups/{path}/instances/{index}
POST /api/web/sessions/{sessionId}/attachments
POST /api/web/sessions/{sessionId}/validate
POST /api/web/sessions/{sessionId}/finalize
POST /api/web/sessions/{sessionId}/submit
```

First supported controls:

```text
text
integer
decimal
date
time
dateTime
select_one
select_multiple
note
begin_group
begin_repeat
calculate
required
constraint
relevant
```

Later supported controls:

```text
image
audio
video
signature
geopoint
geotrace
geoshape
barcode
external CSV lookup
entity list lookup
cascading selects
```

---

# 10. Frontend Structure

Create:

```text
frontend
  └── src
      ├── main.ts
      ├── App.vue
      │
      ├── router
      │   └── index.ts
      │
      ├── stores
      │   ├── auth.ts
      │   ├── projects.ts
      │   ├── forms.ts
      │   ├── submissions.ts
      │   ├── referenceData.ts
      │   ├── reports.ts
      │   └── webFormSession.ts
      │
      ├── api
      │   ├── client.ts
      │   ├── authApi.ts
      │   ├── projectApi.ts
      │   ├── formApi.ts
      │   ├── submissionApi.ts
      │   ├── referenceDataApi.ts
      │   ├── reportApi.ts
      │   └── webFormApi.ts
      │
      ├── layouts
      │   ├── AdminLayout.vue
      │   └── MobileFormLayout.vue
      │
      ├── pages
      │   ├── admin
      │   │   ├── LoginPage.vue
      │   │   ├── DashboardPage.vue
      │   │   ├── ProjectsPage.vue
      │   │   ├── ProjectDetailPage.vue
      │   │   ├── FormsPage.vue
      │   │   ├── FormUploadPage.vue
      │   │   ├── FormDetailPage.vue
      │   │   ├── SubmissionsPage.vue
      │   │   ├── SubmissionDetailPage.vue
      │   │   ├── ReferenceDataPage.vue
      │   │   ├── ReportsPage.vue
      │   │   ├── AuditLogsPage.vue
      │   │   └── SettingsPage.vue
      │   │
      │   └── app
      │       ├── UserFormsPage.vue
      │       ├── FormStartPage.vue
      │       ├── WebFormFillPage.vue
      │       ├── DraftsPage.vue
      │       ├── ReviewSubmissionPage.vue
      │       └── SubmitSuccessPage.vue
      │
      ├── components
      │   ├── admin
      │   ├── shared
      │   └── webforms
      │
      └── webforms
          ├── WebFormRenderer.vue
          ├── WebQuestionRenderer.vue
          ├── controls
          │   ├── TextControl.vue
          │   ├── NumberControl.vue
          │   ├── DateControl.vue
          │   ├── TimeControl.vue
          │   ├── SelectOneControl.vue
          │   ├── SelectMultipleControl.vue
          │   ├── NoteControl.vue
          │   ├── GroupControl.vue
          │   ├── RepeatControl.vue
          │   ├── FileControl.vue
          │   └── GeoPointControl.vue
          └── utils
```

---

# 11. Detailed Implementation Task List

## Phase 1: Project Foundation

### 1.1 Create repository structure

Create:

```text
backend/
frontend/
docs/
docs/reference/
docker-compose.yml
README.md
```

### 1.2 Create Spring Boot backend

Tasks:

1. Create Spring Boot 3 application named `odk-platform`.
2. Configure Java 21 or Java 17.
3. Add Spring Web.
4. Add Spring Security.
5. Add Spring Data JPA.
6. Add PostgreSQL or MariaDB driver.
7. Add Flyway or Liquibase.
8. Add validation dependency.
9. Add test dependencies.
10. Add application profiles:
    - `local`
    - `test`
    - `prod`

### 1.3 Create Vue frontend

Tasks:

1. Create Vue 3 + TypeScript + Vite app.
2. Add Vue Router.
3. Add Pinia.
4. Add Axios.
5. Add Vitest.
6. Add Playwright.
7. Create admin and app route groups.
8. Add base layouts.
9. Add responsive design foundation.

### 1.4 Add Docker setup

Tasks:

1. Add database service.
2. Add backend service.
3. Add frontend service.
4. Add optional MinIO service.
5. Add named volumes.
6. Add health checks.
7. Add `.env.example`.

### 1.5 Acceptance criteria

1. Backend starts.
2. Frontend starts.
3. Database starts.
4. Migrations run.
5. Health endpoint returns OK.
6. Basic frontend page loads.
7. Backend tests run.
8. Frontend tests run.

### 1.6 Required tests

1. `OdkPlatformApplicationTests`
2. Health endpoint test.
3. Database connection test.
4. Frontend smoke test.

---

## Phase 2: Reference Documentation Setup

### 2.1 Store ODK XLSForm template

Tasks:

1. Copy uploaded `ODK XLSForm Template.xlsx` into:

```text
docs/reference/ODK XLSForm Template.xlsx
```

2. Create:

```text
docs/reference/xlsform-template-notes.md
```

3. Document the discovered template sheets:

```text
survey
choices
settings
entities
👋 Start here
⚙️ Types
👀 Appearances
🔀 Relevance
🔒 Constraints
🌐 Translations
🔍 List lookups
✅ Additional columns
```

4. Document key base columns observed in the template:
   - `survey`: `type`, `name`, `label`, `hint`, `required`, `relevant`, `appearance`, `default`, `constraint`, `constraint_message`, `calculation`, `trigger`, `choice_filter`, `parameters`, `repeat_count`, `note`, `image`, `audio`, `video`
   - `choices`: `list_name`, `name`, `label`
   - `settings`: `form_title`, `form_id`, `version`, `instance_name`, `default_language`, `style`
   - `entities`: `list_name`, `label`

### 2.2 Create development rules

Tasks:

1. Add a markdown rule that XLSForm parsing must align with the template.
2. Add a rule that the system must not hardcode only current template columns.
3. Add a rule that extra columns should be preserved where possible.
4. Add a rule that unsupported columns must produce clear warnings, not silent failures.

### 2.3 Acceptance criteria

1. Template file exists in docs.
2. Notes file exists.
3. README links to the reference template.
4. Codex/AI agent instructions mention the template.

### 2.4 Required tests

1. File presence test or documentation check in CI.
2. XLSForm parser tests must use sample workbooks based on this template.

---

## Phase 3: Security and Access Control

### 3.1 Create security domain

Tasks:

1. Create `users` table.
2. Create `roles` table.
3. Create `permissions` table.
4. Create `user_roles` table.
5. Create `role_permissions` table.
6. Create `app_users` table.
7. Create `app_user_form_assignments` table.
8. Create JPA entities and repositories.
9. Add password hashing.
10. Add seed admin user.

### 3.2 Web authentication

Tasks:

1. Implement `/api/auth/login`.
2. Implement `/api/auth/logout`.
3. Implement `/api/auth/me`.
4. Use JWT or secure session authentication.
5. Add frontend auth store.
6. Add login page.
7. Add protected admin routes.
8. Add protected user form routes.

### 3.3 OpenRosa authentication

Tasks:

1. Implement HTTP Basic Auth for OpenRosa routes.
2. Authenticate against `app_users`.
3. Allow app users to access only assigned projects/forms.
4. Return correct `401` for missing credentials.
5. Return correct `403` for unauthorized form access.

### 3.4 Acceptance criteria

1. Admin can log in.
2. Invalid admin login fails.
3. Protected admin routes require authentication.
4. ODK/Kobo user can authenticate with Basic Auth.
5. Unassigned app users cannot see forms.
6. All login attempts are audited.

### 3.5 Required tests

1. `AuthControllerTest`
2. `SecurityConfigTest`
3. `AppUserBasicAuthTest`
4. `PermissionServiceTest`
5. `RoleRepositoryTest`
6. Frontend login test.
7. Playwright protected route test.

---

## Phase 4: Project Management

### 4.1 Backend project module

Tasks:

1. Create `projects` table.
2. Create `project_members` table.
3. Create `project_settings` table.
4. Create project entities.
5. Create repositories.
6. Create service layer.
7. Create REST controller.

### 4.2 Project API

Implement:

```text
GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}
PUT    /api/projects/{id}
DELETE /api/projects/{id}
```

### 4.3 Frontend project management

Tasks:

1. Create Projects page.
2. Create Project Detail page.
3. Add create project modal.
4. Add edit project form.
5. Add project status badges.

### 4.4 Acceptance criteria

1. Admin can create a project.
2. Admin can view projects.
3. Admin can update a project.
4. Admin can deactivate/archive a project.
5. Project actions are audited.

### 4.5 Required tests

1. `ProjectServiceTest`
2. `ProjectControllerIntegrationTest`
3. `ProjectRepositoryTest`
4. Frontend project page test.
5. Playwright project creation flow.

---

## Phase 5: Form XML Upload and Storage

### 5.1 Database

Tasks:

1. Create `forms` table.
2. Create `form_versions` table.
3. Create `form_attachments` table.
4. Create `form_external_datasets` table.
5. Create `form_publish_history` table.
6. Create `form_assignments` table.

### 5.2 XML upload backend

Implement:

```text
POST /api/projects/{projectId}/forms/upload-xml
```

Tasks:

1. Accept XML file upload.
2. Validate file extension and MIME type.
3. Store raw XML.
4. Extract `xmlFormId`.
5. Extract form title.
6. Extract version.
7. Extract instance root node.
8. Generate MD5 hash.
9. Store form record.
10. Store form version record.
11. Set status as draft or validated.
12. Return clean JSON response.

### 5.3 JavaRosa 4.3.2 validation

Tasks:

1. Add JavaRosa 4.3.2 dependency.
2. Create `JavaRosaFormLoader`.
3. Create `JavaRosaFormValidator`.
4. Load uploaded XForm using JavaRosa.
5. Return validation errors clearly.
6. Do not proceed to publish invalid forms.

### 5.4 Frontend form upload

Tasks:

1. Create Form Upload page.
2. Add XML upload component.
3. Show validation results.
4. Show extracted metadata.
5. Show form version status.

### 5.5 Acceptance criteria

1. Admin can upload valid XForm XML.
2. Invalid XML is rejected.
3. JavaRosa validation runs.
4. Form metadata is stored.
5. Raw XML is stored exactly.
6. Upload action is audited.

### 5.6 Required tests

1. `FormUploadControllerTest`
2. `FormServiceTest`
3. `JavaRosaFormLoaderTest`
4. `JavaRosaFormValidatorTest`
5. `XFormMetadataExtractorTest`
6. Multipart upload integration test.
7. Frontend XML upload component test.
8. Playwright XML upload flow.

---

## Phase 6: XForm Metadata Extraction

### 6.1 Extract XForm structure

Tasks:

1. Extract model.
2. Extract instance.
3. Extract binds.
4. Extract body controls.
5. Extract groups.
6. Extract repeats.
7. Extract choices.
8. Extract translations/IText.
9. Extract appearances.
10. Extract media references.
11. Extract external list references.
12. Extract entity/list lookup references.

### 6.2 Store parsed metadata

Store:

```text
schema_json
bind_json
body_json
choices_json
itext_json
entities_json
lookups_json
media_json
```

### 6.3 JavaRosa-first rule

Where JavaRosa exposes the needed data, use JavaRosa.

Only use custom XML parsing when:

1. JavaRosa does not expose enough metadata for frontend rendering.
2. The parser is only building a frontend-friendly projection.
3. The parser does not override JavaRosa behavior.

### 6.4 Acceptance criteria

1. Metadata extraction works for simple forms.
2. Metadata extraction works for groups.
3. Metadata extraction works for repeats.
4. Metadata extraction works for selects.
5. Metadata extraction works for translations.
6. Metadata extraction works for media references.
7. JavaRosa remains the authority for runtime behavior.

### 6.5 Required tests

1. `XFormBindExtractorTest`
2. `XFormBodyExtractorTest`
3. `XFormChoiceExtractorTest`
4. `JavaRosaITextServiceTest`
5. `JavaRosaRepeatServiceTest`
6. Golden sample form tests.
7. Snapshot tests for extracted JSON.

---

## Phase 7: Form Publishing and Assignment

### 7.1 Form lifecycle

Support statuses:

```text
draft
validated
published
deprecated
archived
```

### 7.2 Publishing tasks

Implement:

```text
POST /api/forms/{formId}/publish
POST /api/forms/{formId}/archive
POST /api/forms/{formId}/assignments
```

Tasks:

1. Only valid forms can be published.
2. Only one current published version per form.
3. Old versions remain stored.
4. Assign form to app users.
5. Assign form to web users or roles.
6. Log all publishing actions.

### 7.3 Frontend

Tasks:

1. Add form detail page.
2. Add versions tab.
3. Add publish button.
4. Add assignment UI.
5. Add version timeline.

### 7.4 Acceptance criteria

1. Admin can publish a form.
2. Published form becomes available through OpenRosa.
3. Unpublished form is hidden from OpenRosa.
4. Assigned app users can access form.
5. Unassigned app users cannot access form.

### 7.5 Required tests

1. `FormPublishingServiceTest`
2. `FormAssignmentServiceTest`
3. `FormControllerIntegrationTest`
4. OpenRosa visibility integration test.
5. Frontend form publishing test.

---

## Phase 8: OpenRosa Form List

### 8.1 Endpoint

Implement:

```text
GET /v1/projects/{projectId}/formList
```

### 8.2 Tasks

1. Authenticate app user using Basic Auth.
2. Load assigned published forms.
3. Generate OpenRosa XML.
4. Include `formID`.
5. Include `name`.
6. Include `version`.
7. Include `hash`.
8. Include `downloadUrl`.
9. Include `manifestUrl` when applicable.
10. Include correct headers.
11. Add compatibility alias `/formList`.

### 8.3 Acceptance criteria

1. ODK Collect can fetch blank form list.
2. KoboCollect can fetch blank form list.
3. Response is valid XML.
4. Only assigned forms are returned.
5. Headers are correct.

### 8.4 Required tests

1. `OpenRosaFormListServiceTest`
2. `OpenRosaControllerFormListTest`
3. XML namespace contract test.
4. Header contract test.
5. Basic Auth integration test.
6. Manual ODK Collect test.
7. Manual KoboCollect test.

---

## Phase 9: OpenRosa Form Download and Manifest

### 9.1 Endpoints

Implement:

```text
GET /v1/projects/{projectId}/forms/{xmlFormId}.xml
GET /v1/projects/{projectId}/forms/{xmlFormId}/manifest
GET /v1/projects/{projectId}/forms/{xmlFormId}/attachments/{filename}
```

### 9.2 Tasks

1. Return raw published XML.
2. Enforce assignment permissions.
3. Return media manifest XML.
4. Return file attachments.
5. Add MD5 hashes.
6. Add download URLs.
7. Prevent path traversal.
8. Add compatibility aliases.

### 9.3 Acceptance criteria

1. ODK Collect downloads form XML.
2. KoboCollect downloads form XML.
3. ODK Collect downloads form media.
4. KoboCollect downloads form media.
5. Unauthorized users cannot download unassigned forms.
6. Raw XML is not modified during download.

### 9.4 Required tests

1. `OpenRosaFormDownloadTest`
2. `OpenRosaManifestServiceTest`
3. `FormAttachmentDownloadSecurityTest`
4. Path traversal prevention test.
5. Header contract test.
6. Manual ODK/Kobo media download test.

---

## Phase 10: OpenRosa Submission Upload

### 10.1 Endpoints

Implement:

```text
HEAD /v1/projects/{projectId}/submission
POST /v1/projects/{projectId}/submission
```

### 10.2 Tasks

1. Accept `multipart/form-data`.
2. Require `xml_submission_file`.
3. Accept zero or more attachments.
4. Parse XML safely.
5. Extract `instanceID`.
6. Extract `deprecatedID` if present.
7. Extract `xmlFormId`.
8. Match submission to published form.
9. Validate submitter permission.
10. Validate XML using JavaRosa where possible.
11. Store raw XML.
12. Store attachments.
13. Generate nested JSON.
14. Flatten answers.
15. Detect duplicate instance IDs.
16. Return OpenRosa success XML.
17. Return correct error XML/status for failures.

### 10.3 Acceptance criteria

1. ODK Collect can submit a simple form.
2. KoboCollect can submit a simple form.
3. ODK Collect can submit form with photo/audio/file.
4. Duplicate instance handling works.
5. Raw XML is stored.
6. Attachments are stored.
7. Submission is converted to JSON.
8. Answers are flattened.
9. Submission receive event is audited.

### 10.4 Required tests

1. `OpenRosaSubmissionControllerTest`
2. `OpenRosaSubmissionServiceTest`
3. Multipart upload integration test.
4. Missing `xml_submission_file` test.
5. Attachment upload test.
6. Duplicate instance test.
7. Deprecated ID replacement test.
8. JavaRosa submission validation test.
9. XML parser security test.
10. Manual ODK Collect submission test.
11. Manual KoboCollect submission test.

---

## Phase 11: Submission Processing Pipeline

### 11.1 Raw XML preservation

Tasks:

1. Store original XML exactly.
2. Store submission hash.
3. Store received timestamp.
4. Store source client.
5. Store submitter details.

### 11.2 XML to JSON

Tasks:

1. Convert XML to nested JSON.
2. Preserve repeat structures.
3. Preserve attributes where needed.
4. Preserve empty values.
5. Link attachment filenames.

### 11.3 Flatten answers

Tasks:

1. Create row per field answer.
2. Store path.
3. Store field name.
4. Store value by type.
5. Store repeat path.
6. Store repeat index.
7. Store raw JSON value for complex values.

### 11.4 Acceptance criteria

1. Raw XML, JSON, and flattened answers are all available.
2. Repeats are represented correctly.
3. Attachments are linked.
4. Submission detail page can display all layers.

### 11.5 Required tests

1. `SubmissionXmlParserTest`
2. `SubmissionJsonConverterTest`
3. `SubmissionAnswerFlattenerTest`
4. Repeat flattening test.
5. Attachment linking test.
6. Submission detail API integration test.

---

## Phase 12: Admin Submission Review UI

### 12.1 Backend APIs

Implement:

```text
GET /api/projects/{projectId}/submissions
GET /api/submissions/{submissionId}
GET /api/submissions/{submissionId}/attachments
GET /api/submissions/{submissionId}/download-xml
GET /api/submissions/{submissionId}/download-json
```

### 12.2 Frontend

Tasks:

1. Create submissions list page.
2. Add filters:
   - project
   - form
   - form version
   - submitter
   - source client
   - status
   - date range
3. Create submission detail page.
4. Show raw XML tab.
5. Show JSON tab.
6. Show answers tab.
7. Show attachments tab.
8. Show audit/events tab.

### 12.3 Acceptance criteria

1. Admin can browse submissions.
2. Admin can inspect a submission.
3. Admin can download XML.
4. Admin can download JSON.
5. Admin can download attachments.
6. Filters work.

### 12.4 Required tests

1. `SubmissionControllerTest`
2. Submission filter repository test.
3. Frontend submission list test.
4. Frontend submission detail test.
5. Playwright submission review flow.

---

## Phase 13: Web Form Runtime MVP

### 13.1 Backend runtime

Tasks:

1. Create `web_form_sessions` table.
2. Create `web_form_answers` table.
3. Create `web_form_attachments` table.
4. Create JavaRosa-backed session service.
5. Start session from form version.
6. Return current screen/question state.
7. Save answers.
8. Validate current answer.
9. Re-evaluate relevance after answer update.
10. Re-run calculations after answer update.
11. Return validation messages.
12. Allow draft save.
13. Finalize into ODK-style XML.
14. Submit finalized XML through the same submission pipeline.

### 13.2 Frontend mobile-first form filler

Tasks:

1. Create mobile form layout.
2. Create form list for user.
3. Create form start page.
4. Create form fill page.
5. Create question renderer.
6. Create controls:
   - text
   - integer
   - decimal
   - date
   - time
   - select one
   - select multiple
   - note
7. Add sticky form action bar.
8. Add validation display.
9. Add progress header.
10. Add draft status indicator.
11. Add review page.
12. Add submit success page.

### 13.3 Acceptance criteria

1. User can open a form on mobile screen size.
2. User can fill text, number, date, select-one, select-multiple.
3. Required fields work.
4. Constraints work.
5. Relevant/skip logic works.
6. Calculations work.
7. Draft can be saved.
8. Finalized web form becomes normal submission.
9. Submission appears in admin submission list.

### 13.4 Required tests

1. `WebFormRuntimeServiceTest`
2. `WebFormValidationServiceTest`
3. `JavaRosaRuntimeSessionTest`
4. `WebFormSerializationServiceTest`
5. Web session API integration test.
6. Vue control component tests.
7. Mobile viewport Playwright test.
8. Web form submission end-to-end test.

---

## Phase 14: Groups and Repeats

### 14.1 Tasks

1. Render groups as sections/cards.
2. Render repeat groups.
3. Add repeat instance.
4. Delete repeat instance.
5. Validate repeat count.
6. Support nested groups.
7. Support simple nested repeats.
8. Serialize repeats correctly into XML.
9. Use JavaRosa repeat behavior wherever possible.
10. Add clear UI for repeat navigation on mobile.

### 14.2 Acceptance criteria

1. Web form can fill repeat groups.
2. Repeat data appears correctly in generated XML.
3. JavaRosa validation accepts generated XML.
4. Admin submission JSON shows repeats correctly.
5. Flattened answer table includes repeat indexes.

### 14.3 Required tests

1. `JavaRosaRepeatServiceTest`
2. `RepeatSerializationTest`
3. `NestedGroupRuntimeTest`
4. `RepeatValidationIntegrationTest`
5. Vue RepeatControl tests.
6. Playwright repeat form flow.

---

## Phase 15: Advanced XForm Logic

### 15.1 Tasks

Support or verify JavaRosa-backed behavior for:

1. `selected()`
2. `count-selected()`
3. `indexed-repeat()`
4. `position()`
5. `count()`
6. `jr:choice-name()`
7. `pulldata()`
8. `choice_filter`
9. `instance()` lookups
10. external CSV lookups
11. entity list lookups
12. calculations depending on repeats
13. relevance depending on repeats

### 15.2 Acceptance criteria

1. Advanced logic works in uploaded forms.
2. Web behavior matches Collect as closely as possible.
3. Unsupported logic produces explicit warnings.
4. No silent failures.

### 15.3 Required tests

1. One test form per advanced function.
2. JavaRosa runtime tests.
3. Web runtime tests.
4. XML output comparison tests.
5. Manual comparison with ODK Collect for selected sample forms.

---

## Phase 16: Media and Geo Controls

### 16.1 Tasks

Implement web controls for:

1. image upload
2. audio upload
3. video upload
4. file upload
5. signature
6. geopoint
7. geotrace
8. geoshape
9. barcode/QR later if feasible

### 16.2 Mobile-first requirements

1. Camera upload must work on phones.
2. File input must be touch-friendly.
3. GPS capture must be simple.
4. Map controls must not break small screens.
5. Attachment upload progress must be visible.
6. Failed upload must be recoverable.

### 16.3 Acceptance criteria

1. Web user can add media.
2. Web user can capture/select GPS point.
3. Final XML references media filename.
4. Attachments are stored.
5. Admin can download attachments.
6. JavaRosa submission validation passes where applicable.

### 16.4 Required tests

1. `WebFormAttachmentServiceTest`
2. `AttachmentStorageServiceTest`
3. Media serialization test.
4. Geo value serialization test.
5. Vue FileControl test.
6. Vue GeoPointControl test.
7. Playwright media flow where possible.

---

## Phase 17: XLSForm Upload and Conversion

### 17.1 Tasks

1. Add XLSForm upload endpoint:

```text
POST /api/projects/{projectId}/forms/upload-xlsform
```

2. Accept `.xlsx`.
3. Validate workbook structure.
4. Detect sheets:
   - survey
   - choices
   - settings
   - entities
5. Preserve additional supported columns.
6. Validate required columns.
7. Call pyxform service or subprocess.
8. Convert XLSForm to XForm XML.
9. Validate generated XML with JavaRosa 4.3.2.
10. Store generated XML as form version.
11. Store conversion warnings.
12. Return conversion result.

### 17.2 XLSForm template integration

Tasks:

1. Use the uploaded ODK XLSForm Template as reference.
2. Add sample forms based on the template.
3. Add tests that confirm supported columns are recognized.
4. Add tests that unsupported columns produce clear warnings.
5. Add docs explaining how to create XLSForms using the template.

### 17.3 Acceptance criteria

1. Admin can upload XLSForm.
2. XLSForm converts to XML.
3. Generated XML validates with JavaRosa.
4. ODK Collect can download generated form.
5. Web form renderer can start a session from generated form.
6. Conversion errors are clear.

### 17.4 Required tests

1. `XlsFormUploadControllerTest`
2. `XlsFormStructureValidatorTest`
3. `PxyformConversionServiceTest` or `PyxformConversionServiceTest`
4. `XlsFormTemplateCompatibilityTest`
5. XLSForm upload integration test.
6. OpenRosa download test for converted form.
7. Web runtime test for converted form.

---

## Phase 18: Generic Reference Data and Entities

### 18.1 Tasks

1. Create generic reference list management.
2. Create reference items.
3. Create item properties.
4. Create parent-child relationships.
5. Create entity lists.
6. Create entity items.
7. Create entity properties.
8. Allow import from CSV.
9. Allow export to CSV.
10. Allow forms to reference lists.
11. Support `entities` sheet concepts where possible.
12. Support list lookups without hardcoded domain names.

### 18.2 Acceptance criteria

1. Admin can create a generic reference list.
2. Admin can add items.
3. Admin can define relationships between items.
4. Form can use reference data.
5. Reporting can group/filter by referenced values generically.
6. No database table is named after a specific geography or domain.

### 18.3 Required tests

1. `ReferenceListServiceTest`
2. `ReferenceItemServiceTest`
3. `EntityListServiceTest`
4. `ReferenceRelationshipTest`
5. CSV import/export test.
6. Frontend reference data page test.

---

## Phase 19: Generic Reporting and Exports

### 19.1 Reporting model

Reports must be built from:

```text
projects
forms
form_versions
submissions
submission_answers
generic reference data
```

### 19.2 Tasks

1. Create report definitions.
2. Allow admin to select source form.
3. Allow admin to select fields.
4. Allow admin to create filters.
5. Allow admin to create groupings.
6. Allow admin to create numeric summaries.
7. Allow admin to create categorical breakdowns.
8. Allow admin to configure date range filters.
9. Allow admin to filter by form version.
10. Allow admin to filter by submitter/source.
11. Add report run endpoint.
12. Add CSV export.
13. Add Excel export later.
14. Add dashboard widgets from saved reports.

### 19.3 Report examples

Generic reports may include:

```text
Total submissions by form
Submissions by date
Submissions by status
Submissions by submitter
Submissions by selected answer field
Average of numeric answer field
Sum of numeric answer field
Count by choice answer
Completion/rejection/duplicate status summary
```

Do not hardcode reports like:

```text
gender coverage
district coverage
parish coverage
village coverage
```

Those can only be created by users through generic field-based configuration if the uploaded form contains such fields.

### 19.4 Acceptance criteria

1. Admin can create a generic report.
2. Admin can choose fields from a form.
3. Admin can filter by selected fields.
4. Admin can group by selected fields.
5. Admin can export CSV.
6. Reports work across different form designs.

### 19.5 Required tests

1. `ReportDefinitionServiceTest`
2. `ReportQueryBuilderTest`
3. `ReportRunServiceTest`
4. `CsvExportServiceTest`
5. Generic answer grouping test.
6. Generic numeric aggregation test.
7. Frontend report builder test.
8. Playwright report run/export test.

---

## Phase 20: Audit Trail

### 20.1 Tasks

Audit:

1. login attempts
2. logout
3. user creation
4. role changes
5. app user creation
6. project creation/update/archive
7. form upload
8. form validation
9. form publishing
10. form assignment
11. OpenRosa form downloads
12. submission receives
13. submission duplicate handling
14. submission edits/replacements
15. report creation
16. report export
17. reference data changes
18. settings changes

### 20.2 Acceptance criteria

1. Important actions are logged.
2. Admin can view audit logs.
3. Audit logs are filterable.
4. Audit logs include actor, action, target, IP, user agent, and timestamp.
5. Security-sensitive events are never missing.

### 20.3 Required tests

1. `AuditServiceTest`
2. Audit repository test.
3. Login audit integration test.
4. Form publish audit integration test.
5. Submission receive audit integration test.
6. Frontend audit log page test.

---

## Phase 21: Testing Framework and Quality Gate

### 21.1 Backend unit tests

Required coverage areas:

1. form upload
2. metadata extraction
3. JavaRosa loading
4. JavaRosa validation
5. OpenRosa XML building
6. submission parsing
7. JSON conversion
8. answer flattening
9. duplicate handling
10. report building
11. reference data
12. audit logging
13. permissions

### 21.2 Backend integration tests

Use:

```text
Spring Boot Test
MockMvc
Testcontainers
Real PostgreSQL/MariaDB container
Multipart requests
XML assertions
```

Required integration tests:

1. upload form XML
2. publish form
3. OpenRosa form list
4. OpenRosa form download
5. OpenRosa manifest
6. OpenRosa submission
7. submission with attachment
8. web form session creation
9. web form answer update
10. web form finalize/submit
11. generic report run
12. auth and permissions

### 21.3 Frontend tests

Use:

```text
Vitest
Vue Test Utils
Playwright
```

Required frontend tests:

1. admin login
2. admin dashboard loads
3. project list
4. form upload
5. form detail
6. submission list
7. submission detail
8. report builder
9. mobile form list
10. mobile form fill
11. mobile validation messages
12. mobile draft save
13. mobile submit flow

### 21.4 OpenRosa contract tests

Required checks:

1. `X-OpenRosa-Version` header.
2. XML namespace correctness.
3. formList XML structure.
4. manifest XML structure.
5. OpenRosaResponse XML structure.
6. Correct HTTP status codes.
7. Basic Auth behavior.
8. Multipart submission behavior.

### 21.5 Manual mobile test checklist

Test with ODK Collect:

1. Configure server URL.
2. Log in.
3. Get blank form list.
4. Download simple form.
5. Fill simple form.
6. Submit simple form.
7. Download form with media.
8. Submit form with attachment.
9. Submit form with repeat group.
10. Submit form with geopoint.
11. Try unauthorized form.
12. Try duplicate submission.

Test with KoboCollect:

1. Configure server URL.
2. Log in.
3. Get blank form list.
4. Download simple form.
5. Fill simple form.
6. Submit simple form.
7. Submit form with attachment.
8. Submit form with repeat group.
9. Try unauthorized form.
10. Try duplicate submission.

### 21.6 Acceptance criteria

1. Tests run in CI.
2. Backend tests pass.
3. Frontend tests pass.
4. OpenRosa contract tests pass.
5. Manual ODK Collect checklist passes.
6. Manual KoboCollect checklist passes.
7. No module is accepted without tests.

---

# 12. Build Order for AI Agent

Follow this order exactly.

```text
01. Project foundation
02. Reference documentation setup
03. Security and access control
04. Project management
05. Form XML upload and storage
06. JavaRosa 4.3.2 integration
07. XForm metadata extraction
08. Form publishing and assignment
09. OpenRosa formList
10. OpenRosa form download and manifest
11. OpenRosa submission upload
12. Submission processing pipeline
13. Admin submission review UI
14. Web form runtime MVP
15. Groups and repeats
16. Advanced XForm logic
17. Media and geo controls
18. XLSForm upload and conversion
19. Generic reference data and entities
20. Generic reporting and exports
21. Audit trail
22. Full testing and compatibility certification
```

---

# 13. Definition of Done

The system is complete when:

1. Admin can create and manage projects.
2. Admin can upload ODK XForm XML.
3. Admin can upload XLSForms based on the ODK XLSForm template.
4. XLSForms are converted to XForm XML.
5. JavaRosa 4.3.2 validates forms.
6. Admin can publish forms.
7. Admin can assign forms to app users.
8. ODK Collect can authenticate.
9. KoboCollect can authenticate.
10. ODK Collect can list forms.
11. KoboCollect can list forms.
12. ODK Collect can download forms.
13. KoboCollect can download forms.
14. ODK Collect can submit forms.
15. KoboCollect can submit forms.
16. Submissions are stored as raw XML.
17. Submissions are converted to JSON.
18. Answers are flattened generically.
19. Attachments are stored and linked.
20. Admin can view and export submissions.
21. User can fill forms through a mobile-first web interface.
22. Web forms use JavaRosa-backed validation where possible.
23. Web submissions go through the same submission pipeline.
24. Reports are configurable and generic.
25. Reference data is generic, not location-specific.
26. Audit logs track important events.
27. Unit tests pass.
28. Integration tests pass.
29. Frontend tests pass.
30. OpenRosa compatibility tests pass.
31. Manual ODK Collect tests pass.
32. Manual KoboCollect tests pass.

---

# 14. Important Rules for AI Agent

Do:

1. Use JavaRosa 4.3.2 as much as possible.
2. Store raw XForm XML.
3. Store raw submission XML.
4. Preserve uploaded form versions.
5. Keep web and mobile submissions in the same pipeline.
6. Build admin UI and user form UI as separate experiences.
7. Make user form UI mobile-first.
8. Keep reference data generic.
9. Keep reports generic.
10. Add tests for every module.
11. Return clear errors and warnings.
12. Keep ODK/OpenRosa compatibility as a first-class requirement.

Do not:

1. Do not hardcode district/parish/village/location tables.
2. Do not hardcode gender/location reports.
3. Do not implement CI3 migration.
4. Do not create a separate web-only form format.
5. Do not bypass JavaRosa for logic that JavaRosa can handle.
6. Do not store only JSON.
7. Do not ignore raw XML.
8. Do not silently ignore unsupported XLSForm columns.
9. Do not silently overwrite submissions.
10. Do not fake OpenRosa responses.
11. Do not skip tests.
12. Do not optimize only for desktop users.

---

# 15. First Task for Codex

Start with this exact task:

```text
Create the initial project foundation for a Spring Boot 3 + Vue 3 ODK-compatible platform.

Backend:
1. Create Spring Boot 3 app named odk-platform.
2. Configure Java 21 or Java 17.
3. Add PostgreSQL database connection.
4. Add Flyway migrations.
5. Add Spring Security.
6. Add JPA.
7. Add test dependencies.
8. Add health endpoint.
9. Add base exception handler.
10. Add audit log foundation.

Frontend:
1. Create Vue 3 + TypeScript + Vite app.
2. Add Vue Router.
3. Add Pinia.
4. Add Axios.
5. Add admin layout.
6. Add mobile form layout.
7. Add placeholder routes for admin and app sides.

Docs:
1. Create docs/reference folder.
2. Add ODK XLSForm Template.xlsx into docs/reference.
3. Create xlsform-template-notes.md documenting the template sheets and important columns.

Tests:
1. Add backend startup test.
2. Add health endpoint test.
3. Add database migration test.
4. Add frontend smoke test.

Do not implement OpenRosa yet.
Do not implement web form rendering yet.
Do not implement reporting yet.
```

After that, continue with:

```text
Implement security, projects, form XML upload, JavaRosa 4.3.2 validation, and then OpenRosa formList/form download.
```

---

# 16. Suggested README Commands

```bash
# Run backend
cd backend
./mvnw spring-boot:run

# Run frontend
cd frontend
npm install
npm run dev

# Run with Docker
docker compose up --build

# Run backend tests
cd backend
./mvnw test

# Run frontend tests
cd frontend
npm run test

# Run frontend e2e tests
cd frontend
npx playwright test
```

---

# 17. Suggested Environment Variables

```env
APP_NAME=ODK Platform
APP_ENV=local

DB_HOST=localhost
DB_PORT=5432
DB_NAME=odk_platform
DB_USERNAME=odk_user
DB_PASSWORD=odk_password

JWT_SECRET=change-me
JWT_EXPIRY_MINUTES=120

STORAGE_DRIVER=local
STORAGE_LOCAL_PATH=./storage

OPENROSA_BASE_URL=http://localhost:8080
OPENROSA_MAX_CONTENT_LENGTH=104857600

JAVAROSA_VERSION=4.3.2
```

---

# 18. Reference Links

Use these as supporting implementation references:

```text
ODK OpenRosa overview:
https://docs.getodk.org/openrosa/

OpenRosa form list:
https://docs.getodk.org/openrosa-form-list/

OpenRosa form submission:
https://docs.getodk.org/openrosa-form-submission/

ODK XForms specification:
https://getodk.github.io/xforms-spec/

JavaRosa:
https://github.com/getodk/javarosa

ODK Collect:
https://github.com/getodk/collect

pyxform:
https://github.com/XLSForm/pyxform

Spring Boot:
https://docs.spring.io/spring-boot/

Spring Security:
https://docs.spring.io/spring-security/reference/

Vue:
https://vuejs.org/
```
