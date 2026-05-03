package com.fear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * "Add contact" — minimal Phase B-3 form: nickname + server. Optional
 * display name (defaults to the nickname). Phase B-5 will fold this
 * into the unified "+ new chat" sheet alongside QR / fpshort flows.
 *
 * Submits via FearViewModel.addContactByHandle which:
 *   1. LOOKUP_HANDLE the relay → identity_pk
 *   2. upsert into local Room
 *   3. schedule encrypted-blob push
 */
@Composable
fun AddContactDialog(
    defaultServer: String,
    onDismiss: () -> Unit,
    onSubmit: (nickname: String, server: String, displayName: String) -> Unit,
) {
    var nickname    by remember { mutableStateOf("") }
    var server      by remember { mutableStateOf(defaultServer) }
    var displayName by remember { mutableStateOf("") }
    val canSubmit = nickname.trim().length in 3..32 && server.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Look up someone by their server nickname.")
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.filter { c ->
                        c.isLetterOrDigit() || c in "._-"
                    }.take(32) },
                    label = { Text("Nickname") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = { Text("Server") },
                    singleLine = true,
                )
                if (nickname.isNotEmpty() && server.isNotEmpty()) {
                    Text("→ ${nickname.trim()}@$server",
                         color = androidx.compose.ui.graphics.Color(0xFF8AB4F8))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(32) },
                    label = { Text("Display name (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onSubmit(nickname.trim(), server.trim(), displayName.trim()) },
            ) { Text("Look up & add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
