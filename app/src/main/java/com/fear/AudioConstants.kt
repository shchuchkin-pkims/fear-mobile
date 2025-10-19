// AudioConstants.kt
package com.fear

object AudioConstants {
    // Audio parameters (compatible with PC version)
    const val AUDIO_SAMPLE_RATE = 48000 // 48 kHz sample rate (same as PC)
    const val AUDIO_CHANNELS = 1 // Mono
    const val AUDIO_FRAME_MS = 20 // 20ms frames
    const val AUDIO_FRAME_SAMPLES = AUDIO_SAMPLE_RATE * AUDIO_FRAME_MS / 1000 // 960 samples
    const val AUDIO_PCM_BYTES_PER_FRAME = AUDIO_FRAME_SAMPLES * 2 // 16-bit PCM = 2 bytes per sample
    
    // Opus codec
    const val AC_OPUS_BITRATE = 24000 // 24 kbps
    const val AUDIO_MAX_OPUS_BYTES = 400 // Maximum Opus packet size
    
    // Network
    const val AUDIO_UDP_RECV_BUFSIZE = 1500 // Standard MTU
    
    // Crypto
    const val AUDIO_NONCE_PREFIX_LEN = 4
    const val AUDIO_AEAD_NONCE_LEN = 12
    const val AUDIO_AEAD_ABYTES = 16 // GCM tag size
    
    // Packet types (compatible with PC version)
    const val PKT_VER_AUDIO: Byte = 0x01  // Audio packet version
    const val PKT_VER_HELLO: Byte = 0x7F  // HELLO handshake packet
}