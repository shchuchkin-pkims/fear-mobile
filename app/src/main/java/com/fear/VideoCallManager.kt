package com.fear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Video call manager for FEAR messenger.
 * Compatible with desktop video_call.c implementation.
 *
 * Protocol: UDP with AES-256-GCM encryption.
 * Audio (0x01) and Video (0x02) packets on same socket.
 * HELLO (0x7F) for handshake.
 */
class VideoCallManager(
    private val context: Context,
    private val listener: VideoCallListener
) {
    companion object {
        private const val TAG = "VCM"
    }

    interface VideoCallListener {
        fun onCallStarted()
        fun onCallEnded()
        fun onCallError(error: String)
        fun onConnected(peerWidth: Int, peerHeight: Int, peerFps: Int)
        fun onRemoteVideoFrame(data: ByteArray, width: Int, height: Int)
        fun onStatsUpdated(packetsReceived: Int, packetsLost: Int, rttMs: Int)
    }

    // Keys
    private var audioKey: ByteArray? = null
    private var videoKey: ByteArray? = null

    // Nonce prefixes
    private val localNoncePrefix = ByteArray(4)
    private var remoteNoncePrefix: ByteArray? = null
    private val remotePrefixReady = AtomicBoolean(false)

    // Sequence counters
    private val audioSeqTx = AtomicLong(0)
    private val videoSeqTx = AtomicLong(0)

    // Socket
    private var udpSocket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort = 0

    // State
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null
    private var helloThread: Thread? = null
    private var audioSendThread: Thread? = null
    private var videoSendThread: Thread? = null

    // Codecs
    private var vp8Encoder: Vp8Encoder? = null
    private var vp8Decoder: Vp8Decoder? = null

    // Audio
    private var opusEncoder: OpusCodec.Encoder? = null
    private var opusDecoder: OpusCodec.Decoder? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecordThread: Thread? = null
    @Volatile var isMuted = false

    // Quality
    private var quality = VideoQualityPreset.MEDIUM
    private var sendWidth = 0   // actual encoder/HELLO width (may be swapped for rotation)
    private var sendHeight = 0  // actual encoder/HELLO height
    private var sensorRotation = 0
    private var encoderReady = false
    private var remoteWidth = 0
    private var remoteHeight = 0
    private var remoteFps = 0

    // Fragment reassembly
    private val pendingFrames = HashMap<Long, PendingFrame>()
    private var frameIdCounter = 0L

    // Identity
    private var identityManager: IdentityManager? = null
    private var peerVerified = false

    // Stats
    private var videoPacketsSent = 0L
    private var videoPacketsReceived = 0L

    data class PendingFrame(
        val frameId: Long,
        val totalFrags: Int,
        val data: ByteArray,
        val received: BooleanArray,
        var receivedCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun initialize(masterKey: ByteArray, preset: VideoQualityPreset = VideoQualityPreset.MEDIUM,
                   identityMgr: IdentityManager? = null) {
        audioKey = KeyDerivation.deriveAudioKey(masterKey)
        videoKey = KeyDerivation.deriveVideoKey(masterKey)
        quality = preset
        sendWidth = preset.width
        sendHeight = preset.height
        identityManager = identityMgr
        SecureRandom().nextBytes(localNoncePrefix)

        Log.d(TAG, "Initialized: quality=${preset.width}x${preset.height}@${preset.fps}")
        Log.d(TAG, "Local nonce prefix: ${localNoncePrefix.toHex()}")
        Log.d(TAG, "Audio key[0..3]: ${audioKey!!.take(4).toByteArray().toHex()}")
        Log.d(TAG, "Video key[0..3]: ${videoKey!!.take(4).toByteArray().toHex()}")
        Log.d(TAG, "Identity: ${if (identityMgr?.hasIdentity() == true) "yes" else "no"}")
    }

    fun getLocalUdpPort(): Int = udpSocket?.localPort ?: 0

    /**
     * Start video call to remote peer (network only — decoder attached later via attachDecoderSurface).
     */
    fun startCall(remoteIp: String, remoteUdpPort: Int, localUdpPort: Int = 0) {
        if (running.get()) return

        Log.d(TAG, "startCall: remote=$remoteIp:$remoteUdpPort, localPort=$localUdpPort")

        try {
            remoteAddress = InetAddress.getByName(remoteIp)
            Log.d(TAG, "Resolved remote address: ${remoteAddress?.hostAddress}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve remote IP: $remoteIp", e)
            listener.onCallError("Cannot resolve: $remoteIp")
            return
        }
        remotePort = remoteUdpPort

        try {
            udpSocket = if (localUdpPort > 0) DatagramSocket(localUdpPort) else DatagramSocket()
            udpSocket?.soTimeout = 100
            Log.d(TAG, "UDP socket bound to port ${udpSocket?.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP socket", e)
            listener.onCallError("Socket error: ${e.message}")
            return
        }

        running.set(true)

        // Initialize audio
        initializeAudio()

        // Start HELLO handshake
        helloThread = Thread {
            try {
                helloLoop()
            } catch (e: Exception) {
                Log.e(TAG, "helloLoop crashed", e)
                if (running.get()) {
                    listener.onCallError("Handshake failed: ${e.message}")
                    endCall()
                }
            }
        }.also { it.start() }

        // Start receive loop
        receiveThread = Thread {
            try {
                receiveLoop()
            } catch (e: Exception) {
                Log.e(TAG, "receiveLoop crashed", e)
            }
        }.also { it.start() }

        listener.onCallStarted()
    }

    /**
     * Attach decoder surface for rendering remote video. Can be called after startCall.
     */
    fun attachDecoderSurface(surface: Surface) {
        if (vp8Decoder != null) return // Already attached
        try {
            vp8Decoder = Vp8Decoder().also {
                it.start(surface, quality.width, quality.height)
            }
            Log.d(TAG, "VP8 decoder attached and started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VP8 decoder", e)
        }
    }

    /**
     * Mark encoder as ready. Actual creation deferred until first frame
     * when we know the camera's rotation.
     */
    fun startSending(rotationDegrees: Int = 0) {
        sensorRotation = rotationDegrees
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        sendWidth = if (rotated) quality.height else quality.width
        sendHeight = if (rotated) quality.width else quality.height

        Log.d(TAG, "startSending: rotation=$rotationDegrees -> encoder ${sendWidth}x${sendHeight}")

        try {
            vp8Encoder = Vp8Encoder(sendWidth, sendHeight, quality.fps, quality.bitrateKbps).also {
                it.start()
            }
            encoderReady = true
            Log.d(TAG, "VP8 encoder started: ${sendWidth}x${sendHeight}@${quality.fps}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VP8 encoder", e)
        }
    }

    /**
     * Feed camera plane data directly for encoding and sending.
     * No intermediate format conversion — planes are copied directly to encoder.
     */
    fun sendVideoFrame(
        yBuf: ByteBuffer, yRowStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        srcWidth: Int, srcHeight: Int
    ) {
        if (!running.get() || !remotePrefixReady.get()) return

        val encoder = vp8Encoder ?: return
        val vKey = videoKey ?: return

        val pts = System.nanoTime() / 1000
        val encoded = encoder.encodePlanes(
            yBuf, yRowStride,
            uBuf, uRowStride, uPixelStride,
            vBuf, vRowStride, vPixelStride,
            srcWidth, srcHeight,
            sensorRotation,
            pts
        ) ?: return

        sendFragmentedFrame(encoded, vKey)
    }

    /**
     * Feed Opus-encoded audio data for sending.
     */
    fun sendAudioPacket(opusData: ByteArray) {
        if (!running.get() || !remotePrefixReady.get()) return
        val aKey = audioKey ?: return

        val seq = audioSeqTx.getAndIncrement()
        val nonce = buildNonce(localNoncePrefix, seq)
        val encrypted = aesGcmEncrypt(opusData, aKey, nonce) ?: return

        val packet = ByteBuffer.allocate(1 + 8 + encrypted.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(Common.PKT_VER_AUDIO)
            putLong(seq)
            put(encrypted)
        }.array()

        sendUdp(packet)
    }

    // --- Audio ---

    private fun initializeAudio() {
        try {
            // Opus codecs
            opusEncoder = OpusCodec.createEncoder(Common.AUDIO_SAMPLE_RATE, Common.AUDIO_CHANNELS, Common.AC_OPUS_BITRATE)
            opusDecoder = OpusCodec.createDecoder(Common.AUDIO_SAMPLE_RATE, Common.AUDIO_CHANNELS)

            // AudioRecord (microphone)
            val minRecBuf = AudioRecord.getMinBufferSize(
                Common.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recBuf = maxOf(minRecBuf, Common.AUDIO_PCM_BYTES_PER_FRAME * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Common.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBuf
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                audioRecord?.release()
                audioRecord = null
            }

            // AudioTrack (speaker)
            val minPlayBuf = AudioTrack.getMinBufferSize(
                Common.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val playBuf = maxOf(minPlayBuf, Common.AUDIO_PCM_BYTES_PER_FRAME * 4)

            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(Common.AUDIO_SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(playBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack init failed")
                audioTrack?.release()
                audioTrack = null
            }

            // Start recording and playback
            audioRecord?.startRecording()
            audioTrack?.play()

            // Start mic capture thread
            audioRecordThread = Thread {
                micCaptureLoop()
            }.also { it.start() }

            Log.d(TAG, "Audio initialized: record=${audioRecord != null} track=${audioTrack != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Audio init failed: ${e.message}")
        }
    }

    private fun micCaptureLoop() {
        val pcmBuf = ByteArray(Common.AUDIO_PCM_BYTES_PER_FRAME)
        Log.d(TAG, "Mic capture loop started")

        while (running.get()) {
            try {
                val recorder = audioRecord ?: break
                val bytesRead = recorder.read(pcmBuf, 0, pcmBuf.size)
                if (bytesRead < Common.AUDIO_PCM_BYTES_PER_FRAME) continue
                if (isMuted) continue
                if (!remotePrefixReady.get()) continue

                val encoder = opusEncoder ?: continue

                // Convert bytes to shorts
                val pcmSamples = ShortArray(Common.AUDIO_FRAME_SAMPLES)
                for (i in 0 until Common.AUDIO_FRAME_SAMPLES) {
                    val idx = i * 2
                    val low = pcmBuf[idx].toInt() and 0xFF
                    val high = pcmBuf[idx + 1].toInt() and 0xFF
                    pcmSamples[i] = ((high shl 8) or low).toShort()
                }

                // Encode with Opus
                val opusData = encoder.encode(pcmSamples, Common.AUDIO_FRAME_SAMPLES)
                if (opusData.isEmpty()) continue

                // Send encrypted audio packet
                sendAudioPacket(opusData)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Mic capture error: ${e.message}")
                break
            }
        }
        Log.d(TAG, "Mic capture loop ended")
    }

    fun endCall() {
        running.set(false)

        helloThread?.interrupt()
        receiveThread?.interrupt()
        audioSendThread?.interrupt()
        videoSendThread?.interrupt()
        audioRecordThread?.interrupt()

        vp8Encoder?.stop()
        vp8Decoder?.stop()

        // Stop audio
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { opusEncoder?.destroy() } catch (_: Exception) {}
        opusEncoder = null
        try { opusDecoder?.destroy() } catch (_: Exception) {}
        opusDecoder = null

        udpSocket?.close()
        udpSocket = null

        pendingFrames.clear()
        remotePrefixReady.set(false)
        remoteNoncePrefix = null
        audioSeqTx.set(0)
        videoSeqTx.set(0)
        peerVerified = false

        Log.d(TAG, "Call ended. Sent=$videoPacketsSent Received=$videoPacketsReceived")
        videoPacketsSent = 0
        videoPacketsReceived = 0

        listener.onCallEnded()
    }

    // --- HELLO handshake ---

    private fun helloLoop() {
        var retries = 0
        Log.d(TAG, "HELLO loop started, sending to ${remoteAddress?.hostAddress}:$remotePort")

        while (running.get() && !remotePrefixReady.get() && retries < 100) {
            sendHello()
            if (retries % 20 == 0) {
                Log.d(TAG, "HELLO sent #$retries (waiting for response...)")
            }
            Thread.sleep(50)
            retries++
        }

        if (remotePrefixReady.get()) {
            Log.d(TAG, "HELLO handshake completed after $retries retries")
            Log.d(TAG, "Remote: ${remoteWidth}x${remoteHeight}@${remoteFps}")
            listener.onConnected(remoteWidth, remoteHeight, remoteFps)
        } else if (running.get()) {
            Log.e(TAG, "HELLO handshake timed out after $retries retries (${retries * 50}ms)")
            listener.onCallError("Connection timed out (no response from peer)")
            endCall()
        }
    }

    private fun sendHello() {
        val im = identityManager
        val hasIdentity = im != null && im.hasIdentity()

        var flags: Byte = (Common.HELLO_FLAG_VIDEO.toInt() or Common.HELLO_FLAG_AUDIO.toInt()).toByte()
        if (hasIdentity) flags = (flags.toInt() or Common.HELLO_FLAG_IDENTITY.toInt()).toByte()

        // Base HELLO: [0x7F][prefix(4)][flags(1)][width(2 BE)][height(2 BE)][fps(1)] = 11 bytes
        val baseSize = 11
        val identitySize = if (hasIdentity) Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES else 0
        val totalSize = baseSize + identitySize

        val buf = ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(Common.PKT_VER_HELLO)
            put(localNoncePrefix)
            put(flags)
            putShort(sendWidth.toShort())
            putShort(sendHeight.toShort())
            put(quality.fps.toByte())
        }

        if (hasIdentity && im != null) {
            val pk = im.getPublicKey()
            if (pk == null) {
                Log.w(TAG, "Identity getPublicKey() returned null, sending without identity")
                return sendHelloWithoutIdentity()
            }
            buf.put(pk)
            // Sign the hello bytes so far
            val helloBytes = buf.array().copyOfRange(0, baseSize + Common.IDENTITY_PK_BYTES)
            val sig = im.sign(helloBytes)
            if (sig != null) {
                buf.put(sig)
            } else {
                Log.w(TAG, "Identity sign failed, sending without identity")
                return sendHelloWithoutIdentity()
            }
        }

        sendUdp(buf.array())
    }

    private fun sendHelloWithoutIdentity() {
        val flags = (Common.HELLO_FLAG_VIDEO.toInt() or Common.HELLO_FLAG_AUDIO.toInt()).toByte()
        val buf = ByteBuffer.allocate(11).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(Common.PKT_VER_HELLO)
            put(localNoncePrefix)
            put(flags)
            putShort(sendWidth.toShort())
            putShort(sendHeight.toShort())
            put(quality.fps.toByte())
        }
        sendUdp(buf.array())
    }

    // --- Receive loop ---

    private fun receiveLoop() {
        val recvBuf = ByteArray(Common.AUDIO_UDP_RECV_BUFSIZE)
        Log.d(TAG, "Receive loop started on port ${udpSocket?.localPort}")

        while (running.get()) {
            try {
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                udpSocket?.receive(packet) ?: break

                val data = recvBuf.copyOfRange(0, packet.length)
                if (data.isEmpty()) continue

                val pktType = data[0].toInt() and 0xFF

                when (data[0]) {
                    Common.PKT_VER_HELLO -> {
                        Log.d(TAG, "Received HELLO (${data.size} bytes) from ${packet.address?.hostAddress}:${packet.port}")
                        handleHello(data)
                    }
                    Common.PKT_VER_AUDIO -> handleAudioPacket(data)
                    Common.PKT_TYPE_VIDEO_FRAG -> {
                        videoPacketsReceived++
                        handleVideoFragment(data)
                    }
                    Common.PKT_TYPE_STATS -> handleStatsPacket(data)
                    else -> Log.w(TAG, "Unknown packet type: 0x${"%02x".format(pktType)} (${data.size} bytes)")
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout, continue
                cleanupTimedOutFrames()
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Receive error", e)
                    listener.onCallError("Receive error: ${e.message}")
                }
                break
            }
        }

        Log.d(TAG, "Receive loop ended")
    }

    private fun handleHello(data: ByteArray) {
        if (data.size < 5) {
            Log.w(TAG, "HELLO too short: ${data.size} bytes")
            return
        }

        val prefix = data.copyOfRange(1, 5)
        Log.d(TAG, "HELLO remote prefix: ${prefix.toHex()}")

        // Check if this is a new session (different prefix)
        val existingPrefix = remoteNoncePrefix
        if (existingPrefix != null && !existingPrefix.contentEquals(prefix)) {
            Log.d(TAG, "Peer reconnected (new prefix), resetting state")
            pendingFrames.clear()
            vp8Decoder?.stop()
            peerVerified = false
        }

        remoteNoncePrefix = prefix
        remotePrefixReady.set(true)

        // Parse video HELLO
        if (data.size >= 11) {
            val flags = data[5]
            val bb = ByteBuffer.wrap(data, 6, 5).order(ByteOrder.BIG_ENDIAN)
            remoteWidth = bb.short.toInt() and 0xFFFF
            remoteHeight = bb.short.toInt() and 0xFFFF
            remoteFps = bb.get().toInt() and 0xFF

            Log.d(TAG, "Peer video: ${remoteWidth}x${remoteHeight}@${remoteFps} flags=0x${"%02x".format(flags)}")

            // Handle identity if present
            if ((flags.toInt() and Common.HELLO_FLAG_IDENTITY.toInt()) != 0 && !peerVerified) {
                val sigPrefixOffset = 11
                if (data.size >= sigPrefixOffset + Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES) {
                    val pk = data.copyOfRange(sigPrefixOffset, sigPrefixOffset + Common.IDENTITY_PK_BYTES)
                    val sig = data.copyOfRange(
                        sigPrefixOffset + Common.IDENTITY_PK_BYTES,
                        sigPrefixOffset + Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES)
                    val signedData = data.copyOfRange(0, sigPrefixOffset + Common.IDENTITY_PK_BYTES)

                    val im = identityManager
                    if (im != null && im.verify(signedData, sig, pk)) {
                        peerVerified = true
                        Log.d(TAG, "Peer identity verified: ${im.fingerprint(pk)}")
                    } else {
                        Log.w(TAG, "Peer identity verification FAILED")
                    }
                }
            }
        } else {
            Log.d(TAG, "Audio-only HELLO (${data.size} bytes)")
        }
    }

    private fun handleAudioPacket(data: ByteArray) {
        if (data.size < 1 + 8 + Common.AUDIO_AEAD_ABYTES) return

        val aKey = audioKey ?: return
        val rPrefix = remoteNoncePrefix ?: return

        val bb = ByteBuffer.wrap(data, 1, 8).order(ByteOrder.BIG_ENDIAN)
        val seq = bb.long

        val nonce = buildNonce(rPrefix, seq)
        val ciphertext = data.copyOfRange(9, data.size)
        val opusData = aesGcmDecrypt(ciphertext, aKey, nonce) ?: return

        // Decode Opus -> PCM
        val decoder = opusDecoder ?: return
        val pcmSamples = try {
            decoder.decode(opusData, Common.AUDIO_FRAME_SAMPLES)
        } catch (e: Exception) {
            return
        }
        if (pcmSamples.isEmpty()) return

        // Convert shorts to bytes (little-endian PCM16)
        val audioData = ByteArray(pcmSamples.size * 2)
        for (i in pcmSamples.indices) {
            val s = pcmSamples[i].toInt()
            audioData[i * 2] = (s and 0xFF).toByte()
            audioData[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        // Write to AudioTrack
        try {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED
                && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.write(audioData, 0, audioData.size)
            }
        } catch (_: Exception) {}
    }

    private fun handleVideoFragment(data: ByteArray) {
        if (data.size < 1 + 8 + Common.FRAG_HEADER_SIZE + Common.AUDIO_AEAD_ABYTES) return

        val vKey = videoKey ?: return
        val rPrefix = remoteNoncePrefix ?: return

        val bb = ByteBuffer.wrap(data, 1, 8).order(ByteOrder.BIG_ENDIAN)
        val seq = bb.long

        val nonce = buildNonce(rPrefix, seq)
        val ciphertext = data.copyOfRange(9, data.size)
        val decrypted = aesGcmDecrypt(ciphertext, vKey, nonce)
        if (decrypted == null) {
            if (videoPacketsReceived <= 5) {
                Log.w(TAG, "Video decrypt FAILED seq=$seq cipherLen=${ciphertext.size}")
            }
            return
        }

        if (decrypted.size < Common.FRAG_HEADER_SIZE) return

        // Parse fragment header (all Big-Endian)
        val hdr = ByteBuffer.wrap(decrypted, 0, Common.FRAG_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val frameId = hdr.int.toLong() and 0xFFFFFFFFL
        val fragIndex = hdr.short.toInt() and 0xFFFF
        val totalFrags = hdr.short.toInt() and 0xFFFF
        val fragSize = hdr.short.toInt() and 0xFFFF
        // reserved 2 bytes

        if (fragIndex >= totalFrags || totalFrags > Common.FRAG_MAX_PER_FRAME || totalFrags == 0) return

        val payload = decrypted.copyOfRange(Common.FRAG_HEADER_SIZE,
            Common.FRAG_HEADER_SIZE + minOf(fragSize, decrypted.size - Common.FRAG_HEADER_SIZE))

        // Reassemble
        val frame = pendingFrames.getOrPut(frameId) {
            PendingFrame(
                frameId = frameId,
                totalFrags = totalFrags,
                data = ByteArray(totalFrags * Common.FRAG_MAX_PAYLOAD),
                received = BooleanArray(totalFrags)
            )
        }

        if (frame.totalFrags != totalFrags) return // Mismatch
        if (frame.received[fragIndex]) return // Duplicate

        val offset = fragIndex * Common.FRAG_MAX_PAYLOAD
        System.arraycopy(payload, 0, frame.data, offset, payload.size)
        frame.received[fragIndex] = true
        frame.receivedCount++

        if (frame.receivedCount == totalFrags) {
            // Complete frame — calculate actual size
            val totalSize = (totalFrags - 1) * Common.FRAG_MAX_PAYLOAD + payload.size
            val completeFrame = frame.data.copyOfRange(0, totalSize)
            pendingFrames.remove(frameId)

            if (frameId <= 3) {
                Log.d(TAG, "Complete VP8 frame #$frameId: $totalSize bytes ($totalFrags frags)")
            }

            // Decode VP8
            vp8Decoder?.decode(completeFrame, System.nanoTime() / 1000)
        }
    }

    private fun handleStatsPacket(data: ByteArray) {
        if (data.size < 1 + 8 + 16 + Common.AUDIO_AEAD_ABYTES) return

        val vKey = videoKey ?: return
        val rPrefix = remoteNoncePrefix ?: return

        val bb = ByteBuffer.wrap(data, 1, 8).order(ByteOrder.BIG_ENDIAN)
        val seq = bb.long
        val nonce = buildNonce(rPrefix, seq)
        val ciphertext = data.copyOfRange(9, data.size)
        val decrypted = aesGcmDecrypt(ciphertext, vKey, nonce) ?: return

        if (decrypted.size >= 12) {
            val stats = ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN)
            val received = stats.int
            val lost = stats.int
            val rtt = stats.int
            listener.onStatsUpdated(received, lost, rtt)
        }
    }

    // --- Fragment and send video ---

    private fun sendFragmentedFrame(vp8Frame: ByteArray, vKey: ByteArray) {
        val totalFrags = (vp8Frame.size + Common.FRAG_MAX_PAYLOAD - 1) / Common.FRAG_MAX_PAYLOAD
        if (totalFrags > Common.FRAG_MAX_PER_FRAME) return

        val frameId = frameIdCounter++

        for (i in 0 until totalFrags) {
            val offset = i * Common.FRAG_MAX_PAYLOAD
            val fragSize = minOf(Common.FRAG_MAX_PAYLOAD, vp8Frame.size - offset)
            val fragData = vp8Frame.copyOfRange(offset, offset + fragSize)

            // Build fragment header (12 bytes, Big-Endian)
            val header = ByteBuffer.allocate(Common.FRAG_HEADER_SIZE).apply {
                order(ByteOrder.BIG_ENDIAN)
                putInt(frameId.toInt())
                putShort(i.toShort())
                putShort(totalFrags.toShort())
                putShort(fragSize.toShort())
                putShort(0) // reserved
            }.array()

            // Concat header + payload
            val plaintext = ByteArray(header.size + fragData.size)
            System.arraycopy(header, 0, plaintext, 0, header.size)
            System.arraycopy(fragData, 0, plaintext, header.size, fragData.size)

            // Encrypt with video key
            val seq = videoSeqTx.getAndIncrement()
            val nonce = buildNonce(localNoncePrefix, seq)
            val encrypted = aesGcmEncrypt(plaintext, vKey, nonce)
            if (encrypted == null) {
                if (videoPacketsSent < 3) Log.e(TAG, "Video encrypt failed seq=$seq")
                continue
            }

            // Build packet: [0x02][seq(8 BE)][encrypted]
            val packet = ByteBuffer.allocate(1 + 8 + encrypted.size).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(Common.PKT_TYPE_VIDEO_FRAG)
                putLong(seq)
                put(encrypted)
            }.array()

            sendUdp(packet)
            videoPacketsSent++
        }

        if (frameIdCounter <= 3) {
            Log.d(TAG, "Sent VP8 frame #${frameId}: ${vp8Frame.size} bytes, $totalFrags frags")
        }
    }

    // --- Crypto helpers ---

    private fun buildNonce(prefix: ByteArray, seq: Long): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, 4)
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(seq)
        System.arraycopy(bb.array(), 0, nonce, 4, 8)
        return nonce
    }

    private fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM encrypt failed: ${e.message}")
            null
        }
    }

    private fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null // Normal for bad packets — don't spam logs
        }
    }

    // --- Network ---

    private fun sendUdp(data: ByteArray) {
        try {
            val addr = remoteAddress ?: return
            val packet = DatagramPacket(data, data.size, addr, remotePort)
            udpSocket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "sendUdp failed (${data.size} bytes): ${e.message}")
        }
    }

    private fun cleanupTimedOutFrames() {
        val now = System.currentTimeMillis()
        pendingFrames.entries.removeAll { (_, frame) ->
            now - frame.timestamp > Common.FRAG_TIMEOUT_MS
        }

        // Limit pending frames
        while (pendingFrames.size > Common.FRAG_MAX_PENDING) {
            val oldest = pendingFrames.minByOrNull { it.value.timestamp }?.key
            if (oldest != null) pendingFrames.remove(oldest) else break
        }
    }

    // --- Utility ---

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
