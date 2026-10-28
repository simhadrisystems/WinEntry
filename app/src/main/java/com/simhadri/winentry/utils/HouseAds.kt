package com.simhadri.winentry.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import androidx.annotation.DrawableRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.ViewHouseAdBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-house promo card for Simhadri Systems' own apps (ported from eCalc's HouseAdCard).
 * Everything is bundled: no ad SDK, no network call, no tracking. The installed-app check
 * relies on the manifest <queries> entries for these packages.
 */
object HouseAds {

    private data class HouseAd(
        val packageName: String,
        @DrawableRes val icon: Int,
        val headline: AppStrings.L,
        val body: AppStrings.L,
        val cta: AppStrings.L,
        // null = the app's Play listing. Expense Tracker is still in closed testing, so its Play
        // listing 404s for non-testers; it points at the website page until it goes public.
        val webUrl: String? = null,
    )

    private val ADS = listOf(
        HouseAd(
            packageName = "com.simhadri.sudoku",
            icon = R.drawable.house_ad_esudoku,
            headline = AppStrings.houseAdSudokuHeadline,
            body = AppStrings.houseAdSudokuBody,
            cta = AppStrings.houseAdSudokuCta,
        ),
        HouseAd(
            packageName = "com.simhadri.expensetracker",
            icon = R.drawable.house_ad_expensetracker,
            headline = AppStrings.houseAdExpenseHeadline,
            body = AppStrings.houseAdExpenseBody,
            cta = AppStrings.houseAdExpenseCta,
            webUrl = "https://simhadrisystems.in/expensetracker/",
        ),
        HouseAd(
            packageName = "com.simhadri.calc",
            icon = R.drawable.house_ad_ecalc,
            headline = AppStrings.houseAdCalcHeadline,
            body = AppStrings.houseAdCalcBody,
            cta = AppStrings.houseAdInstallCta,
        ),
    )

    private const val ROTATE_MS = 20_000L
    private const val HIDE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PREFS = "inventory_prefs"
    private const val KEY_HIDDEN_UNTIL = "house_ad_hidden_until"

    // Advances across card instances so Home and Settings don't always open on the same app.
    private var nextStartIndex = 0

    fun isHiddenOnHome(context: Context): Boolean =
        System.currentTimeMillis() <
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_HIDDEN_UNTIL, 0L)

    fun showOnHomeAgain(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_HIDDEN_UNTIL)
            .apply()
    }

    private fun hideOnHome(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_HIDDEN_UNTIL, System.currentTimeMillis() + HIDE_MS)
            .apply()
    }

    /**
     * Binds a card and rotates it while [owner] is STARTED. With [onHidden], the card shows an X
     * that hides it on Home for 7 days, then calls [onHidden] so the caller can hide it and its
     * caption. Returns a function that re-renders the current app in the current language (call
     * it after a language change).
     */
    fun bind(binding: ViewHouseAdBinding, owner: LifecycleOwner, onHidden: (() -> Unit)? = null): () -> Unit {
        val context = binding.root.context
        // Don't advertise an app the user already has; if they have all of them, rotate all anyway
        // rather than leave the card empty.
        val ads = ADS.filterNot { isInstalled(context, it.packageName) }.ifEmpty { ADS }
        var index = nextStartIndex++

        val render = {
            val ad = ads[index.mod(ads.size)]
            val lang = LangPrefs.get(context)
            binding.houseAdIcon.setImageResource(ad.icon)
            binding.houseAdHeadline.text = ad.headline.get(lang)
            binding.houseAdBody.text = ad.body.get(lang)
            binding.houseAdCta.text = ad.cta.get(lang)
            binding.houseAdClose.contentDescription = AppStrings.houseAdClose.get(lang)
            val open = View.OnClickListener { open(context, ad) }
            binding.houseAdIcon.setOnClickListener(open)
            binding.houseAdHeadline.setOnClickListener(open)
            binding.houseAdCta.setOnClickListener(open)
        }

        binding.houseAdClose.visibility = if (onHidden != null) View.VISIBLE else View.GONE
        binding.houseAdClose.setOnClickListener {
            hideOnHome(context)
            onHidden?.invoke()
        }
        render()

        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(ROTATE_MS)
                    index++
                    render()
                }
            }
        }
        return render
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun open(context: Context, ad: HouseAd) {
        val referrer = "&referrer=utm_source%3Dwinentry%26utm_medium%3Dhouse_ad"
        val webUrl = ad.webUrl ?: "https://play.google.com/store/apps/details?id=${ad.packageName}$referrer"
        val primary = if (ad.webUrl == null) "market://details?id=${ad.packageName}$referrer" else webUrl
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(primary)))
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
