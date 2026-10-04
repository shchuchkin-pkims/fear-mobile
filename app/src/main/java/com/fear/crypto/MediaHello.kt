package com.fear.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign

/**
 * Ed25519 over a raw message, seamed like [KeyedHash] so the JVM tests can
 * inject an independent implementation instead of needing a device.
 */
fun interface Ed25519Ops {
    /** @param sk 64-byte libsodium secret key (seed || pk) */
    fun sign(msg: ByteArray, sk: ByteArray): ByteArray
    fun verify(msg: ByteArray, sig: ByteArray, pk: ByteArray): Boolean {
        return false
    }
}

/** Production signer: libsodium through lazysodium, as everywhere else. */
object SodiumEd25519 : Ed25519Ops {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    override fun sign(msg: ByteArray, sk: ByteArray): ByteArray {
        val sig = ByteArray(Sign.ED25519_BYTES)
        ls.cryptoSignDetached(sig, msg, msg.size.toLong(), sk)
        return sig
    }

    override fun verify(msg: ByteArray, sig: ByteArray, pk: ByteArray): Boolean =
        ls.cryptoSignVerifyDetached(sig, msg, msg.size, pk)
}

/**
 * HELLO2 handshake codec - Kotlin port of identity/media_hello.c.
 *
 * Type 0x7E, version 0x03, 62 bytes unsigned or 158 with an identity, all
 * multi-byte integers big-endian. The layout, the acceptance rule and the
 * frozen byte vectors are shared with the C implementation; if the two ever
 * disagree, Android and desktop calls stop connecting.
 *
 * The MAC is verified before any field is read and before any state is
 * touched, which is what keeps an off-path attacker out of the handshake.
 */
object MediaHello {

    const val TYPE: Byte = 0x7E
    const val VERSION: Byte = 0x04

    /** The pre-group HELLO type, kept only to recognise peers that are too old. */
    const val LEGACY_TYPE: Byte = 0x7F

    const val SIZE_BASE = 78
    const val SIZE_SIGNED = 174

    /** Display name field width. Not NUL-terminated when exactly full. */
    const val NAME_BYTES = 16

    const val FLAG_VIDEO = 0x01
    const val FLAG_AUDIO = 0x02
    const val FLAG_IDENTITY = 0x04
    /** Reserved for per-sender key wrapping; must be zero on the wire today. */
    const val FLAG_RESERVED = 0xF8

    const val PK_BYTES = 32
    const val SIG_BYTES = 64

    private const val OFF_TYPE = 0
    private const val OFF_VERSION = 1
    private const val OFF_LENGTH = 2
    private const val OFF_FLAGS = 4
    private const val OFF_RSVD1 = 5
    private const val OFF_KEYVER = 6
    private const val OFF_CALLID = 8
    private const val OFF_SALT = 24
    private const val OFF_WIDTH = 40
    private const val OFF_HEIGHT = 42
    private const val OFF_FPS = 44
    private const val OFF_RSVD2 = 45
    private const val OFF_NAME = 46
    private const val OFF_PK = 62
    private const val OFF_SIG = 94
    /** The signature covers everything before it. */
    private const val SIGNED_RANGE = 94

    /** One value per rejection, so a dropped packet can say why. */
    enum class Status {
        OK,
        ERR_ARGS,
        ERR_TOO_SHORT,
        ERR_TYPE,
        ERR_LEGACY_PEER,
        ERR_MAC,
        ERR_VERSION,
        ERR_LENGTH,
        ERR_RESERVED,
        ERR_CALLID,
        ERR_NAME,
        ERR_SIGNATURE,
    }

