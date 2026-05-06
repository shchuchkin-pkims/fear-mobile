package com.fear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fear.ui.components.Avatar
import com.fear.ui.components.ChatListRow
import com.fear.ui.components.MessageBubble
import com.fear.ui.theme.LocalFearColors
import com.fear.ui.viewmodel.ChatEntry
import com.fear.ui.viewmodel.ChatKind
import com.fear.ui.viewmodel.ChatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    chatList: List<ChatEntry>,
    onChatSelected: (ChatEntry) -> Unit,
    onAddNew: () -> Unit,
    onSendMessage: (String) -> Unit,
    onMenuClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onAttach: () -> Unit,
    onBack: () -> Unit,
    onSenderTap: (String) -> Unit = {},
    onHeaderTap: (ChatEntry) -> Unit = {},
    onScrollHandled: () -> Unit = {},
) {
    val colors = LocalFearColors.current

    // Phone-first layout: when a chat is open, show chat full screen.
    // Side panel will come back for tablets in a later session.
    if (state.activeChatId == null) {
        SidebarOnly(
            chats = chatList,
            activeChatId = state.activeChatId,
            onChatSelected = onChatSelected,
            onMenuClick = onMenuClick,
            onAddNew = onAddNew,
        )
    } else {
        val activeChat = chatList.firstOrNull { it.id == state.activeChatId }
            ?: state.chats.firstOrNull { it.id == state.activeChatId }
            ?: ChatEntry(id = state.activeChatId, title = state.activeChatId)
        ChatPane(
            chatTitle = activeChat.title,
            statusText = state.statusText,
            messages = state.messages,
            pendingScrollToTs = state.pendingScrollToTs,
            onScrollHandled = onScrollHandled,
            onSendMessage = onSendMessage,
            onMenuClick = onMenuClick,
            onAudioCall = onAudioCall,
            onVideoCall = onVideoCall,
            onAttach = onAttach,
            onBack = onBack,
            onSenderTap = onSenderTap,
            onHeaderTap = { onHeaderTap(activeChat) },
        )
    }
}

