package com.simhadri.winentry.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.sync.TestDataImportManager
import com.simhadri.winentry.utils.UserRegistrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class OnboardingState(
    val isDismissed: Boolean = false,
    val step1Done: Boolean = false,           // user registered (registerUserOnly CF succeeded)
    val step2Done: Boolean = false,           // productCount > 0
    val step3Done: Boolean = false,           // test data imported (pref flag set on success)
    val step4Done: Boolean = false,           // earliestCommittedDate != null (test data OR manual OB)
    val downloadInProgress: Boolean = false,
    val downloadError: String? = null,
    val testImportInProgress: Boolean = false,
    val testImportError: String? = null
) {
    val openingBalanceDone = step3Done || step4Done
    val requiredDone       = step1Done && step2Done && openingBalanceDone
    val shouldShow         = !isDismissed && !requiredDone
    val completedCount     = listOf(step1Done, step2Done, openingBalanceDone).count { it }
    val totalRequired      = 3
}

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state

    fun refresh() {
        viewModelScope.launch {
            val current = _state.value
            _state.value = buildState().copy(
                downloadInProgress  = current.downloadInProgress,
                testImportInProgress = current.testImportInProgress
            )
        }
    }

    fun dismissCard() {
        getApplication<Application>()
            .getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_card_dismissed", true).apply()
        _state.value = _state.value.copy(isDismissed = true)
    }

    fun startProductDownload() {
        if (_state.value.downloadInProgress) return
        _state.value = _state.value.copy(downloadInProgress = true, downloadError = null)
        viewModelScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                runCatching { SyncCoordinator(getApplication()).syncProductsOnly() }
            }
            val syncResult = attempt.getOrNull()
            val error: String? = when {
                attempt.isFailure                               -> attempt.exceptionOrNull()?.message ?: "Unknown error"
                syncResult is SyncCoordinator.SyncResult.Error  -> syncResult.message
                else                                            -> null
            }
            _state.value = buildState().copy(downloadInProgress = false, downloadError = error)
        }
    }

    fun clearDownloadError() {
        _state.value = _state.value.copy(downloadError = null)
    }

    fun startTestDataImport(startDate: LocalDate) {
        if (_state.value.testImportInProgress) return
        _state.value = _state.value.copy(testImportInProgress = true, testImportError = null)
        viewModelScope.launch {
            val error: String? = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val db = AppDatabase.getInstance(app)
                val products = db.productDao().getActiveProductsByDailySortKeySync()
                if (products.isEmpty()) return@withContext "Download products first, then retry."
                val fetch = TestDataImportManager.fetchTestData(app)
                if (fetch.isFailure) return@withContext fetch.exceptionOrNull()?.message ?: "Download failed."
                com.simhadri.winentry.sync.SyncCoordinator(app).sampleDataBlocker()?.let { return@withContext it }
                com.simhadri.winentry.utils.DbSnapshot.take(app, "sample-data")
                val repo = DailyStockRepository(db.productDao(), db.dailyStockDao())
                repo.clearAllData()
                val result = TestDataImportManager.importTestData(fetch.getOrThrow(), startDate, products, repo)
                if (result is TestDataImportManager.ImportResult.Failure) {
                    result.message
                } else {
                    app.getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("test_data_imported", true).apply()
                    null
                }
            }
            _state.value = buildState().copy(testImportInProgress = false, testImportError = error)
        }
    }

    fun clearTestImportError() {
        _state.value = _state.value.copy(testImportError = null)
    }

    private suspend fun buildState(): OnboardingState = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
        val dismissed        = prefs.getBoolean("onboarding_card_dismissed", false)
        val testDataImported = prefs.getBoolean("test_data_imported", false)

        val db = AppDatabase.getInstance(app)
        val productCount = db.productDao().getCount()
        val earliestDate = db.dailyStockDao().getEarliestCommittedDate()

        OnboardingState(
            isDismissed = dismissed,
            step1Done   = UserRegistrationManager.isRegistered(app),
            step2Done   = productCount > 0,
            step3Done   = testDataImported,
            step4Done   = earliestDate != null
        )
    }
}
