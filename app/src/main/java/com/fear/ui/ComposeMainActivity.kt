package com.fear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.fear.AppUpdater
import com.fear.BuildConfig
import com.fear.IdentityManager
import kotlinx.coroutines.delay
import com.fear.TrustedKeysActivity
import com.fear.VideoCallActivity
import com.fear.ui.components.AboutDialog
import com.fear.ui.components.CallOverlay
import com.fear.ThemeManager
import com.fear.ui.components.IdentityBackupSheet
import com.fear.ui.components.MenuSheet
import com.fear.ui.components.PasswordPromptDialog
import com.fear.ui.components.QrShowDialog
import com.fear.ui.components.SearchDialog
import com.fear.ui.components.UpdateDialog
import com.fear.ui.screens.ContactsScreen
import com.fear.ui.screens.OnboardingScreen
import com.fear.ui.screens.ProfileScreen
import com.fear.ui.components.AddContactDialog
import com.fear.ui.components.PeerProfileDialog
import com.fear.Common
import com.fear.IdentityBackup
import kotlinx.coroutines.launch
import android.widget.Toast
import com.fear.ui.screens.ChatScreen
import com.fear.ui.screens.ConnectScreen
import com.fear.ui.theme.FearTheme
import com.fear.ui.viewmodel.FearViewModel
import java.io.File

class ComposeMainActivity : ComponentActivity() {

    private val viewModel: FearViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // После возврата из background (например после блокировки
        // экрана) Android Doze обычно убивает наш TCP-сокет, но в
        // ChatUiState.isConnected по-прежнему true — UI остаётся в
        // активном чате, а сообщения не доходят. Тихо переподключаемся.
        viewModel.reconnectIfDropped()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Eagerly construct IdentityManager so its init block runs the
        // plaintext-to-EncryptedFile migration before user interacts.
        // If no identity exists yet (fresh install), generate one now so
        // backup/export and the upcoming '@user@server' UX both work
        // before the user has connected to any room.
        val im = IdentityManager(applicationContext)
        if (!im.hasIdentity()) {
            val ok = im.generateIdentity()
            Log.i("FearIdentity", "Eager generateIdentity: ok=$ok hasIdentity=${im.hasIdentity()}")
        } else {
            Log.i("FearIdentity", "Eager init: identity already present")
        }

