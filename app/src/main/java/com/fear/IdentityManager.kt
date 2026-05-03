package com.fear

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Sign
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Ed25519 identity manager for FEAR messenger.
 * Compatible with desktop identity.c on the wire (same PK/SK format),
 * but on Android disk both `identity` and `known_keys` are wrapped by
 * `EncryptedFile` (Android Keystore-backed AES-GCM, see Phase 0 in
 * doc/architecture-decisions.md).
 *
 * Logical record format (after decryption) matches desktop:
 *   identity:    PK:<base64url>\nSK:<base64url>\n
 *   known_keys:  name\tpk_base64\tverified_flag\n  (one per line)
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

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    init {
        migratePlaintextIfNeeded()
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
     * Short fingerprint per §1 of the architecture doc: first 4 bytes of
     * BLAKE2b(identity_pk) as 8 lowercase hex chars. Used to disambiguate
     * users with the same display name (`evgenii#a3b9c1d2`).
     */
    fun fpshort(pk: ByteArray): String {
        val hash = ByteArray(4)
        ls.cryptoGenericHash(hash, 4, pk, pk.size.toLong(), null, 0)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Convenience: name#fpshort or null if no identity. */
    fun shortIdentity(displayName: String): String? {
        val pk = publicKey ?: return null
        return "$displayName#${fpshort(pk)}"
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
                val updated = known + KnownKey(name, pk, false)
                saveAllKnownKeys(updated)
                "new"
            }
            existing.pk.contentEquals(pk) -> if (existing.verified) "verified" else "trusted"
            else -> "changed"
        }
    }

    fun loadKnownKeys(): List<KnownKey> {
        val text = readEncryptedText(knownKeysFile) ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size >= 2) {
                val name = parts[0]
                val pkBytes = Common.base64Decode(parts[1])
                val verified = parts.getOrNull(2) == "1"
                if (pkBytes != null && pkBytes.size == Common.IDENTITY_PK_BYTES) {
                    KnownKey(name, pkBytes, verified)
                } else null
            } else null
        }.toList()
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

    private fun saveAllKnownKeys(keys: List<KnownKey>) {
        val sb = StringBuilder()
        for (key in keys) {
            sb.append("${key.name}\t${Common.base64Encode(key.pk)}\t${if (key.verified) "1" else "0"}\n")
        }
        writeEncryptedText(knownKeysFile, sb.toString())
    }

    // --- Encrypted file I/O ---

    private fun encryptedFile(file: File): EncryptedFile = EncryptedFile.Builder(
        context,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    /**
     * Returns decrypted text or null if file does not exist / cannot be read.
     *
     * If the file exists but decryption fails (e.g. EncryptedFile was written
     * by a previous install whose Tink keyset is now unreachable — common after
     * `adb install -r` if Keystore lost the master key, or if the SharedPrefs
     * holding the keyset got cleared), we delete the stale ciphertext so the
     * caller's `generateIdentity()` path can write a fresh one.
     */
    private fun readEncryptedText(file: File): String? {
        if (!file.exists()) return null
        return try {
            encryptedFile(file).openFileInput().use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                }
                baos.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read encrypted ${file.name}: ${e.message} — discarding stale ciphertext")
            try { file.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Replace contents of `file` with encrypted `text`.
     *
     * EncryptedFile cannot overwrite an existing file (Tink streaming AEAD
     * refuses), so we delete first. We do NOT use a tmp+rename pattern: the
     * Tink keyset is stored in SharedPreferences keyed on the file path, so
     * writing to `identity.tmp` and renaming to `identity` would leave the
     * file encrypted under one keyset while subsequent reads try to use a
     * different one, producing the cryptic "no matching key" error.
     *
     * The write window is unsigned but tiny (~150 bytes) — a partial-write
     * outage corrupts the identity file at worst, recovered on next launch
     * via generateIdentity().
     */
    private fun writeEncryptedText(file: File, text: String): Boolean {
        return try {
            if (file.exists() && !file.delete()) {
                Log.e(TAG, "Could not delete existing ${file.name} before write")
                return false
            }
            encryptedFile(file).openFileOutput().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write encrypted ${file.name}: ${e.message}")
            false
        }
    }

    // --- Identity load/save ---

    private fun loadIdentity() {
        val text = readEncryptedText(identityFile) ?: return
        var pk: ByteArray? = null
        var sk: ByteArray? = null
        for (line in text.lineSequence()) {
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
    }

    private fun saveIdentity(): Boolean {
        val pk = publicKey ?: return false
        val sk = secretKey ?: return false
        val body = "PK:${Common.base64Encode(pk)}\nSK:${Common.base64Encode(sk)}\n"
        return writeEncryptedText(identityFile, body)
    }

    /**
     * Migrate legacy plaintext `identity` / `known_keys` (written by versions
     * <= 0.5.0) to EncryptedFile form. Best effort: if the encrypted file
     * already exists we leave the legacy untouched (shouldn't happen, but
     * harmless). Plaintext is wiped and deleted after successful migration.
     */
    private fun migratePlaintextIfNeeded() {
        migrateOnePlaintextFile(identityFile)
        migrateOnePlaintextFile(knownKeysFile)
    }

    private fun migrateOnePlaintextFile(file: File) {
        if (!file.exists()) return

        // Heuristic: an EncryptedFile starts with the Tink keyset header,
        // never with "PK:" or "<name>\t<base64>". Try to read as plaintext
        // first; if it parses as our legacy text, treat as legacy.
        val raw = try {
            file.readBytes()
        } catch (_: Exception) {
            return
        }

        // Tink streaming AEAD writes a 1-byte version header, but the bytes
        // are non-printable. Plaintext identity always starts with "PK:".
        val isLegacyIdentity = file.name == "identity" &&
            raw.size > 3 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte() && raw[2] == ':'.code.toByte()

        // Plaintext known_keys lines look like name\t<base64> — at least one tab in first 256 bytes,
        // and all bytes printable. EncryptedFile contents are mostly non-printable.
        val isLegacyKnownKeys = file.name == "known_keys" && looksLikePlaintextKnownKeys(raw)

        if (!isLegacyIdentity && !isLegacyKnownKeys) return

        val asString = try { raw.toString(Charsets.UTF_8) } catch (_: Exception) { return }

        // Move legacy file aside so writeEncryptedText can create new one at the same path
        val backup = File(file.parentFile, "${file.name}.legacy")
        if (backup.exists()) backup.delete()
        if (!file.renameTo(backup)) {
            Log.w(TAG, "Migration: could not rename ${file.name} to .legacy — aborting")
            return
        }

        val ok = writeEncryptedText(file, asString)
        if (ok) {
            // Best effort wipe of plaintext copy
            try {
                val len = backup.length().toInt().coerceAtMost(64 * 1024)
                if (len > 0) backup.outputStream().use { it.write(ByteArray(len)) }
            } catch (_: Exception) {}
            backup.delete()
            Log.i(TAG, "Migrated ${file.name} to EncryptedFile")
        } else {
            // Restore on failure so user doesn't lose identity
            backup.renameTo(file)
            Log.e(TAG, "Failed to encrypt ${file.name} — restored from .legacy")
        }
    }

    private fun looksLikePlaintextKnownKeys(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        val sample = raw.copyOfRange(0, raw.size.coerceAtMost(256))
        var sawTab = false
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0x09) sawTab = true
            // printable ASCII or LF — anything else → not plaintext
            if (v != 0x09 && v != 0x0A && (v < 0x20 || v > 0x7E)) return false
        }
        return sawTab
    }

    companion object {
        private const val TAG = "FearIdentity"
    }
}
