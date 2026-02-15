package com.fear

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREFS_NAME = "fear_theme"
    private const val KEY_THEME = "theme_mode"
    const val THEME_DARK = 0
    const val THEME_LIGHT = 1

    fun getTheme(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME, THEME_DARK)
    }

    fun setTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, theme).apply()
        applyTheme(theme)
    }

    fun applyTheme(theme: Int) {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun isDark(context: Context): Boolean = getTheme(context) == THEME_DARK
}
