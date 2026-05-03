package com.fear

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encrypted identity backup format (.fbk), interoperable with the
 * desktop implementation in identity/identity_backup.c. See that header
 * for the canonical format spec.
 *
 * Wire format:
 *   [4 ] magic    "FBK1"
 *   [1 ] version  = 0x01
 *   [1 ] reserved
 *   [16] argon2id salt
 *   [8 ] opslimit (uint64 BE)
 *   [8 ] memlimit (uint64 BE, bytes)
 *   [24] secretbox nonce
 *   [N ] ciphertext (XSalsa20-Poly1305, plaintext = compact JSON)
 *
 * Plaintext JSON: {"v":1,"sk":"<b64url>","pk":"<b64url>","ts":<unix>}
 */
object IdentityBackup {

    const val MAGIC = "FBK1"
    const val VERSION: Byte = 0x01
    const val HEADER_LEN = 4 + 1 + 1 + 16 + 8 + 8 + 24   // 62

    private const val OPSLIMIT = 3L
    private const val MEMLIMIT = 64L * 1024L * 1024L      // 64 MB

    // Caps used at decode time so a malicious file cannot OOM the device
    private const val MEMLIMIT_CAP = 1024L * 1024L * 1024L  // 1 GB
    private const val OPSLIMIT_CAP = 10L

    private val ls: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    data class Identity(val sk: ByteArray, val pk: ByteArray) {
        fun wipe() {
            sk.fill(0)
        }
    }

    /** Encrypt to a file. Throws IllegalStateException on crypto failure. */
    fun exportToFile(file: File, sk: ByteArray, pk: ByteArray, password: CharArray) {
        file.parentFile?.mkdirs()
        val buf = exportToBuffer(sk, pk, password)
        file.writeBytes(buf)
    }

    /** Decrypt from a file. Throws on bad password / corruption. */
    fun importFromFile(file: File, password: CharArray): Identity {
        val raw = file.readBytes()
        return importFromBuffer(raw, password)
    }

