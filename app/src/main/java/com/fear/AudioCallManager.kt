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
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Импортируем константы
//import com.fear.AudioConstants.*
import com.fear.AudioConstants.AUDIO_PCM_BYTES_PER_FRAME
import com.fear.AudioConstants.AUDIO_MAX_OPUS_BYTES
import com.fear.AudioConstants.AUDIO_UDP_RECV_BUFSIZE
import com.fear.AudioConstants.PKT_VER_AUDIO
import com.fear.AudioConstants.PKT_VER_STATS
import com.fear.crypto.Ed25519Ops
import com.fear.crypto.MediaHello
import com.fear.crypto.MediaKeys
import com.fear.crypto.MediaPacket
import com.fear.crypto.SenderTable
import com.fear.crypto.SodiumEd25519
import java.security.SecureRandom
import android.os.SystemClock
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
        fun onStatsUpdated(rttMs: Int) {}
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

    /**
     * Transmit counter for the audio counter domain. Audio packets and stats
     * packets both draw from it, so they must also share one key - one
     * counter under two keys is harmless, one key under two counters repeats
     * a (key, nonce) pair.
     */
    private val seqTx = AtomicLong(0)

    /** K_call. Copied, never aliased: teardown wipes this array. */
    private var roomKey = ByteArray(0)

    /** 16-byte call id. Mandatory - every media key is bound to it. */
    private var callId: ByteArray? = null

    /** Drawn once per call, before any thread starts, never re-drawn. */
    private var senderSalt = ByteArray(0)

    /** Our wire tag, derived from the salt we announce. */
    private var ownSid = ByteArray(0)

    /** Our send key for the audio counter domain (audio and stats). */
    private var ownAudioKey: ByteArray? = null

    /** HELLO2 MAC key, mk_hello_key(K_call, call_id). */
    private var helloKey: ByteArray? = null

    /** Per-sender keys and replay windows for everyone else in the call. */
    private var senderTable: SenderTable.Table? = null

    /** Guards senderTable: install runs on the receive thread, count on others. */
    private val tableLock = Any()

    /** Our Ed25519 public key when an identity is loaded, else 32 zero bytes. */
    private var idbind: ByteArray = MediaKeys.UNSIGNED_IDBIND

    private var identityManager: IdentityManager? = null

    /** Peers we have already run through TOFU, keyed by their public key. */
    private val seenPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** One line per call, not per packet, when a peer is too old to talk to. */
    private val legacyPeerLogged = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private val audioBuffer = ByteArray(AUDIO_PCM_BYTES_PER_FRAME)
    private val opusBuffer = ByteArray(AUDIO_MAX_OPUS_BYTES)
    private val packetBuffer = ByteArray(AUDIO_UDP_RECV_BUFSIZE)

    // Listen mode: waiting for incoming connection
    private val isListening = AtomicBoolean(false)

    // Socket lock for thread-safe access
    private val socketLock = Any()

    // Wake locks to keep call alive when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Relay mode
    private var relayMode = false
    private var relayRoom = ""
    private var relayName = ""

    // TCP relay
    private var tcpSocket: Socket? = null
    private val tcpSendLock = Any()

    // Opus codec. One encoder, because we have one voice. No single
    // decoder: every rendered participant gets its own, in the pool below.
    private var opusEncoder: OpusCodec.Encoder? = null

    /**
     * One rendered participant: its decoder, its jitter buffer, and enough
     * bookkeeping to decide who gives up a decoder when a new voice arrives.
     */
    private class MixSlot {
        /** sender-table slot index, or -1 when this entry is free */
        var slot: Int = -1
        var decoder: OpusCodec.Decoder? = null
        /** decoded PCM frames waiting for play-out, oldest first */
        val ring: ArrayDeque<ShortArray> = ArrayDeque()
        /** when we last decoded a frame from this participant */
        var lastMs: Long = 0
        /** the jitter buffer has reached the play-out depth */
        var prefilled: Boolean = false
        /** frames actually mixed, for the teardown report */
        var frames: Long = 0
    }

    /**
     * One decoder and one jitter buffer per rendered participant.
     *
     * A single decoder cannot serve several senders: Opus carries state across
     * frames, so interleaving two streams through one decoder makes both
     * unintelligible - and a single AudioTrack write path would have them
     * overwrite each other rather than mix. That is why the crypto working for
     * N participants is not the same as the call working for N: this is the
     * other half, and it is the half that was missing here.
     */
    private val mix = Array(MAX_MIX) { MixSlot() }

    /** Guards mix[]: the receive path fills the rings, play-out drains them. */
    private val mixLock = Any()

    /**
     * Packets successfully decrypted from each sender-table slot. Only the
     * teardown report uses it, and that report is what tells "installed a
     * peer" apart from "actually heard that peer".
     */
    private val rxCount = LongArray(SenderTable.MAX_SLOTS)

    // RTT measurement (ping/pong via stats packets)
    @Volatile private var lastPeerPingTs = 0
    @Volatile private var peerPingRecvTime = 0L
    @Volatile private var measuredRttMs = 0
    private var lastStatsSendTime = 0L

    /**
     * Configure one call. Must be called before any thread starts.
     *
     * @param roomKey K_call, 32 bytes; copied here and wiped on teardown
     * @param callId  the 16-byte call id from the call invite. Mandatory:
     *                without it no media key can be derived and every start
     *                path below refuses. Pass null only to keep a call id
     *                that was supplied by an earlier call to this method.
     * @param identityMgr signs our HELLO2 and binds our public key into our
     *                keys; when absent the call runs unsigned (32 zero bytes)
     */
    fun initialize(roomKey: ByteArray,
                   callId: ByteArray? = null,
                   identityMgr: IdentityManager? = null) {
        val keepId = callId?.copyOf() ?: this.callId?.copyOf()

        // Whatever the previous call left behind goes before it is overwritten.
        clearMediaKeys()

        this.roomKey = roomKey.copyOf()
        this.callId = keepId
        if (identityMgr != null) this.identityManager = identityMgr

        // Initialize Opus codec (destroy first: initialize may run twice for
        // one manager, and the old native handles would leak). No decoder is
        // built here - one is built per participant, the first time that
        // participant is actually heard.
        try { opusEncoder?.destroy() } catch (_: Exception) {}
        opusEncoder = OpusCodec.createEncoder(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, AC_OPUS_BITRATE)
        mixTeardown()

        deriveCallMaterial()
    }

    /** Bind this manager to a call id and derive our per-call material. */
    fun setCallId(callId: ByteArray): Boolean {
        this.callId = callId.copyOf()
        return deriveCallMaterial()
    }

    /**
     * Our SID and send key come from our own salt, so nothing here needs a
     * peer, a role or a handshake - only K_call and the call id.
     */
    private fun deriveCallMaterial(): Boolean {
        val cid = callId
        if (roomKey.size != MediaKeys.KEY_BYTES) {
            println("ACM_DEBUG: bad room key size ${roomKey.size}, media disabled")
            return false
        }
        if (cid == null || cid.size != MediaKeys.CALLID_BYTES || cid.all { it == 0.toByte() }) {
            println("ACM_DEBUG: no call id, media stays disabled")
            return false
        }

        val im = identityManager
        val pk = if (im != null && im.hasIdentity()) im.getPublicKey() else null
        idbind = pk ?: MediaKeys.UNSIGNED_IDBIND

        senderSalt = ByteArray(MediaKeys.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        ownSid = MediaKeys.senderId(roomKey, cid, senderSalt, idbind)
        ownAudioKey = MediaKeys.deriveSender(
            roomKey, MediaKeys.STREAM_AUDIO, KEY_VERSION, cid, senderSalt, idbind)
        helloKey = MediaKeys.helloKey(roomKey, cid)
        // ownSalt is handed over so the table refuses our own announcement
        // echoed back at us, which would install our send keys as a peer.
        synchronized(tableLock) { senderTable = SenderTable.Table(roomKey, cid, senderSalt) }

        // Safe to restart at zero: the key is new because the salt is new.
        seqTx.set(0)
        java.util.Arrays.fill(rxCount, 0L)
        legacyPeerLogged.set(false)
        seenPeers.clear()
        println("ACM_DEBUG: media keys ready, sid=${ownSid.joinToString("") { "%02x".format(it) }}")
        return true
    }

    /** True once a call id and K_call have produced our keys. */
    private fun mediaReady(): Boolean =
        ownAudioKey != null && helloKey != null && senderTable != null &&
            ownSid.size == MediaKeys.SID_BYTES

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

    /**
     * Vestigial. Nonce prefixes are gone: the nonce is now SID || counter and
     * the SID travels in every packet header, so there is nothing to announce
     * out of band. Kept so the signalling code still compiles; callers should
     * stop sending the field.
     */
    fun getLocalNoncePrefix(): ByteArray = ByteArray(0)

    /** @param remoteNoncePrefix ignored; kept so the signalling code compiles */
    fun startCall(remoteUser: String, remoteHost: String, remoteUdpPort: Int, remoteNoncePrefix: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                this@AudioCallManager.remoteUdpPort = remoteUdpPort
                this@AudioCallManager.remoteAddress = InetAddress.getByName(remoteHost)
                
                startAudioCall(true, remoteUser)
                
            } catch (e: Exception) {
                notifyError("Failed to start call: ${e.message}")
            }
        }
    }

    /** @param remoteNoncePrefix ignored; kept so the signalling code compiles */
    fun acceptCall(remoteUser: String, remoteHost: String, remoteUdpPort: Int, remoteNoncePrefix: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                this@AudioCallManager.remoteUdpPort = remoteUdpPort
                this@AudioCallManager.remoteAddress = InetAddress.getByName(remoteHost)
                
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
                // Never log key material: minify is disabled, so println/Log survive
                // into release builds and land in logcat/bugreports.
                println("ACM_DEBUG: Encryption key set (${encryptionKey.size} bytes)")
                // Update room key if different OR if codecs not initialized
                if (!roomKey.contentEquals(encryptionKey) || opusEncoder == null) {
                    println("ACM_DEBUG: Initializing with new key... (encoder=${opusEncoder != null})")
                    initialize(encryptionKey)
                    println("ACM_DEBUG: Initialization complete (encoder=${opusEncoder != null})")
                } else {
                    println("ACM_DEBUG: Already initialized, skipping")
                }

                if (!mediaReady()) {
                    notifyError(CALL_ID_REQUIRED)
                    return@launch
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
                    udpPort = udpSocket?.localPort ?: 0
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

                // Mandatory now. The receive path only fills per-participant
                // jitter buffers; this loop is what mixes them into one stream
                // and what paces the output device.
                startAudioPlayback()
                println("ACM_DEBUG: Audio playback (mixer) started")

                // Announce ourselves. Nothing waits on an answer any more:
                // our keys come from our own salt, so we can already encrypt.
                startHelloAnnounce()

                notifyCallStarted("$serverIp:$serverPort", true)

            } catch (e: Exception) {
                println("ACM_DEBUG: Exception in startCallDirect: ${e.message}")
                notifyError("Failed to start direct call: ${e.message}")
                e.printStackTrace()
                stopAudioCall()
            }
        }
    }

    fun startListenDirect(localPort: Int, encryptionKey: ByteArray) {
        println("ACM_DEBUG: startListenDirect called, localPort=$localPort")

        CoroutineScope(Dispatchers.IO).launch {
            if (isRunning.get()) {
                println("ACM_DEBUG: Call already running, stopping first...")
                stopAudioCall()
                delay(200)
            }
            try {
                if (encryptionKey.size != 32) {
                    notifyError("Invalid encryption key size")
                    return@launch
                }

                if (!roomKey.contentEquals(encryptionKey) || opusEncoder == null) {
                    initialize(encryptionKey)
                }

                if (!mediaReady()) {
                    notifyError(CALL_ID_REQUIRED)
                    return@launch
                }

                // Listen mode: don't set remote address yet
                remoteAddress = null
                remoteUdpPort = 0
                isListening.set(true)

                // Create UDP socket bound to local port
                val bindPort = if (localPort > 0) localPort else 50000
                udpSocket = try {
                    DatagramSocket(bindPort).apply {
                        broadcast = false
                        reuseAddress = true
                    }
                } catch (e: Exception) {
                    println("ACM_DEBUG: Port $bindPort binding failed: ${e.message}")
                    notifyError("Cannot bind to port $bindPort: ${e.message}")
                    return@launch
                }
                println("ACM_DEBUG: Listening on port ${udpSocket?.localPort}")

                initializeAudio()

                audioCallState = AudioCallState(
                    isInCall = true,
                    isCallActive = true,
                    remoteUser = "Listening on :$bindPort",
                    isInitiator = false,
                    udpPort = udpSocket?.localPort ?: 0
                )

                isRunning.set(true)
                AudioCallService.start(context)
                acquireWakeLocks()

                startUdpReceiving()
                startAudioRecording()
                startAudioPlayback()

                notifyCallStarted("Listening on :$bindPort", false)

            } catch (e: Exception) {
                println("ACM_DEBUG: Exception in startListenDirect: ${e.message}")
                notifyError("Failed to start listening: ${e.message}")
                isListening.set(false)
                stopAudioCall()
            }
        }
    }

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
        sendUdpPacket(pkt)
        println("ACM_DEBUG: Sent relay registration: room='$relayRoom' name='$relayName'")
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
            println("ACM_DEBUG: TCP relay connected to $host:$port")
            true
        } catch (e: Exception) {
            println("ACM_DEBUG: TCP relay connect failed: ${e.message}")
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
        println("ACM_DEBUG: TCP relay register: room='$relayRoom' name='$relayName'")
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

        while (isRunning.get()) {
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

    private fun sendPacket(data: ByteArray) {
        if (relayMode && tcpSocket != null) {
            tcpRelaySendMedia(data)
        } else {
            sendUdpPacket(data)
        }
    }

    /**
     * Start relay audio call through server (TCP media relay).
     */
    fun startRelay(serverIp: String, serverPort: Int, room: String, name: String,
                   localPort: Int, encryptionKey: ByteArray) {
        println("ACM_DEBUG: startRelay (TCP) called: server=$serverIp:$serverPort room=$room name=$name")

        CoroutineScope(Dispatchers.IO).launch {
            if (isRunning.get()) {
                stopAudioCall()
                delay(200)
            }
            try {
                if (encryptionKey.size != 32) {
                    notifyError("Invalid encryption key size")
                    return@launch
                }

                if (!roomKey.contentEquals(encryptionKey) || opusEncoder == null) {
                    initialize(encryptionKey)
                }

                if (!mediaReady()) {
                    notifyError(CALL_ID_REQUIRED)
                    return@launch
                }

                relayMode = true
                relayRoom = room
                relayName = name

                // Connect TCP to server for media relay
                if (!tcpRelayConnect(serverIp, serverPort)) {
                    notifyError("TCP relay connect failed")
                    relayMode = false
                    return@launch
                }

                // Register with server
                if (!tcpRelayRegister()) {
                    println("ACM_DEBUG: TCP relay registration failed")
                    try { tcpSocket?.close() } catch (_: Exception) {}
                    tcpSocket = null
                    notifyError("TCP relay registration failed")
                    relayMode = false
                    return@launch
                }

                initializeAudio()

                audioCallState = AudioCallState(
                    isInCall = true, isCallActive = true,
                    remoteUser = "Relay $serverIp:$serverPort",
                    isInitiator = true,
                    udpPort = 0
                )

                isRunning.set(true)
                AudioCallService.start(context)
                acquireWakeLocks()

                startUdpReceiving()
                startAudioRecording()
                startAudioPlayback()
                startHelloAnnounce()

                notifyCallStarted("Relay $serverIp:$serverPort", true)

            } catch (e: Exception) {
                println("ACM_DEBUG: Exception in startRelay: ${e.message}")
                notifyError("Failed to start relay call: ${e.message}")
                relayMode = false
                stopAudioCall()
            }
        }
    }

    private fun startAudioCall(isInitiator: Boolean, remoteUser: String) {
        if (isRunning.get()) return

        if (!mediaReady()) {
            notifyError(CALL_ID_REQUIRED)
            return
        }

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
                udpPort = udpSocket?.localPort ?: 0
            )

            isRunning.set(true)

            // Start foreground service to keep app alive
            AudioCallService.start(context)

            // Acquire wake locks to keep call alive when screen is off
            acquireWakeLocks()

            // Start threads
            startUdpReceiving()
            startAudioRecording()
            startAudioPlayback()

            // Both sides announce now: there is no caller/callee bit in the
            // key derivation any more, so there is none in the handshake.
            startHelloAnnounce()

            notifyCallStarted(remoteUser, isInitiator)
            
        } catch (e: Exception) {
            notifyError("Audio call setup failed: ${e.message}")
            stopAudioCall()
        }
    }

    private fun stopAudioCall() {
        println("ACM_DEBUG: stopAudioCall called, isRunning=${isRunning.get()}")

        if (!isRunning.compareAndSet(true, false)) {
            println("ACM_DEBUG: Already stopped, returning")
            return
        }

        println("ACM_DEBUG: Closing sockets to unblock I/O...")
        // Close sockets first to unblock blocking I/O in receive threads
        try {
            synchronized(socketLock) {
                udpSocket?.close()
            }
        } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}

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

        // Wait for coroutines to finish
        try {
            Thread.sleep(200)
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

        println("ACM_DEBUG: Cleaning up sockets...")
        // Sockets already closed above to unblock I/O, just null them out
        synchronized(socketLock) { udpSocket = null }
        tcpSocket = null

        println("ACM_DEBUG: Cleaning up Opus codecs...")
        // Clean up Opus encoder
        try {
            opusEncoder?.destroy()
            opusEncoder = null
        } catch (e: Exception) {
            println("ACM_DEBUG: Error destroying opusEncoder: ${e.message}")
        }

        // One line per participant we installed: how many of their packets
        // decrypted, and how many frames of theirs actually reached the
        // speaker. A peer that decrypted but never mixed is exactly the defect
        // this pool exists to remove, and it is invisible from a decrypt count
        // alone. Printed before clearMediaKeys() drops the sender table.
        reportMedia()

        // Then release every per-participant decoder and jitter buffer.
        mixTeardown()

        // Stop foreground service
        try {
            AudioCallService.stop(context)
        } catch (e: Exception) {
            Log.e("ACM_DEBUG", "Failed to stop foreground service: ${e.message}")
        }

        // Release wake locks
        releaseWakeLocks()

        // Reset state
        isListening.set(false)
        relayMode = false
        relayRoom = ""
        relayName = ""
        audioCallState = AudioCallState()

        // Last, once every thread that could touch a key is gone.
        clearMediaKeys()

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

            // Use VOICE_COMMUNICATION to enable platform AEC (echo cancellation)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw Exception("Failed to initialize AudioRecord - state is ${audioRecord?.state}")
            }
            println("ACM_DEBUG: AudioRecord created successfully")

            // Enable AEC and noise suppression
            val sessionId = audioRecord!!.audioSessionId
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                val aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                println("ACM_DEBUG: AcousticEchoCanceler enabled")
            }
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                val ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                ns?.enabled = true
                println("ACM_DEBUG: NoiseSuppressor enabled")
            }

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
            println("ACM_DEBUG: startUdpReceiving - entering loop" + if (relayMode) " (TCP relay)" else "")

            while (isRunning.get()) {
                try {
                    // TCP relay path
                    if (relayMode && tcpSocket != null) {
                        val received = tcpRelayRecvMedia()
                        if (received == null) {
                            println("ACM_DEBUG: [relay] TCP connection lost")
                            break
                        }
                        if (received.isNotEmpty() && isRunning.get()) {
                            try {
                                processAudioPacket(received, received.size)
                            } catch (e: Throwable) {
                                // Silently ignore packet processing errors
                            }
                        }
                        continue
                    }

                    // UDP path
                    val receiveBuffer = ByteArray(AUDIO_UDP_RECV_BUFSIZE)
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                    val receiveSuccessful = synchronized(socketLock) {
                        val socket = udpSocket
                        if (socket == null || socket.isClosed) {
                            println("ACM_DEBUG: UDP socket is null or closed, exiting")
                            return@synchronized false
                        }

                        try {
                            socket.soTimeout = 1000
                        } catch (e: Exception) {
                            println("ACM_DEBUG: Failed to set socket timeout: ${e.message}")
                            return@synchronized false
                        }

                        try {
                            socket.receive(packet)
                            true
                        } catch (e: java.net.SocketTimeoutException) {
                            null
                        } catch (e: java.net.SocketException) {
                            println("ACM_DEBUG: Socket exception during receive: ${e.message}")
                            false
                        } catch (e: Exception) {
                            println("ACM_DEBUG: Unexpected exception during receive: ${e.message}")
                            false
                        }
                    }

                    when (receiveSuccessful) {
                        null -> continue
                        false -> break
                        true -> {
                            if (packet.length > 0 && isRunning.get()) {
                                // Listen mode: extract remote address from first incoming packet
                                if (isListening.get() && remoteAddress == null && packet.address != null && !relayMode) {
                                    remoteAddress = packet.address
                                    remoteUdpPort = packet.port
                                    isListening.set(false)
                                    println("ACM_DEBUG: Listen mode - peer connected from ${packet.address.hostAddress}:${packet.port}")
                                    sendHelloPacket()
                                    handler.post {
                                        listener.onCallStarted("${packet.address.hostAddress}:${packet.port}", false)
                                    }
                                }
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
                        println("ACM_DEBUG: receive outer exception: ${e.message}")
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

    /**
     * Play-out: mix every rendered participant into one stream.
     *
     * This runs on its own coroutine rather than inside the receive loop,
     * because with several senders the receive loop fires several times per
     * frame period and would drive the device far faster than real time.
     * AudioTrack in MODE_STREAM blocks until its buffer has room, so writing
     * one frame per iteration is what paces this loop - including the silent
     * frames, which keep the device fed while nobody is speaking. It is the
     * same role Pa_WriteStream plays in the desktop build.
     *
     * A two-party call is simply N=1: one entry in the pool, one frame summed
     * per iteration, the same bytes reaching AudioTrack as before.
     */
    private fun startAudioPlayback() {
        println("ACM_DEBUG: startAudioPlayback called")
        playJob = CoroutineScope(Dispatchers.IO).launch {
            println("ACM_DEBUG: startAudioPlayback - entering mix loop")

            // 32-bit accumulator: summing eight full-scale 16-bit frames
            // cannot overflow it, so clipping is decided once, at the end.
            val acc = IntArray(AUDIO_FRAME_SAMPLES)
            val out = ByteArray(AUDIO_PCM_BYTES_PER_FRAME)
            var frameNo = 0L
            var idleFrames = 0L

            while (isRunning.get()) {
                try {
                    java.util.Arrays.fill(acc, 0)
                    var voices = 0

                    synchronized(mixLock) {
                        for (m in mix) {
                            if (m.slot < 0) continue

                            // Build a little depth before starting a voice, and
                            // go back to waiting if it runs dry: playing every
                            // frame the instant it arrives turns ordinary
                            // network jitter into chopped audio.
                            if (!m.prefilled) {
                                if (m.ring.size < PLAYOUT_PREFILL_FRAMES) continue
                                m.prefilled = true
                            }
                            val frame = m.ring.removeFirstOrNull()
                            if (frame == null) {
                                m.prefilled = false
                                continue
                            }
                            for (k in 0 until AUDIO_FRAME_SAMPLES) acc[k] += frame[k]
                            m.frames++
                            voices++
                        }
                    }

                    // Saturate rather than wrap. Wrapping turns two loud
                    // speakers into a full-scale square wave, which is
                    // unpleasant in a way that clipping is not.
                    for (k in 0 until AUDIO_FRAME_SAMPLES) {
                        var v = acc[k]
                        if (v > MAX_SAMPLE) v = MAX_SAMPLE
                        else if (v < MIN_SAMPLE) v = MIN_SAMPLE
                        out[k * 2] = (v and 0xFF).toByte()
                        out[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }

                    // Synchronized for the whole write: this is what keeps the
                    // track from being released underneath us.
                    val written = try {
                        synchronized(this@AudioCallManager) {
                            val track = audioTrack
                            when {
                                track == null -> NO_TRACK
                                track.state != AudioTrack.STATE_INITIALIZED -> NO_TRACK
                                track.playState != AudioTrack.PLAYSTATE_PLAYING -> NO_TRACK
                                else -> track.write(out, 0, out.size)
                            }
                        }
                    } catch (e: IllegalStateException) {
                        println("ACM_DEBUG: IllegalStateException during write: ${e.message}")
                        NO_TRACK
                    } catch (e: NullPointerException) {
                        println("ACM_DEBUG: NullPointerException during write: ${e.message}")
                        NO_TRACK
                    }

                    if (written == NO_TRACK) {
                        // No usable output device. The frames above were still
                        // popped, so the jitter buffers cannot grow without
                        // bound; pace by hand since nothing else does.
                        idleFrames++
                        if (idleFrames % 250L == 1L) {
                            println("ACM_DEBUG: playout has no usable AudioTrack (frame $frameNo)")
                        }
                        delay(AUDIO_FRAME_MS.toLong())
                        continue
                    }
                    if (written < 0) {
                        println("ACM_DEBUG: track.write returned error: $written")
                        break
                    }

                    frameNo++
                    if (voices > 0 && frameNo % 250L == 0L) {
                        println("ACM_DEBUG: playout frame $frameNo, $voices voice(s) mixed")
                    }
                } catch (e: Exception) {
                    println("ACM_DEBUG: Playback exception: ${e.message}")
                    e.printStackTrace()
                    if (isRunning.get()) {
                        // Stop playback on error
                        break
                    }
                }
            }
            println("ACM_DEBUG: startAudioPlayback - exiting mix loop")
        }
    }

    private var audioFrameCount = 0

    private fun processAudioData(audioData: ByteArray, length: Int) {
        audioFrameCount++

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

            // Encrypt under our own send key. Audio and stats share seqTx,
            // so they share this key: see the note on seqTx.
            val key = ownAudioKey
            if (key == null) {
                println("ACM_DEBUG: processAudioData - no send key, skipping")
                return
            }
            val seq = seqTx.getAndIncrement()
            val encryptedPacket = MediaPacket.encrypt(
                PKT_VER_AUDIO.toInt(), ownSid, seq, key, encodedData)

            if (encryptedPacket == null) {
                println("ACM_DEBUG: processAudioData - encryption failed, skipping")
                return
            }

            if (audioFrameCount % 100 == 0) {
                println("ACM_DEBUG: Encrypted ${encryptedPacket.size} bytes, seq=$seq")
            }

            // Send media packet
            sendPacket(encryptedPacket)

            // Send stats every 2 seconds
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatsSendTime >= 2000L) {
                lastStatsSendTime = now
                sendStatsPacket()
            }

            if (audioFrameCount % 100 == 0) {
                println("ACM_DEBUG: Sent audio packet, seq=$seq, RTT=${measuredRttMs}ms")
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
            if (length < 1) return

            // HELLO2, or a pre-group HELLO we can only complain about.
            if (packetData[0] == MediaHello.TYPE || packetData[0] == MediaHello.LEGACY_TYPE) {
                handleHello2(packetData, length)
                return
            }

            val table = senderTable ?: return

            // The SID chooses the key. Nothing read here is trusted yet - the
            // header is authenticated only by a successful decrypt, and peek()
            // is where a runt packet is turned away.
            val parsed = MediaPacket.peek(packetData, length) ?: return
            val sid = parsed.sid
            val counter = parsed.counter

            // Counter domain, not media type: audio and stats leave on one
            // counter, so they arrive under one key.
            val stream = MediaKeys.STREAM_AUDIO

            // A 3-byte tag collides for real, so there may be two candidates.
            val candidates = synchronized(tableLock) { table.findBySid(sid) }
            for (idx in candidates) {
                val key = synchronized(tableLock) { table.key(idx, stream) } ?: continue
                val plain = MediaPacket.decrypt(packetData, length, key) ?: continue

                // Authenticated only now, so only now may the window move: a
                // forged counter that advanced it would silence the real sender.
                val verdict = synchronized(tableLock) { table.acceptSeq(idx, stream, counter) }
                if (verdict != SenderTable.Verdict.FRESH) {
                    println("ACM_DEBUG: dropped packet from slot $idx, counter=$counter ($verdict)")
                    return
                }
                if (idx >= 0 && idx < rxCount.size) rxCount[idx]++

                // The type byte is covered by the tag, so routing on it cannot
                // be steered by flipping a cleartext byte in flight.
                when (parsed.type) {
                    // Which sender this is decides which decoder it reaches:
                    // idx is the sender-table slot the key came from.
                    PKT_VER_AUDIO.toInt() -> decodeForMix(idx, plain)
                    PKT_VER_STATS.toInt() -> handleStatsPayload(plain)
                    else -> {}
                }
                return
            }
            // Nobody installed opened it: an unknown sender, or noise.

        } catch (e: Throwable) {
            // Catch everything including native crashes
            // Don't propagate any exceptions
        }
    }

    /**
     * The decoder and jitter buffer for one sender, creating or reassigning an
     * entry if this is a voice we are not currently rendering.
     *
     * Reassignment builds a fresh decoder instead of reusing the old one: Opus
     * state left by the previous speaker would be decoded as noise at the head
     * of the new stream. OpusCodec.Decoder.reset() is an empty method over the
     * native decoder, so replacing the object is the only reset reachable from
     * here, and it is the equivalent of OPUS_RESET_STATE on the desktop.
     *
     * Caller must hold mixLock.
     *
     * @return null only if a decoder cannot be created at all
     */
    private fun mixAcquire(tableSlot: Int): MixSlot? {
        for (m in mix) {
            if (m.slot == tableSlot) return m
        }

        val chosen: MixSlot = mix.firstOrNull { it.slot < 0 } ?: run {
            // Everything is busy: the voice heard longest ago steps aside. Its
            // key and replay window stay in the sender table, so it comes back
            // the moment it speaks again.
            var lru = mix[0]
            for (m in mix) {
                if (m.lastMs < lru.lastMs) lru = m
            }
            println("ACM_DEBUG: mix pool full, table slot ${lru.slot} yields its decoder to $tableSlot")
            lru
        }

        // Whatever is queued belongs to the participant being displaced.
        chosen.ring.clear()
        chosen.decoder?.let {
            try { it.destroy() } catch (_: Exception) {}
        }
        chosen.decoder = null

        val dec = try {
            OpusCodec.createDecoder(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)
        } catch (e: Exception) {
            println("ACM_DEBUG: no decoder for table slot $tableSlot: ${e.message}")
            null
        }
        if (dec == null) {
            chosen.slot = -1
            return null
        }

        chosen.decoder = dec
        chosen.slot = tableSlot
        chosen.prefilled = false
        chosen.frames = 0
        // Stamped now, so a voice that has just arrived is not the victim of
        // the very next arrival before it has been heard at all.
        chosen.lastMs = SystemClock.elapsedRealtime()
        println("ACM_DEBUG: rendering table slot $tableSlot (${mix.count { it.slot >= 0 }}/$MAX_MIX voices)")
        return chosen
    }

    /**
     * Decode one authenticated audio payload into its own sender's jitter
     * buffer.
     *
     * Nothing is written to AudioTrack here. With several senders this runs
     * several times per frame period, so writing from here would push the
     * device far faster than real time and let participants overwrite one
     * another - which is the bug this pool replaces, not the fix.
     */
    private fun decodeForMix(tableSlot: Int, encodedData: ByteArray) {
        if (!isRunning.get()) return

        synchronized(mixLock) {
            // Re-read under the lock. isRunning is cleared before mixTeardown
            // takes this lock, so a false here means teardown has not started
            // yet and will still collect whatever mixAcquire builds below;
            // without the re-read a packet in flight can create a decoder
            // after teardown and leak it for the life of the process.
            if (!isRunning.get()) return
            val m = mixAcquire(tableSlot) ?: return
            val decoder = m.decoder ?: return

            val pcm = try {
                decoder.decode(encodedData, AUDIO_FRAME_SAMPLES)
            } catch (e: Exception) {
                println("ACM_DEBUG: Opus decode failed for table slot $tableSlot: ${e.message}")
                return
            }
            if (pcm.size < AUDIO_FRAME_SAMPLES) return

            m.ring.addLast(pcm)
            m.lastMs = SystemClock.elapsedRealtime()

            // Per-sender latency control: one peer arriving in bursts must not
            // add delay for the others, and must not grow without bound.
            while (m.ring.size > MAX_PLAYOUT_FRAMES) m.ring.removeFirst()
        }
    }

    /** Release every per-participant decoder and jitter buffer. */
    private fun mixTeardown() {
        synchronized(mixLock) {
            for (m in mix) {
                m.decoder?.let {
                    try {
                        it.destroy()
                    } catch (e: Exception) {
                        println("ACM_DEBUG: Error destroying decoder: ${e.message}")
                    }
                }
                m.decoder = null
                m.ring.clear()
                m.slot = -1
                m.prefilled = false
                m.lastMs = 0
                m.frames = 0
            }
        }
    }

    /**
     * Per-participant teardown report: decrypted vs actually mixed. Must run
     * before mixTeardown() (which zeroes the frame counts) and before
     * clearMediaKeys() (which drops the sender table).
     */
    private fun reportMedia() {
        // The two locks are taken one after the other, never nested: no other
        // path here takes mixLock and tableLock at the same time, and this one
        // must not be the first to create an order between them.
        val mixedBySlot = HashMap<Int, Long>()
        synchronized(mixLock) {
            for (m in mix) {
                if (m.slot >= 0) mixedBySlot[m.slot] = m.frames
            }
        }
        synchronized(tableLock) {
            val table = senderTable ?: return
            for (i in 0 until SenderTable.MAX_SLOTS) {
                val slot = table.slotAt(i) ?: continue
                val sid = slot.sid.joinToString("") { "%02x".format(it) }
                println("ACM_DEBUG: [MEDIA] peer $sid decrypted ${rxCount[i]} mixed ${mixedBySlot[i] ?: 0L}")
            }
        }
    }

    // The framing - [type(1)][SID(3)][counter(5)] then AES-256-GCM with those
    // nine bytes as AAD - lives in com.fear.crypto.MediaPacket. It used to be
    // hand-copied into this file and into VideoCallManager, which is how two
    // ports of one wire format drift apart; MediaPacket is pinned by the same
    // frozen vectors as the C side.

    private fun handleStatsPayload(decrypted: ByteArray) {
        if (decrypted.size >= 8) {
            val bb = ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN)
            val pingTs = bb.int   // peer's timestamp
            val pongTs = bb.int   // echo of our last timestamp

            // The echo is addressed to nobody: a participant echoes whichever
            // peer it heard from last and everyone receives it, so in a group
            // call most echoes carry a timestamp from a third machine's clock.
            // Subtracting that from ours gives the gap between two uptimes -
            // on a live three-way call it read as 248084855 ms and pinned the
            // whole call at the lowest quality. Foreign values land anywhere
            // in the 32-bit millisecond range, so only a plausible one can be
            // ours. It is a real round trip to whichever peer echoed us last.
            if (pongTs != 0) {
                val now32 = (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt()
                val rtt = now32 - pongTs
                if (rtt in 0..RTT_SANE_MAX_MS) measuredRttMs = rtt
                handler.post { listener.onStatsUpdated(measuredRttMs) }
            }
            lastPeerPingTs = pingTs
            peerPingRecvTime = SystemClock.elapsedRealtime()
        }
    }

    private fun sendStatsPacket() {
        // Stats share the audio counter domain, hence the audio key.
        val key = ownAudioKey ?: return

        val now = SystemClock.elapsedRealtime()
        val now32 = (now and 0xFFFFFFFFL).toInt()
        // Hold time compensation
        val holdTime = if (peerPingRecvTime > 0) (now - peerPingRecvTime).toInt() else 0
        val pongValue = lastPeerPingTs + holdTime

        // AudioStatsPayload: ping_ts(4) + pong_ts(4) + reserved1(4) + reserved2(4)
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(now32)              // ping_ts
            putInt(pongValue)          // pong_ts (echo peer's ping + hold time)
            putInt(0)                  // reserved1
            putInt(0)                  // reserved2
        }.array()

        val seq = seqTx.getAndIncrement()
        val packet = MediaPacket.encrypt(
            PKT_VER_STATS.toInt(), ownSid, seq, key, payload) ?: return
        sendPacket(packet)
    }

    fun getMeasuredRttMs(): Int = measuredRttMs

    /**
     * HELLO2, type 0x7E. Flags say what this binary sends (audio only here)
     * plus IDENTITY when we have one. No video geometry: that field set is
     * meaningful only with MH_FLAG_VIDEO.
     */
    private fun sendHelloPacket() {
        val hk = helloKey ?: return
        val cid = callId ?: return
        if (senderSalt.size != MediaKeys.SALT_BYTES) return

        val im = identityManager
        val pk = if (im != null && im.hasIdentity()) im.getPublicKey() else null
        var flags = MediaHello.FLAG_AUDIO
        if (pk != null) flags = flags or MediaHello.FLAG_IDENTITY

        // IdentityManager never hands out identity_sk, so the array below is
        // a placeholder: build() reads only bytes 32..63 from it, and those
        // are the public key. The signature itself comes from IdentityManager.
        val skPlaceholder = if (pk != null) ByteArray(64).also { pk.copyInto(it, 32) } else null

        val packet = try {
            MediaHello.build(
                MediaHello.Hello(
                    flags = flags,
                    keyVersion = KEY_VERSION,
                    callId = cid,
                    senderSalt = senderSalt,
                    // The name we registered on the relay with, so the far
                    // end can caption us with something a person recognises
                    // instead of six hex digits of our SID.
                    name = relayName,
                ),
                hk,
                skPlaceholder,
                signer = identityOps(),
            )
        } catch (e: Exception) {
            println("ACM_DEBUG: HELLO2 build failed: ${e.message}")
            return
        }

        sendPacket(packet)
    }

    /**
     * Announce ourselves for the whole call: quickly while nobody has
     * answered, slowly once somebody has.
     *
     * Stopping at the first peer left a later arrival dependent on the single
     * reply handleHello2 sends them, which has no retransmission behind it.
     * Two-party audio survived that only because the desktop beacons; the
     * video path, where nothing did, failed outright on a real three-device
     * call. Both sides beacon now.
     */
    private fun startHelloAnnounce() {
        sendHelloPacket()
        CoroutineScope(Dispatchers.IO).launch {
            while (isRunning.get()) {
                sendHelloPacket()
                delay(if (peerCount() == 0) HELLO_RETRY_MS else HELLO_KEEPALIVE_MS)
            }
        }
    }

    private fun peerCount(): Int = synchronized(tableLock) { senderTable?.count() ?: 0 }

    /**
     * Signing and verification for HELLO2. Delegated to IdentityManager when
     * there is one, so identity_sk never leaves it.
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

    /**
     * A verified HELLO2 installs its sender. The table decides what is new:
     * an identical announcement is a no-op by construction, so a repeated
     * HELLO cannot reset a replay window or swap a key out from under us.
     */
    private fun handleHello2(data: ByteArray, length: Int) {
        val hk = helloKey ?: return
        val cid = callId ?: return
        val table = senderTable ?: return

        val res = MediaHello.parse(data, length, hk, signer = identityOps())
        if (res.status != MediaHello.Status.OK) {
            if (res.status == MediaHello.Status.ERR_LEGACY_PEER &&
                legacyPeerLogged.compareAndSet(false, true)) {
                // Once per call: an old peer will keep sending these.
                notifyError("Peer runs a pre-group F.E.A.R. build and must be updated")
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
            println("ACM_DEBUG: HELLO2 not installed: $status")
            return
        }

        val pk = hello.pk
        if (pk != null) notePeerIdentity(pk)

        if (isNew) {
            // Exactly one reply, so the new peer learns us. A repeat installs
            // nothing and gets no answer, which is what stops two peers from
            // echoing HELLOs at each other forever.
            println("ACM_DEBUG: new sender installed (${peerCount()} peers), replying with one HELLO2")
            sendHelloPacket()
        }
    }

    /**
     * TOFU keyed by the peer's public key. Keying it by a display name (or by
     * a fixed string like "peer") would let one participant's record cover
     * another's as soon as a call has more than two people in it.
     */
    private fun notePeerIdentity(pk: ByteArray) {
        val im = identityManager ?: return
        val label = im.fpshort(pk)
        if (!seenPeers.add(label)) return
        println("ACM_DEBUG: peer identity ${im.fingerprint(pk)} is ${im.checkPeerKey(label, pk)}")
    }

    /**
     * Wipe every key, salt and the master key. sodium_memzero has no Kotlin
     * equivalent; Arrays.fill is the best a JVM offers, and it is still
     * strictly better than the old code, which never wiped anything.
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
        helloKey?.let { java.util.Arrays.fill(it, 0) }
        helloKey = null
        java.util.Arrays.fill(senderSalt, 0)
        senderSalt = ByteArray(0)
        java.util.Arrays.fill(roomKey, 0)
        roomKey = ByteArray(0)
        ownSid = ByteArray(0)
        idbind = MediaKeys.UNSIGNED_IDBIND
        // Cleared too: the next call needs its own id, and silently reusing
        // this one would weaken the cross-call replay barrier it exists for.
        callId = null
        seenPeers.clear()
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

    private companion object {
        /** Above this it is somebody else's clock, not a round trip. */
        const val RTT_SANE_MAX_MS = 5000
        /** Beacon period while nobody has answered. */
        const val HELLO_RETRY_MS = 250L
        /** Beacon period once somebody has, matching audio_call's keepalive. */
        const val HELLO_KEEPALIVE_MS = 5000L
        /** Nothing produces a nonzero key version yet. */
        const val KEY_VERSION = 0

        /**
         * How many participants are rendered at once.
         *
         * The sender table holds 32 senders because the transport does, but
         * decoding and mixing 32 streams is not something a phone will do, and
         * a room where eight people talk at once is already unusable for human
         * reasons. Senders past this limit stay authenticated and stay tracked
         * - they are simply not rendered, and the participant heard longest ago
         * gives up its decoder when somebody new speaks.
         */
        const val MAX_MIX = 8

        /** Frames a participant must have queued before it starts playing. */
        const val PLAYOUT_PREFILL_FRAMES = 6

        /** Per-participant jitter-buffer cap, in 20 ms frames. */
        const val MAX_PLAYOUT_FRAMES = 20

        /** Mixing clips here rather than wrapping. */
        const val MAX_SAMPLE = 32767      // Short.MAX_VALUE
        const val MIN_SAMPLE = -32768     // Short.MIN_VALUE

        /**
         * track.write() stand-in for "no usable AudioTrack this iteration".
         * Distinct from every AudioTrack.ERROR_* code, which are -1 to -6.
         */
        const val NO_TRACK = -1000

        const val CALL_ID_REQUIRED =
            "Cannot start the call: no call id. Pass the 16-byte call_id from " +
            "the call invite to initialize() or setCallId()."
    }
}