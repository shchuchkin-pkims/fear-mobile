package com.fear

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity(), FearClient.FearClientListener {

    private lateinit var connectionLayout: LinearLayout
    private lateinit var chatLayout: LinearLayout
    private lateinit var hostEditText: AutoCompleteTextView
    private lateinit var portEditText: EditText
    private lateinit var roomEditText: EditText
    private lateinit var nameEditText: EditText
    private lateinit var keyEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var statusTextView: TextView
    private lateinit var onlineUsersTextView: TextView

    private lateinit var callButton: Button
    private lateinit var videoCallButton: Button
    private lateinit var endCallButton: Button
    private lateinit var callStatusTextView: TextView

    private lateinit var identityInfoTextView: TextView
    private lateinit var generateKeyButton: Button
    private lateinit var createRoomButton: Button
    private lateinit var hostRoomButton: Button
    private lateinit var serverStatusTextView: TextView

    private lateinit var fearClient: FearClient
    private lateinit var messagesAdapter: MessagesAdapter
    private val messages get() = sharedMessages

    private val recentHosts = mutableListOf<String>()
    private lateinit var hostsAdapter: ArrayAdapter<String>

    private var currentCallTarget: String = ""
    private var pendingCallParams: CallParams? = null
    private var identityManager: IdentityManager? = null
    private var fearServer: FearServer? = null
    private var isHosting = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        @Volatile
        private var sharedClient: FearClient? = null
        private val sharedMessages = mutableListOf<Message>()
    }

    data class CallParams(
        val serverIp: String,
        val serverPort: Int,
        val localPort: Int,
        val encryptionKey: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(ThemeManager.getTheme(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MessageNotifier.createChannel(this)
        initializeViews()
        setupRecyclerView()
        initializeCallViews()
        setupIdentity()

        // Reuse existing client to survive activity recreation / minimization
        val existing = sharedClient
        if (existing != null) {
            fearClient = existing
            fearClient.setListener(this)
            if (fearClient.isConnected()) {
                showChatLayout()
                statusTextView.text = "Connected"
                connectButton.isEnabled = false
                disconnectButton.isEnabled = true
            }
        } else {
            fearClient = FearClient(applicationContext, this)
            sharedClient = fearClient
        }
    }

    override fun onResume() {
        super.onResume()
        fearClient.isInForeground = true
        if (fearClient.isConnected() && chatLayout.visibility != View.VISIBLE) {
            showChatLayout()
            statusTextView.text = "Connected"
        }
    }

    override fun onPause() {
        super.onPause()
        fearClient.isInForeground = false
    }

    private fun setupIdentity() {
        identityManager = IdentityManager(this)
        updateIdentityUI()

        generateKeyButton.setOnClickListener {
            val im = identityManager ?: return@setOnClickListener
            if (im.hasIdentity()) {
                AlertDialog.Builder(this)
                    .setTitle("Regenerate Key")
                    .setMessage("This will replace your current identity key. Other users will see a key change warning. Continue?")
                    .setPositiveButton("Regenerate") { _, _ -> doGenerateIdentity(im) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                doGenerateIdentity(im)
            }
        }
    }

    private fun doGenerateIdentity(im: IdentityManager) {
        if (im.generateIdentity()) {
            Toast.makeText(this, "Identity key generated", Toast.LENGTH_SHORT).show()
            updateIdentityUI()
            fearClient.refreshIdentity()
        } else {
            Toast.makeText(this, "Failed to generate key", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateIdentityUI() {
        val im = identityManager ?: return
        if (im.hasIdentity()) {
            val pk = im.getPublicKey()!!
            val fp = im.fingerprint(pk)
            identityInfoTextView.text = "Identity: $fp"
            generateKeyButton.text = "Regenerate Key"
            generateKeyButton.visibility = View.VISIBLE
        } else {
            identityInfoTextView.text = "No identity key"
            generateKeyButton.text = "Generate Identity Key"
            generateKeyButton.visibility = View.VISIBLE
        }
    }

    private fun initializeCallViews() {
        callButton = findViewById(R.id.callButton)
        videoCallButton = findViewById(R.id.videoCallButton)
        endCallButton = findViewById(R.id.endCallButton)
        callStatusTextView = findViewById(R.id.callStatusTextView)

        callButton.setOnClickListener { requestAudioPermissionAndShowDialog() }
        videoCallButton.setOnClickListener { showVideoCallDialog() }
        endCallButton.setOnClickListener { endCurrentCall() }

        findViewById<Button>(R.id.menuButton).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Trusted Keys")
            val themeLabel = if (ThemeManager.isDark(this)) "Light Theme" else "Dark Theme"
            popup.menu.add(themeLabel)
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Trusted Keys" -> {
                        startActivity(Intent(this, TrustedKeysActivity::class.java))
                        true
                    }
                    "Light Theme" -> {
                        ThemeManager.setTheme(this, ThemeManager.THEME_LIGHT)
                        true
                    }
                    "Dark Theme" -> {
                        ThemeManager.setTheme(this, ThemeManager.THEME_DARK)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        updateCallUI(false)
    }

    private fun requestAudioPermissionAndShowDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
                return
            }
        }
        showCallDialog()
    }

    private fun showCallDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_call, null)
        val serverIpEditText = dialogView.findViewById<EditText>(R.id.serverIpEditText)
        val serverPortEditText = dialogView.findViewById<EditText>(R.id.serverPortEditText)
        val localPortEditText = dialogView.findViewById<EditText>(R.id.localPortEditText)
        val encryptionKeyEditText = dialogView.findViewById<EditText>(R.id.encryptionKeyEditText)

        // Pre-fill with connected host and room key
        serverIpEditText.setText(hostEditText.text.toString().trim())
        val roomKeyHex = fearClient.getRoomKeyHex()
        if (roomKeyHex.isNotEmpty()) {
            encryptionKeyEditText.setText(roomKeyHex)
            encryptionKeyEditText.visibility = View.GONE
            dialogView.findViewById<View>(R.id.encryptionKeyLabel).visibility = View.GONE
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Audio Call")
            .setView(dialogView)
            .setPositiveButton("Call") { _, _ ->
                val serverIp = serverIpEditText.text.toString().trim()
                val serverPort = serverPortEditText.text.toString().toIntOrNull() ?: 50000
                val localPortText = localPortEditText.text.toString().trim()
                val localPort = if (localPortText.isEmpty()) 0 else localPortText.toIntOrNull() ?: 0
                val encryptionKey = encryptionKeyEditText.text.toString().trim()

                if (serverIp.isEmpty()) {
                    Toast.makeText(this, "Please enter server IP", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (encryptionKey.length != 64) {
                    Toast.makeText(this, "Key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startAudioCall(serverIp, serverPort, localPort, encryptionKey)
            }
            .setNeutralButton("Listen") { _, _ ->
                val localPortText = localPortEditText.text.toString().trim()
                val localPort = if (localPortText.isEmpty()) 50000 else localPortText.toIntOrNull() ?: 50000
                val encryptionKey = encryptionKeyEditText.text.toString().trim()

                if (encryptionKey.length != 64) {
                    Toast.makeText(this, "Key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                startAudioListen(localPort, encryptionKey)
            }
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()

        // Add relay button if connected to a remote server
        if (fearClient.isConnected() && fearClient.getServerHost().isNotEmpty()) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text = "Relay"
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val encryptionKey = encryptionKeyEditText.text.toString().trim()
                if (encryptionKey.length != 64) {
                    Toast.makeText(this, "Key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startAudioRelay(encryptionKey)
                dialog.dismiss()
            }
        }
    }

    private fun showVideoCallDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_video_call, null)
        val serverIpEditText = dialogView.findViewById<EditText>(R.id.videoServerIpEditText)
        val serverPortEditText = dialogView.findViewById<EditText>(R.id.videoServerPortEditText)
        val encryptionKeyEditText = dialogView.findViewById<EditText>(R.id.videoEncryptionKeyEditText)
        val qualitySpinner = dialogView.findViewById<Spinner>(R.id.videoQualitySpinner)

        // Pre-fill with connected host and room key
        serverIpEditText.setText(hostEditText.text.toString().trim())
        val roomKeyHex = fearClient.getRoomKeyHex()
        if (roomKeyHex.isNotEmpty()) {
            encryptionKeyEditText.setText(roomKeyHex)
            encryptionKeyEditText.visibility = View.GONE
            dialogView.findViewById<View>(R.id.videoEncryptionKeyLabel).visibility = View.GONE
        }

        val qualities = arrayOf("Low (320x240)", "Medium (640x480)", "High (1280x720)")
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)
        qualitySpinner.setSelection(1) // Medium default

        val localPortEditText = dialogView.findViewById<EditText>(R.id.videoLocalPortEditText)

        val builder = AlertDialog.Builder(this)
            .setTitle("Video Call")
            .setView(dialogView)
            .setPositiveButton("Call") { _, _ ->
                val serverIp = serverIpEditText.text.toString().trim()
                val serverPort = serverPortEditText.text.toString().toIntOrNull() ?: 50000
                val encryptionKey = encryptionKeyEditText.text.toString().trim()
                val quality = when (qualitySpinner.selectedItemPosition) {
                    0 -> "low"
                    2 -> "high"
                    else -> "medium"
                }

                if (serverIp.isEmpty()) {
                    Toast.makeText(this, "Please enter server IP", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (encryptionKey.length != 64) {
                    Toast.makeText(this, "Key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val intent = Intent(this, VideoCallActivity::class.java).apply {
                    putExtra(VideoCallActivity.EXTRA_REMOTE_IP, serverIp)
                    putExtra(VideoCallActivity.EXTRA_REMOTE_PORT, serverPort)
                    putExtra(VideoCallActivity.EXTRA_LOCAL_PORT, 0)
                    putExtra(VideoCallActivity.EXTRA_ENCRYPTION_KEY, encryptionKey)
                    putExtra(VideoCallActivity.EXTRA_QUALITY, quality)
                }
                startActivity(intent)
            }
            .setNeutralButton("Listen") { _, _ ->
                val localPortText = localPortEditText.text.toString().trim()
                val localPort = if (localPortText.isEmpty()) 50000 else localPortText.toIntOrNull() ?: 50000
                val encryptionKey = encryptionKeyEditText.text.toString().trim()
                val quality = when (qualitySpinner.selectedItemPosition) {
                    0 -> "low"
                    2 -> "high"
                    else -> "medium"
                }

                if (encryptionKey.length != 64) {
                    Toast.makeText(this, "Key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }

                val intent = Intent(this, VideoCallActivity::class.java).apply {
                    putExtra(VideoCallActivity.EXTRA_REMOTE_IP, "")
                    putExtra(VideoCallActivity.EXTRA_REMOTE_PORT, 0)
                    putExtra(VideoCallActivity.EXTRA_LOCAL_PORT, localPort)
                    putExtra(VideoCallActivity.EXTRA_ENCRYPTION_KEY, encryptionKey)
                    putExtra(VideoCallActivity.EXTRA_QUALITY, quality)
                    putExtra(VideoCallActivity.EXTRA_IS_LISTENING, true)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()

        // Add relay button if connected to a remote server
        if (fearClient.isConnected() && fearClient.getServerHost().isNotEmpty()) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text = "Relay"
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val encryptionKey = encryptionKeyEditText.text.toString().trim()
                val quality = when (qualitySpinner.selectedItemPosition) {
                    0 -> "low"
                    2 -> "high"
                    else -> "medium"
                }

                if (encryptionKey.length != 64) {
                    Toast.makeText(this, "Key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val intent = Intent(this, VideoCallActivity::class.java).apply {
                    putExtra(VideoCallActivity.EXTRA_REMOTE_IP, fearClient.getServerHost())
                    putExtra(VideoCallActivity.EXTRA_REMOTE_PORT, fearClient.getServerPort())
                    putExtra(VideoCallActivity.EXTRA_LOCAL_PORT, 0)
                    putExtra(VideoCallActivity.EXTRA_ENCRYPTION_KEY, encryptionKey)
                    putExtra(VideoCallActivity.EXTRA_QUALITY, quality)
                    putExtra(VideoCallActivity.EXTRA_IS_RELAY, true)
                    putExtra(VideoCallActivity.EXTRA_RELAY_ROOM, fearClient.getCurrentRoom())
                    putExtra(VideoCallActivity.EXTRA_RELAY_NAME, fearClient.getCurrentName())
                }
                startActivity(intent)
                dialog.dismiss()
            }
        }
    }

    private fun startAudioCall(serverIp: String, serverPort: Int, localPort: Int, encryptionKey: String) {
        try {
            if (encryptionKey.length != 64) {
                throw IllegalArgumentException("Key must be 64 hex characters (32 bytes)")
            }
            val keyBytes = ByteArray(32)
            for (i in 0 until 32) {
                keyBytes[i] = encryptionKey.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            fearClient.startAudioCallDirect(serverIp, serverPort, localPort, keyBytes)
            currentCallTarget = "$serverIp:$serverPort"
            callStatusTextView.text = "Calling $serverIp:$serverPort..."
            updateCallUI(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAudioListen(localPort: Int, encryptionKey: String) {
        try {
            if (encryptionKey.length != 64) {
                throw IllegalArgumentException("Key must be 64 hex characters (32 bytes)")
            }
            val keyBytes = ByteArray(32)
            for (i in 0 until 32) {
                keyBytes[i] = encryptionKey.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            fearClient.startAudioListenDirect(localPort, keyBytes)
            currentCallTarget = "Listening on :$localPort"
            callStatusTextView.text = "Listening on port $localPort..."
            updateCallUI(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting listen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAudioRelay(encryptionKey: String) {
        try {
            if (encryptionKey.length != 64) {
                throw IllegalArgumentException("Key must be 64 hex characters (32 bytes)")
            }
            val keyBytes = ByteArray(32)
            for (i in 0 until 32) {
                keyBytes[i] = encryptionKey.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            fearClient.startAudioRelay(keyBytes)
            val host = fearClient.getServerHost()
            val port = fearClient.getServerPort()
            currentCallTarget = "Relay $host:$port"
            callStatusTextView.text = "Relay call via $host:$port..."
            updateCallUI(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting relay call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingCallParams?.let { params ->
                    startAudioCall(params.serverIp, params.serverPort, params.localPort, params.encryptionKey)
                    pendingCallParams = null
                } ?: showCallDialog()
            } else {
                Toast.makeText(this, "Audio permission is required for calls", Toast.LENGTH_LONG).show()
                pendingCallParams = null
            }
        }
    }

    private fun endCurrentCall() {
        fearClient.endAudioCall()
        updateCallUI(false)
        callStatusTextView.text = "Call ended"
    }

    private fun updateCallUI(inCall: Boolean) {
        callButton.isEnabled = !inCall
        videoCallButton.isEnabled = !inCall
        endCallButton.isEnabled = inCall
        callStatusTextView.visibility = if (inCall) View.VISIBLE else View.GONE
    }

    override fun onCallRequestReceived(fromUser: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Incoming Audio Call")
                .setMessage("$fromUser is calling you")
                .setPositiveButton("Accept") { _, _ ->
                    fearClient.acceptAudioCall()
                    currentCallTarget = fromUser
                    updateCallUI(true)
                    callStatusTextView.text = "In call with $fromUser"
                }
                .setNegativeButton("Reject") { _, _ ->
                    fearClient.rejectAudioCall()
                }
                .show()
        }
    }

    override fun onCallStarted(remoteUser: String, isInitiator: Boolean) {
        runOnUiThread {
            callStatusTextView.text = "In call with $remoteUser"
            updateCallUI(true)
        }
    }

    override fun onCallEnded() {
        runOnUiThread {
            updateCallUI(false)
            callStatusTextView.text = "Call ended"
        }
    }

    private fun initializeViews() {
        connectionLayout = findViewById(R.id.connectionLayout)
        chatLayout = findViewById(R.id.chatLayout)
        hostEditText = findViewById(R.id.hostEditText)
        portEditText = findViewById(R.id.portEditText)
        roomEditText = findViewById(R.id.roomEditText)
        nameEditText = findViewById(R.id.nameEditText)
        keyEditText = findViewById(R.id.keyEditText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        messageEditText = findViewById(R.id.messageEditText)
        sendButton = findViewById(R.id.sendButton)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        statusTextView = findViewById(R.id.statusTextView)
        onlineUsersTextView = findViewById(R.id.onlineUsersTextView)

        identityInfoTextView = findViewById(R.id.identityInfoTextView)
        generateKeyButton = findViewById(R.id.generateKeyButton)
        createRoomButton = findViewById(R.id.createRoomButton)
        hostRoomButton = findViewById(R.id.hostRoomButton)
        serverStatusTextView = findViewById(R.id.serverStatusTextView)

        createRoomButton.setOnClickListener { createRoom() }
        hostRoomButton.setOnClickListener { hostRoom() }

        recentHosts.addAll(loadRecentHosts())
        hostsAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, recentHosts)
        hostEditText.setAdapter(hostsAdapter)

        hostEditText.setText("77.221.145.132")
        portEditText.setText("8888")
        roomEditText.setText("testroom")
        nameEditText.setText("Android-user")
        keyEditText.setText("")

        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener { disconnect() }
        sendButton.setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.clearChatButton).setOnClickListener {
            messages.clear()
            messagesAdapter.notifyDataSetChanged()
        }

        showConnectionLayout()
    }

    private fun setupRecyclerView() {
        messagesAdapter = MessagesAdapter(messages)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messagesAdapter
    }

    private fun connect() {
        val host = hostEditText.text.toString().trim()
        val port = portEditText.text.toString().toIntOrNull() ?: Common.DEFAULT_PORT
        val room = roomEditText.text.toString().trim()
        val name = nameEditText.text.toString().trim()
        val key = keyEditText.text.toString().trim()

        if (host.isEmpty() || room.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "Please fill host, room, and name", Toast.LENGTH_SHORT).show()
            return
        }

        saveRecentHost(host)

        val mode = if (key.isEmpty()) {
            FearClient.ConnectMode.JOIN_ROOM
        } else {
            FearClient.ConnectMode.MANUAL_KEY
        }

        fearClient.connect(host, port, room, name, key, mode)
        statusTextView.text = if (mode == FearClient.ConnectMode.JOIN_ROOM) {
            "Joining room (key exchange)..."
        } else {
            "Connecting..."
        }
        connectButton.isEnabled = false
    }

    private fun createRoom() {
        val host = hostEditText.text.toString().trim()
        val port = portEditText.text.toString().toIntOrNull() ?: Common.DEFAULT_PORT
        val room = roomEditText.text.toString().trim()
        val name = nameEditText.text.toString().trim()

        if (host.isEmpty() || room.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "Please fill host, room, and name", Toast.LENGTH_SHORT).show()
            return
        }

        saveRecentHost(host)

        fearClient.connect(host, port, room, name, "", FearClient.ConnectMode.CREATE_ROOM)
        statusTextView.text = "Creating room..."
        connectButton.isEnabled = false
    }

    private fun disconnect() {
        fearClient.disconnect()
        if (isHosting) {
            fearServer?.stop()
            fearServer = null
            isHosting = false
            ServerService.stop(this)
            serverStatusTextView.visibility = View.GONE
        }
    }

    private fun hostRoom() {
        val port = portEditText.text.toString().toIntOrNull() ?: Common.DEFAULT_PORT
        val room = roomEditText.text.toString().trim()
        val name = nameEditText.text.toString().trim()
        val key = keyEditText.text.toString().trim()

        if (room.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "Please fill room and name", Toast.LENGTH_SHORT).show()
            return
        }

        // Determine mode: if key is empty, auto-generate (CREATE_ROOM)
        val connectMode = if (key.isEmpty()) {
            FearClient.ConnectMode.CREATE_ROOM
        } else {
            FearClient.ConnectMode.MANUAL_KEY
        }

        hostRoomButton.isEnabled = false
        connectButton.isEnabled = false

        val localIp = getLocalIpAddress() ?: "unknown"

        val server = FearServer(port, object : FearServer.ServerListener {
            override fun onServerStarted(port: Int) {
                runOnUiThread {
                    isHosting = true
                    ServerService.start(this@MainActivity)
                    serverStatusTextView.text = "Server: $localIp:$port"
                    serverStatusTextView.visibility = View.VISIBLE
                }
                // Connect to our own server as a client
                fearClient.connect("127.0.0.1", port, room, name, key, connectMode)
            }

            override fun onServerStopped() {
                runOnUiThread {
                    serverStatusTextView.visibility = View.GONE
                    hostRoomButton.isEnabled = true
                }
            }

            override fun onServerError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Server error: $error", Toast.LENGTH_LONG).show()
                    hostRoomButton.isEnabled = true
                    connectButton.isEnabled = true
                }
            }

            override fun onClientConnected(name: String, room: String) {
                runOnUiThread {
                    serverStatusTextView.text = "Server: $localIp:$port — room '$room'"
                }
            }

            override fun onClientDisconnected(name: String, room: String) {
                runOnUiThread {
                    serverStatusTextView.text = "Server: $localIp:$port — room '$room'"
                }
            }
        })

        fearServer = server
        server.start()
    }

    private fun loadRecentHosts(): List<String> {
        val file = java.io.File(filesDir, ".fear/recent_hosts")
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.take(10)
    }

    private fun saveRecentHost(host: String) {
        if (host.isBlank()) return
        recentHosts.remove(host)
        recentHosts.add(0, host)
        if (recentHosts.size > 10) recentHosts.subList(10, recentHosts.size).clear()
        hostsAdapter.notifyDataSetChanged()

        val dir = java.io.File(filesDir, ".fear")
        if (!dir.exists()) dir.mkdirs()
        java.io.File(dir, "recent_hosts").writeText(recentHosts.joinToString("\n"))
    }

    private fun getLocalIpAddress(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun sendMessage() {
        val text = messageEditText.text.toString().trim()
        if (text.isNotEmpty()) {
            fearClient.sendMessage(text)
            messageEditText.text.clear()
        }
    }

    private fun showConnectionLayout() {
        connectionLayout.visibility = View.VISIBLE
        chatLayout.visibility = View.GONE
    }

    private fun showChatLayout() {
        connectionLayout.visibility = View.GONE
        chatLayout.visibility = View.VISIBLE
    }

    // --- FearClientListener ---

    override fun onConnected() {
        runOnUiThread {
            showChatLayout()
            statusTextView.text = "Connected"
            connectButton.isEnabled = false
            disconnectButton.isEnabled = true
            // Start foreground service to keep connection alive in background
            ChatService.start(this)
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            showConnectionLayout()
            statusTextView.text = "Disconnected"
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            messages.clear()
            messagesAdapter.notifyDataSetChanged()
            // Stop foreground service
            ChatService.stop(this)
        }
    }

    override fun onMessageReceived(message: Message) {
        runOnUiThread {
            messages.add(message)
            messagesAdapter.notifyItemInserted(messages.size - 1)
            messagesRecyclerView.scrollToPosition(messages.size - 1)
        }
    }

    override fun onFileTransferProgress(filename: String, progress: Float) {
        runOnUiThread {
            statusTextView.text = "Receiving $filename: ${(progress * 100).toInt()}%"
        }
    }

    override fun onFileTransferComplete(filename: String) {
        runOnUiThread {
            statusTextView.text = "File received: $filename"
        }
    }

    override fun onFileTransferError(filename: String, error: String) {
        runOnUiThread {
            statusTextView.text = "File error: $error"
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            statusTextView.text = "Error: $error"
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onContactsUpdated(contacts: List<String>) {
        runOnUiThread {
            if (contacts.isNotEmpty()) {
                onlineUsersTextView.visibility = View.VISIBLE
                onlineUsersTextView.text = "Online: ${contacts.joinToString(", ")}"
            } else {
                onlineUsersTextView.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't disconnect — keep chat session alive when minimized/recreated
    }
}

class MessagesAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<MessagesAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val senderTextView: TextView = itemView.findViewById(R.id.senderTextView)
        val contentTextView: TextView = itemView.findViewById(R.id.contentTextView)
        val timeTextView: TextView = itemView.findViewById(R.id.timeTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        holder.senderTextView.text = message.sender
        holder.contentTextView.text = message.content
        holder.timeTextView.text = dateFormat.format(Date(message.timestamp))

        holder.itemView.setOnLongClickListener {
            val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
            Toast.makeText(it.context, "Message copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun getItemCount(): Int = messages.size
}
