package com.fear.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fear.FearClient
import com.fear.HandleProtocol
import com.fear.IdentityManager
import com.fear.Message
import com.fear.data.AppDatabase
import com.fear.data.ContactEntity
import com.fear.data.ContactsRepository
import com.fear.data.MessageEntity
import com.fear.data.ProfileStore
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

    private val prefs    = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dao      = AppDatabase.get(app).messageDao()
    val profile          = ProfileStore.get(app)
    val profileState: kotlinx.coroutines.flow.StateFlow<com.fear.data.ProfileState> = profile.state
    val contactsRepo: ContactsRepository = ContactsRepository.get(app)
    val contactsFlow: kotlinx.coroutines.flow.Flow<List<ContactEntity>> = contactsRepo.observeAll()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(loadFormFromPrefs())
    val form: StateFlow<ConnectFormState> = _form.asStateFlow()

    private val seenPeers = mutableSetOf<String>()
    private var reportedCount = 0

    /** True iff we're inside the JOIN attempt of an AUTO connect — used to fall
     *  back to CREATE if JOIN times out. */
    private var pendingAutoJoin = false
    // True while we're tearing down one room and re-joining another (DM open).
    // Suppresses the brief onDisconnected→ConnectScreen flash and keeps the
    // chat UI visible with a "switching room" status.
    @Volatile private var switchingRoom = false

    private val listener = object : FearClient.FearClientListener {
        override fun onConnected() {
            pendingAutoJoin = false
            switchingRoom = false
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
            // Phase B-3: pull the encrypted contact blob from the server we
            // just connected to, so a new device picks up an existing list.
            viewModelScope.launch(Dispatchers.IO) {
                contactsRepo.pullFromServer(
                    ContactsRepository.ServerEndpoint(f.host, f.port))
            }
        }

        override fun onDisconnected() {
            seenPeers.clear()
            reportedCount = 0
            // During a planned room switch (openDmWith), suppress the
            // disconnect flash — the next onConnected will repaint.
            if (switchingRoom) {
                _uiState.update { it.copy(statusText = "switching room…") }
                return
            }
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
            switchingRoom = false
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

    /**
     * Information surfaced when the user taps a peer in chat — gathered
     * from whatever sources currently know about the peer:
     *   - identity_pk + verification status come from TOFU `known_keys`
     *     (populated when SIGNED_TEXT or IDENTITY_ANNOUNCE arrives)
     *   - handle and stored display name come from the local contact list
     *     (populated by an explicit Add Contact)
     *
     * The screen-name shown alongside is whatever the message frame carried.
     * If we never received a signed message from this peer the pk is null
     * and the dialog falls back to the wire name only.
     */
    data class PeerInfo(
        val displayName: String,
        val identityPkB64: String?,
        val fpshort: String?,
        val fullFingerprint: String?,
        val handle: String?,
        val server: String?,
        val verified: Boolean,
        val isContact: Boolean,
    )

    /** Best-effort lookup; returns null on system / empty senders. */
    suspend fun lookupPeer(senderName: String): PeerInfo? {
        val sender = senderName.trim()
        if (sender.isEmpty() || sender == "system" || sender == "server") return null
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val im  = IdentityManager(app)
            val known = im.loadKnownKeys().firstOrNull { it.name == sender }

            val pkB64    = known?.pk?.let {
                android.util.Base64.encodeToString(it,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            }
            val fpshort  = known?.pk?.let { im.fpshort(it) }
            val fullFp   = known?.pk?.let { im.fingerprint(it) }
            val contact  = pkB64?.let { contactsRepo.all().firstOrNull { c -> c.identityPkB64 == it } }

            PeerInfo(
                displayName     = contact?.displayName?.takeIf { it.isNotBlank() } ?: sender,
                identityPkB64   = pkB64,
                fpshort         = fpshort,
                fullFingerprint = fullFp,
                handle          = contact?.handle,
                server          = contact?.server,
                verified        = known?.verified == true || contact?.verified == true,
                isContact       = contact != null,
            )
        }
    }

    /**
     * Persist a new display name and, if a session is active, push it to
     * FearClient so the next outgoing frame carries it. Peers receive an
     * IDENTITY_ANNOUNCE so their cached (identity_pk → display name)
     * mapping refreshes without waiting for the next message from us.
     */
    fun setDisplayName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        profile.setDisplayName(trimmed)
        // Mirror into the form so re-connect path uses the latest value.
        _form.update { if (it.name != trimmed) it.copy(name = trimmed) else it }
        // Live-update the active session.
        client.setClientName(trimmed)
    }

    fun connect() {
        val f    = _form.value
        // Display name now lives in ProfileStore. The form's `name` field is kept
        // around for the legacy connect screen but is overridden by the profile
        // value when present (it's always present after first-launch onboarding).
        val name = profile.state.value.displayName.ifBlank { f.name }
        if (f.host.isBlank() || f.room.isBlank() || name.isBlank()) {
            _uiState.update { it.copy(errorBanner = "Set your display name first (Profile).") }
            return
        }
        if (f.mode == ConnectMode.MANUAL_KEY && f.key.isBlank()) {
            _uiState.update { it.copy(errorBanner = "Room key is required for manual mode.") }
            return
        }
        // Sync form.name with the profile so existing downstream code (call
        // managers, file transfers) keeps seeing the right sender name.
        if (f.name != name) {
            _form.update { it.copy(name = name) }
        }
        // Note: connecting to a server no longer "claims" the user's display
        // name there — handles are an opt-in registration via ProfileScreen.

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

    /**
     * Reserve `nickname` on the relay at `host:port`. On success, persist the
     * `host → nickname` mapping in ProfileStore. The result is *always*
     * delivered on the Main thread so UI callbacks (Toast, dialog state)
     * are safe to use directly.
     *
     * Note: this no longer auto-triggers Connect. Registration is an
     * independent action — chat messages just travel under `displayName`.
     */
    fun registerHandle(
        host: String,
        port: Int,
        nickname: String,
        onResult: (String?) -> Unit,   // null = success, else error message
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val rc = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val im = IdentityManager(app)
                    if (!im.hasIdentity()) im.generateIdentity()
                    val pk = im.getPublicKey()
                        ?: return@withContext HandleProtocol.Result.ServerError("no identity")
                    HandleProtocol.registerHandle(
                        host, port, nickname.trim(), pk,
                        sign = { msg -> im.sign(msg) },
                    )
                }
            } catch (e: Exception) {
                HandleProtocol.Result.Network(e)
            }
            // Back on Main: safe to touch state / show Toast.
            when (rc) {
                HandleProtocol.Result.Ok -> {
                    profile.setHandle(host, nickname.trim())
                    onResult(null)
                }
                is HandleProtocol.Result.Conflict ->
                    onResult("Nickname '${nickname}' is taken on $host. Try another.")
                is HandleProtocol.Result.Invalid ->
                    onResult("Invalid nickname: ${rc.reason}. Use 3-32 letters/digits, start with a letter.")
                is HandleProtocol.Result.ServerError ->
                    onResult("Server rejected: ${rc.reason}")
                is HandleProtocol.Result.Network ->
                    onResult("Cannot reach $host:$port — ${rc.cause.message ?: "network error"}")
            }
        }
    }

    /**
     * Resolve `nickname@server` against the relay (LOOKUP_HANDLE), persist
     * the result as a contact, and schedule a push of the updated blob.
     */
    fun addContactByHandle(
        nickname: String,
        serverHost: String,
        serverPort: Int,
        displayName: String? = null,
        onResult: (String?) -> Unit,                  // null on success, else error
    ) {
        viewModelScope.launch {
            val rc = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    com.fear.HandleProtocol.lookupHandle(serverHost, serverPort, nickname.trim())
                }
            } catch (e: Exception) {
                com.fear.HandleProtocol.LookupResult.Network(e)
            }
            when (rc) {
                is com.fear.HandleProtocol.LookupResult.Found -> {
                    val name = displayName?.takeIf { it.isNotBlank() } ?: nickname
                    val pkB64 = android.util.Base64.encodeToString(
                        rc.pk,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                    )
                    contactsRepo.upsert(
                        ContactEntity(
                            identityPkB64 = pkB64,
                            displayName   = name,
                            handle        = nickname.trim(),
                            server        = serverHost,
                            addedAt       = System.currentTimeMillis(),
                            verified      = false,
                        ),
                        ContactsRepository.ServerEndpoint(serverHost, serverPort),
                    )
                    onResult(null)
                }
                com.fear.HandleProtocol.LookupResult.NotFound ->
                    onResult("Nickname '$nickname' not found on $serverHost.")
                is com.fear.HandleProtocol.LookupResult.ServerError ->
                    onResult("Server: ${rc.reason}")
                is com.fear.HandleProtocol.LookupResult.Network ->
                    onResult("Cannot reach $serverHost: ${rc.cause.message ?: "network"}")
            }
        }
    }

    /**
     * Save a peer we already have a public key for (e.g. learned over TOFU)
     * directly into contacts. Used by the in-chat peer profile dialog when
     * the user taps 'Add to contacts' — bypasses the network LOOKUP_HANDLE
     * round-trip because the identity pk is already known.
     */
    fun addContactRaw(
        identityPkB64: String,
        displayName: String,
        handle: String?,
        server: String?,
    ) {
        viewModelScope.launch {
            val current = form.value
            val endpoint = if (handle != null && server != null)
                ContactsRepository.ServerEndpoint(server, current.port)
            else null
            contactsRepo.upsert(
                ContactEntity(
                    identityPkB64 = identityPkB64,
                    displayName   = displayName,
                    handle        = handle,
                    server        = server,
                    addedAt       = System.currentTimeMillis(),
                    verified      = false,
                ),
                endpoint,
            )
        }
    }

    /**
     * Open a 1-on-1 chat with `contact`: compute the deterministic DM
     * room_id, set it on the form, and connect via AUTO mode (JOIN if the
     * other peer already created the room, CREATE otherwise).
     *
     * Server stays the same as the form's current `host:port` — DMs live
     * on whichever relay both sides happen to share.
     */
    /**
     * Open a DM with someone we know by pk but might not have as a contact —
     * used by the in-chat profile dialog. Saves them to contacts first if
     * not already present so the chat list shows a meaningful title next time.
     */
    fun openDmWithPk(
        identityPkB64: String,
        displayName: String,
        handle: String?,
        server: String?,
    ) {
        viewModelScope.launch {
            // Best-effort upsert; ignore errors so the chat still opens.
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val existing = contactsRepo.all().firstOrNull {
                        it.identityPkB64 == identityPkB64
                    }
                    if (existing == null) {
                        addContactRaw(identityPkB64, displayName, handle, server)
                    }
                } catch (_: Exception) { /* non-fatal */ }
            }
            val ce = ContactEntity(
                identityPkB64 = identityPkB64,
                displayName   = displayName,
                handle        = handle,
                server        = server,
                addedAt       = System.currentTimeMillis(),
                verified      = false,
            )
            openDmWith(ce)
        }
    }

    fun openDmWith(contact: ContactEntity) {
        val app = getApplication<Application>()
        val im = IdentityManager(app)
        if (!im.hasIdentity()) im.generateIdentity()
        val otherPk = android.util.Base64.decode(
            contact.identityPkB64,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val dmId = im.dmRoomId(otherPk) ?: run {
            _uiState.update { it.copy(errorBanner = "No identity to open DM.") }
            return
        }
        // Mark this as a planned room switch so onDisconnected doesn't flash
        // the user back to the connect screen for the ~5s the AUTO JOIN
        // timeout takes when no one else is in the DM yet.
        if (_uiState.value.isConnected) switchingRoom = true
        _uiState.update {
            it.copy(
                activeChatId = dmId,
                isConnecting = true,
                messages     = emptyList(),
                statusText   = "switching room…",
                chats        = listOf(ChatEntry(
                    id = dmId,
                    title = contact.displayName.ifBlank { dmId },
                    preview = "",
                    lastActivity = Instant.now())),
            )
        }
        _form.update { it.copy(room = dmId, mode = ConnectMode.AUTO) }
        connect()
    }

    fun removeContact(pkB64: String, server: String?, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ep = if (server != null) ContactsRepository.ServerEndpoint(server, port) else null
            contactsRepo.delete(pkB64, ep)
        }
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
