package com.simhadri.winentry.ui.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentActivity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.simhadri.winentry.BuildConfig
import kotlinx.coroutines.tasks.await

class AppUpdateManager(private val activity: FragmentActivity) {

    companion object {
        private const val PROMPT_DAYS = 7
        private const val FORCE_DAYS  = 60
    }

    suspend fun checkForUpdates(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (BuildConfig.DEBUG) return
        val manager = AppUpdateManagerFactory.create(activity)
        val info = runCatching { manager.appUpdateInfo.await() }.getOrNull() ?: return
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return

        val staleness = info.clientVersionStalenessDays() ?: 0
        val type = when {
            BuildConfig.FORCE_UPDATE    -> AppUpdateType.IMMEDIATE
            staleness >= FORCE_DAYS     -> AppUpdateType.IMMEDIATE
            staleness >= PROMPT_DAYS    -> AppUpdateType.FLEXIBLE
            else                        -> return
        }
        if (!info.isUpdateTypeAllowed(type)) return
        manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.defaultOptions(type))
    }

    /**
     * User-initiated check — ignores staleness gates.
     * Returns true if an update prompt was started, false if already up to date.
     */
    suspend fun checkForUpdatesManually(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        if (BuildConfig.DEBUG) return false
        val manager = AppUpdateManagerFactory.create(activity)
        val info = runCatching { manager.appUpdateInfo.await() }.getOrNull() ?: return false
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return false

        val type = if (BuildConfig.FORCE_UPDATE) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        if (!info.isUpdateTypeAllowed(type)) return false
        manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.defaultOptions(type))
        return true
    }
}
