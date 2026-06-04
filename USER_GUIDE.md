# WinEntry — User Guide
**Version 1.1 (Build 4) · Android App · Updated May 2026**

Specialized Retail Inventory & Accountability · Daily Stock · Sales · Purchases

---

## Contents

1. [About WinEntry](#1-about-winentry)
2. [Installing the App](#2-installing-the-app)
3. [Signing In](#3-signing-in)
4. [Getting Started — First-Time Setup](#4-getting-started--first-time-setup)
   - [Step 1 — Business Info](#step-1--business-info)
   - [Step 2 — Import Product List](#step-2--import-product-list)
   - [Step 3 — Load Sample Data (Optional)](#step-3--load-sample-data-optional)
   - [Step 4 — Setup Opening Stock](#step-4--setup-opening-stock)
5. [Home Screen](#5-home-screen)
6. [Daily Stock Entry](#6-daily-stock-entry)
7. [Purchases](#7-purchases)
8. [Reports](#8-reports)
9. [Items / Products](#9-items--products)
10. [Settings](#10-settings)
11. [Cloud Drive Backup](#11-cloud-drive-backup)
12. [Data Management and Excel Import/Export](#12-data-management-and-excel-importexport)
13. [Help and Support](#13-help-and-support)

---

## 1. About WinEntry

Originally designed and engineered by **Simhadri Systems** to meet the rigorous daily stock reconciliation requirements of licensed retail wine and liquor business in Telugu States, WinEntry is a robust, versatile inventory management tool suitable for managing inventory of any retail business that relies on precise, unit-based stock tracking.

### What the App Does

WinEntry automates the essential daily reconciliation process — the "opening vs. closing" balance — that is critical for high-volume retail environments.

- **Unit-Level Tracking** — Manage inventory across multiple brands, categories and varied sizes (bottles, cases, packets, bags and so on)
- **Daily Reconciliation** — Log daily purchases, sales and expenses to generate instant, accurate daily stock balance sheets
- **Calculated Sales** — Opening Balance + Purchases − Closing Balance = Daily Sale, computed automatically for every product and size
- **Multilingul Interface** — Currently supports English and Telugu; can be localised to any other Indian language with minimal technical changes
- **Privacy-First Design** — Operates fully offline by default to keep your business data local, with optional secure cloud backup for users who need multi-device syncing
- **Excel Export** — Every report and record can be exported to Excel for audits, accounting or regulatory submission or data backup

### Why It's Valuable

**Built by the industry, for the industry.** Born from the unique needs of retail licensees, WinEntry is offered as a collaborative tool. Since licensees operate in exclusive geographic zones, the app serves as a professional-grade solution shared among peers to foster a community of feedback and continuous improvement.

**Eliminates human error.** By replacing tedious manual ppon paper entries with automated digital logging, the app significantly reduces the risk of mathematical errors and financial discrepancies.

**Highly customizable.** While it excels in the licensed retail trade, the underlying logic is code-neutral. It can be adapted to any retail sector — such as Dairy, Hardware or Cosmetics — without requiring code changes.

### Business Use Cases

The app's framework suits any business where stock is managed by count and size:

| Sector                                 | Example use                                                                                                          |
|----------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Licensed retailer (current deployment) | Bottles by brand and pack size — daily reconciliation and generationof R1, R2 reports data for regulatory compliance |
| Dairy & Beverage                       | Crates and individual bottles with high daily turnover                                                               |
| Construction & Hardware                | Bags of cement, boxes of tiles, plumbing fixtures                                                                    |
| Personal Care & Cosmetics              | Multiple brands across SKU sizes (ml / grams)                                                                        |
| Pet Supply                             | Packaged food bags and accessories by unit                                                                           |

### Size Codes (current retail deployment)

The app supports up to five size variants per product. In the current licensed retail configuration these are:

| Size Code | Description     | Typical volume                               | Units per case |
|-----------|-----------------|----------------------------------------------|----------------|
| QQ        | Full / Big      | 551 – 900 ml (usually 750 ml or 650 ml Beer) | 12 per case    |
| QQ        | Litre           | 1000 ml (by suffixing "L" brand its CODE)    | 9 per case  
| PP        | Half / Pint     | 201 – 550 ml (usually 375 ml or Can Beer)    | 24 per case    |
| NN        | Quarter / Nip   | 101 – 200 ml (usually 180 ml)                | 48 per case    |
| DD        | Dip / Miniature | ≤ 100 ml (usually 90 ml or 60 ml)            | 96 per case    |

For other business types, size codes and their descriptions are configured when the app is set up for that deployment.

---

## 2. Installing the App

1. Open **Google Play Store** on your Android phone
2. Search for **WinEntry** or **WinEntry Simple Inventory**
3. Tap the result published by **Simhadri Systems** → tap **Install**
4. Once installed, tap **Open**

**Requirements:** Android 8.0 (Oreo) or later · A Google account for sign-in

[SCREENSHOT: Google Play Store listing for WinEntry showing the Install button — admin to insert screenshot]

> **Updates:** The Play Store notifies you automatically when a new version is released. Tap **Update** on the WinEntry store page, or enable automatic updates in Play Store settings. You can also check from inside the app: Settings → Check for Update.

---

## 3. Signing In

WinEntry uses your **Google account** to sign in — the same account you use for Gmail or YouTube.

**Steps:**

1. Open WinEntry — the Sign In screen appears
2. Tap **Sign in with Google**
3. Select your Google account from the list (or add a new one if prompted)
4. Grant the permissions requested — the app needs Sheets access only for optional cloud backup
5. You will be taken to the Home screen

[SCREENSHOT: Sign In screen showing the "Sign in with Google" button — admin to insert screenshot]

> Always use the same Google account. Signing in with a different account will not show your previous data.

---

## 4. Getting Started — First-Time Setup

When you open the app after signing in for the first time, an amber **Getting Started** card appears at the top of the Home screen. **Tap this card to open the setup checklist.**

[SCREENSHOT: Home screen showing the amber "Getting Started" card at the top with the "Tap to complete setup" description — admin to insert screenshot]

The checklist has 4 steps. Steps 1 and 2 are required. You must also complete either Step 3 or Step 4 before the card disappears and you can begin daily trading.

[SCREENSHOT: Getting Started dialog showing the 4-step checklist — admin to insert screenshot of the full dialog with the language toggle at top and the four step rows]

**Language toggle:** The English / తెలుగు buttons at the top of this dialog switch the app language between English and Telugu. The change applies immediately to all screens. You can change language again at any time from Settings.

---

### Step 1 — Business Info

Your business name and owner details appear as the header on all exported reports and on the Home screen.

**Steps:**

1. Tap **Set Up** next to "Set Up Business Information" — opens the Business Info screen
2. Fill in the fields:
   - **Owner Name** — your full name
   - **Business Name** (required) — name of your business or shop
   - **Licence / Reg. No** — registration or licence number (stored locally, never shared with admin)
   - **Address Lines 1 & 2, Location** — shop address and area
   - **Phone** — 10-digit mobile number
3. Tap **Save & Register**
4. A confirmation dialog explains what fields are shared with the admin (name, business name, phone, location, Android version, app version). Tap **Save & Register** to confirm.

[SCREENSHOT: Business Info screen with all fields filled before saving — admin to insert screenshot]

After saving, a green tick appears on Step 1 in the checklist. Future edits use the button label "Save & Update Registration".

> The "Account: ..." line at the top of the form shows your Google sign-in email — it is read-only and links your data to your account.

---

### Step 2 — Import Product List

Downloads the admin's standard product master list (product codes, names, sizes and prices) to your phone.

**Steps:**

1. Tap **Download** next to "Import Product List"
2. A progress spinner appears — wait 5–30 seconds depending on connection speed
3. Once complete, a green tick appears

[SCREENSHOT: Onboarding dialog showing Step 2 — the Download button before tapping, the spinner while downloading, and the green tick after completion — admin to insert screenshot]

> **If "Request Access" appears instead of "Download":** Your account has not been activated by the admin yet. Tap "Request Access" to send an activation request by email or WhatsApp. Once the admin adds your account to the invited list, come back and tap Download.

After downloading, go to **Items/Products → Product Display Order** to arrange products in the order you count your stock. This makes daily closing entry faster.

---

### Step 3 — Load Sample Data (Optional)

Sample data lets you practise using the app before entering real figures. It loads dummy opening stock and a few days of transactions so you can see how every screen looks with actual data.

**Skip this step if you already have real opening stock to enter — go directly to Step 4.**

**Steps:**

1. Tap **Load** next to "Import Sample / Test Data (Optional)"
2. A date picker opens — select the date you want as the opening balance date for the sample data. Default is 7 days ago.
3. Tap **OK** — sample data loads in the background (10–30 seconds)

[SCREENSHOT: Date picker dialog with the title "Opening Balance Date for Sample Data" — admin to insert screenshot]

Once loaded, a green tick appears and the card shows "Trial setup complete — you're all set!"

> Sample data can be cleared later. Go to Settings → Help & Support → Import Test Data to reload fresh sample data. To wipe all local data: Settings → Business Info → (⋮ menu) → Clear Local Data.

---

### Step 4 — Setup Opening Stock

If you are starting with real data (not sample data), you need to enter the actual unit count per product and size as your starting inventory. This is done once when you first go live. There are two ways to do it — choose whichever suits you.

---

**Option A — Manual entry (enter directly on screen)**

1. Tap **Enter** next to "Setup Opening Stock (manual)"
2. The Opening Stock Setup screen opens
3. Select your first trading date at the top
4. For each product, tap the size fields and type the unit count you have on hand
5. Use the keyboard Next key to move between size fields and products quickly
6. Tap **SAVE** at the bottom — a confirmation shows the total unit count before you confirm

[SCREENSHOT: Opening Stock Setup screen showing the product grid with size columns being filled in manually — admin to insert screenshot]

> Enter individual unit counts, not case counts. If you have 2 full cases (12 units per case) plus 3 loose units, enter 27.

---

**Option B — Import from Excel (faster for large product lists)**

If you already have your opening stock figures in a spreadsheet, or if your product list is long, importing from Excel is much faster than typing each figure individually.

1. Tap **Enter** next to "Setup Opening Stock (manual)" to open the Opening Stock Setup screen
2. Tap ⋮ (top-right menu) → **Download Template**
3. The app saves a pre-filled Excel file to your phone's Downloads folder. The template has one row per product, with columns for each size (L, QQ, PP, NN, DD). Product codes are already filled in — do not change them.
4. Open the template on a computer or on your phone in Google Sheets / Microsoft Excel
5. Enter your opening stock counts in the quantity columns for each product and size. Leave a cell blank or enter 0 if you have none of that size.
6. Save the file and transfer it back to your phone (via WhatsApp, Google Drive, email or USB)
7. Back in the Opening Stock Setup screen, tap ⋮ → **Import from Excel**
8. Select the saved file from your phone's storage
9. The app imports the figures and shows an import result summary (see Section 12 for details)

[SCREENSHOT: Opening Stock Setup screen showing the ⋮ menu open with "Download Template" and "Import from Excel" options visible — admin to insert screenshot]

> After import, review the figures on screen before tapping SAVE. The bottom bar shows sync status and buttons to **Upload to Cloud** or **Restore from Cloud** once you have saved.

---

Once saved (by either method), a green tick appears on Step 4 and the Getting Started card disappears automatically when all required steps are complete.

---

## 5. Home Screen

After setup, the Home screen shows five module cards.

[SCREENSHOT: Home screen after setup — all 5 module cards visible, business name in header, sync icon top-right, account/menu icon — admin to insert screenshot with no Getting Started card]

**Header (top bar):**

| Element | What it does |
|---------|-------------|
| Business name + location | Displayed at top — from Business Info |
| Cloud/Sync icon (top-right) | Tap to manually push data to cloud. White = all synced; Red = sync errors, tap to retry; Amber = Drive Backup not set up |
| Account icon (right of sync) | Shows your Google profile photo when signed in. Tap to see account email or sign out |

**Module cards:**

| Card | What it does |
|------|-------------|
| Daily Closing Stock Entry | Enter today's closing unit count and commit day-end totals |
| Purchases | Record supplier delivery invoices with reference number, quantities and prices |
| Reports | View and export daily, period and monthly reports to Excel |
| Items / Products | Manage product list, display order and opening stock |
| Settings | Business info, language, cloud backup, sync settings and support |

Tap any card to open the module. Use the back arrow (top-left inside the module) or the phone's back button to return to Home.

---

## 6. Daily Stock Entry

This is the core daily workflow. At end of day, count the units on your shelves and enter the closing balance. The app calculates daily sales automatically.

**Formula:** Opening Balance + Purchases − Closing Balance = Daily Sale (computed automatically)

### Opening the Screen

Tap **Daily Closing Stock Entry** from the Home screen.

[SCREENSHOT: Daily Stock screen showing the product list with size columns, date selector at top and the CB Entry / View Only toggle — admin to insert screenshot]

**Top controls:**
- **Date selector** — defaults to today. Use the ‹ and › arrows or tap the date to pick a different date.
- **CB Entry / View Only toggle** — switches between edit mode and read-only view. Default is View Only.

### Entering Closing Balances

1. Tap **CB Entry** in the top bar to enter edit mode
2. The product list appears with a column per configured size
3. Each row shows the Opening Balance (OB) and Purchases (PQ) already filled in
4. Tap a Closing Balance field and type the unit count you physically counted
5. Sale quantity (SQ) is shown instantly: SQ = OB + PQ − CB
6. Tap the **Save** button (green checkmark in toolbar) when all products are entered

[SCREENSHOT: Daily Stock in CB Entry mode showing one product expanded with OB, PQ and CB fields — admin to insert screenshot]

> The keyboard Next key moves focus from one size field to the next product automatically. The list scrolls to keep the active field visible.

> **Negative sale warning:** If your closing balance is higher than OB + PQ (more stock than physically possible), the app shows a warning. Check the figures before saving.

### Importing Closing Balances from Excel

If you have a large number of products, or if your closing stock is already recorded in a spreadsheet from another tool, you can import it directly rather than typing each figure into the app.

**Steps:**

1. In the Daily Stock screen, tap ⋮ (top-right) → **Download Closing Template**
2. The app saves a pre-filled Excel file to your Downloads folder. The template has one row per product with columns for each size (L, QQ, PP, NN, DD) and one column for the date. Product codes and names are already filled — do not change them.
3. Open the template on a computer or in Google Sheets / Microsoft Excel
4. Enter the closing balance counts for each product and size in the quantity columns. Leave a cell blank or enter 0 if you have none of that size.
5. Set the date column to the trading date you are entering for.
6. Save the file and transfer it back to your phone (via WhatsApp, Google Drive, email or USB)
7. Back in the Daily Stock screen, tap ⋮ → **Import Closing**
8. Select the file from your phone's storage
9. The app imports the closing balances and shows an import result summary (see Section 12 for details)

[SCREENSHOT: Daily Stock screen showing the ⋮ menu open with "Download Closing Template" and "Import Closing" options visible — admin to insert screenshot]

> You can import closing balances for multiple dates in one file — just include a separate row for each product-date combination. The app processes each date independently.

> After import, review the figures on screen. Sale quantities are calculated automatically from the imported closing balances.

---

### Day-End Reconciliation

After entering closing balances, scroll to the bottom of the list to complete the day-end summary:

- **Total Day Sales** — calculated automatically from all products and their prices
- **UPI Receipts** — total UPI and digital payment collections for the day (₹)
- **Day Expenses** — any petty cash expenses paid that day
- **Cash for Deposit** — calculated as Sales − UPI − Expenses (shown in red if negative)
- **Cash Deposit** — amount physically deposited in the bank
- **Notes** — any remarks for the day

Tap **Save** at the bottom of the reconciliation section to lock the day-end summary.

[SCREENSHOT: Day-end reconciliation section at the bottom of Daily Stock showing UPI, Expenses, Cash Deposit fields and the calculated total — admin to insert screenshot]

> You can re-edit and re-save the same day at any time to correct mistakes. Re-saving overwrites the previous figures.

### Daily Stock Menu (⋮ top-right)

| Menu item | What it does |
|-----------|-------------|
| Export Daily Stock | Export the full stock sheet as Excel |
| Export Current Date | Export only today's data as Excel |
| Export Date Range | Export a range of dates as Excel |
| Download Closing Template | Download a pre-filled Excel template for bulk CB entry |
| Import Closing | Import closing balances from an Excel file |
| Restore from Cloud | Pull daily stock data down from your cloud workspace |
| Clear Current Date | Delete all entries for the selected date only |
| Clear ALL Daily Stock | Delete all daily stock data (requires typing "DELETE ALL" to confirm) |

### Search

Tap the search icon in the toolbar to filter products by name or product code while in the daily stock screen.

### Unsaved Changes

If you navigate away or switch dates with unsaved changes, the app asks: **Save Now / Discard / Keep Editing**. Your data is safe until you explicitly discard it.

---

## 7. Purchases

Record every supplier delivery invoice here. Purchases are automatically included in the daily stock calculation for the purchase date.

### Purchases List

Tap **Purchases** from the Home screen to see all recorded invoices.

[SCREENSHOT: Purchases list screen showing invoice rows with date, reference number, supplier and total cost — admin to insert screenshot]

- Each row shows: date, invoice reference number, supplier name and total cost
- Tap a row to view the full invoice details
- Tap **+** (bottom-right) to add a new purchase
- Use the search bar at the top to filter by product name, product code, date or invoice number

### Recording a Purchase

Tap **+** to open the Purchase Entry form.

[SCREENSHOT: Purchase Entry form — top half showing Invoice No, Reference No, Supplier and Date fields — admin to insert screenshot]

**Invoice details (top):**
- **Invoice No** — the supplier's invoice number from the delivery document
- **Reference No** — a unique reference for this delivery (e.g. a permit or transport number) used to prevent duplicate imports
- **Supplier** — select from the dropdown or type manually
- **Date** — date goods were received (defaults to today)
- **Notes** — optional remarks

**Product details (bottom):**
1. Tap **Select Product** to choose the product from the list
2. For each size received, enter:
   - **Cases** — number of full sealed cases
   - **Loose** — individual loose units (partial case)
   - **Price** — price per unit (auto-filled from product master if set)
3. Total units = (Cases × units per case) + Loose, and Cost = Total × Price — calculated automatically
4. Tap **Add Another Product** to add more products to the same invoice
5. Tap **Save** when done

[SCREENSHOT: Purchase Entry form — bottom section showing one product selected with Cases / Loose / Price fields filled and the calculated total — admin to insert screenshot]

> If a size was not part of this delivery, leave its Cases and Loose fields at zero.

### Editing or Deleting a Purchase

Long-press any purchase in the list to open an action dialog with **Edit** or **Delete** options. Deletions are permanent and immediately update the daily stock totals for that date.

### Adding Multiple Items from the Same Invoice

In the purchases list, long-press an existing purchase and select **Insert Here**. This opens a new purchase form pre-filled with the same invoice number, date and supplier — just change the product.

### Importing Purchases from Excel

When you receive a delivery with many products, or when entering historical invoices in bulk, importing from Excel is far faster than tapping through each product one by one.

**Steps:**

1. In the Purchases screen, tap ⋮ (top-right) → **Download Template**
2. The app saves a pre-filled Excel template to your Downloads folder. The template has one row per product line with columns for: Date, Invoice No, Reference No, Supplier, Product Code, and quantity/price columns per size (Cases, Loose, Price for each of L, QQ, PP, NN, DD).
3. Open the template on a computer or in Google Sheets / Microsoft Excel
4. Fill in one row per product line of each invoice:
   - Enter the delivery **Date** (format: DD-MM-YYYY or YYYY-MM-DD)
   - Enter the **Invoice No** and **Reference No** from the delivery document
   - Enter the **Supplier** name
   - The **Product Code** column already lists your products — find the right row for each product
   - Enter **Cases** (full sealed cases) and **Loose** (individual loose units) for each size received
   - Enter the **Price** per unit for each size
5. Repeat rows for each product in the invoice. Multiple invoices can be entered in the same file — just use a different Invoice No per delivery.
6. Save the file and transfer it to your phone (via WhatsApp, Google Drive, email or USB)
7. Back in the Purchases screen, tap ⋮ → **Import from Excel**
8. Select the file from your phone's storage
9. The app imports all rows and shows an import result summary (see Section 12 for details)

[SCREENSHOT: Purchases screen showing the ⋮ menu open with "Download Template" and "Import from Excel" options visible — admin to insert screenshot]

> **Duplicate detection:** If any row has the same product + invoice number + date as an existing record, the app flags it and asks whether to update the existing record or skip it. This prevents double-counting a delivery.

> After import, the purchases appear in the list and are immediately reflected in the daily stock totals for their respective dates.

### Purchases Menu (⋮ top-right)

| Menu item | What it does |
|-----------|-------------|
| Import from Excel | Bulk-import purchases from an Excel file (download template first) |
| Export All | Export all purchase records as Excel |
| Export Filtered | Export purchases within a date range |
| Download Template | Get an Excel template pre-filled with your product list |
| Delete by Date Range | Delete all purchases within a chosen date range |
| Multi-Select | Select multiple purchases to delete in one go |
| Download from Cloud | Pull purchase data from your cloud workspace |

> **Duplicate protection:** The app detects records with the same product + invoice number + date and asks whether to update the existing record or skip.

---

## 8. Reports

Tap **Reports** from the Home screen.

[SCREENSHOT: Reports screen showing report cards grouped by Daily Reports, Period Reports and Monthly Reports — admin to insert screenshot]

### Daily Stock Sheet

Full table: opening balance, purchases, closing balance and calculated sales for all products and sizes on a selected date.

1. Tap **Daily Stock Sheet**
2. Select the date
3. Tap **View Report**

[SCREENSHOT: Daily Stock Sheet report viewer showing OB / PQ / SQ / CB columns for each product and size — admin to insert screenshot]

- Business name, owner name and date appear as the report header (from Business Info)
- Tap **Export to Excel** (top-right) to save the sheet to Downloads

### Closing Balances

End-of-day stock quantities per product across all sizes on a selected date. Useful for a quick physical stock verification.

### Daily Brand Wise Account

A daily account summarising sales and stock per brand, grouped by product category. Useful for regulatory reporting where required by your business type.

1. Tap **Daily Brand Wise A/c**
2. Select the date
3. Tap **View Report**
4. Export to Excel as needed

[SCREENSHOT: Brand Wise Account report showing products grouped by category with sale totals — admin to insert screenshot]

### Purchase Report (Period)

All purchase records within a date range, grouped by invoice — useful for supplier-wise or invoice-wise reconciliation.

1. Tap **Purchase Report**
2. Select From and To dates
3. Tap **View Report**

### Monthly Sale Data

Shows the day-end commit totals (sale amount, UPI receipts, expenses, cash deposit) for each day in a selected month — for monthly reconciliation and accounts.

1. Tap **Monthly Sale Data**
2. Navigate months using the ◀ and ▶ arrows, or tap the month name to jump to any month/year

Days with no saved day-end reconciliation are highlighted in amber with a * marker. Days where closing balances were updated after reconciliation are marked with a refresh icon (stale — consider re-committing those days for accuracy). Column totals appear at the bottom; cash amounts shown in red if negative.

**Monthly Summary Menu (⋮):**
- **Share** — Share the report as a colour-coded HTML file
- **Print / Save as PDF** — Print or save using your phone's print service
- **Import Reconciliation from Cloud** — Pull day-end data from your cloud workspace
- **Delete Reconciliation Range** — Remove day-end records for a date range

### Monthly Stock Purchases

Total purchase quantities per product for a selected month, one row per product.

### Include Zero-Activity Products

In the report filter, toggle **"Include products with zero activity"** to show products with no sales or purchases on the selected date. Off by default to keep reports compact.

> Business name and owner details from Settings → Business Info appear as the header on all reports. Set these up in Step 1 before generating reports.

---

## 9. Items / Products

Tap **Items / Products** from the Home screen.

[SCREENSHOT: Products Menu screen showing the four sections — Display (Product Display Order), Stock Setup (Opening Stock Setup), Master Data (Product Master Data), Cloud Update (Download Standard Product List) — admin to insert screenshot]

### Product Display Order

Controls the order products appear in the Daily Stock Entry screen and in reports.

1. Tap **Product Display Order**
2. Toggle the **Edit switch** to enter edit mode
3. **Long-press and drag** any product to move it up or down
4. Long-press a product row for options to **Deactivate** (hides it from daily stock — moves it to an Inactive section at the bottom). Long-press an inactive product to **Re-activate** it.
5. Tap **Renumber & Save** when done, or **Discard** to cancel

[SCREENSHOT: Product Order screen in edit mode showing drag handles on the left of each row — admin to insert screenshot]

> Arrange products in the order you count your shelves. This makes daily closing entry much faster.

### Opening Stock Setup

Used when starting a new period or correcting starting quantities. Opening stock can be entered manually or imported from Excel — see Section 4 — Step 4 for the full walkthrough of both methods.

**Manual entry:**
1. Tap **Opening Stock Setup**
2. Select the date and tap **Begin Entry** (new) or **Edit** (to correct existing)
3. Enter unit counts per size for each product using the keyboard
4. Tap **SAVE** at the bottom

**Import from Excel (recommended for large product lists):**
1. Tap **Opening Stock Setup** → ⋮ → **Download Template**
2. Fill in your stock quantities in the template on a computer
3. Transfer the file back to your phone
4. Tap ⋮ → **Import from Excel** and select the file

See Section 4 — Step 4 for the complete step-by-step import walkthrough.

The bottom bar shows sync status and buttons to **Upload to Cloud** or **Restore from Cloud**.

### Product Master Data

Displays your full product list — product codes, names and size variants — for reference.

[SCREENSHOT: Product Master Data list showing product code, name and size code columns — admin to insert screenshot]

### Download Standard Product List

Downloads the latest product master from the admin's cloud workspace. Run this whenever the admin adds new products or updates prices.

1. Tap **Download Standard Product List**
2. Confirm the download
3. Wait for completion — new and updated products are added; your display order is preserved

> Products in your local list that are not in the admin's standard list are kept unchanged.

---

## 10. Settings

Tap **Settings** from the Home screen.

[SCREENSHOT: Settings screen showing all cards — Language, Business Info, Drive Backup, Sync Settings, Help & Support — with app version at bottom — admin to insert screenshot]

### Language

Tap **App Language** to switch between **English** and **తెలుగు**. The change applies immediately to all screens. You can also switch language from the Getting Started dialog's EN / తె toggle.

### Business Info

Update your business name, owner name, address and phone. All fields are the same as in Step 1 of the Getting Started setup.

- Tap **Save & Update Registration** to sync changes to the admin's system

**Business Info overflow menu (⋮ top-right):**

| Your status | Menu option | What it does |
|-------------|-------------|-------------|
| Registered | Delete Account & Data | Removes all cloud data, registration and sign-in account permanently |
| Not registered | Clear Local Data | Deletes all inventory data from this device (exported Excel files are not affected) |
| Deletion pending | Complete Account Deletion | Finishes removing the sign-in account after re-login |

### Drive Backup

Shows the current state of cloud backup. See Section 11 for the full workflow.

### Sync Settings

Available only when Drive Backup is active. Configure whether the app syncs automatically every 6 hours or only when you tap the Sync button on the Home screen.

### Help & Support

- **Report an Issue / Ask for Help** — sends a support email with device and app version pre-filled
- **Request Feature** — sends a feature request to Simhadri Systems
- **Import Test Data** — reloads sample data (requires registration)
- **User Guide** — opens this guide in the browser

### Error Log

When the app encounters technical errors, a log is saved on the device. If errors exist, a **Share Error Log** button appears at the bottom of Settings. Share the log when contacting support, or tap **Clear** to remove it.

### App Version

Shown at the bottom of Settings. Tap **Check for Update** to check for a newer version on the Play Store.

---

## 11. Cloud Drive Backup

Cloud backup is optional. The app works fully offline without it. When enabled, your daily stock, purchases and day-end summaries are stored in a private Google Sheet in the admin's Drive workspace — separate from your personal Drive.

> Your data, your control. Signing in with Google does not start any sync. You must explicitly enable backup and accept the terms before any data leaves your phone.

### How to Enable

1. **Register** — Go to Settings → Business Info, fill in your business name and tap **Save & Register**
2. **Request workspace** — Go to Settings → tap the **Drive Backup** card → tap **Request Drive Backup**
3. **Read the consent terms** — the dialog explains what data is stored and that the admin has read access to your records
4. Tap **I Agree — Enable Backup**
5. The app contacts the admin's system:
   - If your account is in the invited list → workspace is created immediately → "Drive Backup Active" shows in green
   - If not yet in the invited list → "Drive Backup Pending" shows — the admin will activate your workspace

[SCREENSHOT: Settings screen showing the "Drive Backup Active" card — admin to insert screenshot]

[SCREENSHOT: Cloud backup consent dialog showing the terms and "I Agree — Enable Backup" button — admin to insert screenshot]

> If "Drive Backup Pending" persists more than a day, tap the card → **Send Reminder** to notify the admin again.

### What Is Synced

| Data | Synced? |
|------|---------|
| Daily stock entries | Yes |
| Purchase records | Yes |
| Day-end reconciliation | Yes |
| Opening stock | Yes |
| Product list | Read-only download from admin |
| Licence/Reg. number, full address | Never — stays on device only |

> The administrator has read access to all records in your workspace. This is the basis on which free cloud backup is provided.

### Sync Status Icons on Home Screen

| Icon colour | Meaning |
|-------------|---------|
| White | All records synced successfully |
| Red | One or more records failed — tap to retry |
| Amber / Yellow | Drive Backup not set up |

Tap the sync icon on the Home screen at any time to trigger an immediate manual sync.

### Viewer vs Editor Role

The administrator assigns each account a role. Most users are Editors.

| Role | What you can do |
|------|----------------|
| Editor (default) | Full access — enter data, sync up and down, all buttons active |
| Viewer | Read-only — sync down from cloud only; data entry and save buttons are hidden |

Contact the admin if you need your role changed.

### Deactivating or Deleting

- **Deactivate** — Go to Settings → Drive Backup (active) → tap **Deactivate**. Stops future auto-sync. Local data and Excel exports are not affected.
- **Delete Account & Data** — Go to Settings → Business Info → ⋮ → Delete Account & Data. Permanently removes all cloud data, your registration and your sign-in account.

---

## 12. Data Management and Excel Import/Export

WinEntry can both export data to Excel for records and reports, and import data from Excel to avoid manual typing. All import and export operations use standard `.xlsx` files that open in Microsoft Excel, Google Sheets or any compatible spreadsheet app.

---

### Excel Export — Quick Reference

Exported files are saved to the phone's **Downloads** folder. After export, a Share button lets you send the file directly via WhatsApp, email or any other app.

| What to export | Where to find it | Notes |
|----------------|-----------------|-------|
| Daily stock full sheet | Daily Stock → ⋮ → **Export Daily Stock** | All products, all sizes, for a selected date |
| Closing balances (single date) | Daily Stock → ⋮ → **Export Current Date** | Useful for a snapshot of end-of-day stock |
| Daily stock (date range) | Daily Stock → ⋮ → **Export Date Range** | Select From and To dates |
| All purchases | Purchases → ⋮ → **Export All** | Every invoice ever recorded |
| Purchases (date range) | Purchases → ⋮ → **Export Filtered** | Select From and To dates |
| Monthly summary | Reports → Monthly Sale Data → ⋮ → **Share** or **Print** | Colour-coded HTML or PDF |

---

### Excel Import — How It Works

The import system follows the same three-step pattern for every data type:

**Step 1 — Download the template**
Always start by downloading the template from within the app. The template is pre-filled with your product codes and column headings in the exact format the app expects. Never create an import file from scratch — use the template.

**Step 2 — Fill in the template**
Open the template on a computer or phone (Google Sheets, Microsoft Excel). Enter your data in the quantity/value columns. Do not change product codes, column headings or sheet structure. Save as `.xlsx`.

**Step 3 — Transfer and import**
Transfer the filled file back to your phone (WhatsApp, Google Drive, email attachment or USB cable). In the app, tap ⋮ → the relevant Import option, then select the file. The app validates each row before committing.

---

### Excel Import — Quick Reference

| What to import | Template location | Import location |
|----------------|------------------|-----------------|
| Opening stock | Products → Opening Stock → ⋮ → **Download Template** | Products → Opening Stock → ⋮ → **Import from Excel** |
| Closing balances (daily stock) | Daily Stock → ⋮ → **Download Closing Template** | Daily Stock → ⋮ → **Import Closing** |
| Purchases | Purchases → ⋮ → **Download Template** | Purchases → ⋮ → **Import from Excel** |

For a full step-by-step walkthrough of each import type, see the relevant sections:
- Opening stock import — Section 4 (Step 4, Option B) and Section 9
- Closing balance import — Section 6 (Importing Closing Balances from Excel)
- Purchase import — Section 7 (Importing Purchases from Excel)

---

### Import Result Summary

After any import, the app shows a result dialog with a count for each outcome:

| Result | Meaning |
|--------|---------|
| **New** | Records imported successfully |
| **Skipped** | Already exist — same product + date, or same invoice + date. No change made. |
| **Failed** | Product code not found in your product list, or data format error |

Tap **Copy List** to copy the full list of skipped or failed rows to clipboard. Paste into a notes app or email to review and correct the figures before retrying.

> If many rows fail with "product code not found", check that your product list is up to date: Items/Products → Download Standard Product List. Then retry the import.

---

## 13. Help and Support

Go to Settings → Help & Support.

**Support email:** simhadrisystems@gmail.com

### Contact Support

Tap **Ask for Help** or **Report Issue**. The app pre-fills a message template with your app version, Android version and device model — you choose what additional details to add. No data is sent automatically.

### Common Issues

| Problem | What to do |
|---------|-----------|
| "Request Access" appears instead of "Download" in onboarding | Your account needs to be activated by the admin — tap "Request Access" to send the request |
| "Drive Backup Pending" for more than a day | Tap the card → Send Reminder |
| Sync icon is red on Home screen | Check internet connection, then tap the sync icon to retry |
| Opening balance looks wrong | Check if the previous day was committed — open that date in Daily Stock and verify the closing balance was saved |
| Product missing from the daily stock list | Items/Products → Download Standard Product List to get the latest list from admin |
| "Account Not Activated" when downloading products | Admin needs to add your account — contact admin by email or WhatsApp |
| App shows "Sign in again to complete" after account deletion | Sign out, sign back in with the same account, then go to Business Info → ⋮ → Complete Account Deletion |

### Import Test Data

Go to Settings → Help & Support → Import Test Data. Loads a sample set of opening balances and dummy stock data for practice. Requires app registration (Step 1 in onboarding).

Select the date you want as the sample opening balance day, then confirm the import. Replace sample data with your real figures when you are ready to begin live trading.

---

> All your data stays on your phone unless you explicitly enable Cloud Drive Backup. The app works fully offline — enter closing stock, record purchases, view reports and export to Excel without any internet connection.

---

*WinEntry · Specialized Retail Inventory & Accountability Tool · Simhadri Systems · simhadrisystems@gmail.com · Guide updated May 2026*
