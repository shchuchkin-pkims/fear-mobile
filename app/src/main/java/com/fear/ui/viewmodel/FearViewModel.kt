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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "FearViewModel"
private const val PREFS_NAME = "fear_prefs"
private const val KEY_HOST = "connect.host"
private const val KEY_PORT = "connect.port"
private const val KEY_ROOM = "connect.room"
private const val KEY_NAME = "connect.name"
// Phase B-8: AUTO_JOIN_TIMEOUT_MS retired — AUTO is resolved server-side via
// ROOM_INFO probe inside FearClient.connect, so we no longer wait blindly
// for a KEY_RESPONSE on a possibly-empty room.

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

    /** Состояние регистрации текущей идентичности на выбранном сервере. */
    enum class RegStatus { Unknown, Probing, Registered, NotRegistered, Error }
    data class RegState(
        val host: String = "",
        val status: RegStatus = RegStatus.Unknown,
        val handle: String? = null,
    )
    private val _regState = MutableStateFlow(RegState())
    val regState: StateFlow<RegState> = _regState.asStateFlow()
    private var regProbeJob: kotlinx.coroutines.Job? = null

    /**
     * Запускает проверку регистрации идентичности на `host:port`.
     * Сначала смотрит в локальный ProfileStore (мгновенный ответ), затем
     * через 350мс делает фоновый MSG_TYPE_LOOKUP_HANDLE_BY_PK к серверу,
     * чтобы поймать регистрацию, сохранённую с другого устройства
     * (после импорта identity). Старые probe-задачи отменяются — UI
     * никогда не получает устаревший статус.
     */
    fun probeRegistration(host: String, port: Int) {
        val h = host.trim()
        if (h.isEmpty() || port <= 0) {
            _regState.value = RegState()
            return
        }
        // Step 1: snap to whatever ProfileStore already knows for this host.
        val cached = profile.handleAt(h)
        _regState.value = if (cached != null)
            RegState(h, RegStatus.Registered, cached)
        else
            RegState(h, RegStatus.Probing, null)

        // Step 2: debounced server probe.
        regProbeJob?.cancel()
        regProbeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            val app = getApplication<Application>()
            val pk = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val im = IdentityManager(app)
                if (!im.hasIdentity()) im.generateIdentity()
                im.getPublicKey()
            } ?: run {
                _regState.value = RegState(h, RegStatus.Error, null)
                return@launch
            }
            val rc = HandleProtocol.lookupHandleByPk(h, port, pk)
            // If the user already moved on to another host, drop this reply.
            if (_regState.value.host != h) return@launch
            when (rc) {
                is HandleProtocol.HandleLookupResult.Found -> {
                    profile.setHandle(h, rc.handle)
                    _regState.value = RegState(h, RegStatus.Registered, rc.handle)
                }
                HandleProtocol.HandleLookupResult.NotFound -> {
                    profile.removeHandle(h)
                    _regState.value = RegState(h, RegStatus.NotRegistered, null)
                }
                is HandleProtocol.HandleLookupResult.ServerError,
                is HandleProtocol.HandleLookupResult.Network -> {
                    // Не блокируем пользователя из-за временных проблем со связью —
                    // если есть кэш, оставляем его.
                    val keep = profile.handleAt(h)
                    _regState.value = if (keep != null)
                        RegState(h, RegStatus.Registered, keep)
                    else
                        RegState(h, RegStatus.Error, null)
                }
            }
        }
    }

    /**
     * Phase B-5: unified Telegram-style chat list shown in the sidebar.
     * Combines:
     *   - every saved contact → a DM entry with the deterministic dm:...
     *     room id (last activity = newest DM message or contact addedAt);
     *   - every group room we have local history for → a GROUP entry
     *     (last activity = newest message in that room);
     *   - the currently-joined group room (even if it has no history yet).
     * Sorted newest-first by last activity. Updates live via the message
     * dao's observeChatSummaries() so a new message bumps its room to top.
     */
    val chatList: StateFlow<List<ChatEntry>> =
        combine(
            contactsFlow,
            uiState,
            _form,
            dao.observeChatSummaries(),
        ) { contacts, ui, f, summaries ->
            val im = IdentityManager(app)
            val lastTsByRoom: Map<String, Long> =
                summaries.associate { it.roomId to it.lastTs }

            // ---- DM entries (one per contact) -----------------------------
            val dmEntries = contacts.mapNotNull { c ->
                val pkBytes = try {
                    android.util.Base64.decode(c.identityPkB64,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                } catch (_: Exception) { return@mapNotNull null }
                val pmId = im.pmRoomId(pkBytes) ?: return@mapNotNull null
                val historyTs = lastTsByRoom[pmId]
                ChatEntry(
                    id = pmId,
                    title = c.displayName.ifBlank { c.handle ?: "?" },
                    preview = c.handle?.let { h ->
                        if (c.server != null) "@$h@${c.server}" else "@$h"
                    } ?: "",
                    lastActivity = Instant.ofEpochMilli(historyTs ?: c.addedAt),
                    kind = ChatKind.DM,
                    peerPkB64 = c.identityPkB64,
                )
            }

            // ---- Group entries: every room with history that's not a DM,
            // plus the currently-joined group room (in case it has no
            // history yet, e.g. just connected to "general"). -----------------
            val historicalGroups = summaries
                .map { it.roomId }
                .filter { !it.let { it.startsWith("pm:") || it.startsWith("dm:") } }
                .toMutableSet()
            if (ui.isConnected && f.room.isNotBlank() && !f.room.let { it.startsWith("pm:") || it.startsWith("dm:") }) {
                historicalGroups.add(f.room)
            }
            val groupEntries = historicalGroups.map { roomId ->
                val ts = lastTsByRoom[roomId]
                ChatEntry(
                    id = roomId,
                    title = roomId,
                    preview = "",
                    lastActivity = ts?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
                    kind = ChatKind.GROUP,
                )
            }

            (dmEntries + groupEntries).sortedByDescending { it.lastActivity }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val seenPeers = mutableSetOf<String>()
    private var reportedCount = 0

    // True while we're tearing down one room and re-joining another (DM open).
    // Suppresses the brief onDisconnected→ConnectScreen flash and keeps the
    // chat UI visible with a "switching room" status.
    @Volatile private var switchingRoom = false

    private val listener = object : FearClient.FearClientListener {
        override fun onConnected() {
            switchingRoom = false
            intendedConnected = true
            connectedAtMs = System.currentTimeMillis()
            /* Сначала перенос: ящики и чаты адресуются новым идентификатором,
             * и переехать надо до того, как ими воспользуются. */
            migrateDmRooms()
            /* Ящики контактов - сразу после подключения: письмо могло прийти,
             * пока нас не было. */
            registerMailboxes()
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
                    statusText = "Online…",
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
            // Если текущая комната это DM, после отключения возвращаем
            // на ConnectScreen уже выбранную обычную (групповую) комнату
            // из prefs — это то, что пользователь видел в форме до того
            // как открыл ЛС. По умолчанию prefs хранит "general".
            if (_form.value.room.let { it.startsWith("pm:") || it.startsWith("dm:") }) {
                val saved = prefs.getString(KEY_ROOM, "general") ?: "general"
                _form.update { it.copy(room = saved) }
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
            // Снимаем актуальный список и кладём в state — заголовку чата
            // (групповая комната) нужно показать всех присутствующих, а не
            // только тех, от кого мы получили сообщения.
            val unique = contacts.filter { it.isNotBlank() }.distinct()
            _uiState.update { it.copy(participants = unique) }
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
            // Phase B-8: AUTO is now resolved server-side via ROOM_INFO probe
            // before any JOIN/CREATE is attempted, so the old "JOIN timeout
            // → retry as CREATE" fallback is gone.

            // Filter the noisy CLI-style status lines we don't want surfaced.
            val ignored = listOf(
                "Identity loaded", "Commands:", "[client] connected",
                "[create]", "[join] Will request", "[join] Waiting",
                "[join] Key exchange verified", "[join] Room key",
            )
            if (ignored.any { error.contains(it) }) return
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

        /**
         * Somebody announced a call. Until now this arrived, was remembered
         * so that pressing the call button would join the right call, and
         * was never shown - so a call looked like nothing at all from the
         * other side.
         */
        override fun onCallInviteReceived(
            fromUser: String,
            invite: com.fear.crypto.CallInvite.Invite,
        ) {
            // Do not ring during a call we are already in: the announcement
            // is repeated while a caller waits, and the room broadcast comes
            // back to every member.
            if (_uiState.value.call.active) return
            _uiState.update {
                it.copy(
                    incomingCall = IncomingCall(fromUser, invite.hasVideo),
                    messages = it.messages + ChatMessage(
                        sender = "", timestamp = Instant.now(), isSystem = true,
                        text = if (invite.hasVideo) "\uD83D\uDCF9 $fromUser is calling (video)"
                               else "\uD83D\uDCDE $fromUser is calling"),
                )
            }
        }
    }

    private val client: FearClient = FearClient(app.applicationContext, listener)

    /** Время последнего успешного onConnected, для grace-period в
     *  recomputeStatus. */
    @Volatile private var connectedAtMs: Long = 0

    /** Сколько ждём первый USER_LIST, прежде чем поверить, что мы одни. */
    private val statusSettleMs = 3_000L

    /** Отложенный пересчёт статуса; см. recomputeStatus. */
    private var statusRefresh: kotlinx.coroutines.Job? = null

    /**
     * Сказать клиенту, за какими ящиками следить.
     *
     * Список контактов ведёт эта модель, а ключ пары выводится из двух личных
     * ключей - клиент про контакты ничего не знает. Пропустив контакт, мы
     * просто не увидим его писем, поэтому вызывается это и при подключении, и
     * при каждом изменении списка.
     */
    /**
     * Перенести переписку под новый идентификатор личной комнаты.
     *
     * Старый выводился из двух открытых ключей без секрета, и ретранслятор,
     * знающий ключи всех, кто занял имя, мог перебрать пары и подписать
     * каждую личную комнату именами обоих собеседников. Новый выводится под
     * ключом пары, повторить его снаружи нельзя.
     *
     * Цена - смена адреса у существующих чатов, поэтому переписка переезжает
     * здесь же. Повторный вызов безвреден: переносить будет нечего.
     */
    private fun migrateDmRooms() {
        viewModelScope.launch {
            val im = client.getIdentityManager() ?: return@launch
            if (!im.hasIdentity()) return@launch
            val dao = com.fear.data.AppDatabase.get(getApplication()).messageDao()
            for (c in contactsRepo.all()) {
                val pk = try {
                    android.util.Base64.decode(
                        c.identityPkB64,
                        android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_PADDING or
                            android.util.Base64.NO_WRAP,
                    )
                } catch (e: Exception) {
                    continue
                }
                if (pk.size != 32) continue
                val oldId = im.pmRoomIdV1(pk) ?: continue
                val newId = im.pmRoomId(pk) ?: continue
                if (oldId != newId) dao.renameRoom(oldId, newId)
            }
        }
    }

    private fun registerMailboxes() {
        viewModelScope.launch {
            val im = client.getIdentityManager() ?: return@launch
            if (!im.hasIdentity()) return@launch
            client.forgetMailboxes()
            for (c in contactsRepo.all()) {
                val pk = try {
                    android.util.Base64.decode(
                        c.identityPkB64,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                            android.util.Base64.NO_WRAP)
                } catch (e: Exception) { continue }
                if (pk.size != 32) continue   // длина Ed25519-ключа
                val room = im.pmRoomId(pk) ?: continue
                val kPm = im.pmRoomKey(pk) ?: continue
                client.watchMailbox(room, kPm)
                kPm.fill(0)
            }
        }
    }

    private fun recomputeStatus() {
        val total = maxOf(reportedCount, seenPeers.size + 1)
        val isPm  = _form.value.room.startsWith("pm:") || _form.value.room.startsWith("dm:")
        // Сервер шлёт USER_LIST через несколько мс после успешного
        // подключения. До его прихода не утверждаем, что мы одни, — иначе
        // пользователь видит «один в комнате», когда собеседник уже там.
        val sinceConnect = System.currentTimeMillis() - connectedAtMs
        val settling = sinceConnect < statusSettleMs
        val text = when {
            !_uiState.value.isConnected -> ""
            isPm && total >= 2     -> "Online"
            isPm                   -> if (settling) "Connecting…" else "Offline"
            total <= 1 && settling -> "Online…"
            else                   -> "Online $total"
        }
        _uiState.update { it.copy(statusText = text) }

        /* Без этого надпись замерзает. Пересчитывают её только события, а
         * «ещё подключаемся» перестаёт быть правдой само по себе: если после
         * входа в комнате больше ничего не происходит, «Online…» остаётся на
         * экране навсегда. */
        statusRefresh?.cancel()
        statusRefresh = null
        if (settling && _uiState.value.isConnected) {
            statusRefresh = viewModelScope.launch {
                kotlinx.coroutines.delay(statusSettleMs - sinceConnect + 100)
                recomputeStatus()
            }
        }
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
            // Phase B-8: AUTO now uses MSG_TYPE_ROOM_INFO_REQUEST inside
            // FearClient.connect to ask the server how many members are in
            // the room before deciding to JOIN or CREATE. No more 5s blind
            // wait on a fresh room.
            val clientMode = when (f.mode) {
                ConnectMode.AUTO        -> FearClient.ConnectMode.AUTO
                ConnectMode.CREATE_ROOM -> FearClient.ConnectMode.CREATE_ROOM
                ConnectMode.JOIN_ROOM   -> FearClient.ConnectMode.JOIN_ROOM
                ConnectMode.MANUAL_KEY  -> FearClient.ConnectMode.MANUAL_KEY
            }
            val keyArg = if (f.mode == ConnectMode.MANUAL_KEY) f.key else ""
            client.connect(f.host, f.port, f.room, f.name, keyArg, clientMode)
        }
    }

    /** Состояние клиентского сокета. */
    fun isClientConnected(): Boolean = client.isConnected()

    /** True пока пользователь намеренно «онлайн» — выключается только
     *  явным нажатием Disconnect. Doze может уронить сокет за время
     *  блокировки экрана, и onDisconnected очистит state.isConnected;
     *  по этому флагу onResume понимает, что нужен reconnect. */
    @Volatile private var intendedConnected = false

    /**
     * Вызывается из ComposeMainActivity при ON_RESUME. Если пользователь
     * подразумевает, что он подключён (intendedConnected=true), но
     * реальный TCP-сокет уже мёртв — тихо переподключаемся к той же
     * комнате. Покрывает сценарий «заблокировал экран на минуту → Doze
     * порвал TCP → разблокировал экран».
     */
    fun reconnectIfDropped() {
        if (intendedConnected && !client.isConnected()) {
            val room = _form.value.room
            android.util.Log.i(TAG, "reconnectIfDropped: socket dead, reconnecting to $room")
            switchingRoom = true
            // Возвращаем UI в чат-режим до того, как стартанём connect,
            // иначе пользователь успеет увидеть ConnectScreen.
            _uiState.update {
                it.copy(
                    isConnected = true,
                    isConnecting = true,
                    activeChatId = it.activeChatId ?: room,
                    statusText = "reconnecting…",
                )
            }
            connect()
        }
    }

    fun disconnect() {
        intendedConnected = false
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
     * Тап на групповую комнату в сайдбаре. Если уже подключены к этой
     * комнате — просто переключаем UI (openChat). Иначе — реконнект в
     * режиме AUTO (JOIN с фолбэком на CREATE), чтобы сокет действительно
     * оказался в нужной комнате на сервере.
     */
    /**
     * Перейти к конкретному сообщению в локальной истории. Используется
     * SearchDialog: тап по найденному результату должен открыть нужную
     * комнату и прокрутить чат к сообщению с этим timestamp.
     *
     * Для PM-комнат (`pm:` / `dm:`) ищет соответствующий контакт по
     * совпадению pmRoomId и зовёт [openDmWithPk]; для групповых —
     * [openGroupRoom]. После переключения комнаты state.pendingScrollToTs
     * подхватывается ChatPane-ом, который скроллит список к найденному
     * сообщению.
     */
    fun jumpToMessage(roomId: String, ts: Long) {
        val current = _form.value.room
        if (current == roomId && _uiState.value.isConnected) {
            _uiState.update { it.copy(pendingScrollToTs = ts) }
            return
        }
        if (roomId.startsWith("pm:") || roomId.startsWith("dm:")) {
            // Найдём соответствующий контакт по совпадению идентификатора.
            viewModelScope.launch(Dispatchers.IO) {
                val app = getApplication<Application>()
                val im  = IdentityManager(app)
                val match = contactsRepo.all().firstOrNull { c ->
                    val pkBytes = try {
                        android.util.Base64.decode(c.identityPkB64,
                            android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_WRAP or
                            android.util.Base64.NO_PADDING)
                    } catch (_: Exception) { return@firstOrNull false }
                    im.pmRoomId(pkBytes) == roomId
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (match != null) {
                        _uiState.update { it.copy(pendingScrollToTs = ts) }
                        openDmWith(match)
                    } else {
                        _uiState.update { it.copy(errorBanner =
                            "Не нашли контакт для этой ЛС-комнаты.") }
                    }
                }
            }
        } else {
            _uiState.update { it.copy(pendingScrollToTs = ts) }
            openGroupRoom(roomId)
        }
    }

    /** Сбрасывает целевой timestamp прокрутки — вызывается ChatPane-ом
     *  после успешного scrollToItem. */
    fun clearScrollTarget() {
        _uiState.update { it.copy(pendingScrollToTs = null) }
    }

    /**
     * Полное удаление чата из локального состояния. Вызывается из long-press
     * на элементе сайдбара. Для PM-комнаты удаляет соответствующий контакт
     * (и обновляет blob на сервере), для group — просто стирает локальную
     * историю; запись в сайдбаре исчезнет, потому что chatList строится
     * как объединение contacts × messageDao.observeChatSummaries().
     *
     * Если удаляется текущая активная комната — отключаемся, чтобы UI не
     * показывал чат, которого больше нет в списке.
     */
    fun deleteChat(entry: ChatEntry) {
        val roomId = entry.id
        val isPm   = roomId.startsWith("pm:") || roomId.startsWith("dm:")
        val activeIsThis = (_uiState.value.activeChatId == roomId)

        viewModelScope.launch(Dispatchers.IO) {
            // Локальная история комнаты.
            try { dao.clearRoom(roomId) } catch (_: Exception) { /* non-fatal */ }

            if (isPm) {
                // Удаляем контакт. ContactsRepository сам пересоберёт blob
                // и отправит на сервер при наличии endpoint-а.
                val pk = entry.peerPkB64
                if (pk != null) {
                    val ep = ContactsRepository.ServerEndpoint(
                        _form.value.host, _form.value.port)
                    try { contactsRepo.delete(pk, ep) } catch (_: Exception) {}
                }
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (activeIsThis) {
                    intendedConnected = false
                    client.disconnect()
                    _uiState.update {
                        it.copy(
                            activeChatId = null,
                            messages = emptyList(),
                            statusText = "",
                            participants = emptyList(),
                        )
                    }
                }
            }
        }
    }

    fun openGroupRoom(roomId: String) = openGroupRoom(roomId, ConnectMode.AUTO)

    /**
     * Switch the active chat to a group room.
     *
     * The default ([ConnectMode.AUTO]) is used when the user just taps a
     * room in their sidebar — the relay decides whether to join an
     * existing room or create a new one.
     *
     * The explicit [ConnectMode.JOIN_ROOM] / [ConnectMode.CREATE_ROOM]
     * variants are used by the "+" → "Join room" / "Create new room"
     * sheet on the sidebar so the user's intent is explicit.
     */
    fun openGroupRoom(roomId: String, mode: ConnectMode) {
        val current = _form.value.room
        if (current == roomId && _uiState.value.isConnected) {
            openChat(roomId)
            return
        }
        // Помечаем переход как «switching» чтобы UI не мигнул на ConnectScreen
        // во время короткого reconnect-а.
        if (_uiState.value.isConnected) switchingRoom = true
        _uiState.update {
            it.copy(
                activeChatId = roomId,
                isConnecting = true,
                messages     = emptyList(),
                statusText   = "switching room…",
                chats        = listOf(ChatEntry(
                    id = roomId, title = roomId, preview = "",
                    lastActivity = Instant.now(), kind = ChatKind.GROUP)),
            )
        }
        _form.update { it.copy(room = roomId, key = "", mode = mode) }
        connect()
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
        Log.i(TAG, "openDmWith: hasIdentity=${im.hasIdentity()}, contact=${contact.displayName}")
        if (!im.hasIdentity()) im.generateIdentity()
        val otherPk = try {
            android.util.Base64.decode(
                contact.identityPkB64,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
        } catch (e: Exception) {
            Log.e(TAG, "openDmWith: bad pk for ${contact.displayName}", e)
            _uiState.update { it.copy(errorBanner = "Битый pk у контакта.") }
            return
        }
        Log.i(TAG, "openDmWith: otherPk size=${otherPk.size}")
        val pmId = im.pmRoomId(otherPk) ?: run {
            Log.e(TAG, "openDmWith: pmRoomId вернул null")
            _uiState.update { it.copy(errorBanner = "Нет identity для открытия ЛС.") }
            return
        }
        Log.i(TAG, "openDmWith: pmId=$pmId")
        val key32 = im.pmRoomKey(otherPk) ?: run {
            Log.e(TAG, "openDmWith: pmRoomKey вернул null")
            _uiState.update { it.copy(errorBanner = "Не удалось вычислить ключ ЛС.") }
            return
        }
        Log.i(TAG, "openDmWith: K_pm computed, size=${key32.size}")
        val keyB64 = android.util.Base64.encodeToString(
            key32,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        java.util.Arrays.fill(key32, 0)

        if (_uiState.value.isConnected) switchingRoom = true
        _uiState.update {
            it.copy(
                activeChatId = pmId,
                isConnecting = true,
                messages     = emptyList(),
                statusText   = "switching room…",
                chats        = listOf(ChatEntry(
                    id = pmId,
                    title = contact.displayName.ifBlank { pmId },
                    preview = "",
                    lastActivity = Instant.now())),
            )
        }
        _form.update { it.copy(room = pmId, key = keyB64, mode = ConnectMode.MANUAL_KEY) }
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

    /**
     * The call_id for a video call, hex: the one a peer announced in this room
     * a moment ago, or a fresh one that the client announces there. Null when
     * there is no room to announce it in - every media key is bound to this
     * value, so a call that cannot publish it could only talk to itself.
     */
    fun beginVideoCall(): String? = client.beginCallHex(video = true)

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

    /**
     * Answer the announced call. startAudioCall joins the call_id from the
     * invite, because it is still fresh - that is what puts both ends on the
     * same keys.
     */
    /**
     * Answer. Audio starts here; video is started by the screen, which is the
     * only part that needs an Activity. Either way the call_id comes from the
     * invitation that was just announced - every media key of a call is bound
     * to it, so two ends that each drew their own would connect, exchange
     * packets and hear silence.
     */
    fun acceptIncomingCall() {
        val incoming = _uiState.value.incomingCall ?: return
        _uiState.update { it.copy(incomingCall = null) }
        if (!incoming.video) startAudioCall()
    }

    /** Decline: the caller is not told, there is no protocol message for it. */
    fun dismissIncomingCall() {
        _uiState.update { it.copy(incomingCall = null) }
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
        // Не сохраняем dm:* как «комнату по умолчанию» — это комната ЛС,
        // которую пользователь открыл из сайдбара, а ConnectScreen
        // должен запоминать последнюю обычную (групповую) комнату.
        val roomToSave = if (f.room.let { it.startsWith("pm:") || it.startsWith("dm:") }) "general" else f.room
        prefs.edit()
            .putString(KEY_HOST, f.host)
            .putInt(KEY_PORT, f.port)
            .putString(KEY_ROOM, roomToSave)
            .putString(KEY_NAME, f.name)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        client.disconnect()
    }
}
