package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors
import com.fear.ui.viewmodel.FearViewModel.PeerInfo

/**
 * Read-only profile of another chat participant. Opened by tapping their
 * name or avatar in the message bubble.
 *
 * Shows whatever is known: the wire-frame display name, the cryptographic
 * fingerprint when we've received a signed message from them (TOFU), and
 * their @handle@server when they're already in our contact list.
 *
 * Action: 'Add to contacts' is offered when we have a pk but no contact
 * yet. Saving a real contact uses the Add-Contact flow on the host
 * activity (currently nickname-by-handle); the button here is a shortcut
 * that piggy-backs on the known pk and current display name.
 */
@Composable
fun PeerProfileDialog(
    info: PeerInfo,
    onDismiss: () -> Unit,
    onAddContact: ((PeerInfo) -> Unit)? = null,
    onOpenChat: ((PeerInfo) -> Unit)? = null,
) {
    val colors = LocalFearColors.current
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile") },
        text = {
            Column {
                // Avatar + display name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(info.displayName.firstOrNull()?.uppercase() ?: "?",
                             color = colors.accent, fontSize = 24.sp,
                             fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(info.displayName,
                             color = colors.textPrimary, fontSize = 18.sp,
                             fontWeight = FontWeight.SemiBold)
                        if (info.fpshort != null) {
                            Text("${info.displayName}#${info.fpshort}",
                                 color = colors.textSecondary, fontSize = 12.sp)
                        }
                        if (info.verified) {
                            Text("verified", color = Color(0xFF4CAF50),
                                 fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = colors.border)
                Spacer(Modifier.height(12.dp))

                if (info.handle != null && info.server != null) {
                    LabelValue("Handle", "${info.handle}@${info.server}", ctx)
                    Spacer(Modifier.height(8.dp))
                }
                if (info.fullFingerprint != null) {
                    LabelValue("Identity fingerprint", info.fullFingerprint, ctx, mono = true)
                    Spacer(Modifier.height(8.dp))
                }
                if (info.fullFingerprint == null) {
                    Text(
                        "No signed messages received from this peer yet — " +
                            "their cryptographic identifier is not known.",
                        color = colors.textSecondary, fontSize = 12.sp,
                    )
                }

                if (info.identityPkB64 != null && !info.isContact && onAddContact != null) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = colors.border)
                    Spacer(Modifier.height(12.dp))
                    Text("This peer is not in your contacts.",
                         color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            // Primary action: open a 1-on-1 chat when we have a pk for them.
            if (info.identityPkB64 != null && onOpenChat != null) {
                TextButton(onClick = { onOpenChat(info); onDismiss() }) {
                    Text("Open chat")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            when {
                // 'Add to contacts' offered alongside Open chat for unsaved peers
                info.identityPkB64 != null && !info.isContact && onAddContact != null ->
                    TextButton(onClick = { onAddContact(info); onDismiss() }) {
                        Text("Add to contacts")
                    }
                info.identityPkB64 != null && onOpenChat != null ->
                    TextButton(onClick = onDismiss) { Text("Close") }
                else -> {}
            }
        },
    )
}

@Composable
private fun LabelValue(
    label: String,
    value: String,
    ctx: android.content.Context,
    mono: Boolean = false,
) {
    val colors = LocalFearColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textSecondary, fontSize = 11.sp)
            Text(
                value,
                color = colors.textPrimary,
                fontSize = if (mono) 12.sp else 14.sp,
                fontWeight = if (mono) FontWeight.Normal else FontWeight.Medium,
            )
        }
        IconButton(onClick = {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }) {
            Icon(Icons.Filled.ContentCopy, "Copy",
                 tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}
