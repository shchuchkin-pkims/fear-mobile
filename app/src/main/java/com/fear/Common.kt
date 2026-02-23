package com.fear

import android.util.Base64
import java.net.Socket
import java.util.zip.CRC32

object Common {
    const val MAX_ROOM = 256
    const val MAX_NAME = 256
    const val MAX_FILENAME = 1024
    const val MAX_FRAME = 65536
    const val FILE_CHUNK_SIZE: Long = 8192L
    const val DEFAULT_PORT = 8888

    // Message types matching C version (v0.4.3)
    const val MSG_TYPE_TEXT: Byte = 0
    const val MSG_TYPE_FILE_START: Byte = 1
    const val MSG_TYPE_FILE_CHUNK: Byte = 2
    const val MSG_TYPE_FILE_END: Byte = 3
    const val MSG_TYPE_USER_LIST: Byte = 4
    const val MSG_TYPE_SIGNED_TEXT: Byte = 5
    const val MSG_TYPE_SIGNED_FILE_START: Byte = 6
    const val MSG_TYPE_SIGNED_FILE_CHUNK: Byte = 7
    const val MSG_TYPE_SIGNED_FILE_END: Byte = 8
    const val MSG_TYPE_IDENTITY_ANNOUNCE: Byte = 9

    // Identity constants (Ed25519)
    const val IDENTITY_PK_BYTES = 32
    const val IDENTITY_SK_BYTES = 64
    const val IDENTITY_SIG_BYTES = 64

    // Crypto constants for AES-256-GCM
    const val CRYPTO_AEAD_AES256GCM_KEYBYTES = 32
    const val CRYPTO_AEAD_AES256GCM_NPUBBYTES = 12
    const val CRYPTO_AEAD_AES256GCM_ABYTES = 16

    // Для совместимости с оригинальным кодом
    const val CRYPTO_AEAD_XCHACHA20POLY1305_IETF_KEYBYTES = CRYPTO_AEAD_AES256GCM_KEYBYTES
    const val CRYPTO_AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES = CRYPTO_AEAD_AES256GCM_NPUBBYTES
    const val CRYPTO_AEAD_XCHACHA20POLY1305_IETF_ABYTES = CRYPTO_AEAD_AES256GCM_ABYTES

    // Audio call constants matching PC version
    const val AUDIO_SAMPLE_RATE = 48000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_FRAME_MS = 20
    const val AUDIO_FRAME_SAMPLES = ((AUDIO_SAMPLE_RATE / 1000) * AUDIO_FRAME_MS) // 960
    const val AUDIO_OPUS_BITRATE = 24000
    const val AUDIO_MAX_OPUS_BYTES = 1275
    const val AUDIO_PCM_BYTES_PER_FRAME = (AUDIO_FRAME_SAMPLES * 2 * AUDIO_CHANNELS) // int16_t * samples * channels
    const val AC_OPUS_BITRATE = AUDIO_OPUS_BITRATE // Alias for consistency

    // Audio/video protocol packet types
    const val PKT_VER_AUDIO: Byte = 0x01
    const val PKT_TYPE_VIDEO_FRAG: Byte = 0x02
    const val PKT_TYPE_STATS: Byte = 0x04
    const val PKT_VER_HELLO: Byte = 0x7F

    // Video HELLO flags
    const val HELLO_FLAG_VIDEO: Byte = 0x01
    const val HELLO_FLAG_AUDIO: Byte = 0x02
    const val HELLO_FLAG_IDENTITY: Byte = 0x04

    // Video fragment constants
    const val FRAG_MAX_PAYLOAD = 1200
    const val FRAG_HEADER_SIZE = 12
    const val FRAG_MAX_PER_FRAME = 128
    const val FRAG_TIMEOUT_MS = 500L
    const val FRAG_MAX_PENDING = 8

    // Audio call nonce configuration (AES-GCM)
    const val AUDIO_NONCE_PREFIX_LEN = 4
    const val AUDIO_AEAD_NONCE_LEN = CRYPTO_AEAD_AES256GCM_NPUBBYTES // 12 bytes
    const val AUDIO_AEAD_KEY_LEN = CRYPTO_AEAD_AES256GCM_KEYBYTES   // 32 bytes
    const val AUDIO_AEAD_ABYTES = CRYPTO_AEAD_AES256GCM_ABYTES      // 16 bytes

    // Audio network
    const val AUDIO_UDP_RECV_BUFSIZE = 1500
    const val AUDIO_PLAYOUT_BUFFER_FRAMES = 6

    // Audio call message types for signaling over TCP
    const val MSG_TYPE_AUDIO_CALL_REQUEST: Byte = 10
    const val MSG_TYPE_AUDIO_CALL_ACCEPT: Byte = 11
    const val MSG_TYPE_AUDIO_CALL_REJECT: Byte = 12
    const val MSG_TYPE_AUDIO_CALL_END: Byte = 13
    const val MSG_TYPE_AUDIO_UDP_INFO: Byte = 14

    // ECDH key exchange message types
    const val MSG_TYPE_KEY_REQUEST: Byte = 15
    const val MSG_TYPE_KEY_RESPONSE: Byte = 16
    const val MSG_TYPE_MEDIA_RELAY: Byte = 17

    // crypto_box constants (X25519 + XSalsa20-Poly1305)
    const val CRYPTO_BOX_PUBLICKEYBYTES = 32
    const val CRYPTO_BOX_SECRETKEYBYTES = 32
    const val CRYPTO_BOX_NONCEBYTES = 24
    const val CRYPTO_BOX_MACBYTES = 16

    fun readUInt16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun recvAll(socket: Socket, buffer: ByteArray, length: Int): Boolean {
        var received = 0
        while (received < length) {
            val count = socket.getInputStream().read(buffer, received, length - received)
            if (count <= 0) return false
            received += count
        }
        return true
    }

    fun sendAll(socket: Socket, data: ByteArray): Boolean {
        return try {
            socket.getOutputStream().write(data)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun base64Encode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun base64Decode(data: String): ByteArray? {
        return try {
            Base64.decode(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    fun crc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }
}