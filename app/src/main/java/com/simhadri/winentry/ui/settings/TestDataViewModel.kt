package com.simhadri.winentry.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.sync.TestDataImportManager
import kotlinx.coroutines.launch
import java.time.LocalDate

class TestDataViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AppDatabase.getInstance(application)
    private val repo = DailyStockRepository(db.productDao(), db.dailyStockDao())

    sealed class State {
        object Checking : State()
        object NoData   : State()
        object HasData  : State()
        object Loading  : State()
        data class Done(val success: Boolean, val message: String) : State()
    }

    val state = MutableLiveData<State>(State.Checking)

    fun checkDataState() {
        viewModelScope.launch {
            state.value = State.Checking
            val hasData = repo.getEarliestCommittedDate() != null
            state.value = if (hasData) State.HasData else State.NoData
        }
    }

    fun importTestData(startDate: LocalDate) {
        viewModelScope.launch {
            state.value = State.Loading
            try {
                val products = repo.getActiveProductsSortedSync()
                if (products.isEmpty()) {
                    state.value = State.Done(
                        success = false,
                        message = "No products found in this device.\n\nGo to Home and tap Sync to download the product list first, then retry."
                    )
                    return@launch
                }

                val fetchResult = TestDataImportManager.fetchTestData(getApplication())
                if (fetchResult.isFailure) {
                    state.value = State.Done(
                        success = false,
                        message = fetchResult.exceptionOrNull()?.message
                            ?: "Failed to download test data. Check internet connection."
                    )
                    return@launch
                }

                // Clear all existing daily stock before loading test data
                repo.clearAllData()

                val result = TestDataImportManager.importTestData(
                    testData   = fetchResult.getOrThrow(),
                    obDate     = startDate,
                    products   = products,
                    repository = repo
                )

                state.value = when (result) {
                    is TestDataImportManager.ImportResult.Success -> State.Done(
                        success = true,
                        message = buildString {
                            append("Test data loaded successfully!\n\n")
                            append("• ${result.obCount} products as Opening Balance on $startDate\n")
                            append("• ${result.cbDays} days of Closing Balances (${result.cbRows} entries)\n\n")
                            append("Open Daily Stock to explore the data.")
                        }
                    )
                    is TestDataImportManager.ImportResult.Failure -> State.Done(
                        success = false,
                        message = result.message
                    )
                }
            } catch (e: Exception) {
                state.value = State.Done(
                    success = false,
                    message = e.message ?: "Unexpected error during import."
                )
            }
        }
    }
}
