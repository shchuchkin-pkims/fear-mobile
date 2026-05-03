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
import com.fear.ui.components.MenuSheet
import com.fear.ui.components.PasswordPromptDialog
import com.fear.ui.components.UpdateDialog
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Eagerly construct IdentityManager so its init block runs the
        // plaintext-to-EncryptedFile migration before user interacts.
        // Cheap: ~ms when no migration is needed, ~tens of ms otherwise.
        IdentityManager(applicationContext)

        setContent {
            // App-wide theme override (null = follow system, true/false = forced).
            var darkOverride by remember { mutableStateOf<Boolean?>(null) }
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
                val context = androidx.compose.ui.platform.LocalContext.current

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

                if (!state.isConnected) {
                    ConnectScreen(
                        form = form,
                        isConnecting = state.isConnecting,
                        errorBanner = state.errorBanner,
                        onUpdate = { viewModel.updateForm { _ -> it } },
                        onConnect = viewModel::connect,
                        onDismissError = viewModel::dismissError,
                    )
                } else {
                    ChatScreen(
                        state = state,
                        onChatSelected = viewModel::openChat,
                        onSendMessage  = viewModel::sendMessage,
                        onMenuClick    = { menuOpen = true },
                        onAudioCall    = { startAudioCall() },
                        onVideoCall    = { startVideoCall() },
                        onAttach       = {
                            // OpenDocument with "*/*" — picks any file type
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onBack         = viewModel::closeActiveChat,
                    )

                    if (menuOpen) {
                        MenuSheet(
                            onDismiss = { menuOpen = false },
                            onDisconnect = { viewModel.disconnect() },
                            onTrustedKeys = {
                                startActivity(Intent(this@ComposeMainActivity, TrustedKeysActivity::class.java))
                            },
                            onToggleTheme = { darkOverride = !effectiveDark },
                            onCheckUpdates = { triggerUpdateCheck { updateInfo = it } },
                            onAbout = { aboutOpen = true },
                            onExportIdentity = {
                                exportSaveLauncher.launch("fear-identity-backup.fbk")
                            },
                            onImportIdentity = {
                                importOpenLauncher.launch(arrayOf("*/*"))
                            },
                        )
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
                                runExportIdentity(uri, pw)
                                pw.fill(' ')
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
                            pw.fill(' ')
                        }
                    }

                    if (aboutOpen) {
                        AboutDialog(BuildConfig.VERSION_NAME) { aboutOpen = false }
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
     * Encrypt the live identity (loaded by IdentityManager from filesDir) under
     * `password` and stream the .fbk bytes into the SAF destination Uri. Runs
     * argon2id on a worker thread to keep the UI responsive.
     */
    private fun runExportIdentity(uri: Uri, password: CharArray) {
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
                // identity_sk is not directly exposed; reconstruct via reflection-free path:
                // ed25519 sk = seed||pk, recoverable from sign() but we need raw sk.
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
                runOnUiThread {
                    Toast.makeText(this@ComposeMainActivity,
                        "Identity exported (${blob.size} bytes)", Toast.LENGTH_LONG).show()
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
     */
    private fun writeIdentitySkPk(ctx: android.content.Context, sk: ByteArray, pk: ByteArray) {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        val fearDir = File(ctx.filesDir, ".fear").apply { mkdirs() }
        val target = File(fearDir, "identity")
        val tmp = File(fearDir, "identity.tmp")
        if (tmp.exists()) tmp.delete()

        val body = "PK:${Common.base64Encode(pk)}\nSK:${Common.base64Encode(sk)}\n"
            .toByteArray(Charsets.UTF_8)
        val ef = androidx.security.crypto.EncryptedFile.Builder(
            ctx, tmp, masterKey,
            androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        ef.openFileOutput().use { it.write(body) }
        body.fill(0)

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
    }

    companion object {
        private const val REQ_AUDIO = 1001
        private const val REQ_VIDEO = 1002
    }
}
