package com.fear.crypto

/**
 * Call invitation payload - Kotlin port of identity/call_invite.c.
 *
 * Every media key is bound to a call_id, and two peers have no other way to
 * agree on one: calls carry no signalling of their own, and deriving the
 * value from the room key would make it identical for every call in that
 * room, which is the replay window it exists to close. The initiator draws
 * it and announces it as a chat message, so the relay can neither read nor
 * forge one and every member of the room receives it - which is what makes
 * this work for group calls rather than only for pairs.
 *
 * Layout, inside the chat message envelope:
 *
 *    off size field
 *      0    1  version, 0x01
 *      1    1  media flags: 0x01 audio, 0x02 video
 *      2   16  call_id
 *     18    2  port, big endian, 0 when relayed
 *     20    1  host length, 0 when relayed
 *     21    n  host, UTF-8
 *
 * The bytes are pinned in CallInviteTest and shared with the C test.
 */
object CallInvite {

    const val VERSION = 0x01
    const val FLAG_AUDIO = 0x01
    const val FLAG_VIDEO = 0x02
    /** Bits nobody has defined yet; must be zero on the wire. */
    const val FLAG_RESERVED = 0xFC

    const val MAX_HOST = 255
    const val HEADER_BYTES = 21
    const val MAX_BYTES = HEADER_BYTES + MAX_HOST

    enum class Status {
        OK, ERR_ARGS, ERR_TOO_SHORT, ERR_VERSION, ERR_RESERVED,
        ERR_CALLID, ERR_LENGTH, ERR_HOST
    }

    data class Invite(
        val flags: Int,
        val callId: ByteArray,
        val port: Int = 0,
        /** Empty when the invite carries no direct hint. */
        val host: String = "",
    ) {
        val hasVideo: Boolean get() = flags and FLAG_VIDEO != 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Invite) return false
            return flags == other.flags && callId.contentEquals(other.callId) &&
                port == other.port && host == other.host
        }

        override fun hashCode(): Int {
            var r = flags
            r = 31 * r + callId.contentHashCode()
            r = 31 * r + port
            r = 31 * r + host.hashCode()
            return r
        }
    }

    class ParseResult(val status: Status, val invite: Invite? = null)

    /**
     * A hostname, an IPv4 literal or a bracketless IPv6 literal, nothing
     * else. This value came from another party and goes on to a connect
     * call and into log lines, so it is checked here rather than sanitised
     * at each use.
     */
    private fun hostCharOk(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '.' || c == '-' || c == ':'

    private fun isZero(b: ByteArray, off: Int, len: Int): Boolean {
        for (i in off until off + len) if (b[i] != 0.toByte()) return false
        return true
    }

    fun build(inv: Invite): ByteArray {
        require(inv.flags and FLAG_RESERVED == 0) { "reserved flag bits set" }
        require(inv.callId.size == MediaKeys.CALLID_BYTES) { "bad callId size" }
        require(!isZero(inv.callId, 0, MediaKeys.CALLID_BYTES)) { "callId must not be all zero" }

        val hostBytes = inv.host.toByteArray(Charsets.UTF_8)
        require(hostBytes.size <= MAX_HOST) { "host too long" }
        require(inv.host.all { hostCharOk(it) }) { "host contains illegal characters" }
        require(inv.port in 0..0xFFFF) { "port out of range" }

        val out = ByteArray(HEADER_BYTES + hostBytes.size)
        out[0] = VERSION.toByte()
        out[1] = inv.flags.toByte()
        inv.callId.copyInto(out, 2)
        out[18] = ((inv.port ushr 8) and 0xFF).toByte()
        out[19] = (inv.port and 0xFF).toByte()
        out[20] = hostBytes.size.toByte()
        hostBytes.copyInto(out, 21)
        return out
    }

    fun parse(buf: ByteArray, len: Int = buf.size): ParseResult {
        if (len < 0 || len > buf.size) return ParseResult(Status.ERR_ARGS)
        if (len < HEADER_BYTES) return ParseResult(Status.ERR_TOO_SHORT)
        if (len > MAX_BYTES) return ParseResult(Status.ERR_LENGTH)

        if (buf[0].toInt() and 0xFF != VERSION) return ParseResult(Status.ERR_VERSION)

        val flags = buf[1].toInt() and 0xFF
        if (flags and FLAG_RESERVED != 0) return ParseResult(Status.ERR_RESERVED)

        if (isZero(buf, 2, MediaKeys.CALLID_BYTES)) return ParseResult(Status.ERR_CALLID)

        val hostLen = buf[20].toInt() and 0xFF
        // No tolerance for trailing bytes: tolerant parsers are how the
        // platforms drift apart.
        if (len != HEADER_BYTES + hostLen) return ParseResult(Status.ERR_LENGTH)

        val host = String(buf, 21, hostLen, Charsets.UTF_8)
        if (!host.all { hostCharOk(it) }) return ParseResult(Status.ERR_HOST)

        return ParseResult(Status.OK, Invite(
            flags = flags,
            callId = buf.copyOfRange(2, 2 + MediaKeys.CALLID_BYTES),
            port = ((buf[18].toInt() and 0xFF) shl 8) or (buf[19].toInt() and 0xFF),
            host = host,
        ))
    }
}