        setContent {
            // Тема персистируется через ThemeManager, чтобы другие activity
            // (например TrustedKeysActivity) подхватывали выбор пользователя.
            // null = follow system; true/false = принудительная.
            val initialOverride = remember {
                when (ThemeManager.getTheme(this@ComposeMainActivity)) {
                    ThemeManager.THEME_DARK  -> true
                    ThemeManager.THEME_LIGHT -> false
                    else                      -> null
                }
            }
            var darkOverride by remember { mutableStateOf(initialOverride) }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectiveDark = darkOverride ?: systemDark

            FearTheme(darkTheme = effectiveDark) {
                val state by viewModel.uiState.collectAsState()
                val form  by viewModel.form.collectAsState()
                var menuOpen by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
                var downloadProgress by remember { mutableStateOf<Int?>(null) }
                var autoChecked by remember { mutableStateOf(false) }
                var aboutOpen by remember { mutableStateOf(false) }
                // Identity backup state
                var exportPasswordOpen by remember { mutableStateOf(false) }
                var importPasswordOpen by remember { mutableStateOf<Uri?>(null) }
                var qrPasswordOpen     by remember { mutableStateOf(false) }
                var qrShowText         by remember { mutableStateOf<String?>(null) }
                var pendingQrSaveText  by remember { mutableStateOf<String?>(null) }
                // After a successful QR scan, hold the base64 payload until the
                // user types the password to decrypt it.
                var importQrPasswordOpen by remember { mutableStateOf<String?>(null) }
                var searchOpen by remember { mutableStateOf(false) }
                var profileOpen by remember { mutableStateOf(false) }
                var contactsOpen by remember { mutableStateOf(false) }
                /* Когда не null — открыт диалог регистрации handle для
                 * указанного сервера. Делаем общим для всех экранов:
                 * запускается и из ProfileScreen, и из ConnectScreen. */
                var registerHandleHost by remember { mutableStateOf<String?>(null) }
                var addContactOpen by remember { mutableStateOf(false) }
                /* Sub-sheet for identity backup actions (export/import .fbk,
                 * show/import QR). Replaces four separate menu items with
                 * one entry point. */
                var identityBackupOpen by remember { mutableStateOf(false) }
                /* Открыт диалог со списком участников групповой комнаты,
                 * показывается по тапу на header в групповом чате. */
                var groupInfoVisible by remember { mutableStateOf(false) }
                /* Long-press на элементе сайдбара → диалог подтверждения
                 * удаления чата. */
                var pendingDelete by remember { mutableStateOf<com.fear.ui.viewmodel.ChatEntry?>(null) }
                var peerProfile by remember { mutableStateOf<com.fear.ui.viewmodel.FearViewModel.PeerInfo?>(null) }
                val peerLookupScope = rememberCoroutineScope()
                val profileState by viewModel.profileState.collectAsState()
                val contacts by viewModel.contactsFlow.collectAsState(initial = emptyList())
                val context = androidx.compose.ui.platform.LocalContext.current

                // ZXing camera scan launcher — returns the decoded QR text (null on cancel)
                val qrScanLauncher = rememberLauncherForActivityResult(
                    com.journeyapps.barcodescanner.ScanContract()
                ) { result ->
                    val text = result?.contents
                    if (!text.isNullOrEmpty()) importQrPasswordOpen = text
                }

                // SAF: pick destination .png to save the QR bitmap
                val qrPngSaveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("image/png")
                ) { uri: Uri? ->
                    val text = pendingQrSaveText
                    pendingQrSaveText = null
                    if (uri != null && text != null) saveQrPng(uri, text)
                }

                // Silent auto-update check on first composition (delay 3s so the
                // connect screen renders first). If a newer version is available,
                // populate updateInfo → the UpdateDialog appears.
                LaunchedEffect(Unit) {
                    if (autoChecked) return@LaunchedEffect
                    autoChecked = true
                    delay(3000)
                    runCatching {
                        val info = AppUpdater(applicationContext).checkForUpdate()
                        if (info.hasUpdate && info.downloadUrl.isNotEmpty()) {
                            updateInfo = info
                        }
                    }.onFailure { /* silent — manual menu still works */ }
                }

                // File picker: SAF document → copy to cache → /sendfile <path>
                val filePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val cached = copyUriToCache(uri)
                    if (cached != null) viewModel.sendFilePath(cached.absolutePath)
                }