    /** Encrypt to a heap buffer (used by QR encoder later). */
    fun exportToBuffer(sk: ByteArray, pk: ByteArray, password: CharArray): ByteArray {
        require(sk.size == Common.IDENTITY_SK_BYTES) { "sk must be 64 bytes" }
        require(pk.size == Common.IDENTITY_PK_BYTES) { "pk must be 32 bytes" }

        val plaintext = buildPlaintextJson(sk, pk).toByteArray(Charsets.UTF_8)

        val salt = ByteArray(PwHash.SALTBYTES)            // 16
        ls.sodium.randombytes_buf(salt, salt.size)

        val key = ByteArray(SecretBox.KEYBYTES)            // 32
        derivePasswordKey(password, salt, OPSLIMIT, MEMLIMIT, key)

        val nonce = ByteArray(SecretBox.NONCEBYTES)        // 24
        ls.sodium.randombytes_buf(nonce, nonce.size)

        val cipher = ByteArray(plaintext.size + SecretBox.MACBYTES)
        if (ls.sodium.crypto_secretbox_easy(cipher, plaintext, plaintext.size.toLong(), nonce, key) != 0) {
            wipe(key); wipe(plaintext)
            throw IllegalStateException("secretbox_easy failed")
        }
        wipe(key); wipe(plaintext)

        val out = ByteArray(HEADER_LEN + cipher.size)
        // Header
        System.arraycopy(MAGIC.toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        out[4] = VERSION
        out[5] = 0x00
        System.arraycopy(salt, 0, out, 6, 16)
        writeBeUInt64(out, 22, OPSLIMIT)
        writeBeUInt64(out, 30, MEMLIMIT)
        System.arraycopy(nonce, 0, out, 38, 24)
        // Ciphertext
        System.arraycopy(cipher, 0, out, HEADER_LEN, cipher.size)
        return out
    }

    fun importFromBuffer(buf: ByteArray, password: CharArray): Identity {
        if (buf.size < HEADER_LEN + SecretBox.MACBYTES) {
            throw IllegalArgumentException("backup too short")
        }
        // Magic
        if (buf[0] != 'F'.code.toByte() || buf[1] != 'B'.code.toByte() ||
            buf[2] != 'K'.code.toByte() || buf[3] != '1'.code.toByte()) {
            throw IllegalArgumentException("bad magic, not a FBK1 backup")
        }
        if (buf[4] != VERSION) {
            throw IllegalArgumentException("unsupported version 0x${"%02x".format(buf[4])}")
        }

        val salt = buf.copyOfRange(6, 22)
        val opslimit = readBeUInt64(buf, 22)
        val memlimit = readBeUInt64(buf, 30)
        val nonce = buf.copyOfRange(38, 62)
        val cipher = buf.copyOfRange(HEADER_LEN, buf.size)

        if (memlimit <= 0 || memlimit > MEMLIMIT_CAP || opslimit <= 0 || opslimit > OPSLIMIT_CAP) {
            throw IllegalArgumentException("KDF parameters out of safe range")
        }

        val key = ByteArray(SecretBox.KEYBYTES)
        derivePasswordKey(password, salt, opslimit, memlimit, key)

        val plaintext = ByteArray(cipher.size - SecretBox.MACBYTES)
        if (ls.sodium.crypto_secretbox_open_easy(plaintext, cipher, cipher.size.toLong(), nonce, key) != 0) {
            wipe(key)
            throw IllegalArgumentException("decryption failed (wrong password or corrupt file)")
        }
        wipe(key)

        val json = try { JSONObject(String(plaintext, Charsets.UTF_8)) }
                   catch (e: Exception) {
                       wipe(plaintext)
                       throw IllegalArgumentException("backup payload not valid JSON")
                   }

        val skB64 = json.optString("sk")
        val pkB64 = json.optString("pk")
        if (skB64.isEmpty() || pkB64.isEmpty()) {
            wipe(plaintext)
            throw IllegalArgumentException("backup missing sk/pk fields")
        }
        val sk = Common.base64Decode(skB64)
            ?: throw IllegalArgumentException("sk not base64")
        val pk = Common.base64Decode(pkB64)
            ?: throw IllegalArgumentException("pk not base64")
        wipe(plaintext)

        if (sk.size != Common.IDENTITY_SK_BYTES || pk.size != Common.IDENTITY_PK_BYTES) {
            throw IllegalArgumentException("sk/pk wrong size")
        }
        return Identity(sk, pk)
    }

    // ---------- internals ----------

    private fun buildPlaintextJson(sk: ByteArray, pk: ByteArray): String {
        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("sk", Common.base64Encode(sk))
        obj.put("pk", Common.base64Encode(pk))
        obj.put("ts", System.currentTimeMillis() / 1000L)
        return obj.toString()
    }

    private fun derivePasswordKey(password: CharArray, salt: ByteArray,
                                  opslimit: Long, memlimit: Long, outKey: ByteArray) {
        // Lazysodium expects bytes, not chars. Convert without intermediate String to
        // avoid Java's String pool retaining the password.
        val pwBytes = charsToUtf8(password)
        try {
            // alg=2 hardcoded (crypto_pwhash_ALG_ARGON2ID13) — avoids any
            // chance the Lazysodium enum mis-resolves across versions.
            val rc = ls.sodium.crypto_pwhash(
                outKey, outKey.size.toLong(),
                pwBytes, pwBytes.size.toLong(),
                salt,
                opslimit,
                NativeLong(memlimit),
                2,    // crypto_pwhash_ALG_ARGON2ID13
            )
            if (rc != 0) throw IllegalStateException("crypto_pwhash failed (insufficient memory?)")
        } finally {
            wipe(pwBytes)
        }
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val cb = java.nio.CharBuffer.wrap(chars)
        val bb: ByteBuffer = Charsets.UTF_8.encode(cb)
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    private fun writeBeUInt64(buf: ByteArray, offset: Int, v: Long) {
        ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(v)
    }

    private fun readBeUInt64(buf: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun wipe(b: ByteArray) {
        for (i in b.indices) b[i] = 0
    }
}
