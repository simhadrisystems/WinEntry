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

## Architecture Overview

Single-Activity app (`MainActivity`) using Jetpack Navigation with Fragments. The app is an inventory management tool for a retail business dealing in products with four size variants: **QQ, PP, NN, DD**.

### Data Layer

**Room Database** (`AppDatabase`, version 2) — four tables:

| Table | Purpose |
|---|---|
| `products` | Product master; synced read-only from a shared admin-managed Google Sheet (`MASTER_SPREADSHEET_ID`) |
| `purchases` | Purchase orders per product/invoice; full size-level detail |
| `daily_stock` | One row per product per date; opening/closing/sale/price snapshots committed at day-end |
| `day_reconciliation` | Day-end UPI, expenses, cash deposit, total sales totals |

**Key schema rule**: When incrementing `AppDatabase.version`, always write an explicit `Migration(n, n+1)`. `fallbackToDestructiveMigration()` is intentionally absent — Room will throw on unhandled version mismatches. The only exception is v1→v2 (handled via `fallbackToDestructiveMigrationFrom(1)`).

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
- **Home** — sync trigger, sign-in
- **Daily Stock** (`DailyStockFragment` + `DailyStockViewModel`) — core daily workflow; entry mode switches between opening-balance and closing-balance modes
- **Purchases** — purchase entry (`PurchaseEntryFragment`) and list/export (`PurchasesListFragment`)
- **Reports** — monthly summary and report viewer
- **Settings** — product list management, product sort order, opening stock, business info

`DailyStockViewModel` handles the day-to-day entry; `DailyStockDataViewModel` handles import/export/data-management operations for the same screen to keep the core ViewModel focused.

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
- ViewBinding enabled; no DataBinding, no Compose
