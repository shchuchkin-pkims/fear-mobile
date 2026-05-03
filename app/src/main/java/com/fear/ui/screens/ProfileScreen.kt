package com.fear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors

/**
 * "My Profile". Two distinct identity layers shown separately:
 *
 *   • Display name — global, just the label next to my chat messages.
 *   • Handles      — per-server `nickname@server` claims registered on the
 *                    relay (Phase B-2). User adds these explicitly via
 *                    "Register handle on a server".
 *
 * Plus the cryptographic identity (BLAKE2b fingerprint of identity_pk)
 * and backup actions (Export / Show QR).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    displayName: String,
    setDisplayName: (String) -> Unit,
    handles: Map<String, String>,             // server → nickname
    fpshort: String?,
    fullFingerprint: String?,
    onBack: () -> Unit,
    onAddHandle: () -> Unit,
    onRemoveHandle: (server: String) -> Unit,
    onExportIdentity: () -> Unit,
    onShowQr: () -> Unit,
) {
    val colors = LocalFearColors.current
    val ctx = LocalContext.current

    var editing  by remember { mutableStateOf(false) }
    var draftName by remember(displayName) { mutableStateOf(displayName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar with back button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                     tint = colors.textPrimary)
            }
            Text("My Profile", color = colors.textPrimary, fontSize = 18.sp,
                 fontWeight = FontWeight.SemiBold)
        }

        // ── Header: avatar + display name + fpshort ────────────
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(108.dp).clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                val letter = displayName.firstOrNull()?.uppercase() ?: "?"
                Text(letter, color = colors.accent, fontSize = 44.sp,
                     fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(14.dp))
            if (editing) {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(32) },
                    singleLine = true,
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor = colors.accent,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = {
                        draftName = displayName; editing = false
                    }) { Text("Cancel") }
                    Button(
                        onClick = {
                            val v = draftName.trim()
                            if (v.isNotEmpty()) {
                                setDisplayName(v); editing = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) { Text("Save") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName.ifBlank { "(no name)" },
                         color = colors.textPrimary, fontSize = 22.sp,
                         fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, "Edit name",
                             tint = colors.textSecondary,
                             modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Cryptographic short id under the name.
            if (fpshort != null) {
                Text("$displayName#$fpshort",
                     color = colors.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Display name is just a label shown next to your messages.",
                color = colors.textSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        HorizontalDivider(color = colors.border)

        // ── Handles section ─────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nicknames on servers", color = colors.textSecondary,
                 fontSize = 12.sp, fontWeight = FontWeight.Medium,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = onAddHandle) { Text("Register…") }
        }
        if (handles.isEmpty()) {
            Text(
                "No nicknames registered. Tap 'Register…' above to claim " +
                    "`yournickname@yourserver` so others can find you.",
                color = colors.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            for ((server, nick) in handles) {
                HandleRow(server = server, nickname = nick,
                          onRemove = { onRemoveHandle(server) })
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.border)

        // ── Cryptographic identity ──────────────────────────────
        SectionLabel("Cryptographic identity")
        if (fullFingerprint != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Fingerprint", color = colors.textSecondary, fontSize = 11.sp)
                    Text(fullFingerprint, color = colors.textPrimary,
                         fontSize = 13.sp,
                         fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "FEAR fingerprint", fullFingerprint))
                    android.widget.Toast.makeText(
                        ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, "Copy",
                         tint = colors.textSecondary,
                         modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.border)

        // ── Actions ─────────────────────────────────────────────
        SectionLabel("Backup")
        ActionRow("Export identity to .fbk file", onClick = onExportIdentity)
        ActionRow("Show identity as QR",          onClick = onShowQr)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalFearColors.current
    Text(text, color = colors.textSecondary, fontSize = 12.sp,
         fontWeight = FontWeight.Medium,
         modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    val colors = LocalFearColors.current
    Box(modifier = Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, color = colors.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun HandleRow(server: String, nickname: String, onRemove: () -> Unit) {
    val colors = LocalFearColors.current
    val ctx = LocalContext.current
    val full = "$nickname@$server"
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("handle", full))
            android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }) {
            Text(full, color = colors.textPrimary, fontSize = 14.sp)
            Text("Tap to copy", color = colors.textSecondary, fontSize = 11.sp)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "Remove from device",
                 tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}
