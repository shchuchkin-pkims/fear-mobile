package com.fear.crypto

/**
 * The message that carries a rotation to everyone in the room.
 *
 * Mirrors identity/rotation_bundle.c byte for byte:
 *
 *     [format(1)][key_version(2)][sender_pk(32)][count(2)][entry × count]
 *
 * with the two-byte fields little-endian, as everywhere else on this wire.
 *
 * Nothing here is secret. Every entry is sealed to one member's identity
 * key, so the whole thing is safe to broadcast - which is what it is: it
 * goes out as a service frame, not sealed under K_room, because the member
 * who most needs it is the one who has just joined and holds no current
 * K_room to open an envelope with.
 *
 * The room is not on the wire. It is the room the frame arrived in, and it
 * is bound into every entry, so a bundle lifted into another room does not
 * open and there is no room field for anyone to disagree with the envelope
 * about.
 *
 * Whether the sender was *allowed* to rotate is deliberately not decided
 * here. This reports who sealed the bundle; the client decides whether that
 * is the member the room elected. Splitting it that way is what keeps both
 * testable: one knows about bytes, the other about rooms.
 */
object RotationBundle {

    const val FORMAT_VERSION = 0x01
    const val HEADER_BYTES = 37
    const val MAX_ENTRIES = 100

    /** A parsed bundle. The entries are a view into the caller's buffer. */
    data class View(
        val keyVersion: Int,
        val senderPk: ByteArray,
        val entryCount: Int,
        private val buffer: ByteArray,
    ) {
        /** Entry [i], as its own copy. */
        fun entry(i: Int): ByteArray {
            val off = HEADER_BYTES + i * Rotation.ENTRY_BYTES
            return buffer.copyOfRange(off, off + Rotation.ENTRY_BYTES)
        }

        override fun equals(other: Any?): Boolean =
            other is View && keyVersion == other.keyVersion &&
                senderPk.contentEquals(other.senderPk) &&
                entryCount == other.entryCount &&
                buffer.contentEquals(other.buffer)

        override fun hashCode(): Int =
            (31 * keyVersion + senderPk.contentHashCode()) * 31 + entryCount
    }

    fun size(n: Int): Int = HEADER_BYTES + n * Rotation.ENTRY_BYTES

    /**
     * Seal the new key for every member and put the copies in one message.
     *
     * Every member gets an entry including the sender, because a member that
     * cannot open its own bundle has rotated itself out of the room. A seal
     * that fails returns nothing rather than a short bundle: half a rotation
     * installs the new key for some members and leaves the rest unable to
     * read anything, which is worse than no rotation at all.
     */
    fun build(
        roomId: String,
        newVersion: Int,
        newKRoom: ByteArray,
        senderSk: ByteArray,
        senderPk: ByteArray,
        recipientPks: List<ByteArray>,
        box: RotationBox = SodiumRotationBox,
        hasher: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        if (recipientPks.isEmpty() || recipientPks.size > MAX_ENTRIES) return null
        if (senderPk.size != 32 || newKRoom.size != Rotation.KEY_BYTES) return null

        val out = ByteArray(size(recipientPks.size))
        out[0] = FORMAT_VERSION.toByte()
        writeU16Le(out, 1, newVersion)
        senderPk.copyInto(out, 3)
        writeU16Le(out, 35, recipientPks.size)

        var o = HEADER_BYTES
        for (pk in recipientPks) {
            val entry = Rotation.seal(roomId, newVersion, newKRoom, senderSk,
                                      senderPk, pk, box, hasher)
            if (entry == null) {
                out.fill(0)
                return null
            }
            entry.copyInto(out, o)
            o += Rotation.ENTRY_BYTES
        }
        return out
    }

    /**
     * Read a bundle's header.
     *
     * The declared count and the actual length have to agree exactly. A
     * bundle with room for fewer entries than it claims would have a reader
     * walk off the end; one with room for more has something in it nobody is
     * looking at.
     */
    fun parse(buf: ByteArray): View? {
        if (buf.size < HEADER_BYTES) return null
        if ((buf[0].toInt() and 0xFF) != FORMAT_VERSION) return null

        val count = readU16Le(buf, 35)
        if (count == 0 || count > MAX_ENTRIES) return null
        if (buf.size != size(count)) return null

        return View(
            keyVersion = readU16Le(buf, 1),
            senderPk = buf.copyOfRange(3, 35),
            entryCount = count,
            buffer = buf.copyOf(),
        )
    }

    /**
     * Find our entry and open it.
     *
     * The address is compared first only as a shortcut: an entry meant for
     * somebody else would not open anyway, since the recipient is bound into
     * what it authenticates.
     */
    fun openFor(
        view: View,
        roomId: String,
        recipientSk: ByteArray,
        recipientPk: ByteArray,
        box: RotationBox = SodiumRotationBox,
        hasher: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        for (i in 0 until view.entryCount) {
            val entry = view.entry(i)
            if (!Rotation.constantTimeEquals(entry, 0, recipientPk, 0, 32)) continue
            return Rotation.open(roomId, view.keyVersion, recipientSk, recipientPk,
                                 view.senderPk, entry, box, hasher)
        }
        return null
    }

    private fun writeU16Le(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun readU16Le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
}
