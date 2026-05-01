package com.fear.ui.viewmodel

import com.fear.FearClient
import java.time.Instant

enum class ConnectMode {
    AUTO,         // Try JOIN first; if no one's there, fall back to CREATE.
    CREATE_ROOM,  // Force-create with a fresh key.
    JOIN_ROOM,    // ECDH-join an existing room.
    MANUAL_KEY,   // Connect with a pasted key.
}

data class ConnectFormState(
    val host: String = "fear-project.ru",
    val port: Int = 8888,
    val room: String = "guest",            // default public testing room
    val name: String = "",
    val key: String = "",
    val mode: ConnectMode = ConnectMode.AUTO, // one-button connect: tries JOIN, falls back to CREATE
)

data class ChatEntry(
    val id: String,
    val title: String,
    val preview: String = "",
    val lastActivity: Instant = Instant.EPOCH,
    val unread: Int = 0,
)

data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Instant,
    val fromSelf: Boolean = false,
    val delivered: Boolean = false,
    val isSystem: Boolean = false,
)

data class CallState(
    val active: Boolean = false,
    val remoteUser: String = "",  // empty in relay mode (broadcast to room)
    val rttMs: Int = 0,
)

data class ChatUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val activeChatId: String? = null,
    val chats: List<ChatEntry> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val statusText: String = "",
    val errorBanner: String? = null,
    val call: CallState = CallState(),
)
