package com.fear

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class FearClient(
    private val context: Context,
    private val listener: FearClientListener
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
    }
    
    private var socket: Socket? = null
    private var receiveJob: Job? = null
    private var isConnected = false
    private var currentRoom = ""
    private var clientName = ""
    private var roomKey = ByteArray(0)
    
    private val handler = Handler(Looper.getMainLooper())
    private var currentFileTransfer: FileTransfer? = null

    private var audioCallManager: AudioCallManager? = null

    private var pendingCallRequest: AudioCallRequest? = null

    private fun getOrCreateAudioCallManager(): AudioCallManager {
        if (audioCallManager == null) {
            audioCallManager = AudioCallManager(context, object : AudioCallManager.AudioCallListener {
                override fun onCallStateChanged(state: AudioCallState) {
                    // Handle call state changes
                }

                override fun onCallRequestReceived(fromUser: String) {
                    // Not implemented here - handled via TCP messages
                }

                override fun onCallError(error: String) {
                    notifyError("Audio call error: $error")
                }

                override fun onCallStarted(remoteUser: String, isInitiator: Boolean) {
                    handler.post {
                        listener.onCallStarted(remoteUser, isInitiator)
                    }
                }

                override fun onCallEnded() {
                    handler.post {
                        listener.onCallEnded()
                    }
                }
            })
        }
        return audioCallManager!!
    }

    fun connect(host: String, port: Int, room: String, name: String, keyBase64: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = Common.base64Decode(keyBase64)
                if (key == null || key.size != Common.CRYPTO_AEAD_XCHACHA20POLY1305_IETF_KEYBYTES) {
                    notifyError("Invalid key")
                    return@launch
                }
                
                roomKey = key

                currentRoom = room
                clientName = name
                
                socket = Socket(host, port)
                isConnected = true
                
                notifyConnected()
                
                // Start receiving messages
                startReceiving()
                
            } catch (e: Exception) {
                notifyError("Connection failed: ${e.message}")
            }
        }
    }
    
    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            receiveJob?.cancel()
            socket?.close()
            socket = null
            isConnected = false
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

    private fun notifyCallRequestReceived(fromUser: String) {
        handler.post { listener.onCallRequestReceived(fromUser) }
    }

    private fun notifyCallStarted(remoteUser: String, isInitiator: Boolean) {
        handler.post { listener.onCallStarted(remoteUser, isInitiator) }
    }

    private fun notifyCallEnded() {
        handler.post { listener.onCallEnded() }
    }

    fun startAudioCall(targetUser: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = socket ?: return@launch

                // Send audio call request
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

                // Initialize audio call manager with our UDP port
                val manager = getOrCreateAudioCallManager()
                manager.initialize(roomKey)

                // Send accept response with our UDP info
                val udpInfo = AudioUdpInfo(
                    currentRoom,
                    clientName,
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

                // Send call end message
                val endMsg = AudioCallRequest(currentRoom, clientName, "") // empty target means end call
                sendAudioCallMessage(socket, Common.MSG_TYPE_AUDIO_CALL_END, endMsg)

                // Stop audio call
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

                // Initialize audio call manager with the provided key
                val manager = getOrCreateAudioCallManager()
                manager.initialize(encryptionKey)

                // Start direct UDP audio call (compatible with PC version)
                manager.startCallDirect(serverIp, serverPort, localPort, encryptionKey)

                notifyCallStarted("$serverIp:$serverPort", true)

            } catch (e: Exception) {
                notifyError("Failed to start direct audio call: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun sendAudioCallMessage(socket: Socket, type: Byte, request: AudioCallRequest) {
        val json = """
        {
            "room": "${request.room}",
            "fromUser": "${request.fromUser}",
            "toUser": "${request.toUser}",
            "timestamp": ${request.timestamp}
        }
        """.trimIndent()

        sendEncryptedMessage(socket, type, json.toByteArray(Charsets.UTF_8))
    }

    private fun sendAudioCallMessage(socket: Socket, type: Byte, response: AudioCallResponse) {
        val json = """
        {
            "room": "${response.room}",
            "fromUser": "${response.fromUser}",
            "toUser": "${response.toUser}",
            "accepted": ${response.accepted},
            "timestamp": ${response.timestamp}
        }
        """.trimIndent()

        sendEncryptedMessage(socket, type, json.toByteArray(Charsets.UTF_8))
    }

    private fun sendAudioUdpInfo(socket: Socket, udpInfo: AudioUdpInfo) {
        val json = """
        {
            "room": "${udpInfo.room}",
            "user": "${udpInfo.user}",
            "udpPort": ${udpInfo.udpPort},
            "noncePrefix": "${Common.base64Encode(udpInfo.noncePrefix)}",
            "timestamp": ${udpInfo.timestamp}
        }
        """.trimIndent()

        sendEncryptedMessage(socket, Common.MSG_TYPE_AUDIO_UDP_INFO, json.toByteArray(Charsets.UTF_8))
    }

    private fun sendEncryptedMessage(socket: Socket, type: Byte, payload: ByteArray) {
        val nonce = Crypto.generateNonce()
        val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = clientName.toByteArray(Charsets.UTF_8)

        val ad = ByteArray(2 + roomBytes.size + 2 + nameBytes.size)
        var offset = 0
        Common.writeUInt16(ad, offset, roomBytes.size)
        offset += 2
        System.arraycopy(roomBytes, 0, ad, offset, roomBytes.size)
        offset += roomBytes.size
        Common.writeUInt16(ad, offset, nameBytes.size)
        offset += 2
        System.arraycopy(nameBytes, 0, ad, offset, nameBytes.size)

        val ciphertext = Crypto.encrypt(payload, ad, nonce, roomKey) ?: return
        val frame = buildFrame(roomBytes, nameBytes, nonce, type, ciphertext)

        Common.sendAll(socket, frame)
    }

    private fun sendTextMessage(socket: Socket, text: String) {
        try {
            println("DEBUG: Starting to send message: $text")

            val plaintext = text.toByteArray(Charsets.UTF_8)
            println("DEBUG: Plaintext size: ${plaintext.size} bytes")

            val nonce = Crypto.generateNonce()
            println("DEBUG: Nonce generated: ${nonce.size} bytes")

            // Prepare additional data (room + name)
            val roomBytes = currentRoom.toByteArray(Charsets.UTF_8)
            val nameBytes = clientName.toByteArray(Charsets.UTF_8)

            println("DEBUG: Room: $currentRoom (${roomBytes.size} bytes)")
            println("DEBUG: Name: $clientName (${nameBytes.size} bytes)")

            val ad = ByteArray(2 + roomBytes.size + 2 + nameBytes.size)
            var offset = 0
            Common.writeUInt16(ad, offset, roomBytes.size)
            offset += 2
            System.arraycopy(roomBytes, 0, ad, offset, roomBytes.size)
            offset += roomBytes.size
            Common.writeUInt16(ad, offset, nameBytes.size)
            offset += 2
            System.arraycopy(nameBytes, 0, ad, offset, nameBytes.size)

            println("DEBUG: Additional data prepared: ${ad.size} bytes")

            // Encrypt
            println("DEBUG: Starting encryption...")
            val ciphertext = Crypto.encrypt(plaintext, ad, nonce, roomKey)

            if (ciphertext == null) {
                println("DEBUG: Encryption returned null")
                notifyError("Encryption failed")
                return
            }

            println("DEBUG: Encryption successful, ciphertext size: ${ciphertext.size} bytes")

            // Build frame
            val frame = buildFrame(roomBytes, nameBytes, nonce, Common.MSG_TYPE_TEXT, ciphertext)
            println("DEBUG: Frame built: ${frame.size} bytes")

            if (!Common.sendAll(socket, frame)) {
                println("DEBUG: Send failed")
                notifyError("Send failed")
                disconnect()
            } else {
                println("DEBUG: Message sent successfully")
                // Notify locally
                val message = Message(currentRoom, clientName, text, System.currentTimeMillis())
                notifyMessageReceived(message)
            }

        } catch (e: Exception) {
            println("DEBUG: Exception in sendTextMessage: ${e.message}")
            e.printStackTrace()
            notifyError("Send error: ${e.message}")
        }
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
            
            // Read file data
            val fileData = FileInputStream(file).use { it.readBytes() }
            val fileCrc = Common.crc32(fileData)
            
            // Send file start message
            if (!sendFileStart(socket, file.name, fileSize, fileCrc)) {
                notifyError("Failed to send file start")
                return
            }
            
            // Send file chunks
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
            
            // Send file end
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
        
        val ad = ByteArray(2 + roomBytes.size + 2 + nameBytes.size)
        var offset = 0
        Common.writeUInt16(ad, offset, roomBytes.size)
        offset += 2
        System.arraycopy(roomBytes, 0, ad, offset, roomBytes.size)
        offset += roomBytes.size
        Common.writeUInt16(ad, offset, nameBytes.size)
        offset += 2
        System.arraycopy(nameBytes, 0, ad, offset, nameBytes.size)
        
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
        // Формат соответствует PC: [2 room_len][room][2 name_len][name][2 nonce_len][nonce][1 type][4 clen][clen cipher]
        val frameSize = 2 + roomBytes.size + 2 + nameBytes.size + 2 + nonce.size + 1 + 4 + ciphertext.size
        val frame = ByteArray(frameSize)

        var offset = 0

        // [2 room_len][room]
        Common.writeUInt16(frame, offset, roomBytes.size)
        offset += 2
        System.arraycopy(roomBytes, 0, frame, offset, roomBytes.size)
        offset += roomBytes.size

        // [2 name_len][name]
        Common.writeUInt16(frame, offset, nameBytes.size)
        offset += 2
        System.arraycopy(nameBytes, 0, frame, offset, nameBytes.size)
        offset += nameBytes.size

        // [2 nonce_len][nonce]
        Common.writeUInt16(frame, offset, nonce.size)
        offset += 2
        System.arraycopy(nonce, 0, frame, offset, nonce.size)
        offset += nonce.size

        // [1 type]
        frame[offset] = type
        offset += 1

        // [4 clen][clen cipher]
        Common.writeUInt32(frame, offset, ciphertext.size.toLong())
        offset += 4
        System.arraycopy(ciphertext, 0, frame, offset, ciphertext.size)

        println("DEBUG: Frame built - total size: $frameSize bytes")
        println("DEBUG: Frame structure: room(${roomBytes.size}) + name(${nameBytes.size}) + nonce(${nonce.size}) + type(1) + clen(4) + cipher(${ciphertext.size})")

        return frame
    }
    
    private fun startReceiving() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val socket = socket ?: return@launch
            
            while (isConnected && !socket.isClosed) {
                try {
                    if (!receiveMessage(socket)) {
                        break
                    }
                } catch (e: Exception) {
                    if (isConnected) {
                        notifyError("Receive error: ${e.message}")
                    }
                    break
                }
            }
            
            disconnect()
        }
    }
    private fun receiveMessage(socket: Socket): Boolean {
        return try {
            println("DEBUG: Waiting for message...")

            // Формат PC: [2 room_len][room][2 name_len][name][2 nonce_len][nonce][1 type][4 clen][clen cipher]

            // Читаем room_len (2 байта)
            val roomLenBuf = ByteArray(2)
            if (!Common.recvAll(socket, roomLenBuf, 2)) {
                println("DEBUG: Failed to read room length")
                return false
            }
            val roomLen = Common.readUInt16(roomLenBuf, 0)
            if (roomLen > Common.MAX_ROOM) {
                println("DEBUG: Room length too large: $roomLen")
                return false
            }
            println("DEBUG: Room length: $roomLen")

            // Читаем room
            val roomBuf = ByteArray(roomLen)
            if (!Common.recvAll(socket, roomBuf, roomLen)) {
                println("DEBUG: Failed to read room")
                return false
            }
            val room = String(roomBuf, Charsets.UTF_8)
            println("DEBUG: Room: '$room'")

            // Читаем name_len (2 байта)
            val nameLenBuf = ByteArray(2)
            if (!Common.recvAll(socket, nameLenBuf, 2)) {
                println("DEBUG: Failed to read name length")
                return false
            }
            val nameLen = Common.readUInt16(nameLenBuf, 0)
            if (nameLen > Common.MAX_NAME) {
                println("DEBUG: Name length too large: $nameLen")
                return false
            }
            println("DEBUG: Name length: $nameLen")

            // Читаем name
            val nameBuf = ByteArray(nameLen)
            if (!Common.recvAll(socket, nameBuf, nameLen)) {
                println("DEBUG: Failed to read name")
                return false
            }
            val senderName = String(nameBuf, Charsets.UTF_8)
            println("DEBUG: Sender name: '$senderName'")

            // Читаем nonce_len (2 байта)
            val nonceLenBuf = ByteArray(2)
            if (!Common.recvAll(socket, nonceLenBuf, 2)) {
                println("DEBUG: Failed to read nonce length")
                return false
            }
            val nonceLen = Common.readUInt16(nonceLenBuf, 0)
            if (nonceLen != Common.CRYPTO_AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES) {
                println("DEBUG: Invalid nonce size: $nonceLen, expected: ${Common.CRYPTO_AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES}")
                return false
            }
            println("DEBUG: Nonce length: $nonceLen")

            // Читаем nonce
            val nonce = ByteArray(nonceLen)
            if (!Common.recvAll(socket, nonce, nonceLen)) {
                println("DEBUG: Failed to read nonce")
                return false
            }
            println("DEBUG: Nonce read successfully")

            // Читаем type (1 байт)
            val typeBuf = ByteArray(1)
            if (!Common.recvAll(socket, typeBuf, 1)) {
                println("DEBUG: Failed to read message type")
                return false
            }
            val msgType = typeBuf[0]
            println("DEBUG: Message type: $msgType")

            // Читаем clen (4 байта)
            val clenBuf = ByteArray(4)
            if (!Common.recvAll(socket, clenBuf, 4)) {
                println("DEBUG: Failed to read ciphertext length")
                return false
            }
            val clen = Common.readUInt32(clenBuf, 0)
            if (clen > Common.MAX_FRAME) {
                println("DEBUG: Ciphertext length too large: $clen")
                return false
            }
            println("DEBUG: Ciphertext length: $clen")

            // Читаем ciphertext
            val ciphertext = ByteArray(clen.toInt())
            if (!Common.recvAll(socket, ciphertext, clen.toInt())) {
                println("DEBUG: Failed to read ciphertext")
                return false
            }
            println("DEBUG: Ciphertext read successfully")

            // Пропускаем сообщения не из нашей комнаты или от себя
            if (room != currentRoom) {
                println("DEBUG: Skipping message - not for our room. Expected: '$currentRoom', Got: '$room'")
                return true
            }
            if (senderName == clientName) {
                println("DEBUG: Skipping message - from ourselves")
                return true
            }

            // Подготавливаем Additional Data (должно совпадать с PC версией)
            val ad = ByteArray(2 + roomLen + 2 + nameLen)
            var offset = 0
            Common.writeUInt16(ad, offset, roomLen)
            offset += 2
            System.arraycopy(roomBuf, 0, ad, offset, roomLen)
            offset += roomLen
            Common.writeUInt16(ad, offset, nameLen)
            offset += 2
            System.arraycopy(nameBuf, 0, ad, offset, nameLen)

            println("DEBUG: Additional data prepared: ${ad.size} bytes")
            println("DEBUG: Key size: ${roomKey.size}, Nonce size: ${nonce.size}, Ciphertext size: ${ciphertext.size}, AD size: ${ad.size}")

            // Дешифруем
            println("DEBUG: Starting decryption...")
            val startTime = System.currentTimeMillis()

// ВКЛЮЧАЕМ шифрование обратно
            val plaintext = Crypto.decrypt(ciphertext, ad, nonce, roomKey)

            val endTime = System.currentTimeMillis()
            println("DEBUG: Decryption took ${endTime - startTime} ms")

            if (plaintext == null) {
                println("DEBUG: Decryption failed - checking components...")

                // Проверим компоненты подробнее
                println("DEBUG: Key (first 8 bytes): ${roomKey.copyOf(8).joinToString(" ") { "%02x".format(it) }}")
                println("DEBUG: Nonce (first 8 bytes): ${nonce.copyOf(8).joinToString(" ") { "%02x".format(it) }}")
                println("DEBUG: Ciphertext (first 8 bytes): ${ciphertext.copyOf(8).joinToString(" ") { "%02x".format(it) }}")
                println("DEBUG: AD (first 8 bytes): ${ad.copyOf(8).joinToString(" ") { "%02x".format(it) }}")

                // Попробуем разные варианты дешифрования
                println("DEBUG: Trying alternative decryption approaches...")

                // Вариант 1: Попробуем использовать ciphertext как plaintext (если шифрование отключено на PC)
                try {
                    val fallbackText = String(ciphertext, Charsets.UTF_8)
                    if (fallbackText.isNotEmpty() && fallbackText.length < 100) {
                        val message = Message(room, senderName, "[FALLBACK] $fallbackText", System.currentTimeMillis())
                        notifyMessageReceived(message)
                        println("DEBUG: Fallback message displayed")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Fallback also failed")
                }

                return true
            }

            println("DEBUG: Decryption successful, plaintext size: ${plaintext.size}")

// Проверим, что plaintext выглядит как текст
            val content = String(plaintext, Charsets.UTF_8)
            println("DEBUG: Decrypted content: '$content'")

// Проверим, содержит ли текст печатные символы
            val isPrintable = content.all { it in ' '..'~' || it == '\n' || it == '\r' || it == '\t' }
            if (!isPrintable) {
                println("DEBUG: Content contains non-printable characters")
                // Попробуем hex представление
                println("DEBUG: Content as hex: ${plaintext.joinToString(" ") { "%02x".format(it) }}")
            }

            // Обрабатываем сообщение по типу
            when (msgType) {
                Common.MSG_TYPE_TEXT -> {
                    val message = Message(room, senderName, content, System.currentTimeMillis())
                    notifyMessageReceived(message)
                    println("DEBUG: Text message received from $senderName: $content")
                }
                Common.MSG_TYPE_FILE_START, Common.MSG_TYPE_FILE_CHUNK, Common.MSG_TYPE_FILE_END -> {
                    println("DEBUG: File transfer message type: $msgType")
                    // TODO: Реализовать обработку файлов
                }
                Common.MSG_TYPE_AUDIO_CALL_REQUEST -> {
                    val request = parseAudioCallRequest(String(plaintext, Charsets.UTF_8))
                    if (request != null && request.toUser == clientName) {
                        pendingCallRequest = request
                        notifyCallRequestReceived(request.fromUser)
                    }
                }

                Common.MSG_TYPE_AUDIO_CALL_ACCEPT -> {
                    val response = parseAudioCallResponse(String(plaintext, Charsets.UTF_8))
                    if (response != null && response.toUser == clientName && response.accepted) {
                        // Call accepted - wait for UDP info
                    }
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
                        // Start audio call with received UDP info
                        val host = socket.inetAddress.hostAddress ?: return@receiveMessage true
                        val manager = getOrCreateAudioCallManager()
                        manager.startCall(udpInfo.user, host, udpInfo.udpPort, udpInfo.noncePrefix)
                    }
                }

                Common.MSG_TYPE_AUDIO_CALL_END -> {
                    audioCallManager?.endCall()
                    notifyCallEnded()
                }
                else -> {
                    println("DEBUG: Unknown message type: $msgType")
                }
            }

            true
        } catch (e: Exception) {
            println("DEBUG: Exception in receiveMessage: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    private fun parseAudioCallRequest(json: String): AudioCallRequest? {
        return try {
            // Simple JSON parsing - in production use proper JSON library
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
                
                // Extract basename from path
                val basename = filename.substringAfterLast('/').substringAfterLast('\\')
                
                // Save to Downloads directory
                val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
                downloadsDir.mkdirs()
                val savePath = File(downloadsDir, basename).absolutePath
                
                currentFileTransfer = FileTransfer(
                    filename = savePath,
                    totalSize = fileSize,
                    expectedCrc = expectedCrc,
                    currentCrc = 0xFFFFFFFFL
                )
                
                // Create empty file to start receiving
                File(savePath).createNewFile()
                
                notifyFileTransferProgress(basename, 0f)
            }
            
            Common.MSG_TYPE_FILE_CHUNK -> {
                val transfer = currentFileTransfer ?: return
                
                var offset = 0
                val chunkCrc = Common.readUInt32(plaintext, offset)
                offset += 4
                val chunkData = plaintext.copyOfRange(offset, plaintext.size)
                
                // Verify chunk CRC
                if (Common.crc32(chunkData) != chunkCrc) {
                    notifyFileTransferError(transfer.filename, "Chunk CRC error")
                    currentFileTransfer = null
                    return
                }
                
                // Append to file
                FileOutputStream(transfer.filename, true).use { it.write(chunkData) }
                
                // Update CRC incrementally
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
                
                // Finalize CRC
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
    
    private fun notifyConnected() {
        handler.post { listener.onConnected() }
    }
    
    private fun notifyDisconnected() {
        handler.post { listener.onDisconnected() }
    }
    
    private fun notifyMessageReceived(message: Message) {
        handler.post { listener.onMessageReceived(message) }
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