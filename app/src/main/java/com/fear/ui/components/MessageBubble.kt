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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.AvatarPalette
import com.fear.ui.theme.LocalFearColors
import com.fear.ui.viewmodel.ChatMessage
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MessageBubble(
    msg: ChatMessage,
    modifier: Modifier = Modifier,
    onSenderClick: ((String) -> Unit)? = null,
) {
    val colors = LocalFearColors.current
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    if (msg.isSystem) {
        Row(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center) {
            Text(
                text = msg.text,
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (msg.fromSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!msg.fromSelf) {
            val avatarMod = if (onSenderClick != null && msg.sender.isNotEmpty())
                Modifier.clickable { onSenderClick(msg.sender) } else Modifier
            Box(modifier = avatarMod) {
                Avatar(seed = msg.sender, size = 32.dp)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (msg.fromSelf) colors.bubbleSelf else colors.bubblePeer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!msg.fromSelf && msg.sender.isNotEmpty()) {
                val nameMod = if (onSenderClick != null)
                    Modifier.clickable { onSenderClick(msg.sender) } else Modifier
                Text(
                    text = msg.sender,
                    color = senderColor(msg.sender),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = nameMod,
                )
            }
            Text(
                text = msg.text,
                color = if (msg.fromSelf) colors.bubbleSelfText else colors.bubblePeerText,
                fontSize = 14.sp,
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                Text(
                    text = timeFmt.format(msg.timestamp) +
                            if (msg.fromSelf) (if (msg.delivered) "  ✓✓" else "  ✓") else "",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun senderColor(name: String): androidx.compose.ui.graphics.Color {
    if (name.isEmpty()) return AvatarPalette[0]
    val md = MessageDigest.getInstance("MD5").digest(name.toByteArray())
    return AvatarPalette[(md[0].toInt() and 0xFF) % AvatarPalette.size]
}
