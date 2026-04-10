package com.simple.simpleinventory.utils

import android.content.Context
import com.simple.simpleinventory.utils.AppStrings.Lang

object LangPrefs {
    private const val PREF_FILE = "lang_prefs"
    private const val KEY_LANG  = "language"

    fun get(context: Context): Lang {
        val saved = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANG, Lang.EN.name)
        return runCatching { Lang.valueOf(saved!!) }.getOrDefault(Lang.EN)
    }

    fun set(context: Context, lang: Lang) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang.name).apply()
    }
}
