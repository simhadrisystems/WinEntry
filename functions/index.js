/**
 * WinEntry Cloud Functions
 *
 * createUserSheet  — HTTP (admin tool / manual provisioning fallback)
 * onAdminRequestCreated — Firestore trigger on /admin_requests/{uid}
 *
 * Both share the createUserSheetForUser() helper which:
 *   1. Creates the Google Sheet with 4 tabs + headers IN the shared admin folder
 *   2. Shares the sheet with the end user (writer access)
 *   3. Writes /users/{uid} to Firestore
 *
 * Auth — TWO separate auth paths:
 *
 *   Drive / Sheets (file creation): OAuth2 refresh token for the admin Google account
 *   (tomsrao@gmail.com).  Service accounts have ZERO Drive storage quota and cannot
 *   own Drive files.  Files must be owned by a real Google account.
 *   Config: firebase functions:config:set
 *     adminsheets.client_id=...
 *     adminsheets.client_secret=...
 *     adminsheets.refresh_token=...
 *
 *   Firebase Admin SDK (Firestore writes): firebase-admin initialised below, which
 *   automatically uses the runtime service account — no keyFile needed.
 *
 *   Sheets API (UserRegistry, user sheets, master sheet): every function runs as
 *   SHEETS_SA, so GoogleAuth picks up its credentials from the runtime — no key file.
 *   The sheets are shared with this SA, so the identity must stay the same.
 */

const functions   = require("firebase-functions");
const { defineSecret } = require("firebase-functions/params");
const admin       = require("firebase-admin");
const { google }  = require("googleapis");

const SHEETS_SA = "firebase-adminsdk-fbsvc@winentry-a87f2.iam.gserviceaccount.com";

admin.initializeApp();
const db = admin.firestore();

// ── Secret Manager bindings ───────────────────────────────────────────────────
const SECRET_CLIENT_ID     = defineSecret("ADMIN_OAUTH_CLIENT_ID");
const SECRET_CLIENT_SECRET = defineSecret("ADMIN_OAUTH_CLIENT_SECRET");
const SECRET_REFRESH_TOKEN = defineSecret("ADMIN_OAUTH_REFRESH_TOKEN");

// ── Admin OAuth2 client (for Drive + Sheets file-creation operations) ─────────
function getAdminAuth() {
  const clientId     = SECRET_CLIENT_ID.value();
  const clientSecret = SECRET_CLIENT_SECRET.value();
  const refreshToken = SECRET_REFRESH_TOKEN.value();

  if (!clientId || !clientSecret || !refreshToken) {
    throw new Error(
      "Admin OAuth credentials missing from Secret Manager. Run: " +
      "firebase functions:secrets:set ADMIN_OAUTH_CLIENT_ID  (and the other two)"
    );
  }
  const client = new google.auth.OAuth2(
    clientId,
    clientSecret,
    "https://developers.google.com/oauthplayground"
  );
  client.setCredentials({ refresh_token: refreshToken });
  return client;
}

// ── CONFIG ────────────────────────────────────────────────────────────────────

/** Drive folder where all user sheets live. Owned by simhadrisystems@gmail.com. */
const SHARED_FOLDER_ID = "1WjjaEH1Ps37xl_XBsbia_0mXXLMBLqpL";

const ADMIN_EMAIL = "simhadrisystems@gmail.com";

/** Service account that owns write access to every user sheet. */
const SERVICE_ACCOUNT_EMAIL = "firebase-adminsdk-fbsvc@winentry-a87f2.iam.gserviceaccount.com";

/** Master products sheet (simhadrisystems@gmail.com Drive, WinEntry app folder). */
const MASTER_SHEET_ID = "1rOd-l13Vs1LRa764lM40sjDeN70FWOArCn6NpfHUggs";

/** Admin user registry sheet (simhadrisystems@gmail.com Drive, WinEntry app folder). */
const REGISTRY_SHEET_ID = "1zw5Xek9lW4cohUbjaX6468ZqGF7m7--mdoplByv6Rps";

// ── TAB HEADERS (must match CloudSyncManager.kt exactly) ─────────────────────

const PURCHASES_HEADERS = [
  "TxnId", "Date", "ProductCode", "ProductName", "InvoiceNo", "Supplier",
  "QQ_Boxes", "QQ_Loose", "QQ_Total", "QQ_Price", "QQ_Cost",
  "PP_Boxes", "PP_Loose", "PP_Total", "PP_Price", "PP_Cost",
  "NN_Boxes", "NN_Loose", "NN_Total", "NN_Price", "NN_Cost",
  "DD_Boxes", "DD_Loose", "DD_Total", "DD_Price", "DD_Cost",
  "TotalCost", "Notes", "ReceivedDate"
];
const DAILYSTOCK_HEADERS = [
  "Date", "ProductCode",
  "Open_QQ", "Open_PP", "Open_NN", "Open_DD",
  "Close_QQ", "Close_PP", "Close_NN", "Close_DD",
  "Sale_QQ", "Sale_PP", "Sale_NN", "Sale_DD",
  "Price_QQ", "Price_PP", "Price_NN", "Price_DD",
  "Amt_QQ", "Amt_PP", "Amt_NN", "Amt_DD",
  "SaleAmount", "Committed", "OpeningStock"
];
const DAYSUMMARY_HEADERS = [
  "Date", "TotalSales", "UPI", "Expenses", "CashDeposit", "Notes"
];
const PURCHASEIMPORT_TEMPLATE = [
  ["INVOICE", ""],
  ["DATE",    ""],
  ["S.No", "Brand Code", "Product Name",
   "QQ Boxes", "QQ Loose", "PP Boxes", "PP Loose",
   "NN Boxes", "NN Loose", "DD Boxes", "DD Loose", "Notes"]
];

// ── HELPER: invite check ──────────────────────────────────────────────────────

/**
 * Returns { invited: true, role, invitedBy, notes } if the email is in
 * invited_users, or { invited: false } if not.
 * Document ID is the normalized (lowercase, trimmed) email address.
 */
async function isUserInvited(email) {
  if (!email) return { invited: false };
  const key = email.trim().toLowerCase();
  const doc = await db.collection("invited_users").doc(key).get();
  if (!doc.exists) return { invited: false };
  const d = doc.data();
  return {
    invited:   true,
    role:      d.role      || "editor",
    invitedBy: d.invitedBy || "",
    notes:     d.notes     || ""
  };
}

// ── SHARED HELPER: create user sheet + write Firestore ────────────────────────

function userSheetTitle(email, uid) {
  const safeEmail = (email || uid).replace(/@/g, "_at_").replace(/\./g, "_");
  return `WinEntry \u2013 ${safeEmail}`;  // \u2013 = en-dash, avoids copy-paste encoding issues
}

// Runs as SHEETS_SA, which every user sheet is shared with, so this finds sheets in the
// current SHARED_FOLDER_ID and in the admin's old personal-Drive folder alike.
function saDrive() {
  const auth = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/drive.metadata.readonly"] });
  return google.drive({ version: "v3", auth });
}

