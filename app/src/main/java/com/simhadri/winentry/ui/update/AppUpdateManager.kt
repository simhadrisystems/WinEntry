package com.simhadri.winentry.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.simhadri.winentry.BuildConfig
import com.simhadri.winentry.utils.AppDialogs
import kotlinx.coroutines.tasks.await

class AppUpdateManager(private val activity: FragmentActivity) {

    companion object {
        private const val PROMPT_DAYS = 7
        private const val FORCE_DAYS  = 60
    }

    enum class ManualResult { STARTED, UP_TO_DATE, OPENED_STORE, SKIPPED_DEBUG }

    private val manager = AppUpdateManagerFactory.create(activity)

    suspend fun checkForUpdates(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (BuildConfig.DEBUG) return
        val info = runCatching { manager.appUpdateInfo.await() }.getOrNull() ?: return
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            promptRestartToInstall()
            return
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return

        val staleness = info.clientVersionStalenessDays() ?: 0
        val type = when {
            BuildConfig.FORCE_UPDATE    -> AppUpdateType.IMMEDIATE
            staleness >= FORCE_DAYS     -> AppUpdateType.IMMEDIATE
            staleness >= PROMPT_DAYS    -> AppUpdateType.FLEXIBLE
            else                        -> return
        }
        if (!info.isUpdateTypeAllowed(type)) return
        startFlow(info, type, launcher)
    }

    /**
     * User-initiated check — ignores staleness gates and prefers IMMEDIATE, since the user
     * explicitly asked to update. Falls back to FLEXIBLE, then to the Play Store listing.
     */
    suspend fun checkForUpdatesManually(launcher: ActivityResultLauncher<IntentSenderRequest>): ManualResult {
        if (BuildConfig.DEBUG) return ManualResult.SKIPPED_DEBUG
        // Fails when the app was not installed from Play (e.g. sideloaded APK).
        val info = runCatching { manager.appUpdateInfo.await() }.getOrNull()
            ?: return openPlayStore()

        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            manager.completeUpdate()
            return ManualResult.STARTED
        }
        when (info.updateAvailability()) {
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                startFlow(info, AppUpdateType.IMMEDIATE, launcher)
                return ManualResult.STARTED
            }
            UpdateAvailability.UPDATE_AVAILABLE -> Unit
            else -> return ManualResult.UP_TO_DATE
        }

        val type = when {
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)  -> AppUpdateType.FLEXIBLE
            else -> return openPlayStore()
        }
        startFlow(info, type, launcher)
        return ManualResult.STARTED
    }

    /**
     * Call from onResume(). Play does not resume an interrupted IMMEDIATE update on its own,
     * and a FLEXIBLE download that finished while the app was backgrounded still needs
     * completeUpdate() to actually install.
     */
    suspend fun resumeInProgressUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (BuildConfig.DEBUG) return
        val info = runCatching { manager.appUpdateInfo.await() }.getOrNull() ?: return
        when {
            info.installStatus() == InstallStatus.DOWNLOADED -> promptRestartToInstall()
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                startFlow(info, AppUpdateType.IMMEDIATE, launcher)
        }
    }

    fun openPlayStore(): ManualResult {
        val pkg = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            )
        }
        return ManualResult.OPENED_STORE
    }

    private fun startFlow(
        info: AppUpdateInfo,
        type: Int,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        // Without this listener a FLEXIBLE download finishes in the background and is never
        // installed — tapping "Update" in the Play prompt appears to do nothing.
        if (type == AppUpdateType.FLEXIBLE) {
            manager.registerListener(object : InstallStateUpdatedListener {
                override fun onStateUpdate(state: InstallState) {
                    when (state.installStatus()) {
                        InstallStatus.DOWNLOADED -> {
                            manager.unregisterListener(this)
                            promptRestartToInstall()
                        }
                        InstallStatus.FAILED, InstallStatus.CANCELED ->
                            manager.unregisterListener(this)
                    }
                }
            })
        }
        manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.defaultOptions(type))
    }

    private fun promptRestartToInstall() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        AppDialogs.confirm(
            activity,
            "Update ready",
            "A new version of WinEntry has been downloaded. Restart now to install it.",
            "Restart"
        ) { manager.completeUpdate() }
    }
}
