# Final Physical Compatibility Certification Runbook

Date prepared: 15 August 2026

This runbook is the final manual gate for the ODK-compatible platform after AUDIT-12 through AUDIT-15 passed.

## 1. Current automated baseline

The following implementation gates have been reported green:

- Backend Maven/H2 suite
- PostgreSQL Testcontainers migration/schema gate
- GitHub Actions CI quality gate
- Frontend Vitest suite
- Frontend production build
- Playwright desktop/mobile suite
- AUDIT-13 user-facing web-form workflow shell
- AUDIT-14 expanded browser acceptance flows
- AUDIT-15 web-user/app-user/global-role administration and audit lifecycle

Do not treat these automated results as substitutes for ODK Collect or KoboCollect device execution.

---

# 2. Test environment record

Fill this before starting.

| Item | Value |
|---|---|
| Backend commit SHA | |
| Backend/staging base URL | |
| Project UUID | |
| Project name | |
| Test Form ID | |
| Test Form version | |
| App User username | |
| Device model | |
| Android version | |
| ODK Collect version | |
| KoboCollect version | |
| Tester | |
| Execution date | |

## Required OpenRosa project URL

Use the project-scoped OpenRosa base URL:

`https://<host>/v1/projects/<PROJECT_UUID>`

The client will discover the form list below that base path.

Use HTTPS when sending Basic Authentication credentials.

---

# 3. Prepare one representative certification form

Use one published form that covers as many of these as possible:

- text/string
- integer and decimal
- date/time
- select_one
- select_multiple
- required field
- constraint + constraint message
- relevance
- calculate
- nested groups
- repeat
- nested repeat
- repeat_count
- geopoint
- image/file attachment
- signature
- external CSV/XML data
- at least one form media attachment

Before testing:

- Confirm the form is published.
- Confirm the App User is active.
- Confirm the App User has project access.
- Confirm the App User has access to the certification form.
- Confirm the form appears for this App User only when expected.

---

# 4. ODK Collect certification

## A. Connection and authentication

1. Open ODK Collect.
2. Add a new Project.
3. Choose manual project configuration.
4. Enter:
   - URL: `https://<host>/v1/projects/<PROJECT_UUID>`
   - Username: the dedicated App User username
   - Password: the dedicated App User password
5. Confirm the project is added.
6. Test an intentionally wrong password:
   - Expected: authentication rejected cleanly.
7. Restore the correct password.
8. Select **Get Blank Form** / download forms.
9. Confirm only authorized published forms are shown.

Record:

- [ ] Invalid credentials rejected
- [ ] Valid credentials accepted
- [ ] Correct form list returned
- [ ] Unauthorized forms hidden

## B. Form download and integrity

1. Download the representative form.
2. Confirm the form ID/title/version are correct.
3. Confirm the advertised form MD5 is accepted.
4. Confirm manifest-linked media downloads.
5. Confirm external CSV/XML resources are available.
6. Disconnect network and open the downloaded form.
7. Confirm external resources still work offline.

Record:

- [ ] Form XML downloads
- [ ] MD5 accepted
- [ ] Manifest downloads
- [ ] Media hashes accepted
- [ ] External dataset available offline

## C. XForm behavior comparison

Test each behavior against the same XForm XML:

- [ ] Required validation
- [ ] Constraint message
- [ ] Relevance show/hide
- [ ] Calculation updates
- [ ] select_one stored value
- [ ] select_multiple stored values
- [ ] nested groups
- [ ] repeat add/delete
- [ ] repeat_count
- [ ] nested-repeat parent/child values
- [ ] geopoint
- [ ] image/file attachment
- [ ] signature
- [ ] external-data selection

For any behavior difference, record the exact question/path and expected vs actual result.

## D. Normal submission

1. Complete the form.
2. Finalize it.
3. Send it.
4. Confirm Collect marks the instance sent.
5. In the platform admin UI verify:
   - instance ID
   - raw XML
   - normalized answers
   - repeat paths/indexes
   - attachments
   - source client metadata
   - audit event `SUBMISSION_RECEIVED`

Record:

- [ ] Client reports successful send
- [ ] Instance ID matches
- [ ] Raw XML retained
- [ ] Normalized answers correct
- [ ] Repeats correct
- [ ] Attachments linked
- [ ] `SUBMISSION_RECEIVED` audit event exists

## E. Offline save/resume

1. Download form/media while online.
2. Disconnect the device.
3. Fill part of the form.
4. Save as draft.
5. Close Collect.
6. Re-open the draft offline.
7. Finish it.
8. Reconnect.
9. Send it.