/** Most recently modified, non-trashed sheet with the user's title, or null. */
async function findUserSheetByTitle(email, uid) {
  const title = userSheetTitle(email, uid).replace(/'/g, "\\'");
  const res = await saDrive().files.list({
    q: `name='${title}' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false`,
    fields: "files(id, modifiedTime)",
    orderBy: "modifiedTime desc",
    pageSize: 5,
  });
  const files = res.data.files || [];
  if (files.length > 1) console.warn(`findUserSheetByTitle: ${files.length} sheets titled "${title}" — using newest ${files[0].id}`);
  return files.length ? files[0].id : null;
}

/**
 * False when the file was deleted, trashed, or is not shared with SHEETS_SA (Drive answers
 * 404 for all three). Anything else throws: treating e.g. a 403 API error as "gone" would
 * make createUserSheetForUser create a blank duplicate for a user whose sheet is fine.
 */
async function sheetExists(spreadsheetId) {
  try {
    const f = await saDrive().files.get({ fileId: spreadsheetId, fields: "id, trashed" });
    return !f.data.trashed;
  } catch (err) {
    if (err.code === 404) return false;
    throw err;
  }
}

async function relinkUserSheet(uid, sheetId) {
  await db.collection("users").doc(uid).set({
    userSheetId:  sheetId,
    userSheetUrl: `https://docs.google.com/spreadsheets/d/${sheetId}`,
    sheetRelinkedAt: admin.firestore.FieldValue.serverTimestamp()
  }, { merge: true });
}

/**
 * Creates a personal Google Sheet for the user, moves it to the shared admin
 * folder, shares it with the user, and writes /users/{uid} to Firestore.
 *
 * Idempotent via TWO checks (in order):
 *   1. Firestore: if /users/{uid} already has a userSheetId, return immediately.
 *   2. Drive search fix: if the sheet file already exists in SHARED_FOLDER_ID
 *      (e.g. Firestore doc was lost after a partial failure), recover and
 *      restore the Firestore doc instead of creating a duplicate sheet.
 *
 * @param {string} uid
 * @param {string} email
 * @param {string} displayName
 * @returns {Promise<{sheetId: string, sheetUrl: string, existing: boolean}>}
 */
async function createUserSheetForUser(uid, email, displayName, extraData = {}) {
  const safeDisplayName = displayName || email || "User";
  const sheetTitle = userSheetTitle(email, uid);

  // ── Build Google API clients up front (needed for Drive search + creation) ─
  const auth   = getAdminAuth();
  const sheets = google.sheets({ version: "v4", auth });
  const drive  = google.drive({ version: "v3", auth });

  // ── Check 1: Drive search (PRIMARY gate — immune to Firestore being cleared) ─
  // Always search Drive first. Even if Firestore docs are wiped for testing,
  // an existing sheet in the shared folder is found and reused — no duplicates.
  // Searches every folder SHEETS_SA can see (old personal-Drive folder included); searching
  // only SHARED_FOLDER_ID created blank duplicates for users whose sheet predates the move.
  try {
    let recoveredId = await findUserSheetByTitle(email, uid).catch(err => {
      console.warn(`SA sheet search failed for uid=${uid}: ${err.message} — trying admin folder search`);
      return null;
    });
    if (!recoveredId) {
      const driveSearch = await drive.files.list({
        q: `name='${sheetTitle}' and '${SHARED_FOLDER_ID}' in parents and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false`,
        fields: "files(id)",
        spaces: "drive",
        pageSize: 1,
      });
      const found = driveSearch.data.files;
      if (found && found.length > 0) recoveredId = found[0].id;
    }
    if (recoveredId) {
      const recoveredUrl = `https://docs.google.com/spreadsheets/d/${recoveredId}`;
      console.log(`Drive search found existing sheet for uid=${uid}: ${recoveredId} — reusing`);
      await db.collection("users").doc(uid).set({
        uid,
        email:        email || "",
        displayName:  safeDisplayName,
        ownerName:      extraData.ownerName      || "",
        businessName:   extraData.businessName   || "",
        phone:          extraData.phone          || "",
        location:       extraData.location       || "",
        androidVersion: extraData.androidVersion || "",
        appVersion:     extraData.appVersion     || "",
        userSheetId:  recoveredId,
        userSheetUrl: recoveredUrl,
        role:         extraData.role || "editor",
        createdAt:    admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
      return { sheetId: recoveredId, sheetUrl: recoveredUrl, existing: true };
    }
    console.log(`Drive search: no existing sheet found for uid=${uid} — will create`);
  } catch (err) {
    console.warn(`Drive search failed for uid=${uid}: ${err.message} — falling through to Firestore check`);
  }

  // ── Check 2: Firestore (secondary gate — catches Drive search failures) ────
  const userDoc = await db.collection("users").doc(uid).get();
  if (userDoc.exists && userDoc.data().userSheetId && await sheetExists(userDoc.data().userSheetId)) {
    const existingId = userDoc.data().userSheetId;
    console.log(`Firestore: sheet already exists for uid=${uid}: ${existingId}`);
    return {
      sheetId:  existingId,
      sheetUrl: `https://docs.google.com/spreadsheets/d/${existingId}`,
      existing: true
    };
  }

  // ── Create spreadsheet directly inside the shared admin folder ─────────────
  const driveRes = await drive.files.create({
    requestBody: {
      name:     sheetTitle,
      mimeType: "application/vnd.google-apps.spreadsheet",
      parents:  [SHARED_FOLDER_ID]
    },
    fields: "id"
  });
  const spreadsheetId  = driveRes.data.id;
  const spreadsheetUrl = `https://docs.google.com/spreadsheets/d/${spreadsheetId}`;
  console.log(`Created spreadsheet ${spreadsheetId} in shared folder for uid=${uid}`);

  // ── Add tabs (Drive create gives a single blank sheet; rename + add tabs) ──
  try {
    await sheets.spreadsheets.batchUpdate({
      spreadsheetId,
      requestBody: {
        requests: [
          { updateSheetProperties: { properties: { sheetId: 0, title: "Purchases",  gridProperties: { frozenRowCount: 1 } }, fields: "title,gridProperties.frozenRowCount" } },
          { addSheet: { properties: { title: "DailyStock",     index: 1, gridProperties: { frozenRowCount: 1 } } } },
          { addSheet: { properties: { title: "DaySummary",     index: 2, gridProperties: { frozenRowCount: 1 } } } },
          { addSheet: { properties: { title: "PurchaseImport", index: 3, gridProperties: { frozenRowCount: 3 } } } }
        ]
      }
    });
  } catch (err) {
    console.warn("Tab setup failed (non-fatal):", err.message);
  }

  // ── Write headers ───────────────────────────────────────────────────────────
  try {
    await sheets.spreadsheets.values.batchUpdate({
      spreadsheetId,
      requestBody: {
        valueInputOption: "RAW",
        data: [
          { range: "Purchases!A1",      values: [PURCHASES_HEADERS] },
          { range: "DailyStock!A1",     values: [DAILYSTOCK_HEADERS] },
          { range: "DaySummary!A1",     values: [DAYSUMMARY_HEADERS] },
          { range: "PurchaseImport!A1", values: PURCHASEIMPORT_TEMPLATE }
        ]
      }
    });
  } catch (err) {
    console.warn("Header write failed (non-fatal):", err.message);
  }

  // ── Format header rows ──────────────────────────────────────────────────────
  try {
    const meta = await sheets.spreadsheets.get({ spreadsheetId });
    const fmtRequests = meta.data.sheets
      .filter(s => s.properties.title !== "PurchaseImport")
      .map(s => ({
        repeatCell: {
          range: { sheetId: s.properties.sheetId, startRowIndex: 0, endRowIndex: 1 },
          cell: {
            userEnteredFormat: {
              backgroundColor: { red: 0.18, green: 0.33, blue: 0.53 },
              textFormat: { bold: true, foregroundColor: { red: 1, green: 1, blue: 1 }, fontSize: 10 }
            }
          },
          fields: "userEnteredFormat(backgroundColor,textFormat)"
        }
      }));
    if (fmtRequests.length > 0) {
      await sheets.spreadsheets.batchUpdate({ spreadsheetId, requestBody: { requests: fmtRequests } });
    }
  } catch (err) {
    console.warn("Header formatting skipped (non-fatal):", err.message);
  }

  // ── Share with service account (writer) — NOT with the user Gmail ──────────
  // The sheet is invisible in the user's Google Drive. All sync goes through
  // the syncUserSheet Cloud Function, which authenticates via service account.
  try {
    await drive.permissions.create({
      fileId: spreadsheetId,
      requestBody: { type: "user", role: "writer", emailAddress: SERVICE_ACCOUNT_EMAIL },
      sendNotificationEmail: false
    });
    console.log(`Granted SA writer access on ${spreadsheetId}`);
  } catch (err) {
    console.warn("SA permission grant failed (non-fatal):", err.message);
  }

  // ── Write /users/{uid} to Firestore ────────────────────────────────────────
  await db.collection("users").doc(uid).set({
    uid,
    email:          email || "",
    displayName:    safeDisplayName,
    ownerName:      extraData.ownerName      || "",
    businessName:   extraData.businessName   || "",
    phone:          extraData.phone          || "",
    location:       extraData.location       || "",
    androidVersion: extraData.androidVersion || "",
    appVersion:     extraData.appVersion     || "",
    userSheetId:    spreadsheetId,
    userSheetUrl:   spreadsheetUrl,
    role:           extraData.role || "editor",
    createdAt:      admin.firestore.FieldValue.serverTimestamp()
  });

  console.log(`User setup complete — uid=${uid}, sheetId=${spreadsheetId}`);
  return { sheetId: spreadsheetId, sheetUrl: spreadsheetUrl, existing: false };
}

// ── HELPER: append row to BOTH registry tabs ─────────────────────────────────
// UserRegistry (12 cols) — CF's own format, written only by Cloud Function
// AppRequests  (16 cols) — Android-compatible format, written by both app & CF

async function appendToRegistry(uid, data, sheetId, sheetUrl, status = "sheet_created", forceWriteRegistry = false) {
  try {
    const auth   = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
    const sheets = google.sheets({ version: "v4", auth });

    const spreadsheet  = await sheets.spreadsheets.get({ spreadsheetId: REGISTRY_SHEET_ID });
    const existingTabs = (spreadsheet.data.sheets || []).map(s => s.properties.title);

    // ── Timestamps ────────────────────────────────────────────────────────────
    let registeredAt = "";
    try {
      const ts = data.registeredAt;
      const dt = ts && ts.toDate ? ts.toDate() : new Date();
      registeredAt = dt.toLocaleString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: true });
    } catch (_) { registeredAt = new Date().toISOString(); }
    const processedAt = new Date().toLocaleString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: true });

    // ── Re-registration detection ─────────────────────────────────────────────
    // Check account_deletions/{emailKey} to detect users who deleted and re-registered.
    // This collection persists beyond account deletion — it is admin audit data, not user data.
    let reRegNote = "";
    try {
      const emailKey = (data.email || "").trim().toLowerCase()
        .replace(/@/g, "_at_").replace(/\./g, "_");
      if (emailKey) {
        const deletionDoc = await db.collection("account_deletions").doc(emailKey).get();
        if (deletionDoc.exists) {
          const d = deletionDoc.data();
          const count = d.deletionCount || 0;
          if (count > 0) {
            const lastDate = d.lastDeletedAt && d.lastDeletedAt.toDate
              ? d.lastDeletedAt.toDate().toLocaleDateString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", year: "numeric" })
              : "";
            reRegNote = `Re-registration #${count + 1} (prev account deleted${lastDate ? ": " + lastDate : ""})`;
            console.log(`appendToRegistry — re-registration detected for ${data.email}: ${reRegNote}`);
          }
        }
      }
    } catch (err) {
      console.warn("Re-registration check failed (non-fatal):", err.message);
    }

    // ── 1. UserRegistry tab (12 cols — CF format) ─────────────────────────────
    // A=No  B=Registered At  C=Email  D=Owner Name  E=Business Name
    // F=Phone  G=Location  H=UID  I=Android/App Version  J=Status  K=Sheet Assigned  L=Sheet URL
    const UR_TAB     = "UserRegistry";
    const UR_HEADERS = ["No", "Registered At", "Email", "Owner Name", "Business Name", "Phone", "Location", "UID", "Android/App Version", "Status", "Sheet Assigned", "Sheet URL"];
    if (!existingTabs.includes(UR_TAB)) {
      await sheets.spreadsheets.batchUpdate({ spreadsheetId: REGISTRY_SHEET_ID, requestBody: { requests: [{ addSheet: { properties: { title: UR_TAB, gridProperties: { frozenRowCount: 1 } } } }] } });
      await sheets.spreadsheets.values.update({ spreadsheetId: REGISTRY_SHEET_ID, range: `${UR_TAB}!A1`, valueInputOption: "RAW", requestBody: { values: [UR_HEADERS] } });
    }
    const urExistingRows = (await sheets.spreadsheets.values.get({ spreadsheetId: REGISTRY_SHEET_ID, range: `${UR_TAB}!A:H` })).data.values || [];
    const urMatchIdx     = urExistingRows.findIndex((r, i) => i > 0 && r[7] === uid);
    const urRowNum       = urMatchIdx > 0 ? urMatchIdx : Math.max(1, urExistingRows.length);
    const versionInfo = [data.androidVersion, data.appVersion].filter(Boolean).join(" | ");
    // Skip if user already has a registry entry and we're doing a lower-priority registered_only write
    if (status === "registered_only" && urMatchIdx > 0 && !forceWriteRegistry) {
      console.log(`UserRegistry — uid=${uid} already registered, skipping registered_only write`);
    } else {
      // Re-registration note goes into Sheet URL column (last available) for UserRegistry
      // since that tab has no dedicated Notes column.
      const urSheetUrl = reRegNote || sheetUrl || "";
      const urRow = [urRowNum, registeredAt, data.email || "", data.ownerName || "", data.businessName || "", data.phone || "", data.location || "", uid, versionInfo, status, sheetId || "", urSheetUrl];
      if (urMatchIdx > 0) {
        await sheets.spreadsheets.values.update({ spreadsheetId: REGISTRY_SHEET_ID, range: `${UR_TAB}!A${urMatchIdx + 1}`, valueInputOption: "RAW", requestBody: { values: [urRow] } });
      } else {
        await sheets.spreadsheets.values.append({ spreadsheetId: REGISTRY_SHEET_ID, range: `${UR_TAB}!A:L`, valueInputOption: "RAW", insertDataOption: "INSERT_ROWS", requestBody: { values: [urRow] } });
      }
      console.log(`UserRegistry updated — uid=${uid}, row=${urRowNum}`);
    }

    // ── 2. AppRequests tab (16 cols — Android-compatible format) ──────────────
    // A=Row#  B=Registered At  C=Email  D=Owner Name  E=Business Name  F=Display Name
    // G=Location  H=Phone  I=UID  J=Android/App Version  K=Status  L=Sheet ID  M=Sheet URL
    // N=Processed At  O=Role  P=Notes
    const AR_TAB     = "AppRequests";
    const AR_HEADERS = ["Row#", "Registered At", "Email", "Owner Name", "Business Name", "Display Name", "Location", "Phone", "UID", "Android/App Version", "Status", "Sheet ID", "Sheet URL", "Processed At", "Role", "Notes"];
    if (!existingTabs.includes(AR_TAB)) {
      await sheets.spreadsheets.batchUpdate({ spreadsheetId: REGISTRY_SHEET_ID, requestBody: { requests: [{ addSheet: { properties: { title: AR_TAB, gridProperties: { frozenRowCount: 1 } } } }] } });
      await sheets.spreadsheets.values.update({ spreadsheetId: REGISTRY_SHEET_ID, range: `${AR_TAB}!A1`, valueInputOption: "RAW", requestBody: { values: [AR_HEADERS] } });
    }
    const arExistingRows = (await sheets.spreadsheets.values.get({ spreadsheetId: REGISTRY_SHEET_ID, range: `${AR_TAB}!A:I` })).data.values || [];
    const arMatchIdx     = arExistingRows.findIndex((r, i) => i > 0 && r[8] === uid);
    const arRowNum       = arMatchIdx > 0 ? arMatchIdx : Math.max(1, arExistingRows.length);
    const displayName    = data.displayName || data.ownerName || "";
    // Skip if user already has an entry and we're doing a lower-priority registered_only write
    if (status === "registered_only" && arMatchIdx > 0 && !forceWriteRegistry) {
      console.log(`AppRequests — uid=${uid} already registered, skipping registered_only write`);
    } else {
      const arRow = [arRowNum, registeredAt, data.email || "", data.ownerName || "", data.businessName || "", displayName, data.location || "", data.phone || "", uid, versionInfo, status, sheetId || "", sheetUrl || "", processedAt, "editor", reRegNote];
      if (arMatchIdx > 0) {
        await sheets.spreadsheets.values.update({ spreadsheetId: REGISTRY_SHEET_ID, range: `${AR_TAB}!A${arMatchIdx + 1}`, valueInputOption: "RAW", requestBody: { values: [arRow] } });
      } else {
        await sheets.spreadsheets.values.append({ spreadsheetId: REGISTRY_SHEET_ID, range: `${AR_TAB}!A:P`, valueInputOption: "RAW", insertDataOption: "INSERT_ROWS", requestBody: { values: [arRow] } });
      }
      console.log(`AppRequests updated — uid=${uid}, row=${arRowNum}`);
    }

  } catch (err) {
    console.error("Registry append failed:", err.message);
  }
}

