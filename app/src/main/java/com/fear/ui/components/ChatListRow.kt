package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors
import com.fear.ui.viewmodel.ChatEntry
import com.fear.ui.viewmodel.ChatKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun ChatListRow(
    entry: ChatEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFearColors.current
    val bg = if (selected) colors.selectedItem else androidx.compose.ui.graphics.Color.Transparent
    val titleColor = if (selected) androidx.compose.ui.graphics.Color.White else colors.textPrimary
    val secondaryColor = if (selected) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f) else colors.textSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(seed = entry.title, size = 48.dp)
            // Small overlay badge in the corner that distinguishes a DM
            // (👤) from a group room (👥). Uses Material icons because the
            // Avatar already uses an initial, so the badge is the type cue.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (entry.kind == ChatKind.DM)
                        Icons.Filled.Person else Icons.Filled.Group,
                    contentDescription = if (entry.kind == ChatKind.DM) "Direct" else "Group",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    color = titleColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                if (entry.lastActivity.toEpochMilli() > 0) {
                    Text(
                        text = formatRelativeTime(entry.lastActivity),
                        color = secondaryColor,
                        fontSize = 11.sp,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = entry.preview.ifEmpty { " " },
                    color = secondaryColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (entry.unread > 0) {
                    Text(
                        text = if (entry.unread > 99) "99+" else entry.unread.toString(),
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.unreadBadge)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(instant: java.time.Instant): String {
    val zone = ZoneId.systemDefault()
    val date = instant.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val daysDiff = ChronoUnit.DAYS.between(date, today)
    return when {
        date == today -> DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)
        daysDiff < 7  -> DateTimeFormatter.ofPattern("EEE").withZone(zone).format(instant)
        else          -> DateTimeFormatter.ofPattern("dd.MM").withZone(zone).format(instant)
    }
}
