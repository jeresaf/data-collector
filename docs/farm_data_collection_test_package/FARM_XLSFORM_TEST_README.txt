FARM DATA COLLECTION - COMPREHENSIVE XLSFORM TEST PACKAGE

Main form:
  farm_data_collection_test.xlsx

Upload these with the form as media/data attachments:
  farm_registry.csv
  soil_types.xml
  farm_intro.wav

Form ID: farm_data_collection_test
Version: 2026081501

Features covered:
- text/string
- integer and decimal
- date and time
- select_one
- select_multiple
- required
- constraints + constraint messages
- relevance
- calculations
- nested groups
- user-managed repeat (farm_workers)
- repeat_count (plots)
- nested repeat (crop_varieties inside plots)
- nested repeat_count (crop_varieties)
- geopoint
- image capture
- generic file attachment
- signature
- external CSV select + instance() lookup
- external XML select + instance() lookup
- form media attachment (farm_intro.wav)

Suggested test:
1. Upload the XLSForm and all three attachments.
2. Confirm pyxform + JavaRosa validation succeeds.
3. Publish version 2026081501.
4. Assign web/mobile access.
5. Use consent=yes.
6. Select district and verify CSV farm filtering.
7. Verify CSV owner lookup.
8. Verify XML soil pH lookup.
9. Set plot_count=2 or 3.
10. Inside a plot set crop_variety_count=2.
11. Add farm_workers manually.
12. Capture GPS, farm image, file, and signature.
13. Save draft, resume, validate, review and submit.
14. Inspect normalized answers, repeats, XML, JSON, attachments, events, metadata and reports.
