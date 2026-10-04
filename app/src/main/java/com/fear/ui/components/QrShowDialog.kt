package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-bleed QR display. The QR fills the screen width (a tiny side-padding
 * keeps it off the bezel) so a phone can scan it from across the room.
 *
 * Two actions:
 *   - Save as PNG  → asks the host for a SAF Uri and saves the QR bitmap
 *   - Close        → dismiss
 *
 * `onSavePng` receives the same `qrText` so the caller can re-render the QR
 * bitmap deterministically and write it to the chosen Uri.
 */
@Composable
fun QrShowDialog(
    title: String,
    caption: String,
    qrText: String,
    onSavePng: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,    // allow full-screen width
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101216))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = caption,
                    color = Color(0xFFB0B7BE),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                // Big square QR — fills available width
                QrCodeView(text = qrText, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                // Pushed-to-bottom button row
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onSavePng(qrText) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save as PNG") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Close") }
            }
        }
    }
}
