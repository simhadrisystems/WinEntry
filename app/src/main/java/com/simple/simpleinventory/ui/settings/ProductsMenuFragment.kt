package com.simple.simpleinventory.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.simple.simpleinventory.R
import com.simple.simpleinventory.databinding.FragmentProductsMenuBinding
import com.simple.simpleinventory.utils.AppStrings
import com.simple.simpleinventory.utils.LangPrefs

class ProductsMenuFragment : Fragment() {

    private var _binding: FragmentProductsMenuBinding? = null
    private val binding get() = _binding!!

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
    }

    override fun onResume() {
        super.onResume()
        applyLanguage()
    }

    private fun applyLanguage() {
        val lang = LangPrefs.get(requireContext())
        binding.toolbar.title               = AppStrings.productsMenuToolbarTitle.get(lang)
        binding.textSectionDisplay.text     = AppStrings.productsMenuSectionDisplay.get(lang)
        binding.textProductOrderTitle.text  = AppStrings.productsMenuOrderTitle.get(lang)
        binding.textProductOrderDesc.text   = AppStrings.productsMenuOrderDesc.get(lang)
        binding.textSectionStockSetup.text  = AppStrings.productsMenuSectionStock.get(lang)
        binding.textOpeningStockTitle.text  = AppStrings.productsMenuStockTitle.get(lang)
        binding.textOpeningStockDesc.text   = AppStrings.productsMenuStockDesc.get(lang)
        binding.textSectionMasterData.text  = AppStrings.productsMenuSectionMaster.get(lang)
        binding.textProductMasterTitle.text = AppStrings.productsMenuMasterTitle.get(lang)
        binding.textProductMasterDesc.text  = AppStrings.productsMenuMasterDesc.get(lang)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
