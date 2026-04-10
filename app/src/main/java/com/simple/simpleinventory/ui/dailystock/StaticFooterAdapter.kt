package com.simple.simpleinventory.ui.dailystock

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simple.simpleinventory.databinding.FooterDayReconciliationBinding

/**
 * Single-item adapter that inflates the reconciliation footer card exactly once
 * and never rebinds it. This is the key design principle:
 *
 *   - The footer scrolls in/out naturally as the last item in the ConcatAdapter
 *     (collapses with scroll down, re-emerges on scroll up — as designed).
 *   - No notifyItemChanged ever fires on it — no shaking, no flickering.
 *   - All LiveData observers wire directly to the inflated View via [onViewReady].
 *   - TextWatchers are set once in [onViewReady] and never re-attached.
 *
 * The Fragment sets [onViewReady] before the RecyclerView scrolls to the footer.
 * When the footer first scrolls into view, onCreateViewHolder fires, inflates
 * the layout, and immediately invokes [onViewReady] with the binding so the
 * Fragment can set up all observers and listeners.
 */
class StaticFooterAdapter(
    private val layoutInflater: LayoutInflater
) : RecyclerView.Adapter<StaticFooterAdapter.FooterViewHolder>() {

    /** Called once when the footer view is first inflated. */
    var onViewReady: ((FooterDayReconciliationBinding) -> Unit)? = null

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val binding = FooterDayReconciliationBinding.inflate(layoutInflater, parent, false)
        onViewReady?.invoke(binding)
        return FooterViewHolder(binding)
    }

    /** onBindViewHolder intentionally does nothing — all wiring is in onCreateViewHolder. */
    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) = Unit

    class FooterViewHolder(binding: FooterDayReconciliationBinding) :
        RecyclerView.ViewHolder(binding.root)
}