@Composable
private fun SidebarOnly(
    chats: List<ChatEntry>,
    activeChatId: String?,
    onChatSelected: (ChatEntry) -> Unit,
    onMenuClick: () -> Unit,
    onAddNew: () -> Unit,
) {
    val colors = LocalFearColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header (search + hamburger)
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = colors.textSecondary)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Search", color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)

            if (chats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats yet — tap + to start one",
                         color = colors.textSecondary)
                }
            } else {
                val dms    = chats.filter { it.kind == ChatKind.DM }
                val groups = chats.filter { it.kind == ChatKind.GROUP }
                var dmExpanded    by remember { mutableStateOf(true) }
                var groupExpanded by remember { mutableStateOf(true) }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    sectionHeader(
                        label = "Контакты",
                        count = dms.size,
                        expanded = dmExpanded,
                        onToggle = { dmExpanded = !dmExpanded },
                    )
                    if (dmExpanded) chatItems(dms, activeChatId, onChatSelected)

                    sectionHeader(
                        label = "Группы",
                        count = groups.size,
                        expanded = groupExpanded,
                        onToggle = { groupExpanded = !groupExpanded },
                    )
                    if (groupExpanded) chatItems(groups, activeChatId, onChatSelected)
                }
            }
        }

        FloatingActionButton(
            onClick = onAddNew,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = colors.accent,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New chat",
                 tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

private fun LazyListScope.sectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) = item(key = "hdr-$label") {
    val colors = LocalFearColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Свернуть" else "Развернуть",
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$label  ($count)",
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

private fun LazyListScope.chatItems(
    chats: List<ChatEntry>,
    activeChatId: String?,
    onChatSelected: (ChatEntry) -> Unit,
) {
    if (chats.isEmpty()) {
        item(key = "empty-${chats.hashCode()}") {
            // Empty placeholder for the section so the toggle still gives
            // meaningful feedback. Subtle text, no gap.
        }
    } else {
        items(items = chats, key = { c: ChatEntry -> c.id }) { chat ->
            ChatListRow(
                entry = chat,
                selected = chat.id == activeChatId,
                onClick = { onChatSelected(chat) },
            )
        }
    }
}

@Composable
private fun ChatPane(
    chatTitle: String,
    statusText: String,
    messages: List<com.fear.ui.viewmodel.ChatMessage>,
    pendingScrollToTs: Long?,
    onScrollHandled: () -> Unit,
    onSendMessage: (String) -> Unit,
    onMenuClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onAttach: () -> Unit,
    onBack: () -> Unit,
    onSenderTap: (String) -> Unit = {},
    onHeaderTap: () -> Unit = {},
) {
    val colors = LocalFearColors.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when a new message arrives — но не если ждём
    // прокрутку к найденному через поиск сообщению.
    LaunchedEffect(messages.size) {
        if (pendingScrollToTs == null && messages.isNotEmpty())
            listState.animateScrollToItem(messages.size - 1)
    }

    // Telegram-style: при появлении клавиатуры прокручиваем к последнему
    // сообщению, чтобы оно не оставалось «под» клавиатурой. Слушаем
    // изменения IME inset через WindowInsets.ime.
    val imeBottom = WindowInsets.ime
        .getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && pendingScrollToTs == null && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Если SearchDialog попросил прокрутить к конкретному ts — найдём
    // его индекс и прыгнем туда. Если сообщение ещё не в текущем
    // messages (история ещё грузится), повторим попытку, когда
    // messages.size изменится.
    LaunchedEffect(pendingScrollToTs, messages.size) {
        val target = pendingScrollToTs ?: return@LaunchedEffect
        val idx = messages.indexOfFirst { it.timestamp.toEpochMilli() == target }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            onScrollHandled()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()           // pushes the whole column up by IME height — header stays pinned at TOP of the visible area
            .statusBarsPadding(),   // keeps header below the system status bar
    ) {
        // Chat header — fixed at top, never scrolls
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                     tint = colors.textSecondary)
            }
            // Аватар + название + статус — единая кликабельная зона.
            // Тап → onHeaderTap (профиль собеседника для ЛС, список
            // участников для группы).
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onHeaderTap)
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(seed = chatTitle, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(chatTitle, color = colors.textPrimary,
                         fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(statusText, color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onAudioCall) { Icon(Icons.Filled.Call, "Audio call", tint = colors.textSecondary) }
            IconButton(onClick = onVideoCall) { Icon(Icons.Filled.Videocam, "Video call", tint = colors.textSecondary) }
            IconButton(onClick = onMenuClick) { Icon(Icons.Filled.MoreVert, "More", tint = colors.textSecondary) }
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        // Messages — solid in dark, gradient in light
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    if (colors.isDark) androidx.compose.ui.graphics.SolidColor(colors.chatBackgroundTop)
                    else Brush.verticalGradient(listOf(colors.chatBackgroundTop, colors.chatBackgroundBottom)),
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                // Используем индекс как часть ключа: timestamp+sender может
                // совпадать у системных сообщений, отправленных в одну
                // миллисекунду (например, «Disconnected.» и «switching room…»),
                // и LazyColumn падает с IllegalArgumentException.
                itemsIndexed(messages, key = { i, m ->
                    "$i-${m.timestamp}-${m.sender}-${m.text.hashCode()}"
                }) { _, msg ->
                    MessageBubble(msg, onSenderClick = onSenderTap)
                }
            }
        }

        HorizontalDivider(color = colors.border, thickness = 1.dp)
        // Input area — sits at bottom of (resized) column, just above keyboard
        InputBar(
            onSend = onSendMessage,
            onAttach = onAttach,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFearColors.current
    var text by remember { mutableStateOf("") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAttach) { Icon(Icons.Filled.AttachFile, "Attach", tint = colors.textSecondary) }
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Write a message…", color = colors.textSecondary) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 140.dp)
                .clip(RoundedCornerShape(20.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
            ),
            singleLine = false,
            maxLines = 5,
        )
        IconButton(
            onClick = {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    onSend(trimmed)
                    text = ""
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = colors.accent)
        }
    }
}
