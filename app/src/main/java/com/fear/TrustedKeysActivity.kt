package com.fear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.FearTheme
import com.fear.ui.theme.LocalFearColors

/**
 * Экран управления базой известных ключей собеседников (TOFU).
 * Переписан с XML/Activity на Compose, чтобы соответствовать основной
 * теме приложения (LocalFearColors), включая корректное отображение в
 * светлой теме.
 */
class TrustedKeysActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identityManager = IdentityManager(this)
        setContent {
            FearTheme(darkTheme = ThemeManager.isDark(this)) {
                TrustedKeysScreen(
                    identityManager = identityManager,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun TrustedKeysScreen(
    identityManager: IdentityManager,
    onBack: () -> Unit,
) {
    val colors = LocalFearColors.current
    var keys by remember { mutableStateOf(identityManager.loadKnownKeys()) }
    var pendingDelete by remember { mutableStateOf<IdentityManager.KnownKey?>(null) }
    val myFp = remember(identityManager) {
        identityManager.getPublicKey()?.let { identityManager.fingerprint(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                     tint = colors.textSecondary)
            }
            Text(
                text = "Trusted keys",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = colors.border)

        // Свой fingerprint
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Your identity fingerprint", color = colors.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = myFp ?: "No identity key generated",
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        HorizontalDivider(color = colors.border)

        if (keys.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "База доверенных ключей пуста. Ключи появятся здесь по мере того, как вы получите подписанные сообщения от других пользователей.",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(keys, key = { it.name + ":" + it.pk.contentHashCode() }) { key ->
                    TrustedKeyRow(
                        name        = key.name,
                        fingerprint = identityManager.fingerprint(key.pk),
                        verified    = key.verified,
                        onToggle    = {
                            identityManager.setVerified(key.name, key.pk, !key.verified)
                            keys = identityManager.loadKnownKeys()
                        },
                        onDelete    = { pendingDelete = key },
                    )
                    HorizontalDivider(color = colors.border)
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить ключ") },
            text  = {
                Column {
                    Text("Удалить доверенный ключ для «${toDelete.name}»?")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = identityManager.fingerprint(toDelete.pk),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "В следующий раз пользователь будет восприниматься как новый контакт.",
                        color = colors.textSecondary, fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    identityManager.deleteKnownKey(toDelete.name, toDelete.pk)
                    keys = identityManager.loadKnownKeys()
                    pendingDelete = null
                }) { Text("Удалить", color = Color(0xFFE57373)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun TrustedKeyRow(
    name: String,
    fingerprint: String,
    verified: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalFearColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Аватар
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.firstOrNull()?.uppercase() ?: "?",
                 color = colors.accent, fontSize = 16.sp,
                 fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textPrimary, fontSize = 15.sp,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                text = fingerprint,
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            val statusColor = if (verified) Color(0xFF66BB6A) else Color(0xFF42A5F5)
            val statusText  = if (verified) "✓ Verified" else "TOFU trusted"
            Text(statusText, color = statusColor, fontSize = 11.sp,
                 fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = onToggle,
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (verified) colors.surface else colors.accent,
                    contentColor   = if (verified) colors.textPrimary else Color.White,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(if (verified) "Unverify" else "Verify", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text("Delete", fontSize = 11.sp, color = Color(0xFFE57373))
            }
        }
    }
}
