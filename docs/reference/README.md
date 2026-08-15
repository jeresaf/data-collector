# Reference Documents

This folder contains authoritative reference material for XLSForm support.

## Files

```text
ODK XLSForm Template.xlsx
xlsform-template-notes.md
```

## Purpose

The `ODK XLSForm Template.xlsx` workbook should guide XLSForm upload validation, parser behavior, conversion warnings, and test fixtures.

The platform should treat this workbook as a reference maintained alongside the XLSForm standard and ODK documentation.

## Required Implementation Rule

The backend must use JavaRosa 4.3.2 as much as possible for ODK/XForms behavior. XLSForm parsing and conversion code should not reinvent ODK form behavior.

## Verification

From the project root, run:

```bash
python scripts/check_phase2_reference_docs.py
```
