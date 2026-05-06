package com.fear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fear.data.MessageEntity
import com.fear.ui.theme.LocalFearColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen search over local message history. As the user types, hits a
 * Room LIKE query (debounced 200 ms) and renders the top N matches.
 * Tapping a hit calls [onOpenMessage] so the host can switch to the
 * corresponding chat and scroll to that message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchDialog(
    onSearch: suspend (String) -> List<MessageEntity>,
    onOpenMessage: (MessageEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFearColors.current
    var query   by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<MessageEntity>()) }

    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(200)
        results = withContext(Dispatchers.IO) { onSearch(query) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск сообщений…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor      = colors.textPrimary,
                        unfocusedTextColor    = colors.textPrimary,
                        focusedBorderColor    = colors.accent,
                        unfocusedBorderColor  = colors.border,
                        focusedLabelColor     = colors.accent,
                        unfocusedLabelColor   = colors.textSecondary,
                        cursorColor           = colors.accent,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (query.length < 2)
                        "Введите не менее двух символов"
                    else
                        "${results.size} совпадений",
                    color = colors.textSecondary, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(results, key = { it.id }) { msg ->
                        SearchResultRow(
                            msg = msg,
                            onClick = {
                                onOpenMessage(msg)
                                onDismiss()
                            },
                        )
                        HorizontalDivider(color = colors.border)
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Закрыть", color = colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    msg: MessageEntity,
    onClick: () -> Unit,
) {
    val colors = LocalFearColors.current
    val time = remember(msg.ts) {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(msg.ts))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        Text(
            text = "${msg.senderName} · ${msg.roomId} · $time",
            color = colors.textSecondary, fontSize = 11.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = msg.text,
            color = colors.textPrimary, fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}
