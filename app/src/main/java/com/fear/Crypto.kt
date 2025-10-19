package com.fear

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val random = SecureRandom()

    // AES-256-GCM constants
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val GCM_TAG_LENGTH = 128 // bits
    private const val GCM_IV_LENGTH = 12   // bytes

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(GCM_IV_LENGTH)
        random.nextBytes(nonce)
        return nonce
    }

    fun encrypt(
        plaintext: ByteArray,
        additionalData: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            // Update AAD before encryption
            if (additionalData.isNotEmpty()) {
                cipher.updateAAD(additionalData)
            }

            val ciphertext = cipher.doFinal(plaintext)
            ciphertext
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun decrypt(
        ciphertext: ByteArray,
        additionalData: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            // Update AAD before decryption
            if (additionalData.isNotEmpty()) {
                cipher.updateAAD(additionalData)
            }

            val plaintext = cipher.doFinal(ciphertext)
            plaintext
        } catch (e: Exception) {
            // Silently ignore decryption errors - they happen when wrong key or corrupted packet
            null
        }
    }

    fun generateKey(): ByteArray {
        val key = ByteArray(32) // 256-bit AES key
        random.nextBytes(key)
        return key
    }
}