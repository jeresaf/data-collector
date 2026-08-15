# ODK XLSForm Template Reference Notes

## Purpose

This folder contains the authoritative XLSForm template that should guide XLSForm support in the Spring Boot ODK-compatible platform.

The template file is:

```text
docs/reference/ODK XLSForm Template.xlsx
```

This workbook should be treated as a maintained ODK reference alongside the XLSForm standard and ODK documentation. The application should use it to guide XLSForm upload validation, parser expectations, user documentation, and test fixtures.

---

## Template File Metadata

```text
File name: ODK XLSForm Template.xlsx
Stored path: docs/reference/ODK XLSForm Template.xlsx
Size in bytes: 227776
MD5 checksum: e4114e243b19b4ae3713f4c69ea0446f
Reference captured: 2026-07-13 16:16:15 UTC
```

---

## Workbook Sheets Observed

The uploaded template contains the following sheets:

```text
1. survey
2. choices
3. settings
4. entities
5. 👋 Start here
6. ⚙️ Types
7. 👀 Appearances
8. 🔀 Relevance
9. 🔒 Constraints
10. 🌐 Translations
11. 🔍 List lookups
12. ✅ Additional columns
```

The application must not assume that only `survey`, `choices`, and `settings` exist. The `entities` sheet and the reference/helper sheets are important for understanding current and future XLSForm behavior.

---

## Core Authoring Sheets

### 1. `survey`

The `survey` sheet defines questions, groups, repeats, calculations, display hints, validation rules, and logic.

Observed base columns:

```text
type
name
label
hint
required
relevant
appearance
default
constraint
constraint_message
calculation
trigger
choice_filter
parameters
repeat_count
note
image
audio
video
```

Implementation notes:

1. `type` and `name` are central for form structure.
2. `label` and `hint` may be translatable.
3. `required`, `relevant`, `constraint`, `constraint_message`, `calculation`, `trigger`, `choice_filter`, and `repeat_count` must be passed through to the generated XForm behavior.
4. `appearance` must be preserved because it affects rendering in Collect, KoboCollect, and the web form renderer.
5. `parameters` may configure behavior that is not purely visual.
6. Media columns such as `image`, `audio`, and `video` must be preserved and linked to form attachments where applicable.
7. Additional columns may be added by form designers and should not be silently discarded.

### 2. `choices`

The `choices` sheet defines selectable lists for `select_one`, `select_multiple`, and related list-based question types.

Observed base columns:

```text
list_name
name
label
```

Implementation notes:

1. `list_name` groups choices into a named list.
2. `name` is the submitted value.
3. `label` is the user-facing display value and may be translatable.
4. Extra choice columns may be used for choice filters, lookups, or metadata and should be preserved where possible.

### 3. `settings`

The `settings` sheet defines form-level metadata.

Observed base columns:

```text
form_title
form_id
version
instance_name
default_language
style
```

Implementation notes:

1. `form_id` should become the ODK `xmlFormId`.
2. `form_title` should become the display name.
3. `version` must be stored and exposed through OpenRosa.
4. `instance_name` should be preserved for generated instance display behavior.
5. `default_language` affects IText and label selection.
6. `style` may affect web/Enketo rendering and should be preserved.

### 4. `entities`

The `entities` sheet supports ODK Entities-related workflows.

Observed base columns:

```text
list_name
label
```

Implementation notes:

1. Entities should be modeled generically.
2. Do not hardcode domain-specific names like district, parish, village, school, market, or facility.
3. Entity support should map to generic entity lists, entity items, and entity properties.
4. Entity-related form columns such as `save_to` should be supported or warned about clearly.

---

## Reference and Documentation Sheets

### `👋 Start here`

This sheet introduces XLSForms and explains the workbook structure.

Use it as documentation guidance for:

1. End-user help pages.
2. XLSForm upload instructions.
3. Admin upload tooltips.
4. Validation error explanations.

### `⚙️ Types`

This sheet describes survey `type` values.

Use it to guide:

1. Supported question type mapping.
2. Web form control mapping.
3. Unit test fixture coverage.
4. Unsupported type warning messages.

### `👀 Appearances`

This sheet describes appearances and compatibility with mobile/web renderers.

Use it to guide:

1. Web component selection.
2. Mobile-first rendering rules.
3. Warnings for appearances not yet supported by the custom web renderer.

### `🔀 Relevance`

This sheet explains skip logic / relevance.

