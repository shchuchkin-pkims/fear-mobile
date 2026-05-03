package com.fear.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown the first time the user connects to a given server. Confirms that
 * the implicit "claim @displayName@server" is what they want.
 *
 * Implementation note (Phase B-1): claim is currently local-only — flips
 * a flag in ProfileStore. Phase B-2 will swap the confirm action out for a
 * REGISTER_HANDLE round-trip to the server, surface conflicts, etc.
 */
@Composable
fun RegisterServerDialog(
    handlePreview: String,    // "@evgenii@fear-project.ru"
    server: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Register on $server?") },
        text = {
            Column {
                Text("This is your first connection to $server.")
                Spacer(Modifier.height(10.dp))
                Text("Your handle on this server will be:", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(handlePreview, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Other people on this server will see this name next to your " +
                        "messages. You can change your display name later in " +
                        "your profile.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Register and connect") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
