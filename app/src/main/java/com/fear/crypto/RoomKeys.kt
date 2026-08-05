package com.fear.crypto

/**
 * The generations of K_room a member holds, and who gets to replace them.
 *
 * Mirrors identity/room_keys.c. Sealing takes exactly one generation - the
 * current one. Opening takes the set, because a rotation does not stop the
 * messages already in flight under the generation it replaces: they arrive
 * after it, and a receiver that had already forgotten how to read them would
 * refuse messages that were perfectly legitimate when sent.
 *
 * The old generation is kept for [GRACE_SECONDS] and then gone. Reinstalling
 * one that is still held does not refresh that deadline, so a replayed
 * bundle cannot keep a retired key alive.
 */
class RoomKeys {

    /** How long a replaced generation stays readable. */
    companion object {
        const val GRACE_SECONDS = 60L

        /**
         * Who rotates: the member with the lowest identity key.
         *
         * No election message, no timer, no tie - every member computes the
         * same answer from the same list, so there is nothing to agree on.
         * A member without an identity cannot be chosen: there would be no
         * key to address a bundle from.
         *
         * The comparison is over unsigned bytes, because that is what
         * memcmp does on the other side of this wire.
         */
        @JvmStatic
        fun isRotator(members: List<Member>, me: ByteArray): Boolean {
            if (members.isEmpty() || me.size != 32) return false

            val known = members.filter { it.hasIdentity }
            if (known.none { it.pk.contentEquals(me) }) return false
            return known.none { compareUnsigned(it.pk, me) < 0 }
        }

        internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }

    /** A member of the room, as the election needs to see them. */
    data class Member(val pk: ByteArray, val hasIdentity: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Member && hasIdentity == other.hasIdentity && pk.contentEquals(other.pk)

        override fun hashCode(): Int = 31 * pk.contentHashCode() + hasIdentity.hashCode()
    }

    private class Slot(var key: ChatFrame.RoomKey, var expiresAt: Long)

    private val slots = arrayOfNulls<Slot>(ChatFrame.MAX_KEYS)

    var currentVersion: Int = 0
        private set

    /** Start from the generation the room key exchange produced. */
    fun init(version: Int, key: ByteArray) {
        clear()
        slots[0] = Slot(ChatFrame.RoomKey(version, key.copyOf()), 0L)
        currentVersion = version
    }

    /**
     * Take a new generation in. Returns true if it was installed.
     *
     * A generation already held is not reinstalled - that is what stops a
     * replayed bundle from extending the grace period of a key that should
     * be expiring.
     */
    fun install(version: Int, key: ByteArray, nowSeconds: Long): Boolean {
        if (slots.any { it != null && it.key.version == version }) return false

        val outgoing = slots.firstOrNull { it != null && it.key.version == currentVersion }
        clear()

        slots[0] = Slot(ChatFrame.RoomKey(version, key.copyOf()), 0L)
        currentVersion = version

        if (outgoing != null && ChatFrame.MAX_KEYS > 1) {
            slots[1] = Slot(outgoing.key, nowSeconds + GRACE_SECONDS)
        }
        return true
    }

    /** Drop the generations whose grace period has run out. */
    fun expire(nowSeconds: Long) {
        for (i in slots.indices) {
            val s = slots[i] ?: continue
            if (s.expiresAt == 0L) continue          // the current one
            if (nowSeconds < s.expiresAt) continue
            s.key.key.fill(0)
            slots[i] = null
        }
    }

    fun clear() {
        for (i in slots.indices) {
            slots[i]?.key?.key?.fill(0)
            slots[i] = null
        }
        currentVersion = 0
    }

    /** The generation to seal under, or null before anything is held. */
    fun current(): ChatFrame.RoomKey? =
        slots.firstOrNull { it != null && it.key.version == currentVersion }?.key

    /** Every generation still readable, current first. */
    fun ring(): List<ChatFrame.RoomKey> {
        val out = ArrayList<ChatFrame.RoomKey>(ChatFrame.MAX_KEYS)
        current()?.let { out.add(it) }
        for (s in slots) {
            if (s == null) continue
            if (out.isNotEmpty() && s.key.version == out[0].version) continue
            out.add(s.key)
        }
        return out
    }
}