                // SAF: pick destination .fbk (export). Result captured into pendingExportUri,
                // which is then encrypted by exportPasswordOpen flow below.
                var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
                val exportSaveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri: Uri? ->
                    if (uri != null) {
                        pendingExportUri = uri
                        exportPasswordOpen = true
                    }
                }
                // SAF: pick existing .fbk (import). On result, prompt for password.
                val importOpenLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) importPasswordOpen = uri
                }

                if (profileState.displayName.isBlank()) {
                    // First-launch onboarding: pick a display name and that's it.
                    OnboardingScreen(initialName = form.name) { name ->
                        viewModel.setDisplayName(name)
                        viewModel.updateForm { it.copy(name = name) }
                    }
                } else if (contactsOpen) {
                    ContactsScreen(
                        contacts = contacts,
                        onBack   = { contactsOpen = false },
                        onAdd    = { addContactOpen = true },
                        onRemove = { c ->
                            viewModel.removeContact(c.identityPkB64,
                                form.host, form.port)
                        },
                        onOpenChat = { c ->
                            viewModel.openDmWith(c)
                            contactsOpen = false
                        },
                    )
                    if (addContactOpen) {
                        AddContactDialog(
                            defaultServer = form.host,
                            onDismiss = { addContactOpen = false },
                            onSubmit  = { nick, srv, name ->
                                viewModel.addContactByHandle(nick, srv, form.port, name) { err ->
                                    addContactOpen = false
                                    if (err != null) {
                                        Toast.makeText(this@ComposeMainActivity,
                                            err, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(this@ComposeMainActivity,
                                            "Added $nick@$srv", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                } else if (profileOpen) {
                    val im = remember { com.fear.IdentityManager(applicationContext) }
                    val pk = im.getPublicKey()
                    ProfileScreen(
                        displayName = profileState.displayName,
                        setDisplayName = { viewModel.setDisplayName(it) },
                        handles = profileState.handles,
                        fpshort = pk?.let { im.fpshort(it) },
                        fullFingerprint = pk?.let { im.fingerprint(it) },
                        onBack = { profileOpen = false },
                        onAddHandle = { registerHandleHost = form.host },
                        onRemoveHandle = { server -> viewModel.profile.removeHandle(server) },
                        onExportIdentity = {
                            profileOpen = false
                            exportSaveLauncher.launch("fear-identity-backup.fbk")
                        },
                        onShowQr = {
                            profileOpen = false
                            qrPasswordOpen = true
                        },
                    )
                } else if (!state.isConnected) {
                    val regState by viewModel.regState.collectAsState()
                    ConnectScreen(
                        form = form,
                        displayName = profileState.displayName,
                        isConnecting = state.isConnecting,
                        errorBanner = state.errorBanner,
                        regState = regState,
                        onUpdate = { viewModel.updateForm { _ -> it } },
                        onConnect = { viewModel.connect() },
                        onRegister = { registerHandleHost = form.host },
                        onProbeServer = { host, port -> viewModel.probeRegistration(host, port) },
                        onDismissError = viewModel::dismissError,
                        onOpenProfile = { profileOpen = true },
                    )
                } else {
                    val chatListState by viewModel.chatList.collectAsState()
                    ChatScreen(
                        state = state,
                        chatList = chatListState,
                        onChatSelected = { entry ->
                            // DM entry → reconnect to that DM room (handles
                            // contact-not-yet-saved via openDmWithPk).
                            // GROUP entry → just open the active chat pane.
                            if (entry.kind == com.fear.ui.viewmodel.ChatKind.DM
                                && entry.peerPkB64 != null) {
                                viewModel.openDmWithPk(
                                    identityPkB64 = entry.peerPkB64,
                                    displayName   = entry.title,
                                    handle        = null,
                                    server        = null,
                                )
                            } else {
                                viewModel.openGroupRoom(entry.id)
                            }
                        },
                        onChatLongPressed = { entry -> pendingDelete = entry },
                        onAddNew       = {
                            // Two ways to start a new chat: Add contact (DM)
                            // or Connect to a named room.
                            contactsOpen = true
                        },
                        onSendMessage  = viewModel::sendMessage,
                        onMenuClick    = { menuOpen = true },
                        onAudioCall    = { startAudioCall() },
                        onVideoCall    = { startVideoCall() },
                        onAttach       = {
                            // OpenDocument with "*/*" — picks any file type
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onBack         = viewModel::closeActiveChat,
                        onSenderTap    = { name ->
                            peerLookupScope.launch {
                                peerProfile = viewModel.lookupPeer(name)
                            }
                        },
                        onHeaderTap    = { entry ->
                            if (entry.kind == com.fear.ui.viewmodel.ChatKind.DM) {
                                // ЛС → профиль собеседника. Имя ищем по
                                // самому свежему стороннему сообщению, либо
                                // по самому первому участнику.
                                val peerName = state.participants.firstOrNull {
                                    it.isNotBlank() && it != form.name
                                } ?: state.messages.firstOrNull { !it.fromSelf }?.sender
                                if (peerName != null) {
                                    peerLookupScope.launch {
                                        peerProfile = viewModel.lookupPeer(peerName)
                                    }
                                }
                            } else {
                                // Группа → диалог со списком участников.
                                groupInfoVisible = true
                            }
                        },
                        onScrollHandled = { viewModel.clearScrollTarget() },
                    )

                    if (groupInfoVisible) {
                        val activeTitle = chatListState.firstOrNull {
                            it.id == state.activeChatId
                        }?.title ?: state.activeChatId.orEmpty()
                        // Fallback: если сервер ещё не прислал USER_LIST,
                        // соберём участников из видимых сообщений и добавим
                        // самого пользователя — это всё равно даст
                        // полезный список вместо пустоты.
                        val displayed = run {
                            val fromMessages = state.messages
                                .asSequence()
                                .filter { !it.isSystem && it.sender.isNotBlank()
                                          && it.sender != "system" }
                                .map { it.sender }
                                .toSet()
                            val all = (state.participants.toSet()
                                       + fromMessages
                                       + form.name).filter { it.isNotBlank() }
                            all.distinct().sorted()
                        }
                        com.fear.ui.components.GroupParticipantsDialog(
                            roomTitle = activeTitle,
                            participants = displayed,
                            onPeerTap = { name ->
                                peerLookupScope.launch {
                                    peerProfile = viewModel.lookupPeer(name)
                                }
                            },
                            onDismiss = { groupInfoVisible = false },
                        )
                    }

                    peerProfile?.let { info ->
                        PeerProfileDialog(
                            info = info,
                            onDismiss = { peerProfile = null },
                            onAddContact = { p ->
                                if (p.identityPkB64 != null) {
                                    viewModel.addContactRaw(
                                        identityPkB64 = p.identityPkB64,
                                        displayName   = p.displayName,
                                        handle        = p.handle,
                                        server        = p.server,
                                    )
                                }
                            },
                            onOpenChat = { p ->
                                viewModel.openDmWithPk(
                                    identityPkB64 = p.identityPkB64!!,
                                    displayName   = p.displayName,
                                    handle        = p.handle,
                                    server        = p.server,
                                )
                            },
                        )
                    }

                    pendingDelete?.let { entry ->
                        val isPm = entry.kind == com.fear.ui.viewmodel.ChatKind.DM
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pendingDelete = null },
                            title = { androidx.compose.material3.Text("Delete chat") },
                            text = {
                                androidx.compose.material3.Text(
                                    if (isPm)
                                        "Delete chat with \"${entry.title}\"? " +
                                        "Local message history will be erased and " +
                                        "the contact will be removed from your address book. " +
                                        "This does not affect the conversation on their side."
                                    else
                                        "Delete local history for room '${entry.title}'? " +
                                        "Messages on the server and on other " +
                                        "participants' devices remain intact."
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    viewModel.deleteChat(entry)
                                    pendingDelete = null
                                }) {
                                    androidx.compose.material3.Text(
                                        "Delete",
                                        color = androidx.compose.ui.graphics.Color(0xFFE57373),
                                    )
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                                    androidx.compose.material3.Text("Cancel")
                                }
                            },
                        )
                    }

                    if (menuOpen) {
                        MenuSheet(
                            onDismiss = { menuOpen = false },
                            onDisconnect = { viewModel.disconnect() },
                            onTrustedKeys = {
                                startActivity(Intent(this@ComposeMainActivity, TrustedKeysActivity::class.java))
                            },
                            onToggleTheme = {
                                val next = !effectiveDark
                                darkOverride = next
                                ThemeManager.setTheme(
                                    this@ComposeMainActivity,
                                    if (next) ThemeManager.THEME_DARK
                                    else      ThemeManager.THEME_LIGHT,
                                )
                            },
                            onCheckUpdates = { triggerUpdateCheck { updateInfo = it } },
                            onAbout = { aboutOpen = true },
                            onIdentityBackup = { identityBackupOpen = true },
                            onClearHistory = {
                                viewModel.clearHistory()
                                Toast.makeText(this@ComposeMainActivity,
                                    "Chat history cleared", Toast.LENGTH_SHORT).show()
                            },
                            onSearch = { searchOpen = true },
                            onProfile = { profileOpen = true },
                            onContacts = { contactsOpen = true },
                        )
                    }

                    if (identityBackupOpen) {
                        IdentityBackupSheet(
                            onDismiss = { identityBackupOpen = false },
                            onExportIdentity = {
                                identityBackupOpen = false
                                exportSaveLauncher.launch("fear-identity-backup.fbk")
                            },
                            onImportIdentity = {
                                identityBackupOpen = false
                                importOpenLauncher.launch(arrayOf("*/*"))
                            },
                            onShowQr = {
                                identityBackupOpen = false
                                qrPasswordOpen = true
                            },
                            onImportQr = {
                                identityBackupOpen = false
                                qrScanLauncher.launch(
                                    com.journeyapps.barcodescanner.ScanOptions().apply {
                                        setDesiredBarcodeFormats(
                                            com.journeyapps.barcodescanner.ScanOptions.QR_CODE
                                        )
                                        setPrompt("Scan FEAR identity QR")
                                        setBeepEnabled(false)
                                        setOrientationLocked(false)
                                    }
                                )
                            },
                        )
                    }

                    if (searchOpen) {
                        SearchDialog(
                            onSearch = { needle -> viewModel.searchMessages(needle) },
                            onOpenMessage = { msg ->
                                viewModel.jumpToMessage(msg.roomId, msg.ts)
                            },
                            onDismiss = { searchOpen = false },
                        )
                    }

                    // Password prompt for QR-only flow (no file save)
                    if (qrPasswordOpen) {
                        PasswordPromptDialog(
                            title = "Show identity QR",
                            message = "Pick a password for the encrypted QR. The receiving device will need this same password to decrypt.",
                            confirm = true,
                            onDismiss = { qrPasswordOpen = false },
                        ) { pw ->
                            qrPasswordOpen = false
                            runEncryptToQr(pw) { base64 -> qrShowText = base64 }
                        }
                    }

                    // Export password prompt (after user chose destination)
                    if (exportPasswordOpen) {
                        PasswordPromptDialog(
                            title = "Export identity",
                            message = "Choose a password. You'll need it to restore the backup. Lose the password = lose the backup.",
                            confirm = true,
                            onDismiss = {
                                exportPasswordOpen = false
                                pendingExportUri = null
                            },
                        ) { pw ->
                            val uri = pendingExportUri
                            exportPasswordOpen = false
                            pendingExportUri = null
                            if (uri != null) {
                                runExportIdentity(uri, pw) { base64Blob -> qrShowText = base64Blob }
                            }
                        }
                    }

                    // Import password prompt (after user chose source file)
                    val importUri = importPasswordOpen
                    if (importUri != null) {
                        PasswordPromptDialog(
                            title = "Import identity",
                            message = "Enter the password used when this backup was created.",
                            confirm = false,
                            onDismiss = { importPasswordOpen = null },
                        ) { pw ->
                            importPasswordOpen = null
                            runImportIdentity(importUri, pw)
                        }
                    }

                    // Import password prompt (after a successful QR scan)
                    val importQrText = importQrPasswordOpen
                    if (importQrText != null) {
                        PasswordPromptDialog(
                            title = "Import from QR",
                            message = "Enter the password that was used to encrypt this QR.",
                            confirm = false,
                            onDismiss = { importQrPasswordOpen = null },
                        ) { pw ->
                            importQrPasswordOpen = null
                            runImportIdentityFromBase64(importQrText, pw)
                        }
                    }

                    if (aboutOpen) {
                        // Compute identity strings on each open — cheap (BLAKE2b on 32 bytes).
                        val im = remember { IdentityManager(applicationContext) }
                        val pk = im.getPublicKey()
                        val displayName = viewModel.userName().ifBlank { form.name }
                        val shortId = if (pk != null) "$displayName#${im.fpshort(pk)}" else null
                        val fullFp  = if (pk != null) im.fingerprint(pk) else null
                        AboutDialog(
                            version = BuildConfig.VERSION_NAME,
                            myIdentity = shortId,
                            fullFingerprint = fullFp,
                            onDismiss = { aboutOpen = false },
                        )
                    }

                    // QR display (after successful identity export OR via Show-QR menu)
                    val qrText = qrShowText
                    if (qrText != null) {
                        QrShowDialog(
                            title = "Identity backup QR",
                            caption = "Scan on another device → Import identity. The same password is required to decrypt.",
                            qrText = qrText,
                            onSavePng = { text ->
                                pendingQrSaveText = text
                                qrPngSaveLauncher.launch("fear-identity-qr.png")
                            },
                            onDismiss = { qrShowText = null },
                        )
                    }

                    // Update available dialog (download + install)
                    val info = updateInfo
                    if (info != null) {
                        UpdateDialog(
                            info = info,
                            currentVersion = BuildConfig.VERSION_NAME,
                            downloadProgress = downloadProgress,
                            onConfirm = {
                                downloadProgress = 0
                                startApkDownload(
                                    info = info,
                                    onProgress = { p -> downloadProgress = p },
                                    onDone = { err ->
                                        downloadProgress = null
                                        updateInfo = null
                                        if (err != null) {
                                            Toast.makeText(this@ComposeMainActivity,
                                                "Download failed: $err", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                )
                            },
                            onDismiss = {
                                if (downloadProgress == null) updateInfo = null
                            },
                        )
                    }

                    if (state.call.active) {
                        val title = state.activeChatId.orEmpty().ifEmpty { "Call" }
                        CallOverlay(state.call, title) { viewModel.endAudioCall() }
                    }
                }

                /* Диалог регистрации handle. Открывается из ProfileScreen
                 * («Add server handle»), из ConnectScreen («Register»),
                 * и в потенциальных будущих сценариях — общий для всех. */
                val regHost = registerHandleHost
                if (regHost != null) {
                    com.fear.ui.components.RegisterHandleDialog(
                        serverHost = regHost,
                        initialNickname = profileState.displayName.lowercase()
                            .filter { it.isLetterOrDigit() || it in ".-_" },
                        onDismiss = { registerHandleHost = null },
                        onSubmit = { nick ->
                            viewModel.registerHandle(regHost, form.port, nick) { err ->
                                if (err == null) {
                                    registerHandleHost = null
                                    /* После успешной регистрации перепроверяем
                                     * статус, чтобы UI Connect-экрана сразу
                                     * активировал кнопку Connect. */
                                    viewModel.probeRegistration(regHost, form.port)
                                    Toast.makeText(this@ComposeMainActivity,
                                        "Registered $nick@$regHost",
                                        Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@ComposeMainActivity,
                                        err, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun startAudioCall() {
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        viewModel.startAudioCall()
    }

    private fun startVideoCall() {
        if (!hasAudioPermission() || !hasCameraPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                REQ_VIDEO,
            )
            return
        }
        val keyHex = viewModel.roomKeyHex()
        if (keyHex.length != 64) return
        val intent = Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_REMOTE_IP,    viewModel.serverHost())
            putExtra(VideoCallActivity.EXTRA_REMOTE_PORT,  viewModel.serverPort())
            putExtra(VideoCallActivity.EXTRA_LOCAL_PORT,   0)
            putExtra(VideoCallActivity.EXTRA_ENCRYPTION_KEY, keyHex)
            putExtra(VideoCallActivity.EXTRA_QUALITY,      "medium")
            putExtra(VideoCallActivity.EXTRA_IS_RELAY,     true)
            putExtra(VideoCallActivity.EXTRA_RELAY_ROOM,   viewModel.roomName())
            putExtra(VideoCallActivity.EXTRA_RELAY_NAME,   viewModel.userName())
        }
        startActivity(intent)
    }

    private fun triggerUpdateCheck(onResult: (AppUpdater.UpdateInfo?) -> Unit) {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        val updater = AppUpdater(applicationContext)
        lifecycleScope.launch {
            try {
                val info = updater.checkForUpdate()
                if (info.hasUpdate && info.downloadUrl.isNotEmpty()) {
                    onResult(info)
                } else {
                    onResult(null)
                    Toast.makeText(
                        this@ComposeMainActivity,
                        "You're up to date (v${BuildConfig.VERSION_NAME})",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                onResult(null)
                Toast.makeText(
                    this@ComposeMainActivity,
                    "Update check failed: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Download the APK and ask Android to install it. */
    private fun startApkDownload(
        info: AppUpdater.UpdateInfo,
        onProgress: (Int) -> Unit,
        onDone: (String?) -> Unit,
    ) {
        val updater = AppUpdater(applicationContext)
        lifecycleScope.launch {
            try {
                val apk = updater.downloadApk(info.downloadUrl) { p ->
                    runOnUiThread { onProgress(p) }
                }
                onDone(null)
                updater.installApk(apk)
            } catch (e: Exception) {
                onDone(e.message ?: "unknown")
            }
        }
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    /** Copy a SAF Uri to a real file path in the app's cache so the CLI can read it. */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = displayNameForUri(uri) ?: "file_${System.currentTimeMillis()}"
            val outFile = File(cacheDir, "send/$name")
            outFile.parentFile?.mkdirs()
            contentResolver.openInputStream(uri).use { input ->
                outFile.outputStream().use { output ->
                    input?.copyTo(output) ?: return null
                }
            }
            outFile
        } catch (e: Exception) {
            Log.w("ComposeMainActivity", "copyUriToCache: $e")
            null
        }
    }

    private fun displayNameForUri(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }

    /**
     * Encrypt the live identity under `password` and stream the .fbk bytes
     * into the SAF destination Uri. Runs argon2id on a worker thread.
     *
     * On success, calls `onQrPayload` with the base64-encoded backup blob so
     * the caller can offer scan-to-restore via a QR dialog. Argon2id is a few
     * hundred ms — running it once and reusing the bytes keeps UX snappy.
     */
    private fun runExportIdentity(uri: Uri, password: CharArray,
                                  onQrPayload: (String) -> Unit) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val im = IdentityManager(applicationContext)
                if (!im.hasIdentity()) {
                    runOnUiThread {
                        Toast.makeText(this@ComposeMainActivity,
                            "No identity to export — connect to a room first",
                            Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val pk = im.getPublicKey()!!
                val sk = readIdentitySk(applicationContext)
                if (sk == null) {
                    runOnUiThread {
                        Toast.makeText(this@ComposeMainActivity,
                            "Could not read identity_sk", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val blob = IdentityBackup.exportToBuffer(sk, pk, password)
                sk.fill(0)
                contentResolver.openOutputStream(uri, "wt").use { out ->
                    if (out == null) throw java.io.IOException("openOutputStream returned null")
                    out.write(blob)
                }
                val base64 = android.util.Base64.encodeToString(
                    blob, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "Identity exported (${blob.size} bytes)", Toast.LENGTH_LONG).show()
                    onQrPayload(base64)
                }
            } catch (e: Exception) {
                Log.e("ComposeMainActivity", "export failed", e)
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    /**
     * Encrypt the live identity under `password` purely in memory and hand
     * the base64-encoded blob back via `onQrPayload`. No file is written —
     * used by the 'Show identity as QR' menu shortcut.
     */
    private fun runEncryptToQr(password: CharArray, onQrPayload: (String) -> Unit) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val im = IdentityManager(applicationContext)
                if (!im.hasIdentity()) {
                    runOnUiThread {
                        Toast.makeText(this@ComposeMainActivity,
                            "No identity to encode — connect to a room first",
                            Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val pk = im.getPublicKey()!!
                val sk = readIdentitySk(applicationContext) ?: return@launch
                val blob = IdentityBackup.exportToBuffer(sk, pk, password)
                sk.fill(0)
                val base64 = android.util.Base64.encodeToString(
                    blob, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
                runOnUiThread { onQrPayload(base64) }
            } catch (e: Exception) {
                Log.e("ComposeMainActivity", "encrypt-to-QR failed", e)
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "QR encode failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    /**
     * Render the QR for `qrText` to a PNG and write it to the SAF Uri the
     * user picked. Re-encoding (rather than caching the bitmap) keeps the
     * memory footprint small — a 1024x1024 ARGB bitmap is only alive for
     * the duration of compress().
     */
    private fun saveQrPng(uri: Uri, qrText: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bmp = com.fear.ui.components.renderQrBitmap(qrText)
                contentResolver.openOutputStream(uri).use { out ->
                    if (out == null) throw java.io.IOException("openOutputStream null")
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "QR saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ComposeMainActivity", "saveQrPng failed", e)
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "QR save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Import an identity from a QR scan result. The scanned text is the
     * base64 encoding produced by the export flow; we decode it back into
     * the binary .fbk blob and feed it through IdentityBackup.importFromBuffer.
     */
    private fun runImportIdentityFromBase64(base64: String, password: CharArray) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = try {
                    android.util.Base64.decode(
                        base64,
                        android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP
                    )
                } catch (e: Exception) {
                    throw IllegalArgumentException("scanned QR is not a FEAR backup")
                }
                val identity = IdentityBackup.importFromBuffer(bytes, password)
                writeIdentitySkPk(applicationContext, identity.sk, identity.pk)
                identity.wipe()
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "Identity restored from QR. Reconnect to apply.",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ComposeMainActivity", "QR import failed", e)
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "QR import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    private fun runImportIdentity(uri: Uri, password: CharArray) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri).use { it?.readBytes() }
                    ?: throw java.io.IOException("could not read backup file")
                val identity = IdentityBackup.importFromBuffer(bytes, password)
                writeIdentitySkPk(applicationContext, identity.sk, identity.pk)
                identity.wipe()
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "Identity restored. Reconnect to apply.",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ComposeMainActivity", "import failed", e)
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    /**
     * Read the secret key from the EncryptedFile-wrapped identity store.
     * The file is encoded as PK:<b64>\nSK:<b64>\n  matching the desktop format.
     */
    private fun readIdentitySk(ctx: android.content.Context): ByteArray? {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val identityFile = File(File(ctx.filesDir, ".fear"), "identity")
            if (!identityFile.exists()) return null
            val ef = androidx.security.crypto.EncryptedFile.Builder(
                ctx, identityFile, masterKey,
                androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            val text = ef.openFileInput().use { String(it.readBytes(), Charsets.UTF_8) }
            for (line in text.lineSequence()) {
                if (line.startsWith("SK:")) {
                    return Common.base64Decode(line.substring(3))
                }
            }
            null
        } catch (e: Exception) {
            Log.e("ComposeMainActivity", "readIdentitySk failed", e)
            null
        }
    }

    /**
     * Replace the live identity file (EncryptedFile-wrapped) with imported sk/pk.
     * IdentityManager will pick up the new keys on its next construction.
     *
     * Writes directly to the target path (no tmp+rename) — see the same note
     * in IdentityManager.writeEncryptedText about EncryptedFile keyset binding.
     */
    private fun writeIdentitySkPk(ctx: android.content.Context, sk: ByteArray, pk: ByteArray) {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        val fearDir = File(ctx.filesDir, ".fear").apply { mkdirs() }
        val target = File(fearDir, "identity")
        if (target.exists() && !target.delete()) {
            throw java.io.IOException("could not delete existing identity file")
        }

        val body = "PK:${Common.base64Encode(pk)}\nSK:${Common.base64Encode(sk)}\n"
            .toByteArray(Charsets.UTF_8)
        val ef = androidx.security.crypto.EncryptedFile.Builder(
            ctx, target, masterKey,
            androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        ef.openFileOutput().use { it.write(body) }
        body.fill(0)
    }

    companion object {
        private const val REQ_AUDIO = 1001
        private const val REQ_VIDEO = 1002
    }
}
