package com.fear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors

/**
 * First-launch landing. Asks the user for a single display name and that's
 * it — server, room, all the technical bits stay defaulted. Once submitted,
 * the name is persisted to ProfileStore and we never bother the user about
 * it again unless they edit it from their profile.
 *
 * Telegram-equivalent: the "Your name" step right after phone-number entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    initialName: String = "",
    onSubmit: (String) -> Unit,
) {
    val colors = LocalFearColors.current
    var name by remember { mutableStateOf(initialName) }
    val canContinue = name.trim().length in 1..32

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Big monogram placeholder where a real avatar would go.
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
            Text(letter, color = colors.accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Welcome to F.E.A.R.",
            color = colors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a display name. You can change it later in your profile.\n" +
                "Your friends will see this name next to a unique fingerprint.",
            color = colors.textSecondary,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(32) },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.accent,
                focusedLabelColor = colors.accent,
                unfocusedLabelColor = colors.textSecondary,
            ),
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onSubmit(name.trim()) },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent, contentColor = Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
