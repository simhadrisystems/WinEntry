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
 *   UserRegistry sheet (Sheets API only, existing file): service-account.json keyFile.
 *   The registry sheet was shared with the SA as Editor so values.append works fine.
 */

const functions  = require("firebase-functions");
const { defineSecret } = require("firebase-functions/params");
const admin      = require("firebase-admin");
const { google } = require("googleapis");
const path       = require("path");

const SERVICE_ACCOUNT_KEY_PATH = path.join(__dirname, "service-account.json");

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

/** Drive folder where all user sheets live. Owned by tomsrao@gmail.com. */
const SHARED_FOLDER_ID = "1MZq03EyglI4JR3pr_5siStY7JC0RLKII";

const ADMIN_EMAIL = "tomsrao@gmail.com";

/** Admin user registry sheet. Share with App Engine SA as Editor. */
const REGISTRY_SHEET_ID = "1L4PpNtS2AxfhP2XwnPYVD8ltUSPUJSUcAZbVm7yn5LM";

// ── TAB HEADERS (must match CloudSyncManager.kt exactly) ─────────────────────

const PURCHASES_HEADERS = [
  "TxnId", "Date", "ProductCode", "ProductName", "InvoiceNo", "Supplier",
  "QQ_Boxes", "QQ_Loose", "QQ_Total", "QQ_Price", "QQ_Cost",
  "PP_Boxes", "PP_Loose", "PP_Total", "PP_Price", "PP_Cost",
  "NN_Boxes", "NN_Loose", "NN_Total", "NN_Price", "NN_Cost",
  "DD_Boxes", "DD_Loose", "DD_Total", "DD_Price", "DD_Cost",
  "TotalCost", "Notes"
];
const DAILYSTOCK_HEADERS = [
  "Date", "ProductCode",
  "Open_QQ", "Open_PP", "Open_NN", "Open_DD",
  "Close_QQ", "Close_PP", "Close_NN", "Close_DD",
  "Sale_QQ", "Sale_PP", "Sale_NN", "Sale_DD",
  "Price_QQ", "Price_PP", "Price_NN", "Price_DD",
  "Amt_QQ", "Amt_PP", "Amt_NN", "Amt_DD",
  "SaleAmount", "Committed"
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
  const safeEmail = (email || uid).replace(/@/g, "_at_").replace(/\./g, "_");
  const sheetTitle = `WinEntry \u2013 ${safeEmail}`;  // \u2013 = en-dash, avoids copy-paste encoding issues

  // ── Build Google API clients up front (needed for Drive search + creation) ─
  const auth   = getAdminAuth();
  const sheets = google.sheets({ version: "v4", auth });
  const drive  = google.drive({ version: "v3", auth });

  // ── Check 1: Drive search (PRIMARY gate — immune to Firestore being cleared) ─
  // Always search Drive first. Even if Firestore docs are wiped for testing,
  // an existing sheet in the shared folder is found and reused — no duplicates.
  try {
    const driveSearch = await drive.files.list({
      q: `name='${sheetTitle}' and '${SHARED_FOLDER_ID}' in parents and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false`,
      fields: "files(id)",
      spaces: "drive",
      pageSize: 1,
    });
    const found = driveSearch.data.files;
    if (found && found.length > 0) {
      const recoveredId  = found[0].id;
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
  if (userDoc.exists && userDoc.data().userSheetId) {
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

  // ── Share with user ─────────────────────────────────────────────────────────
  try {
    if (email) {
      await drive.permissions.create({
        fileId: spreadsheetId,
        requestBody: { type: "user", role: "writer", emailAddress: email },
        sendNotificationEmail: false
      });
    }
  } catch (err) {
    console.warn("Sharing with user failed (non-fatal):", err.message);
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
    const auth   = new google.auth.GoogleAuth({ keyFile: SERVICE_ACCOUNT_KEY_PATH, scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
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
      const urRow = [urRowNum, registeredAt, data.email || "", data.ownerName || "", data.businessName || "", data.phone || "", data.location || "", uid, versionInfo, status, sheetId || "", sheetUrl || ""];
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
      const arRow = [arRowNum, registeredAt, data.email || "", data.ownerName || "", data.businessName || "", displayName, data.location || "", data.phone || "", uid, versionInfo, status, sheetId || "", sheetUrl || "", processedAt, "editor", ""];
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

// ── HTTP FUNCTION: createUserSheet ────────────────────────────────────────────

exports.createUserSheet = functions
  .region("asia-south1")
  .runWith({
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
  .runWith({ timeoutSeconds: 30, memory: "256MB" })
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
