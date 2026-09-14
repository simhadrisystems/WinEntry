package com.simhadri.winentry.ui.quicksale

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simhadri.winentry.databinding.FooterQuickSaleReconciliationBinding

/**
 * Single-item adapter that inflates the reconciliation footer card exactly once and
 * never rebinds it — mirrors DailyStock's StaticFooterAdapter pattern so the footer
 * scrolls in/out naturally as the last item and never flickers on data changes.
 * All LiveData observers wire directly to the inflated view via [onViewReady].
 */
class QuickSaleFooterAdapter(
    private val layoutInflater: LayoutInflater
) : RecyclerView.Adapter<QuickSaleFooterAdapter.FooterViewHolder>() {

    var onViewReady: ((FooterQuickSaleReconciliationBinding) -> Unit)? = null

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val binding = FooterQuickSaleReconciliationBinding.inflate(layoutInflater, parent, false)
        onViewReady?.invoke(binding)
        return FooterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) = Unit

    class FooterViewHolder(binding: FooterQuickSaleReconciliationBinding) :
        RecyclerView.ViewHolder(binding.root)
}
