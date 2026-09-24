# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (minified, per-ABI APKs)
./gradlew assembleRelease

# Release AAB (for Play Store upload)
./gradlew bundleRelease

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.simple.simpleinventory.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug
```

The release build generates separate APKs per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`). For sideloading to testers, use `arm64-v8a`. For Play Store, upload the **AAB** from `bundleRelease` — Google serves the right ABI per device automatically.

**Gotcha:** `./gradlew bundleRelease assembleRelease` in one invocation silently disables the per-ABI APK splits for both tasks (`splits.abi.isEnable` checks `gradle.startParameter.taskNames` for any "bundle" task, build-wide) — you'll get one fat universal `app-release.apk` instead of three per-ABI ones. Run them as two separate `./gradlew` invocations if you need both a real AAB and real per-ABI APKs from the same source state.

## Version History

| App Version | versionCode | DB version | Key changes |
|---|---|---|---|
| 1.0.0 | 1 | 1 | Initial release |
| 1.0.1 | 2 | 2 | — |
| 1.0.2 | 3 | 4 | Day reconciliation crash fix (Android 9), deposits field (Migration 3→4), invite system, OAuth fix |
| 1.1.0 | 4 | 4 | In-app update detection, Getting Started onboarding flow, edge-to-edge (Android 15), delete dialog fixes |
| 1.2.0 | 5 | 5 | Profit Reports & Date Received — Sales & Profit Margin report, Date Received on purchases, committed price lock, cascade-clear, deactivation guard, auto-activate on import, report watermark, Downloads save |
| 1.2.0 | 6 | 5 | **Internal test only — never shipped to production.** targetSdk 36, edge-to-edge fixes across all screens (including Login, Opening Stock Setup), Settings Drive-Backup crash fix, Excel-imported-purchase wrong-unit-price fix. Superseded by 1.3.0/vc7, which bundles all of this. |
| 1.3.0 | 7 | 5 | Ships everything from vc6 (above) plus **Quick Sale Check** (new Reports scratchpad — see `ui/quicksale/` section below), and the scroll-to-top FAB overlapping the 3-dot menu fix (`ScrollNavigationHelper`, 5 screens). First production release since 1.2.0/vc5. |
| 1.3.0 | 8 | 5 | **Internal test only — never shipped to production.** No user-facing changes from vc7 — `app/proguard-rules.pro` rework to raise Play Console's optimisation/obfuscation/shrinking scores (removed redundant `data.entity`/`data.repository` blanket keeps, narrowed Firebase Auth/Firestore keeps to what their own bundled rules don't already cover, removed a dead `com.google.api.services.drive` keep, added `-repackageclasses`). Regression-tested on-device: Excel import/export for Products and Purchases, Daily Stock, Reports, Quick Sale Check, Google Sign-In, cloud sync — all pass. Uploaded to internal testing to confirm the Play Console score actually moves before any further ProGuard narrowing is attempted. |
| 1.3.0 | 9 | 5 | **Internal test only — discarded at draft stage, never actually released.** No user-facing changes from vc8 — further `app/proguard-rules.pro` narrowing: removed blanket `kotlin.**`/`com.google.api.**` keeps, deduplicated redundant GMS sign-in rules, narrowed Fragment/Worker keeps to name+constructor only, and replaced the app-wide `public <init>()` blanket with targeted POI/xmlbeans (`TypeSystemHolder`) and commons-compress (`ExtraFieldUtils` ZIP extra-field classes) rules. The commons-compress dependency needed its own keep was discovered only after the blanket rule was narrowed — it crashed Excel export (`ExceptionInInitializerError` in `ZipContentTypeManager`) undocumented until this pass. Regression-tested on-device across two build iterations: Product Master import, Purchase import, Excel export, Google Sign-In, cloud sync, all 5 nav destinations — all pass. Local uncompressed DEX ~17.2MB, down from 21.4MB. Uploaded to an Internal Testing release draft to read real Play Console App Bundle Explorer scores (Optimisation 20%, Obfuscation 20%, Shrinking 22%, DEX 18MB — Low tier), then the draft was discarded before publishing. **Gotcha discovered here: Play Console permanently burns a versionCode the moment any AAB is uploaded, even to a discarded draft** — re-uploading vc9 was rejected ("Version code 9 has already been used"), forcing a bump straight to vc10 for the next round with zero further code changes. |
| 1.3.0 | 10 | 5 | **Internal test only — never shipped to production.** No user-facing changes from vc9 — removed the dead `com.google.api-client:google-api-client-android` + `com.google.apis:google-api-services-sheets` dependencies from `app/build.gradle.kts` entirely (not narrowed, deleted). These were unused: sync moved server-side to Cloud Functions long ago per `CloudSyncManager.kt`'s own doc comment, confirmed by zero references anywhere in `app/src/main/java`. Also removed the now-dead Sheets/`GenericJson` keep block from `app/proguard-rules.pro`. Verified via R8 `mapping.txt` diff: total DEX classes 15,269→14,922 (-347), unobfuscated 9,726→9,454 (-272). Play Console confirmed: Optimisation 20%, Obfuscation 21%, Shrinking 23%, DEX 17.7MB — crossed from **Low to Medium** tier. |
| 1.3.0 | 11 | 5 | **Internal test only — never shipped to production.** No user-facing changes from vc10 — removed `org.apache.poi.hssf.**`/`org.apache.poi.wp.**` proguard keeps (413 + 3 classes) and the already-no-op `schemasMicrosoftComOfficeWord.**`/`schemasMicrosoftComVml.**` keeps (matched zero classes in this project's actual jars). Switched `ExcelHelper.importProducts()`'s auto-detecting `WorkbookFactory.create()` to explicit `XSSFWorkbook(inputStream)`, matching every other Excel helper — this was necessary because `WorkbookFactory` lives inside the already-blanket-kept `org.apache.poi.ss.**` package, so removing just the proguard keep didn't shrink HSSF (R8 still saw `WorkbookFactory.create()`'s own bytecode, which directly instantiates `HSSFWorkbook`, as a live entry point regardless of whether app code called it). Every file picker in the app is hard-locked to the `.xlsx` MIME type, so legacy `.xls` was never reachable through the UI either way. Regression-tested on-device: uninstalled/reinstalled (signature mismatch against a debug-signed build already on the test device), sign-in + Firestore sync both re-established automatically, Product Master list rendered real synced data, Import-from-Excel file picker opened correctly filtered to `.xlsx` with no crash on open/cancel. R8 `mapping.txt` diff: 14,922→14,853 total (-69, mostly an obfuscation win not a shrinking win — most HSSF classes stayed present), unobfuscated 9,454→9,090 (-364). Play Console confirmed: Optimisation 23%, Obfuscation 23%, Shrinking 25%, DEX 17.4MB — still Medium tier. **Deliberately stopped here** — remaining blockers (Apache POI core packages ~5,000+ classes; Firebase Auth's own bundled `-keep class com.google.android.gms.internal.** { *; }` rule, which force-keeps 51% of the GMS bucket outside app control) require either a full POI replacement or a Credential Manager migration, both real engineering projects with real regression risk, not further rule-tuning. Not pursued further since the score is cosmetic for a single-shop personal app. |
| 1.4.0–1.4.3 | 12–15 | 6 | Opening Stock re-baseline (`isOpeningStock`, MIGRATION_5_6) and a bugfix chain. vc15 was built but never uploaded; superseded by vc16. |
| 1.4.4 | 16 | 6 | **Internal test only — never promoted to production; superseded by vc17, which bundles all of this.** **Opening Stock redesign** (rule: on a baseline date, OB belongs to Opening Stock and CB belongs to Daily Stock).<br>- `DailyStockDao.upsertCommittedBatch` keeps the `isOpeningStock` marker on baseline dates.<br>- Diff-based OB correction (`DailyStockRepository.planOpeningStockSave`/`applyOpeningStockSave` plus pure `BaselineMath`): a product with a closing entered keeps its CB and has its sale recalculated; a product with no closing moves its CB with the OB.<br>- Opening Stock import only fills the grid.<br>- Cascade, CLEAR_NEXT and the negative-sale check stop at a baseline; the cascade now shifts the stored sale by the OB delta, so the next day's purchases are kept.<br>- Clearing an entry or a date on a baseline date resets the row instead of deleting it.<br>- Baseline picker (`getBaselineCandidates`/`setActiveBaseline`) replaces "Fix Active Baseline Date".<br>- Amber OB row on baseline dates.<br>**Purchases:** Cloud Function `readAll`/append ranges extended to AC (ReceivedDate), deployed 2026-09-23; down-sync restores ReceivedDate onto existing SYNCED rows (`PurchaseDao.repairReceivedDate`).<br>**Daily Stock:**<br>- A draft's CB is always the current OB + PQ.<br>- Drafts with no purchases on their date are deleted.<br>- Saved amounts refresh immediately after a CB save.<br>**Tests:** `BaselineMathTest`, `ReceivedDateParseTest` (JVM) and `BaselineScenarioTest` (instrumented; in-memory Room, no sign-in needed).<br>**In-app update:** `FORCE_UPDATE = true`. Fixed Settings "Check for Update" doing nothing after tapping Update: the FLEXIBLE flow never registered an `InstallStateUpdatedListener` or called `completeUpdate()`, so the download finished and was never installed. The manual check now prefers IMMEDIATE and opens the Play Store if in-app update isn't possible. Home resumes an interrupted IMMEDIATE update on every `onResume()` and asks again if the user dismisses a forced update. |
| 1.4.5 | 17 | 6 | Everything from vc16, plus the **purchase duplicate fix**:<br>- `PurchaseDao.replaceLine` reuses a line's `txnId` and tombstones other synced copies.<br>- Import from cloud reuses the Purchases-tab TxnId.<br>- Restore keeps one copy per line and skips lines already on the device.<br>- Sync routes deletes by `isDeleted`.<br>**Cloud Functions:** run keyless as `firebase-adminsdk-fbsvc` (deployed 2026-09-23, after the service-account key leak).<br>**Tests:** `PurchaseLineDedupTest` (JVM), `PurchaseReplaceLineTest` (instrumented). Sheets that already hold copies are cleaned with `scripts/dedupe-purchases-sheet.gs`. |
| 1.4.6 | 18 | 7 | **Data safety and integrity release.**<br>**Sync:**<br>- Process-wide `SyncCoordinator.syncLock`; overlapping syncs return `SYNC_BUSY`.<br>- A row is marked SYNCED only if it still matches what was sent (`markSyncedIfUnchanged`).<br>- Restores merge (`mergeFromCloud`) and keep rows with unsynced changes or queued deletes.<br>- CF calls are batched at 300 rows.<br>**Delete outbox:** `pending_cloud_deletes` table (**MIGRATION_6_7**). DAO deletes of committed stock and reconciliation queue CF `delete_daily_stock` / `delete_day_summary`, which are sent before writes.<br>**Accounts and roles:**<br>- The account-switch guard (`data_owner_uid`) blocks uploading another account's data.<br>- Viewers never write, in the app or in the CF.<br>- Sample data stays local-only.<br>- Account deletion stops if `clear_all` fails.<br>- Clear Local Data syncs first or needs typed confirmation; `DbSnapshot` copies the DB to `filesDir/backups` first.<br>**Purchases:**<br>- An edit keeps its txnId.<br>- `CommittedSaleRefresher` recomputes committed sales after any purchase change.<br>- `StrictDate` (day-first, serials, null instead of today) and `ExcelCells` (formula cells, whole ids, non-negative quantities) are used by every import.<br>- Imports run in a single transaction.<br>**Daily Stock:**<br>- A failed load shows an error instead of zeros.<br>- Stale loads are dropped.<br>- Totals ignore the search filter.<br>- Clearing an entry, a date, the next day or a baseline cascades from previous CB + that day's PQ.<br>- Stored prices are kept on re-save.<br>- The closing import cascades.<br>- Unanswered next-day prompts are applied as Update OB.<br>**Products:**<br>- Re-import keeps ids and blank values.<br>- Delete and type change are blocked when history exists.<br>- Sort and activation write single columns.<br>**Settings:** Check Data Integrity (`IntegrityChecker`) and Re-upload All Data.<br>**Room:** schema export on (`app/schemas`).<br>**Tests:** `SyncSafetyTest` (incl. 6→7 migration), `IntegrityScenarioTest`, `ProductIntegrityTest`, `StrictDateTest`, `ExcelCellsTest`. |

### v1.2.0 — V5 Changes (2026-06-09)

#### Daily Stock

| # | Change | Files |
|---|--------|-------|
| 1 | **Cascade dialog 3-way** — "Update OB" / "Clear Next Day" (deletes next committed row for affected products) / "Skip" | `DailyStockViewModel.kt`, `DailyStockFragment.kt` |
| 2 | **Clear Product Entry (Current Date)** — 3-dot menu item; picks committed product from list, deletes row, cascades OB to next day | `DailyStockFragment.kt`, `DailyStockDataViewModel.kt`, `DailyStockRepository.kt` |
| 3 | **Clear Entry from CB dialog** — "Clear Entry" button (red, left-aligned) in `ClosingEntryDialog`; confirm → delete today's row → cascade-update next day's OB | `ClosingEntryDialog.kt`, `DailyStockFragment.kt`, `DailyStockDataViewModel.kt`, `DailyStockRepository.kt` |
| 4 | **Auto-activate product on purchase** — Excel import and cloud down-sync now activate inactive products just like manual entry | `ProductDao.kt`, `PurchaseViewModel.kt`, `SyncCoordinator.kt` |
| 5 | **Deactivation guard** — cannot deactivate a product that has any committed non-zero closing balance in `daily_stock` | `DailyStockDao.kt`, `DailyStockRepository.kt`, `ProductOrderViewModel.kt`, `ProductOrderFragment.kt` |

#### Purchases

| # | Change | Files |
|---|--------|-------|
| 6 | **`receivedDate` field** — new nullable-blank field on `Purchase`; DB migration 4→5; daily stock PQ lookup uses effective date (`COALESCE(NULLIF(receivedDate,''), purchaseDate)`) | `Purchase.kt`, `AppDatabase.kt`, `PurchaseDao.kt`, `PurchaseRepository.kt`, `DailyStockViewModel.kt` |
| 7 | **Date Received picker at entry** — in `PurchaseEntryFragment`; defaults to invoice date, auto-syncs when invoice date changes; validation: min = invoice date, max = today+1; locked in insert/add-another mode | `PurchaseEntryFragment.kt`, `PurchaseViewModel.kt`, `fragment_purchase_entry.xml` |
| 8 | **Date Received editable in purchase list** — double-tap the received-date footer row to open date picker; updates all rows of that invoice atomically via batch SQL | `GroupedPurchasesAdapter.kt`, `PurchasesListFragment.kt`, `PurchaseDao.kt`, `PurchaseRepository.kt`, `PurchaseViewModel.kt` |
| 9 | **Purchase list display enhancements** — invoice stats row (total boxes/loose/units) below invoice header; received date footer at end of each invoice group | `GroupedPurchasesAdapter.kt`, `item_purchase_grouped.xml` |
| 10 | **Date Received in Excel** — export writes `RECEIVED DATE \| [value]` at col 2-3 of DATE row; import reads col 3 with date-pattern guard (plain numbers silently ignored for backward compat) | `PurchaseExcelHelper.kt` |
| 11 | **Date Received in cloud sync** — column AC (index 28) in Purchases sheet; `toSheetRow()` and `parsePurchasesTabRows()` updated | `CloudSyncManager.kt` |

#### Reports

| # | Change | Files |
|---|--------|-------|
| 12 | **Sales & Profit Margin Report** — date-range report; per-product: sale qty by size, buy price, sell price, margin/unit, sale amount, purchase cost, gross margin, margin%; grand total row | `ReportViewerViewModel.kt`, `ReportViewerFragment.kt`, `ReportsFragment.kt`, `fragment_reports.xml`, `colors.xml` |

#### File Export

| # | Change | Files |
|---|--------|-------|
| 13 | **Save to Downloads** — all 11 Excel export points now automatically save to public Downloads folder (MediaStore API on Android 10+; direct file on Android 8-9) and show "Saved to Downloads: filename" toast, then still open share chooser | `FileDownloadHelper.kt` (new), `DailyStockFragment.kt`, `PurchasesListFragment.kt`, `OpeningStockFragment.kt`, `ProductListFragment.kt` |

#### Key DB / Schema Notes

- `MIGRATION_4_5`: `ALTER TABLE purchases ADD COLUMN receivedDate TEXT NOT NULL DEFAULT ''`
- `DailyStockDao.hasNonZeroClosingBalance(productCode)` — new query for deactivation guard
- `ProductDao.activateByIds(ids)` — new batch-activation query
- `PurchaseDao.updateReceivedDateForInvoice(...)` — new batch UPDATE by invoice group
- Effective-date queries: `getPurchasesByEffectiveDateSync`, `getPurchasesByEffectiveDateAndProduct`, `markPurchasesAsProcessedByEffectiveDate`

---

## Architecture Overview

Single-Activity app (`MainActivity`) using Jetpack Navigation with Fragments. The app is an inventory management tool for a retail business dealing in products with four size variants: **QQ, PP, NN, DD**.

### Data Layer

**Room Database** (`AppDatabase`, version 7, schema exported to `app/schemas`) — main tables:

| Table | Purpose |
|---|---|
| `products` | Product master; synced read-only from a shared admin-managed Google Sheet (`MASTER_SPREADSHEET_ID`) |
| `purchases` | Purchase orders per product/invoice; full size-level detail |
| `daily_stock` | One row per product per date; opening/closing/sale/price snapshots committed at day-end |
| `day_reconciliation` | Day-end UPI, expenses, cash deposit, total sales totals |
| `pending_cloud_deletes` | Outbox of local deletes the cloud sheet has not applied yet (see Sync Layer) |

**Key schema rule**: When incrementing `AppDatabase.version`, always write an explicit `Migration(n, n+1)`. `fallbackToDestructiveMigration()` is intentionally absent — Room will throw on unhandled version mismatches. The only exception is v1→v2 (handled via `fallbackToDestructiveMigrationFrom(1)`).

Existing migrations: `MIGRATION_2_3` (added day-reconciliation `syncStatus` column), `MIGRATION_3_4` (added `deposits` column to `day_reconciliation`).

Repositories (`DailyStockRepository`, `PurchaseRepository`, `DayReconciliationRepository`) wrap DAOs and are the correct entry point from ViewModels.

### Sync Layer (`sync/` package)

All cloud sync flows through:

1. **`SyncCoordinator`** — orchestrates what to sync and in what order; also manages WorkManager periodic sync (every 6 hours)
2. **`CloudFunctionClient`** — calls the Cloud Functions (`syncUserSheet` etc., see Cloud Infrastructure below) that perform the actual Google Sheets API v4 operations server-side
3. **`CloudSyncManager`** — row-parsing utilities and entity-to-row serializers only (no live Sheets API calls on-device — that moved server-side to Cloud Functions; see `google-api-client-android`/`google-api-services-sheets` removal in vc10 above, which confirmed this via zero references anywhere in `app/src/main/java`)

**Sheet routing:**
- Products → `MASTER_SPREADSHEET_ID` (hardcoded, admin-managed, shared across all users)
- Everything else → per-user spreadsheet ID (stored in `SyncPrefs` SharedPreferences after login)

**Sync status lifecycle** (stored as TEXT in each table row):
- Purchases: `PENDING_INSERT → SYNCED`, `PENDING_UPDATE → SYNCED`, `PENDING_DELETE → hard-deleted after cloud confirms`
- DailyStock / DayReconciliation: `PENDING_UPSERT → SYNCED`; deletes go through `pending_cloud_deletes` (`DailyStockDao.deleteDailyStock`/`deleteAllForDate`, `DayReconciliationDao.deleteByDate*` queue them; `*LocalOnly` variants and `deleteAll()` do not)
- All tables: `SYNC_ERROR` on failure (retried next sync)

Purchases use a stable `txnId` (format: `yyyyMMdd-HHmmss-XXXX`) as the cloud lookup key — never change this after creation.

**One active row per purchase line.** A line is product + invoice number + invoice date. Every path that saves a line (Excel import, manual entry, Import from cloud, Restore) goes through `PurchaseDao.replaceLine()`. It:
- reuses the replaced line's `txnId`, so the Cloud Function upsert updates that sheet row
- tombstones other already-synced copies (`PENDING_DELETE`), so sync deletes them from the cloud

Import from cloud (the PurchaseImport tab) takes the TxnId the line already has in the Purchases tab (`CloudSyncManager.cloudLineIndex`). Restore keeps the newest copy per line (`newestPerLine`) and skips lines already on the device.

Before this (up to vc16), each re-import hard-deleted the local row and inserted a new `txnId`. That appended a full copy to the sheet: one user sheet reached 3,736 rows for 1,003 lines, and Restore swapped between the copies. `scripts/dedupe-purchases-sheet.gs` cleans such a sheet (dry run first, backup tab).

### Cloud Infrastructure & Billing (GCP project `winentry-a87f2`, `functions/`)

Cloud Functions (`functions/index.js`, region `asia-south1`) back the invite/sheet-provisioning flow: `createUserSheet`, `registerUserOnly`, `syncUserSheet`, `deleteUserRegistration`, `getMasterProducts` (HTTPS), plus `onAdminRequestCreated` / `onInvitedUserAdded` (Firestore triggers). `CloudFunctionClient.kt` calls these for every sync — this means Cloud Functions/Firestore usage genuinely scales with active users, unlike Secret Manager (below).

**Secret Manager gotcha** (cost WinEntry ~₹18–34/year until fixed 2026-07-24): secret versions are immutable — every `firebase functions:secrets:set NAME` (or console edit) creates a **new version** rather than overwriting, and old versions keep counting against the free 6-version-replica/month quota (and get billed past it) even while **disabled** — only **destroying** a version removes it from the billable count. `ADMIN_OAUTH_REFRESH_TOKEN` had accumulated 8 versions (7 stale, from OAuth setup/debugging in April 2026) before cleanup. Since `defineSecret("NAME")` in `functions/index.js` has no version pin, it always reads the latest version, so old versions are always safe to destroy. **After rotating any of `ADMIN_OAUTH_CLIENT_ID` / `ADMIN_OAUTH_CLIENT_SECRET` / `ADMIN_OAUTH_REFRESH_TOKEN`, go destroy the superseded version in Secret Manager** — don't just leave it disabled.

**No service-account keys (since 2026-09-23)**: every function sets `runWith({ serviceAccount: SHEETS_SA })` (`firebase-adminsdk-fbsvc@…`, the identity all sheets are shared with) and `GoogleAuth` uses runtime credentials. The old `service-account.json` key leaked via the public GitHub repo and was deleted, along with the unused "WinEntry Master Sheets Read" API key and a duplicate "WinEntry Functions" OAuth client. Never create a new SA key; `.gitignore` blocks `service-account*.json`. The repo is public, so secret-scan unpushed history before every push.

**User sheets live in two Drive folders.** Early sheets are in the admin's old personal-Drive folder; newer ones are in `SHARED_FOLDER_ID` (Simhadri Systems Drive). Both are shared with `SHEETS_SA`, so `findUserSheetByTitle` (title `WinEntry – <email with _at_ and _>`) searches as the SA to find either. `syncUserSheet` re-links Firestore `userSheetId` when the linked sheet is gone (`sheetRelinkedAt` field). It never auto-creates a blank sheet: rows already SYNCED would not be re-sent. Incident 2026-09-23: user y7PXXNcr had been re-registered, given a blank new-folder sheet (later deleted), and failed every sync. They were re-linked by hand to their old-folder sheet (last data 3 Apr 2026). April–Sept exists only on their phone until vc18's re-upload.

**IAM**: keep project IAM (Console → IAM & Admin → IAM) limited to the Owner account and the 3 auto-created service accounts (`...compute@developer`, `...appspot@`, `firebase-adminsdk-fbsvc@...`). Do **not** add app testers here — that was done once (6 accounts, added pre-Play-Store-testing) and had to be cleaned up. Use **Play Console → Testing → Internal/Closed testing** for testers instead; it grants zero GCP/Firestore access.

**Billing account** (`01B24F-417A5A-AB2FB5`, ID visible under Billing → Account management) also covers two other projects, both with billing intentionally **disabled** (confirmed zero GCP-billable usage — no Cloud Functions/SQL/Storage/Secret Manager, only Play Games Services + Play Billing which don't need Cloud Billing):
- `esudoku` (~/Downloads/eSudoku)
- `simple-inventory-e5ed9` — legacy project from before the Simple Inventory → WinEntry rebrand

A budget alert (₹50/year, 50/90/100% thresholds) is configured on the billing account as a tripwire for future surprises.

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
- **Reports** — monthly summary, report viewer, and Quick Sale Check (scratchpad)
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

### Quick Sale Check (`ui/quicksale/`) — shipping in 1.3.0/vc7, started 2026-09-13

A scratchpad version of the Daily Stock workflow, reachable from Reports → "Quick Sale Check" (next to "Daily Stock Sheet"). Lets the user sanity-check a day's sale numbers — type Opening/Purchase/Closing (or toggle to "Direct Qty" mode and type Sale Qty directly) per product/size, computed against current Products-module sale prices — **without ever reading from or writing to `daily_stock`/`purchases`**.

**Isolation constraint (must hold for every future change here too):** never read from or write to `DailyStockDao`, `PurchaseDao`, `DailyStockRepository`, `PurchaseRepository`, `DailyStockViewModel`, `DailyStockFragment`, `DailyStockExcelHelper`, `PurchaseExcelHelper`, `SyncCoordinator`, `CloudSyncManager`. The only read-only reuse allowed is `ProductDao`'s existing query methods, the `Product` entity, `ProductCodeResolver`, `FileDownloadHelper.exportToDownloadsAndShare()`, `ScrollNavigationHelper`. Every file for this feature lives in `ui/quicksale/` or is its own standalone `utils/QuickSaleExcelHelper.kt` / footer layout — never edit a Daily-Stock-owned file to add functionality here, even cross-module import/export support.

Key points:
- `QuickSaleViewModel` (`activityViewModels()`, not `viewModels()`) holds everything in memory — survives navigating away and back within a session, resets on app close.
- `QuickSaleEntryAdapter` mirrors `DailyEntryAdapter`'s visual language (row colours, CB bar) and its two Closing-field rules: a size's CB is only editable once Opening+Purchase>0 for that size, and CB can never exceed Opening+Purchase (red border + blocked, not just a visual warning) — prevents negative sales.
- Reconciliation footer (UPI/Expenses/Deposits/Notes/Cash-for-deposit) scrolls with the list via `ConcatAdapter` + `QuickSaleFooterAdapter`, mirroring `DailyStockFragment`'s footer pattern but fully in-memory.
- The date button at the top is a **label only** (stamps print/export headers) — there's no per-date storage, just one ongoing scratchpad.
- `QuickSaleExcelHelper.importQuickSaleSheet()` auto-detects three Excel formats by header marker: this screen's own export ("Code" header), the real Daily Stock Sheet report export ("PRODUCT NAME" header, matched by display name), and Daily Stock's Closing-Balances template/export ("DATE_CLOSING" header, Type+BrandCode with the same zero-padding-tolerant matching `DailyStockImportHelper` uses). It also detects the source file's date and updates the working-date label.
- "Export Closing for Daily Stock Import" (3-dot menu) writes a file in the exact layout `DailyStockImportHelper.importClosingOnly()` already reads, so a Closing value checked here can be carried into the real module via its own Import Closing Balances feature — closing the loop both ways without touching that file.
- `QuickSaleViewModel.refreshProducts()` re-reads the active product list on every `onResume()` (plus a manual "Refresh Prices" menu action) and **rebuilds `rowsById` from scratch in the freshly-queried order** — updating in place instead would silently keep the original load's row order even after a display-order change elsewhere, since `LinkedHashMap` preserves insertion order regardless of value updates.
- The search filter (`_searchQuery`) is cleared in `onDestroyView()` regardless of exit path — the search bar UI always looks empty/collapsed on next entry even though the ViewModel (Activity-scoped) would otherwise keep the last query alive, silently hiding products with no visible sign why.
- Export's "Code" column is the **plain** `brandCode` (matches what's shown on screen), so import matches against `Product.getAllBrandCodes()` directly — never `ProductCodeResolver`, which is keyed by the *prefixed* `stockCode` and would silently fail to match most real-world (plain-numeric) brand codes.
- The exported title cell carries a `[MODE:OB_CB]` / `[MODE:DIRECT_QTY]` marker so re-importing the screen's own export restores Direct Qty mode data (Sale Qty columns) correctly and switches the mode to match; the day-end reconciliation figures (UPI/Expenses/Deposits/Notes) are written below the totals row and restored the same way.
- `QuickSaleRow.closingStockValue()` (closing qty × current sale price) is shown in the reconciliation footer under "Total sale" — OB_CB mode only, since Direct Qty never touches `closing`. A parallel **"Print Closing Stock"** menu action mirrors the sale report's full OB/PQ/CB/SQ table but with a Closing Value column and grand-total box, instead of Sale Amount.

### In-App Update Detection (`ui/update/AppUpdateManager.kt`)

Called once per cold start from `HomeFragment.onResume()` via a companion-object session flag. Uses Google Play In-App Update API:
- `FORCE_UPDATE = true` (BuildConfig field) → `IMMEDIATE` mode from day 0
- Staleness 7–59 days → `FLEXIBLE` (dismissible Play prompt)
- Staleness ≥ 60 days → `IMMEDIATE` (blocking Play overlay)
- Always skipped in debug builds

`buildConfigField("Boolean", "FORCE_UPDATE", ...)` is set in `app/build.gradle.kts` (`"true"` since vc16). The flag only affects how **this build** handles the **next** update. Versions already installed keep the update logic they shipped with.

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
- `QuickSaleExcelHelper` — export/import for the Quick Sale Check scratchpad (see `ui/quicksale/` section above); standalone, does not touch the two helpers above
- All use Apache POI 5.2.5

### Google Sheets Column Layouts

**Purchases tab** (A–AC): `TxnId | Date | ProductCode | ProductName | InvoiceNo | Supplier | QQ_Boxes | QQ_Loose | QQ_Total | QQ_Price | QQ_Cost | PP_... | NN_... | DD_... | TotalCost | Notes | ReceivedDate`

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

- **Room 2.8.4** with KSP (not KAPT) for DAOs — bumped from 2.6.1 for AGP 9 built-in Kotlin support (2.6.1 hit a KSP2 `unexpected jvm signature V` crash on suspend-Unit DAO methods)
- **AGP 9.0.1 / Gradle 9.1.0** — no separate `org.jetbrains.kotlin.android` plugin (AGP 9's built-in Kotlin support conflicts with it); JVM target set via top-level `kotlin { jvmToolchain(17) }` in `app/build.gradle.kts`, not `kotlinOptions`. KSP plugin bumped to 2.3.12 (2.3.1+ required for built-in Kotlin compatibility)
- **compileSdk / targetSdk 36** (Android 16) — bumped from 35 to meet Play's 1 Nov 2026 API-level deadline
- **Navigation 2.7.6** — single nav graph, nav_graph.xml
- **Firebase BOM 33.1.0** — Auth + Firestore KTX
- **Google Sheets API v4** — called server-side only, via Cloud Functions (`CloudFunctionClient`); the on-device `google-api-client-android`/`google-api-services-sheets` Java client was removed in vc10 (dead weight, ~1,800 unused classes, see Version History above)
- **WorkManager 2.8.1** — background periodic sync
- **Apache POI 5.2.5** — Excel import/export (requires packaging exclusions already set in `app/build.gradle.kts`)
- **Material 1.12.0** — required for `MaterialButtonToggleGroup` used in the onboarding dialog language toggle
- **Activity KTX 1.8.0** — `ActivityResultContracts` for in-app update launcher
- **Play In-App Update KTX 2.1.0** (`com.google.android.play:app-update-ktx`) — flexible and immediate update flows
- ViewBinding enabled; no DataBinding, no Compose
