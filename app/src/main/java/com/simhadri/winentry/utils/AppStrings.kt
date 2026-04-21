package com.simhadri.winentry.utils

/**
 * Central translation table.
 *
 * To add a new language (e.g. Hindi):
 *   1. Add  `val hi: String = en`  to the L data class
 *   2. Add  `Lang.HI -> hi`        to L.get()
 *   3. Add  `HI("हिन्दी")`         to the Lang enum
 *   4. Fill in the `hi` values for each entry below
 *
 * To correct a translation: just edit the relevant `te = "..."` value here.
 */
object AppStrings {

    enum class Lang(val displayName: String) {
        EN("English"),
        TE("తెలుగు"),
        // HI("हिन्दी"),   ← add next language here
    }

    data class L(
        val en: String,
        val te: String = en,
        // val hi: String = en,   ← add next language here
    ) {
        fun get(lang: Lang): String = when (lang) {
            Lang.EN -> en
            Lang.TE -> te
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // HOME SCREEN
    // ─────────────────────────────────────────────────────────────────

    val homeWelcome = L(
        en = "Select a module to begin",
        te = "ఒక విభాగాన్ని ఎంచుకొని ప్రారంభించు"
    )
    val homeDailyStockTitle = L(
        en = "Daily Stock Entry",
        te = "రోజువారీ స్టాక్ నమోదు"
    )
    val homeDailyStockDesc = L(
        en = "Enter Closing Balance, log purchases, commit sales & expenses",
        te = "ముగింపు నిల్వలు నమోదు · కొనుగోళ్లు ప్రాంభ నిల్వల చేర్పు · అమ్మకం, జమ, ఖర్చుల నిర్ధారణ"
    )
    val homePurchasesTitle = L(
        en = "Purchases",
        te = "కొనుగోళ్లు"
    )
    val homePurchasesDesc = L(
        en = "Log supplier invoices — box quantities and unit prices per brand",
        te = "కొనుగోళ్ల వివరాలు (పెట్టెల, విడి సరకుల సంఖ్య, ధర), సరఫరాదారు,రవాణా అనుమతి వివరము"
    )
    val homeReportsTitle = L(
        en = "Reports",
        te = "రిపోర్టులు / నివేదికలు"
    )
    val homeReportsDesc = L(
        en = "Daily Stock sheets, Monthly summaries and Excel exports",
        te = "రోజువారీ స్టాక్ షీట్లు, నెలవారీ సారాంశం మరియు Excel కు మార్పు"
    )
    val homeProductsTitle = L(
        en = "Items/ Products",
        te = "సరకులు / ఉత్పత్తులు"
    )
    val homeProductsDesc = L(
        en = "Display order, opening stock setup and master data",
        te = "సరకులు పట్టీ కనిపించే క్రమం,  ప్రారంభ నిల్వల ఏర్పాటు, సరకుల మాస్టర్ డేటా"
    )
    val homeSettingsTitle = L(
        en = "Settings",
        te = "సెట్టింగులు/ ఎంపికలు"
    )
    val homeSettingsDesc = L(
        en = "Business info, sync settings and support",
        te = "వ్యాపార వివరములు, సింక్ సెట్టింగులు మరియు మద్దతు"
    )

    // ─────────────────────────────────────────────────────────────────
    // SETTINGS SCREEN
    // ─────────────────────────────────────────────────────────────────

    val settingsToolbarTitle = L(
        en = "Settings",
        te = "సెట్టింగులు / ఎంపికలు"
    )
    val settingsSectionLanguage = L(
        en = "LANGUAGE",
        te = "భాష"
    )
    val settingsLanguageTitle = L(
        en = "App Language యాప్ భాష",
        te = "యాప్-భాష App Language"
    )
    val settingsLanguageDesc = L(
        en = "English / తెలుగు display",
        te = "English / తెలుగులో కనిపించు"
    )
    val settingsSectionBusiness = L(
        en = "BUSINESS",
        te = "వ్యాపారం"
    )
    val settingsBusinessInfoTitle = L(
        en = "Business Info",
        te = "వ్యాపార వివరములు"
    )
    val settingsBusinessInfoDesc = L(
        en = "Business name, address and tax details for reports",
        te = "వ్యాపారం పేరు, చిరునామా, పన్ను వివరాలు, మొదలైనవి"
    )
    val settingsSectionSync = L(
        en = "SYNC",
        te = "సింక్/సమకాలీకరణ"
    )
    val settingsSyncTitle = L(
        en = "Sync Settings",
        te = "సింక్ సెట్టింగులు"
    )
    val settingsSyncDesc = L(
        en = "Auto-sync enable and last sync status",
        te = "తనకుతాను సింక్ చేసే అనుమతి, చివరిగా సింక్ అయిన సమయం"
    )
    val settingsSectionSupport = L(
        en = "SUPPORT",
        te = "మద్దతు"
    )
    val settingsHelpSupportTitle = L(
        en = "Help & Support",
        te = "సహాయం & మద్దతు"
    )
    val settingsHelpSupportDesc = L(
        en = "Report issues, ask for help or request features",
        te = "సమస్యలు నివేదన, సహాయం కోరు, కొత్త సదుపాయాల కోసం అడగు"
    )

    // ─────────────────────────────────────────────────────────────────
    // PRODUCTS MENU SCREEN
    // ─────────────────────────────────────────────────────────────────

    val productsMenuToolbarTitle = L(
        en = "Items / Products",
        te = "సరకులు / ఉత్పత్తులు"
    )
    val productsMenuSectionDisplay = L(
        en = "DISPLAY",
        te = "చూపు క్రమం"
    )
    val productsMenuOrderTitle = L(
        en = "Product Display Order",
        te = "సరకుల పట్టీ క్రమం"
    )
    val productsMenuOrderDesc = L(
        en = "Set the order products appear in Daily Stock",
        te = "రోజువారీ స్టాక్‌లో సరకులు కనిపించే క్రమాన్ని నిర్ణయించు"
    )
    val productsMenuSectionStock = L(
        en = "STOCK SETUP",
        te = "నిల్వ ఏర్పాటు"
    )
    val productsMenuStockTitle = L(
        en = "Opening Stock Setup",
        te = "ప్రారంభ నిల్వల ఏర్పాటు"
    )
    val productsMenuStockDesc = L(
        en = "Set initial stock quantities for a new period",
        te = "కొత్త కాలానికి ప్రారంభ నిల్వ రాశులు నిర్ణయించు"
    )
    val productsMenuSectionMaster = L(
        en = "MASTER DATA",
        te = "మాస్టర్ డేటా"
    )
    val productsMenuMasterTitle = L(
        en = "Product Master Data",
        te = "సరకుల మాస్టర్ డేటా"
    )
    val productsMenuMasterDesc = L(
        en = "Product codes, sizes and pricing",
        te = "సరకుల కోడ్‌లు, సైజులు మరియు ధరలు"
    )

    // ─────────────────────────────────────────────────────────────────
    // REPORTS SCREEN
    // ─────────────────────────────────────────────────────────────────

    val reportsToolbarTitle = L(
        en = "Reports",
        te = "రిపోర్టులు/నివేదికలు"
    )
    val reportsSectionDaily = L(
        en = "DAILY REPORTS",
        te = "రోజువారీ నివేదికలు"
    )
    val reportsDailySheetTitle = L(
        en = "Daily Stock Sheet",
        te = "రోజువారీ స్టాక్ షీట్"
    )
    val reportsDailySheetDesc = L(
        en = "OB · PQ · SQ · CB for all products on a date",
        te = "తేదీ ప్రకారము, సరకుల ప్రారంభ OB · కొనుగోలు PQ · అమ్మకం SQ · ముగింపు CB రాశి వివరము"
    )
    val reportsMonthlySaleTitle = L(
        en = "Monthly Sale Data",
        te = "నెలవారీ అమ్మకాల వివరము"
    )
    val reportsMonthlySaleDesc = L(
        en = "Day-end totals · UPI receipts · Expenses · Cash deposit",
        te = "రోజుచివర్న వచ్చిన మొత్తం వివరాలు · UPI బ్యాంకు ద్వారా · ఖర్చులు · నగదు జమ"
    )
    val reportsClosingBalancesTitle = L(
        en = "Closing Balances",
        te = "ముగింపు నిల్వలు CB"
    )
    val reportsClosingBalancesDesc = L(
        en = "End-of-day stock balance by product and size",
        te = "రోజుచివర్న మిగిలిన నిల్వ వివరము, బ్రాండ్/ సైజు వారీ వర్గీకరణ"
    )
    val reportsBrandWiseTitle = L(
        en = "Daily Brand Wise A/c",
        te = "రోజువారీ బ్రాండ్ వారీ వర్గీకరణ లెక్క"
    )
    val reportsBrandWiseDesc = L(
        en = "Form R2 — all brands, grouped by type",
        te = "ఫారం R2 — అన్ని బ్రాండ్ల అమ్మకాలు రకాల వారీగా వర్గీకరణ"
    )
    val reportsSectionPeriod = L(
        en = "PERIOD REPORTS",
        te = "కాల వ్యవధి నివేదికలు"
    )
    val reportsPurchaseReportTitle = L(
        en = "Purchase Report",
        te = "కొనుగోలు నివేదిక"
    )
    val reportsPurchaseReportDesc = L(
        en = "Purchases by product with daily breakdown for a period",
        te = "కాల వ్యవధిలో సరకుల వారీగా కొనుగోళ్ల వివరము"
    )
    val reportsSectionMonthly = L(
        en = "MONTHLY REPORTS",
        te = "నెలవారీ రిపోర్టులు/నివేదికలు"
    )
    val reportsMonthlyPurchasesTitle = L(
        en = "Monthly Stock Purchases",
        te = "నెలవారీ స్టాక్ కొనుగోళ్ల వివరం"
    )
    val reportsMonthlyPurchasesDesc = L(
        en = "Purchase totals by product per month",
        te = "నెలవారీ బ్రాండ్ వారీగా కొనుగోలు వివరము"
    )
    val reportsViewButton = L(
        en = "View Report",
        te = "రిపోర్టు చూడండి"
    )
    val reportsIncludeZero = L(
        en = "Include products with zero activity",
        te = "అమ్మకాలు లేని సరకులను కూడా చేర్చు"
    )
}
