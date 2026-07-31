package com.fear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
        /** Above this it is somebody else's clock, not a round trip. */
        private const val RTT_SANE_MAX_MS = 5000
        /** Beacon period while nobody has answered. */
        private const val HELLO_RETRY_MS = 250L
        /** Beacon period once somebody has, matching audio_call's keepalive. */
        private const val HELLO_KEEPALIVE_MS = 5000L
        /** How long to sit unanswered before saying so once. */
        private const val HELLO_LONELY_MS = 5000L
        /** Participants rendered at once, out of the 32 the key table holds. */
        private const val MAX_MIX = 8
        /** Jitter depth before a voice starts playing, in frames. */
        private const val PLAYOUT_PREFILL_FRAMES = 6
        /** Per-sender latency cap, in frames. */
        private const val MAX_PLAYOUT_FRAMES = 20
        /** How long the big view's holder may be silent before it is taken. */
        private const val SPEAKER_QUIET_MS = 700L
        /** Floor on how often the big view may change hands. */
        private const val SPEAKER_DWELL_MS = 1500L
        /** Loudness a voice must reach to count as speech, on a 32768 scale. */
        private const val SPEECH_FLOOR = 900.0
        /** Per-frame decay of the smoothed loudness: fast attack, slow fall. */
        private const val ENERGY_DECAY = 0.86
        /** How long somebody keeps their speaking mark after falling quiet. */
        private const val SPEAKING_HOLD_MS = 900L
        /** How much louder a challenger must be to take the big view. */
        private const val SPEAKER_MARGIN = 1.6

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

        /**
         * Who is in the call and who is on the big view, whenever either
         * changes. The screen owns the layout; this only reports state.
         */
        fun onParticipants(participants: List<Participant>) {}
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
    /** One participant, as the call screen needs to draw them. */
    data class Participant(
        val slot: Int,
        val name: String,
        /** On the big view right now. */
        val main: Boolean,
        /** Loud enough to be the one talking. */
        val speaking: Boolean,
        /** The user chose them, so the speaker does not take the view back. */
        val pinned: Boolean,
        /** Their picture's shape, so a cell can be sized to it rather than
         *  stretching a face to fill whatever box it was given. Zero until
         *  they have announced it. */
        val width: Int,
        val height: Int,
    )

    /**
     * A participant's video: its own decoder, pointed at whichever surface
     * currently shows them.
     *
     * One decoder per participant rather than one per surface. A participant
     * appears in at most one place at a time - big view or strip - and moving
     * them is setOutputSurface rather than a rebuild, so a handover costs
     * nothing and loses no reference frames.
     */
    private class PeerVideo {
        var decoder: Vp8Decoder? = null
        var surface: Surface? = null
        var frames: Long = 0
    }

    /** Guards peerVideo, the surfaces, and who is on the big view. */
    private val videoLock = Any()
    private val handler = Handler(Looper.getMainLooper())

    private val peerVideo = HashMap<Int, PeerVideo>()
    private val peerName = HashMap<Int, String>()
    private val peerSize = HashMap<Int, Pair<Int, Int>>()

    /*
     * The big view has a decoder of its own, fed the same frames as the
     * participant currently on it.
     *
     * Moving one decoder between the strip and the big view is what left a
     * pinned participant frozen in their own cell: a decoder renders to one
     * surface, so whichever it was not pointed at kept its last picture.
     * Two decoders for that one participant costs a decoder and buys every
     * cell staying live. It also removes the handover race outright, since
     * no surface ever changes hands - each participant's decoder stays on
     * its cell for the whole call.
     */
    private var mainDecoder: Vp8Decoder? = null
    private var mainDecoderSlot: Int = -1

    /** Surfaces the call screen has given us. */
    private var mainSurface: Surface? = null
    private val thumbSurface = HashMap<Int, Surface>()

    /** Who is on the big view, who the user pinned there, and who is loudest. */
    private var mainSlot: Int = -1
    private var pinnedSlot: Int = -1
    private var lastSwitchMs: Long = 0
    private var lastParticipantsKey: String = ""


    // Audio
    private var opusEncoder: OpusCodec.Encoder? = null

    /**
     * One decoder and one jitter buffer per rendered participant.
     *
     * Opus carries state between frames, so a single decoder fed by several
     * senders garbles all of them, and writing each sender straight to
     * AudioTrack as its packets arrive has them cut each other off instead
     * of mixing. That is the difference between the keys working for N
     * participants and the call working for N.
     */
    private class MixSlot {
        var slot: Int = -1
        var decoder: OpusCodec.Decoder? = null
        val ring: ArrayDeque<ShortArray> = ArrayDeque()
        var lastMs: Long = 0
        var prefilled: Boolean = false
        var frames: Long = 0
        /** Smoothed loudness, fast to rise and slow to fall. */
        var energy: Double = 0.0
        /** When this voice was last above the speech floor. */
        var voiceMs: Long = 0
    }

    private val mix = Array(MAX_MIX) { MixSlot() }
    private val mixLock = Any()
    private var playThread: Thread? = null
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

    /*
     * One surface, so one participant on screen at a time.
     *
     * Every completed frame used to go into the single VP8 decoder whoever
     * sent it, which decodes one stream against another stream's reference
     * frames - the video equivalent of sharing an Opus decoder. Only the
     * holder's frames are decoded now.
     *
     * The choice is sticky rather than most-recently-arrived: nothing here
     * gates sending on speech, every camera streams continuously, so
     * "whoever arrived last" alternates at random and any hold on top of it
     * only sets the period of the flicker.
     */
    private val videoStats = HashMap<Int, Long>()

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
     * Attach the surface remote video is rendered on. Safe to call after
     * startCall, and safe to call again.
     *
     * The decoder has to follow the surface, because MediaCodec bound to a
     * surface that Android has destroyed fails every later call into it - the
     * codec is gone, not merely stale. SurfaceView destroys and recreates its
     * surface when the view is resized, and this view is resized to match the
     * peer whose picture is on screen. With one peer that happens once, at the
     * start; with two peers of different shapes it happens again later, and
     * a decoder that did not follow spends the rest of the call throwing.
     */
    fun setMainSurface(surface: Surface?) {
        synchronized(videoLock) {
            // surfaceChanged fires for every relayout with the same surface,
            // and retargeting on each one was enough churn to keep decoders
            // permanently in pieces.
            if (mainSurface === surface) return
            mainSurface = surface
            dropMainDecoderLocked()
        }
        publishParticipants()
    }

    /**
     * A strip cell for `slot`, or null when it goes away. The screen creates
     * and destroys these as participants come and go.
     */
    fun setThumbSurface(slot: Int, surface: Surface?) {
        synchronized(videoLock) {
            if (thumbSurface[slot] === surface) return
            if (surface == null) thumbSurface.remove(slot) else thumbSurface[slot] = surface
            retargetLocked()
        }
        publishParticipants()
    }

    /**
     * Pin a participant to the big view, or unpin them if they are already
     * pinned. A pin outranks the speaker: the point of choosing somebody is
     * that they stay chosen while other people talk.
     */
    fun togglePin(slot: Int) {
        synchronized(videoLock) {
            pinnedSlot = if (pinnedSlot == slot) -1 else slot
            chooseMainLocked(force = true)
        }
        publishParticipants()
    }

    /** A participant draws in their own cell, and only there. */
    private fun surfaceForLocked(slot: Int): Surface? = thumbSurface[slot]

    /**
     * Point every decoder at the surface its participant now belongs to.
     * Cheap by design: setOutputSurface keeps the decoder, so a handover does
     * not cost the seconds of black that rebuilding would.
     */
    private fun retargetLocked() {
        /*
         * Whoever is leaving the big view goes first.
         *
         * A MediaCodec owns its output surface exclusively: when the next
         * one connects, the framework disconnects the previous holder and
         * that codec is finished - "disconnectFromSurface" in the system log,
         * "Invalid to call at Released state" in ours. Retargeting in
         * whatever order the map happened to be in meant the arriving
         * participant regularly claimed the big view before the departing one
         * had let go, which killed a decoder on most handovers.
         */
        val order = peerVideo.entries.sortedBy { (slot, pv) ->
            if (pv.surface === mainSurface && slot != mainSlot) 0 else 1
        }
        for ((slot, pv) in order) {
            val want = surfaceForLocked(slot)
            if (want === pv.surface) continue
            if (want != null && !want.isValid) continue   // gone already
            val dec = pv.decoder
            if (want == null) {
                // Nowhere to draw: keep the decoder, it will be pointed
                // somewhere again when the screen gives us a surface.
                pv.surface = null
                continue
            }
            if (dec == null) {
                pv.surface = want
                continue
            }
            if (dec.setSurface(want)) {
                pv.surface = want
            } else {
                /* The swap was refused - the codec is gone, usually because
                 * the surface it was configured with was destroyed under it.
                 * Start over on the new surface and accept the wait for this
                 * sender's next keyframe, which is now at most three seconds
                 * away rather than a GOP.
                 *
                 * Rebuilt lazily, on the next frame, rather than here: this
                 * runs on the UI thread while frames arrive on another, and
                 * building a codec is not something to do under a lock the
                 * receive path also wants. */
                Log.w(TAG, "slot $slot: surface swap refused, rebuilding")
                try { dec.stop() } catch (_: Exception) {}
                pv.decoder = null
                pv.surface = want
            }
        }
    }

    /**
     * Decide who is on the big view.
     *
     * Pinned wins. Otherwise the loudest voice takes it, but only once the
     * current holder has been quiet for a moment and no sooner than
     * SPEAKER_DWELL_MS after the last change - without that floor two people
     * in a conversation trade the view on every syllable. Nobody speaking
     * changes nothing: the last speaker stays up, which is what every other
     * client does and what people expect.
     */
    private fun chooseMainLocked(force: Boolean = false) {
        val now = System.currentTimeMillis()

        val present = peerVideo.keys.toMutableSet()
        synchronized(mixLock) { for (m in mix) if (m.slot >= 0) present.add(m.slot) }

        if (pinnedSlot >= 0 && present.contains(pinnedSlot)) {
            if (mainSlot != pinnedSlot) {
                mainSlot = pinnedSlot
                lastSwitchMs = now
                dropMainDecoderLocked()
            }
            return
        }

        if (mainSlot >= 0 && !present.contains(mainSlot)) mainSlot = -1

        if (mainSlot < 0) {
            val first = peerVideo.keys.minOrNull() ?: present.minOrNull()
            if (first != null) {
                mainSlot = first
                lastSwitchMs = now
                dropMainDecoderLocked()
            }
            return
        }

        if (!force && now - lastSwitchMs < SPEAKER_DWELL_MS) return

        // Somebody who sends no video would take the big view and leave it
        // showing nothing, so the choice is between the cameras that are on.
        val withVideo = peerVideo.keys
        var loudest = -1
        var loudestEnergy = 0.0
        var holderVoiceMs = 0L
        var holderEnergy = 0.0
        synchronized(mixLock) {
            for (m in mix) {
                if (m.slot < 0) continue
                if (m.slot == mainSlot) { holderVoiceMs = m.voiceMs; holderEnergy = m.energy }
                if (!withVideo.contains(m.slot)) continue
                if (m.energy > loudestEnergy) { loudestEnergy = m.energy; loudest = m.slot }
            }
        }

        if (loudest < 0 || loudest == mainSlot) return
        if (loudestEnergy < SPEECH_FLOOR) return
        if (now - holderVoiceMs < SPEAKER_QUIET_MS) return
        /* Clearly louder, not merely louder. Three people in one room hear
         * each other's speakers, so their levels sit close together and a
         * bare comparison hands the big view around several times a minute
         * for no reason a viewer can see - and each handover costs both
         * decoders a reconnect. */
        if (loudestEnergy < holderEnergy * SPEAKER_MARGIN) return

        mainSlot = loudest
        lastSwitchMs = now
        dropMainDecoderLocked()
        Log.i(TAG, "speaker: slot $mainSlot (${peerName[mainSlot] ?: "?"})")
        retargetLocked()
    }

    /**
     * Somebody joined or left: pick the big view again and tell the screen.
     *
     * Not left to the periodic pass in the play-out thread, because until a
     * participant is on the big view nothing points a surface at them, and
     * with only one of them in the call there is no strip to fall back on -
     * a two-party call would show nothing at all.
     */
    private fun refreshParticipants() {
        synchronized(videoLock) { chooseMainLocked() }
        publishParticipants()
    }

    /** The big view is about to show somebody else; its decoder starts over. */
    private fun dropMainDecoderLocked() {
        try { mainDecoder?.stop() } catch (_: Exception) {}
        mainDecoder = null
        mainDecoderSlot = -1
    }

    /** Tell the screen who is here, but only when something actually moved. */
    private fun publishParticipants() {
        val list = synchronized(videoLock) {
            val present = peerVideo.keys.toMutableSet()
            synchronized(mixLock) { for (m in mix) if (m.slot >= 0) present.add(m.slot) }
            val now = System.currentTimeMillis()
            val speaking = HashSet<Int>()
            synchronized(mixLock) {
                for (m in mix) {
                    if (m.slot >= 0 && now - m.voiceMs < SPEAKING_HOLD_MS) speaking.add(m.slot)
                }
            }
            present.sorted().map { slot ->
                val size = peerSize[slot]
                Participant(
                    slot = slot,
                    name = peerName[slot] ?: "%06x".format(slot),
                    main = slot == mainSlot,
                    speaking = speaking.contains(slot),
                    pinned = slot == pinnedSlot,
                    width = size?.first ?: 0,
                    height = size?.second ?: 0,
                )
            }
        }

        val key = list.joinToString("|") {
            "${it.slot}:${it.name}:${it.main}:${it.speaking}:${it.pinned}:${it.width}x${it.height}"
        }
        if (key == lastParticipantsKey) return
        lastParticipantsKey = key
        handler.post { listener.onParticipants(list) }
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

        /*
         * The running check above is a gate, not a lock. This runs on
         * CameraX's own analyzer pool, and nothing synchronises that pool
         * with the thread ending the call: the check passes, endCall stops
         * the encoder, and the frame already in flight writes into a buffer
         * that no longer exists.
         *
         * Uncaught on that pool it kills the process, which is what it did
         * on every exit from a video call - and it took endCall's key wiping
         * and its teardown report down with it, so leaving a call by pressing
         * back left the sender table and the media keys unwiped and told the
         * peers nothing. A frame dropped during teardown costs a frame.
         */
        try {
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
        } catch (e: IllegalStateException) {
            // Only worth a line if we are not already tearing down, where it
            // is the expected outcome rather than a fault.
            if (running.get()) Log.w(TAG, "video frame dropped: ${e.message}")
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

    /**
     * The decoder and jitter buffer for a sender, creating or reassigning one
     * when this is a voice we are not currently rendering. Reassignment
     * replaces the decoder outright, because the old one holds the displaced
     * stream's history and would decode the new one as noise.
     */
    private fun mixAcquire(tableSlot: Int): MixSlot? {
        for (m in mix) if (m.slot == tableSlot) return m

        val chosen = mix.firstOrNull { it.slot < 0 } ?: run {
            var lru = mix[0]
            for (m in mix) if (m.lastMs < lru.lastMs) lru = m
            lru
        }

        chosen.ring.clear()
        chosen.decoder?.let { try { it.destroy() } catch (_: Exception) {} }
        chosen.decoder = try {
            OpusCodec.createDecoder(Common.AUDIO_SAMPLE_RATE, Common.AUDIO_CHANNELS)
        } catch (e: Exception) {
            Log.w(TAG, "no decoder for slot $tableSlot: ${e.message}")
            null
        }
        if (chosen.decoder == null) { chosen.slot = -1; return null }

        chosen.slot = tableSlot
        chosen.prefilled = false
        chosen.frames = 0
        // Stamped now, so a voice that has only just arrived is not the
        // victim of the very next arrival.
        chosen.lastMs = System.currentTimeMillis()
        return chosen
    }

    /** Release every decoder and buffer. */
    private fun mixTeardown() {
        synchronized(mixLock) {
            for (m in mix) {
                m.decoder?.let { try { it.destroy() } catch (_: Exception) {} }
                m.decoder = null
                m.ring.clear()
                m.slot = -1
                m.prefilled = false
            }
        }
    }

    /**
     * Mix every rendered participant into one stream.
     *
     * Runs on its own thread because with several senders the receive loop
     * fires several times per frame period and would drive the device faster
     * than real time. AudioTrack.write blocks when its buffer is full, and
     * that is what paces this loop - including the silent frames, which keep
     * the device fed while nobody is speaking.
     */
    private fun startPlayout() {
        if (playThread != null) return
        playThread = Thread {
            val n = Common.AUDIO_FRAME_SAMPLES
            val acc = IntArray(n)
            val out = ByteArray(n * 2)
            var speakerTick = 0
            while (running.get()) {
                java.util.Arrays.fill(acc, 0)
                synchronized(mixLock) {
                    for (m in mix) {
                        if (m.slot < 0) continue
                        if (!m.prefilled) {
                            if (m.ring.size < PLAYOUT_PREFILL_FRAMES) continue
                            m.prefilled = true
                        }
                        val frame = m.ring.removeFirstOrNull()
                        if (frame == null) { m.prefilled = false; continue }
                        val len = minOf(n, frame.size)
                        for (i in 0 until len) acc[i] += frame[i].toInt()
                        m.frames++
                    }
                }

                // Five times a second is plenty to follow a conversation and
                // cheap enough to do on the thread that is already awake.
                if (++speakerTick >= 10) {
                    speakerTick = 0
                    synchronized(videoLock) { chooseMainLocked() }
                    publishParticipants()
                }

                val track = audioTrack
                if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                // Saturate rather than wrap: wrapping turns two loud speakers
                // into a full-scale square wave, which is worse than clipping.
                for (i in 0 until n) {
                    val v = if (acc[i] > 32767) 32767 else if (acc[i] < -32768) -32768 else acc[i]
                    out[i * 2] = (v and 0xFF).toByte()
                    out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                try { track.write(out, 0, out.size) } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun initializeAudio() {
        try {
            // Opus codecs
            opusEncoder = OpusCodec.createEncoder(Common.AUDIO_SAMPLE_RATE, Common.AUDIO_CHANNELS, Common.AC_OPUS_BITRATE)

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
            startPlayout()

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
        vp8Encoder = null
        synchronized(videoLock) {
            for (pv in peerVideo.values) {
                try { pv.decoder?.stop() } catch (_: Exception) {}
                pv.decoder = null
            }
            peerVideo.clear()
            peerSize.clear()
            dropMainDecoderLocked()
            thumbSurface.clear()
            mainSurface = null
            mainSlot = -1
            pinnedSlot = -1
        }

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        // Who was heard and who was seen, before the pools are released.
        synchronized(mixLock) {
            for (m in mix) {
                if (m.slot >= 0) Log.i(TAG, "[MEDIA] peer slot ${m.slot} mixed ${m.frames}")
            }
        }
        for ((slot, frames) in videoStats) {
            Log.i(TAG, "[VIDEO] peer slot $slot (${peerName[slot] ?: "?"}) frames $frames")
        }
        mixTeardown()
        videoStats.clear()
        try { playThread?.interrupt() } catch (_: Exception) {}
        playThread = null

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { opusEncoder?.destroy() } catch (_: Exception) {}
        opusEncoder = null

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

    /**
     * Announce ourselves for the whole call: quickly while nobody has
     * answered, slowly once somebody has.
     *
     * This used to stop at the first peer, on the reasoning that a later
     * arrival is covered by the one reply handleHello2 sends them. That reply
     * is a single packet with no retransmission behind it, and when a real
     * three-device call was placed this phone was the later arrival: both
     * desktops heard its HELLO and neither reply came back, so it sent video
     * they could both see, received nothing, and gave up after five seconds -
     * five times running.
     *
     * Nor does an unanswered HELLO end the call any more. Being first into a
     * group call is normal; the old five-second timeout dropped whoever
     * opened one before anybody else could join it.
     *
     * A repeat installs nothing at the peer, since the sender table is
     * idempotent per salt, and draws no reply of its own, so the beacon
     * cannot turn into a handshake storm.
     */
    private fun helloLoop() {
        Log.d(TAG, "HELLO loop started, sending to ${remoteAddress?.hostAddress}:$remotePort")

        var announced = false
        var lonelyLogged = false
        var waitedMs = 0L

        while (running.get()) {
            sendHello()

            if (!announced && peerCount() > 0) {
                announced = true
                Log.d(TAG, "HELLO handshake completed after ${waitedMs}ms")
                Log.d(TAG, "Remote: ${remoteWidth}x${remoteHeight}@${remoteFps}")
                listener.onConnected(remoteWidth, remoteHeight, remoteFps)
            }

            if (!announced && !lonelyLogged && waitedMs >= HELLO_LONELY_MS) {
                lonelyLogged = true
                Log.w(TAG, "no peer after ${waitedMs}ms, still announcing")
            }

            val gap = if (peerCount() == 0) HELLO_RETRY_MS else HELLO_KEEPALIVE_MS
            try {
                Thread.sleep(gap)
            } catch (_: InterruptedException) {
                break
            }
            waitedMs += gap
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
        val slotIdx: Int
        synchronized(tableLock) {
            val before = table.count()
            // install is idempotent per salt, so a repeat announcement gives
            // back the slot this sender already holds rather than a new one.
            val r = table.install(hello.senderSalt, peerIdbind, hello.keyVersion)
            status = r.first
            slotIdx = r.second
            isNew = status == SenderTable.Status.OK && table.count() > before
        }

        if (status != SenderTable.Status.OK) {
            Log.w(TAG, "HELLO2 not installed: $status")
            return
        }

        // What this participant calls themselves, for their caption. Taken
        // from every verified announcement, so somebody who rejoins under a
        // different name is not labelled with the old one.
        if (slotIdx >= 0) {
            synchronized(videoLock) { peerName[slotIdx] = hello.name }
        }
        refreshParticipants()

        if (hello.flags and MediaHello.FLAG_VIDEO != 0) {
            remoteWidth = hello.width
            remoteHeight = hello.height
            remoteFps = hello.fps
            if (slotIdx >= 0 && hello.width > 0 && hello.height > 0) {
                synchronized(videoLock) { peerSize[slotIdx] = hello.width to hello.height }
            }
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
                Common.PKT_VER_AUDIO.toInt() -> handleAudioPayload(idx, plain)
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

    /**
     * Decode into this sender's own decoder and queue the frame. The play-out
     * thread does the mixing and the writing; nothing here touches AudioTrack.
     */
    private fun handleAudioPayload(slot: Int, opusData: ByteArray) {
        synchronized(mixLock) {
            if (!running.get()) return
            val m = mixAcquire(slot) ?: return
            val pcm = try {
                m.decoder?.decode(opusData, Common.AUDIO_FRAME_SAMPLES)
            } catch (e: Exception) {
                null
            } ?: return
            if (pcm.isEmpty()) return

            /*
             * Loudness, which is what decides the big view.
             *
             * Video arrival cannot decide it: nothing gates sending on
             * speech, every camera streams continuously, so "whoever decoded
             * last" alternates at random - measured at nine handovers in ten
             * seconds on the desktop. Speech is a property of the audio.
             *
             * Fast attack and slow decay, so a voice takes the view on its
             * first syllable and does not lose it between words.
             */
            var sum = 0.0
            for (v in pcm) { val d = v.toDouble(); sum += d * d }
            val rms = kotlin.math.sqrt(sum / pcm.size.coerceAtLeast(1))
            m.energy = if (rms > m.energy) rms else m.energy * ENERGY_DECAY
            val nowMs = System.currentTimeMillis()
            if (rms > SPEECH_FLOOR) m.voiceMs = nowMs

            m.ring.addLast(pcm)
            m.lastMs = nowMs
            // One peer arriving in bursts must not add latency to another.
            while (m.ring.size > MAX_PLAYOUT_FRAMES) m.ring.removeFirst()
        }
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

            videoStats[slot] = (videoStats[slot] ?: 0L) + 1
            decodeForPeer(slot, completeFrame)
        }
    }

    /**
     * Decode one completed picture into its own participant's decoder.
     *
     * Per sender rather than shared, for the same reason the Opus decoders
     * are: VP8 predicts from previous frames, so two streams through one
     * decoder ruin each other. The decoder is built on first use, when the
     * screen has told us where this participant draws.
     */
    private fun decodeForPeer(slot: Int, frame: ByteArray) {
        val dec: Vp8Decoder?
        synchronized(videoLock) {
            val pv = peerVideo.getOrPut(slot) { PeerVideo() }
            pv.frames++

            if (pv.decoder == null) {
                val target = surfaceForLocked(slot)
                /*
                 * isValid, not just non-null. A SurfaceView hands its surface
                 * out and takes it away again on every relayout, and the
                 * window here rearranges itself each time somebody joins.
                 * Configuring a codec onto a surface that has already gone
                 * does not fail: the codec starts, reports itself started,
                 * and is in Released state by the first frame. That is
                 * exactly what a strip of black rectangles looked like from
                 * the inside - two decoders running and 1938 frames dropped
                 * with "Invalid to call at Released state".
                 */
                if (target == null || !target.isValid) {
                    // The screen will give us a live one; tell it who is here.
                    handler.post { refreshParticipants() }
                    return
                }
                pv.decoder = try {
                    Vp8Decoder().also { it.start(target, quality.width, quality.height) }
                } catch (e: Exception) {
                    Log.e(TAG, "no decoder for slot $slot", e)
                    null
                }
                pv.surface = if (pv.decoder != null) target else null
                if (pv.decoder != null) handler.post { refreshParticipants() }
            }
            dec = pv.decoder
        }

        /* The big view, if this is who is on it. Its decoder is separate
         * because reference frames belong to one stream: pointing it at a new
         * participant means starting over, which costs a wait for their next
         * keyframe - at most three seconds - while their cell keeps playing. */
        val big: Vp8Decoder?
        synchronized(videoLock) {
            if (slot != mainSlot) {
                big = null
            } else {
                if (mainDecoderSlot != slot) {
                    try { mainDecoder?.stop() } catch (_: Exception) {}
                    mainDecoder = null
                    mainDecoderSlot = slot
                }
                val target = mainSurface
                if (mainDecoder == null && target != null && target.isValid) {
                    mainDecoder = try {
                        Vp8Decoder().also { it.start(target, quality.width, quality.height) }
                    } catch (e: Exception) {
                        Log.e(TAG, "no decoder for the big view", e)
                        null
                    }
                }
                big = mainDecoder
            }
        }
        if (big != null && !big.decode(frame, System.nanoTime() / 1000)) {
            synchronized(videoLock) {
                if (mainDecoder === big) {
                    try { mainDecoder?.stop() } catch (_: Exception) {}
                    mainDecoder = null
                }
            }
        }

        if (dec == null) return

        /* Decoding outside the lock, because it can block on the codec and the
         * UI thread wants this lock to move participants around. */
        if (dec.decode(frame, System.nanoTime() / 1000)) return

        /* A decoder that refuses a frame is not coming back on its own -
         * usually its surface went away underneath it. Drop it and let the
         * next frame build one on whatever surface is live by then. The cost
         * is a wait for this sender's next keyframe, which is three seconds
         * at worst now rather than a whole GOP. */
        synchronized(videoLock) {
            val pv = peerVideo[slot] ?: return
            if (pv.decoder !== dec) return   // somebody already replaced it
            Log.w(TAG, "slot $slot: decode refused, rebuilding")
            try { pv.decoder?.stop() } catch (_: Exception) {}
            pv.decoder = null
            pv.surface = null
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
