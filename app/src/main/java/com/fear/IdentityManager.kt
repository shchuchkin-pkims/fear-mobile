package com.fear

import android.content.Context
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Sign
import java.io.File

/**
 * Ed25519 identity manager for FEAR messenger.
 * Compatible with desktop identity.c implementation.
 *
 * Key storage format (matching desktop):
 *   PK:<base64url_no_padding>
 *   SK:<base64url_no_padding>
 *
 * Known keys format:
 *   name\tpk_base64\tverified_flag
 */
class IdentityManager(private val context: Context) {

    private val ls: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    private var publicKey: ByteArray? = null
    private var secretKey: ByteArray? = null

    private val fearDir: File
        get() {
            val dir = File(context.filesDir, ".fear")
            dir.mkdirs()
            return dir
        }

    private val identityFile: File get() = File(fearDir, "identity")
    private val knownKeysFile: File get() = File(fearDir, "known_keys")

    init {
        loadIdentity()
    }

    fun hasIdentity(): Boolean = publicKey != null && secretKey != null

    fun getPublicKey(): ByteArray? = publicKey?.copyOf()

    /**
     * Generate a new Ed25519 keypair and save to disk.
     * Returns true on success.
     */
    fun generateIdentity(): Boolean {
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)

        if (!ls.cryptoSignKeypair(pk, sk)) return false

        publicKey = pk
        secretKey = sk
        return saveIdentity()
    }

    /**
     * Sign a message with our Ed25519 secret key.
     * Returns 64-byte detached signature, or null if no identity.
     */
    fun sign(message: ByteArray): ByteArray? {
        val sk = secretKey ?: return null
        val sig = ByteArray(Sign.ED25519_BYTES)

        val success = ls.cryptoSignDetached(sig, message, message.size.toLong(), sk)
        return if (success) sig else null
    }

    /**
     * Verify a detached Ed25519 signature.
     */
    fun verify(message: ByteArray, signature: ByteArray, pk: ByteArray): Boolean {
        if (signature.size != Sign.ED25519_BYTES || pk.size != Sign.ED25519_PUBLICKEYBYTES) return false
        return ls.cryptoSignVerifyDetached(signature, message, message.size, pk)
    }

    /**
     * Compute BLAKE2b fingerprint of a public key (first 8 bytes).
     * Returns hex string like "ab:cd:ef:01:23:45:67:89".
     */
    fun fingerprint(pk: ByteArray): String {
        val hash = ByteArray(8)
        ls.cryptoGenericHash(hash, 8, pk, pk.size.toLong(), null, 0)
        return hash.joinToString(":") { "%02x".format(it) }
    }

    /**
     * Build the payload for MSG_TYPE_SIGNED_TEXT.
     * Format: [pk(32)][sig(64)][text]
     */
    fun buildSignedTextPayload(text: ByteArray): ByteArray? {
        val pk = publicKey ?: return null
        val sig = sign(text) ?: return null

        val payload = ByteArray(Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES + text.size)
        System.arraycopy(pk, 0, payload, 0, Common.IDENTITY_PK_BYTES)
        System.arraycopy(sig, 0, payload, Common.IDENTITY_PK_BYTES, Common.IDENTITY_SIG_BYTES)
        System.arraycopy(text, 0, payload, Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES, text.size)
        return payload
    }

    /**
     * Build the payload for MSG_TYPE_IDENTITY_ANNOUNCE.
     * Format: [pk(32)][sig_over_name(64)]
     */
    fun buildIdentityAnnouncePayload(name: String): ByteArray? {
        val pk = publicKey ?: return null
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val sig = sign(nameBytes) ?: return null

        val payload = ByteArray(Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES)
        System.arraycopy(pk, 0, payload, 0, Common.IDENTITY_PK_BYTES)
        System.arraycopy(sig, 0, payload, Common.IDENTITY_PK_BYTES, Common.IDENTITY_SIG_BYTES)
        return payload
    }

    // --- TOFU known keys management ---

    data class KnownKey(val name: String, val pk: ByteArray, val verified: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KnownKey) return false
            return name == other.name && pk.contentEquals(other.pk) && verified == other.verified
        }
        override fun hashCode(): Int = name.hashCode() * 31 + pk.contentHashCode()
    }

    /**
     * Check a peer's public key against known keys (TOFU model).
     * Returns: "new" if first time, "verified" if matches and verified,
     * "trusted" if matches but not verified, "changed" if key changed.
     */
    fun checkPeerKey(name: String, pk: ByteArray): String {
        val known = loadKnownKeys()
        val existing = known.find { it.name == name }

        return when {
            existing == null -> {
                // First time seeing this peer — trust on first use
                saveKnownKey(name, pk, false)
                "new"
            }
            existing.pk.contentEquals(pk) -> if (existing.verified) "verified" else "trusted"
            else -> "changed"
        }
    }

    fun loadKnownKeys(): List<KnownKey> {
        if (!knownKeysFile.exists()) return emptyList()

        return knownKeysFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size >= 2) {
                val name = parts[0]
                val pkBytes = Common.base64Decode(parts[1])
                val verified = parts.getOrNull(2) == "1"
                if (pkBytes != null && pkBytes.size == Common.IDENTITY_PK_BYTES) {
                    KnownKey(name, pkBytes, verified)
                } else null
            } else null
        }
    }

    /**
     * Set the verified status for a known key.
     */
    fun setVerified(name: String, pk: ByteArray, verified: Boolean) {
        val keys = loadKnownKeys().toMutableList()
        val idx = keys.indexOfFirst { it.name == name && it.pk.contentEquals(pk) }
        if (idx >= 0) {
            keys[idx] = KnownKey(name, pk, verified)
            saveAllKnownKeys(keys)
        }
    }

    /**
     * Delete a known key entry.
     */
    fun deleteKnownKey(name: String, pk: ByteArray) {
        val keys = loadKnownKeys().toMutableList()
        keys.removeAll { it.name == name && it.pk.contentEquals(pk) }
        saveAllKnownKeys(keys)
    }

    private fun saveKnownKey(name: String, pk: ByteArray, verified: Boolean) {
        val line = "$name\t${Common.base64Encode(pk)}\t${if (verified) "1" else "0"}\n"
        knownKeysFile.appendText(line)
    }

    private fun saveAllKnownKeys(keys: List<KnownKey>) {
        val sb = StringBuilder()
        for (key in keys) {
            sb.append("${key.name}\t${Common.base64Encode(key.pk)}\t${if (key.verified) "1" else "0"}\n")
        }
        knownKeysFile.writeText(sb.toString())
    }

    // --- File I/O ---

    private fun loadIdentity() {
        if (!identityFile.exists()) return

        try {
            val lines = identityFile.readLines()
            var pk: ByteArray? = null
            var sk: ByteArray? = null

            for (line in lines) {
                when {
                    line.startsWith("PK:") -> pk = Common.base64Decode(line.substring(3))
                    line.startsWith("SK:") -> sk = Common.base64Decode(line.substring(3))
                }
            }

            if (pk != null && pk.size == Common.IDENTITY_PK_BYTES &&
                sk != null && sk.size == Common.IDENTITY_SK_BYTES) {
                publicKey = pk
                secretKey = sk
            }
        } catch (_: Exception) {}
    }

    private fun saveIdentity(): Boolean {
        val pk = publicKey ?: return false
        val sk = secretKey ?: return false

        return try {
            identityFile.writeText("PK:${Common.base64Encode(pk)}\nSK:${Common.base64Encode(sk)}\n")
            true
        } catch (_: Exception) {
            false
        }
    }
}
