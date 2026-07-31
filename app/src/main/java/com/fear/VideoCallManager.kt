package com.fear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import com.fear.crypto.Ed25519Ops
import com.fear.crypto.MediaHello
import com.fear.crypto.MediaKeys
import com.fear.crypto.MediaPacket
import com.fear.crypto.SenderTable
import com.fear.crypto.SodiumEd25519
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Video call manager for FEAR messenger.
 * Compatible with desktop video_call.c implementation.
 *
 * Protocol: UDP with AES-256-GCM encryption.
 * Audio (0x01) and Video (0x02) packets on same socket.
 * HELLO2 (0x7E) for handshake; 0x7F is the pre-group HELLO, recognised only
 * so an old peer can be told to update.
 *
 * Keys are per sender: every participant encrypts under keys derived from its
 * own announced salt, so a call can have more than two people in it and two
 * senders can both start their counters at zero.
 */
class VideoCallManager(
    private val context: Context,
    private val listener: VideoCallListener
) {
    companion object {
        private const val TAG = "VCM"

        /** Nothing produces a nonzero key version yet. */
        private const val KEY_VERSION = 0

        private const val CALL_ID_REQUIRED =
            "Cannot start the call: no call id. Pass the 16-byte call_id from " +
            "the call invite to initialize() or setCallId()."
    }

    interface VideoCallListener {
        fun onCallStarted()
        fun onCallEnded()
        fun onCallError(error: String)
        fun onConnected(peerWidth: Int, peerHeight: Int, peerFps: Int)
        fun onRemoteVideoFrame(data: ByteArray, width: Int, height: Int)
        fun onStatsUpdated(packetsReceived: Int, packetsLost: Int, rttMs: Int)
    }

    // Keys. Ours are derived from our own salt, so they need no peer and no
    // caller/callee bit; a peer's are derived from what that peer announced.
    /** K_call. Copied, never aliased: teardown wipes this array. */
    private var callKey = ByteArray(0)

    /** 16-byte call id. Mandatory - every media key is bound to it. */
    private var callId: ByteArray? = null

    /** Drawn once per call, before any thread starts, never re-drawn. */
    private var senderSalt = ByteArray(0)

    /** Our wire tag, one per participant across audio, video and stats. */
    private var ownSid = ByteArray(0)

    /** Send key for the audio counter domain. */
    private var ownAudioKey: ByteArray? = null

    /** Send key for the video counter domain: fragments AND stats. */
    private var ownVideoKey: ByteArray? = null

    /** HELLO2 MAC key, mk_hello_key(K_call, call_id). */
    private var helloKey: ByteArray? = null

    /** Our Ed25519 public key when an identity is loaded, else 32 zero bytes. */
    private var idbind: ByteArray = MediaKeys.UNSIGNED_IDBIND

    /** Per-sender keys and replay windows for the other participants. */
    private var senderTable: SenderTable.Table? = null

    /** Guards senderTable: install runs on the receive thread, count elsewhere. */
    private val tableLock = Any()

    /** Peers already run through TOFU, keyed by their public key. */
    private val seenPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** One line per call, not per packet, when a peer is too old to talk to. */
    private val legacyPeerLogged = AtomicBoolean(false)

    // Sequence counters. Two counter domains, not three: video fragments and
    // stats both draw from videoSeqTx, so they must also share the video key.
    private val audioSeqTx = AtomicLong(0)
    private val videoSeqTx = AtomicLong(0)

    // Socket
    private var udpSocket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort = 0

    // State
    private val isListening = AtomicBoolean(false)
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

    // Fragment reassembly, keyed by (sender slot, frame id): two senders can
    // be on the same frame id at the same moment, and one bucket for both
    // would splice their fragments into one broken frame.
    private val pendingFrames = HashMap<Long, PendingFrame>()
    private var frameIdCounter = 0L

    // Identity
    private var identityManager: IdentityManager? = null
    private var peerVerified = false

    // Relay mode
    private var relayMode = false
    private var relayRoom = ""
    private var relayName = ""

    // TCP relay
    private var tcpSocket: Socket? = null
    private val tcpSendLock = Any()

    // Stats
    private var videoPacketsSent = 0L
    private var videoPacketsReceived = 0L

    // RTT measurement (ping/pong via stats packets)
    @Volatile private var lastPeerPingTs = 0
    @Volatile private var peerPingRecvTime = 0L
    @Volatile private var measuredRttMs = 0
    private var lastStatsSendTime = 0L

    data class PendingFrame(
        val frameId: Long,
        val totalFrags: Int,
        val data: ByteArray,
        val received: BooleanArray,
        var receivedCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Configure one call. Must be called before any thread starts.
     *
     * @param masterKey K_call, 32 bytes; copied here and wiped on teardown
     * @param callId    the 16-byte call id from the call invite. Mandatory:
     *                  without it no media key exists and every start path
     *                  below refuses. Pass null only to keep a call id that
     *                  was supplied by an earlier call to this method.
     */
    fun initialize(masterKey: ByteArray, preset: VideoQualityPreset = VideoQualityPreset.MEDIUM,
                   identityMgr: IdentityManager? = null, callId: ByteArray? = null) {
        val keepId = callId?.copyOf() ?: this.callId?.copyOf()
        clearMediaKeys()

        callKey = masterKey.copyOf()
        this.callId = keepId
        quality = preset
        sendWidth = preset.width
        sendHeight = preset.height
        identityManager = identityMgr

        val ok = deriveCallMaterial()

        Log.d(TAG, "Initialized: quality=${preset.width}x${preset.height}@${preset.fps}")
        // Never log key material: minify is disabled, so these survive into
        // release builds and land in logcat/bugreports.
        Log.d(TAG, "Media keys ready: $ok")
        Log.d(TAG, "Identity: ${if (identityMgr?.hasIdentity() == true) "yes" else "no"}")
    }

    /** Bind this manager to a call id and derive our per-call material. */
    fun setCallId(callId: ByteArray): Boolean {
        this.callId = callId.copyOf()
        return deriveCallMaterial()
    }

    /**
     * Our SID and both send keys come from our own salt, so nothing here
     * needs a peer, a role or a handshake - only K_call and the call id.
     */
    private fun deriveCallMaterial(): Boolean {
        val cid = callId
        if (callKey.size != MediaKeys.KEY_BYTES) {
            Log.e(TAG, "Bad master key size ${callKey.size}: media disabled")
            return false
        }
        if (cid == null || cid.size != MediaKeys.CALLID_BYTES || cid.all { it == 0.toByte() }) {
            Log.e(TAG, "No call id: media stays disabled until one is supplied")
            return false
        }

        val im = identityManager
        val pk = if (im != null && im.hasIdentity()) im.getPublicKey() else null
        idbind = pk ?: MediaKeys.UNSIGNED_IDBIND

        senderSalt = ByteArray(MediaKeys.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        ownSid = MediaKeys.senderId(callKey, cid, senderSalt, idbind)
        ownAudioKey = MediaKeys.deriveSender(
            callKey, MediaKeys.STREAM_AUDIO, KEY_VERSION, cid, senderSalt, idbind)
        ownVideoKey = MediaKeys.deriveSender(
            callKey, MediaKeys.STREAM_VIDEO, KEY_VERSION, cid, senderSalt, idbind)
        helloKey = MediaKeys.helloKey(callKey, cid)
        // ownSalt is handed over so the table refuses our own announcement
        // echoed back at us, which would install our send keys as a peer.
        synchronized(tableLock) { senderTable = SenderTable.Table(callKey, cid, senderSalt) }

        // Safe to restart at zero: the keys are new because the salt is new.
        audioSeqTx.set(0)
        videoSeqTx.set(0)
        legacyPeerLogged.set(false)
        seenPeers.clear()
        Log.d(TAG, "Media keys ready, sid=${ownSid.toHex()}")
        return true
    }

    /** True once a call id and K_call have produced our keys. */
    private fun mediaReady(): Boolean =
        ownAudioKey != null && ownVideoKey != null && helloKey != null &&
            senderTable != null && ownSid.size == MediaKeys.SID_BYTES

    private fun peerCount(): Int = synchronized(tableLock) { senderTable?.count() ?: 0 }

    /**
     * Wipe every key, salt and the master key. sodium_memzero has no Kotlin
     * equivalent; Arrays.fill is what a JVM offers.
     */
    private fun clearMediaKeys() {
        synchronized(tableLock) {
            senderTable?.let { t ->
                for (i in 0 until SenderTable.MAX_SLOTS) {
                    val slot = t.slotAt(i) ?: continue
                    for (k in slot.keys) java.util.Arrays.fill(k, 0)
                }
            }
            senderTable = null
        }
        ownAudioKey?.let { java.util.Arrays.fill(it, 0) }
        ownAudioKey = null
        ownVideoKey?.let { java.util.Arrays.fill(it, 0) }
        ownVideoKey = null
        helloKey?.let { java.util.Arrays.fill(it, 0) }
        helloKey = null
        java.util.Arrays.fill(senderSalt, 0)
        senderSalt = ByteArray(0)
        java.util.Arrays.fill(callKey, 0)
        callKey = ByteArray(0)
        ownSid = ByteArray(0)
        idbind = MediaKeys.UNSIGNED_IDBIND
        // Cleared too: the next call needs its own id, and silently reusing
        // this one would weaken the cross-call replay barrier it exists for.
        callId = null
        seenPeers.clear()
    }

    fun getLocalUdpPort(): Int = udpSocket?.localPort ?: 0

    /**
     * Send UDP relay registration packet: [0xFE][2 room_len LE][room][2 name_len LE][name]
     */
    private fun sendRelayRegistration() {
        val roomBytes = relayRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = relayName.toByteArray(Charsets.UTF_8)
        val pkt = ByteBuffer.allocate(1 + 2 + roomBytes.size + 2 + nameBytes.size).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(0xFE.toByte())
            putShort(roomBytes.size.toShort())
            put(roomBytes)
            putShort(nameBytes.size.toShort())
            put(nameBytes)
        }.array()
        sendUdp(pkt)
        Log.d(TAG, "Sent relay registration: room='$relayRoom' name='$relayName'")
    }

    // --- TCP relay helpers ---

    private fun tcpRecvAll(input: java.io.InputStream, buf: ByteArray, len: Int): Boolean {
        var received = 0
        while (received < len) {
            val n = input.read(buf, received, len - received)
            if (n <= 0) return false
            received += n
        }
        return true
    }

    private fun tcpRelayConnect(host: String, port: Int): Boolean {
        return try {
            tcpSocket = Socket(host, port).apply {
                tcpNoDelay = true  // Disable Nagle's algorithm for low-latency media
            }
            Log.d(TAG, "TCP relay connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TCP relay connect failed: ${e.message}")
            false
        }
    }

    private fun tcpRelayRegister(): Boolean {
        val sock = tcpSocket ?: return false
        val roomBytes = relayRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = relayName.toByteArray(Charsets.UTF_8)
        val zeroNonce = ByteArray(Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES)
        val frameSize = 2 + roomBytes.size + 2 + nameBytes.size + 2 + zeroNonce.size + 1 + 4 + 1
        val frame = ByteArray(frameSize)
        var off = 0
        Common.writeUInt16(frame, off, roomBytes.size); off += 2
        System.arraycopy(roomBytes, 0, frame, off, roomBytes.size); off += roomBytes.size
        Common.writeUInt16(frame, off, nameBytes.size); off += 2
        System.arraycopy(nameBytes, 0, frame, off, nameBytes.size); off += nameBytes.size
        Common.writeUInt16(frame, off, zeroNonce.size); off += 2
        System.arraycopy(zeroNonce, 0, frame, off, zeroNonce.size); off += zeroNonce.size
        frame[off++] = Common.MSG_TYPE_MEDIA_RELAY
        Common.writeUInt32(frame, off, 1L); off += 4
        frame[off] = 0x20.toByte()
        Log.d(TAG, "TCP relay register: room='$relayRoom' name='$relayName'")
        return Common.sendAll(sock, frame)
    }

    private fun tcpRelaySendMedia(data: ByteArray): Boolean {
        val sock = tcpSocket ?: return false
        val roomBytes = relayRoom.toByteArray(Charsets.UTF_8)
        val nameBytes = relayName.toByteArray(Charsets.UTF_8)
        val zeroNonce = ByteArray(Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES)
        val hdrLen = 2 + roomBytes.size + 2 + nameBytes.size + 2 + zeroNonce.size + 1 + 4
        val frame = ByteArray(hdrLen + data.size)
        var off = 0
        Common.writeUInt16(frame, off, roomBytes.size); off += 2
        System.arraycopy(roomBytes, 0, frame, off, roomBytes.size); off += roomBytes.size
        Common.writeUInt16(frame, off, nameBytes.size); off += 2
        System.arraycopy(nameBytes, 0, frame, off, nameBytes.size); off += nameBytes.size
        Common.writeUInt16(frame, off, zeroNonce.size); off += 2
        System.arraycopy(zeroNonce, 0, frame, off, zeroNonce.size); off += zeroNonce.size
        frame[off++] = Common.MSG_TYPE_MEDIA_RELAY
        Common.writeUInt32(frame, off, data.size.toLong()); off += 4
        System.arraycopy(data, 0, frame, off, data.size)
        synchronized(tcpSendLock) {
            return Common.sendAll(sock, frame)
        }
    }

    private fun tcpRelayRecvMedia(): ByteArray? {
        val sock = tcpSocket ?: return null
        val input = sock.getInputStream()
        val hdr2 = ByteArray(2)
        val hdr4 = ByteArray(4)
        val skipBuf = ByteArray(Common.MAX_ROOM)
        val nonceBuf = ByteArray(Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES)
        val typeBuf = ByteArray(1)

        while (running.get()) {
            if (!tcpRecvAll(input, hdr2, 2)) return null
            val roomLen = Common.readUInt16(hdr2, 0)
            if (roomLen > Common.MAX_ROOM) return null
            if (!tcpRecvAll(input, skipBuf, roomLen)) return null

            if (!tcpRecvAll(input, hdr2, 2)) return null
            val nameLen = Common.readUInt16(hdr2, 0)
            if (nameLen > Common.MAX_NAME) return null
            if (!tcpRecvAll(input, skipBuf, nameLen)) return null

            if (!tcpRecvAll(input, hdr2, 2)) return null
            val nonceLen = Common.readUInt16(hdr2, 0)
            if (nonceLen != Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES) return null
            if (!tcpRecvAll(input, nonceBuf, nonceLen)) return null

            if (!tcpRecvAll(input, typeBuf, 1)) return null

            if (!tcpRecvAll(input, hdr4, 4)) return null
            val clen = Common.readUInt32(hdr4, 0).toInt()
            if (clen > Common.MAX_FRAME) return null

            if (typeBuf[0] == Common.MSG_TYPE_MEDIA_RELAY) {
                val payload = ByteArray(clen)
                if (clen > 0 && !tcpRecvAll(input, payload, clen)) return null
                return payload
            }

            // Skip non-media frame payload
            var remaining = clen
            while (remaining > 0) {
                val chunk = minOf(remaining, skipBuf.size)
                if (!tcpRecvAll(input, skipBuf, chunk)) return null
                remaining -= chunk
            }
        }
        return null
    }

    /**
     * Start relay video call through server (TCP media relay).
     */
    fun startRelay(serverIp: String, serverPort: Int, room: String, name: String, localUdpPort: Int = 0) {
        if (running.get()) return

        if (!mediaReady()) {
            listener.onCallError(CALL_ID_REQUIRED)
            return
        }

        relayMode = true
        relayRoom = room
        relayName = name

        Log.d(TAG, "startRelay (TCP): server=$serverIp:$serverPort room=$room name=$name")

        // Run on background thread — Socket() is a blocking network call
        Thread {
            try {
                // Connect TCP to server for media relay
                if (!tcpRelayConnect(serverIp, serverPort)) {
                    listener.onCallError("TCP relay connect failed")
                    return@Thread
                }

                // Register with server
                if (!tcpRelayRegister()) {
                    Log.e(TAG, "TCP relay registration failed")
                    try { tcpSocket?.close() } catch (_: Exception) {}
                    tcpSocket = null
                    listener.onCallError("TCP relay registration failed")
                    return@Thread
                }

                running.set(true)
                initializeAudio()

                // Start HELLO handshake
                helloThread = Thread {
                    try { helloLoop() } catch (e: Exception) {
                        Log.e(TAG, "helloLoop crashed", e)
                        if (running.get()) {
                            listener.onCallError("Handshake failed: ${e.message}")
                            endCall()
                        }
                    }
                }.also { it.start() }

                // Start receive loop
                receiveThread = Thread {
                    try { receiveLoop() } catch (e: Exception) {
                        Log.e(TAG, "receiveLoop crashed", e)
                    }
                }.also { it.start() }

                listener.onCallStarted()
            } catch (e: Exception) {
                Log.e(TAG, "startRelay failed: ${e.message}")
                listener.onCallError("Relay failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Start video call to remote peer (network only — decoder attached later via attachDecoderSurface).
     */
    fun startCall(remoteIp: String, remoteUdpPort: Int, localUdpPort: Int = 0) {
        if (running.get()) return

        Log.d(TAG, "startCall: remote=$remoteIp:$remoteUdpPort, localPort=$localUdpPort")

        if (!mediaReady()) {
            listener.onCallError(CALL_ID_REQUIRED)
            return
        }

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
     * Start listening for incoming video call on local port.
     * Remote address will be set when first HELLO arrives.
     */
    fun startListen(localUdpPort: Int) {
        if (running.get()) return

        val bindPort = if (localUdpPort > 0) localUdpPort else 50000
        Log.d(TAG, "startListen: port=$bindPort")

        if (!mediaReady()) {
            listener.onCallError(CALL_ID_REQUIRED)
            return
        }

        // Don't set remote address — will be learned from incoming HELLO
        remoteAddress = null
        remotePort = 0
        isListening.set(true)

        try {
            udpSocket = DatagramSocket(bindPort)
            udpSocket?.soTimeout = 100
            Log.d(TAG, "Listening on port ${udpSocket?.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to port $bindPort", e)
            listener.onCallError("Cannot bind to port $bindPort: ${e.message}")
            isListening.set(false)
            return
        }

        running.set(true)

        initializeAudio()

        // Start receive loop (will extract sender from first HELLO)
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
        // No readiness gate: our send keys exist as soon as the call does.
        if (!running.get()) return

        val encoder = vp8Encoder ?: return
        val vKey = ownVideoKey ?: return

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

        // Send stats every 2 seconds
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsSendTime >= 2000L) {
            lastStatsSendTime = now
            sendStatsPacket(vKey)
        }
    }

    /**
     * Feed Opus-encoded audio data for sending.
     */
    fun sendAudioPacket(opusData: ByteArray) {
        if (!running.get()) return
        val aKey = ownAudioKey ?: return

        // Audio has its own counter domain here, unlike audio_call: video
        // fragments and stats share the other one.
        val seq = audioSeqTx.getAndIncrement()
        val packet = MediaPacket.encrypt(
            Common.PKT_VER_AUDIO.toInt(), ownSid, seq, aKey, opusData) ?: return
        sendPacket(packet)
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
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Common.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBuf
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                audioRecord?.release()
                audioRecord = null
            } else {
                val sessionId = audioRecord!!.audioSessionId
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    val aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                    aec?.enabled = true
                    Log.d(TAG, "AcousticEchoCanceler enabled")
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    val ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                    ns?.enabled = true
                    Log.d(TAG, "NoiseSuppressor enabled")
                }
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
        if (!running.compareAndSet(true, false)) return  // Guard against re-entry

        // Close sockets first to unblock any blocking I/O in threads
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}

        // Interrupt threads
        helloThread?.interrupt()
        receiveThread?.interrupt()
        audioSendThread?.interrupt()
        videoSendThread?.interrupt()
        audioRecordThread?.interrupt()

        // Wait for threads to finish (with timeout)
        val threads = listOfNotNull(helloThread, receiveThread, audioSendThread,
                                    videoSendThread, audioRecordThread)
        for (t in threads) {
            try { t.join(500) } catch (_: Exception) {}
        }
        helloThread = null
        receiveThread = null
        audioSendThread = null
        videoSendThread = null
        audioRecordThread = null

        // Now safe to release codecs and audio
        try { vp8Encoder?.stop() } catch (_: Exception) {}
        try { vp8Decoder?.stop() } catch (_: Exception) {}
        vp8Encoder = null
        vp8Decoder = null

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

        udpSocket = null
        tcpSocket = null

        pendingFrames.clear()
        isListening.set(false)
        audioSeqTx.set(0)
        videoSeqTx.set(0)
        peerVerified = false
        relayMode = false
        relayRoom = ""
        relayName = ""

        // Last, once every thread that could touch a key is gone.
        clearMediaKeys()

        Log.d(TAG, "Call ended. Sent=$videoPacketsSent Received=$videoPacketsReceived")
        videoPacketsSent = 0
        videoPacketsReceived = 0

        listener.onCallEnded()
    }

    // --- HELLO handshake ---

    private fun helloLoop() {
        var retries = 0
        Log.d(TAG, "HELLO loop started, sending to ${remoteAddress?.hostAddress}:$remotePort")

        // Announce until somebody is known, then stop: a peer that joins
        // later is answered by the one reply in handleHello2. Nothing waits
        // on this loop to start sending media.
        while (running.get() && peerCount() == 0 && retries < 100) {
            sendHello()
            if (retries % 20 == 0) {
                Log.d(TAG, "HELLO sent #$retries (waiting for a peer...)")
            }
            Thread.sleep(50)
            retries++
        }

        if (peerCount() > 0) {
            Log.d(TAG, "HELLO handshake completed after $retries retries")
            Log.d(TAG, "Remote: ${remoteWidth}x${remoteHeight}@${remoteFps}")
            listener.onConnected(remoteWidth, remoteHeight, remoteFps)
        } else if (running.get()) {
            Log.e(TAG, "HELLO handshake timed out after $retries retries (${retries * 50}ms)")
            listener.onCallError("Connection timed out (no response from peer)")
            endCall()
        }
    }

    /**
     * HELLO2, type 0x7E. This binary sends audio and video, so both flags are
     * set and the geometry fields are meaningful; IDENTITY is added when an
     * identity is loaded.
     */
    private fun sendHello() {
        val hk = helloKey ?: return
        val cid = callId ?: return
        if (senderSalt.size != MediaKeys.SALT_BYTES) return

        val im = identityManager
        val pk = if (im != null && im.hasIdentity()) im.getPublicKey() else null
        var flags = MediaHello.FLAG_VIDEO or MediaHello.FLAG_AUDIO
        if (pk != null) flags = flags or MediaHello.FLAG_IDENTITY

        // IdentityManager never hands out identity_sk, so this array is a
        // placeholder: build() reads only bytes 32..63 from it, and those are
        // the public key. The signature comes back from IdentityManager.
        val skPlaceholder = if (pk != null) ByteArray(64).also { pk.copyInto(it, 32) } else null

        val packet = try {
            MediaHello.build(
                MediaHello.Hello(
                    flags = flags,
                    keyVersion = KEY_VERSION,
                    callId = cid,
                    senderSalt = senderSalt,
                    width = sendWidth,
                    height = sendHeight,
                    fps = quality.fps,
                ),
                hk,
                skPlaceholder,
                signer = identityOps(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "HELLO2 build failed: ${e.message}")
            return
        }

        sendPacket(packet)
    }

    /**
     * Signing and verification for HELLO2, delegated to IdentityManager when
     * there is one so identity_sk never leaves it.
     */
    private fun identityOps(): Ed25519Ops {
        val im = identityManager ?: return SodiumEd25519
        return object : Ed25519Ops {
            override fun sign(msg: ByteArray, sk: ByteArray): ByteArray =
                im.sign(msg) ?: ByteArray(0)

            override fun verify(msg: ByteArray, sig: ByteArray, pk: ByteArray): Boolean =
                im.verify(msg, sig, pk)
        }
    }

    // --- Receive loop ---

    private fun receiveLoop() {
        val recvBuf = ByteArray(Common.AUDIO_UDP_RECV_BUFSIZE)
        Log.d(TAG, "Receive loop started" + if (relayMode) " (TCP relay)" else " on port ${udpSocket?.localPort}")

        while (running.get()) {
            try {
                val data: ByteArray

                if (relayMode && tcpSocket != null) {
                    // TCP relay path
                    val received = tcpRelayRecvMedia()
                    if (received == null) {
                        Log.e(TAG, "[relay] TCP connection lost")
                        running.set(false)
                        break
                    }
                    data = received
                } else {
                    // UDP path
                    val packet = DatagramPacket(recvBuf, recvBuf.size)
                    udpSocket?.receive(packet) ?: break

                    data = recvBuf.copyOfRange(0, packet.length)

                    // Listen mode: learn remote address from first packet
                    if (isListening.get() && remoteAddress == null && packet.address != null) {
                        remoteAddress = packet.address
                        remotePort = packet.port
                        isListening.set(false)
                        Log.d(TAG, "Listen mode - peer connected from ${packet.address.hostAddress}:${packet.port}")

                        helloThread = Thread {
                            try { helloLoop() } catch (e: Exception) {
                                Log.e(TAG, "helloLoop crashed", e)
                            }
                        }.also { it.start() }
                    }
                }

                if (data.isEmpty()) continue

                // HELLO2 (0x7E) or a pre-group HELLO (0x7F). Everything else
                // is media, and its type byte is not trusted until the packet
                // decrypts under some sender's key.
                if (data[0] == MediaHello.TYPE || data[0] == MediaHello.LEGACY_TYPE) {
                    handleMediaHello(data)
                } else {
                    handleMediaPacket(data)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout, continue (UDP only)
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

    /**
     * A verified HELLO2 installs its sender. The table decides what is new:
     * an identical announcement is a no-op by construction, so a repeated
     * HELLO cannot reset a replay window or swap a key out from under us.
     */
    private fun handleMediaHello(data: ByteArray) {
        val hk = helloKey ?: return
        val cid = callId ?: return
        val table = senderTable ?: return

        val res = MediaHello.parse(data, data.size, hk, signer = identityOps())
        if (res.status != MediaHello.Status.OK) {
            if (res.status == MediaHello.Status.ERR_LEGACY_PEER &&
                legacyPeerLogged.compareAndSet(false, true)) {
                // Once per call: an old peer will keep sending these.
                Log.w(TAG, "Peer runs a pre-group build")
                listener.onCallError("Peer runs a pre-group F.E.A.R. build and must be updated")
            } else {
                Log.d(TAG, "HELLO2 rejected: ${res.status}")
            }
            // Never answer a HELLO that failed to parse: replying would tell
            // an off-path prober which guesses are worth repeating.
            return
        }

        val hello = res.hello ?: return
        // The MAC key is derived from our own call id, so a foreign call id
        // cannot reach this point. Checked anyway - it is one comparison.
        if (!hello.callId.contentEquals(cid)) return

        val peerIdbind = hello.pk ?: MediaKeys.UNSIGNED_IDBIND
        val status: SenderTable.Status
        val isNew: Boolean
        synchronized(tableLock) {
            val before = table.count()
            status = table.install(hello.senderSalt, peerIdbind, hello.keyVersion).first
            isNew = status == SenderTable.Status.OK && table.count() > before
        }

        if (status != SenderTable.Status.OK) {
            Log.w(TAG, "HELLO2 not installed: $status")
            return
        }

        if (hello.flags and MediaHello.FLAG_VIDEO != 0) {
            remoteWidth = hello.width
            remoteHeight = hello.height
            remoteFps = hello.fps
            Log.d(TAG, "Peer video: ${remoteWidth}x${remoteHeight}@${remoteFps}")
        }

        val pk = hello.pk
        if (pk != null) {
            // parse() already verified the signature over the announcement.
            peerVerified = true
            notePeerIdentity(pk)
        }

        if (isNew) {
            // Exactly one reply, so the new peer learns us. A repeat installs
            // nothing and gets no answer, which is what stops two peers from
            // echoing HELLOs at each other forever. Nothing is reset here
            // either: a re-announcement must not drop anyone's frames or
            // rewind anyone's replay window.
            Log.d(TAG, "New sender installed (${peerCount()} peers), replying with one HELLO2")
            sendHello()
        }
    }

    /**
     * TOFU keyed by the peer's public key. Keying it by a display name (or by
     * a fixed string) would let one participant's record cover another's as
     * soon as a call has more than two people in it.
     */
    private fun notePeerIdentity(pk: ByteArray) {
        val im = identityManager ?: return
        val label = im.fpshort(pk)
        if (!seenPeers.add(label)) return
        Log.d(TAG, "Peer identity ${im.fingerprint(pk)} is ${im.checkPeerKey(label, pk)}")
    }

    /**
     * One media packet: find the sender by SID, decrypt under that sender's
     * key for this counter domain, then - and only then - offer the counter
     * to that sender's replay window.
     */
    private fun handleMediaPacket(data: ByteArray) {
        val table = senderTable ?: return

        // The SID chooses the key. Nothing here is trusted yet, and peek() is
        // where a runt packet is turned away.
        val parsed = MediaPacket.peek(data) ?: return
        val type = parsed.type

        // Counter domain, not media type: audio has its own counter, while
        // video fragments and stats share the video counter and therefore the
        // video key. Getting this wrong is an instant nonce collision.
        val stream = when (type) {
            Common.PKT_VER_AUDIO.toInt() -> MediaKeys.STREAM_AUDIO
            Common.PKT_TYPE_VIDEO_FRAG.toInt(),
            Common.PKT_TYPE_STATS.toInt() -> MediaKeys.STREAM_VIDEO
            else -> return
        }

        val sid = parsed.sid
        val counter = parsed.counter

        // A 3-byte tag collides for real, so there may be two candidates.
        val candidates = synchronized(tableLock) { table.findBySid(sid) }
        for (idx in candidates) {
            val key = synchronized(tableLock) { table.key(idx, stream) } ?: continue
            val plain = MediaPacket.decrypt(data, data.size, key) ?: continue

            // Authenticated only now, so only now may the window move: a
            // forged counter that advanced it would silence the real sender.
            val verdict = synchronized(tableLock) { table.acceptSeq(idx, stream, counter) }
            if (verdict != SenderTable.Verdict.FRESH) {
                Log.d(TAG, "Dropped packet from slot $idx, counter=$counter ($verdict)")
                return
            }

            // The type byte is covered by the tag, so routing on it cannot be
            // steered by flipping a cleartext byte in flight.
            when (type) {
                Common.PKT_VER_AUDIO.toInt() -> handleAudioPayload(plain)
                Common.PKT_TYPE_VIDEO_FRAG.toInt() -> {
                    videoPacketsReceived++
                    handleVideoFragment(idx, plain)
                }
                Common.PKT_TYPE_STATS.toInt() -> handleStatsPayload(plain)
            }
            return
        }
        // Nobody installed opened it: an unknown sender, or noise.
    }

    private fun handleAudioPayload(opusData: ByteArray) {
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

    /** @param slot the sender this fragment authenticated as */
    private fun handleVideoFragment(slot: Int, decrypted: ByteArray) {
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
        // The reassembly buffer is sized at FRAG_MAX_PAYLOAD per fragment, so
        // an oversized one would run off the end of it.
        if (payload.size > Common.FRAG_MAX_PAYLOAD) return

        // One reassembly bucket per sender: frame ids are per sender and two
        // of them will collide as soon as there are three participants.
        val bucket = (slot.toLong() shl 32) or frameId

        // Reassemble
        val frame = pendingFrames.getOrPut(bucket) {
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
            pendingFrames.remove(bucket)

            if (frameId <= 3) {
                Log.d(TAG, "Complete VP8 frame #$frameId from slot $slot: $totalSize bytes ($totalFrags frags)")
            }

            // Decode VP8
            vp8Decoder?.decode(completeFrame, System.nanoTime() / 1000)
        }
    }

    private fun handleStatsPayload(decrypted: ByteArray) {
        if (decrypted.size >= 16) {
            val stats = ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN)
            val received = stats.int
            val lost = stats.int
            val pongTs = stats.int   // echo of our last ping timestamp
            val pingTs = stats.int   // peer's current timestamp (reserved field)

            // RTT: pongTs echoes our ping + hold time
            if (pongTs != 0) {
                val now32 = (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt()
                measuredRttMs = now32 - pongTs
            }
            lastPeerPingTs = pingTs
            peerPingRecvTime = SystemClock.elapsedRealtime()

            listener.onStatsUpdated(received, lost, measuredRttMs)
        }
    }

    private fun sendStatsPacket(vKey: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val now32 = (now and 0xFFFFFFFFL).toInt()
        // Hold time compensation: add time we held the peer's ping
        val holdTime = if (peerPingRecvTime > 0) (now - peerPingRecvTime).toInt() else 0
        val pongValue = lastPeerPingTs + holdTime
        // StatsPayload: packets_received(4) + packets_lost(4) + rtt_ms/pong(4) + reserved/ping(4)
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(videoPacketsReceived.toInt())  // packets_received
            putInt(0)                              // packets_lost (not tracked)
            putInt(pongValue)                      // rtt_ms field = echo peer's ping + hold time
            putInt(now32)                          // reserved field = our ping timestamp
        }.array()

        // Stats ride the video counter, so they ride the video key with it.
        val seq = videoSeqTx.getAndIncrement()
        val packet = MediaPacket.encrypt(
            Common.PKT_TYPE_STATS.toInt(), ownSid, seq, vKey, payload) ?: return
        sendPacket(packet)
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

            // Encrypt under our own video send key
            val seq = videoSeqTx.getAndIncrement()
            val packet = MediaPacket.encrypt(
                Common.PKT_TYPE_VIDEO_FRAG.toInt(), ownSid, seq, vKey, plaintext)
            if (packet == null) {
                if (videoPacketsSent < 3) Log.e(TAG, "Video encrypt failed seq=$seq")
                continue
            }

            sendPacket(packet)
            videoPacketsSent++
        }

        if (frameIdCounter <= 3) {
            Log.d(TAG, "Sent VP8 frame #${frameId}: ${vp8Frame.size} bytes, $totalFrags frags")
        }
    }

    // The framing - [type(1)][SID(3)][counter(5)] then AES-256-GCM with those
    // nine bytes as AAD - lives in com.fear.crypto.MediaPacket. It used to be
    // hand-copied into this file and into AudioCallManager, which is how two
    // ports of one wire format drift apart; MediaPacket is pinned by the same
    // frozen vectors as the C side.

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

    private fun sendPacket(data: ByteArray) {
        if (relayMode && tcpSocket != null) {
            tcpRelaySendMedia(data)
        } else {
            sendUdp(data)
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
