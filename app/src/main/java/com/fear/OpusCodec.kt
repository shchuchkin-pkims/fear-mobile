package com.fear

/**
 * OpusCodec wrapper для Android с использованием JNI
 * Совместим с ПК-версией (48kHz, 1 channel, 960 samples/frame, 128kbps)
 */
object OpusCodec {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val FRAME_SIZE = 960 // 20ms at 48kHz
    const val BITRATE = 128000

    // Application type for Opus encoder
    private const val APPLICATION_VOIP = 2048

    init {
        try {
            System.loadLibrary("fear_opus")
            println("OPUS_DEBUG: Native library loaded successfully")
        } catch (e: Exception) {
            println("OPUS_DEBUG: Failed to load native library: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Encoder using native libopus
     */
    class Encoder(private val sampleRate: Int, private val channels: Int, private val bitrate: Int) {
        private var nativeHandle: Long = 0

        init {
            try {
                nativeHandle = nativeCreate(sampleRate, channels, APPLICATION_VOIP)
                if (nativeHandle != 0L) {
                    nativeSetBitrate(nativeHandle, bitrate)
                    println("OPUS_DEBUG: Encoder initialized (native, ${sampleRate}Hz, ${channels}ch, ${bitrate}bps)")
                } else {
                    println("OPUS_DEBUG: Failed to create native encoder")
                }
            } catch (e: Exception) {
                println("OPUS_DEBUG: Encoder initialization exception: ${e.message}")
                e.printStackTrace()
            }
        }

        fun encode(pcmData: ShortArray, frameSize: Int): ByteArray {
            if (nativeHandle == 0L) {
                println("OPUS_DEBUG: encode - encoder not initialized")
                return ByteArray(0)
            }

            if (pcmData.isEmpty() || frameSize <= 0 || frameSize > pcmData.size) {
                println("OPUS_DEBUG: encode - invalid input")
                return ByteArray(0)
            }

            try {
                val outputBuffer = ByteArray(4000) // Max Opus packet size
                val encodedSize = nativeEncode(nativeHandle, pcmData, frameSize, outputBuffer, outputBuffer.size)

                if (encodedSize < 0) {
                    println("OPUS_DEBUG: encode failed with error code: $encodedSize")
                    return ByteArray(0)
                }

                if (encodedSize == 0) {
                    println("OPUS_DEBUG: encode returned 0 bytes")
                    return ByteArray(0)
                }

                return outputBuffer.copyOf(encodedSize)
            } catch (e: Exception) {
                println("OPUS_DEBUG: encode exception: ${e.message}")
                e.printStackTrace()
                return ByteArray(0)
            }
        }

        fun reset() {
            // Native opus encoder doesn't need explicit reset
        }

        fun destroy() {
            try {
                if (nativeHandle != 0L) {
                    nativeDestroy(nativeHandle)
                    nativeHandle = 0
                    println("OPUS_DEBUG: Encoder destroyed")
                }
            } catch (e: Exception) {
                println("OPUS_DEBUG: destroy exception: ${e.message}")
            }
        }

        private external fun nativeCreate(sampleRate: Int, channels: Int, application: Int): Long
        private external fun nativeSetBitrate(handle: Long, bitrate: Int)
        private external fun nativeEncode(handle: Long, pcm: ShortArray, frameSize: Int,
                                          output: ByteArray, maxOutputSize: Int): Int
        private external fun nativeDestroy(handle: Long)
    }

    /**
     * Decoder using native libopus
     */
    class Decoder(private val sampleRate: Int, private val channels: Int) {
        private var nativeHandle: Long = 0

        init {
            try {
                nativeHandle = nativeCreate(sampleRate, channels)
                if (nativeHandle != 0L) {
                    println("OPUS_DEBUG: Decoder initialized (native, ${sampleRate}Hz, ${channels}ch)")
                } else {
                    println("OPUS_DEBUG: Failed to create native decoder")
                }
            } catch (e: Exception) {
                println("OPUS_DEBUG: Decoder initialization exception: ${e.message}")
                e.printStackTrace()
            }
        }

        fun decode(opusData: ByteArray, frameSize: Int): ShortArray {
            if (nativeHandle == 0L) {
                println("OPUS_DEBUG: decode - decoder not initialized, returning silence")
                return ShortArray(frameSize)
            }

            if (opusData.isEmpty()) {
                println("OPUS_DEBUG: decode - empty input, returning silence")
                return ShortArray(frameSize)
            }

            try {
                val outputBuffer = ShortArray(frameSize)
                val decodedSamples = nativeDecode(nativeHandle, opusData, opusData.size,
                                                   outputBuffer, frameSize)

                if (decodedSamples < 0) {
                    println("OPUS_DEBUG: decode failed with error code: $decodedSamples")
                    return ShortArray(frameSize)
                }

                if (decodedSamples == 0) {
                    println("OPUS_DEBUG: decode returned 0 samples")
                    return ShortArray(frameSize)
                }

                // If we got fewer samples than expected, pad with zeros
                if (decodedSamples < frameSize) {
                    val paddedBuffer = ShortArray(frameSize)
                    System.arraycopy(outputBuffer, 0, paddedBuffer, 0, decodedSamples)
                    return paddedBuffer
                }

                return outputBuffer
            } catch (e: Exception) {
                println("OPUS_DEBUG: decode exception: ${e.message}")
                e.printStackTrace()
                return ShortArray(frameSize)
            }
        }

        fun decode(opusData: ByteArray): ShortArray {
            return decode(opusData, FRAME_SIZE)
        }

        fun reset() {
            // Native opus decoder doesn't need explicit reset
        }

        fun destroy() {
            try {
                if (nativeHandle != 0L) {
                    nativeDestroy(nativeHandle)
                    nativeHandle = 0
                    println("OPUS_DEBUG: Decoder destroyed")
                }
            } catch (e: Exception) {
                println("OPUS_DEBUG: destroy exception: ${e.message}")
            }
        }

        private external fun nativeCreate(sampleRate: Int, channels: Int): Long
        private external fun nativeDecode(handle: Long, opusData: ByteArray, opusSize: Int,
                                          output: ShortArray, frameSize: Int): Int
        private external fun nativeDestroy(handle: Long)
    }

    /**
     * Создать энкодер
     */
    fun createEncoder(sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS, bitrate: Int = BITRATE): Encoder {
        return Encoder(sampleRate, channels, bitrate)
    }

    /**
     * Создать декодер
     */
    fun createDecoder(sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): Decoder {
        return Decoder(sampleRate, channels)
    }
}
