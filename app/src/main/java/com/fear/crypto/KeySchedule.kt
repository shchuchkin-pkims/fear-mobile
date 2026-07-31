package com.fear.crypto

/**
 * Room key schedule - Kotlin port of identity/key_schedule.c (architecture §5).
 *
 *     K_epoch = BLAKE2b(key  = K_room,
 *                       data = "fear.epoch.v1" || keyVersion(2, LE) || epoch(4, LE),
 *                       out  = 32)
 *
 * `epoch` is the number of whole hours since the UNIX epoch, so every
 * member derives the same key from the clock with nothing exchanged. The
 * wire header carries [keyVersion(2)][epoch(4)], little endian, matching
 * the rest of the format.
 *
 * The vectors in KeyScheduleTest are the ones frozen on the C side: this
 * port is only correct if it reproduces them byte for byte.
 */
object KeySchedule {

    const val KEY_BYTES = 32
    const val EPOCH_SECONDS = 3600L
    const val HEADER_BYTES = 6
    const val CTX = "fear.epoch.v1"

    /** Accept a header epoch this far from the local one (clock skew, hour boundary). */
    const val EPOCH_SKEW = 1L

    /** Largest epoch the 4-byte wire field can carry. */
    const val MAX_EPOCH = 0xFFFFFFFFL

    /**
     * Whole hours since the UNIX epoch. Saturates instead of wrapping, so a
     * bogus far-future clock cannot alias onto a valid epoch.
     */
    fun epochFromUnix(unixSeconds: Long): Long {
        if (unixSeconds <= 0L) return 0L
        val e = unixSeconds / EPOCH_SECONDS
        return if (e > MAX_EPOCH) MAX_EPOCH else e
    }

    /** The exact bytes hashed under K_room. Split out so tests can pin the framing. */
    fun info(keyVersion: Int, epoch: Long): ByteArray {
        require(keyVersion in 0..0xFFFF) { "keyVersion out of range: $keyVersion" }
        require(epoch in 0L..MAX_EPOCH) { "epoch out of range: $epoch" }
        val ctx = CTX.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(ctx.size + 2 + 4)
        ctx.copyInto(out, 0)
        var o = ctx.size
        out[o++] = (keyVersion and 0xFF).toByte()
        out[o++] = ((keyVersion ushr 8) and 0xFF).toByte()
        out[o++] = (epoch and 0xFF).toByte()
        out[o++] = ((epoch ushr 8) and 0xFF).toByte()
        out[o++] = ((epoch ushr 16) and 0xFF).toByte()
        out[o] = ((epoch ushr 24) and 0xFF).toByte()
        return out
    }

    /** Derive K_epoch for this K_room generation and hour. */
    fun deriveEpochKey(
        kRoom: ByteArray,
        keyVersion: Int,
        epoch: Long,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(kRoom.size == KEY_BYTES) { "K_room must be $KEY_BYTES bytes" }
        return hash.blake2b(info(keyVersion, epoch), kRoom, KEY_BYTES)
    }

    /** Is a received header epoch close enough to ours to be used? */
    fun epochAcceptable(headerEpoch: Long, localEpoch: Long): Boolean {
        val diff = if (headerEpoch > localEpoch) headerEpoch - localEpoch
                   else localEpoch - headerEpoch
        return diff <= EPOCH_SKEW
    }

    /** Serialize [keyVersion(2)][epoch(4)], little endian. */
    fun writeHeader(keyVersion: Int, epoch: Long): ByteArray {
        require(keyVersion in 0..0xFFFF) { "keyVersion out of range: $keyVersion" }
        require(epoch in 0L..MAX_EPOCH) { "epoch out of range: $epoch" }
        return byteArrayOf(
            (keyVersion and 0xFF).toByte(),
            ((keyVersion ushr 8) and 0xFF).toByte(),
            (epoch and 0xFF).toByte(),
            ((epoch ushr 8) and 0xFF).toByte(),
            ((epoch ushr 16) and 0xFF).toByte(),
            ((epoch ushr 24) and 0xFF).toByte(),
        )
    }

    /** Parse [keyVersion(2)][epoch(4)], little endian, from `buf` at `off`. */
    fun readHeader(buf: ByteArray, off: Int = 0): Pair<Int, Long> {
        require(off >= 0 && buf.size - off >= HEADER_BYTES) { "header truncated" }
        val ver = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
        val epoch = (buf[off + 2].toLong() and 0xFF) or
                    ((buf[off + 3].toLong() and 0xFF) shl 8) or
                    ((buf[off + 4].toLong() and 0xFF) shl 16) or
                    ((buf[off + 5].toLong() and 0xFF) shl 24)
        return ver to epoch
    }
}
