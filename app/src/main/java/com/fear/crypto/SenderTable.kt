package com.fear.crypto

/**
 * Per-sender key slots and replay windows - Kotlin port of
 * identity/media_senders.c.
 *
 * A call has one shared key and N senders, each encrypting under a key
 * derived from its own announcement. This is the receiver-side bookkeeping:
 * which senders exist, which key opens an arriving packet, and whether that
 * packet is fresh.
 *
 * Android has had no replay protection at all until now, so this lands with
 * the sender table rather than after it: a desktop that resets its window on
 * a stream restart and an Android that has no window would disagree about
 * what a legitimate restart even looks like.
 *
 * The rules and their reasons are the same as on the C side, and each is
 * protecting against something specific rather than being defensive by
 * habit - see the comments at each one.
 */
object SenderTable {

    /** Key slots. Matches the transport limit so the table cannot be outrun. */
    const val MAX_SLOTS = 32

    /** Live slots allowed to share one 3-byte SID. */
    const val SID_CAP = 2

    /** Counter domains: audio and video. */
    const val STREAMS = 2

    /** Sliding replay window width, in packets. */
    const val WINDOW_BITS = 64

    enum class Status { OK, ERR_ARGS, ERR_FULL, ERR_SID_CAP, ERR_SELF, ERR_NOT_FOUND }

    enum class Verdict { FRESH, REPLAY, JUMP }

    /**
     * A sliding window over accepted counters.
     *
     * Forward jumps beyond [MediaKeys.MAX_CTR_JUMP] are refused outright: a
     * window that advances to any counter that decrypts lets one forged
     * packet at a huge value silence the real sender for the rest of the
     * call.
     */
    class Window {
        var maxSeq: Long = 0L
            private set
        var bitmap: Long = 0L
            private set
        var started: Boolean = false
            private set

        fun copyFrom(other: Window) {
            maxSeq = other.maxSeq
            bitmap = other.bitmap
            started = other.started
        }

        fun accept(seq: Long): Verdict {
            if (seq < 0) return Verdict.REPLAY

            if (!started) {
                // A stream may legitimately be joined in progress, so the
                // first packet anchors the window wherever it lands.
                started = true
                maxSeq = seq
                bitmap = 1L
                return Verdict.FRESH
            }

            if (seq > maxSeq) {
                val advance = seq - maxSeq
                if (advance > MediaKeys.MAX_CTR_JUMP) return Verdict.JUMP
                bitmap = if (advance >= WINDOW_BITS) 1L else (bitmap shl advance.toInt()) or 1L
                maxSeq = seq
                return Verdict.FRESH
            }

            val behind = maxSeq - seq
            if (behind >= WINDOW_BITS) return Verdict.REPLAY   // older than the window
            val bit = 1L shl behind.toInt()
            if (bitmap and bit != 0L) return Verdict.REPLAY    // already seen
            bitmap = bitmap or bit
            return Verdict.FRESH
        }
    }

    class Slot(
        val sid: ByteArray,
        val salt: ByteArray,
        val idbind: ByteArray,
        val keyVersion: Int,
        val keys: Array<ByteArray>,
        val installOrder: Long,
    ) {
        val windows = Array(STREAMS) { Window() }
    }

    /** What survives a slot being released, so its counters cannot be rewound. */
    private class Tomb(val salt: ByteArray, val idbind: ByteArray) {
        val windows = Array(STREAMS) { Window() }
    }

    /**
     * One table per call.
     *
     * @param ownSalt our own salt, so the table can refuse it coming back at
     *   us - installing it would create a peer whose keys are our send keys
     */
    class Table(
        private val kCall: ByteArray,
        private val callId: ByteArray,
        private val ownSalt: ByteArray? = null,
        private val hash: KeyedHash = SodiumKeyedHash,
    ) {
        init {
            require(kCall.size == MediaKeys.KEY_BYTES) { "bad K_call size" }
            require(callId.size == MediaKeys.CALLID_BYTES) { "bad callId size" }
            require(callId.any { it != 0.toByte() }) { "callId must not be all zero" }
        }

        private val slots = arrayOfNulls<Slot>(MAX_SLOTS)
        private val tombs = ArrayList<Tomb>()
        private var nextOrder = 1L

        fun count(): Int = slots.count { it != null }

        fun slotAt(idx: Int): Slot? = slots.getOrNull(idx)

        /**
         * Install a sender announced by a verified HELLO, or return the
         * existing slot if this exact announcement is already present.
         *
         * Slots are keyed by the whole announcement, not by the salt alone:
         * matching on salt would let someone who captured a salt and
         * announced it first under another identity own the slot, after
         * which the genuine HELLO would be treated as already installed and
         * ignored.
         */
        fun install(
            salt: ByteArray,
            idbind: ByteArray,
            keyVersion: Int,
        ): Pair<Status, Int> {
            if (salt.size != MediaKeys.SALT_BYTES) return Status.ERR_ARGS to -1
            if (idbind.size != MediaKeys.IDBIND_BYTES) return Status.ERR_ARGS to -1
            if (keyVersion !in 0..0xFFFF) return Status.ERR_ARGS to -1

            if (ownSalt != null && ownSalt.contentEquals(salt)) return Status.ERR_SELF to -1

            val existing = slots.indexOfFirst {
                it != null && it.keyVersion == keyVersion &&
                    it.salt.contentEquals(salt) && it.idbind.contentEquals(idbind)
            }
            // Idempotent: a repeated HELLO is a no-op, not a way to reset a
            // replay window.
            if (existing >= 0) return Status.OK to existing

            val sid = MediaKeys.senderId(kCall, callId, salt, idbind, hash)

            // A 3-byte tag collides for real, so collisions must be
            // survivable - but capped, or a member could grind salts until
            // every lookup has to try many keys.
            val sharing = slots.count { it != null && it.sid.contentEquals(sid) }
            if (sharing >= SID_CAP) return Status.ERR_SID_CAP to -1

            val free = slots.indexOfFirst { it == null }
            // Refuse rather than evict: eviction under pressure is a way for
            // one member to push another out of the call.
            if (free < 0) return Status.ERR_FULL to -1

            val keys = arrayOf(
                MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, keyVersion, callId, salt, idbind, hash),
                MediaKeys.deriveSender(kCall, MediaKeys.STREAM_VIDEO, keyVersion, callId, salt, idbind, hash),
            )
            val slot = Slot(sid, salt.copyOf(), idbind.copyOf(), keyVersion, keys, nextOrder++)

            // Resume the counters if this announcement was here before,
            // otherwise a release-and-reinstall rewinds the window and a
            // recording becomes replayable.
            val tomb = tombs.firstOrNull {
                it.salt.contentEquals(salt) && it.idbind.contentEquals(idbind)
            }
            if (tomb != null) {
                for (s in 0 until STREAMS) slot.windows[s].copyFrom(tomb.windows[s])
                tombs.remove(tomb)
            }

            slots[free] = slot
            return Status.OK to free
        }

        /** Release a slot, keeping its replay state in a tombstone. */
        fun retire(idx: Int): Status {
            val slot = slots.getOrNull(idx) ?: return Status.ERR_NOT_FOUND
            tombs.removeAll {
                it.salt.contentEquals(slot.salt) && it.idbind.contentEquals(slot.idbind)
            }
            val tomb = Tomb(slot.salt, slot.idbind)
            for (s in 0 until STREAMS) tomb.windows[s].copyFrom(slot.windows[s])
            tombs.add(tomb)
            // Losing the oldest tombstone matters less than losing the newest.
            while (tombs.size > MAX_SLOTS) tombs.removeAt(0)
            slots[idx] = null
            return Status.OK
        }

        /**
         * Candidate slots for a wire tag, oldest install first. Ordering by
         * recency would let a late arrival with a ground-out colliding salt
         * be tried first and shadow whoever was already here.
         */
        fun findBySid(sid: ByteArray): List<Int> =
            slots.withIndex()
                .filter { (_, s) -> s != null && s.sid.contentEquals(sid) }
                .sortedBy { (_, s) -> s!!.installOrder }
                .take(SID_CAP)
                .map { it.index }

        /** The decryption key for a slot and counter domain, or null. */
        fun key(idx: Int, stream: Int): ByteArray? {
            if (stream != MediaKeys.STREAM_AUDIO && stream != MediaKeys.STREAM_VIDEO) return null
            return slots.getOrNull(idx)?.keys?.get(stream)
        }

        /**
         * Offer a counter to a slot's window. Call this only after the packet
         * has been authenticated: a forged counter allowed to move the window
         * is exactly the denial of service the jump limit guards against.
         */
        fun acceptSeq(idx: Int, stream: Int, seq: Long): Verdict {
            if (stream != MediaKeys.STREAM_AUDIO && stream != MediaKeys.STREAM_VIDEO) return Verdict.REPLAY
            val slot = slots.getOrNull(idx) ?: return Verdict.REPLAY
            return slot.windows[stream].accept(seq)
        }
    }
}
