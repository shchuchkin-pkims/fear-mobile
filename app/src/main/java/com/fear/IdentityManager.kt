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
import com.fear.crypto.RotationBundle

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
     * Deterministic room id for a 1-on-1 chat between two identities.
     * Both sides compute the same value without coordination
     * (Phase B-4, doc §11):
     *
     *   room_id = base64url(BLAKE2b(min(pk_a, pk_b) || max(pk_a, pk_b), 16))
     *
     * 16 raw bytes → 22 base64url chars; safely fits as a room name on the
     * wire and is opaque to onlookers. Prefixed with "dm:" so the UI can
     * tell DMs from user-named group rooms at a glance.
     */
    fun dmRoomId(otherPk: ByteArray): String? = pmRoomId(otherPk)

    /**
     * Детерминированный room_id для личного 1-на-1 чата (Phase B-5+).
     * Префикс "pm:" + base64url(blake2b(min(pk_a,pk_b)||max(pk_a,pk_b),16)).
     * Обе стороны вычисляют одинаковое значение без координации.
     */
    /**
     * Идентификатор личной комнаты - устаревший вывод, только для переноса.
     *
     * Считается из двух открытых ключей и без секрета, а значит его может
     * посчитать кто угодно, кому эти ключи известны. Ретранслятор знает
     * открытые ключи всех, кто занял имя, и потому мог перебрать пары и
     * подписать каждую личную комнату именами обоих собеседников.
     */
    fun pmRoomIdV1(otherPk: ByteArray): String? {
        val mine = publicKey ?: return null
        require(otherPk.size == Common.IDENTITY_PK_BYTES) { "other pk wrong size" }

        val (lo, hi) = if (compareUnsigned(mine, otherPk) <= 0) mine to otherPk
                       else                                      otherPk to mine
        val concat = ByteArray(64).apply {
            System.arraycopy(lo, 0, this, 0, 32)
            System.arraycopy(hi, 0, this, 32, 32)
        }
        val digest = ByteArray(16)
        ls.cryptoGenericHash(digest, 16, concat, concat.size.toLong(), null, 0)
        return "pm:" + android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
    }

    /**
     * Идентификатор личной комнаты: BLAKE2b под ключом пары.
     *
     * K_pm выводят только двое, поэтому для ретранслятора это непрозрачная
     * метка - перебрать пары открытых ключей и узнать, кто с кем переписывается,
     * больше нельзя. Зеркалит identity_pm_room_id_v2 из C-библиотеки; разойдясь,
     * телефон и ПК оказались бы в разных комнатах и молча не видели друг друга.
     */
    fun pmRoomId(otherPk: ByteArray): String? {
        val kPm = pmRoomKey(otherPk) ?: return null
        val ctx = "fear.pm.room.v2".toByteArray(Charsets.US_ASCII)
        val digest = ByteArray(16)
        ls.cryptoGenericHash(digest, 16, ctx, ctx.size.toLong(), kPm, kPm.size)
        kPm.fill(0)
        return "pm:" + android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
    }

    /**
     * Детерминированный 32-байтовый AES-256-GCM ключ ЛС-комнаты.
     * Считается так же как identity_pm_room_key() в C-библиотеке:
     *   shared = X25519(my_x_sk, their_x_pk)
     *   K_pm   = BLAKE2b(key=shared, data="fear.pm.v1.key"||lo||hi, len=32)
     * Никто, кроме обладателей обоих identity_sk, не может его вычислить.
     */
    fun pmRoomKey(otherPk: ByteArray): ByteArray? {
        val myPk = publicKey ?: return null
        val mySk = secretKey ?: return null
        require(otherPk.size == Common.IDENTITY_PK_BYTES) { "other pk wrong size" }

        val myXSk = ByteArray(32)
        val theirXPk = ByteArray(32)
        if (!ls.convertSecretKeyEd25519ToCurve25519(myXSk, mySk)) return null
        if (!ls.convertPublicKeyEd25519ToCurve25519(theirXPk, otherPk)) return null

        val shared = ByteArray(32)
        if (!ls.cryptoScalarMult(shared, myXSk, theirXPk)) return null

        val (lo, hi) = if (compareUnsigned(myPk, otherPk) <= 0) myPk to otherPk
                       else                                     otherPk to myPk
        val ctx  = "fear.pm.v1.key".toByteArray(Charsets.US_ASCII)
        val info = ByteArray(ctx.size + 64)
        System.arraycopy(ctx, 0, info, 0, ctx.size)
        System.arraycopy(lo,  0, info, ctx.size,      32)
        System.arraycopy(hi,  0, info, ctx.size + 32, 32)

        val out = ByteArray(32)
        ls.cryptoGenericHash(out, 32,
                             info, info.size.toLong(),
                             shared, shared.size)
        // Best-effort wipe.
        java.util.Arrays.fill(myXSk, 0)
        java.util.Arrays.fill(shared, 0)
        return out
    }

    /**
     * Собрать конверт ротации: новое K_room, запечатанное каждому
     * участнику по отдельности (identity/rotation_bundle.c).
     *
     * Операция живёт здесь, а не в вызывающем коде, по той же причине, что и
     * sign(): наружу отдавать секретный ключ незачем, а ротации нужен именно
     * он - из него выводится curve25519-ключ для crypto_box.
     */
    fun buildRotationBundle(
        roomId: String,
        newVersion: Int,
        newKRoom: ByteArray,
        recipientPks: List<ByteArray>,
    ): ByteArray? {
        val myPk = publicKey ?: return null
        val mySk = secretKey ?: return null
        return RotationBundle.build(roomId, newVersion, newKRoom, mySk, myPk, recipientPks)
    }

    /** Открыть адресованную нам запись из чужого конверта ротации. */
    fun openRotationBundle(
        view: RotationBundle.View,
        roomId: String,
    ): ByteArray? {
        val myPk = publicKey ?: return null
        val mySk = secretKey ?: return null
        return RotationBundle.openFor(view, roomId, mySk, myPk)
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }

    /**
     * Derive a 32-byte symmetric key from `identity_sk` for the given
     * application context. Used by ContactsCipher (Phase B-3) to encrypt
     * the contact-list blob without ever exposing identity_sk to the
     * caller.
     *
     *   K = BLAKE2b(input=ctx, key=identity_sk, length=32)
     *
     * Stable across devices that share the same identity (e.g. phone +
     * desktop after `Import identity`), since identity_sk is the same
     * — that's what makes the encrypted blob portable.
     */
    fun deriveSymmetricKey(ctx: String, length: Int = 32): ByteArray? {
        val sk = secretKey ?: return null
        val ctxBytes = ctx.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(length)
        ls.cryptoGenericHash(out, length,
                              ctxBytes, ctxBytes.size.toLong(), sk, sk.size)
        return out
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
     *
     * Раскладка: [pk(32)][sig(64)][name_len(2)][name].
     *
     * Это единственное место, где отображаемое имя вообще уезжает с
     * телефона, и уезжает оно уже запечатанным ключом комнаты. Подпись
     * покрывает не одно имя, а метку сессии вместе с ним: иначе чужой анонс
     * можно было бы взять целиком и повторить под своей меткой, получив
     * вместе с ним и имя.
     */
    fun buildIdentityAnnouncePayload(sessionTag: String, name: String): ByteArray? {
        val pk = publicKey ?: return null
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 0xFFFF) return null
        val sig = sign(com.fear.crypto.SessionTag.announceSignedBytes(sessionTag, name))
            ?: return null

        val payload = ByteArray(
            Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES + 2 + nameBytes.size)
        System.arraycopy(pk, 0, payload, 0, Common.IDENTITY_PK_BYTES)
        System.arraycopy(sig, 0, payload, Common.IDENTITY_PK_BYTES, Common.IDENTITY_SIG_BYTES)
        val at = Common.IDENTITY_PK_BYTES + Common.IDENTITY_SIG_BYTES
        payload[at] = ((nameBytes.size shr 8) and 0xFF).toByte()
        payload[at + 1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, payload, at + 2, nameBytes.size)
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
