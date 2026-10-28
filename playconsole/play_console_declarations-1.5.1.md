# WinEntry 1.5.1 (versionCode 21) — Play Console checklist

Prepared 2026-10-28 for **internal testing**.

## Files

| File | Use |
|---|---|
| `release-builds/v21-vc21/app-release-vc21-1.5.1.aab` | Upload to Play (internal testing release) |
| `release-builds/v21-vc21/app-release-vc21-1.5.1.apk` | arm64-v8a APK for sideloading to testers |
| `release-notes/release-name-1.5.1.txt` | Release name |
| `release-notes/whatsnew-en-US-1.5.1.txt` | Release notes (en-US) |

Signed with the upload key (`SIMPLEIN`). versionCode 21 is used up as soon as the AAB is uploaded, even if the draft is later discarded.

## What changed in 1.5.1

- An "Explore other apps from Simhadri Systems" card on Home (bottom) and in Settings (above the version line). It shows eSudoku, Expense Tracker and Win eCalc, changes app every 20 seconds and skips apps already installed.
- The Home card has an X that hides it for 7 days, with a Snackbar and an Undo button. The Settings card has no X.
- Tapping a card opens that app's Play listing. Expense Tracker opens `https://simhadrisystems.in/expensetracker/` because it is still in closed testing.
- The card is built into the app: no ad SDK, no network call, no tracking, no new permission. The manifest `<queries>` names three packages (`com.simhadri.sudoku`, `com.simhadri.expensetracker`, `com.simhadri.calc`) for the on-device check of which apps are installed.

## Declarations (Policy → App content)

| Section | Answer | Change from 1.5.0? |
|---|---|---|
| **Ads** — "Does your app contain ads?" | **Yes, my app contains ads** | **CHANGED** (was No). Google counts promoting your own apps in an ad-style card as ads. The store listing will show "Contains ads". |
| Advertising ID — "Does your app use advertising ID?" | No | Unchanged. Checked: the vc21 release APK has no `com.google.android.gms.permission.AD_ID`. |
| Data safety | Unchanged | The card collects and shares nothing. The installed-app check runs on the device only. No new SDK. |
| Privacy policy URL | Same URL (https://sites.google.com/view/winentryapp/privacy-policy) | The content is updated to v2.4. Publish it on Google Sites **before** submitting (see below). |
| Content rating | Unchanged | The questionnaire's ads question doesn't affect the rating. Re-submit only if Play asks. |
| Target audience | Unchanged (18+) | — |
| App access, Financial features, Government apps, Health | Unchanged | — |

How it is described: WinEntry is a general inventory and business tool. Neither the card nor any of the promoted apps promotes alcohol. Keep the copy generic.

## Privacy policy v2.4 (`public/privacy.html`)

- New section **6a. Promotions for Our Other Apps**: no ad network or SDK, Advertising ID not accessed, no data collected when the card is shown, dismissed or tapped, business data never used to choose what is shown, the installed-app check stays on the device, taps open Play or the website, and the Home X hides the card for 7 days.
- Header: "Last updated: 28 October 2026 (v2.4)".
- New **Section 13. Version History** (Date | Version | Summary, newest first; same convention as eSudoku and eCalc). Contact is renumbered to Section 14. Every future amendment adds a row here.
- **Publish:** update the Google Sites page https://sites.google.com/view/winentryapp/privacy-policy from `public/privacy.html` by hand. Do this before submitting the release for review. The Play Console privacy URL stays the same.

## Upload steps (internal testing)

1. Update the Google Sites privacy page to v2.4 (above) and check that the live page shows sections 6a and 13.
2. Play Console → WinEntry → **Policy → App content → Ads** → change to **Yes** → Save.
3. **Testing → Internal testing → Create new release** → upload `app-release-vc21-1.5.1.aab`.
4. Release name: from `release-name-1.5.1.txt`. Release notes: paste `whatsnew-en-US-1.5.1.txt` under `<en-US>`.
5. Review the release → Start rollout to internal testing. If changes are pending, send them for review from **Publishing overview**; the Ads declaration change goes with them.
6. On a tester device: update from Play, sign in, then check the Home and Settings cards, the X and Undo, the EN/TE caption and dark mode.

## Pre-release testing done (2026-10-28, release build on the test phone)

- Excel import and export: working and stable (user tested).
- Card shows on Home and in Settings. Tapping X hides it; the original build gave no feedback on X, so the Snackbar and Undo were added and installed.
- Still to recheck: the card coming back after 7 days, plus the Snackbar and Undo. The X was tapped during testing, so the Home card is hidden on the test phone until about 2026-11-04.
