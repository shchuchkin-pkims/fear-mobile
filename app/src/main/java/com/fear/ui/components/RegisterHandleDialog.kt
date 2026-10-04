package com.fear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * Modal that asks the user for a `nickname` to claim on `serverHost`.
 *
 * The full claim is shown live as `nickname@host` so it's clear the
 * nickname is *server-scoped*, not a global username. On submit the host
 * passes the value to FearViewModel.registerHandle which round-trips
 * REGISTER_HANDLE with the relay; result/error is surfaced via Toast.
 *
 * Validation here is light — the server is the authority for both
 * uniqueness (CONFLICT) and syntactic acceptability (INVALID).
 */
@Composable
fun RegisterHandleDialog(
    serverHost: String,
    initialNickname: String = "",
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var nickname by remember { mutableStateOf(initialNickname) }
    val canSubmit = nickname.trim().length in 3..32

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register a nickname on $serverHost") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick a unique nickname others on this server will use to find you. " +
                        "Different from your display name — display name is just shown next " +
                        "to your messages.",
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.filter { c ->
                        c.isLetterOrDigit() || c == '.' || c == '_' || c == '-'
                    }.take(32) },
                    label = { Text("Nickname") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                if (nickname.isNotEmpty()) {
                    Text(
                        "→ ${nickname.trim()}@$serverHost",
                        color = androidx.compose.ui.graphics.Color(0xFF8AB4F8),
                    )
                }
                Text(
                    "3-32 chars: letters, digits, . _ -. Must start with a letter.",
                    color = androidx.compose.ui.graphics.Color.Gray,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onSubmit(nickname.trim()) },
            ) { Text("Register") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
