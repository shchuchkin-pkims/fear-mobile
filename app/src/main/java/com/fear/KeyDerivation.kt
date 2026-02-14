package com.fear

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Key derivation for FEAR video/audio calls using BLAKE2b KDF.
 * Compatible with desktop key_derivation.h implementation.
 *
 * Master key -> audio_key (subkey_id=1, ctx="fearaudi")
 * Master key -> video_key (subkey_id=2, ctx="fearvide")
 */
object KeyDerivation {

    private val ls: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    private const val SUBKEY_ID_AUDIO = 1L
    private const val SUBKEY_ID_VIDEO = 2L
    private const val CTX_AUDIO = "fearaudi"  // 8 bytes
    private const val CTX_VIDEO = "fearvide"  // 8 bytes

    /**
     * Derive audio sub-key from master key using libsodium KDF.
     */
    fun deriveAudioKey(masterKey: ByteArray): ByteArray {
        require(masterKey.size == 32) { "Master key must be 32 bytes" }
        val subkey = ByteArray(32)
        ls.cryptoKdfDeriveFromKey(subkey, 32, SUBKEY_ID_AUDIO, CTX_AUDIO.toByteArray(Charsets.UTF_8), masterKey)
        return subkey
    }

    /**
     * Derive video sub-key from master key using libsodium KDF.
     */
    fun deriveVideoKey(masterKey: ByteArray): ByteArray {
        require(masterKey.size == 32) { "Master key must be 32 bytes" }
        val subkey = ByteArray(32)
        ls.cryptoKdfDeriveFromKey(subkey, 32, SUBKEY_ID_VIDEO, CTX_VIDEO.toByteArray(Charsets.UTF_8), masterKey)
        return subkey
    }
}
