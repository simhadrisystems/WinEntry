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
// Values are stored in Google Cloud Secret Manager (not functions.config).
// First deploy will prompt you to set these values if they don't exist yet.
// Or set them manually:
//   firebase functions:secrets:set ADMIN_OAUTH_CLIENT_ID
//   firebase functions:secrets:set ADMIN_OAUTH_CLIENT_SECRET
//   firebase functions:secrets:set ADMIN_OAUTH_REFRESH_TOKEN
const SECRET_CLIENT_ID     = defineSecret("ADMIN_OAUTH_CLIENT_ID");
const SECRET_CLIENT_SECRET = defineSecret("ADMIN_OAUTH_CLIENT_SECRET");
const SECRET_REFRESH_TOKEN = defineSecret("ADMIN_OAUTH_REFRESH_TOKEN");

// ── Admin OAuth2 client (for Drive + Sheets file-creation operations) ─────────
// Files created through this client are owned by the admin Google account and
// count against the admin's Drive quota — not the service account's (which is 0).

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
    "https://developers.google.com/oauthplayground"   // redirect URI used when generating the token
  );
  client.setCredentials({ refresh_token: refreshToken });
  return client;
}

// ── CONFIG ────────────────────────────────────────────────────────────────────

/** Drive folder where all user sheets live. Owned by tomsrao@gmail.com. */
const SHARED_FOLDER_ID = "1MZq03EyglI4JR3pr_5siStY7JC0RLKII";

/**
 * Admin email — shared on every new user sheet as Editor so admin always has
 * direct access without relying on folder move working.
 * TODO: update before release if admin email changes.
 */
const ADMIN_EMAIL = "tomsrao@gmail.com";

/** Master products sheet (simhadrisystems@gmail.com Drive, WinEntry app folder). */
const MASTER_SHEET_ID = "1rOd-l13Vs1LRa764lM40sjDeN70FWOArCn6NpfHUggs";

/** Admin user registry sheet (simhadrisystems@gmail.com Drive, WinEntry app folder). */
const REGISTRY_SHEET_ID = "1zw5Xek9lW4cohUbjaX6468ZqGF7m7--mdoplByv6Rps";
const REGISTRY_TAB      = "UserRegistry";

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

const REGISTRY_HEADERS = [
  "No", "Registered At", "Email", "Owner Name", "Business Name",
  "Phone", "Location", "UID", "Device", "Status", "Sheet Assigned", "Notes"
];

// ── SHARED HELPER: create user sheet + write Firestore ────────────────────────

/**
 * Creates a personal Google Sheet for the user, moves it to the shared admin
 * folder, shares it with the user, and writes /users/{uid} to Firestore.
 *
 * Idempotent: if /users/{uid} already has a userSheetId, returns it immediately.
 *
 * @param {string} uid
 * @param {string} email
 * @param {string} displayName
 * @returns {Promise<{sheetId: string, sheetUrl: string, existing: boolean}>}
 */
async function createUserSheetForUser(uid, email, displayName) {
  const safeDisplayName = displayName || email || "User";
  // Sheet name uses email for uniqueness — display names can collide across users
  const safeEmail = (email || uid).replace(/@/g, "_at_").replace(/\./g, "_");
  const sheetTitle = `WinEntry – ${safeEmail}`;

  // ── Idempotency check ───────────────────────────────────────────────────────
  const userDoc = await db.collection("users").doc(uid).get();
  if (userDoc.exists && userDoc.data().userSheetId) {
    const existingId = userDoc.data().userSheetId;
    console.log(`Sheet already exists for uid=${uid}: ${existingId}`);
    return {
      sheetId:  existingId,
      sheetUrl: `https://docs.google.com/spreadsheets/d/${existingId}`,
      existing: true
    };
  }

  // ── Admin OAuth2 client (Drive + Sheets) ───────────────────────────────────
  // Files are created as tomsrao@gmail.com, so storage counts against that
  // account's quota.  Service accounts have 0 Drive quota and cannot own files.
  const auth   = getAdminAuth();
  const sheets = google.sheets({ version: "v4", auth });
  const drive  = google.drive({ version: "v3", auth });

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
  // (No "move to folder" step needed — the file was created directly in SHARED_FOLDER_ID)
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

  // Note: no separate "share with admin" step needed — the file is owned by the
  // admin account (tomsrao@gmail.com) and already lives in their shared folder.

  // ── Write /users/{uid} to Firestore ────────────────────────────────────────
  await db.collection("users").doc(uid).set({
    uid,
    email:        email || "",
    displayName:  safeDisplayName,
    userSheetId:  spreadsheetId,
    userSheetUrl: spreadsheetUrl,
    role:         "editor",
    createdAt:    admin.firestore.FieldValue.serverTimestamp()
  });

  console.log(`User setup complete — uid=${uid}, sheetId=${spreadsheetId}`);
  return { sheetId: spreadsheetId, sheetUrl: spreadsheetUrl, existing: false };
}

// ── HELPER: append row to UserRegistry sheet ─────────────────────────────────

