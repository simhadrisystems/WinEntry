# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (minified, per-ABI APKs)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.simple.simpleinventory.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug
```

The release build generates separate APKs per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`). For sideloading to testers, use `arm64-v8a`. For Play Store, upload all splits.

## Version History

| App Version | versionCode | DB version | Key changes |
|---|---|---|---|
| 1.0.0 | 1 | 1 | Initial release |
| 1.0.1 | 2 | 2 | — |
| 1.0.2 | 3 | 4 | Day reconciliation crash fix (Android 9), deposits field (Migration 3→4), invite system, OAuth fix |
| 1.1.0 | 4 | 4 | In-app update detection, Getting Started onboarding flow, edge-to-edge (Android 15), delete dialog fixes |

---

## Architecture Overview

Single-Activity app (`MainActivity`) using Jetpack Navigation with Fragments. The app is an inventory management tool for a retail business dealing in products with four size variants: **QQ, PP, NN, DD**.

### Data Layer

**Room Database** (`AppDatabase`, version 4) — four tables:

| Table | Purpose |
|---|---|
| `products` | Product master; synced read-only from a shared admin-managed Google Sheet (`MASTER_SPREADSHEET_ID`) |
| `purchases` | Purchase orders per product/invoice; full size-level detail |
| `daily_stock` | One row per product per date; opening/closing/sale/price snapshots committed at day-end |
| `day_reconciliation` | Day-end UPI, expenses, cash deposit, total sales totals |

**Key schema rule**: When incrementing `AppDatabase.version`, always write an explicit `Migration(n, n+1)`. `fallbackToDestructiveMigration()` is intentionally absent — Room will throw on unhandled version mismatches. The only exception is v1→v2 (handled via `fallbackToDestructiveMigrationFrom(1)`).

Existing migrations: `MIGRATION_2_3` (added day-reconciliation `syncStatus` column), `MIGRATION_3_4` (added `deposits` column to `day_reconciliation`).

Repositories (`DailyStockRepository`, `PurchaseRepository`, `DayReconciliationRepository`) wrap DAOs and are the correct entry point from ViewModels.

### Sync Layer (`sync/` package)

All cloud sync flows through:

1. **`SyncCoordinator`** — orchestrates what to sync and in what order; also manages WorkManager periodic sync (every 6 hours)
2. **`CloudSyncManager`** — low-level Google Sheets API v4 operations; batches all writes to stay under the 60 req/min quota (max 3 API calls per full sync: one batch update, one append, one delete batch)

**Sheet routing:**
- Products → `MASTER_SPREADSHEET_ID` (hardcoded, admin-managed, shared across all users)
- Everything else → per-user spreadsheet ID (stored in `SyncPrefs` SharedPreferences after login)

**Sync status lifecycle** (stored as TEXT in each table row):
- Purchases: `PENDING_INSERT → SYNCED`, `PENDING_UPDATE → SYNCED`, `PENDING_DELETE → hard-deleted after cloud confirms`
- DailyStock / DayReconciliation: `PENDING_UPSERT → SYNCED`
- All tables: `SYNC_ERROR` on failure (retried next sync)

Purchases use a stable `txnId` (format: `yyyyMMdd-HHmmss-XXXX`) as the cloud lookup key — never change this after creation.

### Auth & Roles

Firebase Auth handles login. After sign-in, `AuthViewModel.handleSignedInUser()` fetches the user's Firestore document (`/users/{uid}`) to get:
- `userSheetId` — their personal Google Sheet ID
- `role` — `"editor"` (full access) or `"viewer"` (read-only, down-sync only)

Role is cached in `inventory_prefs` SharedPreferences and read throughout the app via `UserRole.isViewer(context)` / `UserRole.isEditor(context)`. Default role is `"editor"` for backward compatibility.

### UI Structure

Navigation graph with five main destinations reachable via bottom navigation:
- **Home** — sync trigger, sign-in, Getting Started onboarding card
- **Daily Stock** (`DailyStockFragment` + `DailyStockViewModel`) — core daily workflow; entry mode switches between opening-balance and closing-balance modes
- **Purchases** — purchase entry (`PurchaseEntryFragment`) and list/export (`PurchasesListFragment`)
- **Reports** — monthly summary and report viewer
- **Settings** — product list management, product sort order, opening stock, business info

`DailyStockViewModel` handles the day-to-day entry; `DailyStockDataViewModel` handles import/export/data-management operations for the same screen to keep the core ViewModel focused.

