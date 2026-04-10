package com.simple.simpleinventory.data.entity

/**
 * Unified sync status constants used by all three syncable tables:
 * purchases, daily_stock, and day_reconciliation.
 *
 * Each table stores syncStatus as a TEXT column. The valid values differ
 * slightly per table — see the table notes below — but SYNCED and
 * SYNC_ERROR are shared by all three.
 *
 * ── purchases ─────────────────────────────────────────────────────────────
 *   PENDING_INSERT  new row, not yet in Sheets
 *   PENDING_UPDATE  row changed, Sheets copy needs refresh
 *   PENDING_DELETE  row soft-deleted, Sheets row must be removed
 *   SYNCED          successfully mirrored
 *   SYNC_ERROR      last attempt failed, will retry
 *
 * ── daily_stock / day_reconciliation ──────────────────────────────────────
 *   PENDING_UPSERT  committed row not yet in Sheets (insert or overwrite)
 *   SYNCED          successfully mirrored
 *   SYNC_ERROR      last attempt failed, will retry
 */
object SyncStatus {

    // ── Purchase-specific ─────────────────────────────────────────────────────
    const val PENDING_INSERT = "PENDING_INSERT"
    const val PENDING_UPDATE = "PENDING_UPDATE"
    const val PENDING_DELETE = "PENDING_DELETE"

    // ── DailyStock / DayReconciliation ────────────────────────────────────────
    const val PENDING_UPSERT = "PENDING_UPSERT"

    // ── Shared ────────────────────────────────────────────────────────────────
    const val SYNCED      = "SYNCED"
    const val SYNC_ERROR  = "SYNC_ERROR"
}
