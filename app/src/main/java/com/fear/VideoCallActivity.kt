package com.fear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import java.util.concurrent.Executors

/**
 * Full-screen video call activity.
 * Remote video on SurfaceView, local camera preview in corner.
 */
class VideoCallActivity : AppCompatActivity(), VideoCallManager.VideoCallListener {

    companion object {
        private const val TAG = "VideoCallActivity"
        const val EXTRA_REMOTE_IP = "remote_ip"
        const val EXTRA_REMOTE_PORT = "remote_port"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_ENCRYPTION_KEY = "encryption_key"
        const val EXTRA_QUALITY = "quality"
        private const val PERMISSION_CODE = 200
    }

    private lateinit var remoteSurfaceView: SurfaceView
    private lateinit var localPreview: PreviewView
    private lateinit var endCallButton: Button
    private lateinit var toggleCameraButton: Button
    private lateinit var toggleMuteButton: Button
    private lateinit var statsTextView: TextView

    private var videoCallManager: VideoCallManager? = null
    private var isFrontCamera = true
    private var isMuted = false
    private var encoderAvailable = false

    private var remoteIp = ""
    private var remotePort = 0
    private var localPort = 0
    private var encryptionKeyHex = ""
    private var qualityPreset = "medium"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_call)

        remoteIp = intent.getStringExtra(EXTRA_REMOTE_IP) ?: ""
        remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, 50000)
        localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0)
        encryptionKeyHex = intent.getStringExtra(EXTRA_ENCRYPTION_KEY) ?: ""
        qualityPreset = intent.getStringExtra(EXTRA_QUALITY) ?: "medium"

        Log.d(TAG, "onCreate: remote=$remoteIp:$remotePort keyLen=${encryptionKeyHex.length} quality=$qualityPreset")

        remoteSurfaceView = findViewById(R.id.remoteSurfaceView)
        localPreview = findViewById(R.id.localPreview)
        endCallButton = findViewById(R.id.endCallButton)
        toggleCameraButton = findViewById(R.id.toggleCameraButton)
        toggleMuteButton = findViewById(R.id.toggleMuteButton)
        statsTextView = findViewById(R.id.statsTextView)

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
        if (remoteIp.isEmpty()) {
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

        Log.d(TAG, "Starting call to $remoteIp:$remotePort quality=$qualityPreset")

        val manager = VideoCallManager(this, this)
        videoCallManager = manager

        val identityMgr = IdentityManager(this)
        manager.initialize(keyBytes, preset, if (identityMgr.hasIdentity()) identityMgr else null)

        statsTextView.text = "Connecting to $remoteIp..."

        // Start network (HELLO handshake) immediately — don't wait for surface
        manager.startCall(remoteIp, remotePort, localPort)

        // Set up decoder when surface is ready
        remoteSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created, attaching decoder")
                manager.attachDecoderSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        // Also check if surface already exists
        if (remoteSurfaceView.holder.surface.isValid) {
            Log.d(TAG, "Surface already valid, attaching decoder immediately")
            manager.attachDecoderSurface(remoteSurfaceView.holder.surface)
        }

        // Start camera
        startCamera(preset)
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
        videoCallManager?.endCall()
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
        runOnUiThread { finish() }
    }

    override fun onCallError(error: String) {
        runOnUiThread {
            Log.e(TAG, "Call error: $error")
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            finish()
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

    override fun onStatsUpdated(packetsReceived: Int, packetsLost: Int, rttMs: Int) {
        runOnUiThread {
            val lossPercent = if (packetsReceived > 0)
                (packetsLost * 100) / (packetsReceived + packetsLost) else 0
            statsTextView.text = "RTT: ${rttMs}ms | Loss: $lossPercent%"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCallManager?.endCall()
    }
}
