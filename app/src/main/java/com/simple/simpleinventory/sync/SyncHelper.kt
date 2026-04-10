package com.simple.simpleinventory.sync

import android.content.Context
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simple.simpleinventory.data.entity.Product
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin facade over [SyncCoordinator] for use in Fragments.
 *
 *  1. [syncTransactions]          — push pending purchases, daily stock, day summary
 *  2. [syncProductsFromCloud]     — download/refresh product master data
 *  3. [downloadPurchasesFromCloud] — pull new purchases from PurchaseImport sheet tab
 *     • Reads sheet → classifies rows as new vs duplicate
 *     • If duplicates found: shows dialog asking Skip (default) or Replace
 *     • Commits inserts/replacements after user confirms
 *     • Sheet is NEVER modified — permanent record, dedup on every run
 */
object SyncHelper {

    // ── Transaction sync (daily sync icon) ───────────────────────────────────

    fun syncTransactions(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View
    ) {
        scope.launch {
            val coordinator = SyncCoordinator(context)
            snack(anchorView, "⏫ Syncing transactions…", Snackbar.LENGTH_SHORT)
            when (val result = coordinator.performFullSync()) {
                is SyncCoordinator.SyncResult.Success ->
                    snack(anchorView,
                        "✓ Synced — ${result.purchasesCount} purchase(s), " +
                        "${result.stockCount} stock row(s)",
                        Snackbar.LENGTH_LONG)
                is SyncCoordinator.SyncResult.Error ->
                    snack(anchorView, "✗ Sync failed: ${result.message}", Snackbar.LENGTH_LONG)
                else -> {}
            }
        }
    }

    // ── Product master sync (Products menu) ──────────────────────────────────

    fun syncProductsFromCloud(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View
    ) {
        showProductSyncModeDialog(context, scope, anchorView)
    }

