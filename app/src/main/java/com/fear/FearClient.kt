package com.fear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.*

class FearClient(
    private val context: Context,
    @Volatile private var listener: FearClientListener
) {
    interface FearClientListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(message: Message)
        fun onFileTransferProgress(filename: String, progress: Float)
        fun onFileTransferComplete(filename: String)
        fun onFileTransferError(filename: String, error: String)
        fun onError(error: String)
        fun onCallRequestReceived(fromUser: String)
        fun onCallStarted(remoteUser: String, isInitiator: Boolean)
        fun onCallEnded()
        fun onAudioStatsUpdated(rttMs: Int) {}
        fun onContactsUpdated(contacts: List<String>)
    }

    private var socket: Socket? = null
    private var receiveJob: Job? = null
    @Volatile private var isConnected = false
    @Volatile var isInForeground = true
    @Volatile var lastContacts: List<String> = emptyList()

    fun setListener(newListener: FearClientListener) {
        listener = newListener
    }

    fun isConnected(): Boolean = isConnected
    private var currentRoom = ""
    private var clientName = ""
    private var serverHost = ""
    private var serverPort = 0
    private var roomKey = ByteArray(0)

    fun getServerHost(): String = serverHost
    fun getServerPort(): Int = serverPort
    fun getCurrentRoom(): String = currentRoom
    fun getCurrentName(): String = clientName

    fun getRoomKeyHex(): String {
        if (roomKey.isEmpty()) return ""
        return roomKey.joinToString("") { "%02x".format(it) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentFileTransfer: FileTransfer? = null

    enum class ConnectMode { MANUAL_KEY, CREATE_ROOM, JOIN_ROOM }

    private var audioCallManager: AudioCallManager? = null
    private var pendingCallRequest: AudioCallRequest? = null
    private var identityManager: IdentityManager? = null

    private fun getOrCreateAudioCallManager(): AudioCallManager {
        if (audioCallManager == null) {
            audioCallManager = AudioCallManager(context, object : AudioCallManager.AudioCallListener {
                override fun onCallStateChanged(state: AudioCallState) {}

                override fun onCallRequestReceived(fromUser: String) {}

                override fun onCallError(error: String) {
                    notifyError("Audio call error: $error")
                }

                override fun onCallStarted(remoteUser: String, isInitiator: Boolean) {
                    handler.post { listener.onCallStarted(remoteUser, isInitiator) }
                }

                override fun onCallEnded() {
                    handler.post { listener.onCallEnded() }
                }

                override fun onStatsUpdated(rttMs: Int) {
                    handler.post { listener.onAudioStatsUpdated(rttMs) }
                }
            })
        }
        return audioCallManager!!
    }

    fun connect(host: String, port: Int, room: String, name: String,
                keyBase64: String, mode: ConnectMode = ConnectMode.MANUAL_KEY) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Close any previous connection before starting a new one
                receiveJob?.cancel()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                isConnected = false

                currentRoom = room
                clientName = name
                serverHost = host
                serverPort = port

                when (mode) {
                    ConnectMode.MANUAL_KEY -> {
                        val key = Common.base64Decode(keyBase64)
                        if (key == null || key.size != Common.CRYPTO_AEAD_AES256GCM_KEYBYTES) {
                            notifyError("Invalid key")
                            return@launch
                        }
                        roomKey = key
                    }
                    ConnectMode.CREATE_ROOM -> {
                        roomKey = ByteArray(Common.CRYPTO_AEAD_AES256GCM_KEYBYTES)
                        SecureRandom().nextBytes(roomKey)
                        val b64 = Common.base64Encode(roomKey)
                        if (BuildConfig.DEBUG) Log.i("FearClient", "[create] Room key generated: $b64")
                        notifyMessageReceived(Message(room, "system",
                            "[create] Room key generated", System.currentTimeMillis()))
                    }
                    ConnectMode.JOIN_ROOM -> {
                        // Key will be obtained via ECDH exchange after socket connection
                        roomKey = ByteArray(0)
                    }
                }

                socket = Socket(host, port)
                isConnected = true

                // Initialize identity manager
                identityManager = IdentityManager(context)
                val im = identityManager
                Log.d("FearClient", "Identity loaded: hasIdentity=${im?.hasIdentity()}")
                if (im?.hasIdentity() == true) {
                    val fp = im.fingerprint(im.getPublicKey()!!)
                    Log.d("FearClient", "Identity fingerprint: $fp")
                }

                val s = socket ?: return@launch

                // If join mode, perform ECDH key exchange before proceeding
                if (mode == ConnectMode.JOIN_ROOM) {
                    notifyMessageReceived(Message(room, "system",
                        "[join] Requesting room key via ECDH exchange...", System.currentTimeMillis()))
                    val receivedKey = ecdhJoinRoom(s)
                    if (receivedKey == null) {
                        notifyError("Key exchange failed: no response (timeout)")
                        socket?.close()
                        socket = null
                        isConnected = false
                        return@launch
                    }
                    roomKey = receivedKey
                    notifyMessageReceived(Message(room, "system",
                        "[join] Room key received!", System.currentTimeMillis()))
                }

                notifyConnected()

                // Send registration message (empty text) so server registers us
                sendRegistrationMessage(s)

                // Send identity announce if we have a key
                sendIdentityAnnounce(s)

                // Start receiving messages
                startReceiving()

            } catch (e: Exception) {
                notifyError("Connection failed: ${e.message}")
            }
        }
    }

    /**
     * Send an initial empty text message to register with the server.
     * The server reads room/name from the frame header on first message.
     */
    private fun sendRegistrationMessage(socket: Socket) {
        val plaintext = " ".toByteArray(Charsets.UTF_8)
        val nonce = Crypto.generateNonce()
        val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = clientName.toByteArray(Charsets.UTF_8)
        val ad = buildAd(roomBytes, nameBytes)
        val ciphertext = Crypto.encrypt(plaintext, ad, nonce, roomKey) ?: return
        val frame = buildFrame(roomBytes, nameBytes, nonce, Common.MSG_TYPE_TEXT, ciphertext)
        Common.sendAll(socket, frame)
    }

    /**
     * Send IDENTITY_ANNOUNCE with [pk(32)][sig_over_name(64)] if we have an identity.
     */
    private fun sendIdentityAnnounce(socket: Socket) {
        val im = identityManager
        if (im == null) {
            Log.w("FearClient", "sendIdentityAnnounce: identityManager is null")
            return
        }
        if (!im.hasIdentity()) {
            Log.w("FearClient", "sendIdentityAnnounce: no identity key")
            return
        }
        val payload = im.buildIdentityAnnouncePayload(clientName)
        if (payload == null) {
            Log.e("FearClient", "sendIdentityAnnounce: buildPayload returned null (signing failed?)")
            return
        }
        Log.d("FearClient", "sendIdentityAnnounce: sending ${payload.size} bytes for name='$clientName'")
        sendEncryptedMessage(socket, Common.MSG_TYPE_IDENTITY_ANNOUNCE, payload)
    }

    fun getIdentityManager(): IdentityManager? = identityManager

    /**
     * Reload identity from disk and re-send IDENTITY_ANNOUNCE if connected.
     * Call this after generating a new identity key while already connected.
     */
    fun refreshIdentity() {
        identityManager = IdentityManager(context)
        val im = identityManager
        Log.d("FearClient", "refreshIdentity: hasIdentity=${im?.hasIdentity()}, isConnected=$isConnected")
        if (isConnected) {
            CoroutineScope(Dispatchers.IO).launch {
                val s = socket ?: return@launch
                sendIdentityAnnounce(s)
            }
        }
    }

    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            receiveJob?.cancel()
            socket?.close()
            socket = null
            isConnected = false
            serverHost = ""
            serverPort = 0
            notifyDisconnected()
        }
    }

    fun sendMessage(text: String) {
        if (!isConnected) {
            notifyError("Not connected")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = socket ?: return@launch

                if (text.startsWith("/sendfile ")) {
                    val filename = text.substring(10).trim()
                    sendFile(socket, filename)
                } else {
                    sendTextMessage(socket, text)
                }
            } catch (e: Exception) {
                notifyError("Send failed: ${e.message}")
            }
        }
    }

    fun startAudioCall(targetUser: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = socket ?: return@launch
                val request = AudioCallRequest(currentRoom, clientName, targetUser)
                sendAudioCallMessage(socket, Common.MSG_TYPE_AUDIO_CALL_REQUEST, request)
            } catch (e: Exception) {
                notifyError("Failed to start audio call: ${e.message}")
            }
        }
    }

    fun acceptAudioCall() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = socket ?: return@launch
                val request = pendingCallRequest ?: return@launch

                val manager = getOrCreateAudioCallManager()
                manager.initialize(roomKey)

                val udpInfo = AudioUdpInfo(
                    currentRoom, clientName,
                    manager.getLocalUdpPort(),
                    manager.getLocalNoncePrefix()
                )
                sendAudioUdpInfo(socket, udpInfo)
                pendingCallRequest = null
            } catch (e: Exception) {
                notifyError("Failed to accept audio call: ${e.message}")
            }
        }
    }

    fun rejectAudioCall() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = socket ?: return@launch
                val request = pendingCallRequest ?: return@launch
                val response = AudioCallResponse(currentRoom, clientName, request.fromUser, false)
                sendAudioCallMessage(socket, Common.MSG_TYPE_AUDIO_CALL_REJECT, response)
                pendingCallRequest = null
            } catch (e: Exception) {
                notifyError("Failed to reject audio call: ${e.message}")
            }
        }
    }

    fun endAudioCall() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = socket ?: return@launch
                val endMsg = AudioCallRequest(currentRoom, clientName, "")
                sendAudioCallMessage(socket, Common.MSG_TYPE_AUDIO_CALL_END, endMsg)
                audioCallManager?.endCall()
            } catch (e: Exception) {
                notifyError("Failed to end audio call: ${e.message}")
            }
        }
    }

    fun startAudioCallDirect(serverIp: String, serverPort: Int, localPort: Int, encryptionKey: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (encryptionKey.size != 32) {
                    notifyError("Invalid encryption key size: ${encryptionKey.size}, expected 32 bytes")
                    return@launch
                }
                val manager = getOrCreateAudioCallManager()
                manager.initialize(encryptionKey)
                manager.startCallDirect(serverIp, serverPort, localPort, encryptionKey)
                notifyCallStarted("$serverIp:$serverPort", true)
            } catch (e: Exception) {
                notifyError("Failed to start direct audio call: ${e.message}")
            }
        }
    }

    fun startAudioRelay(encryptionKey: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (encryptionKey.size != 32) {
                    notifyError("Invalid encryption key size: ${encryptionKey.size}, expected 32 bytes")
                    return@launch
                }
                if (serverHost.isEmpty() || serverPort == 0) {
                    notifyError("Not connected to a server")
                    return@launch
                }
                val manager = getOrCreateAudioCallManager()
                manager.initialize(encryptionKey)
                manager.startRelay(serverHost, serverPort, currentRoom, clientName, 0, encryptionKey)
                notifyCallStarted("Relay $serverHost:$serverPort", true)
            } catch (e: Exception) {
                notifyError("Failed to start relay audio call: ${e.message}")
            }
        }
    }

    fun startAudioListenDirect(localPort: Int, encryptionKey: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (encryptionKey.size != 32) {
                    notifyError("Invalid encryption key size: ${encryptionKey.size}, expected 32 bytes")
                    return@launch
                }
                val manager = getOrCreateAudioCallManager()
                manager.initialize(encryptionKey)
                manager.startListenDirect(localPort, encryptionKey)
                notifyCallStarted("Listening on :$localPort", false)
            } catch (e: Exception) {
                notifyError("Failed to start audio listening: ${e.message}")
            }
        }
    }

    // --- ECDH Key Exchange ---

    /**
     * Send a zero-nonce service frame (unencrypted payload).
     * Used for KEY_REQUEST and KEY_RESPONSE.
     */
    private fun sendServiceFrame(socket: Socket, type: Byte, payload: ByteArray) {
        val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = clientName.toByteArray(Charsets.UTF_8)
        val zeroNonce = ByteArray(Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES)
        val frame = buildFrame(roomBytes, nameBytes, zeroNonce, type, payload)
        Common.sendAll(socket, frame)
    }

    /**
     * Perform ECDH key exchange as joiner: send KEY_REQUEST, wait for KEY_RESPONSE.
     * Returns the room key on success, null on failure/timeout.
     */
    private fun ecdhJoinRoom(socket: Socket): ByteArray? {
        val ls = LazySodiumAndroid(SodiumAndroid())
        val myPk = ByteArray(Common.CRYPTO_BOX_PUBLICKEYBYTES)
        val mySk = ByteArray(Common.CRYPTO_BOX_SECRETKEYBYTES)
        ls.cryptoBoxKeypair(myPk, mySk)

        // Send KEY_REQUEST with our ephemeral public key
        sendServiceFrame(socket, Common.MSG_TYPE_KEY_REQUEST, myPk)
        Log.i("FearClient", "[join] Sent KEY_REQUEST, waiting for response...")

        // Set socket timeout to 30 seconds
        val oldTimeout = socket.soTimeout
        socket.soTimeout = 30000

        try {
            while (true) {
                // Read frame header
                val roomLenBuf = ByteArray(2)
                if (!Common.recvAll(socket, roomLenBuf, 2)) return null
                val roomLen = Common.readUInt16(roomLenBuf, 0)
                if (roomLen > Common.MAX_ROOM) return null

                val roomBuf = ByteArray(roomLen)
                if (!Common.recvAll(socket, roomBuf, roomLen)) return null
                val room = String(roomBuf, Charsets.UTF_8)

                val nameLenBuf = ByteArray(2)
                if (!Common.recvAll(socket, nameLenBuf, 2)) return null
                val nameLen = Common.readUInt16(nameLenBuf, 0)
                if (nameLen > Common.MAX_NAME) return null

                val nameBuf = ByteArray(nameLen)
                if (!Common.recvAll(socket, nameBuf, nameLen)) return null
                val senderName = String(nameBuf, Charsets.UTF_8)

                val nonceLenBuf = ByteArray(2)
                if (!Common.recvAll(socket, nonceLenBuf, 2)) return null
                val nonceLen = Common.readUInt16(nonceLenBuf, 0)
                if (nonceLen != Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES) return null

                val nonce = ByteArray(nonceLen)
                if (!Common.recvAll(socket, nonce, nonceLen)) return null

                val typeBuf = ByteArray(1)
                if (!Common.recvAll(socket, typeBuf, 1)) return null

                val clenBuf = ByteArray(4)
                if (!Common.recvAll(socket, clenBuf, 4)) return null
                val clen = Common.readUInt32(clenBuf, 0).toInt()
                if (clen > Common.MAX_FRAME) return null

                val payload = ByteArray(clen)
                if (!Common.recvAll(socket, payload, clen)) return null

                // Check if this is a KEY_RESPONSE service message
                val isZeroNonce = nonce.all { it == 0.toByte() }
                if (typeBuf[0] != Common.MSG_TYPE_KEY_RESPONSE || !isZeroNonce || room != currentRoom) {
                    continue
                }

                // Parse: [target_name_len(2)][target_name][responder_pk(32)][box_nonce(24)][box_cipher(48)]
                val minLen = 2 + Common.CRYPTO_BOX_PUBLICKEYBYTES +
                        Common.CRYPTO_BOX_NONCEBYTES + 32 + Common.CRYPTO_BOX_MACBYTES
                if (clen < minLen) continue

                var off = 0
                val targetLen = Common.readUInt16(payload, off); off += 2
                if (off + targetLen + Common.CRYPTO_BOX_PUBLICKEYBYTES +
                    Common.CRYPTO_BOX_NONCEBYTES + 32 + Common.CRYPTO_BOX_MACBYTES > clen) continue

                val targetName = String(payload, off, targetLen, Charsets.UTF_8); off += targetLen
                if (targetName != clientName) continue

                val responderPk = payload.copyOfRange(off, off + Common.CRYPTO_BOX_PUBLICKEYBYTES)
                off += Common.CRYPTO_BOX_PUBLICKEYBYTES
                val boxNonce = payload.copyOfRange(off, off + Common.CRYPTO_BOX_NONCEBYTES)
                off += Common.CRYPTO_BOX_NONCEBYTES
                val boxCipher = payload.copyOfRange(off, off + 32 + Common.CRYPTO_BOX_MACBYTES)
                off += 32 + Common.CRYPTO_BOX_MACBYTES

                // Check for identity signature (anti-MITM)
                val remaining = clen - off
                var sigVerified = false
                if (remaining >= Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES) {
                    val idPk = payload.copyOfRange(off, off + Common.IDENTITY_PK_BYTES)
                    val sig = payload.copyOfRange(off + Common.IDENTITY_PK_BYTES,
                        off + Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES)
                    val im = identityManager
                    if (im != null && im.verify(responderPk, sig, idPk)) {
                        sigVerified = true
                        val tofu = im.checkPeerKey(senderName, idPk)
                        val fp = im.fingerprint(idPk)
                        when (tofu) {
                            "verified", "trusted" -> {
                                notifyMessageReceived(Message(currentRoom, "system",
                                    "[join] Key exchange verified: $senderName [$fp]",
                                    System.currentTimeMillis()))
                            }
                            "new" -> {
                                notifyMessageReceived(Message(currentRoom, "system",
                                    "[join] New identity for '$senderName': $fp (trusted on first use)",
                                    System.currentTimeMillis()))
                            }
                            "changed" -> {
                                notifyMessageReceived(Message(currentRoom, "system",
                                    "*** WARNING: Identity key for '$senderName' has CHANGED! ***\n" +
                                    "*** This could indicate a MITM attack! ***\n" +
                                    "*** Fingerprint: $fp ***",
                                    System.currentTimeMillis()))
                            }
                        }
                    } else {
                        notifyMessageReceived(Message(currentRoom, "system",
                            "[join] WARNING: Signature verification FAILED for '$senderName'!",
                            System.currentTimeMillis()))
                    }
                }

                // Decrypt room key using crypto_box_open_easy
                val decrypted = ByteArray(32)
                val ok = ls.cryptoBoxOpenEasy(
                    decrypted, boxCipher, boxCipher.size.toLong(),
                    boxNonce, responderPk, mySk
                )

                // Zero secret key
                java.util.Arrays.fill(mySk, 0.toByte())

                if (ok) {
                    val verStr = if (sigVerified) " (identity verified)" else " (unsigned)"
                    if (BuildConfig.DEBUG) Log.i("FearClient", "[join] Room key received from '$senderName'$verStr")
                    notifyMessageReceived(Message(currentRoom, "system",
                        "[join] Room key received from '$senderName'$verStr",
                        System.currentTimeMillis()))
                    return decrypted
                } else {
                    Log.e("FearClient", "[join] Failed to decrypt room key")
                    return null
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.e("FearClient", "[join] Key exchange timeout (30s)")
            java.util.Arrays.fill(mySk, 0.toByte())
            return null
        } finally {
            socket.soTimeout = oldTimeout
        }
    }

    /**
     * Handle incoming KEY_REQUEST: encrypt and send the room key to the joiner.
     */
    private fun sendKeyResponse(socket: Socket, joinerName: String, joinerPk: ByteArray) {
        if (roomKey.isEmpty()) return

        val ls = LazySodiumAndroid(SodiumAndroid())
        val myPk = ByteArray(Common.CRYPTO_BOX_PUBLICKEYBYTES)
        val mySk = ByteArray(Common.CRYPTO_BOX_SECRETKEYBYTES)
        ls.cryptoBoxKeypair(myPk, mySk)

        val boxNonce = ByteArray(Common.CRYPTO_BOX_NONCEBYTES)
        SecureRandom().nextBytes(boxNonce)

        val boxCipher = ByteArray(32 + Common.CRYPTO_BOX_MACBYTES)
        val ok = ls.cryptoBoxEasy(
            boxCipher, roomKey, roomKey.size.toLong(),
            boxNonce, joinerPk, mySk
        )

        java.util.Arrays.fill(mySk, 0.toByte())

        if (!ok) {
            Log.e("FearClient", "[key-exchange] crypto_box_easy failed")
            return
        }

        // Build payload: [name_len(2)][name][pk(32)][nonce(24)][cipher(48)]
        // If we have identity: append [id_pk(32)][sig(64)] (anti-MITM)
        val nameBytes = joinerName.toByteArray(Charsets.UTF_8)
        val im = identityManager
        val idPk = im?.getPublicKey()
        val sig = if (idPk != null) im?.sign(myPk) else null
        val sigExtra = if (idPk != null && sig != null) (Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES) else 0

        val payloadSize = 2 + nameBytes.size + Common.CRYPTO_BOX_PUBLICKEYBYTES +
                Common.CRYPTO_BOX_NONCEBYTES + boxCipher.size + sigExtra
        val payload = ByteArray(payloadSize)
        var off = 0
        Common.writeUInt16(payload, off, nameBytes.size); off += 2
        System.arraycopy(nameBytes, 0, payload, off, nameBytes.size); off += nameBytes.size
        System.arraycopy(myPk, 0, payload, off, Common.CRYPTO_BOX_PUBLICKEYBYTES); off += Common.CRYPTO_BOX_PUBLICKEYBYTES
        System.arraycopy(boxNonce, 0, payload, off, Common.CRYPTO_BOX_NONCEBYTES); off += Common.CRYPTO_BOX_NONCEBYTES
        System.arraycopy(boxCipher, 0, payload, off, boxCipher.size); off += boxCipher.size

        if (idPk != null && sig != null) {
            System.arraycopy(idPk, 0, payload, off, Common.IDENTITY_PK_BYTES); off += Common.IDENTITY_PK_BYTES
            System.arraycopy(sig, 0, payload, off, Common.IDENTITY_SIG_BYTES)
        }

        sendServiceFrame(socket, Common.MSG_TYPE_KEY_RESPONSE, payload)
        val signedStr = if (sigExtra > 0) " (signed)" else ""
        if (BuildConfig.DEBUG) Log.i("FearClient", "[key-exchange] Sent room key to '$joinerName'$signedStr")
        notifyMessageReceived(Message(currentRoom, "system",
            "[key-exchange] Sent room key to '$joinerName'$signedStr", System.currentTimeMillis()))
    }

    // --- Send helpers ---

    private fun buildAd(roomBytes: ByteArray, nameBytes: ByteArray): ByteArray {
        val ad = ByteArray(2 + roomBytes.size + 2 + nameBytes.size)
        var offset = 0
        Common.writeUInt16(ad, offset, roomBytes.size)
        offset += 2
        System.arraycopy(roomBytes, 0, ad, offset, roomBytes.size)
        offset += roomBytes.size
        Common.writeUInt16(ad, offset, nameBytes.size)
        offset += 2
        System.arraycopy(nameBytes, 0, ad, offset, nameBytes.size)
        return ad
    }

    private fun sendEncryptedMessage(socket: Socket, type: Byte, payload: ByteArray) {
        val nonce = Crypto.generateNonce()
        val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = clientName.toByteArray(Charsets.UTF_8)
        val ad = buildAd(roomBytes, nameBytes)
        val ciphertext = Crypto.encrypt(payload, ad, nonce, roomKey) ?: return
        val frame = buildFrame(roomBytes, nameBytes, nonce, type, ciphertext)
        Common.sendAll(socket, frame)
    }

    private fun sendTextMessage(socket: Socket, text: String) {
        try {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val nonce = Crypto.generateNonce()
            val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
            val nameBytes = clientName.toByteArray(Charsets.UTF_8)
            val ad = buildAd(roomBytes, nameBytes)

            // If we have identity, send as SIGNED_TEXT
            val im = identityManager
            val msgType: Byte
            val plaintext: ByteArray

            if (im != null && im.hasIdentity()) {
                val signedPayload = im.buildSignedTextPayload(textBytes)
                if (signedPayload != null) {
                    msgType = Common.MSG_TYPE_SIGNED_TEXT
                    plaintext = signedPayload
                    Log.d("FearClient", "Sending SIGNED_TEXT (type=5), payload=${plaintext.size} bytes")
                } else {
                    msgType = Common.MSG_TYPE_TEXT
                    plaintext = textBytes
                    Log.w("FearClient", "buildSignedTextPayload returned null, fallback to TEXT")
                }
            } else {
                msgType = Common.MSG_TYPE_TEXT
                plaintext = textBytes
                Log.w("FearClient", "No identity: im=${im != null}, hasIdentity=${im?.hasIdentity()}, sending TEXT (type=0)")
            }

            val ciphertext = Crypto.encrypt(plaintext, ad, nonce, roomKey)
            if (ciphertext == null) {
                notifyError("Encryption failed")
                return
            }

            val frame = buildFrame(roomBytes, nameBytes, nonce, msgType, ciphertext)

            if (!Common.sendAll(socket, frame)) {
                notifyError("Send failed")
                disconnect()
            } else {
                val message = Message(currentRoom, clientName, text, System.currentTimeMillis())
                notifyMessageReceived(message)
            }
        } catch (e: Exception) {
            notifyError("Send error: ${e.message}")
        }
    }

    private fun sendAudioCallMessage(socket: Socket, type: Byte, request: AudioCallRequest) {
        val json = """{"room":"${request.room}","fromUser":"${request.fromUser}","toUser":"${request.toUser}","timestamp":${request.timestamp}}"""
        sendEncryptedMessage(socket, type, json.toByteArray(Charsets.UTF_8))
    }

    private fun sendAudioCallMessage(socket: Socket, type: Byte, response: AudioCallResponse) {
        val json = """{"room":"${response.room}","fromUser":"${response.fromUser}","toUser":"${response.toUser}","accepted":${response.accepted},"timestamp":${response.timestamp}}"""
        sendEncryptedMessage(socket, type, json.toByteArray(Charsets.UTF_8))
    }

    private fun sendAudioUdpInfo(socket: Socket, udpInfo: AudioUdpInfo) {
        val json = """{"room":"${udpInfo.room}","user":"${udpInfo.user}","udpPort":${udpInfo.udpPort},"noncePrefix":"${Common.base64Encode(udpInfo.noncePrefix)}","timestamp":${udpInfo.timestamp}}"""
        sendEncryptedMessage(socket, Common.MSG_TYPE_AUDIO_UDP_INFO, json.toByteArray(Charsets.UTF_8))
    }

    private fun sendFile(socket: Socket, filename: String) {
        try {
            val file = File(filename)
            if (!file.exists()) {
                notifyError("File not found: $filename")
                return
            }

            val fileSize = file.length()
            if (fileSize == 0L) {
                notifyError("File is empty: $filename")
                return
            }

            val fileData = FileInputStream(file).use { it.readBytes() }
            val fileCrc = Common.crc32(fileData)

            if (!sendFileStart(socket, file.name, fileSize, fileCrc)) {
                notifyError("Failed to send file start")
                return
            }

            var offset = 0
            while (offset < fileSize) {
                val chunkSize = minOf(Common.FILE_CHUNK_SIZE.toLong(), fileSize - offset).toInt()
                val chunkData = fileData.copyOfRange(offset, offset + chunkSize)
                val chunkCrc = Common.crc32(chunkData)

                if (!sendFileChunk(socket, chunkData, chunkCrc)) {
                    notifyError("File transfer failed")
                    return
                }

                offset += chunkSize
                val progress = offset.toFloat() / fileSize.toFloat()
                notifyFileTransferProgress(file.name, progress)
            }

            sendFileEnd(socket, fileCrc)
            notifyFileTransferComplete(file.name)
        } catch (e: Exception) {
            notifyError("File transfer error: ${e.message}")
        }
    }

    private fun sendFileStart(socket: Socket, filename: String, fileSize: Long, crc: Long): Boolean {
        val filenameBytes = filename.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + filenameBytes.size + 4 + 4)
        var offset = 0
        Common.writeUInt16(payload, offset, filenameBytes.size)
        offset += 2
        System.arraycopy(filenameBytes, 0, payload, offset, filenameBytes.size)
        offset += filenameBytes.size
        Common.writeUInt32(payload, offset, fileSize)
        offset += 4
        Common.writeUInt32(payload, offset, crc)
        return sendFileMessage(socket, Common.MSG_TYPE_FILE_START, payload)
    }

    private fun sendFileChunk(socket: Socket, chunkData: ByteArray, chunkCrc: Long): Boolean {
        val payload = ByteArray(4 + chunkData.size)
        Common.writeUInt32(payload, 0, chunkCrc)
        System.arraycopy(chunkData, 0, payload, 4, chunkData.size)
        return sendFileMessage(socket, Common.MSG_TYPE_FILE_CHUNK, payload)
    }

    private fun sendFileEnd(socket: Socket, finalCrc: Long): Boolean {
        val payload = ByteArray(4)
        Common.writeUInt32(payload, 0, finalCrc)
        return sendFileMessage(socket, Common.MSG_TYPE_FILE_END, payload)
    }

    private fun sendFileMessage(socket: Socket, type: Byte, payload: ByteArray): Boolean {
        val nonce = Crypto.generateNonce()
        val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = clientName.toByteArray(Charsets.UTF_8)
        val ad = buildAd(roomBytes, nameBytes)
        val ciphertext = Crypto.encrypt(payload, ad, nonce, roomKey) ?: return false
        val frame = buildFrame(roomBytes, nameBytes, nonce, type, ciphertext)
        return Common.sendAll(socket, frame)
    }

    private fun buildFrame(
        roomBytes: ByteArray,
        nameBytes: ByteArray,
        nonce: ByteArray,
        type: Byte,
        ciphertext: ByteArray
    ): ByteArray {
        // Format matches PC: [2 room_len][room][2 name_len][name][2 nonce_len][nonce][1 type][4 clen][cipher]
        val frameSize = 2 + roomBytes.size + 2 + nameBytes.size + 2 + nonce.size + 1 + 4 + ciphertext.size
        val frame = ByteArray(frameSize)
        var offset = 0

        Common.writeUInt16(frame, offset, roomBytes.size)
        offset += 2
        System.arraycopy(roomBytes, 0, frame, offset, roomBytes.size)
        offset += roomBytes.size

        Common.writeUInt16(frame, offset, nameBytes.size)
        offset += 2
        System.arraycopy(nameBytes, 0, frame, offset, nameBytes.size)
        offset += nameBytes.size

        Common.writeUInt16(frame, offset, nonce.size)
        offset += 2
        System.arraycopy(nonce, 0, frame, offset, nonce.size)
        offset += nonce.size

        frame[offset] = type
        offset += 1

        Common.writeUInt32(frame, offset, ciphertext.size.toLong())
        offset += 4
        System.arraycopy(ciphertext, 0, frame, offset, ciphertext.size)

        return frame
    }

    // --- Receive loop ---

    private fun startReceiving() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val socket = socket ?: return@launch

            while (isConnected && !socket.isClosed) {
                try {
                    if (!receiveMessage(socket)) break
                } catch (e: Exception) {
                    if (isConnected) notifyError("Receive error: ${e.message}")
                    break
                }
            }
            disconnect()
        }
    }

    private fun receiveMessage(socket: Socket): Boolean {
        return try {
            // Frame: [2 room_len][room][2 name_len][name][2 nonce_len][nonce][1 type][4 clen][cipher]

            // Read room
            val roomLenBuf = ByteArray(2)
            if (!Common.recvAll(socket, roomLenBuf, 2)) return false
            val roomLen = Common.readUInt16(roomLenBuf, 0)
            if (roomLen > Common.MAX_ROOM) return false

            val roomBuf = ByteArray(roomLen)
            if (!Common.recvAll(socket, roomBuf, roomLen)) return false
            val room = String(roomBuf, Charsets.UTF_8)

            // Read name
            val nameLenBuf = ByteArray(2)
            if (!Common.recvAll(socket, nameLenBuf, 2)) return false
            val nameLen = Common.readUInt16(nameLenBuf, 0)
            if (nameLen > Common.MAX_NAME) return false

            val nameBuf = ByteArray(nameLen)
            if (!Common.recvAll(socket, nameBuf, nameLen)) return false
            val senderName = String(nameBuf, Charsets.UTF_8)

            // Read nonce
            val nonceLenBuf = ByteArray(2)
            if (!Common.recvAll(socket, nonceLenBuf, 2)) return false
            val nonceLen = Common.readUInt16(nonceLenBuf, 0)
            if (nonceLen != Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES) return false

            val nonce = ByteArray(nonceLen)
            if (!Common.recvAll(socket, nonce, nonceLen)) return false

            // Read type
            val typeBuf = ByteArray(1)
            if (!Common.recvAll(socket, typeBuf, 1)) return false
            val msgType = typeBuf[0]

            // Read ciphertext length
            val clenBuf = ByteArray(4)
            if (!Common.recvAll(socket, clenBuf, 4)) return false
            val clen = Common.readUInt32(clenBuf, 0)
            if (clen > Common.MAX_FRAME) return false

            // Read ciphertext/payload
            val ciphertext = ByteArray(clen.toInt())
            if (!Common.recvAll(socket, ciphertext, clen.toInt())) return false

            // Skip messages from other rooms
            if (room != currentRoom) return true

            // Check if this is a service message (all-zero nonce)
            val isServiceMessage = nonce.all { it == 0.toByte() }

            // Handle USER_LIST (service message, not encrypted)
            if (isServiceMessage && msgType == Common.MSG_TYPE_USER_LIST) {
                handleUserList(ciphertext)
                return true
            }

            // Handle KEY_REQUEST (service message, not encrypted)
            if (isServiceMessage && msgType == Common.MSG_TYPE_KEY_REQUEST) {
                if (ciphertext.size == Common.CRYPTO_BOX_PUBLICKEYBYTES &&
                    roomKey.isNotEmpty() && senderName != clientName) {
                    sendKeyResponse(socket, senderName, ciphertext)
                }
                return true
            }

            // Handle KEY_RESPONSE (ignore in normal recv loop, handled by ecdhJoinRoom)
            if (isServiceMessage && msgType == Common.MSG_TYPE_KEY_RESPONSE) {
                return true
            }

            // Skip own messages
            if (senderName == clientName) return true

            // Build AD for decryption
            val ad = ByteArray(2 + roomLen + 2 + nameLen)
            var offset = 0
            Common.writeUInt16(ad, offset, roomLen)
            offset += 2
            System.arraycopy(roomBuf, 0, ad, offset, roomLen)
            offset += roomLen
            Common.writeUInt16(ad, offset, nameLen)
            offset += 2
            System.arraycopy(nameBuf, 0, ad, offset, nameLen)

            // Decrypt
            val plaintext = Crypto.decrypt(ciphertext, ad, nonce, roomKey)
            if (plaintext == null) {
                return true
            }

            // Process by message type
            when (msgType) {
                Common.MSG_TYPE_TEXT -> {
                    val content = String(plaintext, Charsets.UTF_8)
                    val message = Message(room, senderName, content, System.currentTimeMillis())
                    notifyMessageReceived(message)
                }

                Common.MSG_TYPE_FILE_START, Common.MSG_TYPE_FILE_CHUNK, Common.MSG_TYPE_FILE_END -> {
                    handleFileMessage(msgType, plaintext, room, senderName)
                }

                Common.MSG_TYPE_SIGNED_TEXT -> {
                    // [pk(32)][sig(64)][text]
                    val sigPrefixLen = Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES
                    if (plaintext.size > sigPrefixLen) {
                        val pk = plaintext.copyOfRange(0, Common.IDENTITY_PK_BYTES)
                        val sig = plaintext.copyOfRange(Common.IDENTITY_PK_BYTES, sigPrefixLen)
                        val textBytes = plaintext.copyOfRange(sigPrefixLen, plaintext.size)
                        val text = String(textBytes, Charsets.UTF_8)

                        val im = identityManager
                        val prefix: String
                        if (im != null) {
                            val sigOk = im.verify(textBytes, sig, pk)
                            if (sigOk) {
                                val status = im.checkPeerKey(senderName, pk)
                                prefix = when (status) {
                                    "changed" -> {
                                        val fp = im.fingerprint(pk)
                                        val warn = Message(room, "system",
                                            "WARNING: Key CHANGED for $senderName! " +
                                            "Fingerprint: $fp. Possible MITM attack!",
                                            System.currentTimeMillis())
                                        notifyMessageReceived(warn)
                                        "[!] "
                                    }
                                    "verified" -> "[V] "
                                    else -> "[T] "  // "new" or "trusted" — TOFU
                                }
                            } else {
                                prefix = "[!] "  // Signature verification failed
                            }
                        } else {
                            Log.w("FearClient", "SIGNED_TEXT: identityManager is null")
                            prefix = "[?] "
                        }

                        val message = Message(room, senderName, prefix + text,
                            System.currentTimeMillis(), Common.MSG_TYPE_SIGNED_TEXT)
                        notifyMessageReceived(message)
                    }
                }

                Common.MSG_TYPE_SIGNED_FILE_START -> {
                    if (plaintext.size > Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES) {
                        val stripped = plaintext.copyOfRange(
                            Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES, plaintext.size)
                        handleFileMessage(Common.MSG_TYPE_FILE_START, stripped, room, senderName)
                    }
                }
                Common.MSG_TYPE_SIGNED_FILE_CHUNK -> {
                    if (plaintext.size > Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES) {
                        val stripped = plaintext.copyOfRange(
                            Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES, plaintext.size)
                        handleFileMessage(Common.MSG_TYPE_FILE_CHUNK, stripped, room, senderName)
                    }
                }
                Common.MSG_TYPE_SIGNED_FILE_END -> {
                    if (plaintext.size > Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES) {
                        val stripped = plaintext.copyOfRange(
                            Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES, plaintext.size)
                        handleFileMessage(Common.MSG_TYPE_FILE_END, stripped, room, senderName)
                    }
                }

                Common.MSG_TYPE_IDENTITY_ANNOUNCE -> {
                    // [pk(32)][sig_over_name(64)]
                    val sigPrefixLen = Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES
                    if (plaintext.size >= sigPrefixLen) {
                        val pk = plaintext.copyOfRange(0, Common.IDENTITY_PK_BYTES)
                        val sig = plaintext.copyOfRange(Common.IDENTITY_PK_BYTES, sigPrefixLen)
                        val nameBytes = senderName.toByteArray(Charsets.UTF_8)
                        val im = identityManager
                        if (im != null) {
                            val sigOk = im.verify(nameBytes, sig, pk)
                            val fp = im.fingerprint(pk)
                            if (sigOk) {
                                val status = im.checkPeerKey(senderName, pk)
                                if (status == "changed") {
                                    val msg = Message(room, "system",
                                        "WARNING: Key CHANGED for $senderName! Fingerprint: $fp. Possible MITM attack!",
                                        System.currentTimeMillis())
                                    notifyMessageReceived(msg)
                                }
                            }
                        }
                    }
                }

                Common.MSG_TYPE_AUDIO_CALL_REQUEST -> {
                    val request = parseAudioCallRequest(String(plaintext, Charsets.UTF_8))
                    if (request != null && request.toUser == clientName) {
                        pendingCallRequest = request
                        notifyCallRequestReceived(request.fromUser)
                    }
                }

                Common.MSG_TYPE_AUDIO_CALL_ACCEPT -> {
                    // Wait for UDP info
                }

                Common.MSG_TYPE_AUDIO_CALL_REJECT -> {
                    val response = parseAudioCallResponse(String(plaintext, Charsets.UTF_8))
                    if (response != null && response.toUser == clientName && !response.accepted) {
                        notifyError("Audio call rejected by ${response.fromUser}")
                    }
                }

                Common.MSG_TYPE_AUDIO_UDP_INFO -> {
                    val udpInfo = parseAudioUdpInfo(String(plaintext, Charsets.UTF_8))
                    if (udpInfo != null && udpInfo.user != clientName) {
                        val host = socket.inetAddress.hostAddress ?: return@receiveMessage true
                        val manager = getOrCreateAudioCallManager()
                        manager.startCall(udpInfo.user, host, udpInfo.udpPort, udpInfo.noncePrefix)
                    }
                }

                Common.MSG_TYPE_AUDIO_CALL_END -> {
                    audioCallManager?.endCall()
                    notifyCallEnded()
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    // --- USER_LIST parsing ---

    private fun handleUserList(payload: ByteArray) {
        // Format: [2 count][for each: 2 name_len, name]
        if (payload.size < 2) return

        val count = Common.readUInt16(payload, 0)
        val contacts = mutableListOf<String>()
        var offset = 2

        for (i in 0 until count) {
            if (offset + 2 > payload.size) break
            val nameLen = Common.readUInt16(payload, offset)
            offset += 2
            if (offset + nameLen > payload.size) break
            val name = String(payload, offset, nameLen, Charsets.UTF_8)
            offset += nameLen
            contacts.add(name)
        }

        lastContacts = contacts
        handler.post { listener.onContactsUpdated(contacts) }
    }

    // --- File transfer handling ---

    private fun handleFileMessage(type: Byte, plaintext: ByteArray, room: String, sender: String) {
        when (type) {
            Common.MSG_TYPE_FILE_START -> {
                var offset = 0
                val filenameLen = Common.readUInt16(plaintext, offset)
                offset += 2
                val filename = String(plaintext, offset, filenameLen, Charsets.UTF_8)
                offset += filenameLen
                val fileSize = Common.readUInt32(plaintext, offset)
                offset += 4
                val expectedCrc = Common.readUInt32(plaintext, offset)

                val basename = filename.substringAfterLast('/').substringAfterLast('\\')
                val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
                downloadsDir.mkdirs()
                val savePath = File(downloadsDir, basename).absolutePath

                currentFileTransfer = FileTransfer(
                    filename = savePath,
                    totalSize = fileSize,
                    expectedCrc = expectedCrc,
                    currentCrc = 0xFFFFFFFFL
                )

                File(savePath).createNewFile()
                notifyFileTransferProgress(basename, 0f)

                val msg = Message(room, sender, "Sending file: $basename ($fileSize bytes)",
                    System.currentTimeMillis())
                notifyMessageReceived(msg)
            }

            Common.MSG_TYPE_FILE_CHUNK -> {
                val transfer = currentFileTransfer ?: return

                var offset = 0
                val chunkCrc = Common.readUInt32(plaintext, offset)
                offset += 4
                val chunkData = plaintext.copyOfRange(offset, plaintext.size)

                if (Common.crc32(chunkData) != chunkCrc) {
                    notifyFileTransferError(transfer.filename, "Chunk CRC error")
                    currentFileTransfer = null
                    return
                }

                FileOutputStream(transfer.filename, true).use { it.write(chunkData) }

                var currentCrc = transfer.currentCrc
                for (byte in chunkData) {
                    currentCrc = currentCrc xor (byte.toLong() and 0xFF)
                    for (j in 0 until 8) {
                        currentCrc = (currentCrc ushr 1) xor (0xEDB88320L and -(currentCrc and 1))
                    }
                }
                transfer.currentCrc = currentCrc
                transfer.received += chunkData.size

                val progress = transfer.received.toFloat() / transfer.totalSize.toFloat()
                val basename = File(transfer.filename).name
                notifyFileTransferProgress(basename, progress)
            }

            Common.MSG_TYPE_FILE_END -> {
                val transfer = currentFileTransfer ?: return
                val finalCrc = Common.readUInt32(plaintext, 0)
                val calculatedCrc = transfer.currentCrc xor 0xFFFFFFFFL

                if (calculatedCrc == finalCrc) {
                    notifyFileTransferComplete(File(transfer.filename).name)
                } else {
                    File(transfer.filename).delete()
                    notifyFileTransferError(transfer.filename, "CRC mismatch")
                }

                currentFileTransfer = null
            }
        }
    }

    // --- JSON parsing helpers ---

    private fun parseAudioCallRequest(json: String): AudioCallRequest? {
        return try {
            val room = extractJsonField(json, "room")
            val fromUser = extractJsonField(json, "fromUser")
            val toUser = extractJsonField(json, "toUser")
            val timestamp = extractJsonField(json, "timestamp")?.toLongOrNull() ?: System.currentTimeMillis()
            AudioCallRequest(room ?: "", fromUser ?: "", toUser ?: "", timestamp)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAudioCallResponse(json: String): AudioCallResponse? {
        return try {
            val room = extractJsonField(json, "room")
            val fromUser = extractJsonField(json, "fromUser")
            val toUser = extractJsonField(json, "toUser")
            val accepted = extractJsonField(json, "accepted")?.toBooleanStrictOrNull() ?: false
            val timestamp = extractJsonField(json, "timestamp")?.toLongOrNull() ?: System.currentTimeMillis()
            AudioCallResponse(room ?: "", fromUser ?: "", toUser ?: "", accepted, timestamp)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAudioUdpInfo(json: String): AudioUdpInfo? {
        return try {
            val room = extractJsonField(json, "room")
            val user = extractJsonField(json, "user")
            val udpPort = extractJsonField(json, "udpPort")?.toIntOrNull() ?: 0
            val noncePrefixBase64 = extractJsonField(json, "noncePrefix")
            val noncePrefix = Common.base64Decode(noncePrefixBase64 ?: "") ?: ByteArray(0)
            val timestamp = extractJsonField(json, "timestamp")?.toLongOrNull() ?: System.currentTimeMillis()
            AudioUdpInfo(room ?: "", user ?: "", udpPort, noncePrefix, timestamp)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    // --- Notification helpers ---

    private fun notifyConnected() {
        handler.post { listener.onConnected() }
    }

    private fun notifyDisconnected() {
        handler.post { listener.onDisconnected() }
    }

    private fun notifyMessageReceived(message: Message) {
        handler.post { listener.onMessageReceived(message) }
        if (!isInForeground && message.sender != "system" && message.sender != clientName) {
            MessageNotifier.show(context, message.sender, message.content)
        }
    }

    private fun notifyCallRequestReceived(fromUser: String) {
        handler.post { listener.onCallRequestReceived(fromUser) }
    }

    private fun notifyCallStarted(remoteUser: String, isInitiator: Boolean) {
        handler.post { listener.onCallStarted(remoteUser, isInitiator) }
    }

    private fun notifyCallEnded() {
        handler.post { listener.onCallEnded() }
    }

    private fun notifyFileTransferProgress(filename: String, progress: Float) {
        handler.post { listener.onFileTransferProgress(filename, progress) }
    }

    private fun notifyFileTransferComplete(filename: String) {
        handler.post { listener.onFileTransferComplete(filename) }
    }

    private fun notifyFileTransferError(filename: String, error: String) {
        handler.post { listener.onFileTransferError(filename, error) }
    }

    private fun notifyError(error: String) {
        handler.post { listener.onError(error) }
    }
}
