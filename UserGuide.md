# WinEntry — User Guide

**Version 1.0 | April 2026**

---

## Table of Contents

1. [Installation](#1-installation)
2. [Signing In](#2-signing-in)
3. [Language Settings](#3-language-settings)
4. [Products — Viewing & Syncing Master Data](#4-products--viewing--syncing-master-data)
5. [Opening Stock — Setup & Import](#5-opening-stock--setup--import)
6. [Purchases — Adding, Importing & Managing](#6-purchases--adding-importing--managing)
7. [Daily Stock — Closing Balance & Reconciliation](#7-daily-stock--closing-balance--reconciliation)
8. [Syncing Data to Cloud](#8-syncing-data-to-cloud)
9. [Reports](#9-reports)
10. [Settings](#10-settings)
11. [Help & Support](#11-help--support)

---

## 1. Installation

1. Download the APK file (`app-arm64-v8a-release.apk`) shared by your admin via WhatsApp or email.
2. On your Android phone, go to **Settings → Security** and enable **Install from Unknown Sources** (only required for sideloaded APKs; not needed if installed from Play Store).
3. Open the downloaded APK file and tap **Install**.
4. Once installed, tap **Open** or find **WinEntry** in your app drawer.

> **Note:** If you receive an "App not installed" error, ensure you have at least 50 MB of free storage and that your device runs Android 8.0 (Oreo) or later.

---

## 2. Signing In

WinEntry uses your **Google account** to sign in and sync data securely.

1. Open the app. You will see the **Sign In** screen.
2. Tap **Sign in with Google**.
3. Select your Google account from the list.
4. The app will verify your account and set up your profile automatically.

> **First-time users:** Your account must be registered by the admin before you can sign in. If you see *"Account not set up yet. Please contact the admin"*, reach out to your admin with your Google email address.

**Signing Out:**
- From the **Home** screen, tap the profile icon (top right) → **Sign Out**.
- Signing out clears your local session. Your data in the cloud remains safe.

---

## 3. Language Settings

The app supports multiple display languages.

1. Go to **Settings** (from Home screen or bottom navigation).
2. Tap **Language**.
3. Select your preferred language from the list.
4. The app will immediately switch to the selected language.

---

## 4. Products — Viewing & Syncing Master Data

Products (brand codes, names, size variants) are managed centrally by the admin in a shared Google Sheet. You do not add or edit products — you sync them.

### Viewing Products
- Go to **Settings → Products** to view the full product list.
- Products are listed with their brand code, name, and four size variants: **QQ, PP, NN, DD**.

### Syncing Product Master Data
1. From the **Home** screen, tap the **Sync** button (cloud icon in the top bar).
2. The app will download the latest product list from the admin's shared sheet.
3. A confirmation message will appear once sync is complete.

> **Note:** Product sync is read-only. You cannot add, edit, or delete products from within the app.

---

## 5. Opening Stock — Setup & Import

Opening stock records the quantity of each product you have on hand at the start. This is a one-time setup when you first start using the app, or at the start of a new financial year.

### Entering Opening Stock Manually
1. Go to **Settings → Opening Stock**.
2. Select the product from the list.
3. Enter the opening quantity for each size (QQ, PP, NN, DD).
4. Tap **Save**.
5. Repeat for all products.

### Importing Opening Stock from Excel
If you have existing stock data in an Excel file:
1. Go to **Settings → Opening Stock**.
2. Tap the **Import** button (top right).
3. Select your Excel file from the file picker.
4. The app will read each row and match products by brand code.
5. Review the import summary and tap **Confirm** to save.

> **Excel format:** Each row must have the brand code in the first column, followed by quantities for QQ, PP, NN, DD in order.

---

## 6. Purchases — Adding, Importing & Managing

Purchases records stock received from suppliers — invoice-wise with size-level detail.

### Adding a Purchase
1. Tap **Purchases** from the Home screen or bottom navigation.
2. Tap the **+** (Add) button.
3. Fill in the details:
   - **Date** — date of the purchase
   - **Invoice No.** — supplier invoice number
   - **Supplier** — supplier name
   - **Product** — select from the dropdown (type to search by brand code)
   - **Quantities** — enter boxes and loose units for each size (QQ, PP, NN, DD)
   - **Price per unit** — enter selling price for each size
4. Tap **Save**.
5. To add another product from the same invoice, tap **Add Another** — the invoice details are retained.

### Importing Purchases from Excel
1. Go to **Purchases → Import** (menu icon, top right).
2. Select your Excel file.
3. The app matches rows to products using brand codes and aliases.
4. Review the summary — matched and unmatched rows are shown separately.
5. Tap **Confirm** to import matched rows.

### Viewing & Managing Purchases
- Purchases are listed grouped by date.
- Tap any purchase to expand and view size-level detail.
- Long-press a purchase entry to **Edit** or **Delete** it.
- Use the **Filter & Export** option to export a date-range to Excel.

### Deleting Purchases by Date Range
1. Go to **Purchases → Delete by Date Range** (menu icon).
2. Select the start and end dates.
3. Tap **Delete** — this permanently removes all purchases in that range.

---

## 7. Daily Stock — Closing Balance & Reconciliation

The Daily Stock screen is your core day-end workflow. Each day you record closing stock levels and reconcile sales.

### Daily Workflow Overview

| Step | Action |
|------|--------|
| 1 | Open Daily Stock screen |
| 2 | Verify opening balances (auto-carried from previous day's closing) |
| 3 | Process today's purchases (if any) |
| 4 | Enter closing balance for each product and size |
| 5 | Enter day-end reconciliation (UPI, cash, expenses) |
| 6 | Commit the day |

### Entering Closing Balance
1. Tap **Daily Stock** from the Home screen.
2. The screen shows today's date with all products listed.
3. For each product, tap the row to expand it.
4. Enter the **closing quantity** for each size (QQ, PP, NN, DD).
5. The app automatically calculates: **Sale = Opening + Purchases − Closing**.

### Processing Purchases
If you received stock today:
1. On the Daily Stock screen, tap the **Process Purchases** button.
2. The app loads all purchases entered for today's date.
3. Tap **Apply** to add them to today's opening balance.

### Day-End Reconciliation
After entering all closing balances:
1. Scroll to the bottom of the Daily Stock screen.
2. Fill in:
   - **UPI Sales** — total UPI/digital payments received
   - **Cash Sales** — total cash received
   - **Expenses** — any expenses paid out
   - **Cash Deposit** — cash deposited to bank
3. The app shows **Total Sales** calculated from stock movement.
4. Tap **Commit Day** to finalise the day.

> **Important:** Once a day is committed, closing values are locked and carry forward as the next day's opening. You can still edit uncommitted entries.

---

## 8. Syncing Data to Cloud

All your data (purchases, daily stock, reconciliation) syncs to your personal Google Sheet in the cloud.

### Manual Sync
- From the **Home** screen, tap the **Sync** (cloud) button in the top bar.
- A progress indicator appears while sync runs.
- On completion, the button returns to normal.

### Automatic Sync
- The app auto-syncs every **6 hours** in the background via WorkManager.
- No action required — this happens silently.

### Sync Status
- If the **Sync button turns red**, one or more records failed to sync.
- Tap the red sync button to retry.
- For persistent errors, go to **Help & Support → Report an Issue**.

### Viewer vs Editor Role
- **Editor** — can enter and sync data (full access)
- **Viewer** — can view data and sync down from cloud, but cannot enter or modify records

---

## 9. Reports

Reports help you review sales performance and stock movement over time.

### Monthly Summary
1. Tap **Reports** from the Home screen.
2. Select **Monthly Summary**.
3. Choose the month and year.
4. The report shows:
   - Total sales by product and size
   - Total purchase cost
   - Gross profit estimate
   - Day-wise breakdown

### Other Reports
From the Reports screen you can access:
- **Daily Sheet** — full day-wise stock movement
- **Closing Balance Report** — stock on hand at end of any period
- **Purchase Report** — purchases by supplier or product
- **Brand-wise Sales** — sales aggregated by brand
- **Monthly Purchase Summary** — total purchases by month

### Exporting Reports
- Each report has a **Share / Export** button (top right).
- Reports export to Excel (`.xlsx`) and can be shared via WhatsApp, email, or saved to device storage.

---

## 10. Settings

| Setting | What it does |
|---------|-------------|
| **Business Info** | Set your business name and location — appears on reports |
| **Products** | View and manage the product list (sync from admin sheet) |
| **Product Order** | Drag to reorder how products appear in the daily stock list |
| **Opening Stock** | Set or import opening stock quantities |
| **Language** | Switch display language |
| **Help & Support** | Access user guide, report issues, contact support |

---

## 11. Help & Support

From **Settings → Help & Support**:

| Option | Use when |
|--------|----------|
| **User Guide** | You want to read or download this guide |
| **Report an Issue** | Something in the app is not working correctly |
| **Contact Support** | You have a question about how to use the app |
| **Request a Feature** | You'd like to suggest an improvement |

Tapping **Report an Issue** or **Contact Support** opens a pre-filled message via **Email** or **WhatsApp** with your app version and device info automatically included — this helps the admin resolve your issue faster.

---

*WinEntry is designed for retail businesses managing products in QQ, PP, NN and DD size variants. For admin setup queries (new user registration, product master updates), contact your admin directly.*

---

**© 2026 Simhadri Systems. All rights reserved.**
