# KoboCollect Compatibility Checklist

Run this separately from the ODK Collect checklist. KoboCollect is OpenRosa-compatible, but it must be certified as its own client rather than assuming ODK Collect results automatically apply.

## Test setup

- [ ] Backend starts cleanly and the target project/form is published.
- [ ] A dedicated App User has project/form access.
- [ ] Record KoboCollect version, Android version, backend build/commit, project, form, date, and tester.
- [ ] Use a representative form containing core types, choices, groups/repeats, logic, geo and media.

## Authentication and form retrieval

- [ ] Configure KoboCollect with the project server URL and Basic Auth credentials.
- [ ] Invalid credentials fail without exposing server internals.
- [ ] Valid credentials retrieve the expected form list.
- [ ] Form ID, version, title and `md5:` hash are accepted.
- [ ] Form XML downloads successfully.
- [ ] Manifest/media files download and their MD5 values are accepted.

## Form behavior

- [ ] Required/constraint behavior matches the XForm.
- [ ] Relevance and calculations update after dependent answers change.
- [ ] Choices retain correct stored values.
- [ ] Groups and nested groups render without losing question context.
- [ ] Repeats and nested repeats preserve instance ordering and values.
- [ ] Geo values submit in ODK-compatible coordinate format.
- [ ] Images/files/signatures are uploaded with the expected filenames.
- [ ] External CSV/XML-backed selections work after form download and offline use.

## Submission behavior

- [ ] Completed instance uploads successfully.
- [ ] Backend stores instance ID, source XML and normalized answers.
- [ ] Submission attachments are linked correctly.
- [ ] Audit trail contains `SUBMISSION_RECEIVED`.
- [ ] Exact duplicate resend returns conflict and is audited.
- [ ] Replacement submission using `deprecatedID` is accepted/versioned and audited.
- [ ] Large multipart submissions stay within configured server limits and return a clear error when limits are exceeded.

## Offline behavior

- [ ] Download form/media, disconnect network, complete/save an instance, reconnect, and send it.
- [ ] Repeat data, geo values and media survive the offline cycle.
- [ ] Failed sends remain retryable in KoboCollect.

## Result record

| Item | Value |
|---|---|
| KoboCollect version | |
| Backend commit/build | |
| Device / Android version | |
| Project | |
| Form ID / version | |
| Tester | |
| Date | |
| Overall result | PASS / FAIL |
| Failure notes / issue links | |
