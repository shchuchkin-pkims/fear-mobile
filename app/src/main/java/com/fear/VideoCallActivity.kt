package com.fear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fear.crypto.MediaKeys
import java.util.concurrent.Executors

/**
 * Full-screen video call activity.
 * Remote video on SurfaceView, local camera preview in corner.
 */
class VideoCallActivity : AppCompatActivity(), VideoCallManager.VideoCallListener {

    companion object {
        /** Shortest interval between two resizes of the same surface. */
        private const val REFIT_MIN_MS = 8000L
        private const val TAG = "VideoCallActivity"
        const val EXTRA_REMOTE_IP = "remote_ip"
        const val EXTRA_REMOTE_PORT = "remote_port"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_ENCRYPTION_KEY = "encryption_key"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_IS_LISTENING = "is_listening"
        const val EXTRA_IS_RELAY = "is_relay"
        const val EXTRA_RELAY_ROOM = "relay_room"
        const val EXTRA_RELAY_NAME = "relay_name"
        /** The 16-byte call_id as 32 hex characters. Mandatory: see below. */
        const val EXTRA_CALL_ID = "call_id"
        private const val PERMISSION_CODE = 200
    }

    private lateinit var remoteSurfaceView: android.view.TextureView
    private lateinit var localPreview: PreviewView
    private lateinit var endCallButton: Button
    private lateinit var toggleCameraButton: Button
    private lateinit var toggleMuteButton: Button
    private lateinit var statsTextView: TextView
    private lateinit var mainCaption: TextView
    private lateinit var mainContainer: android.widget.FrameLayout

    /** Surfaces we made from the views' textures, ours to release. */
    private var mainSurface: Surface? = null
    private val thumbSurfaces = HashMap<Int, Surface>()

    /* What each surface is currently sized for, and when it was last resized.
     *
     * Resizing a SurfaceView destroys its surface and takes the decoder bound
     * to it down with it, so this has to be rare. Senders do not make that
     * easy: the quality controller moves them between 640x480 and 1280x720,
     * which is a genuine change of shape, and following every one of those
     * cost 139 decoder rebuilds in half a minute. */
    private val pendingAspect = HashMap<Int, Pair<Int, Int>>()
    private val appliedFit = HashMap<Int, String>()
    private val layoutHooked = HashSet<Int>()
    private lateinit var stripScroll: android.widget.HorizontalScrollView
    private lateinit var strip: android.widget.LinearLayout

    /** One strip cell per participant, keyed by their sender slot. */
    private val stripCells = HashMap<Int, View>()

    /* What each cell and the caption currently say.
     *
     * Participants are republished several times a second, because who is
     * speaking changes that often. Writing the same text and the same colour
     * back into the views on every one of those is not free: it relayouts the
     * window, a relayout hands every SurfaceView a new surface, and a decoder
     * configured onto the old one is dead from that moment. Measured at 87
     * decoder rebuilds in 25 seconds - the picture arrived anyway, in fits.
     * So nothing is written unless it changed. */
    private val cellText = HashMap<Int, String>()
    private val cellColor = HashMap<Int, Int>()
    private var captionText: String? = null

    private var videoCallManager: VideoCallManager? = null
    private var isFrontCamera = true
    private var isMuted = false
    private var encoderAvailable = false
    @Volatile private var callEnded = false

