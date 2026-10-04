package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors tests/test_media_packet.c, including the frozen packets.
 *
 * Three implementations agree on these bytes: the expected values were
 * produced by Python's `cryptography`, the C side reproduces them through
 * libsodium, and this test reproduces them through the JCE. That is what
 * makes the framing safe to hand-write on two platforms - and it is exactly
 * the check that was missing while this code lived inline in the call
 * managers.
 */
class MediaPacketTest {

    private val bc = KeyedHash { data, key, outLen ->
        val d = Blake2bDigest(key, outLen, null, null)
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bin(h: String) = ByteArray(h.length / 2) {
        h.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val kCall = ByteArray(32) { it.toByte() }
    private val callId = ByteArray(16) { (0x10 + it).toByte() }
    private val salt = ByteArray(16) { (0xA0 + it).toByte() }
    private val zeros = MediaKeys.UNSIGNED_IDBIND

    private val key = MediaKeys.deriveSender(
        kCall, MediaKeys.STREAM_AUDIO, 0, callId, salt, zeros, bc)
    private val sid = MediaKeys.senderId(kCall, callId, salt, zeros, bc)

    @Test
    fun theKeyAndTagAreTheOnesTheVectorsAssume() {
        assertEquals("0bc7ef4f1ac0a8c46391f4f153ef956893475f84be6a061d88a08139a8554de0", hex(key))
        assertEquals("0f2cee", hex(sid))
    }

    @Test
    fun frozenPackets() {
        assertEquals(
            "010f2cee00000000005f5197e880b22213154f7aa71c525e79ba489b0569",
            hex(MediaPacket.encrypt(0x01, sid, 0, key, "hello".toByteArray())!!))
        assertEquals(
            "010f2cee0102030405543721e24f1bcf13d6e7139ea7e56a2ab4dc9f352f",
            hex(MediaPacket.encrypt(0x01, sid, 0x0102030405L, key, "hello".toByteArray())!!))
        assertEquals(
            "010f2cee0000000007ea70f1d8ef7d4158c07dd4435ae0ba3b1cc6c33f7b" +
            "2844f534dfae38ec011e40ba3f4c65",
            hex(MediaPacket.encrypt(0x01, sid, 7, key, ByteArray(20) { it.toByte() })!!))
    }

    @Test
    fun roundtripAndPeek() {
        val payload = "opus frame stand-in".toByteArray()
        val pkt = MediaPacket.encrypt(0x01, sid, 42, key, payload)!!
        assertEquals(MediaPacket.HEADER_BYTES + payload.size + MediaPacket.TAG_BYTES, pkt.size)

        val p = MediaPacket.peek(pkt)!!
        assertEquals(0x01, p.type)
        assertArrayEquals(sid, p.sid)
        assertEquals(42L, p.counter)

        assertArrayEquals(payload, MediaPacket.decrypt(pkt, key = key))
    }

    @Test
    fun counterIsBigEndianAcrossFiveBytes() {
        val pkt = MediaPacket.encrypt(0x01, sid, 0x0102030405L, key, "x".toByteArray())!!
        assertArrayEquals(bin("0102030405"), pkt.copyOfRange(4, 9))
        assertEquals(0x0102030405L, MediaPacket.peek(pkt)!!.counter)

        assertNotNull(MediaPacket.encrypt(0x01, sid, MediaPacket.MAX_COUNTER, key, "x".toByteArray()))
        // Past the field: refused, never truncated.
        assertNull(MediaPacket.encrypt(0x01, sid, MediaPacket.MAX_COUNTER + 1, key, "x".toByteArray()))
    }

    @Test
    fun theHeaderIsAuthenticated() {
        val pkt = MediaPacket.encrypt(0x01, sid, 7, key, "payload".toByteArray())!!

        // Flipping the type byte used to give a packet that still decrypted
        // and was then handed to the wrong parser.
        val flippedType = pkt.copyOf().also { it[0] = 0x04 }
        assertNull(MediaPacket.decrypt(flippedType, key = key))

        val flippedCounter = pkt.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }
        assertNull(MediaPacket.decrypt(flippedCounter, key = key))

        val flippedSid = pkt.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        assertNull(MediaPacket.decrypt(flippedSid, key = key))

        val flippedTag = pkt.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(MediaPacket.decrypt(flippedTag, key = key))
    }

    @Test
    fun twoSendersAtCounterZeroDoNotCollide() {
        val saltB = ByteArray(16) { (0x5A + it).toByte() }
        val keyB = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, zeros, bc)
        val sidB = MediaKeys.senderId(kCall, callId, saltB, zeros, bc)

        val a = MediaPacket.encrypt(0x01, sid, 0, key, "same".toByteArray())!!
        val b = MediaPacket.encrypt(0x01, sidB, 0, keyB, "same".toByteArray())!!
        assertEquals(false, hex(a) == hex(b))

        // One sender's key does not open the other's packet.
        assertNull(MediaPacket.decrypt(b, key = key))
        assertNotNull(MediaPacket.decrypt(b, key = keyB))
    }

    @Test
    fun boundsAreRefused() {
        val pkt = MediaPacket.encrypt(0x01, sid, 0, key, "x".toByteArray())!!
        assertNull(MediaPacket.peek(pkt, MediaPacket.HEADER_BYTES))
        assertNull(MediaPacket.decrypt(pkt, MediaPacket.HEADER_BYTES + MediaPacket.TAG_BYTES - 1, key))
        assertNull(MediaPacket.encrypt(0x01, ByteArray(2), 0, key, "x".toByteArray()))
        assertNull(MediaPacket.encrypt(0x01, sid, 0, ByteArray(16), "x".toByteArray()))
    }
}
