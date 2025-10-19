package com.fear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), FearClient.FearClientListener {

    private lateinit var connectionLayout: LinearLayout
    private lateinit var chatLayout: LinearLayout
    private lateinit var hostEditText: EditText
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

    private lateinit var fearClient: FearClient
    private lateinit var messagesAdapter: MessagesAdapter
    private val messages = mutableListOf<Message>()

    private lateinit var callButton: Button

    private lateinit var endCallButton: Button

    private lateinit var callStatusTextView: TextView

    private var currentCallTarget: String = ""
    private var pendingCallParams: CallParams? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    data class CallParams(
        val serverIp: String,
        val serverPort: Int,
        val localPort: Int,
        val encryptionKey: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        println("FEAR_DEBUG: Application starting...")

        // Простая проверка крипто
        try {
            val testNonce = Crypto.generateNonce()
            println("CRYPTO_TEST: Nonce generation works: ${testNonce.size} bytes")
        } catch (e: Exception) {
            println("CRYPTO_TEST: Crypto test failed: ${e.message}")
        }

        initializeViews()
        setupRecyclerView()
        initializeCallViews()
        fearClient = FearClient(this, this)
    }

    private fun initializeCallViews() {
        callButton = findViewById(R.id.callButton)
        endCallButton = findViewById(R.id.endCallButton)
        callStatusTextView = findViewById(R.id.callStatusTextView)

        callButton.setOnClickListener { requestAudioPermissionAndShowDialog() }
        endCallButton.setOnClickListener { endCurrentCall() }

        updateCallUI(false)
    }

    private fun requestAudioPermissionAndShowDialog() {
        println("PERMISSION_DEBUG: requestAudioPermissionAndShowDialog called")
        println("PERMISSION_DEBUG: SDK_INT = ${Build.VERSION.SDK_INT}")

        // Check for audio permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            println("PERMISSION_DEBUG: Permission status = $hasPermission (GRANTED=${PackageManager.PERMISSION_GRANTED})")

            if (hasPermission != PackageManager.PERMISSION_GRANTED) {
                println("PERMISSION_DEBUG: Requesting permission...")
                // Request permission - dialog will be shown after permission is granted
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSION_REQUEST_CODE
                )
                return
            } else {
                println("PERMISSION_DEBUG: Permission already granted")
            }
        }

        // Permission already granted, show dialog
        println("PERMISSION_DEBUG: Showing call dialog")
        showCallDialog()
    }

    private fun showCallDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_call, null)
        val serverIpEditText = dialogView.findViewById<EditText>(R.id.serverIpEditText)
        val serverPortEditText = dialogView.findViewById<EditText>(R.id.serverPortEditText)
        val localPortEditText = dialogView.findViewById<EditText>(R.id.localPortEditText)
        val encryptionKeyEditText = dialogView.findViewById<EditText>(R.id.encryptionKeyEditText)

        AlertDialog.Builder(this)
            .setTitle("Start Audio Call")
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

                if (encryptionKey.isEmpty() || encryptionKey.length != 64) {
                    Toast.makeText(this, "Encryption key must be 64 hex characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                startAudioCall(serverIp, serverPort, localPort, encryptionKey)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAudioCall(serverIp: String, serverPort: Int, localPort: Int, encryptionKey: String) {
        println("CALL_DEBUG: startAudioCall called")
        println("CALL_DEBUG: serverIp=$serverIp, serverPort=$serverPort, localPort=$localPort")
        println("CALL_DEBUG: encryptionKey length=${encryptionKey.length}")

        try {
            // Convert hex key to byte array
            if (encryptionKey.length != 64) {
                throw IllegalArgumentException("Key must be 64 hex characters (32 bytes)")
            }

            println("CALL_DEBUG: Converting hex key to bytes...")
            val keyBytes = ByteArray(32)
            for (i in 0 until 32) {
                val index = i * 2
                val hex = encryptionKey.substring(index, index + 2)
                keyBytes[i] = hex.toInt(16).toByte()
            }

            println("CALL_DEBUG: Calling fearClient.startAudioCallDirect...")
            fearClient.startAudioCallDirect(serverIp, serverPort, localPort, keyBytes)

            println("CALL_DEBUG: Updating UI...")
            currentCallTarget = "$serverIp:$serverPort"
            callStatusTextView.text = "Calling $serverIp:$serverPort..."
            updateCallUI(true)
            println("CALL_DEBUG: startAudioCall completed successfully")
        } catch (e: Exception) {
            println("CALL_DEBUG: Exception in startAudioCall: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "Error starting call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        println("PERMISSION_DEBUG: onRequestPermissionsResult called")
        println("PERMISSION_DEBUG: requestCode = $requestCode, expected = $PERMISSION_REQUEST_CODE")
        println("PERMISSION_DEBUG: grantResults = ${grantResults.toList()}")

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                println("PERMISSION_DEBUG: Permission granted!")
                // Permission granted
                pendingCallParams?.let { params ->
                    println("PERMISSION_DEBUG: Have pending params, starting call")
                    // If we have pending call params, start the call
                    startAudioCall(params.serverIp, params.serverPort, params.localPort, params.encryptionKey)
                    pendingCallParams = null
                } ?: run {
                    println("PERMISSION_DEBUG: No pending params, showing dialog")
                    // No pending params, show the dialog
                    showCallDialog()
                }
            } else {
                println("PERMISSION_DEBUG: Permission denied!")
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
        endCallButton.isEnabled = inCall
        callStatusTextView.visibility = if (inCall) View.VISIBLE else View.GONE
    }

    // FearClientListener implementations
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
            Toast.makeText(this, "Audio call started with $remoteUser", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCallEnded() {
        runOnUiThread {
            updateCallUI(false)
            callStatusTextView.text = "Call ended"
            Toast.makeText(this, "Audio call ended", Toast.LENGTH_SHORT).show()
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

        // Set default values
        hostEditText.setText("77.221.145.132") // Change to your server IP
        portEditText.setText("8888")
//        portEditText.setText(Common.DEFAULT_PORT.toString())
        roomEditText.setText("testroom")
        nameEditText.setText("Android-user")

        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener { disconnect() }
        sendButton.setOnClickListener { sendMessage() }

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

        if (host.isEmpty() || room.isEmpty() || name.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        fearClient.connect(host, port, room, name, key)
        statusTextView.text = "Connecting..."
        connectButton.isEnabled = false
    }

    private fun disconnect() {
        fearClient.disconnect()
    }

    private fun sendMessage() {
        val text = messageEditText.text.toString().trim()
        if (text.isNotEmpty()) {
            println("UI_DEBUG: Sending message: $text")
            try {
                fearClient.sendMessage(text)
                messageEditText.text.clear()
            } catch (e: Exception) {
                println("UI_DEBUG: Exception in sendMessage: ${e.message}")
                e.printStackTrace()
                Toast.makeText(this, "Send error: ${e.message}", Toast.LENGTH_LONG).show()
            }
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

    // FearClientListener implementations
    override fun onConnected() {
        runOnUiThread {
            showChatLayout()
            statusTextView.text = "Connected"
            connectButton.isEnabled = false
            disconnectButton.isEnabled = true
            Toast.makeText(this, "Connected successfully", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "File received: $filename", Toast.LENGTH_LONG).show()
        }
    }

    override fun onFileTransferError(filename: String, error: String) {
        runOnUiThread {
            statusTextView.text = "File error: $error"
            Toast.makeText(this, "File error: $error", Toast.LENGTH_LONG).show()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            statusTextView.text = "Error: $error"
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fearClient.disconnect()
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
        val view = androidx.appcompat.view.ContextThemeWrapper(parent.context, R.style.Theme_FEAR).let { context ->
            android.view.LayoutInflater.from(context).inflate(R.layout.item_message, parent, false)
        }
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        holder.senderTextView.text = message.sender
        holder.contentTextView.text = message.content
        holder.timeTextView.text = dateFormat.format(Date(message.timestamp))
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}