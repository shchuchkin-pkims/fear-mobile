package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors tests/test_media_keys.c: the same frozen fear.media.v2 vectors,
 * hashed with BouncyCastle so the check stays independent of libsodium and
 * runs on a plain JVM. If these ever disagree with the C side, Android and
 * desktop calls stop interoperating.
 */
class MediaKeysTest {

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
    private val callId2 = ByteArray(16) { (0xE0 + it).toByte() }
    private val saltA = ByteArray(16) { (0xA0 + it).toByte() }
    private val saltB = ByteArray(16) { (0x5A + it).toByte() }
    private val pk = bin("d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737")
    private val zeros = MediaKeys.UNSIGNED_IDBIND

    @Test
    fun helloKeyVectors() {
        assertEquals("e17ec5d029ed2987c577869ec52547ba6b2504b9ea8759c51a6d94645b967924",
            hex(MediaKeys.helloKey(kCall, callId, bc)))
        assertEquals("32593dd97694c649d21f9642d2a188d3918b286a374a09c725e3335cabc2a0a7",
            hex(MediaKeys.helloKey(kCall, callId2, bc)))
    }

    @Test
    fun senderKeyVectors() {
        // unsigned audio
        assertEquals("0bc7ef4f1ac0a8c46391f4f153ef956893475f84be6a061d88a08139a8554de0",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, zeros, bc)))
        // unsigned video: same inputs, other counter domain
        assertEquals("0d9837fae769f064978fd34c440199c876b0018444f78f5112ab5fda691a7460",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_VIDEO, 0, callId, saltA, zeros, bc)))
        // signed audio: identity bound in
        assertEquals("8a9243ecb9c62c27ba149995fe00efdb28d0e2ca5aad325ea88b86d5c3e6771e",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, pk, bc)))
        // key_version is bound, big endian
        assertEquals("77c76b092b98a4fed163eda6aefbe9fd591ae8a96e703c64a3419339eef3edcf",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 7, callId, saltA, pk, bc)))
        // another sender in the same call
        assertEquals("42175bf01239d346f9ded7e88611f973ccdba9bf4e7856421fd65ebae9ba5128",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, pk, bc)))
        // same sender, different call: a recording cannot replay across calls
        assertEquals("cd451578c0089eb3e1d63bcb2714723ae9bbf5a65a7e5941180b18ea61077401",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId2, saltA, pk, bc)))
    }

    @Test
    fun senderIdVectors() {
        assertEquals("0f2cee", hex(MediaKeys.senderId(kCall, callId, saltA, zeros, bc)))
        assertEquals("5338c1", hex(MediaKeys.senderId(kCall, callId, saltA, pk, bc)))
        assertEquals("19bbc4", hex(MediaKeys.senderId(kCall, callId, saltB, pk, bc)))
        assertEquals("26dd22", hex(MediaKeys.senderId(kCall, callId2, saltA, pk, bc)))
    }

    @Test
    fun everyInputIsBound() {
        val base = hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, pk, bc))
        assertNotEquals(base, hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_VIDEO, 0, callId, saltA, pk, bc)))
        assertNotEquals(base, hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 1, callId, saltA, pk, bc)))
        assertNotEquals(base, hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId2, saltA, pk, bc)))
        assertNotEquals(base, hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, pk, bc)))
        assertNotEquals(base, hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, zeros, bc)))
    }

    /** Three participants in one call all end up with distinct keys. */
    @Test
    fun groupOfThreeHasNoKeyCollisions() {
        val saltC = ByteArray(16) { (0x33 + it).toByte() }
        val keys = listOf(saltA, saltB, saltC).flatMap { salt ->
            listOf(MediaKeys.STREAM_AUDIO, MediaKeys.STREAM_VIDEO).map { s ->
                hex(MediaKeys.deriveSender(kCall, s, 0, callId, salt, zeros, bc))
            }
        }
        assertEquals(6, keys.toSet().size)

        // And each participant derives every peer's key from public data alone.
        val bAsSeenByA = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, zeros, bc)
        val bOwnKey = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, zeros, bc)
        assertArrayEquals(bOwnKey, bAsSeenByA)
    }

    @Test
    fun helloMacRoundtripAndRejection() {
        val hk = MediaKeys.helloKey(kCall, callId, bc)
        val body = ByteArray(40) { it.toByte() }

        val mac = MediaKeys.helloMac(hk, body, bc)
        assertEquals("a0254883e218b3ab5b22aa7a6c11dbd2", hex(mac))
        assertTrue(MediaKeys.helloMacVerify(hk, body, mac, bc))

        val tampered = body.copyOf().also { it[13] = (it[13].toInt() xor 1).toByte() }
        assertFalse(MediaKeys.helloMacVerify(hk, tampered, mac, bc))

        val badMac = mac.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }
        assertFalse(MediaKeys.helloMacVerify(hk, body, badMac, bc))

        // A HELLO from another call must not verify: this is what keeps an
        // off-path attacker out of the handshake entirely.
        val otherHk = MediaKeys.helloKey(kCall, callId2, bc)
        assertFalse(MediaKeys.helloMacVerify(otherHk, body, mac, bc))

        assertFalse(MediaKeys.helloMacVerify(hk, body, ByteArray(8), bc))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAllZeroCallId() {
        MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, ByteArray(16), saltA, pk, bc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesShortCallId() {
        MediaKeys.helloKey(kCall, ByteArray(8) { 1 }, bc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownStream() {
        MediaKeys.deriveSender(kCall, 7, 0, callId, saltA, pk, bc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesWrongIdbindSize() {
        MediaKeys.senderId(kCall, callId, saltA, ByteArray(16), bc)
    }
}