Record:

- [ ] Draft survives restart
- [ ] Repeat answers survive
- [ ] Media survives
- [ ] Geo survives
- [ ] Send succeeds after reconnect

## F. Duplicate submission

Use the same completed instance / same `instanceID` and attempt to resend it.

Expected platform behavior:

- duplicate is rejected
- backend preserves original record
- audit event `SUBMISSION_DUPLICATE` exists

Record:

- [ ] Duplicate rejected
- [ ] Original unchanged
- [ ] Duplicate audit event exists

## G. Replacement submission

Create/send a valid replacement instance that contains `deprecatedID` pointing to the original instance.

Record:

- [ ] Replacement accepted
- [ ] Replacement/version relationship correct
- [ ] `SUBMISSION_REPLACED` audit event exists

ODK Collect overall result:

`PASS / FAIL`

---

# 5. KoboCollect certification

Run this as a separate client certification. Do not inherit ODK Collect results.

## A. Connection

1. Open KoboCollect.
2. Choose **Manually enter project details**.
3. Enter:
   - URL: `https://<host>/v1/projects/<PROJECT_UUID>`
   - Username: dedicated App User username
   - Password: dedicated App User password
4. Add the project.
5. Verify wrong credentials fail.
6. Verify correct credentials retrieve the authorized form list.

Record:

- [ ] Invalid credentials rejected
- [ ] Valid credentials accepted
- [ ] Correct form list returned
- [ ] Unauthorized forms hidden

## B. Download + form behavior

Repeat:

- [ ] XML download
- [ ] MD5 integrity
- [ ] manifest/media
- [ ] external data offline
- [ ] required/constraint
- [ ] relevance/calculations
- [ ] choices
- [ ] groups
- [ ] repeats/nested repeats
- [ ] geopoint
- [ ] attachment/signature

## C. Submission

Verify:

- [ ] normal submission
- [ ] raw XML
- [ ] normalized answers
- [ ] repeat paths/indexes
- [ ] attachments
- [ ] `SUBMISSION_RECEIVED`
- [ ] duplicate rejection + audit
- [ ] replacement + audit
- [ ] offline save/resume/send

KoboCollect overall result:

`PASS / FAIL`

---

# 6. Real mobile browser barcode/media certification

Run on a physical phone browser against the web form UI.

## Barcode

- [ ] Browser requests camera permission.
- [ ] Denying permission leaves manual entry usable.
- [ ] Granting permission opens scanning.
- [ ] Rear-facing camera is preferred where available.
- [ ] A supported barcode is detected and populates the answer.
- [ ] Scanner/camera tracks stop after successful scan.
- [ ] Scanner/camera tracks stop when control/view closes.
- [ ] Re-opening scanner works.
- [ ] Manual fallback remains usable if BarcodeDetector is unavailable.

## Media

- [ ] Image capture/upload works.
- [ ] File upload works.
- [ ] Signature works.
- [ ] Attachment filename is stored in the XForm answer.
- [ ] Attachment bytes arrive in backend storage.
- [ ] Final submission links attachments correctly.

Overall browser-device result:

`PASS / FAIL`

---

# 7. Advanced XForm comparison

Choose at least one advanced form containing several of:

- relevance
- calculations
- constraints
- nested repeats
- repeat_count
- secondary instances
- external CSV/XML data
- instance() lookups
- entities/reference data where applicable

Fill the same answer sequence in:

1. ODK Collect
2. Platform web form

Compare:

- visible question sequence
- calculated values
- relevance transitions
- constraint failures/messages
- repeat creation/order
- stored answer values
- final instance XML

Record every difference as:

| Question/path | ODK Collect | Web form | Match? | Notes |
|---|---|---|---|---|

Overall advanced-XForm result:

`PASS / FAIL`

---

# 8. Final sign-off

Do not mark the platform fully certified until all rows below are PASS.

| Gate | Result |
|---|---|
| ODK Collect authentication/form list | |
| ODK Collect form/media download | |
| ODK Collect XForm behavior | |
| ODK Collect normal submission | |
| ODK Collect duplicate/replacement | |
| ODK Collect offline/resume | |
| KoboCollect authentication/form list | |
| KoboCollect form/media download | |
| KoboCollect XForm behavior | |
| KoboCollect normal submission | |
| KoboCollect duplicate/replacement | |
| KoboCollect offline/resume | |
| Physical web barcode | |
| Physical web media/signature | |
| Advanced XForm ODK-vs-web comparison | |

Final certification:

`PASS / FAIL`

Tester/sign-off:

Date:
