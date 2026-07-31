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

    const val STREAM_AUDIO = 0
    const val STREAM_VIDEO = 1

    const val DIR_CALLER_TO_CALLEE = 0
    const val DIR_CALLEE_TO_CALLER = 1

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
