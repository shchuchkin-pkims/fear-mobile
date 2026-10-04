package com.fear.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fear.AppUpdater

@Composable
fun UpdateDialog(
    info: AppUpdater.UpdateInfo,
    currentVersion: String,
    downloadProgress: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            if (downloadProgress != null) {
                Column {
                    Text("Downloading… $downloadProgress%")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text("New version ${info.latestVersion} is available.\n" +
                        "Current: $currentVersion")
            }
        },
        confirmButton = {
            if (downloadProgress == null) {
                TextButton(onClick = onConfirm) { Text("Update") }
            }
        },
        dismissButton = {
            if (downloadProgress == null) {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}
