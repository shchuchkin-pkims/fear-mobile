package com.fear.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fear.FearClient
import com.fear.Message
import com.fear.data.AppDatabase
import com.fear.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "FearViewModel"
private const val PREFS_NAME = "fear_prefs"
private const val KEY_HOST = "connect.host"
private const val KEY_PORT = "connect.port"
private const val KEY_ROOM = "connect.room"
private const val KEY_NAME = "connect.name"
private const val AUTO_JOIN_TIMEOUT_MS = 5000

class FearViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dao   = AppDatabase.get(app).messageDao()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(loadFormFromPrefs())
    val form: StateFlow<ConnectFormState> = _form.asStateFlow()

    private val seenPeers = mutableSetOf<String>()
    private var reportedCount = 0

    /** True iff we're inside the JOIN attempt of an AUTO connect — used to fall
     *  back to CREATE if JOIN times out. */
    private var pendingAutoJoin = false

    private val listener = object : FearClient.FearClientListener {
        override fun onConnected() {
            pendingAutoJoin = false
            saveFormToPrefs()
            val f = _form.value
            _uiState.update {
                it.copy(
                    isConnected = true,
                    isConnecting = false,
                    activeChatId = f.room,
                    chats = listOf(ChatEntry(
                        id = f.room, title = f.room,
                        preview = "", lastActivity = Instant.now())),
                    messages = emptyList(),
                    statusText = "online",
                    errorBanner = null,
                )
            }
            seenPeers.clear()
            reportedCount = 0
            recomputeStatus()
            // Load persisted history for this room (Phase A §9a) — fire and
            // forget; the UI re-renders when state updates.
            loadHistoryForRoom(f.room)
        }

        override fun onDisconnected() {
            seenPeers.clear()
            reportedCount = 0
            _uiState.update {
                it.copy(
                    isConnected = false,
                    isConnecting = false,
                    activeChatId = null,
                    statusText = "",
                    messages = it.messages + ChatMessage(
                        sender = "", text = "Disconnected.", timestamp = Instant.now(), isSystem = true,
                    ),
                )
            }
        }

        override fun onMessageReceived(message: Message) {
            val ownName = _form.value.name
            if (message.sender == ownName) return

            // Strip identity-status prefix that FearClient prepends ([V]/[T]/[?]/[!]).
            val stripped = message.content.replace(Regex("^\\[[VT?!]] "), "")

            val msg = ChatMessage(
                sender    = message.sender,
                text      = stripped,
                timestamp = Instant.ofEpochMilli(message.timestamp),
                fromSelf  = false,
                delivered = true,
            )
            _uiState.update { it.copy(messages = it.messages + msg) }
            persistMessage(msg)
            if (message.sender.isNotBlank() && message.sender != "system") {
                seenPeers.add(message.sender)
                recomputeStatus()
            }
        }

        override fun onContactsUpdated(contacts: List<String>) {
            reportedCount = contacts.size
            for (c in contacts) {
                if (c.isNotBlank() && c != _form.value.name) seenPeers.add(c)
            }
            recomputeStatus()
        }

        override fun onCallStarted(remoteUser: String, isInitiator: Boolean) {
            _uiState.update {
                it.copy(call = it.call.copy(active = true, remoteUser = remoteUser))
            }
        }

        override fun onCallEnded() {
            _uiState.update { it.copy(call = CallState()) }
        }

        override fun onAudioStatsUpdated(rttMs: Int) {
            _uiState.update { it.copy(call = it.call.copy(rttMs = rttMs)) }
        }

        override fun onError(error: String) {
            Log.w(TAG, "FearClient error: $error")
            // ANY error during the JOIN leg of an AUTO connect → silently retry
            // as CREATE. JOIN can fail with "timeout", "Software caused connection
            // abort", "Connection reset" depending on which layer detects the
            // missing peer first; treating all of them as "no responder" is safe
            // because we already passed the TCP-connect phase before pendingAutoJoin
            // was set (we got onConnected for the TCP layer's sake — wait, no,
            // pendingAutoJoin is set *before* connect()). To be safe, only retry
            // if we haven't already reached onConnected.
            if (pendingAutoJoin) {
                pendingAutoJoin = false
                Log.i(TAG, "[auto] JOIN failed ($error) — retrying as CREATE")
                viewModelScope.launch(Dispatchers.IO) {
                    val f = _form.value
                    client.connect(f.host, f.port, f.room, f.name, "",
                                   FearClient.ConnectMode.CREATE_ROOM)
                }
                return
            }
            // Filter the noisy CLI-style status lines we don't want surfaced.
            val ignored = listOf(
                "Identity loaded", "Commands:", "[client] connected",
                "[create]", "[join] Will request", "[join] Waiting",
                "[join] Key exchange verified", "[join] Room key",
            )
            if (ignored.any { error.contains(it) }) return
            pendingAutoJoin = false
            _uiState.update { it.copy(errorBanner = error.trim(), isConnecting = false) }
        }

        override fun onFileTransferProgress(filename: String, progress: Float) {}
        override fun onFileTransferComplete(filename: String) {
            _uiState.update {
                it.copy(messages = it.messages + ChatMessage(
                    sender = "", text = "📎 file received: $filename",
                    timestamp = Instant.now(), isSystem = true))
            }
        }
        override fun onFileTransferError(filename: String, error: String) {
            _uiState.update { it.copy(errorBanner = "File transfer failed: $error") }
        }
        override fun onCallRequestReceived(fromUser: String) {}
    }

    private val client: FearClient = FearClient(app.applicationContext, listener)

    private fun recomputeStatus() {
        val total = maxOf(reportedCount, seenPeers.size + 1)
        val text = when {
            !_uiState.value.isConnected -> ""
            total <= 1 -> "just you online"
            else       -> "$total online"
        }
        _uiState.update { it.copy(statusText = text) }
    }

    fun updateForm(transform: (ConnectFormState) -> ConnectFormState) {
        _form.update(transform)
    }

    fun connect() {
        val f = _form.value
        if (f.host.isBlank() || f.room.isBlank() || f.name.isBlank()) {
            _uiState.update { it.copy(errorBanner = "Type your name first (and check server/room).") }
            return
        }
        if (f.mode == ConnectMode.MANUAL_KEY && f.key.isBlank()) {
            _uiState.update { it.copy(errorBanner = "Room key is required for manual mode.") }
            return
        }

        _uiState.update { it.copy(isConnecting = true, errorBanner = null) }

        viewModelScope.launch(Dispatchers.IO) {
            when (f.mode) {
                ConnectMode.AUTO -> {
                    // Try JOIN first with a short timeout; on timeout, listener.onError
                    // triggers the CREATE retry.
                    pendingAutoJoin = true
                    client.connect(f.host, f.port, f.room, f.name, "",
                                   FearClient.ConnectMode.JOIN_ROOM,
                                   joinTimeoutMs = AUTO_JOIN_TIMEOUT_MS)
                }
                ConnectMode.CREATE_ROOM -> client.connect(
                    f.host, f.port, f.room, f.name, "", FearClient.ConnectMode.CREATE_ROOM)
                ConnectMode.JOIN_ROOM   -> client.connect(
                    f.host, f.port, f.room, f.name, "", FearClient.ConnectMode.JOIN_ROOM)
                ConnectMode.MANUAL_KEY  -> client.connect(
                    f.host, f.port, f.room, f.name, f.key, FearClient.ConnectMode.MANUAL_KEY)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) { client.disconnect() }
    }

    fun sendMessage(text: String) {
        if (!_uiState.value.isConnected || text.isBlank()) return
        val msg = ChatMessage(
            sender = _form.value.name,
            text = text,
            timestamp = Instant.now(),
            fromSelf = true,
            delivered = false,
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
        persistMessage(msg)
        viewModelScope.launch(Dispatchers.IO) { client.sendMessage(text) }
    }

    fun closeActiveChat() { _uiState.update { it.copy(activeChatId = null) } }
    fun openChat(id: String) {
        _uiState.update { it.copy(activeChatId = id) }
        loadHistoryForRoom(id)
    }

    /** Suspend search across all rooms. Used by the search dialog. */
    suspend fun searchMessages(needle: String): List<MessageEntity> {
        if (needle.length < 2) return emptyList()
        return dao.search(needle)
    }

    /** Wipe persisted history for the currently active chat. */
    fun clearHistory() {
        val roomId = _uiState.value.activeChatId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearRoom(roomId)
            _uiState.update { it.copy(messages = emptyList()) }
        }
    }

    /** Replace the in-memory message list with what's on disk for `roomId`. */
    private fun loadHistoryForRoom(roomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = dao.loadRecent(roomId)
            val msgs = rows.map { e ->
                ChatMessage(
                    sender    = e.senderName,
                    text      = e.text,
                    timestamp = Instant.ofEpochMilli(e.ts),
                    fromSelf  = e.fromSelf,
                    delivered = true,
                    isSystem  = e.isSystem,
                )
            }
            _uiState.update {
                if (it.activeChatId == roomId) it.copy(messages = msgs) else it
            }
        }
    }

    /** Append `msg` to the persisted history of the currently active chat. */
    private fun persistMessage(msg: ChatMessage) {
        val roomId = _uiState.value.activeChatId ?: return
        val ownName = _form.value.name
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(MessageEntity(
                roomId     = roomId,
                senderName = msg.sender.ifEmpty { if (msg.fromSelf) ownName else "system" },
                text       = msg.text,
                ts         = msg.timestamp.toEpochMilli(),
                fromSelf   = msg.fromSelf,
                isSystem   = msg.isSystem,
            ))
        }
    }
    fun dismissError() { _uiState.update { it.copy(errorBanner = null) } }

    /** Get the room key (32 bytes) for passing to call dialogs / VideoCallActivity. */
    fun roomKeyHex(): String = client.getRoomKeyHex()
    fun serverHost(): String = client.getServerHost()
    fun serverPort(): Int    = client.getServerPort()
    fun roomName(): String   = client.getCurrentRoom()
    fun userName(): String   = client.getCurrentName()

    /** Audio call: relay through the chat server. Uses roomKey for symmetric crypto. */
    fun startAudioCall() {
        if (!_uiState.value.isConnected) return
        val keyHex = client.getRoomKeyHex()
        if (keyHex.length != 64) {
            _uiState.update { it.copy(errorBanner = "No room key — wait for connection.") }
            return
        }
        val keyBytes = ByteArray(32) { i ->
            keyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        viewModelScope.launch(Dispatchers.IO) { client.startAudioRelay(keyBytes) }
        _uiState.update { it.copy(call = it.call.copy(active = true)) }
    }

    fun endAudioCall() {
        viewModelScope.launch(Dispatchers.IO) { client.endAudioCall() }
        _uiState.update { it.copy(call = CallState()) }
    }

    fun sendFilePath(path: String) {
        if (!_uiState.value.isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            client.sendMessage("/sendfile $path")
        }
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage(
                sender = _form.value.name,
                text = "📎 sent file: ${path.substringAfterLast('/')}",
                timestamp = Instant.now(), fromSelf = true, isSystem = true))
        }
    }

    private fun loadFormFromPrefs(): ConnectFormState {
        val def = ConnectFormState()
        return ConnectFormState(
            host = prefs.getString(KEY_HOST, def.host) ?: def.host,
            port = prefs.getInt(KEY_PORT, def.port),
            room = prefs.getString(KEY_ROOM, def.room) ?: def.room,
            name = prefs.getString(KEY_NAME, def.name) ?: def.name,
            key = "",
            mode = ConnectMode.AUTO,
        )
    }

    private fun saveFormToPrefs() {
        val f = _form.value
        prefs.edit()
            .putString(KEY_HOST, f.host)
            .putInt(KEY_PORT, f.port)
            .putString(KEY_ROOM, f.room)
            .putString(KEY_NAME, f.name)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        client.disconnect()
    }
}
