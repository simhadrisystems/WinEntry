package com.simple.simpleinventory.ui.util

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Reusable scroll navigation helper.
 *
 * Attaches two mini FABs to a RecyclerView:
 *   fabScrollTop    — top-end corner, visible when NOT at top
 *   fabScrollBottom — bottom-end corner, visible when NOT at bottom
 *
 * Usage in any Fragment:
 *
 *   ScrollNavigationHelper.setup(
 *       recyclerView   = binding.recyclerView,
 *       fabTop         = binding.fabScrollTop,
 *       fabBottom      = binding.fabScrollBottom,
 *       lifecycleOwner = viewLifecycleOwner,
 *       dataReady      = viewModel.someListLiveData   // show bottom FAB once data loads
 *   )
 *
 * The [dataReady] LiveData is optional — pass null if you want the bottom FAB
 * to appear immediately (e.g. when data is already loaded synchronously).
 */
object ScrollNavigationHelper {

    private const val DIM_ALPHA  = 0.40f
    private const val FULL_ALPHA = 1.00f
    private const val FADE_MS    = 200L

    fun setup(
        recyclerView:   RecyclerView,
        fabTop:         FloatingActionButton,
        fabBottom:      FloatingActionButton,
        lifecycleOwner: LifecycleOwner,
        dataReady:      LiveData<out List<*>>? = null
    ) {
        // ── 1. Raise bottom FAB above system navigation bar ───────────────────
        ViewCompat.setOnApplyWindowInsetsListener(fabBottom) { view, insets ->
            val navBarHeight = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            ).bottom
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.bottomMargin = navBarHeight + 8.dpToPx(view.context)
            view.layoutParams = params
            insets
        }

        // ── 2. Dim at rest, opaque on touch ───────────────────────────────────
        val touchFade = View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().alpha(FULL_ALPHA).setDuration(120).start()
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    view.animate().alpha(DIM_ALPHA).setDuration(300).start()
            }
            false   // let click listener still fire
        }
        fabTop.setOnTouchListener(touchFade)
        fabBottom.setOnTouchListener(touchFade)

        // ── 3. Show bottom FAB once data is ready ─────────────────────────────
        if (dataReady != null) {
            dataReady.observe(lifecycleOwner) { list ->
                if (!list.isNullOrEmpty()) showFab(fabBottom)
            }
        } else {
            showFab(fabBottom)
        }

        // ── 4. Scroll listener — update FAB visibility on every scroll ────────
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstVisibleItemPosition()
                val lastVisible  = lm.findLastVisibleItemPosition()
                val totalItems   = rv.adapter?.itemCount ?: 0
                val atTop        = firstVisible <= 2
                val atBottom     = lastVisible  >= totalItems - 1

                if (atTop) hideFab(fabTop) else showFab(fabTop)
                if (atBottom) hideFab(fabBottom) else showFab(fabBottom)
            }
        })

        // ── 5. Click actions ──────────────────────────────────────────────────
        fabTop.setOnClickListener {
            recyclerView.smoothScrollToPosition(0)
        }
        fabBottom.setOnClickListener {
            val lastPos = (recyclerView.adapter?.itemCount ?: 1) - 1
            recyclerView.smoothScrollToPosition(lastPos)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showFab(fab: FloatingActionButton) {
        if (fab.visibility != View.VISIBLE) {
            fab.alpha = 0f
            fab.visibility = View.VISIBLE
            fab.animate().alpha(DIM_ALPHA).setDuration(FADE_MS).start()
        }
    }

    private fun hideFab(fab: FloatingActionButton) {
        if (fab.visibility == View.VISIBLE) {
            fab.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                fab.visibility = View.GONE
            }.start()
        }
    }

    private fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}
