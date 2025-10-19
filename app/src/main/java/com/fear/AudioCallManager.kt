// AudioCallManager.kt
package com.fear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Импортируем константы
//import com.fear.AudioConstants.*
import com.fear.AudioConstants.AUDIO_PCM_BYTES_PER_FRAME
import com.fear.AudioConstants.AUDIO_MAX_OPUS_BYTES
import com.fear.AudioConstants.AUDIO_UDP_RECV_BUFSIZE
import com.fear.AudioConstants.AUDIO_NONCE_PREFIX_LEN
import com.fear.AudioConstants.AUDIO_AEAD_NONCE_LEN
import com.fear.AudioConstants.AUDIO_AEAD_ABYTES
import com.fear.AudioConstants.PKT_VER_AUDIO
import com.fear.AudioConstants.PKT_VER_HELLO
import com.fear.AudioConstants.AUDIO_SAMPLE_RATE
import com.fear.AudioConstants.AUDIO_CHANNELS
import com.fear.AudioConstants.AC_OPUS_BITRATE
import com.fear.AudioConstants.AUDIO_FRAME_MS
import com.fear.AudioConstants.AUDIO_FRAME_SAMPLES


class AudioCallManager(
    private val context: Context,
    private val listener: AudioCallListener
) {
    interface AudioCallListener {
        fun onCallStateChanged(state: AudioCallState)
        fun onCallRequestReceived(fromUser: String)
        fun onCallError(error: String)
        fun onCallStarted(remoteUser: String, isInitiator: Boolean)
        fun onCallEnded()
    }

    private var audioCallState = AudioCallState()
    private var udpSocket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remoteUdpPort: Int = 0
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var udpReceiveJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val seqTx = AtomicLong(0)
    private var roomKey = ByteArray(0)
    private var localNoncePrefix = ByteArray(0)
    @Volatile private var remoteNoncePrefix = ByteArray(0)
    private var remotePrefixReady = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private val audioBuffer = ByteArray(AUDIO_PCM_BYTES_PER_FRAME)
    private val opusBuffer = ByteArray(AUDIO_MAX_OPUS_BYTES)
    private val packetBuffer = ByteArray(AUDIO_UDP_RECV_BUFSIZE)

    // Socket lock for thread-safe access
    private val socketLock = Any()

    // Wake locks to keep call alive when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Opus codec
    private var opusEncoder: OpusCodec.Encoder? = null
    private var opusDecoder: OpusCodec.Decoder? = null

    fun initialize(roomKey: ByteArray) {
        this.roomKey = roomKey
        this.localNoncePrefix = ByteArray(AUDIO_NONCE_PREFIX_LEN).apply {
            Crypto.generateNonce().copyInto(this, 0, 0, AUDIO_NONCE_PREFIX_LEN)
        }

        // Initialize Opus codec
        opusEncoder = OpusCodec.createEncoder(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, AC_OPUS_BITRATE)
        opusDecoder = OpusCodec.createDecoder(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)
    }

    private fun acquireWakeLocks() {
        try {
            // Acquire partial wake lock to keep CPU running when screen is off
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "F.E.A.R.::AudioCallWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }
            Log.d("ACM_DEBUG", "Wake lock acquired")

            // Acquire WiFi lock to keep WiFi active
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "F.E.A.R.::AudioCallWifiLock"
            ).apply {
                acquire()
            }
            Log.d("ACM_DEBUG", "WiFi lock acquired")
        } catch (e: Exception) {
            Log.e("ACM_DEBUG", "Failed to acquire wake locks: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("ACM_DEBUG", "Wake lock released")
                }
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("ACM_DEBUG", "WiFi lock released")
                }
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e("ACM_DEBUG", "Failed to release wake locks: ${e.message}")
        }
    }

    fun getLocalUdpPort(): Int {
        return udpSocket?.localPort ?: 0
    }

    fun getLocalNoncePrefix(): ByteArray {
        return localNoncePrefix
    }

    fun startCall(remoteUser: String, remoteHost: String, remoteUdpPort: Int, remoteNoncePrefix: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                this@AudioCallManager.remoteUdpPort = remoteUdpPort
                this@AudioCallManager.remoteAddress = InetAddress.getByName(remoteHost)
                this@AudioCallManager.remoteNoncePrefix = remoteNoncePrefix
                this@AudioCallManager.remotePrefixReady.set(true)
                
                startAudioCall(true, remoteUser)
                
            } catch (e: Exception) {
                notifyError("Failed to start call: ${e.message}")
            }
        }
    }

    fun acceptCall(remoteUser: String, remoteHost: String, remoteUdpPort: Int, remoteNoncePrefix: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                this@AudioCallManager.remoteUdpPort = remoteUdpPort
                this@AudioCallManager.remoteAddress = InetAddress.getByName(remoteHost)
                this@AudioCallManager.remoteNoncePrefix = remoteNoncePrefix
                this@AudioCallManager.remotePrefixReady.set(true)
                
                startAudioCall(false, remoteUser)
                
            } catch (e: Exception) {
                notifyError("Failed to accept call: ${e.message}")
            }
        }
    }

    fun endCall() {
        stopAudioCall()
    }

    fun startCallDirect(serverIp: String, serverPort: Int, localPort: Int, encryptionKey: ByteArray) {
        println("ACM_DEBUG: startCallDirect called")
        println("ACM_DEBUG: serverIp=$serverIp, serverPort=$serverPort, localPort=$localPort")

        CoroutineScope(Dispatchers.IO).launch {
            // Check if already running - do this inside coroutine to avoid blocking UI
            if (isRunning.get()) {
                println("ACM_DEBUG: Call already running, stopping first...")
                stopAudioCall()
                // Add small delay to ensure cleanup
                delay(200)
            }
            try {
                println("ACM_DEBUG: Inside coroutine")

                if (encryptionKey.size != 32) {
                    println("ACM_DEBUG: Invalid key size: ${encryptionKey.size}")
                    notifyError("Invalid encryption key size")
                    return@launch
                }

                println("ACM_DEBUG: Checking if need to reinitialize...")
                println("ACM_DEBUG: Encryption key (first 8 bytes): ${encryptionKey.take(8).joinToString(" ") { "%02x".format(it) }}")
                // Update room key if different OR if codecs not initialized
                if (!roomKey.contentEquals(encryptionKey) || opusEncoder == null || opusDecoder == null) {
                    println("ACM_DEBUG: Initializing with new key... (encoder=${opusEncoder != null}, decoder=${opusDecoder != null})")
                    initialize(encryptionKey)
                    println("ACM_DEBUG: Initialization complete (encoder=${opusEncoder != null}, decoder=${opusDecoder != null})")
                } else {
                    println("ACM_DEBUG: Already initialized, skipping")
                }

                println("ACM_DEBUG: Setting remote parameters...")
                // Set remote parameters
                remoteAddress = InetAddress.getByName(serverIp)
                remoteUdpPort = serverPort
                println("ACM_DEBUG: Remote address set: $remoteAddress:$remoteUdpPort")

                println("ACM_DEBUG: Creating UDP socket...")
                // Create UDP socket - try specific port, fallback to any free port
                udpSocket = try {
                    if (localPort > 0) {
                        println("ACM_DEBUG: Trying to bind to port $localPort...")
                        DatagramSocket(localPort).apply {
                            broadcast = false
                            reuseAddress = true
                        }
                    } else {
                        println("ACM_DEBUG: Using auto port selection...")
                        DatagramSocket().apply {
                            broadcast = false
                            reuseAddress = true
                        }
                    }
                } catch (e: Exception) {
                    println("ACM_DEBUG: Port binding failed: ${e.message}, using fallback...")
                    // If specific port fails, use any available port
                    DatagramSocket().apply {
                        broadcast = false
                        reuseAddress = true
                    }
                }
                println("ACM_DEBUG: UDP socket created on port ${udpSocket?.localPort}")

                println("ACM_DEBUG: Initializing audio...")
                // Initialize audio
                try {
                    initializeAudio()
                    println("ACM_DEBUG: Audio initialized successfully")
                } catch (e: Exception) {
                    println("ACM_DEBUG: Audio initialization failed: ${e.message}")
                    e.printStackTrace()
                    notifyError("Failed to initialize audio: ${e.message}")
                    udpSocket?.close()
                    udpSocket = null
                    return@launch
                }

                // Update state
                audioCallState = AudioCallState(
                    isInCall = true,
                    isCallActive = true,
                    remoteUser = "$serverIp:$serverPort",
                    isInitiator = true,
                    udpPort = udpSocket?.localPort ?: 0,
                    localNoncePrefix = localNoncePrefix,
                    remoteNoncePrefix = ByteArray(0) // Will be received via HELLO
                )

                isRunning.set(true)

                // Start foreground service to keep app alive
                AudioCallService.start(context)

                // Acquire wake locks to keep call alive when screen is off
                acquireWakeLocks()

                println("ACM_DEBUG: Starting threads...")
                // Start threads
                startUdpReceiving()
                println("ACM_DEBUG: UDP receiving started")

                startAudioRecording()
                println("ACM_DEBUG: Audio recording started")

                // Don't start playback loop - processAudioPacket will write directly to AudioTrack
                // startAudioPlayback()
                println("ACM_DEBUG: Audio playback ready (direct mode)")

                // Send HELLO packet to establish nonce prefix exchange
                sendHelloPacket()
                println("ACM_DEBUG: HELLO packet sent")

                // Send periodic HELLO packets until we receive remote prefix
                CoroutineScope(Dispatchers.IO).launch {
                    var attempts = 0
                    while (!remotePrefixReady.get() && isRunning.get() && attempts < 100) {
                        sendHelloPacket()
                        delay(50)
                        attempts++
                        if (attempts % 20 == 0) {
                            sendHelloPacket()
                        }
                    }
                }

                notifyCallStarted("$serverIp:$serverPort", true)

            } catch (e: Exception) {
                println("ACM_DEBUG: Exception in startCallDirect: ${e.message}")
                notifyError("Failed to start direct call: ${e.message}")
                e.printStackTrace()
                stopAudioCall()
            }
        }
    }

    private fun startAudioCall(isInitiator: Boolean, remoteUser: String) {
        if (isRunning.get()) return

        try {
            // Initialize UDP socket
            udpSocket = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }

            // Initialize audio
            initializeAudio()

            // Update state
            audioCallState = AudioCallState(
                isInCall = true,
                isCallActive = true,
                remoteUser = remoteUser,
                isInitiator = isInitiator,
                udpPort = udpSocket?.localPort ?: 0,
                localNoncePrefix = localNoncePrefix,
                remoteNoncePrefix = remoteNoncePrefix
            )

            isRunning.set(true)

            // Start foreground service to keep app alive
            AudioCallService.start(context)

            // Acquire wake locks to keep call alive when screen is off
            acquireWakeLocks()

            // Start threads
            startUdpReceiving()
            startAudioRecording()
            // Don't start playback loop - processAudioPacket will write directly to AudioTrack
            // startAudioPlayback()

            // Send HELLO packet if initiator
            if (isInitiator) {
                sendHelloPacket()
            }

            notifyCallStarted(remoteUser, isInitiator)
            
        } catch (e: Exception) {
            notifyError("Audio call setup failed: ${e.message}")
            stopAudioCall()
        }
    }

    private fun stopAudioCall() {
        println("ACM_DEBUG: stopAudioCall called, isRunning=${isRunning.get()}")

        if (!isRunning.get()) {
            println("ACM_DEBUG: Already stopped, returning")
            return
        }

        println("ACM_DEBUG: Setting isRunning to false")
        isRunning.set(false)

        println("ACM_DEBUG: Cancelling jobs...")
        // Stop jobs
        try {
            recordJob?.cancel()
            recordJob = null
        } catch (e: Exception) {
            println("ACM_DEBUG: Error cancelling recordJob: ${e.message}")
        }

        try {
            playJob?.cancel()
            playJob = null
        } catch (e: Exception) {
            println("ACM_DEBUG: Error cancelling playJob: ${e.message}")
        }

        try {
            udpReceiveJob?.cancel()
            udpReceiveJob = null
        } catch (e: Exception) {
            println("ACM_DEBUG: Error cancelling udpReceiveJob: ${e.message}")
        }

        // Small delay to allow threads to finish (non-blocking if already in coroutine)
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            // Interrupted during sleep, continue cleanup
        }

        println("ACM_DEBUG: Releasing audio resources...")
        // Release audio resources with synchronization
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            println("ACM_DEBUG: Error stopping audioRecord: ${e.message}")
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            println("ACM_DEBUG: Error releasing audioRecord: ${e.message}")
        }
        audioRecord = null

        // Synchronize audioTrack access to prevent race condition with playback thread
        synchronized(this) {
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                println("ACM_DEBUG: Error stopping audioTrack: ${e.message}")
            }

            try {
                audioTrack?.release()
            } catch (e: Exception) {
                println("ACM_DEBUG: Error releasing audioTrack: ${e.message}")
            }
            audioTrack = null
        }

        println("ACM_DEBUG: Closing UDP socket...")
        // Close UDP socket
        try {
            synchronized(socketLock) {
                udpSocket?.close()
                udpSocket = null
            }
        } catch (e: Exception) {
            println("ACM_DEBUG: Error closing UDP socket: ${e.message}")
        }

        println("ACM_DEBUG: Cleaning up Opus codecs...")
        // Clean up Opus encoder/decoder
        try {
            opusEncoder?.destroy()
            opusEncoder = null
        } catch (e: Exception) {
            println("ACM_DEBUG: Error destroying opusEncoder: ${e.message}")
        }

        try {
            opusDecoder?.destroy()
            opusDecoder = null
        } catch (e: Exception) {
            println("ACM_DEBUG: Error destroying opusDecoder: ${e.message}")
        }

        // Stop foreground service
        try {
            AudioCallService.stop(context)
        } catch (e: Exception) {
            Log.e("ACM_DEBUG", "Failed to stop foreground service: ${e.message}")
        }

        // Release wake locks
        releaseWakeLocks()

        // Reset state
        remotePrefixReady.set(false)
        audioCallState = AudioCallState()

        println("ACM_DEBUG: stopAudioCall completed")
        notifyCallEnded()
    }

    private fun initializeAudio() {
        println("ACM_DEBUG: initializeAudio - starting")

        try {
            // Clean up any existing audio resources first
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                println("ACM_DEBUG: Error cleaning up old audioRecord: ${e.message}")
            }
            audioRecord = null

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                println("ACM_DEBUG: Error cleaning up old audioTrack: ${e.message}")
            }
            audioTrack = null

            println("ACM_DEBUG: Creating AudioRecord...")
            // AudioRecord for recording
            val minRecordBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            println("ACM_DEBUG: Minimum record buffer size: $minRecordBufferSize")

            if (minRecordBufferSize == AudioRecord.ERROR || minRecordBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                throw Exception("AudioRecord not supported for sample rate $AUDIO_SAMPLE_RATE Hz")
            }

            // Use larger buffer to prevent overflows
            val recordBufferSize = maxOf(minRecordBufferSize, AUDIO_PCM_BYTES_PER_FRAME * 4)
            println("ACM_DEBUG: Using record buffer size: $recordBufferSize")

            // Use MIC instead of VOICE_COMMUNICATION to avoid echo cancellation issues
            // VOICE_COMMUNICATION applies aggressive AEC/AGC which can:
            // 1. Mute microphone when hearing sound from speaker
            // 2. Capture echo from speaker in multi-party calls
            // MIC gives raw microphone input without processing
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw Exception("Failed to initialize AudioRecord - state is ${audioRecord?.state}")
            }
            println("ACM_DEBUG: AudioRecord created successfully")

            println("ACM_DEBUG: Creating AudioTrack...")
            // AudioTrack for playback
            val minPlayBufferSize = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            println("ACM_DEBUG: Minimum play buffer size: $minPlayBufferSize")

            if (minPlayBufferSize == AudioTrack.ERROR || minPlayBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                throw Exception("AudioTrack not supported for sample rate $AUDIO_SAMPLE_RATE Hz")
            }

            // Use larger buffer to prevent underruns
            val playBufferSize = maxOf(minPlayBufferSize, AUDIO_PCM_BYTES_PER_FRAME * 4)
            println("ACM_DEBUG: Using play buffer size: $playBufferSize")

            // Use AudioAttributes builder (modern API)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                throw Exception("Failed to initialize AudioTrack - state is ${audioTrack?.state}")
            }
            println("ACM_DEBUG: AudioTrack created successfully")

            println("ACM_DEBUG: Starting recording and playback...")
            audioRecord?.startRecording()
            println("ACM_DEBUG: AudioRecord started")

            audioTrack?.play()
            println("ACM_DEBUG: AudioTrack started")

            println("ACM_DEBUG: initializeAudio - completed successfully")

        } catch (e: Exception) {
            println("ACM_DEBUG: initializeAudio - exception: ${e.message}")
            e.printStackTrace()

            // Clean up on error
            try {
                audioRecord?.release()
            } catch (ex: Exception) {
                println("ACM_DEBUG: Error releasing audioRecord: ${ex.message}")
            }
            audioRecord = null

            try {
                audioTrack?.release()
            } catch (ex: Exception) {
                println("ACM_DEBUG: Error releasing audioTrack: ${ex.message}")
            }
            audioTrack = null

            throw Exception("Audio initialization failed: ${e.message}", e)
        }
    }

    private fun startUdpReceiving() {
        udpReceiveJob = CoroutineScope(Dispatchers.IO).launch {
            println("ACM_DEBUG: startUdpReceiving - entering loop")

            while (isRunning.get()) {
                try {
                    // Create a FRESH buffer for each receive to avoid memory corruption
                    // This prevents native crashes from invalid buffer references
                    val receiveBuffer = ByteArray(AUDIO_UDP_RECV_BUFSIZE)
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                    // Thread-safe socket access with synchronized block
                    val receiveSuccessful = synchronized(socketLock) {
                        val socket = udpSocket
                        if (socket == null || socket.isClosed) {
                            println("ACM_DEBUG: UDP socket is null or closed, exiting")
                            return@synchronized false
                        }

                        // Set timeout inside synchronized block
                        try {
                            socket.soTimeout = 1000 // 1 second timeout
                        } catch (e: Exception) {
                            println("ACM_DEBUG: Failed to set socket timeout: ${e.message}")
                            return@synchronized false
                        }

                        // Receive data inside synchronized block to prevent socket closure during receive
                        try {
                            socket.receive(packet)
                            true
                        } catch (e: java.net.SocketTimeoutException) {
                            // Timeout is normal, continue loop
                            null
                        } catch (e: java.net.SocketException) {
                            // Socket was closed, exit loop
                            println("ACM_DEBUG: Socket exception during receive: ${e.message}")
                            false
                        } catch (e: Exception) {
                            // Other exception, exit loop
                            println("ACM_DEBUG: Unexpected exception during receive: ${e.message}")
                            false
                        }
                    }

                    // Check result from synchronized block
                    when (receiveSuccessful) {
                        null -> continue // Timeout, try again
                        false -> break // Error or socket closed, exit loop
                        true -> {
                            // Success, process packet
                            if (packet.length > 0 && isRunning.get()) {
                                try {
                                    processAudioPacket(packet.data, packet.length)
                                } catch (e: Throwable) {
                                    // Silently ignore ALL packet processing errors
                                }
                            }
                        }
                    }

                } catch (e: Throwable) {
                    if (isRunning.get()) {
                        println("ACM_DEBUG: UDP receive outer exception: ${e.message}")
                        break
                    }
                }
            }
            println("ACM_DEBUG: startUdpReceiving - exiting loop")
        }
    }

    private fun startAudioRecording() {
        println("ACM_DEBUG: startAudioRecording called")
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            println("ACM_DEBUG: startAudioRecording - inside coroutine")
            val recorder = audioRecord
            if (recorder == null) {
                println("ACM_DEBUG: audioRecord is null in startAudioRecording!")
                return@launch
            }

            println("ACM_DEBUG: startAudioRecording - audioRecord is valid, entering loop")

            var frameCount = 0
            while (isRunning.get()) {
                try {
                    val bytesRead = recorder.read(audioBuffer, 0, audioBuffer.size)
                    if (bytesRead > 0) {
                        frameCount++
                        if (frameCount % 50 == 0) {
                            println("ACM_DEBUG: Recording frame $frameCount, bytes: $bytesRead")
                        }
                        processAudioData(audioBuffer, bytesRead)
                    } else if (bytesRead < 0) {
                        println("ACM_DEBUG: recorder.read returned error: $bytesRead")
                    }
                } catch (e: Exception) {
                    println("ACM_DEBUG: Recording exception: ${e.message}")
                    e.printStackTrace()
                    if (isRunning.get()) {
                        notifyError("Recording error: ${e.message}")
                    }
                    break
                }
            }
            println("ACM_DEBUG: startAudioRecording - exiting loop")
        }
    }

    private fun startAudioPlayback() {
        println("ACM_DEBUG: startAudioPlayback called")
        playJob = CoroutineScope(Dispatchers.IO).launch {
            val track = audioTrack
            if (track == null) {
                println("ACM_DEBUG: audioTrack is null in startAudioPlayback!")
                return@launch
            }

            println("ACM_DEBUG: startAudioPlayback - entering loop")

            // For now, just play silence
            // In full implementation, this would play from a decoded audio buffer
            val silence = ByteArray(AUDIO_PCM_BYTES_PER_FRAME) { 0 }

            while (isRunning.get()) {
                try {
                    // Synchronize entire write operation to prevent track from being released
                    val written = try {
                        synchronized(this@AudioCallManager) {
                            val currentTrack = audioTrack

                            if (currentTrack == null) {
                                println("ACM_DEBUG: audioTrack became null, stopping playback")
                                return@synchronized -2  // Signal to break
                            }

                            // Check state before write
                            if (currentTrack.state != AudioTrack.STATE_INITIALIZED) {
                                println("ACM_DEBUG: audioTrack not initialized, stopping playback")
                                return@synchronized -2  // Signal to break
                            }

                            if (currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                println("ACM_DEBUG: audioTrack not playing, stopping playback")
                                return@synchronized -2  // Signal to break
                            }

                            // Write while still holding the lock
                            currentTrack.write(silence, 0, silence.size)
                        }
                    } catch (e: IllegalStateException) {
                        println("ACM_DEBUG: IllegalStateException during write: ${e.message}")
                        -1
                    } catch (e: NullPointerException) {
                        println("ACM_DEBUG: NullPointerException during write: ${e.message}")
                        -1
                    }

                    if (written == -2 || written < 0) {
                        if (written == -2) {
                            // Track is gone or invalid, stop playback
                            break
                        }
                        println("ACM_DEBUG: track.write returned error: $written")
                        break
                    }

                    delay(AUDIO_FRAME_MS.toLong())
                } catch (e: Exception) {
                    println("ACM_DEBUG: Playback exception: ${e.message}")
                    e.printStackTrace()
                    if (isRunning.get()) {
                        // Stop playback on error
                        break
                    }
                }
            }
            println("ACM_DEBUG: startAudioPlayback - exiting loop")
        }
    }

    private var audioFrameCount = 0

    private fun processAudioData(audioData: ByteArray, length: Int) {
        audioFrameCount++

        if (!remotePrefixReady.get()) {
            // Skip sending audio until we have remote prefix
            if (audioFrameCount % 100 == 0) {
                println("ACM_DEBUG: processAudioData - waiting for remote prefix (frame $audioFrameCount)")
            }
            return
        }

        if (audioFrameCount % 100 == 0) {
            println("ACM_DEBUG: processAudioData - processing frame $audioFrameCount, length=$length")
        }

        try {
            val encoder = opusEncoder
            if (encoder == null) {
                println("ACM_DEBUG: processAudioData - encoder is null!")
                return
            }

            // Ensure we have exactly the right amount of data
            // We expect AUDIO_PCM_BYTES_PER_FRAME (1920 bytes = 960 samples * 2 bytes/sample)
            if (length < AUDIO_PCM_BYTES_PER_FRAME) {
                // Not enough data, skip this frame
                return
            }

            // Convert bytes to shorts for Opus encoder
            // Take only AUDIO_FRAME_SAMPLES (960 samples)
            val pcmSamples = ShortArray(AUDIO_FRAME_SAMPLES)
            for (i in 0 until AUDIO_FRAME_SAMPLES) {
                val idx = i * 2
                if (idx + 1 < length && idx + 1 < AUDIO_PCM_BYTES_PER_FRAME) {
                    val low = audioData[idx].toInt() and 0xFF
                    val high = audioData[idx + 1].toInt() and 0xFF
                    pcmSamples[i] = ((high shl 8) or low).toShort()
                } else {
                    pcmSamples[i] = 0  // Pad with silence if needed
                }
            }

            // Encode with Opus - pass exact frame size
            val encodedData = encoder.encode(pcmSamples, AUDIO_FRAME_SAMPLES)

            if (encodedData.isEmpty()) {
                println("ACM_DEBUG: Encoder returned empty data, skipping")
                return
            }

            if (audioFrameCount % 100 == 0) {
                println("ACM_DEBUG: Encoded ${encodedData.size} bytes")
            }

            // Encrypt with AES-GCM
            val seq = seqTx.getAndIncrement()
            val encryptedPacket = encryptAudioPacket(encodedData, seq)

            if (encryptedPacket == null) {
                println("ACM_DEBUG: processAudioData - encryption failed, skipping")
                return
            }

            if (audioFrameCount % 100 == 0) {
                println("ACM_DEBUG: Encrypted ${encryptedPacket.size} bytes, seq=$seq")
            }

            // Send over UDP
            sendUdpPacket(encryptedPacket)

            if (audioFrameCount % 100 == 0) {
                println("ACM_DEBUG: Sent audio packet, seq=$seq")
            }

        } catch (e: Exception) {
            // Don't spam error messages - audio processing errors are common
            // Just log to console
            println("ACM_DEBUG: processAudioData exception: ${e.message}")
        }
    }

    private fun processAudioPacket(packetData: ByteArray, length: Int) {
        // Early return if not running
        if (!isRunning.get()) return

        try {
            // Log first byte to see what we're receiving
            if (length > 0) {
                val firstByte = packetData[0]
                if (firstByte == PKT_VER_AUDIO) {
                    // Audio packet - log always for debugging
                    Log.d("ACM_DEBUG", "Received AUDIO packet, length=$length")
                } else if (firstByte != PKT_VER_HELLO) {
                    Log.d("ACM_DEBUG", "Received packet with unknown version: 0x${firstByte.toString(16)}, length=$length")
                }
            }

            // Check if this is a HELLO packet
            if (length >= 1 + AUDIO_NONCE_PREFIX_LEN && packetData[0] == PKT_VER_HELLO) {
                Log.d("ACM_DEBUG", "Received HELLO packet, length=$length")
                try {
                    // Extract remote nonce prefix
                    val receivedPrefix = ByteArray(AUDIO_NONCE_PREFIX_LEN)
                    System.arraycopy(packetData, 1, receivedPrefix, 0, AUDIO_NONCE_PREFIX_LEN)

                    println("ACM_DEBUG: Extracted remote nonce prefix: ${receivedPrefix.joinToString(" ") { "%02x".format(it) }}")

                    // IMPORTANT: In multi-party calls, we need to accept HELLO from any participant
                    // Simply use the most recent HELLO packet's nonce prefix
                    // This allows the app to work in group calls where multiple peers send HELLO
                    val isNewPrefix = !receivedPrefix.contentEquals(remoteNoncePrefix)

                    if (remoteNoncePrefix.isEmpty()) {
                        println("ACM_DEBUG: Remote nonce prefix set (FIRST TIME), remotePrefixReady=true")
                    } else if (isNewPrefix) {
                        println("ACM_DEBUG: Switching to new remote peer: ${receivedPrefix.joinToString(" ") { "%02x".format(it) }}")
                    }

                    // Always update remote nonce prefix to support multi-party calls
                    remoteNoncePrefix = receivedPrefix
                    remotePrefixReady.set(true)

                    // Send our HELLO back if we haven't sent it yet or in response
                    if (!audioCallState.isInitiator) {
                        println("ACM_DEBUG: Sending HELLO response")
                        sendHelloPacket()
                    }
                } catch (e: Exception) {
                    println("ACM_DEBUG: HELLO processing error: ${e.message}")
                    e.printStackTrace()
                }
                return
            }

            // Check if we have decoder
            val decoder = opusDecoder
            if (decoder == null) {
                println("ACM_DEBUG: No decoder available, skipping audio packet")
                return
            }

            // Check if we're still running before continuing
            if (!isRunning.get()) {
                println("ACM_DEBUG: Not running, skipping audio packet")
                return
            }

            println("ACM_DEBUG: Attempting to decrypt audio packet...")
            // Decrypt packet - can return null if wrong key or corrupted
            val decryptResult = try {
                decryptAudioPacket(packetData, length)
            } catch (e: Exception) {
                println("ACM_DEBUG: Decryption exception: ${e.message}")
                e.printStackTrace()
                null
            }

            if (decryptResult == null) {
                println("ACM_DEBUG: Decryption failed, skipping packet")
                return
            }

            val (encodedData, seq) = decryptResult

            // Log decryption success always
            println("ACM_DEBUG: Decrypted audio packet, seq=$seq, size=${encodedData.size}")

            // Check if we're still running before decoding
            if (!isRunning.get()) {
                println("ACM_DEBUG: Not running after decrypt, skipping")
                return
            }

            println("ACM_DEBUG: Attempting to decode ${encodedData.size} bytes...")
            // Decode with Opus - this is native code and can crash
            val pcmSamples = try {
                decoder.decode(encodedData, AUDIO_FRAME_SAMPLES)
            } catch (e: Exception) {
                // Opus decode failed - corrupted data or wrong format
                println("ACM_DEBUG: Opus decode failed: ${e.message}")
                e.printStackTrace()
                return
            }

            // Check if decode was successful (decoder returns empty array on error)
            if (pcmSamples.isEmpty()) {
                println("ACM_DEBUG: Opus decoder returned empty array")
                return
            }

            println("ACM_DEBUG: Decoded ${pcmSamples.size} samples successfully")

            // Check if we're still running before playing
            if (!isRunning.get()) return

            // Convert shorts to bytes for AudioTrack
            val audioData = try {
                ByteArray(pcmSamples.size * 2).also { data ->
                    for (i in pcmSamples.indices) {
                        val sample = pcmSamples[i].toInt()
                        data[i * 2] = (sample and 0xFF).toByte()
                        data[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                }
            } catch (e: Exception) {
                return
            }

            // Play audio - use synchronized access for entire write operation
            // This prevents the track from being released while we're writing to it
            if (isRunning.get()) {
                try {
                    synchronized(this@AudioCallManager) {
                        val track = audioTrack
                        if (track != null &&
                            track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            val written = track.write(audioData, 0, audioData.size)
                            if (seq % 50 == 0L) {
                                println("ACM_DEBUG: Wrote $written bytes to AudioTrack (requested ${audioData.size})")
                            }
                        } else {
                            println("ACM_DEBUG: AudioTrack not ready - state=${track?.state}, playState=${track?.playState}")
                        }
                    }
                } catch (e: IllegalStateException) {
                    println("ACM_DEBUG: AudioTrack write IllegalStateException: ${e.message}")
                } catch (e: NullPointerException) {
                    println("ACM_DEBUG: AudioTrack write NullPointerException: ${e.message}")
                } catch (e: Exception) {
                    println("ACM_DEBUG: AudioTrack write Exception: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            // Catch everything including native crashes
            // Don't propagate any exceptions
        }
    }

    private fun encryptAudioPacket(audioData: ByteArray, seq: Long): ByteArray? {
        try {
            // Check if we have local nonce prefix
            if (localNoncePrefix.size < AUDIO_NONCE_PREFIX_LEN) {
                println("ACM_DEBUG: encryptAudioPacket - localNoncePrefix not ready or too small (${localNoncePrefix.size})")
                return null
            }

            val nonce = ByteArray(AUDIO_AEAD_NONCE_LEN).apply {
                // Build nonce: prefix + sequence number
                System.arraycopy(localNoncePrefix, 0, this, 0, AUDIO_NONCE_PREFIX_LEN)
                // Add sequence number (big endian)
                for (i in 0 until 8) {
                    this[AUDIO_NONCE_PREFIX_LEN + i] = ((seq shr (8 * (7 - i))) and 0xFF).toByte()
                }
            }

            // Encrypt with AES-GCM
            val ciphertext = Crypto.encrypt(audioData, byteArrayOf(), nonce, roomKey)
            if (ciphertext == null) {
                println("ACM_DEBUG: encryptAudioPacket - encryption failed")
                return null
            }

            // Build packet: [1 version][8 seq][ciphertext]
            val packet = ByteArray(1 + 8 + ciphertext.size)
            packet[0] = PKT_VER_AUDIO
            for (i in 0 until 8) {
                packet[1 + i] = ((seq shr (8 * (7 - i))) and 0xFF).toByte()
            }
            System.arraycopy(ciphertext, 0, packet, 9, ciphertext.size)

            return packet
        } catch (e: Exception) {
            println("ACM_DEBUG: encryptAudioPacket - exception: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun decryptAudioPacket(packetData: ByteArray, length: Int): Pair<ByteArray, Long>? {
        if (length < 1 + 8 + AUDIO_AEAD_ABYTES) return null
        if (packetData[0] != PKT_VER_AUDIO) return null

        // Check if we have remote nonce prefix
        val prefixSize = remoteNoncePrefix.size
        val prefixReady = remotePrefixReady.get()
        if (prefixSize < AUDIO_NONCE_PREFIX_LEN) {
            if (System.currentTimeMillis() % 1000 < 50) {
                println("ACM_DEBUG: decryptAudioPacket - size=$prefixSize, ready=$prefixReady, prefix=${remoteNoncePrefix.joinToString(" ") { "%02x".format(it) }}")
            }
            return null
        }

        // Extract sequence number
        var seq: Long = 0
        for (i in 0 until 8) {
            seq = (seq shl 8) or (packetData[1 + i].toLong() and 0xFF)
        }

        // Log occasionally
        if (seq % 100 == 0L) {
            println("ACM_DEBUG: decryptAudioPacket - attempting decrypt, seq=$seq, length=$length")
        }

        // Build nonce
        val nonce = try {
            ByteArray(AUDIO_AEAD_NONCE_LEN).apply {
                System.arraycopy(remoteNoncePrefix, 0, this, 0, AUDIO_NONCE_PREFIX_LEN)
                for (i in 0 until 8) {
                    this[AUDIO_NONCE_PREFIX_LEN + i] = ((seq shr (8 * (7 - i))) and 0xFF).toByte()
                }
            }
        } catch (e: Exception) {
            println("ACM_DEBUG: decryptAudioPacket - failed to build nonce: ${e.message}")
            return null
        }

        // Extract ciphertext
        val ciphertext = packetData.copyOfRange(9, length)

        // Decrypt
        val plaintext = Crypto.decrypt(ciphertext, byteArrayOf(), nonce, roomKey)
        if (plaintext == null) {
            if (seq % 100 == 0L) {
                println("ACM_DEBUG: decryptAudioPacket - decryption failed, seq=$seq")
            }
            return null
        }

        return Pair(plaintext, seq)
    }

    private fun sendHelloPacket() {
        val packet = ByteArray(1 + AUDIO_NONCE_PREFIX_LEN)
        packet[0] = PKT_VER_HELLO
        System.arraycopy(localNoncePrefix, 0, packet, 1, AUDIO_NONCE_PREFIX_LEN)
        
        sendUdpPacket(packet)
    }

    private fun sendUdpPacket(packet: ByteArray) {
        val socket = udpSocket ?: return
        val address = remoteAddress ?: return
        
        try {
            val udpPacket = DatagramPacket(
                packet, packet.size,
                address, remoteUdpPort
            )
            socket.send(udpPacket)
        } catch (e: Exception) {
            // Ignore send errors
        }
    }

    private fun notifyCallStarted(remoteUser: String, isInitiator: Boolean) {
        handler.post {
            listener.onCallStarted(remoteUser, isInitiator)
        }
    }

    private fun notifyCallEnded() {
        handler.post {
            listener.onCallEnded()
        }
    }

    private fun notifyError(error: String) {
        handler.post {
            listener.onCallError(error)
        }
    }
}