// ── HELPER: soft-delete both registry tab rows for uid ────────────────────────
// Blanks all PII fields (name, phone, business, location, version, sheet refs)
// while retaining Row#, Registered At, Email (abuse detection), UID, Role, and
// a deletion timestamp in the Notes / Sheet URL column.
// Called from deleteUserRegistration before the Firebase Auth account is removed.

async function softDeleteRegistryRows(uid) {
  const auth   = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
  const sheets = google.sheets({ version: "v4", auth });
  const deletedAt = new Date().toLocaleString("en-IN", {
    timeZone: "Asia/Kolkata", day: "2-digit", month: "short",
    year: "numeric", hour: "2-digit", minute: "2-digit", hour12: true
  });

  // UserRegistry: UID at col H (index 7), 12 cols A–L
  // Keep: A(Row#), B(Registered At), C(Email), H(UID)
  // Blank: D(Owner), E(Business), F(Phone), G(Location), I(Version), K(Sheet Assigned)
  // Set:   J(Status) = "account_deleted", L(Sheet URL) = deletion timestamp
  try {
    const urData = (await sheets.spreadsheets.values.get({
      spreadsheetId: REGISTRY_SHEET_ID, range: "UserRegistry!A:L"
    })).data.values || [];
    const urIdx = urData.findIndex((r, i) => i > 0 && r[7] === uid);
    if (urIdx > 0) {
      const r = urData[urIdx];
      await sheets.spreadsheets.values.update({
        spreadsheetId: REGISTRY_SHEET_ID,
        range: `UserRegistry!A${urIdx + 1}`,
        valueInputOption: "RAW",
        requestBody: { values: [[
          r[0] || "",         // A Row#          — keep
          r[1] || "",         // B Registered At — keep for audit
          r[2] || "",         // C Email         — keep for abuse detection
          "",                 // D Owner Name    — blanked
          "",                 // E Business Name — blanked
          "",                 // F Phone         — blanked
          "",                 // G Location      — blanked
          uid,                // H UID           — keep for audit
          "",                 // I Version       — blanked
          "account_deleted",  // J Status
          "",                 // K Sheet Assigned — blanked
          `Deleted: ${deletedAt}` // L Sheet URL — repurposed as deletion note
        ]] }
      });
      console.log(`UserRegistry soft-deleted uid=${uid} at row ${urIdx + 1}`);
    } else {
      console.log(`UserRegistry: no row found for uid=${uid} — skipping soft-delete`);
    }
  } catch (err) {
    console.warn("UserRegistry soft-delete failed:", err.message);
  }

  // AppRequests: UID at col I (index 8), 16 cols A–P
  // Keep: A(Row#), B(Registered At), C(Email), I(UID), N(Processed At), O(Role)
  // Blank: D(Owner), E(Business), F(Display), G(Location), H(Phone), J(Version), L(SheetID), M(SheetURL)
  // Set:   K(Status) = "account_deleted", P(Notes) = deletion timestamp
  try {
    const arData = (await sheets.spreadsheets.values.get({
      spreadsheetId: REGISTRY_SHEET_ID, range: "AppRequests!A:P"
    })).data.values || [];
    const arIdx = arData.findIndex((r, i) => i > 0 && r[8] === uid);
    if (arIdx > 0) {
      const r = arData[arIdx];
      await sheets.spreadsheets.values.update({
        spreadsheetId: REGISTRY_SHEET_ID,
        range: `AppRequests!A${arIdx + 1}`,
        valueInputOption: "RAW",
        requestBody: { values: [[
          r[0] || "",         // A Row#          — keep
          r[1] || "",         // B Registered At — keep for audit
          r[2] || "",         // C Email         — keep for abuse detection
          "",                 // D Owner Name    — blanked
          "",                 // E Business Name — blanked
          "",                 // F Display Name  — blanked
          "",                 // G Location      — blanked
          "",                 // H Phone         — blanked
          uid,                // I UID           — keep for audit
          "",                 // J Version       — blanked
          "account_deleted",  // K Status
          "",                 // L Sheet ID      — blanked
          "",                 // M Sheet URL     — blanked
          r[13] || "",        // N Processed At  — keep for audit
          r[14] || "",        // O Role          — keep for audit
          `Deleted: ${deletedAt}` // P Notes
        ]] }
      });
      console.log(`AppRequests soft-deleted uid=${uid} at row ${arIdx + 1}`);
    } else {
      console.log(`AppRequests: no row found for uid=${uid} — skipping soft-delete`);
    }
  } catch (err) {
    console.warn("AppRequests soft-delete failed:", err.message);
  }
}

