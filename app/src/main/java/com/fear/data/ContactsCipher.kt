package com.fear.data

import com.fear.Common
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.SecretBox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encrypt/decrypt the user's contact list under a key derived from
 * identity_sk. The result is stored on the server as an opaque blob
 * (Phase B-3, doc/architecture-decisions.md §3).
 *
 * Wire format:
 *   [4 ] magic "FCN1"
 *   [1 ] version (0x01)
 *   [1 ] reserved
 *   [24] crypto_secretbox nonce
 *   [N ] ciphertext (XSalsa20-Poly1305, plaintext = compact JSON)
 *
 * Plaintext JSON: {"v":1,"contacts":[{...},...]}
 *
 * KDF: K_contacts = BLAKE2b(identity_sk, key=null, ctx="fear.contacts.v1")
 * — 32 bytes. We don't use crypto_kdf because identity_sk is 64 bytes
 * (Ed25519 form) and BLAKE2b is what's already on both platforms.
 */
object ContactsCipher {

    private const val MAGIC = "FCN1"
    private const val VERSION: Byte = 0x01
    private const val HEADER_LEN = 4 + 1 + 1 + 24      // 30
    private const val KDF_CTX = "fear.contacts.v1"

    private val ls: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    /** Encrypt `contacts` under K_contacts. */
    fun encrypt(contacts: List<ContactEntity>, contactsKey: ByteArray): ByteArray {
        require(contactsKey.size == SecretBox.KEYBYTES) { "key wrong size" }

        val key = contactsKey.copyOf()
        val plaintext = serializeJson(contacts).toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(SecretBox.NONCEBYTES)
        ls.sodium.randombytes_buf(nonce, nonce.size)

        val cipher = ByteArray(plaintext.size + SecretBox.MACBYTES)
        if (ls.sodium.crypto_secretbox_easy(cipher, plaintext,
                                             plaintext.size.toLong(), nonce, key) != 0) {
            wipe(key); wipe(plaintext)
            throw IllegalStateException("contacts encrypt failed")
        }
        wipe(key); wipe(plaintext)

        val out = ByteArray(HEADER_LEN + cipher.size)
        System.arraycopy(MAGIC.toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        out[4] = VERSION
        out[5] = 0x00
        System.arraycopy(nonce, 0, out, 6, 24)
        System.arraycopy(cipher, 0, out, HEADER_LEN, cipher.size)
        return out
    }

    /** Decrypt a blob fetched from the server. Throws on tamper / wrong sk. */
    fun decrypt(blob: ByteArray, contactsKey: ByteArray): List<ContactEntity> {
        require(contactsKey.size == SecretBox.KEYBYTES) { "key wrong size" }
        if (blob.size < HEADER_LEN + SecretBox.MACBYTES)
            throw IllegalArgumentException("blob too short")
        if (blob[0] != 'F'.code.toByte() || blob[1] != 'C'.code.toByte() ||
            blob[2] != 'N'.code.toByte() || blob[3] != '1'.code.toByte())
            throw IllegalArgumentException("bad magic")
        if (blob[4] != VERSION)
            throw IllegalArgumentException("unsupported version 0x${"%02x".format(blob[4])}")

        val nonce = blob.copyOfRange(6, 30)
        val cipher = blob.copyOfRange(HEADER_LEN, blob.size)

        val key = contactsKey.copyOf()
        val plaintext = ByteArray(cipher.size - SecretBox.MACBYTES)
        if (ls.sodium.crypto_secretbox_open_easy(plaintext, cipher,
                                                  cipher.size.toLong(), nonce, key) != 0) {
            wipe(key)
            throw IllegalArgumentException("decrypt failed (tampered or wrong identity)")
        }
        wipe(key)
        val list = parseJson(String(plaintext, Charsets.UTF_8))
        wipe(plaintext)
        return list
    }

    // ---------- internals ----------

    private fun serializeJson(contacts: List<ContactEntity>): String {
        val arr = JSONArray()
        for (c in contacts) {
            val o = JSONObject()
            o.put("pk",   c.identityPkB64)
            o.put("name", c.displayName)
            if (c.handle != null) o.put("handle", c.handle)
            if (c.server != null) o.put("server", c.server)
            o.put("ts",       c.addedAt)
            o.put("verified", c.verified)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("v", 1)
        root.put("contacts", arr)
        return root.toString()
    }

    private fun parseJson(json: String): List<ContactEntity> {
        val root = JSONObject(json)
        val arr  = root.optJSONArray("contacts") ?: return emptyList()
        val out  = ArrayList<ContactEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(ContactEntity(
                identityPkB64 = o.getString("pk"),
                displayName   = o.optString("name", ""),
                handle        = o.optString("handle").takeIf { it.isNotEmpty() },
                server        = o.optString("server").takeIf { it.isNotEmpty() },
                addedAt       = o.optLong("ts", 0L),
                verified      = o.optBoolean("verified", false),
            ))
        }
        return out
    }

    private fun wipe(b: ByteArray) { for (i in b.indices) b[i] = 0 }
}
