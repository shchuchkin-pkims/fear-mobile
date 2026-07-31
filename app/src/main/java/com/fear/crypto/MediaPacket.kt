package com.fear.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Media packet framing - Kotlin port of identity/media_packet.c.
 *
 *    off size field
 *      0    1  packet type
 *      1    3  SID, the sender tag
 *      4    5  counter, big endian, per sender and counter domain
 *      9   ..  AES-256-GCM ciphertext and its 16-byte tag
 *
 *    nonce = SID(3) || 0x00 00 00 00 || counter(5)
 *    AAD   = the nine cleartext header bytes
 *
 * This existed only as hand-written code inside AudioCallManager and
 * VideoCallManager, duplicated and pinned by nothing. That is the one part
 * of the group-call change where the two platforms could silently disagree:
 * every other layout is held in place by shared frozen vectors. It is a
 * module with its own vectors now, and both managers call it.
 *
 * AES-GCM comes from the JCE rather than from lazysodium so the tests can
 * run on a plain JVM, and so the vectors are checked by a third
 * implementation - the C side pins the same bytes from libsodium, and the
 * expected values were produced by neither.
 */
object MediaPacket {

    const val HEADER_BYTES = 9
    const val TAG_BYTES = 16
    const val COUNTER_BYTES = 5
    const val NONCE_BYTES = 12

    /** Largest counter the 5-byte field can carry. */
    const val MAX_COUNTER = 0xFFFFFFFFFFL

    private const val TRANSFORM = "AES/GCM/NoPadding"

    class Parsed(val type: Int, val sid: ByteArray, val counter: Long)

    private fun header(type: Int, sid: ByteArray, counter: Long): ByteArray {
        val h = ByteArray(HEADER_BYTES)
        h[0] = type.toByte()
        sid.copyInto(h, 1, 0, MediaKeys.SID_BYTES)
        for (i in 0 until COUNTER_BYTES) {
            h[4 + i] = ((counter ushr (8 * (COUNTER_BYTES - 1 - i))) and 0xFF).toByte()
        }
        return h
    }

    private fun nonce(sid: ByteArray, counter: Long): ByteArray {
        val n = ByteArray(NONCE_BYTES)
        sid.copyInto(n, 0, 0, MediaKeys.SID_BYTES)
        // Four zero bytes sit between the tag and the counter.
        for (i in 0 until COUNTER_BYTES) {
            n[7 + i] = ((counter ushr (8 * (COUNTER_BYTES - 1 - i))) and 0xFF).toByte()
        }
        return n
    }

    /** Frame and encrypt one media packet. Returns null on bad arguments. */
    fun encrypt(
        type: Int,
        sid: ByteArray,
        counter: Long,
        key: ByteArray,
        plain: ByteArray,
    ): ByteArray? {
        if (sid.size != MediaKeys.SID_BYTES) return null
        if (key.size != MediaKeys.KEY_BYTES) return null
        // Refuse rather than truncate: a wrapped counter would repeat under a
        // live key, which is the exact failure this scheme exists to prevent.
        if (counter < 0 || counter > MAX_COUNTER) return null

        return try {
            val hdr = header(type, sid, counter)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                   GCMParameterSpec(TAG_BYTES * 8, nonce(sid, counter)))
            c.updateAAD(hdr)
            hdr + c.doFinal(plain)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read the header without decrypting. A receiver needs the SID to choose
     * which key to try, so this is deliberately separate; nothing here is
     * trusted until [decrypt] succeeds.
     */
    fun peek(pkt: ByteArray, len: Int = pkt.size): Parsed? {
        if (len < HEADER_BYTES + TAG_BYTES || len > pkt.size) return null
        var counter = 0L
        for (i in 0 until COUNTER_BYTES) {
            counter = (counter shl 8) or (pkt[4 + i].toLong() and 0xFF)
        }
        return Parsed(pkt[0].toInt() and 0xFF, pkt.copyOfRange(1, 4), counter)
    }

    /**
     * Decrypt under a candidate key. The nine header bytes are the
     * associated data, so a packet whose type or counter was altered fails
     * here instead of being routed to the wrong parser.
     */
    fun decrypt(pkt: ByteArray, len: Int = pkt.size, key: ByteArray): ByteArray? {
        if (len < HEADER_BYTES + TAG_BYTES || len > pkt.size) return null
        if (key.size != MediaKeys.KEY_BYTES) return null
        val p = peek(pkt, len) ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                   GCMParameterSpec(TAG_BYTES * 8, nonce(p.sid, p.counter)))
            c.updateAAD(pkt, 0, HEADER_BYTES)
            c.doFinal(pkt, HEADER_BYTES, len - HEADER_BYTES)
        } catch (e: Exception) {
            null   // wrong key, altered header, or a bad tag
        }
    }
}
