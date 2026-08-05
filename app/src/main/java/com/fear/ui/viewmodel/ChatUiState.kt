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
    /* Сервер по умолчанию - Меппел. Запомненный выбор человека важнее и
     * подставляется поверх этого значения при запуске. */
    val host: String = "77.221.145.132",
    val port: Int = 8888,
    val room: String = "general",          // default public room (general channel)
    val name: String = "",
    val key: String = "",
    val mode: ConnectMode = ConnectMode.AUTO, // one-button connect: tries JOIN, falls back to CREATE
)

enum class ChatKind {
    GROUP,                  // multi-user room — icon 👥
    DM,                     // 1-on-1 with a contact — icon 👤, id starts with "dm:"
}

data class ChatEntry(
    val id: String,
    val title: String,
    val preview: String = "",
    val lastActivity: Instant = Instant.EPOCH,
    val unread: Int = 0,
    val kind: ChatKind = ChatKind.GROUP,
    /** For DM entries: the contact pk (URL-safe b64) so the row can drive
     *  openDmWithPk without re-parsing the dm id. Null for GROUP rooms. */
    val peerPkB64: String? = null,
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

/**
 * A call somebody in the room has announced and we have not answered yet.
 *
 * The announcement carries the call_id every media key of that call is bound
 * to, so answering means joining *that* call rather than starting a second
 * one nobody else is keyed to.
 */
data class IncomingCall(
    val fromUser: String,
    val video: Boolean,
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
    /** Non-null while a room member is calling and we have not answered. */
    val incomingCall: IncomingCall? = null,
    /** Если установлено — UI чата должен прокрутить список к сообщению с
     *  этим timestamp и кратко подсветить его. Используется при переходе
     *  по результату поиска. После применения сбрасывается ViewModel-ом. */
    val pendingScrollToTs: Long? = null,
    /** Список participants комнаты, последние известные значения от
     *  сервера. Используется заголовком чата для группового профиля. */
    val participants: List<String> = emptyList(),
)