### Getting Started Onboarding (`ui/home/`)

New users see a **Getting Started** module card on the Home screen (amber, always bilingual EN+TE). Tapping it opens `OnboardingDialogFragment` — a full-screen dialog with:
- Language toggle (EN / తె) at the top that switches the app language live
- Welcome text and 3-paragraph setup description (bilingual via `AppStrings`)
- 4-step checklist: Business Info, Import Products, Import Sample Data (optional), Setup Opening Stock (optional)
- Steps 3 and 4 are alternatives — completing either one satisfies the "opening balance" requirement
- A User Guide note with an "Open Guide" button at the bottom

`OnboardingViewModel` (`AndroidViewModel`) tracks state as `OnboardingState`:
- `step1Done` = business_name pref non-empty
- `step2Done` = `productDao.getCount() > 0`
- `step3Done` = `inventory_prefs["test_data_imported"]` flag (set on successful `startTestDataImport()`)
- `step4Done` = `dailyStockDao.getEarliestCommittedDate() != null`
- `requiredDone = step1Done && step2Done && (step3Done || step4Done)` — card disappears only when all 3 required steps are done
- Card dismissal is persisted via `inventory_prefs["onboarding_card_dismissed"]`

Navigation clicks in `OnboardingDialogFragment` do **not** call `dismiss()` — the dialog stays in `childFragmentManager` and re-appears automatically when the user navigates back from BusinessInfo or OpeningStock.

### In-App Update Detection (`ui/update/AppUpdateManager.kt`)

Called once per cold start from `HomeFragment.onResume()` via a companion-object session flag. Uses Google Play In-App Update API:
- `FORCE_UPDATE = true` (BuildConfig field) → `IMMEDIATE` mode from day 0
- Staleness 7–59 days → `FLEXIBLE` (dismissible Play prompt)
- Staleness ≥ 60 days → `IMMEDIATE` (blocking Play overlay)
- Always skipped in debug builds

`buildConfigField("Boolean", "FORCE_UPDATE", "false")` is set in `app/build.gradle.kts` — change to `"true"` before critical patch releases.

Settings screen has a "Check for Update" button wired to `AppUpdateManager.checkForUpdatesManually()`.

### Bilingual String System (`utils/AppStrings.kt`)

All user-visible strings live in `AppStrings` as `L(en, te)` objects. `LangPrefs` persists the selected language (`"lang_prefs"` SharedPreferences). Both have a `set(context, lang)` method — the onboarding dialog calls `LangPrefs.set()` directly when the user toggles language inside the dialog.

New `AppStrings` entries added in v4: `onboardingCardTitle`, `onboardingCardDesc`, `onboardingTitle`, `onboardingWelcome`, `onboardingDesc`, `onboardingChecklist`, `onboardingStep1`–`onboardingStep4`, `onboardingUserGuideNote`, `onboardingUserGuideBtn`.

### Dialog Pattern (`utils/AppDialogs.kt`)

All dialogs must go through `AppDialogs`. Methods:
- `info()` — single-button info
- `confirm()` — Cancel + action
- `destructive()` — Cancel + **red** action button (for deletes/sign-out)
- `toggle()` — Close + dynamic action (for on/off settings)
- `withTextInput()` — action button gated on user typing a required word (e.g. "DELETE"); button stays red and disabled until text matches
- `withEmailInput()` — action button gated on user typing their sign-in email exactly

Never call `MaterialAlertDialogBuilder` directly in fragments. The red-button tint (`R.color.app_color_delete`) is applied inside `AppDialogs` — callers don't need to handle it.

### Product Codes

Products have a `brandCode` (primary, e.g. `"W1249"`) and optional `aliases` (comma-separated alternative codes). The four size codes are auto-derived: `qqCode`, `ppCode`, `nnCode`, `ddCode`. `ProductCodeResolver` builds lookup maps for matching import rows to canonical products using both primary codes and aliases.

### Excel I/O

- `ExcelHelper` / `PurchaseExcelHelper` — export purchases to `.xlsx`
- `DailyStockExcelHelper` — export daily stock
- `DailyStockImportHelper` — import daily stock from Excel
- All use Apache POI 5.2.5

### Google Sheets Column Layouts

