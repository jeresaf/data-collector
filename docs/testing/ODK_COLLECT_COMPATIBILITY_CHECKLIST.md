# ODK Collect Compatibility Checklist

Use this checklist against a running backend and a real ODK Collect device/emulator. Record the exact app version, backend build/commit, project, form, date, and tester before starting.

## Test setup

- [ ] Backend starts with all Flyway migrations applied successfully.
- [ ] A project exists with an active App User username/password.
- [ ] The App User is assigned to the project and to at least one published form.
- [ ] Test form includes representative string, number, date, select/select-multiple, group, repeat, relevance, constraint, calculation, geopoint, image/file upload, and signature fields where supported.
- [ ] At least one form attachment/external CSV dataset is available for manifest testing.

## Server connection and authentication

- [ ] Add the project server URL to ODK Collect using Basic Auth credentials.
- [ ] Invalid credentials are rejected cleanly.
- [ ] Valid credentials can retrieve the form list.
- [ ] Form list displays the expected form ID/version/name.
- [ ] Download works with the advertised `md5:` form hash.

## Form download and manifest

- [ ] Download the published form.
- [ ] Media listed by the manifest downloads successfully.
- [ ] Manifest media hash validation succeeds.
- [ ] External CSV/XML dataset referenced by the form is available offline after download.
- [ ] Updating/publishing a new form version is detected by Collect.

## Data-entry compatibility

- [ ] Required questions block invalid progression/submission.
- [ ] Constraints display the expected constraint message.
- [ ] Relevant questions show/hide correctly after source-answer changes.
- [ ] Calculated values recalculate after dependencies change.
- [ ] `select_one` and `select_multiple` retain expected values/labels.
- [ ] Groups display in the expected hierarchy.
- [ ] Repeats can be added/deleted where the XForm permits it.
- [ ] `repeat_count` prevents/adds repeat instances according to the form definition.
- [ ] Nested repeats preserve the correct parent/child instance values.
- [ ] Geopoint capture saves latitude/longitude and optional altitude/accuracy.
- [ ] Image/file capture attaches the binary and leaves the expected filename in XML.
- [ ] Signature capture submits as the expected image attachment.

## Submission contract

- [ ] A completed instance uploads successfully and Collect marks it sent.
- [ ] The backend stores the exact instance ID.
- [ ] Submission XML is retained and is parseable.
- [ ] Normalized answers match the submitted values, including repeat instance paths/indexes.
- [ ] Attachments are associated with the correct submission.
- [ ] Audit trail contains `SUBMISSION_RECEIVED` for the upload.
- [ ] Re-sending the same instance is rejected as a duplicate (`409`) and audited as `SUBMISSION_DUPLICATE`.
- [ ] A valid replacement using `deprecatedID` creates the replacement/version history and is audited as `SUBMISSION_REPLACED`.

## Offline/resume behavior

- [ ] Start a form offline and save it as draft in Collect.
- [ ] Re-open and finish the saved instance offline.
- [ ] Restore connectivity and send successfully.
- [ ] No media or repeat answers are lost after offline save/resume.

## Result record

| Item | Value |
|---|---|
| ODK Collect version | |
| Backend commit/build | |
| Device / Android version | |
| Project | |
| Form ID / version | |
| Tester | |
| Date | |
| Overall result | PASS / FAIL |
| Failure notes / issue links | |
