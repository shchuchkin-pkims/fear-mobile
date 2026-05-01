package com.fear.ui.theme

import androidx.compose.ui.graphics.Color

// Dark palette — neutral grays (matches the desktop redesign).
object FearColorsDark {
    val Background      = Color(0xFF2A2C30)   // window, sidebar, header, input strip
    val ChatBackground  = Color(0xFF1B1D20)   // dark center area
    val Surface         = Color(0xFF3A3D42)   // pills, fields, menus
    val Border          = Color(0xFF1A1C1E)
    val Hover           = Color(0xFF34373B)
    val Accent          = Color(0xFF5288C1)   // selected, send button hover
    val SelectedItem    = Color(0xFF2B5278)   // selected chat in list
    val TextPrimary     = Color(0xFFFFFFFF)
    val TextSecondary   = Color(0xFF8A8D92)
    val BubbleSelf      = Color(0xFF2B5278)
    val BubblePeer      = Color(0xFF3A3D42)
    val BubbleSelfText  = Color(0xFFFFFFFF)
    val BubblePeerText  = Color(0xFFFFFFFF)
    val UnreadBadge     = Color(0xFF5288C1)
}

// Light palette — Telegram light style.
object FearColorsLight {
    val Background          = Color(0xFFFFFFFF)
    val ChatBackgroundTop   = Color(0xFFE8F5DC)   // gradient top — pale green
    val ChatBackgroundBottom = Color(0xFFB7DDA0)  // gradient bottom — green
    val Surface             = Color(0xFFF4F4F5)
    val Border              = Color(0xFFDADCE0)
    val Hover               = Color(0xFFF0F0F1)
    val Accent              = Color(0xFF2AABEE)
    val SelectedItem        = Color(0xFF2AABEE)
    val TextPrimary         = Color(0xFF000000)
    val TextSecondary       = Color(0xFF707579)
    val BubbleSelf          = Color(0xFFEFFDDE)
    val BubblePeer          = Color(0xFFFFFFFF)
    val BubbleSelfText      = Color(0xFF000000)
    val BubblePeerText      = Color(0xFF000000)
    val UnreadBadge         = Color(0xFF4DCD5E)
}

// Hash-based avatar palette (matches desktop).
val AvatarPalette = listOf(
    Color(0xFFE17076), Color(0xFFEDA86C), Color(0xFFA695E7),
    Color(0xFF7BC862), Color(0xFF65AADD), Color(0xFFEE7AAE),
    Color(0xFF6EC9CB), Color(0xFFFAA774),
)
