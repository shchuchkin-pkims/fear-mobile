package com.fear.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation, on the byte level where the two platforms have to agree.
 *
 * A room with a desktop client and a phone in it rotates only if both build
 * the same bytes from the same inputs; if they do not, the room splits in
 * two and neither side sees an error - each just stops being able to read
 * the other. So the binding vectors here are the ones the C test pins, taken
 * from the C implementation, and the bundle layout is checked field by
 * field rather than by round-tripping through this same code.
 *
 * The box itself is stubbed. That is deliberate: it is libsodium on both
 * sides, called with the same parameters, and what a JVM reimplementation of
 * crypto_box would prove is that the reimplementation is right. What is
 * worth testing here is everything around it - the binding, the addressing,
 * the framing, and the refusals.
 */
class RotationTest {

    /* BouncyCastle rather than libsodium: a JVM unit test has no native
     * library for JNA to load, and the point here is the byte string, not
     * which library produced it. */
    private val bc = KeyedHash { data, key, outLen ->
        val d = org.bouncycastle.crypto.digests.Blake2bDigest(
            if (key.isEmpty()) null else key, outLen, null, null,
        )
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    /**
     * Round-trips the plaintext and nothing else.
     *
     * Every refusal this test checks comes from the binding or the address,
     * both of which are ours; letting the stub open anything is what makes
     * that visible instead of hiding behind a MAC failure.
     */
    private val stubBox = object : RotationBox {
        override fun seal(plaintext: ByteArray, nonce: ByteArray,
                          recipientCurvePk: ByteArray, senderCurveSk: ByteArray): ByteArray =
            plaintext + ByteArray(Rotation.MAC_BYTES) { 0x5A }

        override fun open(ciphertext: ByteArray, nonce: ByteArray,
                          senderCurvePk: ByteArray, recipientCurveSk: ByteArray): ByteArray? =
            if (ciphertext.size < Rotation.MAC_BYTES) null
            else ciphertext.copyOfRange(0, ciphertext.size - Rotation.MAC_BYTES)

        override fun edPkToCurve(edPk: ByteArray) = edPk.copyOfRange(0, 32)
        override fun edSkToCurve(edSk: ByteArray) = edSk.copyOfRange(0, 32)
    }

    private fun bin(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /* crypto_sign_seed_keypair over a seed of 0x11 and 0x22 - the parties the
     * C test uses, so the vectors below can be regenerated from it. */
    private val senderPk = bin("d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737")
    private val recipientPk = bin("a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f0")
    private val senderSk = ByteArray(64) { 0x31 }
    private val recipientSk = ByteArray(64) { 0x32 }
    private val kNew = ByteArray(32) { (0xC0 + it).toByte() }

    @Test
    fun `binding matches the C implementation`() {
        val b = Rotation.binding("live", 5, senderPk, recipientPk, bc)
        assertEquals("eec1a881cf95bcab46ffa70d341249fdaccb26240acad02cf216f00382d9ea73", hex(b!!))
    }

    @Test
    fun `binding hashes UTF-8 bytes and a little-endian version`() {
        // Version 258 is 0x0102: written the other way round the vector breaks.
        // The room id is non-ASCII, so a platform hashing UTF-16 breaks too.
        val b = Rotation.binding("комната", 258, senderPk, recipientPk, bc)
        assertEquals("c5a5d34c2feb013b389232d1c2b2f04d86c819630d4a161d7f8cc23a70b6f0f8", hex(b!!))
    }

    @Test
    fun `binding separates rooms, versions, senders and recipients`() {
        val base = Rotation.binding("live", 5, senderPk, recipientPk, bc)!!
        assertFalse(base.contentEquals(Rotation.binding("live2", 5, senderPk, recipientPk, bc)!!))
        assertFalse(base.contentEquals(Rotation.binding("live", 6, senderPk, recipientPk, bc)!!))
        assertFalse(base.contentEquals(Rotation.binding("live", 5, recipientPk, recipientPk, bc)!!))
        assertFalse(base.contentEquals(Rotation.binding("live", 5, senderPk, senderPk, bc)!!))
    }

    @Test
    fun `bundle header is laid out as the C writes it`() {
        val third = ByteArray(32) { 0x77 }
        val bundle = RotationBundle.build(
            "live", 0x0102, kNew, senderSk, senderPk,
            listOf(senderPk, recipientPk, third), stubBox, bc,
        )!!

        assertEquals(RotationBundle.size(3), bundle.size)
        assertEquals(RotationBundle.FORMAT_VERSION, bundle[0].toInt() and 0xFF)
        assertEquals(0x02, bundle[1].toInt() and 0xFF)      // version, low byte first
        assertEquals(0x01, bundle[2].toInt() and 0xFF)
        assertArrayEquals(senderPk, bundle.copyOfRange(3, 35))
        assertEquals(3, bundle[35].toInt() and 0xFF)
        assertEquals(0, bundle[36].toInt() and 0xFF)

        // Each entry starts with the address it is for, in the order given.
        for ((i, pk) in listOf(senderPk, recipientPk, third).withIndex()) {
            val off = RotationBundle.HEADER_BYTES + i * Rotation.ENTRY_BYTES
            assertArrayEquals(pk, bundle.copyOfRange(off, off + 32))
        }
    }

    @Test
    fun `parse reads back what build wrote`() {
        val bundle = RotationBundle.build(
            "live", 5, kNew, senderSk, senderPk,
            listOf(senderPk, recipientPk), stubBox, bc,
        )!!
        val view = RotationBundle.parse(bundle)!!
        assertEquals(5, view.keyVersion)
        assertEquals(2, view.entryCount)
        assertArrayEquals(senderPk, view.senderPk)
    }

    @Test
    fun `a bundle that disagrees with itself is refused`() {
        val bundle = RotationBundle.build(
            "live", 5, kNew, senderSk, senderPk,
            listOf(senderPk, recipientPk), stubBox, bc,
        )!!

        assertNull("no header at all",
            RotationBundle.parse(bundle.copyOfRange(0, RotationBundle.HEADER_BYTES - 1)))

        val wrongFormat = bundle.copyOf(); wrongFormat[0] = 0x02
        assertNull("a format we do not speak", RotationBundle.parse(wrongFormat))

        val tooMany = bundle.copyOf(); tooMany[35] = 3
        assertNull("claims one more than it has", RotationBundle.parse(tooMany))

        val none = bundle.copyOf(); none[35] = 0
        assertNull("claims none", RotationBundle.parse(none))

        val absurd = bundle.copyOf(); absurd[35] = 0xFF.toByte(); absurd[36] = 0xFF.toByte()
        assertNull("more than we will look at", RotationBundle.parse(absurd))

        // Trailing bytes nobody is looking at are a refusal too.
        assertNull("longer than its count", RotationBundle.parse(bundle + byteArrayOf(0)))
    }

    @Test
    fun `build refuses an empty room and more entries than the format holds`() {
        assertNull(RotationBundle.build("live", 5, kNew, senderSk, senderPk,
                                        emptyList(), stubBox, bc))
        val many = (0 until RotationBundle.MAX_ENTRIES + 1).map { ByteArray(32) { _ -> it.toByte() } }
        assertNull(RotationBundle.build("live", 5, kNew, senderSk, senderPk, many, stubBox, bc))
    }

    @Test
    fun `each member opens its own entry and nobody else's`() {
        val bundle = RotationBundle.build(
            "live", 5, kNew, senderSk, senderPk,
            listOf(senderPk, recipientPk), stubBox, bc,
        )!!
        val view = RotationBundle.parse(bundle)!!

        assertArrayEquals(kNew,
            RotationBundle.openFor(view, "live", recipientSk, recipientPk, stubBox, bc))
        // The sender gets an entry too: a member that cannot open its own
        // bundle has rotated itself out of the room.
        assertArrayEquals(kNew,
            RotationBundle.openFor(view, "live", senderSk, senderPk, stubBox, bc))

        val stranger = ByteArray(32) { 0x09 }
        assertNull("no entry addressed to them",
            RotationBundle.openFor(view, "live", recipientSk, stranger, stubBox, bc))
    }

    @Test
    fun `an entry belongs to one room and one generation`() {
        val bundle = RotationBundle.build(
            "live", 5, kNew, senderSk, senderPk, listOf(recipientPk), stubBox, bc,
        )!!
        val view = RotationBundle.parse(bundle)!!

        assertNull("lifted into another room",
            RotationBundle.openFor(view, "other", recipientSk, recipientPk, stubBox, bc))

        // Claiming it installs a different generation breaks the binding.
        val moved = bundle.copyOf(); moved[1] = 6
        val movedView = RotationBundle.parse(moved)!!
        assertNull(RotationBundle.openFor(movedView, "live", recipientSk, recipientPk, stubBox, bc))

        // And so does claiming somebody else sealed it.
        val forged = bundle.copyOf(); recipientPk.copyInto(forged, 3)
        val forgedView = RotationBundle.parse(forged)!!
        assertNull(RotationBundle.openFor(forgedView, "live", recipientSk, recipientPk, stubBox, bc))
    }

    // --- the ring ---------------------------------------------------------

    @Test
    fun `the ring holds the replaced generation for the grace period`() {
        val rk = RoomKeys()
        rk.init(0, ByteArray(32) { 0x01 })
        assertEquals(1, rk.ring().size)

        assertTrue(rk.install(1, ByteArray(32) { 0x02 }, 1000L))
        assertEquals(1, rk.current()!!.version)
        assertEquals(listOf(1, 0), rk.ring().map { it.version })

        rk.expire(1000L + RoomKeys.GRACE_SECONDS - 1)
        assertEquals(2, rk.ring().size)

        rk.expire(1000L + RoomKeys.GRACE_SECONDS)
        assertEquals(listOf(1), rk.ring().map { it.version })
    }

    @Test
    fun `reinstalling a held generation does not extend its life`() {
        val rk = RoomKeys()
        rk.init(0, ByteArray(32) { 0x01 })
        rk.install(1, ByteArray(32) { 0x02 }, 1000L)

        // A replayed bundle for generation 0 must not push its deadline out.
        assertFalse(rk.install(0, ByteArray(32) { 0x01 }, 1050L))
        rk.expire(1000L + RoomKeys.GRACE_SECONDS)
        assertEquals(listOf(1), rk.ring().map { it.version })
    }

    // --- the election -----------------------------------------------------

    @Test
    fun `the lowest identity key rotates`() {
        val low = ByteArray(32) { 0x01 }
        val high = ByteArray(32) { 0x02 }
        val members = listOf(RoomKeys.Member(low, true), RoomKeys.Member(high, true))
        assertTrue(RoomKeys.isRotator(members, low))
        assertFalse(RoomKeys.isRotator(members, high))
    }

    @Test
    fun `keys are compared as unsigned bytes`() {
        // 0x80 is above 0x7f. Compared as signed bytes it is below, and the
        // two platforms would elect different members - which is the room
        // splitting in two.
        val lower = ByteArray(32) { 0x7F }
        val higher = ByteArray(32) { 0x80.toByte() }
        val members = listOf(RoomKeys.Member(lower, true), RoomKeys.Member(higher, true))
        assertTrue(RoomKeys.isRotator(members, lower))
        assertFalse(RoomKeys.isRotator(members, higher))
    }

    @Test
    fun `a member without an identity is never chosen and never counted`() {
        val me = ByteArray(32) { 0x05 }
        val nameless = RoomKeys.Member(ByteArray(32) { 0x01 }, false)
        assertTrue("the nameless one cannot be elected over us",
            RoomKeys.isRotator(listOf(nameless, RoomKeys.Member(me, true)), me))
        assertFalse("and cannot elect itself",
            RoomKeys.isRotator(listOf(nameless, RoomKeys.Member(me, true)), nameless.pk))
    }

    @Test
    fun `a room we are not in is not ours to rotate`() {
        val me = ByteArray(32) { 0x05 }
        val others = listOf(RoomKeys.Member(ByteArray(32) { 0x06 }, true))
        assertFalse(RoomKeys.isRotator(others, me))
        assertFalse(RoomKeys.isRotator(emptyList(), me))
    }

    @Test
    fun `sealing and opening survive a full entry`() {
        val entry = Rotation.sealWithNonce(
            "live", 5, kNew, senderSk, senderPk, recipientPk,
            ByteArray(Rotation.NONCE_BYTES) { 0x2B }, stubBox, bc,
        )
        assertNotNull(entry)
        assertEquals(Rotation.ENTRY_BYTES, entry!!.size)
        assertArrayEquals(recipientPk, entry.copyOfRange(0, 32))
        assertArrayEquals(ByteArray(Rotation.NONCE_BYTES) { 0x2B },
                          entry.copyOfRange(32, 32 + Rotation.NONCE_BYTES))

        assertArrayEquals(kNew, Rotation.open("live", 5, recipientSk, recipientPk,
                                              senderPk, entry, stubBox, bc))
    }
}
