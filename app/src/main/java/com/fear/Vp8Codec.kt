package com.fear

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class Vp8Encoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    companion object {
        private const val TAG = "Vp8Encoder"
        private const val MIME_VP8 = "video/x-vnd.on2.vp8"
        private const val MIME_VP8_ALT = "video/x-vp8"

        private val SW_ENCODER_NAMES = listOf(
            "c2.android.vp8.encoder",
            "OMX.google.vpx.encoder",
            "c2.google.vp8.encoder"
        )
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameCount = 0L

    private fun makeFormat(mime: String): MediaFormat {
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
    }

    fun start() {
        codec = tryCreateEncoder(MIME_VP8)
            ?: tryCreateEncoder(MIME_VP8_ALT)
            ?: tryCreateByName()

        if (codec == null) {
            Log.e(TAG, "No VP8 encoder available on this device")
            Log.e(TAG, "Available encoders: ${listAvailableEncoders()}")
            throw IllegalStateException("VP8 encoder not available")
        }

        Log.d(TAG, "VP8 encoder started: ${codec?.name} ${width}x${height}@${fps}")
    }

    private fun tryCreateEncoder(mime: String): MediaCodec? {
        return try {
            MediaCodec.createEncoderByType(mime).also {
                it.configure(makeFormat(mime), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
                Log.d(TAG, "Created encoder by type: $mime -> ${it.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "createEncoderByType($mime) failed: ${e.message}")
            null
        }
    }

    private fun tryCreateByName(): MediaCodec? {
        val mimes = listOf(MIME_VP8, MIME_VP8_ALT)
        for (mime in mimes) {
            for (name in SW_ENCODER_NAMES) {
                try {
                    return MediaCodec.createByCodecName(name).also {
                        it.configure(makeFormat(mime), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        it.start()
                        Log.d(TAG, "Created encoder by name: $name (mime=$mime)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "createByCodecName($name, $mime) failed: ${e.message}")
                }
            }
        }
        return null
    }

    private fun listAvailableEncoders(): String {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        return list.codecInfos
            .filter { it.isEncoder }
            .flatMap { info -> info.supportedTypes.map { "${info.name} ($it)" } }
            .filter { it.contains("vp8", ignoreCase = true) || it.contains("vp9", ignoreCase = true) }
            .joinToString(", ")
            .ifEmpty { "none with VP8/VP9" }
    }

    /**
     * Encode directly from camera plane ByteBuffers with rotation + scaling.
     * Copies plane-by-plane from source (camera) to encoder's getInputImage(),
     * applying rotation and downscaling during the copy.
     *
     * The camera may output at a higher resolution than the encoder expects,
     * so we scale from the full camera image to the encoder dimensions.
     */
    fun encodePlanes(
        srcY: ByteBuffer, srcYStride: Int,
        srcU: ByteBuffer, srcUStride: Int, srcUPixelStride: Int,
        srcV: ByteBuffer, srcVStride: Int, srcVPixelStride: Int,
        srcWidth: Int, srcHeight: Int,
        rotationDegrees: Int,
        presentationTimeUs: Long
    ): ByteArray? {
        val c = codec ?: return null

        val rotated = rotationDegrees == 90 || rotationDegrees == 270

        if (frameCount == 0L) {
            Log.d(TAG, "First frame: camera=${srcWidth}x${srcHeight} encoder=${width}x${height} rotation=$rotationDegrees")
            Log.d(TAG, "Camera Y stride=$srcYStride, U stride=$srcUStride pixel=$srcUPixelStride")
            val virtW = if (rotated) srcHeight else srcWidth
            val virtH = if (rotated) srcWidth else srcHeight
            Log.d(TAG, "Virtual (rotated) source: ${virtW}x${virtH} -> encoder ${width}x${height}")
        }

        val inputIndex = c.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val image = c.getInputImage(inputIndex)
            if (image != null) {
                if (frameCount == 0L) {
                    val dstYStride = image.planes[0].rowStride
                    val dstUStride = image.planes[1].rowStride
                    val dstUPixel = image.planes[1].pixelStride
                    Log.d(TAG, "Encoder image Y stride=$dstYStride, U stride=$dstUStride pixel=$dstUPixel")
                }

                val dstYBuf = image.planes[0].buffer
                val dstYStride = image.planes[0].rowStride
                val dstUBuf = image.planes[1].buffer
                val dstUStride = image.planes[1].rowStride
                val dstUPixel = image.planes[1].pixelStride
                val dstVBuf = image.planes[2].buffer
                val dstVStride = image.planes[2].rowStride
                val dstVPixel = image.planes[2].pixelStride

                // Virtual (post-rotation) source dimensions
                val virtW = if (rotated) srcHeight else srcWidth
                val virtH = if (rotated) srcWidth else srcHeight

                // Copy Y plane with rotation + scaling
                for (dr in 0 until height) {
                    for (dc in 0 until width) {
                        // Scale: map encoder pixel to virtual (rotated) source pixel
                        val vc = dc * virtW / width
                        val vr = dr * virtH / height
                        // Map virtual back to original source coords
                        val sc: Int
                        val sr: Int
                        when (rotationDegrees) {
                            90 -> { sc = vr; sr = srcHeight - 1 - vc }
                            180 -> { sc = srcWidth - 1 - vc; sr = srcHeight - 1 - vr }
                            270 -> { sc = srcWidth - 1 - vr; sr = vc }
                            else -> { sc = vc; sr = vr }
                        }
                        dstYBuf.put(dr * dstYStride + dc,
                            srcY.get(sr * srcYStride + sc))
                    }
                }

                // Copy UV planes with rotation + scaling (half resolution)
                val uvDstW = width / 2
                val uvDstH = height / 2
                val uvSrcW = srcWidth / 2
                val uvSrcH = srcHeight / 2
                val uvVirtW = if (rotated) uvSrcH else uvSrcW
                val uvVirtH = if (rotated) uvSrcW else uvSrcH

                for (dr in 0 until uvDstH) {
                    for (dc in 0 until uvDstW) {
                        val vc = dc * uvVirtW / uvDstW
                        val vr = dr * uvVirtH / uvDstH
                        val sc: Int
                        val sr: Int
                        when (rotationDegrees) {
                            90 -> { sc = vr; sr = uvSrcH - 1 - vc }
                            180 -> { sc = uvSrcW - 1 - vc; sr = uvSrcH - 1 - vr }
                            270 -> { sc = uvSrcW - 1 - vr; sr = vc }
                            else -> { sc = vc; sr = vr }
                        }
                        val srcIdx = sr * srcUStride + sc * srcUPixelStride
                        dstUBuf.put(dr * dstUStride + dc * dstUPixel, srcU.get(srcIdx))
                        dstVBuf.put(dr * dstVStride + dc * dstVPixel, srcV.get(srcIdx))
                    }
                }
            } else {
                Log.w(TAG, "getInputImage returned null")
                c.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                frameCount++
                return null
            }
            c.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, presentationTimeUs, 0)
        }

        frameCount++

        val outputIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
        if (outputIndex >= 0) {
            val outputBuffer = c.getOutputBuffer(outputIndex) ?: return null
            val data = ByteArray(bufferInfo.size)
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.get(data)
            c.releaseOutputBuffer(outputIndex, false)
            return data
        }

        return null
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }
}

class Vp8Decoder {
    companion object {
        private const val TAG = "Vp8Decoder"
        private const val MIME_VP8 = "video/x-vnd.on2.vp8"
        private const val MIME_VP8_ALT = "video/x-vp8"

        private val SW_DECODER_NAMES = listOf(
            "c2.android.vp8.decoder",
            "OMX.google.vpx.decoder",
            "c2.google.vp8.decoder"
        )
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var decoderSurface: Surface? = null
    private var decoderWidth = 0
    private var decoderHeight = 0
    @Volatile private var needsKeyframe = false

    fun start(surface: Surface, width: Int, height: Int) {
        decoderSurface = surface
        decoderWidth = width
        decoderHeight = height
        codec = tryCreateDecoder(MIME_VP8, surface, width, height)
            ?: tryCreateDecoder(MIME_VP8_ALT, surface, width, height)
            ?: tryCreateByName(surface, width, height)

        if (codec == null) {
            Log.e(TAG, "No VP8 decoder available on this device")
            throw IllegalStateException("VP8 decoder not available")
        }

        Log.d(TAG, "VP8 decoder started: ${codec?.name}")
    }

    private fun tryCreateDecoder(mime: String, surface: Surface, width: Int, height: Int): MediaCodec? {
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, surface, null, 0)
                it.start()
                Log.d(TAG, "Created decoder by type: $mime -> ${it.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "createDecoderByType($mime) failed: ${e.message}")
            null
        }
    }

    private fun tryCreateByName(surface: Surface, width: Int, height: Int): MediaCodec? {
        for (name in SW_DECODER_NAMES) {
            try {
                val format = MediaFormat.createVideoFormat(MIME_VP8, width, height)
                return MediaCodec.createByCodecName(name).also {
                    it.configure(format, surface, null, 0)
                    it.start()
                    Log.d(TAG, "Created decoder by name: $name")
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun decode(data: ByteArray, presentationTimeUs: Long) {
        val c = codec ?: return

        // VP8 keyframe: bit 0 of first byte is 0
        val isKeyframe = data.isNotEmpty() && (data[0].toInt() and 0x01) == 0

        // After decoder recreation, skip P-frames until we get a keyframe
        if (needsKeyframe) {
            if (!isKeyframe) return
            Log.d(TAG, "Got keyframe after decoder recreation, resuming")
            needsKeyframe = false
        }

        try {
            val inputIndex = c.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = c.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data)
                c.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
            }

            var outputIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                c.releaseOutputBuffer(outputIndex, true)
                outputIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder error, recreating: ${e.message}")
            recreateDecoder()
        }
    }

    private fun recreateDecoder() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        val surface = decoderSurface ?: return
        codec = tryCreateDecoder(MIME_VP8, surface, decoderWidth, decoderHeight)
            ?: tryCreateDecoder(MIME_VP8_ALT, surface, decoderWidth, decoderHeight)
            ?: tryCreateByName(surface, decoderWidth, decoderHeight)
        if (codec != null) {
            Log.d(TAG, "Decoder recreated: ${codec?.name}, waiting for keyframe")
            needsKeyframe = true
        } else {
            Log.e(TAG, "Failed to recreate decoder")
        }
    }

    fun flush() {
        try {
            codec?.flush()
            codec?.start()
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }
}

data class VideoQualityPreset(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    companion object {
        val LOW = VideoQualityPreset(320, 240, 15, 200)
        val MEDIUM = VideoQualityPreset(640, 480, 25, 800)
        val HIGH = VideoQualityPreset(1280, 720, 30, 1500)
    }
}
