package com.fear.crypto

/**
 * Per-direction media keys - Kotlin port of identity/media_keys.c
 * (audit items M3 / M5).
 *
 *     K_media = BLAKE2b(key  = K_call,
 *                       data = "fear.media.v1" || stream(1) || direction(1) || salt(16),
 *                       out  = 32)
 *
 * Today both directions of a call share one key and are told apart only by
 * a random 4-byte nonce prefix, while both peers start their sequence
 * counter at 0 - a prefix collision then reuses a key/nonce pair under GCM
 * (M3). The key is also derived deterministically from the room key, so
 * collisions pile up across sessions rather than being confined to one call
 * (M5). A key per direction plus a fresh 16-byte session salt removes both.
 *
 * The vectors in MediaKeysTest are the ones frozen on the C side.
 */
object MediaKeys {

    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val CTX = "fear.media.v1"
    const val SALT_CTX = "fear.media.salt.v1"

    const val STREAM_AUDIO = 0
    const val STREAM_VIDEO = 1

    const val DIR_CALLER_TO_CALLEE = 0
    const val DIR_CALLEE_TO_CALLER = 1

    /**
     * Fold the two HELLO halves into the session salt:
     *
     *     salt = BLAKE2b(key  = master,
     *                    data = "fear.media.salt.v1" || lo || hi,
     *                    out  = 16)
     *
     * lo/hi are the halves in byte order, which makes the fold commutative:
     * both ends reach the same salt without agreeing who spoke first. Each
     * peer contributes one half, so neither can dictate the salt on its own
     * and a replayed HELLO cannot push the other side back onto a salt it
     * has already used.
     */
    fun saltCombine(
        master: ByteArray,
        halfA: ByteArray,
        halfB: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(master.size == KEY_BYTES) { "master must be $KEY_BYTES bytes" }
        require(halfA.size == SALT_BYTES && halfB.size == SALT_BYTES) {
            "halves must be $SALT_BYTES bytes"
        }
        val cmp = compareHalves(halfA, halfB)
        val lo = if (cmp <= 0) halfA else halfB
        val hi = if (cmp <= 0) halfB else halfA

        val ctx = SALT_CTX.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(ctx.size + 2 * SALT_BYTES)
        ctx.copyInto(data, 0)
        lo.copyInto(data, ctx.size)
        hi.copyInto(data, ctx.size + SALT_BYTES)
        return hash.blake2b(data, master, SALT_BYTES)
    }

    /**
     * Decide the direction bit from the halves: the peer with the smaller
     * half is the caller, so the two ends cannot disagree.
     *
     * Returns null when the halves are equal - that is a reflected HELLO
     * far more often than a 1-in-2^128 coincidence, and either answer would
     * put both ends on the same key with both counters at 0. The caller
     * must abandon the call rather than pick.
     */
    fun roleFromHalves(localHalf: ByteArray, peerHalf: ByteArray): Boolean? {
        require(localHalf.size == SALT_BYTES && peerHalf.size == SALT_BYTES) {
            "halves must be $SALT_BYTES bytes"
        }
        val cmp = compareHalves(localHalf, peerHalf)
        if (cmp == 0) return null
        return cmp < 0
    }

    /** Unsigned byte-order comparison, matching memcmp on the C side. */
    private fun compareHalves(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until SALT_BYTES) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    /** The exact bytes hashed under the call master key. */
    fun info(stream: Int, direction: Int, salt: ByteArray): ByteArray {
        require(stream == STREAM_AUDIO || stream == STREAM_VIDEO) { "bad stream: $stream" }
        require(direction == DIR_CALLER_TO_CALLEE || direction == DIR_CALLEE_TO_CALLER) {
            "bad direction: $direction"
        }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val ctx = CTX.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(ctx.size + 2 + SALT_BYTES)
        ctx.copyInto(out, 0)
        out[ctx.size] = stream.toByte()
        out[ctx.size + 1] = direction.toByte()
        salt.copyInto(out, ctx.size + 2)
        return out
    }

    /** Derive one directional media key. */
    fun derive(
        master: ByteArray,
        stream: Int,
        direction: Int,
        salt: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): ByteArray {
        require(master.size == KEY_BYTES) { "master must be $KEY_BYTES bytes" }
        return hash.blake2b(info(stream, direction, salt), master, KEY_BYTES)
    }

    /**
     * Both keys this peer needs, picked from its role: the caller's send key
     * is the callee's receive key and vice versa, so each end calls this and
     * gets a matching pair.
     *
     * @return (sendKey, recvKey)
     */
    fun derivePair(
        master: ByteArray,
        stream: Int,
        isCaller: Boolean,
        salt: ByteArray,
        hash: KeyedHash = SodiumKeyedHash,
    ): Pair<ByteArray, ByteArray> {
        val sendDir = if (isCaller) DIR_CALLER_TO_CALLEE else DIR_CALLEE_TO_CALLER
        val recvDir = if (isCaller) DIR_CALLEE_TO_CALLER else DIR_CALLER_TO_CALLEE
        return derive(master, stream, sendDir, salt, hash) to
               derive(master, stream, recvDir, salt, hash)
    }
}
