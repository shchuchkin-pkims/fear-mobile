package com.fear.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * The box operations a rotation entry needs.
 *
 * A seam for the same reason [KeyedHash] is one: lazysodium-android is an
 * AAR with a native library and will not load off a device, while the byte
 * framing around these calls is exactly what must not drift from the C.
 * Tests drive the framing through a stub and leave the box itself to
 * libsodium, which is the same library on both platforms.
 */
interface RotationBox {
    /** crypto_box_easy. Null if it fails. */
    fun seal(plaintext: ByteArray, nonce: ByteArray,
             recipientCurvePk: ByteArray, senderCurveSk: ByteArray): ByteArray?

    /** crypto_box_open_easy. Null if it fails - including a bad tag. */
    fun open(ciphertext: ByteArray, nonce: ByteArray,
             senderCurvePk: ByteArray, recipientCurveSk: ByteArray): ByteArray?

    /** crypto_sign_ed25519_pk_to_curve25519. */
    fun edPkToCurve(edPk: ByteArray): ByteArray?

    /** crypto_sign_ed25519_sk_to_curve25519. */
    fun edSkToCurve(edSk: ByteArray): ByteArray?
}

/** Production implementation: libsodium via lazysodium. */
object SodiumRotationBox : RotationBox {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    override fun seal(plaintext: ByteArray, nonce: ByteArray,
                      recipientCurvePk: ByteArray, senderCurveSk: ByteArray): ByteArray? {
        val ct = ByteArray(plaintext.size + Rotation.MAC_BYTES)
        val ok = ls.cryptoBoxEasy(ct, plaintext, plaintext.size.toLong(),
                                  nonce, recipientCurvePk, senderCurveSk)
        return if (ok) ct else null
    }

    override fun open(ciphertext: ByteArray, nonce: ByteArray,
                      senderCurvePk: ByteArray, recipientCurveSk: ByteArray): ByteArray? {
        if (ciphertext.size < Rotation.MAC_BYTES) return null
        val pt = ByteArray(ciphertext.size - Rotation.MAC_BYTES)
        val ok = ls.cryptoBoxOpenEasy(pt, ciphertext, ciphertext.size.toLong(),
                                      nonce, senderCurvePk, recipientCurveSk)
        return if (ok) pt else null
    }

    override fun edPkToCurve(edPk: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return if (ls.convertPublicKeyEd25519ToCurve25519(out, edPk)) out else null
    }

    override fun edSkToCurve(edSk: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return if (ls.convertSecretKeyEd25519ToCurve25519(out, edSk)) out else null
    }
}

/**
 * One member's copy of a new room key.
 *
 * Mirrors identity/rotation.c byte for byte. An entry is
 *
 *     [recipient_pk(32)][nonce(24)][crypto_box(K_room(32) || binding(32))]
 *
 * The recipient's identity key is in the clear because that is the address:
 * a member finds its own entry by it without trying to open every one. What
 * it costs is telling anyone who sees the bundle which identity keys are in
 * the room, which the participant list already tells them.
 *
 * The binding is what stops an entry being replayed somewhere it does not
 * belong. The box proves who sealed it; the binding proves what for:
 *
 *     BLAKE2b("fear.rotation.v1" || room || version || sender_pk || recipient_pk)
 *
 * The room is bound in but never sent, so a bundle lifted into another room
 * does not open and there is no room field in the message for anyone to
 * disagree with the envelope about.
 */
object Rotation {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val MAC_BYTES = 16

    /** 64 bytes of plaintext plus the box MAC. */
    const val CT_BYTES = 80
    const val ENTRY_BYTES = 32 + NONCE_BYTES + CT_BYTES

    /** Longer than any room id the protocol carries. */
    const val MAX_ROOM_ID = 512

    private const val BINDING_CTX = "fear.rotation.v1"

    /** K_room(32) || binding(32). */
    private const val PT_BYTES = 64

    /**
     * What an entry is for: this room, this generation, from them, to us.
     *
     * Public, because it says nothing that is not already on the wire - it
     * exists to be checked, not to be secret.
     */
    fun binding(
        roomId: String,
        newVersion: Int,
        senderPk: ByteArray,
        recipientPk: ByteArray,
        hasher: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        val room = roomId.toByteArray(Charsets.UTF_8)
        if (room.size > MAX_ROOM_ID) return null
        if (senderPk.size != 32 || recipientPk.size != 32) return null

        val ctx = BINDING_CTX.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(ctx.size + room.size + 2 + 32 + 32)
        var o = 0
        ctx.copyInto(data, o); o += ctx.size
        room.copyInto(data, o); o += room.size
        data[o++] = (newVersion and 0xFF).toByte()
        data[o++] = ((newVersion ushr 8) and 0xFF).toByte()
        senderPk.copyInto(data, o); o += 32
        recipientPk.copyInto(data, o)

        /* Unkeyed, like crypto_generichash_init(&st, NULL, 0, 32) in the C:
         * there is no secret here to key it with. */
        return hasher.blake2b(data, ByteArray(0), 32)
    }

