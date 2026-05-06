package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors

/**
 * Информационный диалог о групповой комнате. Открывается тапом по
 * аватару/названию в верхней полосе чата. Содержит:
 *   - название комнаты,
 *   - количество участников,
 *   - список присутствующих сейчас (по последнему USER_LIST от сервера).
 *
 * Тап по строке участника зовёт onPeerTap(name) — host (ComposeMainActivity)
 * откроет тот же PeerProfileDialog, что и при клике по сообщению этого
 * пользователя в чате.
 */
@Composable
fun GroupParticipantsDialog(
    roomTitle: String,
    participants: List<String>,
    onPeerTap: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFearColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(roomTitle, color = colors.textPrimary, fontSize = 18.sp,
                     fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${participants.size} участник" +
                           pluralSuffix(participants.size),
                    color = colors.textSecondary, fontSize = 12.sp,
                )
            }
        },
        text = {
            if (participants.isEmpty()) {
                Text("Сервер ещё не прислал список участников.",
                     color = colors.textSecondary, fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    items(participants, key = { it }) { name ->
                        ParticipantRow(name) {
                            onPeerTap(name)
                            onDismiss()
                        }
                        HorizontalDivider(color = colors.border)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun ParticipantRow(name: String, onClick: () -> Unit) {
    val colors = LocalFearColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.firstOrNull()?.uppercase() ?: "?",
                 color = colors.accent, fontSize = 14.sp,
                 fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, color = colors.textPrimary, fontSize = 15.sp)
    }
}

private fun pluralSuffix(n: Int): String {
    val mod10  = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11                     -> ""
        mod10 in 2..4 && (mod100 < 12 || mod100 > 14)  -> "а"
        else                                           -> "ов"
    }
}
