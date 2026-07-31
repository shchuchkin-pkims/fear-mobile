package com.fear.crypto

/**
 * Sender-rooted media keys for group calls - Kotlin port of
 * identity/media_keys.c (audit M3 / M5).
 *
 *     K_send(P, stream) = BLAKE2b(key  = K_call,
 *                                 data = "fear.media.v2" || stream
 *                                        || keyVersion || callId
 *                                        || senderSalt || idbind,
 *                                 out  = 32)
 *
 * Every participant encrypts under a key nobody else uses, derived from its
 * own public announcement. A receiver derives any peer's key from K_call plus
 * what that peer announced, so there is no agreement step, no role, and a
 * participant joining or leaving changes nobody else's keys. The previous
 * scheme keyed on a caller/callee bit, which with three participants meant
 * the last HELLO won and every receive key but one was wrong.
 *
 * All multi-byte integers are big-endian, like the rest of the media wire.
 * KeySchedule is little-endian and is deliberately not reused here.
 *
 * The vectors in MediaKeysTest are the ones frozen on the C side; if the two
 * ever disagree, Android and desktop stop interoperating.
 */
object MediaKeys {

    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val CALLID_BYTES = 16
    const val SID_BYTES = 3
    const val MAC_BYTES = 16
    const val IDBIND_BYTES = 32

    /** Largest forward jump a replay window may accept in one step. */
    const val MAX_CTR_JUMP = 16384

    const val CTX_V2 = "fear.media.v2"
    const val SID_CTX = "fear.media.sid.v2"
    const val HELLO_CTX = "fear.media.hello.v2"

    /** Counter domain, not media type: audio and stats share one counter. */
    const val STREAM_AUDIO = 0
    const val STREAM_VIDEO = 1

    /** Unsigned calls bind 32 zero bytes where the Ed25519 public key would go. */
    val UNSIGNED_IDBIND = ByteArray(IDBIND_BYTES)

    private fun requireCallId(callId: ByteArray) {
        require(callId.size == CALLID_BYTES) { "callId must be $CALLID_BYTES bytes" }
        // All-zero means nobody plumbed it through; refusing keeps the
        // cross-call replay barrier from being silently disabled.
        require(callId.any { it != 0.toByte() }) { "callId must not be all zero" }
    }

    /** HELLO authentication key for a call. */
    fun helloKey(
        kCall: ByteArray,
        callId: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(kCall.size == KEY_BYTES) { "K_call must be $KEY_BYTES bytes" }
        requireCallId(callId)
        val ctx = HELLO_CTX.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(ctx.size + CALLID_BYTES)
        ctx.copyInto(data, 0)
        callId.copyInto(data, ctx.size)
        return hash.blake2b(data, kCall, KEY_BYTES)
    }

    /**
     * One sender's media key for one counter domain. Used with our own salt
     * and identity for the send key, and with a peer's announced values for
     * the key we decrypt that peer with.
     *
     * @param idbind the sender's Ed25519 public key, or [UNSIGNED_IDBIND]
     */
    fun deriveSender(
        kCall: ByteArray,
        stream: Int,
        keyVersion: Int,
        callId: ByteArray,
        senderSalt: ByteArray,
        idbind: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(kCall.size == KEY_BYTES) { "K_call must be $KEY_BYTES bytes" }
        require(stream == STREAM_AUDIO || stream == STREAM_VIDEO) { "bad stream: $stream" }
        require(keyVersion in 0..0xFFFF) { "keyVersion out of range: $keyVersion" }
        require(senderSalt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(idbind.size == IDBIND_BYTES) { "idbind must be $IDBIND_BYTES bytes" }
        requireCallId(callId)

        val ctx = CTX_V2.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(ctx.size + 1 + 2 + CALLID_BYTES + SALT_BYTES + IDBIND_BYTES)
        var o = 0
        ctx.copyInto(data, o); o += ctx.size
        data[o++] = stream.toByte()
        data[o++] = ((keyVersion ushr 8) and 0xFF).toByte()   // big endian
        data[o++] = (keyVersion and 0xFF).toByte()
        callId.copyInto(data, o); o += CALLID_BYTES
        senderSalt.copyInto(data, o); o += SALT_BYTES
        idbind.copyInto(data, o)
        return hash.blake2b(data, kCall, KEY_BYTES)
    }

    /**
     * A sender's wire tag. One tag identifies a participant across audio,
     * video and stats, so it deliberately takes no stream argument.
     *
     * BLAKE2b cannot produce a 3-byte digest and carries the output length in
     * its parameter block, so this computes 16 bytes and truncates - exactly
     * as the C side does, or the tags diverge.
     */
    fun senderId(
        kCall: ByteArray,
        callId: ByteArray,
        senderSalt: ByteArray,
        idbind: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(kCall.size == KEY_BYTES) { "K_call must be $KEY_BYTES bytes" }
        require(senderSalt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(idbind.size == IDBIND_BYTES) { "idbind must be $IDBIND_BYTES bytes" }
        requireCallId(callId)

        val ctx = SID_CTX.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(ctx.size + CALLID_BYTES + SALT_BYTES + IDBIND_BYTES)
        var o = 0
        ctx.copyInto(data, o); o += ctx.size
        callId.copyInto(data, o); o += CALLID_BYTES
        senderSalt.copyInto(data, o); o += SALT_BYTES
        idbind.copyInto(data, o)
        return hash.blake2b(data, kCall, 16).copyOf(SID_BYTES)
    }

    /** Authenticate a HELLO body (everything before the trailing MAC). */
    fun helloMac(
        helloKey: ByteArray,
        hello: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(helloKey.size == KEY_BYTES) { "hello key must be $KEY_BYTES bytes" }
        return hash.blake2b(hello, helloKey, MAC_BYTES)
    }

    /** Verify a HELLO MAC in constant time. */
    fun helloMacVerify(
        helloKey: ByteArray,
        hello: ByteArray,
        mac: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): Boolean {
        if (mac.size != MAC_BYTES) return false
        val expect = helloMac(helloKey, hello, hash)
        // Constant time: a short-circuiting compare leaks the MAC one byte per
        // forgery attempt, and the attacker chooses how often to retry.
        var diff = 0
        for (i in 0 until MAC_BYTES) diff = diff or (expect[i].toInt() xor mac[i].toInt())
        return diff == 0
    }
}
