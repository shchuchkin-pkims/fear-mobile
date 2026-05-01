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
import com.fear.TrustedKeysActivity
import com.fear.VideoCallActivity
import com.fear.ui.components.CallOverlay
import com.fear.ui.components.MenuSheet
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

        setContent {
            // App-wide theme override (null = follow system, true/false = forced).
            var darkOverride by remember { mutableStateOf<Boolean?>(null) }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectiveDark = darkOverride ?: systemDark

            FearTheme(darkTheme = effectiveDark) {
                val state by viewModel.uiState.collectAsState()
                val form  by viewModel.form.collectAsState()
                var menuOpen by remember { mutableStateOf(false) }

                // File picker: SAF document → copy to cache → /sendfile <path>
                val filePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val cached = copyUriToCache(uri)
                    if (cached != null) viewModel.sendFilePath(cached.absolutePath)
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

    companion object {
        private const val REQ_AUDIO = 1001
        private const val REQ_VIDEO = 1002
    }
}
