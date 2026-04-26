package com.simhadri.winentry.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FragmentProductsMenuBinding
import com.simhadri.winentry.sync.SyncHelper
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import com.simhadri.winentry.utils.UserRegistrationManager

class ProductsMenuFragment : Fragment() {

    private var _binding: FragmentProductsMenuBinding? = null
    private val binding get() = _binding!!

    private var registrationCheckActive = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductsMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.cardProductOrder.setOnClickListener {
            findNavController().navigate(R.id.action_productsMenu_to_productOrder)
        }

        binding.cardOpeningStock.setOnClickListener {
            findNavController().navigate(R.id.action_productsMenu_to_openingStock)
        }

        binding.cardProductMasterData.setOnClickListener {
            findNavController().navigate(R.id.action_productsMenu_to_productList)
        }

        binding.cardDownloadProducts.setOnClickListener {
            handleDownloadProducts()
        }
    }

    override fun onResume() {
        super.onResume()
        applyLanguage()
    }

    // ── Download Products card ────────────────────────────────────────────────

    private fun handleDownloadProducts() {
        if (registrationCheckActive) return
        registrationCheckActive = true

        UserRegistrationManager.ensureRegistered(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            onNotRegistered = {
                registrationCheckActive = false
                AppDialogs.confirm(
                    context     = requireContext(),
                    title       = "Registration Required",
                    message     = "App registration required to access Admin's drive space.\n\n" +
                        "Go to Business Info to register.",
                    actionLabel = "Go to Business Info"
                ) { findNavController().navigate(R.id.businessInfoFragment) }
            },
            onReady = {
                registrationCheckActive = false
                SyncHelper.syncProductsFromCloud(requireContext(), viewLifecycleOwner.lifecycleScope, binding.root)
            }
        )
    }

    private fun applyLanguage() {
        val lang = LangPrefs.get(requireContext())
        val te = lang == AppStrings.Lang.TE
        val titleSp = if (te) 19f else 17f
        val descSp  = if (te) 11f else 13f

        binding.toolbar.title                    = AppStrings.productsMenuToolbarTitle.get(lang)
        binding.textSectionDisplay.text          = AppStrings.productsMenuSectionDisplay.get(lang)
        binding.textProductOrderTitle.text       = AppStrings.productsMenuOrderTitle.get(lang)
        binding.textProductOrderDesc.text        = AppStrings.productsMenuOrderDesc.get(lang)
        binding.textSectionStockSetup.text       = AppStrings.productsMenuSectionStock.get(lang)
        binding.textOpeningStockTitle.text       = AppStrings.productsMenuStockTitle.get(lang)
        binding.textOpeningStockDesc.text        = AppStrings.productsMenuStockDesc.get(lang)
        binding.textSectionMasterData.text       = AppStrings.productsMenuSectionMaster.get(lang)
        binding.textProductMasterTitle.text      = AppStrings.productsMenuMasterTitle.get(lang)
        binding.textProductMasterDesc.text       = AppStrings.productsMenuMasterDesc.get(lang)
        binding.textSectionCloud.text            = AppStrings.productsMenuSectionCloud.get(lang)
        binding.textDownloadProductsTitle.text   = AppStrings.productsMenuDownloadTitle.get(lang)
        binding.textDownloadProductsDesc.text    = AppStrings.productsMenuDownloadDesc.get(lang)

        for (v in listOf(binding.textSectionDisplay, binding.textSectionStockSetup,
                         binding.textSectionMasterData, binding.textSectionCloud))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        for (v in listOf(binding.textProductOrderTitle, binding.textOpeningStockTitle,
                         binding.textProductMasterTitle, binding.textDownloadProductsTitle))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp)
        for (v in listOf(binding.textProductOrderDesc, binding.textOpeningStockDesc,
                         binding.textProductMasterDesc, binding.textDownloadProductsDesc))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, descSp)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
