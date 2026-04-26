# Cloud Function Setup — WinEntry Sheet Creation

## Why OAuth2 (not Service Account) for Drive

Google Service Accounts have **zero Drive storage quota**. Any attempt to create
a Drive file owned by a service account fails with:
> "The user's Drive storage quota has been exceeded"

Solution: use an OAuth2 refresh token for the admin's personal Google account
(`tomsrao@gmail.com`). Files are owned by that account and count against its
quota. The service account is still used for Firebase Admin SDK (Firestore writes)
and for the UserRegistry sheet (which is shared with the SA as Editor).

---

## One-Time Setup (repeat if credentials are lost or rotated)

### Step 1 — Create an OAuth 2.0 Web Client in GCP Console

1. Open: https://console.cloud.google.com/apis/credentials
2. Select project **winentry-a87f2**
3. Click **+ CREATE CREDENTIALS → OAuth client ID**
4. Application type: **Web application**
5. Name: `WinEntry Functions` (any label)
6. Under **Authorized redirect URIs**, add exactly:
   ```
   https://developers.google.com/oauthplayground
   ```
7. Click **Create** — note the **Client ID** and **Client Secret**

> If multiple Web clients exist, pick one and use it exclusively throughout.
> The client ID looks like: `827909879534-xxxx.apps.googleusercontent.com`

---

### Step 2 — Generate a Refresh Token (OAuth2 Playground)

1. Open: https://developers.google.com/oauthplayground
2. Click the **gear icon** (top right) — do this BEFORE anything else
3. Check **"Use your own OAuth credentials"**
4. Enter the **Client ID** and **Client Secret** from Step 1
5. In the left panel, add these two scopes (paste each URL and press Enter/arrow):
   ```
   https://www.googleapis.com/auth/drive
   https://www.googleapis.com/auth/spreadsheets
   ```
   > Must be `drive` (full access), NOT `drive.file` or `drive.readonly`
6. Click **Authorize APIs** → sign in as **`tomsrao@gmail.com`**
7. Click **Exchange authorization code for tokens**
8. Copy the **Refresh token** value

---

### Step 3 — Store Credentials in Firebase Secret Manager

Run each command and paste the value when prompted (no quotes needed):

```bash
firebase functions:secrets:set ADMIN_OAUTH_CLIENT_ID
firebase functions:secrets:set ADMIN_OAUTH_CLIENT_SECRET
firebase functions:secrets:set ADMIN_OAUTH_REFRESH_TOKEN
```

> All three values must come from the **same OAuth client**.
> Mixing credentials from different clients causes `unauthorized_client` error.

When Firebase asks *"re-deploy the functions and destroy the stale version?"* — say **Yes**.

> **Why Secret Manager (not functions.config)?**
> `functions.config()` is deprecated and will stop working in March 2027.
> Secret Manager is the current Firebase-recommended approach for sensitive values.

---

### Step 4 — Deploy

If secrets were updated without auto-redeploy:

```bash
firebase deploy --only functions
```

---

## How It Works

```
User taps "Send Request" in app
        │
        ▼
CloudFunctionClient.createUserSheet()  ──► HTTP Cloud Function (createUserSheet)
        │                                          │
        │ success                                  │ calls createUserSheetForUser()
        ▼                                          │
sheetId saved to app prefs                         ▼
sync enabled immediately              drive.files.create() as tomsrao@gmail.com
                                               │
        │ CF fails (timeout/network)            ▼
        ▼                              Sheet created in SHARED_FOLDER_ID
admin_requests/{uid} written                   │
        │                                      ▼
        ▼                          /users/{uid} written to Firestore
onAdminRequestCreated trigger                  │
fires automatically                            ▼
        │                          User shared as Editor on the sheet
        ▼
createUserSheetForUser() called
(idempotent — skips if sheet exists)
```

---

## Key Constants (index.js)

| Constant | Value | Purpose |
|---|---|---|
| `SHARED_FOLDER_ID` | `1MZq03EyglI4JR3pr_5siStY7JC0RLKII` | Admin Drive folder where all user sheets are created |
| `ADMIN_EMAIL` | `tomsrao@gmail.com` | Admin account that owns all sheets |
| `REGISTRY_SHEET_ID` | `1L4PpNtS2AxfhP2XwnPYVD8ltUSPUJSUcAZbVm7yn5LM` | Admin UserRegistry tracking sheet |

---

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `unauthorized_client` | Refresh token generated with different client credentials than stored in secrets | Regenerate refresh token using the same Client ID/Secret that is stored in `ADMIN_OAUTH_CLIENT_ID` / `ADMIN_OAUTH_CLIENT_SECRET` |
| `Insufficient Permission` | Refresh token generated with wrong scope (`drive.file` instead of `drive`) | Regenerate refresh token selecting `https://www.googleapis.com/auth/drive` scope |
| `The caller does not have permission` | Old error from SA trying to create files without a parent folder | Fixed — no longer applicable |
| `Drive storage quota exceeded` | Old error from SA owning files (SA quota = 0) | Fixed — OAuth2 admin account used instead |
| `Admin OAuth credentials missing` | Secrets not set or function not redeployed after setting | Run Step 3 again, say Yes to redeploy |

---

## Updating Credentials Later

To rotate the refresh token (e.g. if it expires or is revoked):

1. Repeat Step 2 (OAuth Playground) using the same Client ID/Secret already in secrets
2. Run: `firebase functions:secrets:set ADMIN_OAUTH_REFRESH_TOKEN`
3. Say Yes to redeploy

To rotate the OAuth client entirely (e.g. client was deleted):

1. Repeat Steps 1, 2, and 3 fully
2. All three secrets must be updated together

---

## Dependencies (package.json)

```json
"firebase-functions": "^4.9.0",
"firebase-admin": "^12.x",
"googleapis": "^144.x"
```

The `defineSecret` import used in index.js:
```javascript
const { defineSecret } = require("firebase-functions/params");
```
