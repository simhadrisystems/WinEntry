---
name: Cloud Function sheet creation status
description: Current state of onAdminRequestCreated CF and pending fix for Drive API
type: project
---

Cloud Function `onAdminRequestCreated` (Firestore trigger on /admin_requests/{uid}) is deployed and partially working:
- Firestore /admin_requests doc creation: ✓
- UserRegistry sheet row append: ✓ (after sharing with appspot SA)
- User sheet creation: FAILING with "The caller does not have permission"

**Why:** Google Drive API likely not enabled in the project. `spreadsheets.create` requires Drive API even with Sheets scope.

**Fix:** Google Cloud Console → APIs & Services → Library → "Google Drive API" → Enable

**Why:** Service account key file (`winentry/service-account.json`) is now deployed (removed from firebase.json ignore list). Auth uses explicit keyFile, not ADC.

**How to apply:** After enabling Drive API, delete admin_requests/{uid} and users/{uid} Firestore docs, retrigger from tester device.