    data class Hello(
        val flags: Int,
        val keyVersion: Int,
        val callId: ByteArray,
        val senderSalt: ByteArray,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        /**
         * What the sender calls themselves, so a call can put a person's
         * name under their picture instead of six hex digits of their SID.
         * A label, not an identity: the MAC only proves a room member sent
         * it. Empty when none was announced.
         */
        val name: String = "",
        /** Present only when flags has FLAG_IDENTITY. */
        val pk: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Hello) return false
            return flags == other.flags && keyVersion == other.keyVersion &&
                callId.contentEquals(other.callId) &&
                senderSalt.contentEquals(other.senderSalt) &&
                width == other.width && height == other.height && fps == other.fps &&
                (pk?.contentEquals(other.pk ?: ByteArray(0)) ?: (other.pk == null))
        }

        override fun hashCode(): Int {
            var r = flags
            r = 31 * r + keyVersion
            r = 31 * r + callId.contentHashCode()
            r = 31 * r + senderSalt.contentHashCode()
            r = 31 * r + width
            r = 31 * r + height
            r = 31 * r + fps
            r = 31 * r + (pk?.contentHashCode() ?: 0)
            return r
        }
    }

    class ParseResult(val status: Status, val hello: Hello? = null)

    /** Wire size implied by a flag set. */
    fun size(flags: Int): Int =
        if (flags and FLAG_IDENTITY != 0) SIZE_SIGNED else SIZE_BASE

    private fun wrBe16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun rdBe16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun isZero(b: ByteArray, off: Int, len: Int): Boolean {
        for (i in off until off + len) if (b[i] != 0.toByte()) return false
        return true
    }

    /**
     * Serialize and authenticate a HELLO.
     *
     * @param identitySk 64-byte secret key, required when FLAG_IDENTITY is set
     */
    fun build(
        hello: Hello,
        helloKey: ByteArray,
        identitySk: ByteArray? = null,
        hash: KeyedHash = SodiumKeyedHash,
        signer: Ed25519Ops = SodiumEd25519,
    ): ByteArray {
        require(hello.flags and FLAG_RESERVED == 0) { "reserved flag bits set" }
        require(hello.callId.size == MediaKeys.CALLID_BYTES) { "bad callId size" }
        require(hello.senderSalt.size == MediaKeys.SALT_BYTES) { "bad salt size" }
        require(!isZero(hello.callId, 0, MediaKeys.CALLID_BYTES)) { "callId must not be all zero" }
        require(hello.keyVersion in 0..0xFFFF) { "keyVersion out of range" }
        val signed = hello.flags and FLAG_IDENTITY != 0
        require(!signed || identitySk?.size == 64) { "IDENTITY needs a 64-byte secret key" }

        val total = size(hello.flags)
        val out = ByteArray(total)
        out[OFF_TYPE] = TYPE
        out[OFF_VERSION] = VERSION
        wrBe16(out, OFF_LENGTH, total)
        out[OFF_FLAGS] = hello.flags.toByte()
        wrBe16(out, OFF_KEYVER, hello.keyVersion)
        hello.callId.copyInto(out, OFF_CALLID)
        hello.senderSalt.copyInto(out, OFF_SALT)

        // Truncated on a byte boundary rather than refused: a long name is a
        // display problem, and failing to announce ourselves over one would
        // be worse. Cutting UTF-8 mid-character would produce bytes the far
        // end rejects, so the cut lands on a whole character.
        if (hello.name.isNotEmpty()) {
            var encoded = hello.name.toByteArray(Charsets.UTF_8)
            if (encoded.size > NAME_BYTES) {
                var take = hello.name.length
                while (take > 0) {
                    encoded = hello.name.substring(0, take).toByteArray(Charsets.UTF_8)
                    if (encoded.size <= NAME_BYTES) break
                    take--
                }
                if (take == 0) encoded = ByteArray(0)
            }
            encoded.copyInto(out, OFF_NAME)
        }

        // Zeroed rather than stale when the flag is clear.
        if (hello.flags and FLAG_VIDEO != 0) {
            wrBe16(out, OFF_WIDTH, hello.width)
            wrBe16(out, OFF_HEIGHT, hello.height)
            out[OFF_FPS] = hello.fps.toByte()
        }

        if (signed) {
            val sk = identitySk!!
            // The public key comes from the secret key, never from the caller:
            // a mismatched pair would only fail at the peer.
            sk.copyInto(out, OFF_PK, 32, 64)
            val sig = signer.sign(out.copyOfRange(0, SIGNED_RANGE), sk)
            require(sig.size == SIG_BYTES) { "bad signature size" }
            sig.copyInto(out, OFF_SIG)
        }

        val macBody = out.copyOfRange(0, total - MediaKeys.MAC_BYTES)
        MediaKeys.helloMac(helloKey, macBody, hash).copyInto(out, total - MediaKeys.MAC_BYTES)
        return out
    }

    /**
     * Verify and decode. Nothing is returned unless the packet passes every
     * rule, and the MAC is checked before any field is examined.
     */
    fun parse(
        buf: ByteArray,
        len: Int = buf.size,
        helloKey: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
        signer: Ed25519Ops = SodiumEd25519,
    ): ParseResult {
        if (len < 0 || len > buf.size) return ParseResult(Status.ERR_ARGS)

        // Recognising an old peer costs nothing and touches no state.
        if (len > 0 && buf[OFF_TYPE] == LEGACY_TYPE) return ParseResult(Status.ERR_LEGACY_PEER)
        if (len > 0 && buf[OFF_TYPE] != TYPE) return ParseResult(Status.ERR_TYPE)
        if (len < MediaKeys.MAC_BYTES + 1) return ParseResult(Status.ERR_TOO_SHORT)
        if (len > SIZE_SIGNED) return ParseResult(Status.ERR_LENGTH)

        // The MAC gates everything below, including the length and flags
        // bytes, so those cannot be tampered with to steer the parse.
        val body = buf.copyOfRange(0, len - MediaKeys.MAC_BYTES)
        val mac = buf.copyOfRange(len - MediaKeys.MAC_BYTES, len)
        if (!MediaKeys.helloMacVerify(helloKey, body, mac, hash)) {
            return ParseResult(Status.ERR_MAC)
        }

        if (buf[OFF_VERSION] != VERSION) return ParseResult(Status.ERR_VERSION)

        val flags = buf[OFF_FLAGS].toInt() and 0xFF
        if (flags and FLAG_RESERVED != 0) return ParseResult(Status.ERR_RESERVED)
        if (buf[OFF_RSVD1] != 0.toByte() || buf[OFF_RSVD2] != 0.toByte()) {
            return ParseResult(Status.ERR_RESERVED)
        }

        if (len != size(flags)) return ParseResult(Status.ERR_LENGTH)
        if (rdBe16(buf, OFF_LENGTH) != len) return ParseResult(Status.ERR_LENGTH)

        if (isZero(buf, OFF_CALLID, MediaKeys.CALLID_BYTES)) return ParseResult(Status.ERR_CALLID)

        // The name reaches text renderers, so control characters are refused
        // rather than stripped, and everything after the first NUL has to be
        // NUL: padding is not a place to hide bytes a careless consumer might
        // read past the terminator. UTF-8 passes; a name need not be English.
        var nameEnd = NAME_BYTES
        run {
            var ended = false
            for (i in 0 until NAME_BYTES) {
                val ch = buf[OFF_NAME + i].toInt() and 0xFF
                if (ended) {
                    if (ch != 0) return ParseResult(Status.ERR_NAME)
                    continue
                }
                if (ch == 0) { ended = true; nameEnd = i; continue }
                if (ch < 0x20 || ch == 0x7F) return ParseResult(Status.ERR_NAME)
            }
        }

        var pk: ByteArray? = null
        if (flags and FLAG_IDENTITY != 0) {
            pk = buf.copyOfRange(OFF_PK, OFF_PK + PK_BYTES)
            val sig = buf.copyOfRange(OFF_SIG, OFF_SIG + SIG_BYTES)
            if (!signer.verify(buf.copyOfRange(0, SIGNED_RANGE), sig, pk)) {
                return ParseResult(Status.ERR_SIGNATURE)
            }
        }

        val videoOn = flags and FLAG_VIDEO != 0
        return ParseResult(Status.OK, Hello(
            flags = flags,
            keyVersion = rdBe16(buf, OFF_KEYVER),
            callId = buf.copyOfRange(OFF_CALLID, OFF_CALLID + MediaKeys.CALLID_BYTES),
            senderSalt = buf.copyOfRange(OFF_SALT, OFF_SALT + MediaKeys.SALT_BYTES),
            name = String(buf, OFF_NAME, nameEnd, Charsets.UTF_8),
            width = if (videoOn) rdBe16(buf, OFF_WIDTH) else 0,
            height = if (videoOn) rdBe16(buf, OFF_HEIGHT) else 0,
            fps = if (videoOn) buf[OFF_FPS].toInt() and 0xFF else 0,
            pk = pk,
        ))
    }
}
