package com.simhadri.winentry.ui.purchases

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.util.ProductCodeResolver
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.repository.PurchaseRepository
import com.simhadri.winentry.helpers.PurchaseExcelHelper
import kotlinx.coroutines.launch
import com.simhadri.winentry.ui.auth.ErrorLogger
import java.text.SimpleDateFormat
import java.util.*

class PurchaseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PurchaseRepository
    private val productDao = AppDatabase.getInstance(application).productDao()
    private val dailyStockDao = AppDatabase.getInstance(application).dailyStockDao()

    val allPurchases: LiveData<List<Purchase>>
    val allProducts: LiveData<List<Product>>

    private val _selectedProduct = MutableLiveData<Product?>()
    val selectedProduct: LiveData<Product?> = _selectedProduct

    private val _saveStatus = MutableLiveData<SaveStatus?>()
    val saveStatus: LiveData<SaveStatus?> = _saveStatus

    // Current purchase being edited
    private val _currentPurchase = MutableLiveData<PurchaseCalculation>()
    val currentPurchase: LiveData<PurchaseCalculation> = _currentPurchase

    init {
        val database = AppDatabase.getInstance(application)
        repository = PurchaseRepository(database.purchaseDao())
        allPurchases = repository.allPurchases
        allProducts = productDao.getAllProducts()  // all products — inactive still purchasable

        // Initialize with today's date
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _currentPurchase.value = PurchaseCalculation(purchaseDate = today)
    }

    /**
     * Set the selected product and initialize purchase with product defaults
     */
    fun selectProduct(product: Product) {
        _selectedProduct.value = product
        
        // CRITICAL: Preserve date, invoice and supplier when selecting a new product
        val prev = _currentPurchase.value
        val currentDate = prev?.purchaseDate
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        _currentPurchase.value = PurchaseCalculation(
            productId = product.id,
            productCode = product.stockCode,
            productName = product.displayName,
            purchaseDate = currentDate,
            invoiceNumber = prev?.invoiceNumber ?: "",
            supplierName  = prev?.supplierName  ?: "",
            // Use actual product values - DO NOT replace zeros
            qqUnitsPerBox = product.qqUnitsPerBox,
            ppUnitsPerBox = product.ppUnitsPerBox,
            nnUnitsPerBox = product.nnUnitsPerBox,
            ddUnitsPerBox = product.ddUnitsPerBox,
            qqUnitPrice = product.qqPurchasePrice,
            ppUnitPrice = product.ppPurchasePrice,
            nnUnitPrice = product.nnPurchasePrice,
            ddUnitPrice = product.ddPurchasePrice
        )
        
        android.util.Log.d("PurchaseViewModel", "selectProduct: Date preserved as $currentDate")
    }

    /**
     * Update QQ size quantities
     */
    fun updateQQ(boxes: Int, loose: Int, unitPrice: Double, unitsPerBox: Int) {
        val current = _currentPurchase.value ?: return
        val totalUnits = Purchase.calculateTotalUnits(boxes, loose, unitsPerBox)
        val totalCost = Purchase.calculateTotalCost(totalUnits, unitPrice)

        _currentPurchase.value = current.copy(
            qqBoxes = boxes,
            qqLoose = loose,
            qqUnitsPerBox = unitsPerBox,  // Update with passed value
            qqUnitPrice = unitPrice,
            qqTotalUnits = totalUnits,
            qqTotalCost = totalCost
        ).recalculateGrandTotal()
    }

    /**
     * Update PP size quantities
     */
    fun updatePP(boxes: Int, loose: Int, unitPrice: Double, unitsPerBox: Int) {
        val current = _currentPurchase.value ?: return
        val totalUnits = Purchase.calculateTotalUnits(boxes, loose, unitsPerBox)
        val totalCost = Purchase.calculateTotalCost(totalUnits, unitPrice)

        _currentPurchase.value = current.copy(
            ppBoxes = boxes,
            ppLoose = loose,
            ppUnitsPerBox = unitsPerBox,  // Update with passed value
            ppUnitPrice = unitPrice,
            ppTotalUnits = totalUnits,
            ppTotalCost = totalCost
        ).recalculateGrandTotal()
    }

    /**
     * Update NN size quantities
     */
    fun updateNN(boxes: Int, loose: Int, unitPrice: Double, unitsPerBox: Int) {
        val current = _currentPurchase.value ?: return
        val totalUnits = Purchase.calculateTotalUnits(boxes, loose, unitsPerBox)
        val totalCost = Purchase.calculateTotalCost(totalUnits, unitPrice)

        _currentPurchase.value = current.copy(
            nnBoxes = boxes,
            nnLoose = loose,
            nnUnitsPerBox = unitsPerBox,  // Update with passed value
            nnUnitPrice = unitPrice,
            nnTotalUnits = totalUnits,
            nnTotalCost = totalCost
        ).recalculateGrandTotal()
    }

    /**
     * Update DD size quantities
     */
    fun updateDD(boxes: Int, loose: Int, unitPrice: Double, unitsPerBox: Int) {
        val current = _currentPurchase.value ?: return
        val totalUnits = Purchase.calculateTotalUnits(boxes, loose, unitsPerBox)
        val totalCost = Purchase.calculateTotalCost(totalUnits, unitPrice)

        _currentPurchase.value = current.copy(
            ddBoxes = boxes,
            ddLoose = loose,
            ddUnitsPerBox = unitsPerBox,  // Update with passed value
            ddUnitPrice = unitPrice,
            ddTotalUnits = totalUnits,
            ddTotalCost = totalCost
        ).recalculateGrandTotal()
    }

    /**
     * Update purchase metadata
     */
    fun updateMetadata(date: String, supplier: String, invoice: String, notes: String) {
        val current = _currentPurchase.value ?: return
        _currentPurchase.value = current.copy(
            purchaseDate = date,
            supplierName = supplier,
            invoiceNumber = invoice,
            notes = notes
        )
    }

    fun updateReceivedDate(date: String) {
        val current = _currentPurchase.value ?: return
        _currentPurchase.value = current.copy(receivedDate = date)
    }

    /** Update receivedDate for every purchase row in an invoice group (list screen). */
    fun updateInvoiceReceivedDate(invoiceNumber: String, purchaseDate: String, receivedDate: String) {
        viewModelScope.launch {
            repository.updateReceivedDateForInvoice(invoiceNumber, purchaseDate, receivedDate)
        }
    }

    /**
     * The active opening-stock baseline date, if any (see OpeningStockFragment).
     * New purchases must be dated on/after this date — used both to bound the
     * invoice-date picker and as the authoritative save-time check below.
     */
    suspend fun getActiveOpeningStockDate(): String? = dailyStockDao.getLatestOpeningStockDate()

    /**
     * Save purchase to database
     */
    /**
     * Called from Insert Here mode — forces invoice and date before saving,
     * overriding whatever the ViewModel currently holds for those fields.
     */
    fun savePurchase(forcedInvoice: String, forcedDate: String) {
        val current = _currentPurchase.value ?: return
        _currentPurchase.value = current.copy(
            invoiceNumber = forcedInvoice,
            purchaseDate  = forcedDate
        )
        savePurchase()
    }

    private fun formatDateForMessage(date: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed!!)
    } catch (_: Exception) { date }

    fun savePurchase() {
        viewModelScope.launch {
            try {
                _saveStatus.value = SaveStatus.Saving

                val calc = _currentPurchase.value
                if (calc == null || calc.productId == 0L) {
                    _saveStatus.value = SaveStatus.Error("Please select a product")
                    return@launch
                }

                if (calc.purchaseDate.isEmpty()) {
                    _saveStatus.value = SaveStatus.Error("Please select a date")
                    return@launch
                }

                // Purchases can only be added on/after the active opening-stock baseline —
                // anything earlier belongs to a superseded trading period.
                val minDate = dailyStockDao.getLatestOpeningStockDate()
                if (minDate != null && calc.purchaseDate < minDate) {
                    _saveStatus.value = SaveStatus.Error(
                        "Cannot add a purchase dated before the current opening stock date " +
                        "(${formatDateForMessage(minDate)}). Purchases must be on or after that date."
                    )
                    return@launch
                }

                // Check if at least one size has quantities
                if (calc.qqTotalUnits == 0 && calc.ppTotalUnits == 0 &&
                    calc.nnTotalUnits == 0 && calc.ddTotalUnits == 0) {
                    _saveStatus.value = SaveStatus.Error("Please enter quantities for at least one size")
                    return@launch
                }

                // VALIDATION: Check loose units are less than units per box
                if (calc.qqLoose >= calc.qqUnitsPerBox && calc.qqUnitsPerBox > 0 && calc.qqLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("QQ loose (${calc.qqLoose}) must be less than units/box (${calc.qqUnitsPerBox})")
                    return@launch
                }
                if (calc.ppLoose >= calc.ppUnitsPerBox && calc.ppUnitsPerBox > 0 && calc.ppLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("PP loose (${calc.ppLoose}) must be less than units/box (${calc.ppUnitsPerBox})")
                    return@launch
                }
                if (calc.nnLoose >= calc.nnUnitsPerBox && calc.nnUnitsPerBox > 0 && calc.nnLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("NN loose (${calc.nnLoose}) must be less than units/box (${calc.nnUnitsPerBox})")
                    return@launch
                }
                if (calc.ddLoose >= calc.ddUnitsPerBox && calc.ddUnitsPerBox > 0 && calc.ddLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("DD loose (${calc.ddLoose}) must be less than units/box (${calc.ddUnitsPerBox})")
                    return@launch
                }

                // ── Duplicate check: same product + same date + same invoice → block ──
                // Uses getPurchasesByInvoiceAndDate then filters by productId
                val invoiceKey = calc.invoiceNumber.trim()
                val existingForInvoice = try {
                    AppDatabase.getInstance(getApplication()).purchaseDao()
                        .getPurchasesByInvoiceAndDate(invoiceKey, calc.purchaseDate)
                } catch (e: Exception) { emptyList() }

                val duplicate = existingForInvoice.find { it.productId == calc.productId }
                if (duplicate != null) {
                    _saveStatus.value = SaveStatus.DuplicateFound(
                        existingPurchaseId = duplicate.id,
                        productName        = calc.productName,
                        date               = calc.purchaseDate,
                        invoice            = invoiceKey
                    )
                    return@launch
                }

                android.util.Log.d("PurchaseViewModel", "===== SAVING PURCHASE =====")
                android.util.Log.d("PurchaseViewModel", "Purchase date: ${calc.purchaseDate}")
                android.util.Log.d("PurchaseViewModel", "Product: ${calc.productName}")
                android.util.Log.d("PurchaseViewModel", "QQ Total: ${calc.qqTotalUnits}")
                android.util.Log.d("PurchaseViewModel", "Invoice: ${calc.invoiceNumber}")

                val purchase = Purchase(
                    purchaseDate = calc.purchaseDate,
                    productId = calc.productId,
                    productCode = calc.productCode,
                    productName = calc.productName,

                    qqBoxes = calc.qqBoxes,
                    qqLoose = calc.qqLoose,
                    qqUnitsPerBox = calc.qqUnitsPerBox,
                    qqTotalUnits = calc.qqTotalUnits,
                    qqUnitPrice = calc.qqUnitPrice,
                    qqTotalCost = calc.qqTotalCost,

                    ppBoxes = calc.ppBoxes,
                    ppLoose = calc.ppLoose,
                    ppUnitsPerBox = calc.ppUnitsPerBox,
                    ppTotalUnits = calc.ppTotalUnits,
                    ppUnitPrice = calc.ppUnitPrice,
                    ppTotalCost = calc.ppTotalCost,

                    nnBoxes = calc.nnBoxes,
                    nnLoose = calc.nnLoose,
                    nnUnitsPerBox = calc.nnUnitsPerBox,
                    nnTotalUnits = calc.nnTotalUnits,
                    nnUnitPrice = calc.nnUnitPrice,
                    nnTotalCost = calc.nnTotalCost,

                    ddBoxes = calc.ddBoxes,
                    ddLoose = calc.ddLoose,
                    ddUnitsPerBox = calc.ddUnitsPerBox,
                    ddTotalUnits = calc.ddTotalUnits,
                    ddUnitPrice = calc.ddUnitPrice,
                    ddTotalCost = calc.ddTotalCost,

                    totalCost = calc.grandTotal,
                    supplierName = calc.supplierName,
                    invoiceNumber = calc.invoiceNumber,
                    notes = calc.notes,
                    receivedDate = calc.receivedDate
                )

                android.util.Log.d("PurchaseViewModel", "Purchase object created with date: ${purchase.purchaseDate}")
                repository.insert(purchase)
                android.util.Log.d("PurchaseViewModel", "Purchase saved to database")

                // Auto-activate product if it was inactive — purchasing it means tracking it
                val selectedProduct = _selectedProduct.value
                val activatedName = if (selectedProduct != null && !selectedProduct.isActive) {
                    productDao.updateProduct(selectedProduct.copy(isActive = true))
                    selectedProduct.displayName
                } else ""

                _saveStatus.value = SaveStatus.Success(activatedName)
                resetForm()

            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "Purchases", "Operation failed", e)
                _saveStatus.value = SaveStatus.Error(e.message ?: "Failed to save purchase")
            }
        }
    }

    /**
     * Reset form to initial state
     */
    /**
     * Get purchase by ID for editing
     */
    suspend fun getPurchaseById(id: Long): Purchase? {
        return repository.getPurchaseById(id)
    }
    
    /**
     * Load an existing purchase for editing - DIRECTLY from Purchase data
     * This method does NOT depend on Product master table
     * Historical purchases remain editable even if product is deleted/inactive
     */
    fun loadPurchaseForEditDirectly(purchase: Purchase) {
        // Set a dummy selectedProduct to pass validation checks
        // In edit mode, we don't actually need the product master data
        _selectedProduct.value = null  // Explicitly null - edit mode doesn't use it
        
        // Load ALL data from the Purchase record (historical snapshot)
        _currentPurchase.value = PurchaseCalculation(
            productId = purchase.productId,  // Keep for reference only
            productCode = purchase.productCode,  // Historical snapshot
            productName = purchase.productName,  // Historical snapshot
            purchaseDate = purchase.purchaseDate,
            
            // QQ - from Purchase snapshot
            qqBoxes = purchase.qqBoxes,
            qqLoose = purchase.qqLoose,
            qqUnitsPerBox = purchase.qqUnitsPerBox,  // Historical value
            qqTotalUnits = purchase.qqTotalUnits,
            qqUnitPrice = purchase.qqUnitPrice,  // Historical price
            qqTotalCost = purchase.qqTotalCost,
            
            // PP - from Purchase snapshot
            ppBoxes = purchase.ppBoxes,
            ppLoose = purchase.ppLoose,
            ppUnitsPerBox = purchase.ppUnitsPerBox,
            ppTotalUnits = purchase.ppTotalUnits,
            ppUnitPrice = purchase.ppUnitPrice,
            ppTotalCost = purchase.ppTotalCost,
            
            // NN - from Purchase snapshot
            nnBoxes = purchase.nnBoxes,
            nnLoose = purchase.nnLoose,
            nnUnitsPerBox = purchase.nnUnitsPerBox,
            nnTotalUnits = purchase.nnTotalUnits,
            nnUnitPrice = purchase.nnUnitPrice,
            nnTotalCost = purchase.nnTotalCost,
            
            // DD - from Purchase snapshot
            ddBoxes = purchase.ddBoxes,
            ddLoose = purchase.ddLoose,
            ddUnitsPerBox = purchase.ddUnitsPerBox,
            ddTotalUnits = purchase.ddTotalUnits,
            ddUnitPrice = purchase.ddUnitPrice,
            ddTotalCost = purchase.ddTotalCost,
            
            grandTotal = purchase.totalCost,
            supplierName = purchase.supplierName,
            invoiceNumber = purchase.invoiceNumber,
            notes = purchase.notes,
            receivedDate = purchase.receivedDate
        )

        android.util.Log.d("PurchaseViewModel", "Loaded purchase DIRECTLY for edit (no product dependency): ${purchase.productName}")
    }
    
    /**
     * Load an existing purchase for editing - DEPRECATED
     * Use loadPurchaseForEditDirectly instead
     */
    fun loadPurchaseForEdit(purchase: Purchase) {
        // Find the product
        val product = allProducts.value?.find { it.id == purchase.productId }
        
        if (product != null) {
            _selectedProduct.value = product
            
            // Load purchase data into currentPurchase
            _currentPurchase.value = PurchaseCalculation(
                productId = purchase.productId,
                productCode = purchase.productCode,
                productName = purchase.productName,
                purchaseDate = purchase.purchaseDate,
                
                qqBoxes = purchase.qqBoxes,
                qqLoose = purchase.qqLoose,
                qqUnitsPerBox = purchase.qqUnitsPerBox,
                qqTotalUnits = purchase.qqTotalUnits,
                qqUnitPrice = purchase.qqUnitPrice,
                qqTotalCost = purchase.qqTotalCost,
                
                ppBoxes = purchase.ppBoxes,
                ppLoose = purchase.ppLoose,
                ppUnitsPerBox = purchase.ppUnitsPerBox,
                ppTotalUnits = purchase.ppTotalUnits,
                ppUnitPrice = purchase.ppUnitPrice,
                ppTotalCost = purchase.ppTotalCost,
                
                nnBoxes = purchase.nnBoxes,
                nnLoose = purchase.nnLoose,
                nnUnitsPerBox = purchase.nnUnitsPerBox,
                nnTotalUnits = purchase.nnTotalUnits,
                nnUnitPrice = purchase.nnUnitPrice,
                nnTotalCost = purchase.nnTotalCost,
                
                ddBoxes = purchase.ddBoxes,
                ddLoose = purchase.ddLoose,
                ddUnitsPerBox = purchase.ddUnitsPerBox,
                ddTotalUnits = purchase.ddTotalUnits,
                ddUnitPrice = purchase.ddUnitPrice,
                ddTotalCost = purchase.ddTotalCost,
                
                grandTotal = purchase.totalCost,
                supplierName = purchase.supplierName,
                invoiceNumber = purchase.invoiceNumber,
                notes = purchase.notes
            )
            
            android.util.Log.d("PurchaseViewModel", "Loaded purchase for edit: ${purchase.productName}, Date: ${purchase.purchaseDate}")
        }
    }
    
    /**
     * Update existing purchase
     */
    fun updatePurchase(purchaseId: Long) {
        viewModelScope.launch {
            try {
                _saveStatus.value = SaveStatus.Saving

                val calc = _currentPurchase.value
                if (calc == null || calc.productId == 0L) {
                    _saveStatus.value = SaveStatus.Error("Please select a product")
                    return@launch
                }

                if (calc.purchaseDate.isEmpty()) {
                    _saveStatus.value = SaveStatus.Error("Please select a date")
                    return@launch
                }

                // Check if at least one size has quantities
                if (calc.qqTotalUnits == 0 && calc.ppTotalUnits == 0 && 
                    calc.nnTotalUnits == 0 && calc.ddTotalUnits == 0) {
                    _saveStatus.value = SaveStatus.Error("Please enter quantities for at least one size")
                    return@launch
                }
                
                // VALIDATION: Check loose units are less than units per box
                if (calc.qqLoose >= calc.qqUnitsPerBox && calc.qqUnitsPerBox > 0 && calc.qqLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("QQ loose (${calc.qqLoose}) must be less than units/box (${calc.qqUnitsPerBox})")
                    return@launch
                }
                if (calc.ppLoose >= calc.ppUnitsPerBox && calc.ppUnitsPerBox > 0 && calc.ppLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("PP loose (${calc.ppLoose}) must be less than units/box (${calc.ppUnitsPerBox})")
                    return@launch
                }
                if (calc.nnLoose >= calc.nnUnitsPerBox && calc.nnUnitsPerBox > 0 && calc.nnLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("NN loose (${calc.nnLoose}) must be less than units/box (${calc.nnUnitsPerBox})")
                    return@launch
                }
                if (calc.ddLoose >= calc.ddUnitsPerBox && calc.ddUnitsPerBox > 0 && calc.ddLoose > 0) {
                    _saveStatus.value = SaveStatus.Error("DD loose (${calc.ddLoose}) must be less than units/box (${calc.ddUnitsPerBox})")
                    return@launch
                }

                val purchase = Purchase(
                    id = purchaseId,  // Keep the same ID to update
                    purchaseDate = calc.purchaseDate,
                    productId = calc.productId,
                    productCode = calc.productCode,
                    productName = calc.productName,

                    qqBoxes = calc.qqBoxes,
                    qqLoose = calc.qqLoose,
                    qqUnitsPerBox = calc.qqUnitsPerBox,
                    qqTotalUnits = calc.qqTotalUnits,
                    qqUnitPrice = calc.qqUnitPrice,
                    qqTotalCost = calc.qqTotalCost,

                    ppBoxes = calc.ppBoxes,
                    ppLoose = calc.ppLoose,
                    ppUnitsPerBox = calc.ppUnitsPerBox,
                    ppTotalUnits = calc.ppTotalUnits,
                    ppUnitPrice = calc.ppUnitPrice,
                    ppTotalCost = calc.ppTotalCost,

                    nnBoxes = calc.nnBoxes,
                    nnLoose = calc.nnLoose,
                    nnUnitsPerBox = calc.nnUnitsPerBox,
                    nnTotalUnits = calc.nnTotalUnits,
                    nnUnitPrice = calc.nnUnitPrice,
                    nnTotalCost = calc.nnTotalCost,

                    ddBoxes = calc.ddBoxes,
                    ddLoose = calc.ddLoose,
                    ddUnitsPerBox = calc.ddUnitsPerBox,
                    ddTotalUnits = calc.ddTotalUnits,
                    ddUnitPrice = calc.ddUnitPrice,
                    ddTotalCost = calc.ddTotalCost,

                    totalCost = calc.grandTotal,
                    supplierName = calc.supplierName,
                    invoiceNumber = calc.invoiceNumber,
                    notes = calc.notes,
                    receivedDate = calc.receivedDate
                )

                repository.update(purchase)
                android.util.Log.d("PurchaseViewModel", "Purchase updated")
                _saveStatus.value = SaveStatus.Success()
                resetForm()

            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "Purchases", "Operation failed", e)
                _saveStatus.value = SaveStatus.Error(e.message ?: "Failed to update purchase")
            }
        }
    }

    fun resetForm() {
        val currentDate     = _currentPurchase.value?.purchaseDate
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val currentReceived = _currentPurchase.value?.receivedDate ?: ""
        _selectedProduct.value = null
        _currentPurchase.value = PurchaseCalculation(
            purchaseDate = currentDate,
            receivedDate = currentReceived
        )
    }

    fun clearSaveStatus() {
        _saveStatus.value = null
    }

    // ── Import status — survives rotation ─────────────────────────────────────

    sealed class ImportStatus {
        object Idle                                         : ImportStatus()
        data class Running(val message: String)             : ImportStatus()
        data class Success(
            val newCount:     Int,
            val skippedCount: Int,
            val failCount:    Int,
            val warnings:     List<String>,
            val errors:       List<String>
        ) : ImportStatus()
        data class Error(val message: String)               : ImportStatus()
    }

    private val _importStatus = MutableLiveData<ImportStatus>(ImportStatus.Idle)
    val importStatus: LiveData<ImportStatus> = _importStatus

    fun clearImportStatus() { _importStatus.value = ImportStatus.Idle }

    /**
     * Import purchases from an Excel file.
     * Runs in viewModelScope — survives rotation.
     * Fragment observes [importStatus] to update UI.
     */
    fun importFromExcel(
        uri:         android.net.Uri,
        excelHelper: PurchaseExcelHelper
    ) {
        if (_importStatus.value is ImportStatus.Running) return
        _importStatus.value = ImportStatus.Running("Importing purchases…")
        viewModelScope.launch {
            try {
                val products = getProductsForImport()
                if (products.isEmpty()) {
                    _importStatus.postValue(ImportStatus.Error(
                        "No products in database. Please add products first."))
                    return@launch
                }
                val result = excelHelper.importPurchases(uri, products, getRepository())
                // Use suspend save so ALL purchases are written before Success is posted.
                // savePurchase() fires separate coroutines (fire-and-forget) — use
                // savePurchaseSuspend() here to keep the import sequential and atomic.
                var skippedByBaseline = 0
                for (purchase in result.purchases) {
                    if (!savePurchaseSuspend(purchase)) skippedByBaseline++
                }
                // Auto-activate any inactive products that appear in the imported purchases
                val importedProductIds = result.purchases
                    .map { it.productId }.filter { it > 0 }.toSet()
                if (importedProductIds.isNotEmpty()) {
                    productDao.activateByIds(importedProductIds.toList())
                }
                val baselineWarning = if (skippedByBaseline > 0) listOf(
                    "$skippedByBaseline purchase(s) skipped — dated before the active " +
                    "opening stock date."
                ) else emptyList()
                _importStatus.postValue(ImportStatus.Success(
                    newCount     = result.successCount - skippedByBaseline,
                    skippedCount = result.skippedCount + skippedByBaseline,
                    failCount    = result.failCount,
                    warnings     = result.warnings + baselineWarning,
                    errors       = result.errors
                ))
            } catch (e: Exception) {
                android.util.Log.e("PurchaseViewModel", "Import exception", e)
                _importStatus.postValue(ImportStatus.Error(e.message ?: "Import failed"))
            }
        }
    }

    /**
     * Get purchases by date
     */
    fun getPurchasesByDate(date: String): LiveData<List<Purchase>> {
        return repository.getPurchasesByDate(date)
    }

    /**
     * Get purchases by date range
     */
    fun getPurchasesByDateRange(startDate: String, endDate: String): LiveData<List<Purchase>> {
        return repository.getPurchasesByDateRange(startDate, endDate)
    }

    /**
     * Delete purchase
     */
    fun deletePurchase(purchase: Purchase) {
        viewModelScope.launch {
            repository.delete(purchase)
        }
    }
    
    /**
     * Save a Purchase object directly (for imports)
     * This bypasses the normal form validation
     */
    /**
     * Save a purchase, resolving alias productCode to primary before insert.
     * This is the single write gate for all import paths. Any purchase entered or
     * imported with an alias code (e.g. "WC542") is normalised to the primary code
     * (e.g. "W1182") before hitting the DB, so all downstream logic (daily stock,
     * reports, sync) only ever sees primary codes.
     */
    fun savePurchase(purchase: Purchase) {
        viewModelScope.launch { savePurchaseSuspend(purchase) }
    }

    /**
     * Suspend version — use inside coroutines to ensure sequential writes.
     * Returns false (without inserting) when [purchase.purchaseDate] is before the
     * active opening-stock baseline — that period was superseded by a re-baseline.
     */
    private suspend fun savePurchaseSuspend(purchase: Purchase): Boolean {
        try {
            val minDate = dailyStockDao.getLatestOpeningStockDate()
            if (minDate != null && purchase.purchaseDate < minDate) {
                android.util.Log.w("PurchaseViewModel",
                    "Skipped purchase dated ${purchase.purchaseDate} — before opening stock date $minDate")
                return false
            }
            val normalised = normalisePurchaseCode(purchase)
            if (normalised.productId > 0 && normalised.invoiceNumber.isNotBlank())
                repository.saveLine(normalised)
            else
                repository.insert(normalised)
            android.util.Log.d("PurchaseViewModel",
                "Saved purchase: ${normalised.productName} " +
                "code=${normalised.productCode} date=${normalised.purchaseDate}")
            return true
        } catch (e: Exception) {
            android.util.Log.e("PurchaseViewModel",
                "Failed to save purchase: ${e.message}", e)
            throw e
        }
    }

    /**
     * Resolve alias productCode → primary productCode + productId.
     * If [purchase.productCode] is already the primary code, returns unchanged.
     * If it matches an alias, replaces productCode and productId with canonical values.
     * If unrecognised, logs a warning and returns unchanged (safe fallback).
     */
    private suspend fun normalisePurchaseCode(purchase: Purchase): Purchase {
        val products = try { productDao.getAllActiveProductsSync() }
                       catch (e: Exception) { allProducts.value ?: emptyList() }
        val canonical = ProductCodeResolver.resolve(purchase.productCode, products)
        if (canonical == null) {
            android.util.Log.w("PurchaseViewModel",
                "normalisePurchaseCode: no product found for code=${purchase.productCode}")
            return purchase
        }
        val primaryCode = ProductCodeResolver.primaryCode(canonical)
        if (primaryCode == purchase.productCode && canonical.id == purchase.productId) {
            return purchase  // already canonical — no change needed
        }
        android.util.Log.d("PurchaseViewModel",
            "normalisePurchaseCode: ${purchase.productCode} → $primaryCode " +
            "(id: ${purchase.productId} → ${canonical.id})")
        return purchase.copy(
            productCode = primaryCode,
            productId   = canonical.id
        )
    }
    
    /**
     * Load products synchronously for import operations
     * LiveData.value may be null if not yet observed, so we load directly
     */
    suspend fun getProductsForImport(): List<Product> {
        return try {
            productDao.getAllProductsSync()  // inactive products can still appear in historic imports
        } catch (e: Exception) {
            android.util.Log.e("PurchaseViewModel", "Failed to load products for import: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get repository for import operations
     */
    fun getRepository(): PurchaseRepository {
        return repository
    }

    /**
     * Data class for purchase calculations (in-memory only)
     * NOTE: purchaseDate has NO default - must be explicitly set to prevent date bugs
     */
    data class PurchaseCalculation(
        val productId: Long = 0,
        val productCode: String = "",
        val productName: String = "",
        val purchaseDate: String = "",  // NO DEFAULT - must be set explicitly
        
        // QQ
        val qqBoxes: Int = 0,
        val qqLoose: Int = 0,
        val qqUnitsPerBox: Int = 12,  // Default matches Product
        val qqTotalUnits: Int = 0,
        val qqUnitPrice: Double = 0.0,
        val qqTotalCost: Double = 0.0,
        
        // PP
        val ppBoxes: Int = 0,
        val ppLoose: Int = 0,
        val ppUnitsPerBox: Int = 24,  // Default matches Product
        val ppTotalUnits: Int = 0,
        val ppUnitPrice: Double = 0.0,
        val ppTotalCost: Double = 0.0,
        
        // NN
        val nnBoxes: Int = 0,
        val nnLoose: Int = 0,
        val nnUnitsPerBox: Int = 48,  // Default matches Product
        val nnTotalUnits: Int = 0,
        val nnUnitPrice: Double = 0.0,
        val nnTotalCost: Double = 0.0,
        
        // DD
        val ddBoxes: Int = 0,
        val ddLoose: Int = 0,
        val ddUnitsPerBox: Int = 96,  // Default matches Product
        val ddTotalUnits: Int = 0,
        val ddUnitPrice: Double = 0.0,
        val ddTotalCost: Double = 0.0,
        
        // Metadata
        val supplierName: String = "",
        val invoiceNumber: String = "",
        val notes: String = "",
        val receivedDate: String = "",  // blank = same as purchaseDate

        val grandTotal: Double = 0.0
    ) {
        fun recalculateGrandTotal(): PurchaseCalculation {
            val total = qqTotalCost + ppTotalCost + nnTotalCost + ddTotalCost
            return this.copy(grandTotal = total)
        }
    }

    sealed class SaveStatus {
        object Saving : SaveStatus()
        /** Purchase saved. activatedProductName is non-empty when an inactive product was auto-activated. */
        data class Success(val activatedProductName: String = "") : SaveStatus()
        data class Error(val message: String) : SaveStatus()
        /** Duplicate product found for same date+invoice — carry its ID so Fragment can navigate to Edit */
        data class DuplicateFound(val existingPurchaseId: Long, val productName: String, val date: String, val invoice: String) : SaveStatus()
    }
}