Use it to guide:

1. JavaRosa-backed relevance evaluation.
2. Web form runtime behavior.
3. Test cases for show/hide logic.

### `🔒 Constraints`

This sheet explains validation constraints.

Use it to guide:

1. Constraint validation messages.
2. Required vs constraint behavior.
3. JavaRosa-backed validation tests.

### `🌐 Translations`

This sheet explains multilingual XLSForms.

Use it to guide:

1. IText extraction.
2. Translation-aware label/hint rendering.
3. Default language handling.

### `🔍 List lookups`

This sheet explains list lookups using choices, entities, CSV, GeoJSON, XML, and the `instance()` function.

Use it to guide:

1. External dataset support.
2. Entity lookup support.
3. Generic reference data design.
4. `pulldata()` / `instance()` style test coverage.

### `✅ Additional columns`

This sheet documents additional optional columns.

Use it to guide:

1. Parser flexibility.
2. Warning behavior.
3. Future support.
4. Regression tests for preserving unknown but valid columns.

---

## Development Rules

1. **JavaRosa-first rule:** Use JavaRosa 4.3.2 for XForm behavior wherever possible.
2. **Do not reinvent ODK logic:** Custom code should adapt JavaRosa output for APIs and UI, not replace ODK behavior.
3. **Preserve raw uploads:** Store the original XLSForm file and generated XForm XML.
4. **Preserve raw XML:** Store the final generated XForm XML exactly as published.
5. **Do not silently discard columns:** Unknown or unsupported columns must produce structured warnings.
6. **Support extra columns:** Extra columns should be preserved in metadata where possible.
7. **Do not hardcode geography:** No district/parish/village-specific schema should be created.
8. **Keep reporting generic:** Reports should be generated from selected form fields and submission answers.
9. **Mobile-first user forms:** User-side form filling must be optimized for phones and touch input.
10. **Admin dashboard separation:** Admin UI should be management-oriented and distinct from the user form-filling UI.
11. **Test every parser rule:** XLSForm parsing, warnings, conversions, and generated XML behavior need unit and integration tests.
12. **Test against ODK mobile clients:** ODK Collect and KoboCollect compatibility must be manually verified.

---

## Suggested XLSForm Parser Behavior

When a workbook is uploaded:

1. Confirm it is a valid `.xlsx`.
2. Confirm a `survey` sheet exists.
3. Confirm a `settings` sheet exists or generate clear warnings when missing.
4. Confirm a `choices` sheet exists when select questions depend on choices.
5. Detect optional `entities` sheet.
6. Read all headers exactly.
7. Normalize known headers for internal processing.
8. Preserve unknown headers as extra metadata.
9. Validate required fields per row depending on row type.
10. Convert using pyxform where applicable.
11. Validate generated XForm with JavaRosa 4.3.2.
12. Store conversion warnings and errors.
13. Show warnings to the admin in the upload UI.
14. Do not publish forms with blocking errors.

---

## Required Tests Based on This Template

### Unit tests

```text
XlsFormWorkbookStructureTest
XlsFormSurveySheetHeaderTest
XlsFormChoicesSheetHeaderTest
XlsFormSettingsSheetHeaderTest
XlsFormEntitiesSheetHeaderTest
XlsFormUnknownColumnWarningTest
XlsFormTemplateCompatibilityTest
```

### Integration tests

```text
XlsFormUploadIntegrationTest
XlsFormToXFormConversionIntegrationTest
JavaRosaGeneratedXFormValidationTest
OpenRosaDownloadConvertedFormTest
WebRuntimeConvertedFormSessionTest
```

### Manual tests

```text
Upload workbook based on ODK XLSForm Template.xlsx
Convert workbook to XForm
Publish converted form
Download in ODK Collect
Download in KoboCollect
Submit test record from ODK Collect
Submit test record from KoboCollect
Start web form session from converted form
Submit web form record
```

---

## Acceptance Criteria for Phase 2

Phase 2 is complete when:

1. `docs/reference/ODK XLSForm Template.xlsx` exists.
2. `docs/reference/xlsform-template-notes.md` exists.
3. The notes identify the core sheets and observed base columns.
4. The notes explain how the system should treat extra/unsupported columns.
5. The notes emphasize JavaRosa 4.3.2 as the behavior authority.
6. The notes explain that reference data and reporting must remain generic.
7. A verification script exists to check the reference docs.
8. The root README or phase checklist links to the reference template.