    private var remoteIp = ""
    private var remotePort = 0
    private var localPort = 0
    private var encryptionKeyHex = ""
    private var qualityPreset = "medium"
    private var isListeningMode = false
    private var isRelayMode = false
    private var relayRoom = ""
    private var relayName = ""
    private var callIdHex = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_call)

        remoteIp = intent.getStringExtra(EXTRA_REMOTE_IP) ?: ""
        remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, 50000)
        localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0)
        encryptionKeyHex = intent.getStringExtra(EXTRA_ENCRYPTION_KEY) ?: ""
        qualityPreset = intent.getStringExtra(EXTRA_QUALITY) ?: "medium"
        isListeningMode = intent.getBooleanExtra(EXTRA_IS_LISTENING, false)
        isRelayMode = intent.getBooleanExtra(EXTRA_IS_RELAY, false)
        relayRoom = intent.getStringExtra(EXTRA_RELAY_ROOM) ?: ""
        relayName = intent.getStringExtra(EXTRA_RELAY_NAME) ?: ""
        callIdHex = intent.getStringExtra(EXTRA_CALL_ID) ?: ""

        Log.d(TAG, "onCreate: remote=$remoteIp:$remotePort keyLen=${encryptionKeyHex.length} quality=$qualityPreset listen=$isListeningMode relay=$isRelayMode")

        remoteSurfaceView = findViewById(R.id.remoteSurfaceView)
        localPreview = findViewById(R.id.localPreview)
        endCallButton = findViewById(R.id.endCallButton)
        toggleCameraButton = findViewById(R.id.toggleCameraButton)
        toggleMuteButton = findViewById(R.id.toggleMuteButton)
        statsTextView = findViewById(R.id.statsTextView)
        mainCaption = findViewById(R.id.mainCaption)
        mainContainer = findViewById(R.id.mainContainer)
        stripScroll = findViewById(R.id.stripScroll)
        strip = findViewById(R.id.strip)

        endCallButton.setOnClickListener { endCall() }
        toggleCameraButton.setOnClickListener { toggleCamera() }
        toggleMuteButton.setOnClickListener { toggleMute() }

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_CODE)
        } else {
            startVideoCall()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startVideoCall()
            } else {
                Toast.makeText(this, "Camera and audio permissions required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startVideoCall() {
        if (!isListeningMode && !isRelayMode && remoteIp.isEmpty()) {
            Log.e(TAG, "Remote IP is empty")
            Toast.makeText(this, "Remote IP is empty", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (encryptionKeyHex.length != 64) {
            Log.e(TAG, "Invalid key length: ${encryptionKeyHex.length} (need 64 hex chars)")
            Toast.makeText(this, "Invalid key (need 64 hex chars, got ${encryptionKeyHex.length})", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Parse key
        val keyBytes = try {
            ByteArray(32) { i ->
                encryptionKeyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse key hex", e)
            Toast.makeText(this, "Invalid hex key", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Select quality
        val preset = when (qualityPreset) {
            "low" -> VideoQualityPreset.LOW
            "high" -> VideoQualityPreset.HIGH
            else -> VideoQualityPreset.MEDIUM
        }

        // Every media key of this call is bound to the call_id, so there is
        // no call without one. The screen that started us draws it and
        // announces it to the room; deriving one here from the room key would
        // give every call in that room the same id.
        val callId = try {
            if (callIdHex.length != MediaKeys.CALLID_BYTES * 2) null
            else ByteArray(MediaKeys.CALLID_BYTES) { i ->
                callIdHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
        if (callId == null) {
            Log.e(TAG, "No usable call id in the intent (${callIdHex.length} chars)")
            Toast.makeText(this, "No call id: cannot start an encrypted call",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val manager = VideoCallManager(this, this)
        videoCallManager = manager

        val identityMgr = IdentityManager(this)
        manager.initialize(keyBytes, preset,
            if (identityMgr.hasIdentity()) identityMgr else null, callId)

        if (isRelayMode) {
            Log.d(TAG, "Starting relay call to $remoteIp:$remotePort room=$relayRoom name=$relayName")
            statsTextView.text = "Relay via $remoteIp..."
            manager.startRelay(remoteIp, remotePort, relayRoom, relayName, localPort)
        } else if (isListeningMode) {
            val listenPort = if (localPort > 0) localPort else 50000
            Log.d(TAG, "Starting listen on port $listenPort quality=$qualityPreset")
            statsTextView.text = "Listening on port $listenPort..."
            manager.startListen(listenPort)
        } else {
            Log.d(TAG, "Starting call to $remoteIp:$remotePort quality=$qualityPreset")
            statsTextView.text = "Connecting to $remoteIp..."
            manager.startCall(remoteIp, remotePort, localPort)
        }

        /* A resize hands the view a new surface, and whoever was drawing on
         * the old one is drawing nowhere from that moment. Both callbacks
         * report it, which is why a call with two peers of different shapes
         * used to lose its picture as soon as the second one announced. */
        remoteSurfaceView.surfaceTextureListener =
            object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: android.graphics.SurfaceTexture, w: Int, h: Int
                ) {
                    mainSurface?.release()
                    mainSurface = Surface(st)
                    manager.setMainSurface(mainSurface)
                }

                /* A resize is only a resize here: the texture survives it,
                 * so the decoder attached to it keeps running. */
                override fun onSurfaceTextureSizeChanged(
                    st: android.graphics.SurfaceTexture, w: Int, h: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(
                    st: android.graphics.SurfaceTexture
                ): Boolean {
                    manager.setMainSurface(null)
                    mainSurface?.release()
                    mainSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
            }

        remoteSurfaceView.surfaceTexture?.let {
            mainSurface?.release()
            mainSurface = Surface(it)
            manager.setMainSurface(mainSurface)
        }

        // Start camera
        startCamera(preset)
    }

    /**
     * Letterbox a participant's picture inside its box.
     *
     * A codec fills whatever surface it is handed, so a full-screen portrait
     * view stretches a landscape webcam across it. Nothing in the view
     * hierarchy does this for us: neither SurfaceView nor TextureView has a
     * scale type.
     *
     * @param key identifies the view, so an unchanged shape is not re-applied
     */
    /**
     * Letterbox a participant's picture inside its view.
     *
     * A codec fills whatever surface it is handed, so a landscape webcam in a
     * portrait view is stretched across it unless something intervenes.
     * Neither SurfaceView nor TextureView has a scale type, so the shape has
     * to be imposed here.
     *
     * Done with a transform rather than by resizing the view. Resizing is
     * what a SurfaceView cannot survive - the framework releases the codec
     * bound to the surface it destroys - and a TextureView turns out to be no
     * happier about it. The transform leaves the view's bounds and its
     * SurfaceTexture alone, so the decoder never notices.
     *
     * Applied from a layout listener, because the first participant update
     * arrives before the view has been measured and a transform computed
     * against zero, or against an intermediate measurement, is wrong for the
     * rest of the call.
     */
    private fun fitToAspect(key: Int, tv: android.view.TextureView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        pendingAspect[key] = vw to vh

        if (!layoutHooked.contains(key)) {
            layoutHooked.add(key)
            tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                applyAspect(key, v as android.view.TextureView)
            }
        }
        applyAspect(key, tv)
    }

    private fun applyAspect(key: Int, tv: android.view.TextureView) {
        val size = pendingAspect[key] ?: return
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) return

        val aspect = size.first.toFloat() / size.second.toFloat()
        val boxAspect = w.toFloat() / h.toFloat()

        val stamp = "$w:$h:${"%.3f".format(aspect)}"
        if (appliedFit[key] == stamp) return
        appliedFit[key] = stamp

        val sx: Float
        val sy: Float
        if (aspect > boxAspect) {
            sx = 1f
            sy = boxAspect / aspect
        } else {
            sx = aspect / boxAspect
            sy = 1f
        }

        val m = android.graphics.Matrix()
        m.setScale(sx, sy, w / 2f, h / 2f)
        tv.setTransform(m)
        tv.invalidate()
    }

    /**
     * Redraw the strip for the current participants.
     *
     * The person on the big view keeps a cell too, marked rather than
     * carrying video: they are already on screen, and a second decoder for
     * the same stream buys nothing but heat. Everyone else gets a cell with
     * their own picture in it.
     */
    private fun updateParticipants(participants: List<VideoCallManager.Participant>) {
        val manager = videoCallManager ?: return

        val main = participants.firstOrNull { it.main }

        // The big view, shaped to whoever is on it. Posted when the container
        // has not been measured yet, which is the case on the first update.
        if (main != null && main.width > 0) {
            fitToAspect(-1, remoteSurfaceView, main.width, main.height)
        }
        val wanted = main?.let { if (it.pinned) "\uD83D\uDCCC ${it.name}" else it.name }
        if (wanted != captionText) {
            captionText = wanted
            if (wanted != null) {
                mainCaption.text = wanted
                mainCaption.visibility = View.VISIBLE
            } else {
                mainCaption.visibility = View.GONE
            }
        }

        // Cells for people who have left.
        val present = participants.map { it.slot }.toSet()
        for (slot in stripCells.keys.toList()) {
            if (present.contains(slot)) continue
            stripCells.remove(slot)?.let { strip.removeView(it) }
            cellText.remove(slot)
            cellColor.remove(slot)
            pendingAspect.remove(slot)
            appliedFit.remove(slot)
            layoutHooked.remove(slot)
            thumbSurfaces.remove(slot)?.release()
            manager.setThumbSurface(slot, null)
        }

        /* Shown as soon as anybody is here and never hidden again while the
         * call lasts. Toggling it destroys every cell's surface and takes the
         * decoders bound to them with it, which with people joining one after
         * another meant the strip spent the call rebuilding itself instead of
         * showing anyone. */
        if (participants.isNotEmpty() && stripScroll.visibility != View.VISIBLE) {
            stripScroll.visibility = View.VISIBLE
        }

        for (p in participants) {
            val existing = stripCells[p.slot]
            val cell = existing ?: layoutInflater
                .inflate(R.layout.item_video_thumb, strip, false)
                .also { view ->
                    stripCells[p.slot] = view
                    strip.addView(view)
                    view.setOnClickListener { manager.togglePin(p.slot) }

                    val sv = view.findViewById<android.view.TextureView>(R.id.thumbSurface)
                    val slot = p.slot
                    sv.surfaceTextureListener =
                        object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) {
                                thumbSurfaces.remove(slot)?.release()
                                val surf = Surface(st)
                                thumbSurfaces[slot] = surf
                                manager.setThumbSurface(slot, surf)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(
                                st: android.graphics.SurfaceTexture
                            ): Boolean {
                                manager.setThumbSurface(slot, null)
                                thumbSurfaces.remove(slot)?.release()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(
                                st: android.graphics.SurfaceTexture
                            ) = Unit
                        }
                }

            val text = if (p.pinned) "\uD83D\uDCCC ${p.name}" else p.name
            if (cellText[p.slot] != text) {
                cellText[p.slot] = text
                cell.findViewById<TextView>(R.id.thumbName).text = text
            }

            // Speaking is marked on the cell rather than only by who is big,
            // so the strip still says who is talking while somebody is pinned.
            val color = when {
                p.pinned -> 0xFFFFC107.toInt()    // chosen by the user
                p.main -> 0xFF2196F3.toInt()      // on the big view
                p.speaking -> 0xFF4CAF50.toInt()  // talking
                else -> 0xFF303030.toInt()
            }
            if (cellColor[p.slot] != color) {
                cellColor[p.slot] = color
                cell.findViewById<View>(R.id.thumbFrame).setBackgroundColor(color)
            }

            // Same treatment for the cell: a portrait phone and a landscape
            // webcam do not both fit a fixed box without one of them being
            // squashed to do it.
            if (p.width > 0) {
                fitToAspect(
                    p.slot,
                    cell.findViewById(R.id.thumbSurface),
                    p.width,
                    p.height,
                )
            }

            /* The person on the big view has no second copy of themselves
             * in the strip - one decoder per participant, and it is drawing
             * on the big view. Their cell is marked instead. Alpha would have
             * been the obvious way to say so and does nothing at all to a
             * SurfaceView, whose layer the view hierarchy does not compose. */
        }
    }

    private fun startCamera(preset: VideoQualityPreset) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(localPreview.surfaceProvider)
            }

            val cameraSelector = if (isFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(preset.width, preset.height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val manager = videoCallManager
                if (manager != null && encoderAvailable) {
                    val planes = imageProxy.planes
                    if (planes.size >= 3) {
                        manager.sendVideoFrame(
                            planes[0].buffer, planes[0].rowStride,
                            planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                            planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
                            imageProxy.width, imageProxy.height
                        )
                    }
                }
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                // Get sensor rotation for correct video orientation
                val rotation = camera.cameraInfo.sensorRotationDegrees
                Log.d(TAG, "Camera sensor rotation: $rotation, front=$isFrontCamera")

                // Try to start encoder with rotation (may fail if VP8 not available)
                try {
                    videoCallManager?.startSending(rotation)
                    encoderAvailable = true
                    Log.d(TAG, "Camera + encoder started (front=$isFrontCamera rotation=$rotation)")
                } catch (e: Exception) {
                    encoderAvailable = false
                    Log.w(TAG, "VP8 encoder not available - receive-only mode: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera error", e)
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun endCall() {
        if (callEnded) return
        callEnded = true
        videoCallManager?.endCall()
        videoCallManager = null
        finish()
    }

    private fun toggleCamera() {
        isFrontCamera = !isFrontCamera
        val preset = when (qualityPreset) {
            "low" -> VideoQualityPreset.LOW
            "high" -> VideoQualityPreset.HIGH
            else -> VideoQualityPreset.MEDIUM
        }
        startCamera(preset)
    }

    private fun toggleMute() {
        isMuted = !isMuted
        videoCallManager?.isMuted = isMuted
        toggleMuteButton.text = if (isMuted) "Unmute" else "Mute"
    }

    // --- VideoCallListener ---

    override fun onCallStarted() {
        runOnUiThread { statsTextView.text = "Connecting..." }
    }

    override fun onCallEnded() {
        runOnUiThread {
            if (!callEnded) {
                callEnded = true
                videoCallManager = null
                finish()
            }
        }
    }

    override fun onCallError(error: String) {
        runOnUiThread {
            Log.e(TAG, "Call error: $error")
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            if (!callEnded) {
                callEnded = true
                videoCallManager = null
                finish()
            }
        }
    }

    override fun onConnected(peerWidth: Int, peerHeight: Int, peerFps: Int) {
        runOnUiThread {
            val mode = if (encoderAvailable) "Connected" else "Connected (receive only)"
            statsTextView.text = "$mode ${peerWidth}x${peerHeight}@${peerFps}"
            Log.d(TAG, "Connected! Peer: ${peerWidth}x${peerHeight}@${peerFps}, encoder=$encoderAvailable")

            // Resize SurfaceView to preserve aspect ratio
            if (peerWidth > 0 && peerHeight > 0) {
                fitSurfaceToAspectRatio(peerWidth, peerHeight)
            }
        }
    }

    private fun fitSurfaceToAspectRatio(videoWidth: Int, videoHeight: Int) {
        val parent = remoteSurfaceView.parent as? android.view.ViewGroup ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW == 0 || parentH == 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()

        val newW: Int
        val newH: Int
        if (videoAspect > parentAspect) {
            // Video is wider — fit to width, letterbox top/bottom
            newW = parentW
            newH = (parentW / videoAspect).toInt()
        } else {
            // Video is taller — fit to height, pillarbox left/right
            newH = parentH
            newW = (parentH * videoAspect).toInt()
        }

        val lp = remoteSurfaceView.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.width = newW
        lp.height = newH
        lp.gravity = android.view.Gravity.CENTER
        remoteSurfaceView.layoutParams = lp
        Log.d(TAG, "SurfaceView resized: ${newW}x${newH} (video ${videoWidth}x${videoHeight}, parent ${parentW}x${parentH})")
    }

    override fun onRemoteVideoFrame(data: ByteArray, width: Int, height: Int) {
        // Handled by SurfaceView directly via VP8 decoder
    }

    override fun onParticipants(participants: List<VideoCallManager.Participant>) {
        runOnUiThread { updateParticipants(participants) }
    }

    override fun onStatsUpdated(packetsReceived: Int, packetsLost: Int, rttMs: Int) {
        runOnUiThread {
            val lossPercent = if (packetsReceived > 0)
                (packetsLost * 100) / (packetsReceived + packetsLost) else 0
            statsTextView.text = "RTT: ${rttMs}ms | Loss: $lossPercent%"
            // Color: green < 100ms, yellow 100-300ms, red > 300ms
            val color = when {
                rttMs < 100 -> 0xFF00DD00.toInt()   // green
                rttMs < 300 -> 0xFFFFC800.toInt()   // yellow
                else -> 0xFFFF2828.toInt()           // red
            }
            statsTextView.setTextColor(color)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!callEnded) {
            callEnded = true
            videoCallManager?.endCall()
            videoCallManager = null
        }
    }
}
