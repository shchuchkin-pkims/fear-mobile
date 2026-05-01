package com.fear.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.theme.LocalFearColors

@Composable
fun AboutDialog(version: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = LocalFearColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("F.E.A.R. Messenger") },
        text = {
            Column {
                Text(
                    "Fully Encrypted Anonymous Routing",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                Text("Version $version", color = colors.textSecondary, fontSize = 12.sp)

                Spacer(Modifier.height(12.dp))
                Text(
                    "End-to-end encrypted text, voice and video over a self-hostable " +
                            "TCP relay. Open source, decentralised, no phone numbers.",
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(14.dp))
                Text("Author", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Evgenii Shchuchkin", fontSize = 13.sp)
                Link("shchuchkin-pkims@yandex.ru", "mailto:shchuchkin-pkims@yandex.ru", ctx)

                Spacer(Modifier.height(10.dp))
                Text("Links", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Link("fear-project.ru", "https://fear-project.ru/", ctx)
                Link("github.com/shchuchkin-pkims/fear",        "https://github.com/shchuchkin-pkims/fear",        ctx)
                Link("github.com/shchuchkin-pkims/fear-mobile", "https://github.com/shchuchkin-pkims/fear-mobile", ctx)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun Link(label: String, url: String, ctx: Context) {
    val colors = LocalFearColors.current
    Text(
        text = label,
        color = colors.accent,
        fontSize = 13.sp,
        modifier = Modifier.clickable {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
    )
}