async function appendToRegistry(uid, data, sheetId, sheetUrl) {
  try {
    const auth   = new google.auth.GoogleAuth({ keyFile: SERVICE_ACCOUNT_KEY_PATH, scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
    const sheets = google.sheets({ version: "v4", auth });

    // Ensure UserRegistry tab exists
    const spreadsheet = await sheets.spreadsheets.get({ spreadsheetId: REGISTRY_SHEET_ID });
    const tabExists   = (spreadsheet.data.sheets || []).some(s => s.properties.title === REGISTRY_TAB);

    if (!tabExists) {
      await sheets.spreadsheets.batchUpdate({
        spreadsheetId: REGISTRY_SHEET_ID,
        requestBody: { requests: [{ addSheet: { properties: { title: REGISTRY_TAB, gridProperties: { frozenRowCount: 1 } } } }] }
      });
      await sheets.spreadsheets.values.update({
        spreadsheetId: REGISTRY_SHEET_ID,
        range: `${REGISTRY_TAB}!A1`,
        valueInputOption: "RAW",
        requestBody: { values: [REGISTRY_HEADERS] }
      });
      // Bold header
      const meta      = await sheets.spreadsheets.get({ spreadsheetId: REGISTRY_SHEET_ID });
      const tabSheet  = (meta.data.sheets || []).find(s => s.properties.title === REGISTRY_TAB);
      if (tabSheet) {
        await sheets.spreadsheets.batchUpdate({
          spreadsheetId: REGISTRY_SHEET_ID,
          requestBody: {
            requests: [{
              repeatCell: {
                range:  { sheetId: tabSheet.properties.sheetId, startRowIndex: 0, endRowIndex: 1 },
                cell:   { userEnteredFormat: { backgroundColor: { red: 0.12, green: 0.22, blue: 0.36 }, textFormat: { bold: true, foregroundColor: { red: 1, green: 1, blue: 1 }, fontSize: 10 } } },
                fields: "userEnteredFormat(backgroundColor,textFormat)"
              }
            }]
          }
        });
      }
    }

    // Row number
    const existing  = await sheets.spreadsheets.values.get({ spreadsheetId: REGISTRY_SHEET_ID, range: `${REGISTRY_TAB}!A:A` });
    const rowNo     = Math.max(1, (existing.data.values || []).length);

    // Timestamp
    let registeredAt = "";
    try {
      const ts = data.registeredAt;
      const dt = ts && ts.toDate ? ts.toDate() : new Date();
      registeredAt = dt.toLocaleString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: true });
    } catch (_) { registeredAt = new Date().toISOString(); }

    const row = [
      rowNo,
      registeredAt,
      data.email        || "",
      data.ownerName    || "",
      data.businessName || "",
      data.phone        || "",
      data.location     || "",
      uid,
      data.device       || "",
      "sheet_created",           // updated status
      sheetId  || "",            // K — Sheet Assigned
      sheetUrl || ""             // L — Sheet URL
    ];

    await sheets.spreadsheets.values.append({
      spreadsheetId: REGISTRY_SHEET_ID,
      range: `${REGISTRY_TAB}!A:A`,
      valueInputOption: "RAW",
      insertDataOption: "INSERT_ROWS",
      requestBody: { values: [row] }
    });
    console.log(`Registry row appended — uid=${uid}, rowNo=${rowNo}`);
  } catch (err) {
    console.error("Registry append failed:", err.message);
  }
}

// ── HTTP FUNCTION: createUserSheet ────────────────────────────────────────────
// Admin / manual fallback. Calls the same helper as the Firestore trigger.

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
    const { email, displayName } = req.body;

    try {
      const result = await createUserSheetForUser(uid, email, displayName);
      return res.status(200).json(result);
    } catch (err) {
      console.error("createUserSheet failed:", err.message);
      return res.status(500).json({ error: err.message });
    }
  });

// ── FIRESTORE TRIGGER: onAdminRequestCreated ──────────────────────────────────
// Fires on /admin_requests/{uid} create.
// 1. Creates user's personal sheet in admin's workspace (via shared helper)
// 2. Updates admin_requests/{uid} with result status
// 3. Appends row to admin UserRegistry sheet
//
// This trigger is the reliable fallback path: the app tries the HTTP function
// first (direct, instant response). If that fails, it writes admin_requests and
// this trigger picks up and creates the sheet server-side via Service Account.
// createUserSheetForUser() is idempotent — if HTTP CF already created the sheet
// and wrote /users/{uid}, this trigger detects it and skips creation safely.

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

    console.log(`onAdminRequestCreated — uid=${uid}, email=${data.email}`);

    let sheetId  = "";
    let sheetUrl = "";

    // ── 1. Create (or recover) the user's sheet in admin's workspace ──────
    try {
      const displayName = data.ownerName || data.displayName || data.email || "";
      const result = await createUserSheetForUser(uid, data.email, displayName);
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

// ── HTTP FUNCTION: getMasterProducts ─────────────────────────────────────────
// Returns the master products list and test data tabs from MASTER_SHEET_ID.
// All reads use the service account — the master sheet no longer needs to be
// shared publicly.  Requires a valid Firebase ID token (any signed-in user).
//
// Response: { products: [[...], ...], testOb: [[...], ...], testCb: [[...], ...] }
//   products — Products!A2:U  (UNFORMATTED_VALUE)
//   testOb   — TestOB!A2:G   (UNFORMATTED_VALUE)
//   testCb   — TestCB!A2:H   (UNFORMATTED_VALUE)
// testOb / testCb are empty arrays if the tab does not exist.

exports.getMasterProducts = functions
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

    try {
      await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
    } catch (err) {
      return res.status(401).json({ error: "Invalid token" });
    }

    const auth   = new google.auth.GoogleAuth({ keyFile: SERVICE_ACCOUNT_KEY_PATH, scopes: ["https://www.googleapis.com/auth/spreadsheets.readonly"] });
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