// ── SYNC HELPERS (used by syncUserSheet CF) ───────────────────────────────────

// Sheets date serial → "yyyy-MM-dd". Handles both integer serials and
// decimal strings (e.g. "46087.0") produced by UNFORMATTED_VALUE reads.
function serialToDateStr(serial) {
  const epoch = new Date(Date.UTC(1899, 11, 30));
  const d = new Date(epoch.getTime() + Math.round(parseFloat(serial)) * 86400000);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth()+1).padStart(2,'0')}-${String(d.getUTCDate()).padStart(2,'0')}`;
}

// Normalise any date representation to "yyyy-MM-dd".
// Handles: "yyyy-MM-dd" string, Sheets serial number, or falls back to raw string.
function normalizeDate(val) {
  if (val == null) return '';
  const s = String(val).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
  const num = parseFloat(s);
  if (!isNaN(num) && num > 1000) return serialToDateStr(num);
  return s;
}

async function writePurchases(sheets, spreadsheetId, rows) {
  if (!rows.length) return { written: 0, updated: 0, inserted: 0 };

  const existing = (await sheets.spreadsheets.values.get({
    spreadsheetId, range: "Purchases!A:A"
  })).data.values || [];

  const txnIdToRow = {};
  for (let i = 1; i < existing.length; i++) {
    const id = existing[i] && existing[i][0];
    if (id) txnIdToRow[id] = i + 1;
  }

  const updateData = [];
  const newRows    = [];
  for (const row of rows) {
    const txnId = row[0];
    if (txnIdToRow[txnId]) {
      updateData.push({ range: `Purchases!A${txnIdToRow[txnId]}`, values: [row] });
    } else {
      newRows.push(row);
    }
  }

  if (updateData.length) {
    await sheets.spreadsheets.values.batchUpdate({
      spreadsheetId,
      requestBody: { valueInputOption: "RAW", data: updateData }
    });
  }
  if (newRows.length) {
    await sheets.spreadsheets.values.append({
      spreadsheetId, range: "Purchases!A:AC",
      valueInputOption: "RAW", insertDataOption: "INSERT_ROWS",
      requestBody: { values: newRows }
    });
  }
  return { written: rows.length, updated: updateData.length, inserted: newRows.length };
}

async function deletePurchases(sheets, spreadsheetId, txnIds) {
  if (!txnIds.length) return { deleted: 0 };

  const [existingRes, metaRes] = await Promise.all([
    sheets.spreadsheets.values.get({ spreadsheetId, range: "Purchases!A:A" }),
    sheets.spreadsheets.get({ spreadsheetId })
  ]);

  const existing = existingRes.data.values || [];
  const purchasesSheet = metaRes.data.sheets.find(s => s.properties.title === "Purchases");
  if (!purchasesSheet) return { deleted: 0, error: "Purchases tab not found" };
  const sheetId = purchasesSheet.properties.sheetId;

  const txnSet = new Set(txnIds);
  const rowIndices = [];
  for (let i = 1; i < existing.length; i++) {
    const id = existing[i] && existing[i][0];
    if (id && txnSet.has(id)) rowIndices.push(i); // 0-based; row 0 is header
  }
  if (!rowIndices.length) return { deleted: 0 };

  // Sort descending so deleting later rows doesn't shift earlier indices
  rowIndices.sort((a, b) => b - a);
  const requests = rowIndices.map(idx => ({
    deleteDimension: { range: { sheetId, dimension: "ROWS", startIndex: idx, endIndex: idx + 1 } }
  }));
  await sheets.spreadsheets.batchUpdate({ spreadsheetId, requestBody: { requests } });
  return { deleted: rowIndices.length };
}

async function writeDailyStock(sheets, spreadsheetId, rows) {
  if (!rows.length) return { written: 0, updated: 0, inserted: 0 };

  // UNFORMATTED_VALUE returns raw serial numbers for date-formatted cells;
  // normalizeDate() converts both serials and "yyyy-MM-dd" strings uniformly.
  const existing = (await sheets.spreadsheets.values.get({
    spreadsheetId, range: "DailyStock!A:B",
    valueRenderOption: "UNFORMATTED_VALUE"
  })).data.values || [];

  const keyToRow = {};
  for (let i = 1; i < existing.length; i++) {
    const rawDate = existing[i] && existing[i][0];
    const code    = existing[i] && existing[i][1];
    if (rawDate != null && code) keyToRow[`${normalizeDate(rawDate)}|${code}`] = i + 1;
  }

  const updateData = [];
  const newRows    = [];
  for (const row of rows) {
    const key = `${row[0]}|${row[1]}`;
    if (keyToRow[key]) {
      updateData.push({ range: `DailyStock!A${keyToRow[key]}`, values: [row] });
    } else {
      newRows.push(row);
    }
  }

  if (updateData.length) {
    await sheets.spreadsheets.values.batchUpdate({
      spreadsheetId,
      requestBody: { valueInputOption: "RAW", data: updateData }
    });
  }
  if (newRows.length) {
    await sheets.spreadsheets.values.append({
      spreadsheetId, range: "DailyStock!A:Y",
      valueInputOption: "RAW", insertDataOption: "INSERT_ROWS",
      requestBody: { values: newRows }
    });
  }
  return { written: rows.length, updated: updateData.length, inserted: newRows.length };
}

async function writeDaySummary(sheets, spreadsheetId, rows) {
  if (!rows.length) return { written: 0, updated: 0, inserted: 0 };

  const existing = (await sheets.spreadsheets.values.get({
    spreadsheetId, range: "DaySummary!A:A",
    valueRenderOption: "UNFORMATTED_VALUE"
  })).data.values || [];

  const dateToRow = {};
  for (let i = 1; i < existing.length; i++) {
    const rawDate = existing[i] && existing[i][0];
    if (rawDate != null) dateToRow[normalizeDate(rawDate)] = i + 1;
  }

  const updateData = [];
  const newRows    = [];
  for (const row of rows) {
    const date = normalizeDate(row[0]);
    if (dateToRow[date]) {
      updateData.push({ range: `DaySummary!A${dateToRow[date]}`, values: [row] });
    } else {
      newRows.push(row);
    }
  }

  if (updateData.length) {
    await sheets.spreadsheets.values.batchUpdate({
      spreadsheetId,
      requestBody: { valueInputOption: "RAW", data: updateData }
    });
  }
  if (newRows.length) {
    await sheets.spreadsheets.values.append({
      spreadsheetId, range: "DaySummary!A:F",
      valueInputOption: "RAW", insertDataOption: "INSERT_ROWS",
      requestBody: { values: newRows }
    });
  }
  return { written: rows.length, updated: updateData.length, inserted: newRows.length };
}

async function readAll(sheets, spreadsheetId) {
  // UNFORMATTED_VALUE → date cells return serial numbers, which Android already
  // knows how to parse (readDailyStockFromSheet does the same conversion).
  const [purchasesRes, dailyStockRes, daySummaryRes, purchaseImportRes] = await Promise.all([
    sheets.spreadsheets.values.get({ spreadsheetId, range: "Purchases!A2:AC", valueRenderOption: "UNFORMATTED_VALUE" }),
    sheets.spreadsheets.values.get({ spreadsheetId, range: "DailyStock!A2:Y", valueRenderOption: "UNFORMATTED_VALUE" }),
    sheets.spreadsheets.values.get({ spreadsheetId, range: "DaySummary!A2:F", valueRenderOption: "UNFORMATTED_VALUE" }),
    sheets.spreadsheets.values.get({ spreadsheetId, range: "PurchaseImport!A1:L" })
      .catch(() => ({ data: { values: null } }))  // tab may not exist yet
  ]);
  // Column AC (ReceivedDate) may come back as a serial if edited in the sheet
  const purchases = (purchasesRes.data.values || []).map(row => {
    if (row.length > 28 && row[28] !== "" && row[28] != null) row[28] = normalizeDate(row[28]);
    return row;
  });
  return {
    purchases,
    dailyStock:     dailyStockRes.data.values     || [],
    daySummary:     daySummaryRes.data.values     || [],
    purchaseImport: purchaseImportRes.data.values || []
  };
}

async function clearAll(sheets, spreadsheetId) {
  await Promise.all([
    sheets.spreadsheets.values.clear({ spreadsheetId, range: "Purchases!A2:ZZ",  requestBody: {} }),
    sheets.spreadsheets.values.clear({ spreadsheetId, range: "DailyStock!A2:ZZ", requestBody: {} }),
    sheets.spreadsheets.values.clear({ spreadsheetId, range: "DaySummary!A2:ZZ", requestBody: {} })
  ]);
  return { cleared: true };
}

// ── HTTP FUNCTION: createUserSheet ────────────────────────────────────────────

exports.createUserSheet = functions
  .region("asia-south1")
  .runWith({
    serviceAccount: SHEETS_SA,
    timeoutSeconds: 60,
    memory: "256MB",
    secrets: ["ADMIN_OAUTH_CLIENT_ID", "ADMIN_OAUTH_CLIENT_SECRET", "ADMIN_OAUTH_REFRESH_TOKEN"]
  })
  .https.onRequest(async (req, res) => {

    res.set("Access-Control-Allow-Origin", "*");
    if (req.method === "OPTIONS") {
      res.set("Access-Control-Allow-Methods", "POST");
      res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return res.status(204).send("");
    }
    if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

    // Verify Firebase ID token
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) return res.status(401).json({ error: "Missing token" });

    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
    } catch (err) {
      return res.status(401).json({ error: "Invalid token" });
    }

    const uid = decodedToken.uid;
    const { email, displayName, ownerName, businessName, phone, location, androidVersion, appVersion } = req.body;

    // ── Invite check ──────────────────────────────────────────────────────────
    const invite = await isUserInvited(email);
    if (!invite.invited) {
      console.log(`createUserSheet — uid=${uid} (${email}) not in invited_users — recording request`);
      try {
        await db.collection("admin_requests").doc(uid).set({
          uid, email: email || "", displayName: displayName || "",
          ownerName: ownerName || "", businessName: businessName || "",
          phone: phone || "", location: location || "",
          androidVersion: androidVersion || "", appVersion: appVersion || "",
          status: "awaiting_approval",
          requestedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
      } catch (err) {
        console.warn(`admin_requests write failed for uid=${uid} (non-fatal): ${err.message}`);
      }
      await appendToRegistry(uid, { email, displayName, ownerName, businessName, phone, location, androidVersion, appVersion, registeredAt: null }, "", "", "awaiting_approval");
      return res.status(403).json({ error: "not_invited", message: "Your request has been recorded. The admin will set up your workspace shortly." });
    }

    try {
      const result = await createUserSheetForUser(uid, email, displayName, { ownerName, businessName, phone, location, androidVersion, appVersion, role: invite.role });

      // Write admin_requests/{uid} so admin always has a record regardless of which path ran.
      // Uses merge:true — safe to call even if a fallback doc already exists (won't re-trigger
      // onAdminRequestCreated since that trigger fires on .onCreate only).
      try {
        await db.collection("admin_requests").doc(uid).set({
          uid,
          email:          email          || "",
          displayName:    displayName    || "",
          ownerName:      ownerName      || "",
          businessName:   businessName   || "",
          phone:          phone          || "",
          location:       location       || "",
          androidVersion: androidVersion || "",
          appVersion:     appVersion     || "",
          userSheetId:    result.sheetId,
          userSheetUrl:   result.sheetUrl,
          status:         "sheet_created",
          processedAt:    admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
      } catch (err) {
        console.warn(`admin_requests write failed for uid=${uid} (non-fatal): ${err.message}`);
      }

      // Write registry rows — Android app cannot write registry (no sheet permission).
      await appendToRegistry(uid, {
        email, displayName, ownerName, businessName, phone, location, androidVersion, appVersion,
        registeredAt: null   // will fall back to current time in appendToRegistry
      }, result.sheetId, result.sheetUrl);
      return res.status(200).json(result);
    } catch (err) {
      console.error("createUserSheet failed:", err.message);
      return res.status(500).json({ error: err.message });
    }
  });

// ── HTTP FUNCTION: registerUserOnly ───────────────────────────────────────────
// Registers a user in Firestore and UserRegistry WITHOUT creating a Google Sheet.
// Called when the user taps "Download Master Products List" and has no Firestore doc.

exports.registerUserOnly = functions
  .region("asia-south1")
  .runWith({ serviceAccount: SHEETS_SA, timeoutSeconds: 30, memory: "256MB" })
  .https.onRequest(async (req, res) => {

    res.set("Access-Control-Allow-Origin", "*");
    if (req.method === "OPTIONS") {
      res.set("Access-Control-Allow-Methods", "POST");
      res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return res.status(204).send("");
    }
    if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) return res.status(401).json({ error: "Missing token" });

    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
    } catch (err) {
      return res.status(401).json({ error: "Invalid token" });
    }

    const uid = decodedToken.uid;
    const { email, displayName, ownerName, businessName, phone, location, androidVersion, appVersion } = req.body;
    const forceUpdate = req.body.forceUpdate === true;

    try {
      const userDoc = await db.collection("users").doc(uid).get();

      // Without forceUpdate: skip if user doc already exists
      if (userDoc.exists && !forceUpdate) {
        console.log(`registerUserOnly — uid=${uid} already in Firestore, skipping`);
        return res.status(200).json({ alreadyRegistered: true });
      }

      // Preserve sheet status if user already has a sheet (forceUpdate path)
      const existingData     = userDoc.exists ? userDoc.data() : {};
      const existingSheetId  = existingData.userSheetId  || "";
      const existingSheetUrl = existingData.userSheetUrl || "";
      const writeStatus      = existingSheetId ? "sheet_created" : "registered_only";

      const safeDisplayName = displayName || ownerName || email || "User";

      await db.collection("users").doc(uid).set({
        uid,
        email:          email          || "",
        displayName:    safeDisplayName,
        ownerName:      ownerName      || "",
        businessName:   businessName   || "",
        phone:          phone          || "",
        location:       location       || "",
        androidVersion: androidVersion || "",
        appVersion:     appVersion     || "",
        role:           existingData.role || "editor",
        registeredAt:   existingData.registeredAt || admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      await appendToRegistry(uid, {
        email, displayName: safeDisplayName, ownerName, businessName, phone, location,
        androidVersion, appVersion, registeredAt: null
      }, existingSheetId, existingSheetUrl, writeStatus, forceUpdate);

      // Ensure admin_requests doc exists so onInvitedUserAdded trigger can
      // provision a sheet when admin adds this user to invited_users.
      try {
        const reqDocRef = db.collection("admin_requests").doc(uid);
        const reqDoc = await reqDocRef.get();
        if (!reqDoc.exists) {
          await reqDocRef.set({
            uid,
            email:          email          || "",
            displayName:    safeDisplayName,
            ownerName:      ownerName      || "",
            businessName:   businessName   || "",
            phone:          phone          || "",
            location:       location       || "",
            androidVersion: androidVersion || "",
            appVersion:     appVersion     || "",
            status:         writeStatus,
            requestedAt:    admin.firestore.FieldValue.serverTimestamp()
          });
          console.log(`registerUserOnly — admin_requests/${uid} created (status=${writeStatus})`);
        }
      } catch (err) {
        console.warn("admin_requests write failed (non-fatal):", err.message);
      }

      console.log(`registerUserOnly — uid=${uid} ${forceUpdate ? "updated" : "registered"} (status=${writeStatus})`);
      return res.status(200).json({ registered: true, forceUpdate });
    } catch (err) {
      console.error("registerUserOnly failed:", err.message);
      return res.status(500).json({ error: err.message });
    }
  });

// ── FIRESTORE TRIGGER: onAdminRequestCreated ──────────────────────────────────

exports.onAdminRequestCreated = functions
  .region("asia-south1")
  .runWith({
    serviceAccount: SHEETS_SA,
    timeoutSeconds: 60,
    memory: "256MB",
    secrets: ["ADMIN_OAUTH_CLIENT_ID", "ADMIN_OAUTH_CLIENT_SECRET", "ADMIN_OAUTH_REFRESH_TOKEN"]
  })
  .firestore.document("admin_requests/{uid}")
  .onCreate(async (snap) => {
    const data = snap.data();
    const uid  = snap.id;

    // Skip if the HTTP CF already handled this request — it writes status="sheet_created"
    // before the document is created, so this trigger would otherwise run redundantly.
    if (data.status === "sheet_created") {
      console.log(`onAdminRequestCreated — uid=${uid} already processed by HTTP CF, skipping`);
      return null;
    }

    console.log(`onAdminRequestCreated — uid=${uid}, email=${data.email}`);

    // ── Invite check — only create sheet for approved users ───────────────
    const invite = await isUserInvited(data.email);
    if (!invite.invited) {
      console.log(`onAdminRequestCreated — uid=${uid} not in invited_users, recording as awaiting_approval`);
      try {
        await snap.ref.update({ status: "awaiting_approval", processedAt: admin.firestore.FieldValue.serverTimestamp() });
      } catch (_) {}
      await appendToRegistry(uid, data, "", "", "awaiting_approval");
      return null;
    }

    let sheetId  = "";
    let sheetUrl = "";

    // ── 1. Create (or recover) the user's sheet in admin's workspace ──────
    try {
      const displayName = data.ownerName || data.displayName || data.email || "";
      const result = await createUserSheetForUser(uid, data.email, displayName, {
        ownerName:      data.ownerName      || "",
        businessName:   data.businessName   || "",
        phone:          data.phone          || "",
        location:       data.location       || "",
        androidVersion: data.androidVersion || "",
        appVersion:     data.appVersion     || "",
        role:           invite.role
      });
      sheetId  = result.sheetId;
      sheetUrl = result.sheetUrl;
      console.log(`Sheet ready for uid=${uid}: ${sheetId} (existing=${result.existing})`);

      // ── 2. Update admin_requests document with outcome ─────────────────
      await snap.ref.update({
        status:       "sheet_created",
        userSheetId:  sheetId,
        userSheetUrl: sheetUrl,
        processedAt:  admin.firestore.FieldValue.serverTimestamp()
      });
    } catch (err) {
      console.error(`createUserSheetForUser failed for uid=${uid}:`, err.message);
      try {
        await snap.ref.update({
          status:      "creation_failed",
          errorMsg:    err.message,
          processedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      } catch (_) { /* status update failure is non-fatal */ }
    }

    // ── 3. Append to admin UserRegistry sheet (with actual sheetId) ───────
    await appendToRegistry(uid, data, sheetId, sheetUrl);
  });

// ── FIRESTORE TRIGGER: onInvitedUserAdded ────────────────────────────────────
// Fires whenever admin writes to invited_users/{emailKey} (create OR update).
// Finds all admin_requests with matching email + status "awaiting_approval" and
// creates their sheets automatically — no user action required.
//
// Admin workflow:
//   1. Add/update Firestore > invited_users > <email> doc  (e.g. { role: "editor" })
//   2. This trigger fires, creates the sheet, writes /users/{uid}, updates registry.
//   3. User opens app → Settings → Drive Backup card activates automatically.

exports.onInvitedUserAdded = functions
  .region("asia-south1")
  .runWith({
    serviceAccount: SHEETS_SA,
    timeoutSeconds: 120,
    memory: "256MB",
    secrets: ["ADMIN_OAUTH_CLIENT_ID", "ADMIN_OAUTH_CLIENT_SECRET", "ADMIN_OAUTH_REFRESH_TOKEN"]
  })
  .firestore.document("invited_users/{emailKey}")
  .onWrite(async (change, context) => {

    // Deletion — admin removed from invited_users: revoke cloud access.
    if (!change.after.exists) {
      const revokedEmail = context.params.emailKey;
      console.log(`onInvitedUserAdded — revocation triggered for email=${revokedEmail}`);

      let revokeSnap;
      try {
        revokeSnap = await db.collection("admin_requests")
          .where("email", "==", revokedEmail).get();
      } catch (err) {
        console.error(`onInvitedUserAdded revoke — query failed for ${revokedEmail}:`, err.message);
        return null;
      }

      if (revokeSnap.empty) {
        console.log(`onInvitedUserAdded revoke — no admin_requests found for ${revokedEmail}, nothing to revoke`);
        return null;
      }

      for (const requestDoc of revokeSnap.docs) {
        const uid = requestDoc.id;
        if (uid.includes("@")) continue;

        // Clear userSheetId from /users/{uid} — syncUserSheet will return no_sheet,
        // stopping cloud sync. The doc itself is preserved so re-adding the invite
        // recovers the existing Drive sheet rather than creating a new one.
        try {
          const userDocRef = db.collection("users").doc(uid);
          const userDoc    = await userDocRef.get();
          if (userDoc.exists) {
            await userDocRef.update({
              userSheetId:  admin.firestore.FieldValue.delete(),
              userSheetUrl: admin.firestore.FieldValue.delete(),
              revokedAt:    admin.firestore.FieldValue.serverTimestamp()
            });
            console.log(`onInvitedUserAdded revoke — cleared sheet access for uid=${uid}`);
          }
        } catch (err) {
          console.error(`onInvitedUserAdded revoke — failed to clear sheet for uid=${uid}:`, err.message);
        }

        // Mark admin_requests as revoked
        try {
          await requestDoc.ref.update({
            status:    "revoked",
            revokedAt: admin.firestore.FieldValue.serverTimestamp()
          });
        } catch (err) {
          console.error(`onInvitedUserAdded revoke — failed to update status for uid=${uid}:`, err.message);
        }
      }
      return null;
    }

    const afterData = change.after.data();
    // Document ID in invited_users IS the normalized email (see isUserInvited())
    const email = context.params.emailKey;
    const role  = (afterData && afterData.role) || "editor";

    console.log(`onInvitedUserAdded — triggered for email=${email}, role=${role}`);

    // Find all pending admin_requests for this email
    let pendingSnap;
    try {
      pendingSnap = await db.collection("admin_requests")
        .where("email", "==", email)
        .get();
    } catch (err) {
      console.error(`onInvitedUserAdded — query failed for email=${email}:`, err.message);
      return null;
    }

    if (pendingSnap.empty) {
      console.log(`onInvitedUserAdded — no admin_requests found for email=${email}, nothing to do`);
      return null;
    }

    // Process each pending request (almost always just one per email)
    for (const requestDoc of pendingSnap.docs) {
      const data = requestDoc.data();
      const uid  = requestDoc.id;

      // Guard: doc ID must be a Firebase UID, not an email address.
      // A stale doc with email-as-ID would write the email into the UID column of the registry.
      if (uid.includes("@")) {
        console.warn(`onInvitedUserAdded — skipping doc with email-like ID "${uid}" (should be a Firebase UID). Delete this stale doc from admin_requests.`);
        continue;
      }

      if (data.status === "sheet_created") {
        console.log(`onInvitedUserAdded — uid=${uid} already sheet_created, skipping`);
        continue;
      }

      console.log(`onInvitedUserAdded — creating sheet for uid=${uid} (${email})`);

      try {
        const displayName = data.ownerName || data.displayName || data.email || "";
        const result = await createUserSheetForUser(uid, data.email, displayName, {
          ownerName:      data.ownerName      || "",
          businessName:   data.businessName   || "",
          phone:          data.phone          || "",
          location:       data.location       || "",
          androidVersion: data.androidVersion || "",
          appVersion:     data.appVersion     || "",
          role
        });

        // Update admin_requests to reflect completion
        await requestDoc.ref.update({
          status:       "sheet_created",
          userSheetId:  result.sheetId,
          userSheetUrl: result.sheetUrl,
          processedAt:  admin.firestore.FieldValue.serverTimestamp()
        });

        // Update registry sheet rows with final sheetId + status
        await appendToRegistry(uid, data, result.sheetId, result.sheetUrl, "sheet_created");

        console.log(`onInvitedUserAdded — done uid=${uid}, sheetId=${result.sheetId} (existing=${result.existing})`);

      } catch (err) {
        console.error(`onInvitedUserAdded — sheet creation failed for uid=${uid}:`, err.message);
        try {
          await requestDoc.ref.update({
            status:      "creation_failed",
            errorMsg:    err.message,
            processedAt: admin.firestore.FieldValue.serverTimestamp()
          });
        } catch (_) { /* non-fatal */ }
      }
    }

    return null;
  });

// ── HTTP FUNCTION: syncUserSheet ──────────────────────────────────────────────
// All app ↔ sheet data transfer goes through here. The service account holds
// writer access to every user sheet; the user's Gmail is never granted access.
//
// Operations:
//   write_purchases  — upsert rows by TxnId (col A)
//   delete_purchases — delete rows by TxnId array
//   write_daily_stock — upsert rows by Date+ProductCode (cols A+B)
//   write_day_summary — upsert rows by Date (col A)
//   read_all          — return all rows from Purchases, DailyStock, DaySummary

exports.syncUserSheet = functions
  .region("asia-south1")
  .runWith({ serviceAccount: SHEETS_SA, timeoutSeconds: 120, memory: "512MB" })
  .https.onRequest(async (req, res) => {

    res.set("Access-Control-Allow-Origin", "*");
    if (req.method === "OPTIONS") {
      res.set("Access-Control-Allow-Methods", "POST");
      res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return res.status(204).send("");
    }
    if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) return res.status(401).json({ error: "Missing token" });

    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
    } catch (err) {
      return res.status(401).json({ error: "Invalid token" });
    }

    const uid = decodedToken.uid;
    const { operation, rows, txnIds } = req.body;

    // Resolve user's sheet from Firestore
    let spreadsheetId;
    try {
      const userDoc = await db.collection("users").doc(uid).get();
      if (!userDoc.exists || !userDoc.data().userSheetId) {
        return res.status(404).json({ error: "no_sheet", message: "User sheet not found. Complete registration first." });
      }
      spreadsheetId = userDoc.data().userSheetId;
    } catch (err) {
      return res.status(500).json({ error: "Firestore lookup failed: " + err.message });
    }

    // Service account auth — the SA has writer access to user sheets
    const auth   = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
    const sheets = google.sheets({ version: "v4", auth });

    console.log(`syncUserSheet [${operation}] — uid=${uid}, spreadsheetId=${spreadsheetId}`);

    const runOperation = async (id) => {
      switch (operation) {
        case "write_purchases":   return writePurchases(sheets, id, rows || []);
        case "delete_purchases":  return deletePurchases(sheets, id, txnIds || []);
        case "write_daily_stock": return writeDailyStock(sheets, id, rows || []);
        case "write_day_summary": return writeDaySummary(sheets, id, rows || []);
        case "read_all":          return readAll(sheets, id);
        case "clear_all":         return clearAll(sheets, id);
        default:                  return null;
      }
    };
    const sheetGone = err => err.code === 404 || /Requested entity was not found/i.test(err.message || "");

    try {
      let result;
      try {
        result = await runOperation(spreadsheetId);
      } catch (err) {
        if (!sheetGone(err)) throw err;
        // Linked sheet was deleted: re-link to the user's sheet found by title (either Drive
        // folder) and retry once. Never create a blank sheet here — rows the device already
        // marked SYNCED would not be re-sent, so a new sheet would silently miss history.
        const foundId = await findUserSheetByTitle(decodedToken.email, uid);
        if (!foundId || foundId === spreadsheetId) {
          console.error(`syncUserSheet [${operation}] — sheet ${spreadsheetId} gone, no replacement found — uid=${uid}`);
          return res.status(404).json({ error: "no_sheet", message: "Linked cloud sheet no longer exists." });
        }
        console.warn(`syncUserSheet — sheet ${spreadsheetId} gone; re-linking uid=${uid} to ${foundId}`);
        await relinkUserSheet(uid, foundId);
        result = await runOperation(foundId);
      }
      if (result === null) return res.status(400).json({ error: `Unknown operation: ${operation}` });
      return res.status(200).json(result);
    } catch (err) {
      console.error(`syncUserSheet [${operation}] failed — uid=${uid}:`, err.message);
      return res.status(500).json({ error: err.message });
    }
  });

// ── HTTP FUNCTION: deleteUserRegistration ─────────────────────────────────────
// Called during account deletion (before Firebase Auth account is removed).
// 1. Soft-deletes the user's rows in both UserRegistry and AppRequests registry tabs
//    — blanks PII, sets status = "account_deleted", records deletion timestamp.
// 2. Upserts account_deletions/{emailKey} in Firestore to track repeat registrations.
//    This collection is NOT part of the user's account and survives account deletion.
//
// Non-fatal: if registry soft-delete fails, account deletion continues on the client.

exports.deleteUserRegistration = functions
  .region("asia-south1")
  .runWith({ serviceAccount: SHEETS_SA, timeoutSeconds: 30, memory: "256MB" })
  .https.onRequest(async (req, res) => {

    res.set("Access-Control-Allow-Origin", "*");
    if (req.method === "OPTIONS") {
      res.set("Access-Control-Allow-Methods", "POST");
      res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return res.status(204).send("");
    }
    if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) return res.status(401).json({ error: "Missing token" });

    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
    } catch (err) {
      return res.status(401).json({ error: "Invalid token" });
    }

    const uid   = decodedToken.uid;
    const email = (req.body.email || "").trim().toLowerCase();

    try {
      // 1. Soft-delete registry rows — blanks PII, preserves audit fields
      await softDeleteRegistryRows(uid);

      // 2. Upsert account_deletions/{emailKey} — admin audit record, never deleted
      if (email) {
        const emailKey = email.replace(/@/g, "_at_").replace(/\./g, "_");
        await db.collection("account_deletions").doc(emailKey).set({
          email,
          deletionCount:  admin.firestore.FieldValue.increment(1),
          lastDeletedAt:  admin.firestore.FieldValue.serverTimestamp(),
          lastDeletedUid: uid
        }, { merge: true });
        console.log(`account_deletions upserted — email=${email}, uid=${uid}`);
      }

      console.log(`deleteUserRegistration complete — uid=${uid}`);
      return res.status(200).json({ success: true });
    } catch (err) {
      console.error("deleteUserRegistration failed:", err.message);
      return res.status(500).json({ error: err.message });
    }
  });

// ── HTTP FUNCTION: getMasterProducts ─────────────────────────────────────────
// Returns master products list + test data tabs from MASTER_SHEET_ID.
// Uses service account — master sheet no longer needs public sharing.
// Requires a valid Firebase ID token (any signed-in user).
//
// Response: { products: [[...], ...], testOb: [[...], ...], testCb: [[...], ...] }

exports.getMasterProducts = functions
  .region("asia-south1")
  .runWith({ serviceAccount: SHEETS_SA, timeoutSeconds: 30, memory: "256MB" })
  .https.onRequest(async (req, res) => {

    res.set("Access-Control-Allow-Origin", "*");
    if (req.method === "OPTIONS") {
      res.set("Access-Control-Allow-Methods", "POST");
      res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      return res.status(204).send("");
    }
    if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) return res.status(401).json({ error: "Missing token" });

    let masterProductsToken;
    try {
      masterProductsToken = await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
    } catch (err) {
      return res.status(401).json({ error: "Invalid token" });
    }

    // Gate: user must have completed registration (Step 1 of onboarding).
    const userDoc = await db.collection("users").doc(masterProductsToken.uid).get();
    if (!userDoc.exists) {
      console.log(`getMasterProducts — uid=${masterProductsToken.uid} not registered — blocked`);
      return res.status(403).json({ error: "not_registered", message: "Complete app registration first." });
    }

    const auth   = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/spreadsheets.readonly"] });
    const sheets = google.sheets({ version: "v4", auth });

    try {
      const [productsRes, testObRes, testCbRes] = await Promise.all([
        sheets.spreadsheets.values.get({
          spreadsheetId: MASTER_SHEET_ID,
          range: "Products!A2:U",
          valueRenderOption: "UNFORMATTED_VALUE"
        }),
        sheets.spreadsheets.values.get({
          spreadsheetId: MASTER_SHEET_ID,
          range: "TestOB!A2:G",
          valueRenderOption: "UNFORMATTED_VALUE"
        }).catch(() => ({ data: { values: null } })),
        sheets.spreadsheets.values.get({
          spreadsheetId: MASTER_SHEET_ID,
          range: "TestCB!A2:H",
          valueRenderOption: "UNFORMATTED_VALUE"
        }).catch(() => ({ data: { values: null } }))
      ]);

      return res.status(200).json({
        products: productsRes.data.values || [],
        testOb:   testObRes.data.values   || [],
        testCb:   testCbRes.data.values   || []
      });
    } catch (err) {
      console.error("getMasterProducts failed:", err.message);
      return res.status(500).json({ error: err.message });
    }
  });
