package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.AvatarPalette
import java.security.MessageDigest

@Composable
fun Avatar(
    seed: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val color = colorForSeed(seed)
    val initials = initialsFor(seed)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value / 2.5f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun colorForSeed(seed: String): Color {
    if (seed.isEmpty()) return AvatarPalette[0]
    val md = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
    val idx = (md[0].toInt() and 0xFF) % AvatarPalette.size
    return AvatarPalette[idx]
}

private fun initialsFor(name: String): String {
    if (name.isBlank()) return "?"
    val parts = name.trim().split(Regex("\\s+"))
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        else -> name.take(2).uppercase()
    }
}