**Purchases tab** (A–AB): `TxnId | Date | ProductCode | ProductName | InvoiceNo | Supplier | QQ_Boxes | QQ_Loose | QQ_Total | QQ_Price | QQ_Cost | PP_... | NN_... | DD_... | TotalCost | Notes`

**DailyStock tab** (A–X): `date | productCode | openQq..openDd | closeQq..closeDd | saleQq..saleDd | priceQq..priceDd | amountQq..amountDd | saleAmount | isCommitted`

## Design System — Dark Mode Rules

These conventions exist to prevent dark mode regressions. Every layout change must follow them.

### Text Colors — NEVER use hardcoded hex

| Wrong | Correct |
|---|---|
| `android:textColor="#212121"` | `android:textAppearance="@style/TextAppearance.App.CardTitle"` |
| `android:textColor="#555555"` | `android:textAppearance="@style/TextAppearance.App.CardDescription"` |
| `android:textColor="#757575"` | `android:textAppearance="@style/TextAppearance.App.SectionHeader"` |
| `android:textColor="?android:attr/textColorPrimary"` | `android:textColor="?attr/colorOnSurface"` |
| `android:textColor="?android:attr/textColorSecondary"` | `android:textColor="?attr/colorOnSurfaceVariant"` |

Defined in `values/themes.xml`. Each style resolves to `?attr/colorOnSurface` or `?attr/colorOnSurfaceVariant` — semantic tokens that flip automatically in dark mode.

For accent/status colors that must survive dark mode, add a night override in `values-night/colors.xml` (e.g. crimson `#880E4F` → `#F48FB1` pink for dark surfaces).

### Card Backgrounds — always use style, never hardcode

```xml
<!-- Standard interactive card -->
<com.google.android.material.card.MaterialCardView
    style="@style/Style.App.Card" ... />

<!-- Elevated detail card -->
<com.google.android.material.card.MaterialCardView
    style="@style/Style.App.Card.Elevated" ... />
```

Both styles set `cardBackgroundColor="?attr/colorSurface"` which adapts automatically. Never set `app:cardBackgroundColor` with a hardcoded color.

### Dialogs — always use AppDialogs, never call MaterialAlertDialogBuilder directly

```kotlin
// Info (1 button)
AppDialogs.info(context, "Title", "Message")

// Confirmation (Cancel + action)
AppDialogs.confirm(context, "Title", "Message", "Action Label") { doIt() }

// Destructive (Cancel + red-intent action)
AppDialogs.destructive(context, "Title", "Message", "Delete") { doIt() }

// Toggle setting (Close + dynamic action)
AppDialogs.toggle(context, "Title", statusMessage, toggleLabel) { toggle() }
```

`AppDialogs` is in `utils/AppDialogs.kt`. Adding it here ensures `MaterialAlertDialogBuilder` is always called with the correct context and button patterns stay consistent.

### Popup Menus — always use AppCompat variant

```kotlin
// Correct — theme-aware
import androidx.appcompat.widget.PopupMenu

// Wrong — system-level, ignores Material theme
import android.widget.PopupMenu
```

The toolbar's `android:theme=ThemeOverlay.AppCompat.Dark.ActionBar` bleeds into any popup that doesn't use AppCompat. The `popupTheme` on `Widget.SimpleInventory.Toolbar.WithMenu` uses `ThemeOverlay.App.PopupMenu`, which resolves to Light in `values/` and Dark in `values-night/`.

### Summary: the golden rule

**Never put a literal hex color on text in a layout file.** Use `@style/TextAppearance.App.*`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`, or a `@color/app_*` name that has a `values-night/colors.xml` override.

---

## Key Dependencies

- **Room 2.6.1** with KSP (not KAPT) for DAOs
- **Navigation 2.7.6** — single nav graph, nav_graph.xml
- **Firebase BOM 33.1.0** — Auth + Firestore KTX
- **Google Sheets API v4** + `google-api-client-android:2.2.0`
- **WorkManager 2.8.1** — background periodic sync
- **Apache POI 5.2.5** — Excel import/export (requires packaging exclusions already set in `app/build.gradle.kts`)
- **Material 1.12.0** — required for `MaterialButtonToggleGroup` used in the onboarding dialog language toggle
- **Activity KTX 1.8.0** — `ActivityResultContracts` for in-app update launcher
- **Play In-App Update KTX 2.1.0** (`com.google.android.play:app-update-ktx`) — flexible and immediate update flows
- ViewBinding enabled; no DataBinding, no Compose
