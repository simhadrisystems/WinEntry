package com.simhadri.winentry.ui.products

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.simhadri.winentry.ui.auth.ErrorLogger

class ProductViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).productDao()

    // Sort order selection
    private val _sortOrder = MutableLiveData<SortOrder>(SortOrder.SERIAL)

    // Switch LiveData based on sort order
    val allProducts: LiveData<List<Product>> = _sortOrder.switchMap { sortOrder ->
        when (sortOrder) {
            SortOrder.SERIAL -> dao.getAllProductsBySerial()
            SortOrder.NAME -> dao.getAllProductsByName()
            SortOrder.TYPE -> dao.getAllProductsByType()
            else -> dao.getAllProductsBySerial()
        }
    }
    
    // Save result for observing errors
    private val _saveResult = MutableLiveData<SaveResult?>()
    val saveResult: LiveData<SaveResult?> = _saveResult

    fun setSortOrder(sortOrder: SortOrder) {
        _sortOrder.value = sortOrder
    }

    fun insert(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.insertProduct(product)
                _saveResult.postValue(SaveResult.Success)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                if (e.message?.contains("brandCode") == true) {
                    _saveResult.postValue(SaveResult.DuplicateBrandCode(product.brandCode))
                } else {
                    _saveResult.postValue(SaveResult.Error(e.message ?: "Database constraint error"))
                }
            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "Products", "Insert failed product=${product.brandCode}", e)
                _saveResult.postValue(SaveResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun update(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.updateProduct(product)
                _saveResult.postValue(SaveResult.Success)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                if (e.message?.contains("brandCode") == true) {
                    _saveResult.postValue(SaveResult.DuplicateBrandCode(product.brandCode))
                } else {
                    _saveResult.postValue(SaveResult.Error(e.message ?: "Database constraint error"))
                }
            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "Products", "Update failed product=${product.brandCode}", e)
                _saveResult.postValue(SaveResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun delete(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteProduct(product)
        }
    }

    fun insertAll(products: List<Product>) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertProducts(products)
        }
    }
    
    fun clearSaveResult() {
        _saveResult.value = null
    }

    enum class SortOrder {
        SERIAL, NAME, TYPE
    }
    
    sealed class SaveResult {
        object Success : SaveResult()
        data class DuplicateBrandCode(val brandCode: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
}