    /**
     * Seal the new key for one member, with a nonce the caller chose.
     *
     * Exists for the tests, which need the same input to give the same
     * bytes; everything else wants [seal].
     */
    fun sealWithNonce(
        roomId: String,
        newVersion: Int,
        newKRoom: ByteArray,
        senderSk: ByteArray,
        senderPk: ByteArray,
        recipientPk: ByteArray,
        nonce: ByteArray,
        box: RotationBox = SodiumRotationBox,
        hasher: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        if (newKRoom.size != KEY_BYTES || nonce.size != NONCE_BYTES) return null
        if (recipientPk.size != 32 || senderPk.size != 32) return null

        val bind = binding(roomId, newVersion, senderPk, recipientPk, hasher) ?: return null

        val pt = ByteArray(PT_BYTES)
        newKRoom.copyInto(pt, 0)
        bind.copyInto(pt, KEY_BYTES)

        val curveSk = box.edSkToCurve(senderSk) ?: return null
        val curvePk = box.edPkToCurve(recipientPk) ?: run {
            curveSk.fill(0)
            return null
        }

        val ct = box.seal(pt, nonce, curvePk, curveSk)
        pt.fill(0)
        curveSk.fill(0)
        if (ct == null || ct.size != CT_BYTES) return null

        val entry = ByteArray(ENTRY_BYTES)
        recipientPk.copyInto(entry, 0)
        nonce.copyInto(entry, 32)
        ct.copyInto(entry, 32 + NONCE_BYTES)
        return entry
    }

    /** Seal the new key for one member. */
    fun seal(
        roomId: String,
        newVersion: Int,
        newKRoom: ByteArray,
        senderSk: ByteArray,
        senderPk: ByteArray,
        recipientPk: ByteArray,
        box: RotationBox = SodiumRotationBox,
        hasher: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        val nonce = ByteArray(NONCE_BYTES)
        java.security.SecureRandom().nextBytes(nonce)
        return sealWithNonce(roomId, newVersion, newKRoom, senderSk, senderPk,
                             recipientPk, nonce, box, hasher)
    }

    /**
     * Open the entry addressed to us.
     *
     * Null covers every refusal there is - not ours, wrong sender, tampered,
     * or replayed from another room or generation - because to the caller
     * they are one thing: this is not a key we may install.
     */
    fun open(
        roomId: String,
        newVersion: Int,
        recipientSk: ByteArray,
        recipientPk: ByteArray,
        senderPk: ByteArray,
        entry: ByteArray,
        box: RotationBox = SodiumRotationBox,
        hasher: KeyedHash = SodiumKeyedHash,
    ): ByteArray? {
        if (entry.size != ENTRY_BYTES) return null
        if (recipientPk.size != 32 || senderPk.size != 32) return null

        /* Addressed to us at all? */
        if (!constantTimeEquals(entry, 0, recipientPk, 0, 32)) return null

        val nonce = entry.copyOfRange(32, 32 + NONCE_BYTES)
        val ct = entry.copyOfRange(32 + NONCE_BYTES, ENTRY_BYTES)

        val curveSk = box.edSkToCurve(recipientSk) ?: return null
        val curvePk = box.edPkToCurve(senderPk) ?: run {
            curveSk.fill(0)
            return null
        }

        val pt = box.open(ct, nonce, curvePk, curveSk)
        curveSk.fill(0)
        if (pt == null || pt.size != PT_BYTES) return null

        /* The box only proves who sealed it; the binding proves what for. */
        val expect = binding(roomId, newVersion, senderPk, recipientPk, hasher)
        if (expect == null || !constantTimeEquals(pt, KEY_BYTES, expect, 0, 32)) {
            pt.fill(0)
            return null
        }

        val key = pt.copyOfRange(0, KEY_BYTES)
        pt.fill(0)
        return key
    }

    internal fun constantTimeEquals(
        a: ByteArray, aOff: Int, b: ByteArray, bOff: Int, len: Int,
    ): Boolean {
        if (a.size < aOff + len || b.size < bOff + len) return false
        var diff = 0
        for (i in 0 until len) diff = diff or (a[aOff + i].toInt() xor b[bOff + i].toInt())
        return diff == 0
    }
}
