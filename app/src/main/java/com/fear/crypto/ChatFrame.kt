package com.fear.crypto

import com.fear.Crypto

/**
 * Sealing a chat payload under the room key schedule (Phase C).
 *
 * Mirrors identity/chat_frame.c byte for byte. A chat frame's ciphertext
 * field is
 *
 *     [key_version(2)][epoch(4)][AES-256-GCM ciphertext || tag]
 *
 * encrypted not under K_room but under the epoch key derived from it:
 *
 *     K_epoch = BLAKE2b(key = K_room, "fear.epoch.v1" || version || epoch)
 *
 * Every member derives the same K_epoch from the same hour; nothing is
 * exchanged to agree on it, and K_epoch is never stored.
 *
 * The six header bytes are in the clear and bound into the additional data
 * along with the room and sender the server routes on, so a relay can read
 * them - it has to, to route - and cannot change them without the AEAD
 * failing. They sit in front of the ciphertext because the server reads the
 * message type and the length at fixed offsets and treats the ciphertext as
 * opaque; nothing in it changes.
 *
 * What this gives and what it does not: keys are separated per hour and
 * K_epoch never reaches storage. It is not forward secrecy on its own -
 * anyone holding K_room derives every epoch. That needs rotation on a
 * membership change and the old K_room destroyed.
 */
object ChatFrame {

    /** K_room generation on the wire. Zero until rotation bundles land. */
    const val KEY_VERSION = 0

    /** AES-256-GCM authentication tag, in bytes. */
    const val TAG_BYTES = 16

    /** Bytes a sealed payload adds to the plaintext. */
    const val OVERHEAD_BYTES = KeySchedule.HEADER_BYTES + TAG_BYTES

    /**
     * Additional data: what the server routes on, then the six bytes naming
     * the key. Lengths are little endian, matching wr_u16 in the console
     * client - the additional data has to be byte-identical on both platforms
     * or nothing decrypts across them.
     */
    private fun ad(room: ByteArray, name: ByteArray, header: ByteArray): ByteArray {
        val out = ByteArray(2 + room.size + 2 + name.size + header.size)
        var o = 0
        out[o++] = (room.size and 0xFF).toByte()
        out[o++] = ((room.size shr 8) and 0xFF).toByte()
        room.copyInto(out, o); o += room.size
        out[o++] = (name.size and 0xFF).toByte()
        out[o++] = ((name.size shr 8) and 0xFF).toByte()
        name.copyInto(out, o); o += name.size
        header.copyInto(out, o)
        return out
    }

    /**
     * Seal for a named epoch. Deterministic, which is what lets a test pin
     * the format against the C implementation and against a third one.
     */
    fun sealAt(
        kRoom: ByteArray,
        room: ByteArray,
        name: ByteArray,
        plain: ByteArray,
        nonce: ByteArray,
        keyVersion: Int,
        epoch: Long,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        val header = KeySchedule.writeHeader(keyVersion, epoch)
        val kEpoch = KeySchedule.deriveEpochKey(kRoom, keyVersion, epoch, hash)
        try {
            val ct = Crypto.encrypt(plain, ad(room, name, header), nonce, kEpoch) ?: return null
            return header + ct
        } finally {
            kEpoch.fill(0)
        }
    }

    /** Seal for the current hour and the current K_room generation. */
    fun seal(
        kRoom: ByteArray,
        room: ByteArray,
        name: ByteArray,
        plain: ByteArray,
        nonce: ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray? = sealAt(kRoom, room, name, plain, nonce, KEY_VERSION,
                           KeySchedule.epochFromUnix(nowSeconds), hash)

    /**
     * Open a sealed payload.
     *
     * The epoch is checked against `localEpoch` before anything is derived:
     * otherwise anyone able to name an epoch could make us derive an
     * unbounded number of keys, and a message from days ago is a replay
     * however well it authenticates.
     */
    fun openAt(
        kRoom: ByteArray,
        room: ByteArray,
        name: ByteArray,
        sealed: ByteArray,
        nonce: ByteArray,
        localEpoch: Long,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        if (sealed.size < KeySchedule.HEADER_BYTES + TAG_BYTES) return null

        val (version, epoch) = KeySchedule.readHeader(sealed)
        if (version != KEY_VERSION) return null
        if (!KeySchedule.epochAcceptable(epoch, localEpoch)) return null

        val kEpoch = KeySchedule.deriveEpochKey(kRoom, version, epoch, hash)
        try {
            val header = sealed.copyOfRange(0, KeySchedule.HEADER_BYTES)
            val body = sealed.copyOfRange(KeySchedule.HEADER_BYTES, sealed.size)
            return Crypto.decrypt(body, ad(room, name, header), nonce, kEpoch)
        } finally {
            kEpoch.fill(0)
        }
    }

    /** Open, taking the local epoch from the clock. */
    fun open(
        kRoom: ByteArray,
        room: ByteArray,
        name: ByteArray,
        sealed: ByteArray,
        nonce: ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray? = openAt(kRoom, room, name, sealed, nonce,
                           KeySchedule.epochFromUnix(nowSeconds), hash)
}
