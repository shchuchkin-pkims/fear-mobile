package com.fear.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Convenience holder for theme-specific colors that don't fit Material's roles
// (e.g. bubble colors, avatar palette).
data class FearColors(
    val isDark: Boolean,
    val background: Color,
    val chatBackgroundTop: Color,    // for the gradient (or solid in dark)
    val chatBackgroundBottom: Color,
    val surface: Color,
    val border: Color,
    val hover: Color,
    val accent: Color,
    val selectedItem: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val bubbleSelf: Color,
    val bubblePeer: Color,
    val bubbleSelfText: Color,
    val bubblePeerText: Color,
    val unreadBadge: Color,
)

private val DarkColors = FearColors(
    isDark = true,
    background = FearColorsDark.Background,
    chatBackgroundTop = FearColorsDark.ChatBackground,
    chatBackgroundBottom = FearColorsDark.ChatBackground,
    surface = FearColorsDark.Surface,
    border = FearColorsDark.Border,
    hover = FearColorsDark.Hover,
    accent = FearColorsDark.Accent,
    selectedItem = FearColorsDark.SelectedItem,
    textPrimary = FearColorsDark.TextPrimary,
    textSecondary = FearColorsDark.TextSecondary,
    bubbleSelf = FearColorsDark.BubbleSelf,
    bubblePeer = FearColorsDark.BubblePeer,
    bubbleSelfText = FearColorsDark.BubbleSelfText,
    bubblePeerText = FearColorsDark.BubblePeerText,
    unreadBadge = FearColorsDark.UnreadBadge,
)

private val LightColors = FearColors(
    isDark = false,
    background = FearColorsLight.Background,
    chatBackgroundTop = FearColorsLight.ChatBackgroundTop,
    chatBackgroundBottom = FearColorsLight.ChatBackgroundBottom,
    surface = FearColorsLight.Surface,
    border = FearColorsLight.Border,
    hover = FearColorsLight.Hover,
    accent = FearColorsLight.Accent,
    selectedItem = FearColorsLight.SelectedItem,
    textPrimary = FearColorsLight.TextPrimary,
    textSecondary = FearColorsLight.TextSecondary,
    bubbleSelf = FearColorsLight.BubbleSelf,
    bubblePeer = FearColorsLight.BubblePeer,
    bubbleSelfText = FearColorsLight.BubbleSelfText,
    bubblePeerText = FearColorsLight.BubblePeerText,
    unreadBadge = FearColorsLight.UnreadBadge,
)

val LocalFearColors = staticCompositionLocalOf { DarkColors }

private val FearTypography = Typography(
    titleMedium  = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall   = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge    = TextStyle(fontSize = 14.sp),
    bodyMedium   = TextStyle(fontSize = 13.sp),
    bodySmall    = TextStyle(fontSize = 12.sp),
    labelSmall   = TextStyle(fontSize = 11.sp),
)

@Composable
fun FearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val fearColors = if (darkTheme) DarkColors else LightColors

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = fearColors.accent,
            background = fearColors.background,
            surface = fearColors.background,
            onBackground = fearColors.textPrimary,
            onSurface = fearColors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = fearColors.accent,
            background = fearColors.background,
            surface = fearColors.background,
            onBackground = fearColors.textPrimary,
            onSurface = fearColors.textPrimary,
        )
    }

    CompositionLocalProvider(LocalFearColors provides fearColors) {
        MaterialTheme(colorScheme = materialScheme, typography = FearTypography, content = content)
    }
}
