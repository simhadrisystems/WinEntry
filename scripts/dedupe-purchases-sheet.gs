/**
 * Removes duplicate purchase lines from a WinEntry user sheet's Purchases tab.
 *
 * Older app versions appended a new copy of a purchase line (same invoice date, product
 * code and invoice number, new TxnId) on every re-import. This keeps the newest copy of
 * each line (largest TxnId, which starts with a yyyyMMdd-HHmmss timestamp) and deletes
 * the rest. If the kept copy has no ReceivedDate (column AC) but an older copy does,
 * that date is carried over.
 *
 * Run from the sheet: Extensions > Apps Script, paste this file, run dedupePurchases.
 *   DRY_RUN = true  -> only writes the DupReport tab, changes nothing.
 *   DRY_RUN = false -> copies Purchases to a backup tab first, then rewrites Purchases.
 *
 * repairPurchaseTextColumns() (run separately) turns real date cells in the text columns
 * back into plain "yyyy-MM-dd" text, e.g. after the first version of this script.
 *
 * Text columns are written with the plain-text format "@": setValues() otherwise parses
 * strings like typed input, turning "2026-04-30" into a date cell that the Sheets API
 * returns as a serial number (46142), which the app stored as the purchase date.
 */
const DRY_RUN = true;

const COL_TXN = 0, COL_DATE = 1, COL_CODE = 2, COL_INVOICE = 4, COL_RECEIVED = 28;
const FIRST_DATA_COL = 6, LAST_DATA_COL = 27;   // G..AB: quantities, prices, costs, notes
// TxnId, Date, ProductCode, ProductName, InvoiceNo, Supplier, Notes, ReceivedDate
const TEXT_COLS = [0, 1, 2, 3, 4, 5, 27, 28];

function asText(v, tz) {
  if (v instanceof Date) return Utilities.formatDate(v, tz, "yyyy-MM-dd");
  return v === null || v === undefined ? "" : String(v);
}

/** Writes rows starting at row 2, with TEXT_COLS stored as plain text. */
function writeRowsAsText(sheet, rows, width, tz) {
  if (!rows.length) return;
  TEXT_COLS.forEach(c => sheet.getRange(2, c + 1, rows.length, 1).setNumberFormat("@"));
  const out = rows.map(r => r.map((v, c) => TEXT_COLS.indexOf(c) >= 0 ? asText(v, tz) : v));
  sheet.getRange(2, 1, rows.length, width).setValues(out);
}

function repairPurchaseTextColumns() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName("Purchases");
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return;
  const width = Math.max(sheet.getLastColumn(), 29);
  const tz = ss.getSpreadsheetTimeZone();
  const rows = sheet.getRange(2, 1, lastRow - 1, width).getValues();
  const fixed = rows.filter(r => TEXT_COLS.some(c => r[c] instanceof Date)).length;

  const stamp = Utilities.formatDate(new Date(), tz, "yyyyMMdd_HHmm");
  sheet.copyTo(ss).setName("Purchases_backup_" + stamp);
  writeRowsAsText(sheet, rows, width, tz);
  SpreadsheetApp.getUi().alert(`Done. ${fixed} row(s) had date cells, now plain text.\n\nBackup tab: Purchases_backup_${stamp}`);
}

function dedupePurchases() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName("Purchases");
  if (!sheet) throw new Error("No Purchases tab in this spreadsheet");

  const lastRow = sheet.getLastRow();
  const width = Math.max(sheet.getLastColumn(), COL_RECEIVED + 1);
  if (lastRow < 2) { Logger.log("Purchases tab is empty"); return; }
  const rows = sheet.getRange(2, 1, lastRow - 1, width).getValues();
  const tz = ss.getSpreadsheetTimeZone();

  const dateKey = v => v instanceof Date ? Utilities.formatDate(v, tz, "yyyy-MM-dd") : String(v).trim();
  const cell = v => v instanceof Date ? dateKey(v) : String(v).trim();

  const groups = new Map();
  const blankTxn = [];
  rows.forEach((row, i) => {
    const txn = cell(row[COL_TXN]);
    if (!txn) { blankTxn.push(i); return; }
    const key = [dateKey(row[COL_DATE]), cell(row[COL_CODE]), cell(row[COL_INVOICE])].join("|");
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(i);
  });

  const keep = new Set(blankTxn);   // rows without a TxnId are left alone
  const report = [["Date", "Product", "Invoice", "Copies", "Kept TxnId", "Columns that differ", "ReceivedDate carried over"]];
  const colName = c => c < 26 ? String.fromCharCode(65 + c) : "A" + String.fromCharCode(65 + c - 26);
  // Numbers compare with rounding so 1234.5 and 1234.4999999 count as the same
  const sameValue = (a, b) => {
    const na = Number(a), nb = Number(b);
    if (cell(a) !== "" && cell(b) !== "" && !isNaN(na) && !isNaN(nb)) return Math.abs(na - nb) < 0.005;
    return cell(a) === cell(b);
  };
  groups.forEach((idx, key) => {
    idx.sort((a, b) => cell(rows[b][COL_TXN]).localeCompare(cell(rows[a][COL_TXN])));
    const kept = idx[0];
    keep.add(kept);
    if (idx.length === 1) return;

    const differCols = [];
    for (let c = FIRST_DATA_COL; c <= COL_RECEIVED; c++) {
      if (idx.some(r => sameValue(rows[r][c], rows[kept][c]) === false)) differCols.push(colName(c));
    }

    let carried = "";
    if (!cell(rows[kept][COL_RECEIVED])) {
      const other = idx.map(r => rows[r][COL_RECEIVED]).find(v => cell(v));
      if (other !== undefined) { rows[kept][COL_RECEIVED] = other; carried = cell(other); }
    }
    const [d, code, inv] = key.split("|");
    report.push([d, code, inv, idx.length, cell(rows[kept][COL_TXN]), differCols.join(" "), carried]);
  });

  const reportSheet = ss.getSheetByName("DupReport") || ss.insertSheet("DupReport");
  reportSheet.clear();
  reportSheet.getRange(1, 1, report.length, report[0].length).setValues(report);
  reportSheet.setFrozenRows(1);

  const kept = rows.filter((_, i) => keep.has(i));
  const summary = `${rows.length} rows -> ${kept.length} kept, ${rows.length - kept.length} duplicates` +
    `, ${report.length - 1} lines had copies, ${report.slice(1).filter(r => r[5]).length} with differing values`;
  const byCol = {};
  report.slice(1).forEach(r => String(r[5]).split(" ").filter(Boolean).forEach(c => byCol[c] = (byCol[c] || 0) + 1));
  Logger.log("Lines differing per column: " + JSON.stringify(byCol));
  Logger.log((DRY_RUN ? "DRY RUN: " : "") + summary);

  if (DRY_RUN) {
    SpreadsheetApp.getUi().alert("Dry run - nothing changed.\n\n" + summary + "\n\nSee the DupReport tab.");
    return;
  }

  const stamp = Utilities.formatDate(new Date(), tz, "yyyyMMdd_HHmm");
  sheet.copyTo(ss).setName("Purchases_backup_" + stamp);

  sheet.getRange(2, 1, lastRow - 1, width).clearContent();
  writeRowsAsText(sheet, kept, width, tz);
  SpreadsheetApp.getUi().alert("Done.\n\n" + summary + "\n\nBackup tab: Purchases_backup_" + stamp);
}
