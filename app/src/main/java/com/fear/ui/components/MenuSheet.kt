package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
    onTrustedKeys: () -> Unit,
    onToggleTheme: () -> Unit,
    onCheckUpdates: () -> Unit,
    onAbout: () -> Unit,
    onExportIdentity: () -> Unit,
    onImportIdentity: () -> Unit,
    onShowQr: () -> Unit,
    onImportQr: () -> Unit,
    onClearHistory: () -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onContacts: () -> Unit,
) {
    val colors = LocalFearColors.current
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp)) {

            MenuItem(Icons.Filled.Person, "My profile", colors.textPrimary) {
                onProfile(); onDismiss()
            }
            MenuItem(Icons.Filled.PeopleAlt, "Contacts", colors.textPrimary) {
                onContacts(); onDismiss()
            }
            MenuItem(Icons.Filled.Search, "Search messages…", colors.textPrimary) {
                onSearch(); onDismiss()
            }
            MenuItem(Icons.Filled.SystemUpdate, "Check for updates", colors.textPrimary) {
                onCheckUpdates(); onDismiss()
            }
            MenuItem(Icons.Filled.VpnKey, "Trusted keys", colors.textPrimary) {
                onTrustedKeys(); onDismiss()
            }
            MenuItem(Icons.Filled.Backup, "Export identity…", colors.textPrimary) {
                onExportIdentity(); onDismiss()
            }
            MenuItem(Icons.Filled.Restore, "Import identity…", colors.textPrimary) {
                onImportIdentity(); onDismiss()
            }
            MenuItem(Icons.Filled.QrCode, "Show identity as QR…", colors.textPrimary) {
                onShowQr(); onDismiss()
            }
            MenuItem(Icons.Filled.QrCodeScanner, "Import identity from QR…", colors.textPrimary) {
                onImportQr(); onDismiss()
            }
            MenuItem(
                if (colors.isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                if (colors.isDark) "Switch to light theme" else "Switch to dark theme",
                colors.textPrimary,
            ) { onToggleTheme(); onDismiss() }
            MenuItem(Icons.Filled.Info, "About F.E.A.R.", colors.textPrimary) {
                onAbout(); onDismiss()
            }
            MenuItem(Icons.Filled.Delete, "Clear chat history…", colors.textPrimary) {
                onClearHistory(); onDismiss()
            }
            MenuItem(Icons.AutoMirrored.Filled.ExitToApp, "Disconnect", colors.textPrimary) {
                onDisconnect(); onDismiss()
            }
        }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val colors = LocalFearColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = textColor, fontSize = 15.sp)
    }
}