    private fun showProductSyncModeDialog(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View
    ) {
        // Build a custom view with two styled menu-item rows
        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 4.dp(), 0, 4.dp())
        }

        fun menuRow(
            title: String,
            subtitle: String,
            iconRes: Int
        ): android.widget.LinearLayout {
            return android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(24.dp(), 14.dp(), 24.dp(), 14.dp())
                isClickable = true
                isFocusable = true
                with(android.util.TypedValue()) {
                    context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, this, true)
                    setBackgroundResource(resourceId)
                }
                // Icon
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    android.widget.LinearLayout.LayoutParams(24.dp(), 24.dp()).also {
                        it.gravity = android.view.Gravity.TOP
                        it.marginEnd = 16.dp()
                        layoutParams = it
                    }
                })
                // Text column
                addView(android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                        layoutParams = it
                    }
                    addView(android.widget.TextView(context).apply {
                        text = title
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#212121"))
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    addView(android.widget.TextView(context).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(android.graphics.Color.parseColor("#757575"))
                        setPadding(0, 2.dp(), 0, 0)
                    })
                })
            }
        }

        val rowUpdate = menuRow(
            title    = "Update from Master",
            subtitle = "Add new products · Keep your Active status & Sort Key",
            iconRes  = android.R.drawable.ic_menu_rotate
        )
        val divider = android.view.View(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).also { layoutParams = it }
        }
        val rowReset = menuRow(
            title    = "Full Reset from Master",
            subtitle = "Overwrite ALL settings — Active status & Sort Key will revert",
            iconRes  = android.R.drawable.ic_menu_revert
        )

        container.addView(rowUpdate)
        container.addView(divider)
        container.addView(rowReset)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Update Products from Cloud")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        rowUpdate.setOnClickListener {
            dialog.dismiss()
            doProductSync(context, scope, anchorView, preserveUserSettings = true)
        }
        rowReset.setOnClickListener {
            dialog.dismiss()
            showFullResetConfirmation(context, scope, anchorView)
        }

        dialog.show()
    }

    private fun showFullResetConfirmation(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Confirm Full Reset")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(
                "This will overwrite Active status and Sort Key for ALL products " +
                "with master data values.\n\n" +
                "Your customisations will be lost. This cannot be undone."
            )
            .setPositiveButton("Reset All Products") { _, _ ->
                doProductSync(context, scope, anchorView, preserveUserSettings = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doProductSync(
        context:              Context,
        scope:                CoroutineScope,
        anchorView:           View,
        preserveUserSettings: Boolean
    ) {
        scope.launch {
            val coordinator = SyncCoordinator(context)
            snack(anchorView, "Downloading products from master sheet…", Snackbar.LENGTH_SHORT)
            when (val result = coordinator.syncProductsOnly(preserveUserSettings)) {
                is SyncCoordinator.SyncResult.Success -> {
                    val msg = if (preserveUserSettings)
                        "✓ ${result.productsCount} products updated · Active & Sort Key settings preserved"
                    else
                        "✓ ${result.productsCount} products reset from master data"
                    snack(anchorView, msg, Snackbar.LENGTH_LONG)
                }
                is SyncCoordinator.SyncResult.Error ->
                    snack(anchorView, "Product sync failed: ${result.message}", Snackbar.LENGTH_LONG)
                else -> {}
            }
        }
    }

    // ── Purchase down-sync (Purchases menu) ──────────────────────────────────

    /**
     * Pull purchases from the PurchaseImport sheet tab.
     *
     * Flow:
     *   1. Read the sheet and classify rows as new vs duplicate
     *      (dedup key: invoiceNumber | productCode | purchaseDate).
     *   2. If duplicates exist, show a dialog:
     *         "Skip duplicates (recommended)"  — default, safe
     *         "Replace with sheet values"       — overwrites existing rows
     *         "Cancel"                          — abort entirely
     *   3. Insert new rows; insert/replace duplicates per user choice.
     *   4. Show result Snackbar with counts and any not-found product codes.
     *
     * The PurchaseImport sheet is never modified. The same sheet can grow
     * indefinitely — already-imported rows are silently skipped on every run.
     *
     * @param activity  Required to show the duplicate-confirmation dialog on the UI thread.
     * @param onRefresh Called after a successful commit so the purchases list reloads.
     */
    fun downloadPurchasesFromCloud(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View,
        products:   List<Product>,
        activity:   android.app.Activity,
        onRefresh:  () -> Unit = {}
    ) {
        scope.launch {
            val coordinator = SyncCoordinator(context)
            snack(anchorView, "⬇ Reading PurchaseImport sheet…", Snackbar.LENGTH_SHORT)

            val preview = coordinator.previewPurchaseDownSync(products)

            when (preview) {

                is SyncCoordinator.SyncResult.PurchaseDownSyncPreview -> {

                    // Nothing found at all
                    if (preview.newRows.isEmpty() && preview.dupRows.isEmpty()) {
                        snack(anchorView, buildString {
                            append("✓ No new purchases found in PurchaseImport sheet")
                            if (preview.notFoundCodes.isNotEmpty())
                                append("\n⚠ Not found: ${preview.notFoundCodes.joinToString()}")
                        }, Snackbar.LENGTH_LONG)
                        return@launch
                    }

                    // Show import summary dialog (duplicates + not-found)
                    // Always shown when there is something to report.
                    val hasDups      = preview.dupRows.isNotEmpty()
                    val hasNotFound  = preview.notFoundCodes.isNotEmpty()

                    val toReplace: List<com.simple.simpleinventory.data.entity.Purchase> =
                        if (hasDups || hasNotFound) {
                            suspendCancellableCoroutine { cont ->
                                activity.runOnUiThread {
                                    val mono = android.graphics.Typeface.MONOSPACE

                                    val msg = buildString {
                                        // ── Summary line ──────────────────────────────
                                        append("NEW: ${preview.newRows.size}  ")
                                        append("DUPLICATE: ${preview.dupRows.size}  ")
                                        append("NOT FOUND: ${preview.notFoundCodes.size}")
                                        append("\n")

                                        // ── Duplicates table ──────────────────────────
                                        if (hasDups) {
                                            append("\n─── DUPLICATES (already imported) ───\n")
                                            append("Row  Code       Size  Invoice\n")
                                            // Group by invoice+date for clear reading
                                            val grouped = preview.dupRows.groupBy {
                                                "${it.invoiceNumber}|${it.purchaseDate}"
                                            }
                                            var rowNum = 1
                                            grouped.forEach { (_, rows) ->
                                                rows.forEach { p ->
                                                    val sizes = buildString {
                                                        if (p.qqTotalUnits > 0) append("QQ ")
                                                        if (p.ppTotalUnits > 0) append("PP ")
                                                        if (p.nnTotalUnits > 0) append("NN ")
                                                        if (p.ddTotalUnits > 0) append("DD ")
                                                    }.trim()
                                                    append("${rowNum.toString().padEnd(4)} ")
                                                    append(p.productCode.take(10).padEnd(10))
                                                    append(sizes.padEnd(5))
                                                    append(" ${p.invoiceNumber} ${p.purchaseDate}\n")
                                                    rowNum++
                                                }
                                            }
                                        }

                                        // ── Not-found codes ───────────────────────────
                                        if (hasNotFound) {
                                            append("\n─── NOT FOUND (check product codes) ───\n")
                                            preview.notFoundCodes.forEachIndexed { i, code ->
                                                append("${(i + 1).toString().padEnd(4)} $code\n")
                                            }
                                        }

                                        if (hasDups) {
                                            append("\nWhat should happen to the duplicates?")
                                        }
                                    }

                                    val scrollView = android.widget.ScrollView(activity).apply {
                                        setPadding(40, 16, 40, 8)
                                    }
                                    val textView = android.widget.TextView(activity).apply {
                                        text = msg
                                        textSize = 12f
                                        typeface = mono
                                        setTextColor(android.graphics.Color.parseColor("#212121"))
                                    }
                                    scrollView.addView(textView)

                                    val builder = MaterialAlertDialogBuilder(activity)
                                        .setTitle("Import summary")
                                        .setView(scrollView)
                                        .setNegativeButton("Cancel") { _, _ ->
                                            cont.cancel()
                                        }
                                        .setCancelable(false)

                                    if (hasDups) {
                                        builder
                                            .setPositiveButton("Skip duplicates") { _, _ ->
                                                cont.resumeWith(Result.success(emptyList()))
                                            }
                                            .setNeutralButton("Replace duplicates") { _, _ ->
                                                cont.resumeWith(Result.success(preview.dupRows))
                                            }
                                    } else {
                                        // Only not-found — just OK/Cancel
                                        builder.setPositiveButton("Import anyway") { _, _ ->
                                            cont.resumeWith(Result.success(emptyList()))
                                        }
                                    }
                                    builder.show()
                                }
                            }
                        } else {
                            emptyList()
                        }

                    // Commit
                    val total = preview.newRows.size + toReplace.size
                    snack(anchorView, "⬇ Saving $total purchase(s)…", Snackbar.LENGTH_SHORT)

                    when (val result = coordinator.commitPurchaseDownSync(
                        preview.newRows, toReplace)) {

                        is SyncCoordinator.SyncResult.PurchaseDownSync -> {
                            snack(anchorView, buildString {
                                if (result.inserted > 0)
                                    append("✓ ${result.inserted} purchase(s) imported")
                                if (result.replaced > 0)
                                    append("  •  ${result.replaced} replaced")
                                val skipped = preview.dupRows.size - toReplace.size
                                if (skipped > 0)
                                    append("  •  $skipped duplicate(s) skipped")
                                if (preview.notFoundCodes.isNotEmpty())
                                    append("\n⚠ Not found: ${preview.notFoundCodes.joinToString()}")
                            }.ifBlank { "✓ Import complete" }, Snackbar.LENGTH_LONG)
                            onRefresh()
                        }

                        is SyncCoordinator.SyncResult.Error ->
                            snack(anchorView, "✗ Commit failed: ${result.message}", Snackbar.LENGTH_LONG)

                        else -> {}
                    }
                }

                is SyncCoordinator.SyncResult.Error ->
                    snack(anchorView, "✗ ${preview.message}", Snackbar.LENGTH_LONG)

                else -> snack(anchorView, "✗ Unexpected response from server", Snackbar.LENGTH_LONG)
            }
        }
    }

    private fun snack(view: View, msg: String, duration: Int) =
        Snackbar.make(view, msg, duration).show()

    // ── Daily stock down-sync (cloud → local DB) ──────────────────────────────

    /**
     * Pull all committed DailyStock rows from the cloud sheet and upsert them
     * into the local DB. Used for device restore or data recovery.
     *
     * Shows a confirmation dialog before overwriting local data.
     * After import, caller should refresh the Daily Stock screen.
     */
    /**
     * Full restore — downloads ALL committed rows from cloud, no date filter.
     * Shows a confirmation dialog before proceeding.
     */
    fun downloadDailyStockFromCloud(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View,
        onRefresh:  () -> Unit = {}
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Restore Daily Stock from Cloud")
            .setMessage(
                "This will download all daily stock records from Google Sheets " +
                "and merge them into the local database.\n\n" +
                "Existing local rows for the same date and product will be overwritten."
            )
            .setPositiveButton("Download All") { _, _ ->
                scope.launch {
                    snack(anchorView, "Downloading daily stock from cloud…", Snackbar.LENGTH_SHORT)
                    val result = SyncCoordinator(context).downloadDailyStockFromCloud()
                    when (result) {
                        is SyncCoordinator.SyncResult.DailyStockDownSync ->
                            snack(anchorView,
                                "✓ Restored ${result.count} daily stock record(s)",
                                Snackbar.LENGTH_LONG).also { onRefresh() }
                        is SyncCoordinator.SyncResult.Error ->
                            snack(anchorView, "✗ Restore failed: ${result.message}", Snackbar.LENGTH_LONG)
                        else -> {}
                    }
                }
            }
            .setNeutralButton("By Date Range…") { _, _ ->
                showDateRangePicker(context, scope, anchorView, onRefresh)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Date-range restore — lets user pick a start and end date, then downloads
     * only rows within that range. Existing local rows for those dates are overwritten.
     */
    private fun showDateRangePicker(
        context:    Context,
        scope:      CoroutineScope,
        anchorView: View,
        onRefresh:  () -> Unit
    ) {
        val sdf     = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val dispFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
        val today   = java.util.Calendar.getInstance()

        // Start with current month as default range
        val startCal = (today.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val endCal = today.clone() as java.util.Calendar

        // Show start-date picker, then end-date picker, then confirm
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                startCal.set(y, m, d)
                android.app.DatePickerDialog(
                    context,
                    { _, ey, em, ed ->
                        endCal.set(ey, em, ed)
                        val from    = sdf.format(startCal.time)
                        val to      = sdf.format(endCal.time)
                        val dispFrom = dispFmt.format(startCal.time)
                        val dispTo   = dispFmt.format(endCal.time)
                        MaterialAlertDialogBuilder(context)
                            .setTitle("Restore $dispFrom – $dispTo")
                            .setMessage(
                                "Download daily stock from cloud for $dispFrom to $dispTo.\n\n" +
                                "Existing local records for these dates will be overwritten."
                            )
                            .setPositiveButton("Download") { _, _ ->
                                scope.launch {
                                    snack(anchorView,
                                        "Downloading $dispFrom – $dispTo…",
                                        Snackbar.LENGTH_SHORT)
                                    val result = SyncCoordinator(context)
                                        .downloadDailyStockFromCloud(from, to)
                                    when (result) {
                                        is SyncCoordinator.SyncResult.DailyStockDownSync ->
                                            snack(anchorView,
                                                "✓ Restored ${result.count} record(s) for $dispFrom – $dispTo",
                                                Snackbar.LENGTH_LONG).also { onRefresh() }
                                        is SyncCoordinator.SyncResult.Error ->
                                            snack(anchorView,
                                                "✗ Restore failed: ${result.message}",
                                                Snackbar.LENGTH_LONG)
                                        else -> {}
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    },
                    endCal.get(java.util.Calendar.YEAR),
                    endCal.get(java.util.Calendar.MONTH),
                    endCal.get(java.util.Calendar.DAY_OF_MONTH)
                ).apply {
                    setTitle("Select end date")
                    // End date must be >= start date
                    datePicker.minDate = startCal.timeInMillis
                }.show()
            },
            startCal.get(java.util.Calendar.YEAR),
            startCal.get(java.util.Calendar.MONTH),
            startCal.get(java.util.Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("Select start date")
            // Can't pick future dates
            datePicker.maxDate = today.timeInMillis
        }.show()
    }
}
