# XLSForm Development Rules

## 1. JavaRosa 4.3.2 First

Use JavaRosa 4.3.2 as the backend authority for XForm behavior wherever possible.

Do not manually recreate:

```text
required checks
constraints
relevance
calculations
repeat behavior
IText behavior
XPath evaluation
submission validation
```

unless JavaRosa cannot provide the needed capability.

## 2. Preserve the Source Files

For XLSForm upload:

```text
original XLSForm file
generated XForm XML
conversion warnings
conversion errors
parsed metadata
```

must be stored.

## 3. Do Not Hardcode Domain Geography

Do not create fixed tables or APIs for:

```text
district
sub_county
parish
village
```

Use generic reference data and entities instead.

## 4. Reporting Must Be Generic

Reports should be based on:

```text
forms
form versions
submitted answers
selected fields
filters
groupings
aggregations
status
source client
date range
submitter
```

Do not hardcode gender or location reports.

## 5. Admin and User Interfaces Must Be Separate

Admin dashboard:

```text
management
tables
filters
publishing
assignments
reports
audit logs
```

User side:

```text
mobile-first
form-oriented
touch-friendly
drafts
validation
review
submit
```

## 6. Unsupported XLSForm Features

Unsupported fields, columns, appearances, or functions must produce clear warnings.

Never silently ignore important form behavior.
