# ODK Platform - System User & Administration Manual

**Build documented:** 15 August 2026  
**Purpose:** From initial setup to data collection, submission review, reporting and audit.

> This companion Markdown manual summarizes the same operational workflow as the formatted Word manual. The Word document is the primary delivery.

## 1. System workflow

`Users -> Project -> Reference Data (if needed) -> Form Upload -> Validate -> Publish -> Assign Access -> Collect Data -> Review Submission -> Report / Export -> Audit`

### Identity types

- **Web User:** signs into the browser application.
- **App User:** authenticates from ODK Collect/KoboCollect through OpenRosa.
- Browser **Web access** and mobile **App User form assignments** are separate.

## 2. Main navigation

- **Projects** - create/open projects and manage forms, project submissions, reference data, reports, audit, users/access and settings.
- **Submissions** - cross-project submission list.
- **Forms** - browser data collection workspace.
- **Users** - SYSTEM_ADMIN identity administration.
- **Sign out** - end browser session.

## 3. Start a new project

1. Sign in with a Web User account.
2. If SYSTEM_ADMIN, create required Web Users and App Users under **Users**.
3. Open **Projects** -> **New Project**.
4. Enter project name and optional description.
5. Open the project.
6. Configure **Users & Access**.
7. Configure **Settings**.
8. Load **Reference Data** if the form needs it.
9. Upload XForm XML or XLSForm.
10. Open Form Detail -> **Versions** and publish the intended validated version.
11. Grant browser access in Form Detail -> **Web access**.
12. Configure App User mobile form assignments for ODK/Kobo.
13. Test collection before field deployment.

## 4. Browser data collection

1. Open **Forms** (`/app/forms`).
2. Click **Start form**.
3. Answer questions using **Next** and **Back**.
4. Use **Save Draft** when needed.
5. Resume from **Drafts**.
6. At the end, **Validate Form** and **Review answers**.
7. Correct anything with **Continue editing**.
8. Choose **Continue to submit**.
9. Confirm **Submit form**.
10. View the final instance under **Submitted** or in administration **Submissions**.

Supported controls include text, numbers, dates/times, select-one/multiple, groups, repeats, geo, image/audio/video/file upload, signature and barcode scanning/manual fallback.

## 5. ODK Collect / KoboCollect

Use the App User credentials and project OpenRosa URL:

`https://<host>/v1/projects/<PROJECT_UUID>`

Only assigned **published** forms should be visible.

Typical workflow:

1. Configure the project URL, App User username and password.
2. Refresh/download blank forms.
3. Download form media/external resources.
4. Fill online or offline.
5. Save/resume as needed.
6. Finalize.
7. Reconnect if offline.
8. Send the finalized form.
9. Confirm the submission in the web administration interface.

## 6. Submission review

Open **Submissions** and select a record.

Detail tabs:

- Answers
- Raw XML
- JSON
- Attachments
- Audit / Events
- Metadata

Authorized managers can use **Update Status**, select a status such as `APPROVED`, enter review notes and confirm.

## 7. Reports

Project -> **Reports**:

1. New Report
2. Select source form
3. Select fields
4. Group if needed
5. Aggregate with NONE / COUNT / SUM / AVG / MIN / MAX
6. Add filters
7. Save Report
8. Run Report
9. Apply From/To dates
10. Export CSV or Excel

## 8. Reference data

Project -> **Reference Data** supports:

- lists
- items
- labels/codes
- sort order
- active state
- generic properties
- parent relationships
- CSV import
- CSV export

## 9. Audit

Project -> **Audit** can filter by action, resource type, status, actor, IP address and date/time range.

Important events include authentication, user lifecycle, project changes, form lifecycle/assignment, OpenRosa downloads, submission receipt/duplicate/replacement/status changes, reports, reference-data changes and settings changes.

## 10. Mobile assignment API note

The current audited backend supports mobile App User form assignment, but a verified current UI control was not located.

Assign forms:

```http
POST /api/projects/<PROJECT_UUID>/app-users/<APP_USER_UUID>/form-assignments
Content-Type: application/json

{
  "formIds": ["<FORM_UUID_1>", "<FORM_UUID_2>"]
}
```

Read active assignments:

`GET /api/projects/<PROJECT_UUID>/app-users/<APP_USER_UUID>/form-assignments/active`

Remove one:

`DELETE /api/projects/<PROJECT_UUID>/app-users/<APP_USER_UUID>/form-assignments/<FORM_UUID>`

See the Word manual for the full step-by-step system instructions, role guidance, troubleshooting and deployment checklist.
