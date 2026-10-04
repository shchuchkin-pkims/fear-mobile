package com.fear

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREFS_NAME = "fear_theme"
    private const val KEY_THEME = "theme_mode"
    /** -1 = follow system; 0 = dark; 1 = light. */
    const val THEME_SYSTEM = -1
    const val THEME_DARK = 0
    const val THEME_LIGHT = 1

    fun getTheme(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME, THEME_SYSTEM)
    }

    fun setTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, theme).apply()
        applyTheme(theme)
    }

    fun applyTheme(theme: Int) {
        when (theme) {
            THEME_LIGHT  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK   -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else         -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /** True если эффективная тема — тёмная (учитывает «follow system»). */
    fun isDark(context: Context): Boolean {
        val mode = getTheme(context)
        return when (mode) {
            THEME_LIGHT -> false
            THEME_DARK  -> true
            else        -> {
                val cfg = context.resources.configuration.uiMode and
                          android.content.res.Configuration.UI_MODE_NIGHT_MASK
                cfg == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
