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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fear.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen search over local message history. As the user types, hits a
 * Room LIKE query (debounced 200ms) and renders the top N matches sorted
 * newest-first. Tapping a hit dismisses; integration with chat-jumping
 * comes when there's more than one room (Phase B).
 */
@Composable
fun SearchDialog(
    onSearch: suspend (String) -> List<MessageEntity>,
    onDismiss: () -> Unit,
) {
    var query   by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<MessageEntity>()) }

    // Debounced search — don't hammer Room on every keystroke.
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
                .background(Color(0xFF101216))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search messages…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                if (query.length < 2) {
                    Text("Type at least 2 characters",
                         color = Color(0xFFB0B7BE), fontSize = 12.sp)
                } else {
                    Text("${results.size} match${if (results.size == 1) "" else "es"}",
                         color = Color(0xFFB0B7BE), fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(results, key = { it.id }) { msg ->
                        SearchResultRow(msg)
                        HorizontalDivider(color = Color(0xFF22252B))
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SearchResultRow(msg: MessageEntity) {
    val time = remember(msg.ts) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(msg.ts))
    }
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(
            "${msg.senderName}  ·  ${msg.roomId}  ·  $time",
            color = Color(0xFFB0B7BE), fontSize = 11.sp,
        )
        Text(msg.text, color = Color.White, fontSize = 14.sp,
             fontWeight = FontWeight.Normal)
    }
}
