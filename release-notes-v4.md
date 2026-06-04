# v4 — Release Notes (versionCode 4, versionName 1.1.0)

---

## Play Store — "What's New" Text

### English (en-IN)  *(500 character limit)*

```
Getting Started Guide — New users now see a guided setup card on the Home screen. Tap it to open a step-by-step checklist: set up Business Info, import your Product List, and load Opening Stock (or sample data to explore). Switch between English and Telugu inside the guide.

Auto Update Alerts — The app now notifies you when a new version is available on the Play Store.

Bug Fixes — Delete confirmation dialogs now correctly require typed confirmation before deleting.
```
*Character count: ~456*

---

### Telugu (te-IN)  *(500 character limit)*

```
గెటింగ్ స్టార్టెడ్ గైడ్ — కొత్త వినియోగదారులకు స్క్రీన్‌లో సెటప్ కార్డ్ కనిపిస్తుంది. దానిపై నొక్కి వ్యాపార వివరాలు, ప్రొడక్ట్ జాబితా దిగుమతి మరియు ప్రారంభ నిల్వ సెటప్ చేయండి. గైడ్ లోపల English / తెలుగు మార్చవచ్చు.

అప్‌డేట్ నోటిఫికేషన్ — కొత్త వెర్షన్ అందుబాటులో ఉంటే యాప్ స్వయంగా తెలియజేస్తుంది.

బగ్ పరిష్కారాలు — డిలీట్ డైలాగ్‌లు ఇప్పుడు సరిగ్గా నిర్ధారణ కోసం అడుగుతున్నాయి.
```
*Character count: ~390*

---

## Internal Release Notes (for team / admin reference)

### New Features

**1. Getting Started Onboarding (new users)**
- Amber "Getting Started · ప్రారంభం" card appears on the Home screen above the module cards for all new users
- Tapping opens a full-screen bilingual onboarding dialog
- Language toggle (EN / తె) inside the dialog switches the app language instantly — no need to go to Settings
- 4-step checklist displayed as a setup guide:
  1. Set Up Business Information (navigates to Business Info screen; dialog re-opens on return)
  2. Import Product List (inline cloud download with progress indicator)
  3. Import Sample / Test Data — optional; pick a start date, imports test OB + transactions
  4. Setup Opening Stock (manual) — optional; navigates to Opening Stock screen
- Card disappears automatically when steps 1, 2, and either step 3 or 4 are complete
- Snackbar "Trial setup complete — you're all set!" shown on completion
- User Guide link at the bottom of the dialog (opens Google Sites guide in browser)
- **Request Access flow:** if the account is not yet activated by admin, Step 2 button changes to "Request Access" / "యాక్టివేట్" and opens a contact dialog (Email Admin / WhatsApp / Later) using `SupportHelper.IssueType.WORKSPACE_REQUEST`

**2. In-App Update Detection**
- Play Store update check on every cold start (once per process)
- 7–59 days stale → dismissible Flexible update prompt
- 60+ days stale → blocking Immediate update overlay
- `FORCE_UPDATE = true` BuildConfig flag available for critical patch releases
- "Check for Update" button added to Settings screen

### Improvements

**3. Daily Stock — Per-product save / cancel buttons**
- Each product card shows 💾 save and ✖ cancel buttons when that product has unsaved edits
- Buttons disappear immediately on tap; don't wait for LiveData round-trip
- Allows committing individual products without saving the entire screen at once

**4. Daily Stock — Negative sale warnings**
- Sale quantity cells turn red (#C62828) when calculated sale is negative for any size
- Row-level indicator appears when CB exceeds OB + PQ for any size
- Does not block entry — warns so the user can correct the closing balance

**5. Daily Stock — Smart closing entry**
- Closing balance input fields are disabled for products with zero opening balance AND zero purchases
- Prevents nonsensical entries for products with no stock on a given date

**6. Monthly Sale Data — Stale reconciliation indicators**
- Days where the day-end reconciliation was saved but closing balances were subsequently edited show a 🔄 marker on the date cell and blue highlight
- Days with no day-end reconciliation saved show an amber * marker
- Summary banner at top of the month view when any stale or missing reconciliation rows exist

### Bug Fixes

**7. Delete Dialogs — confirmation and styling**
- "Delete All Products" was deleting immediately without showing the "type DELETE" input field — now correctly gated
- All destructive action buttons (Delete, Sign Out, etc.) now consistently show red button text via `AppDialogs.destructive()`
- New `AppDialogs.withTextInput()` for typed-word-gated bulk deletes
- Account deletion "withEmailInput" confirm button now also red

**8. Edge-to-Edge display (Android 15)**
- `enableEdgeToEdge()` applied in `MainActivity` with `SystemBarStyle.dark()` for correct status bar color on all Android versions

### Technical

- Room DB: stays at version 4 (no schema changes in this release)
- `AppDatabase` migrations in place: v1→v2 (destructive fallback), v2→v3 (syncStatus), v3→v4 (deposits)
- New dependency: `com.google.android.play:app-update-ktx:2.1.0`
- Material updated to `1.12.0`, Activity KTX to `1.8.0`
- New color tokens: `module_onboarding` (#D97706 Amber-600), `module_onboarding_light` (#FDE68A Amber-200)
- New SharedPrefs keys: `inventory_prefs["onboarding_card_dismissed"]`, `inventory_prefs["test_data_imported"]`
- Report HTML generation moved to `ReportViewerViewModel` — cached across rotation, no DB re-query on screen flip

### Upload Checklist

- [ ] Set `FORCE_UPDATE = "false"` in `app/build.gradle.kts` (unless this is a critical patch)
- [ ] Run `./gradlew assembleRelease` — generates 3 ABI APKs in `app/release/`
- [ ] Upload all 3 APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) to Play Console
- [ ] Paste "What's New" text for en-IN and te-IN locales
- [ ] Submit for review
