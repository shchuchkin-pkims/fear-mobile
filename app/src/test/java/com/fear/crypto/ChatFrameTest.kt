package com.fear.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sealed bytes here are the same vector tests/test_chat_frame.c pins, and
 * both came from a third implementation - hashlib for BLAKE2b, the Python
 * cryptography package for AES-GCM. Three implementations agreeing on one
 * byte string is what says the two platforms can read each other's chat,
 * short of putting a phone and a desktop in a room together.
 */
class ChatFrameTest {

    /* BouncyCastle rather than libsodium: a JVM unit test has no native
     * library for JNA to load, and the point here is the byte string, not
     * which library produced it. */
    private val bc = KeyedHash { data, key, outLen ->
        val d = org.bouncycastle.crypto.digests.Blake2bDigest(key, outLen, null, null)
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private fun bin(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val kRoom = ByteArray(32) { it.toByte() }
    private val room = "live".toByteArray(Charsets.UTF_8)
    private val name = "pc".toByteArray(Charsets.UTF_8)
    private val plain = "the quick brown fox".toByteArray(Charsets.UTF_8)
    private val nonce = ByteArray(12) { (0xA0 + it).toByte() }
    private val epoch = 0x0005A1B2L

    private val sealedHex =
        "0000b2a10500d63d254b2dba6ef0704b85255ce16a7df7b208889d33f064" +
        "8ac2522f82be8b5a82198c"

    @Test
    fun sealedFrameIsByteExact() {
        val sealed = ChatFrame.sealAt(kRoom, room, name, plain, nonce,
                                      ChatFrame.KEY_VERSION, epoch, bc)
        assertNotNull(sealed)
        assertEquals(sealedHex, hex(sealed!!))
        assertEquals(KeySchedule.HEADER_BYTES + plain.size + ChatFrame.TAG_BYTES, sealed.size)

        // The header is in the clear and names the key.
        assertArrayEquals(bin("0000b2a10500"), sealed.copyOfRange(0, 6))
    }

    @Test
    fun roundTrips() {
        val sealed = ChatFrame.sealAt(kRoom, room, name, plain, nonce,
                                      ChatFrame.KEY_VERSION, epoch, bc)!!
        val opened = ChatFrame.openAt(kRoom, room, name, sealed, nonce, epoch, bc)
        assertNotNull(opened)
        assertArrayEquals(plain, opened)
    }

    @Test
    fun oneEpochEitherWayIsSkewAndAnythingFurtherIsAReplay() {
        val sealed = bin(sealedHex)
        assertNotNull(ChatFrame.openAt(kRoom, room, name, sealed, nonce, epoch + 1, bc))
        assertNotNull(ChatFrame.openAt(kRoom, room, name, sealed, nonce, epoch - 1, bc))
        assertNull(ChatFrame.openAt(kRoom, room, name, sealed, nonce, epoch + 2, bc))
        assertNull(ChatFrame.openAt(kRoom, room, name, sealed, nonce, epoch - 2, bc))
    }

    @Test
    fun theHeaderIsAuthenticatedNotMerelyCarried() {
        val sealed = bin(sealedHex)

        // A neighbouring epoch, still inside the skew: accepted by the check,
        // refused by the tag.
        val movedEpoch = sealed.copyOf().also { it[2] = (it[2].toInt() xor 0x01).toByte() }
        assertNull(ChatFrame.openAt(kRoom, room, name, movedEpoch, nonce, epoch, bc))

        // A K_room generation we do not have.
        val movedVersion = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(ChatFrame.openAt(kRoom, room, name, movedVersion, nonce, epoch, bc))
    }

    @Test
    fun theRoomAndTheSenderAreBoundToo() {
        val sealed = bin(sealedHex)
        assertNull(ChatFrame.openAt(kRoom, "other".toByteArray(), name, sealed, nonce, epoch, bc))
        assertNull(ChatFrame.openAt(kRoom, room, "mallory".toByteArray(), sealed, nonce, epoch, bc))
    }

    @Test
    fun tooShortToHoldAHeader() {
        assertNull(ChatFrame.openAt(kRoom, room, name, ByteArray(6), nonce, epoch, bc))
        assertNull(ChatFrame.openAt(kRoom, room, name, ByteArray(0), nonce, epoch, bc))
    }

    @Test
    fun anotherRoomKeyDerivesAnotherEpochKey() {
        val other = kRoom.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(ChatFrame.openAt(other, room, name, bin(sealedHex), nonce, epoch, bc))
    }
}
