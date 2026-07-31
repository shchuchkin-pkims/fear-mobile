package com.fear.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frozen vectors, run through the production crypto stack on a real
 * device.
 *
 * Every other test of these values substitutes the primitives: the JVM unit
 * tests inject BouncyCastle for BLAKE2b and Ed25519 because lazysodium is a
 * native AAR that cannot load off-device. That substitution is what makes
 * those tests independent, and it is also what leaves a gap - nothing has
 * ever checked that libsodium on ARM and Android's own AES-GCM provider
 * produce the same bytes as the desktop.
 *
 * If they did not, every call between a phone and a desktop would fail to
 * key, and the unit tests on both sides would still be green. This closes
 * that gap: same vectors, no injected hasher, no injected signer.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceVectorsTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bin(h: String) = ByteArray(h.length / 2) {
        h.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val kCall = ByteArray(32) { it.toByte() }
    private val callId = ByteArray(16) { (0x10 + it).toByte() }
    private val callId2 = ByteArray(16) { (0xE0 + it).toByte() }
    private val saltA = ByteArray(16) { (0xA0 + it).toByte() }
    private val saltB = ByteArray(16) { (0x5A + it).toByte() }
    private val zeros = MediaKeys.UNSIGNED_IDBIND
    private val pk = bin("d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737")

    /** Keyed BLAKE2b through libsodium, the path a real call uses. */
    @Test
    fun mediaKeyVectorsThroughLibsodium() {
        assertEquals("e17ec5d029ed2987c577869ec52547ba6b2504b9ea8759c51a6d94645b967924",
            hex(MediaKeys.helloKey(kCall, callId)))
        assertEquals("32593dd97694c649d21f9642d2a188d3918b286a374a09c725e3335cabc2a0a7",
            hex(MediaKeys.helloKey(kCall, callId2)))

        assertEquals("0bc7ef4f1ac0a8c46391f4f153ef956893475f84be6a061d88a08139a8554de0",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, zeros)))
        assertEquals("0d9837fae769f064978fd34c440199c876b0018444f78f5112ab5fda691a7460",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_VIDEO, 0, callId, saltA, zeros)))
        assertEquals("8a9243ecb9c62c27ba149995fe00efdb28d0e2ca5aad325ea88b86d5c3e6771e",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, pk)))
        assertEquals("77c76b092b98a4fed163eda6aefbe9fd591ae8a96e703c64a3419339eef3edcf",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 7, callId, saltA, pk)))
        assertEquals("42175bf01239d346f9ded7e88611f973ccdba9bf4e7856421fd65ebae9ba5128",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, pk)))
        assertEquals("cd451578c0089eb3e1d63bcb2714723ae9bbf5a65a7e5941180b18ea61077401",
            hex(MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId2, saltA, pk)))
    }

    /**
     * The 16-byte digest truncated to three bytes. Worth its own check: the
     * output length goes into the BLAKE2b parameter block, so asking for 16
     * and cutting differs from asking for 3, and a port that got it wrong
     * would tag every packet differently while every key still matched.
     */
    @Test
    fun senderTagsThroughLibsodium() {
        assertEquals("0f2cee", hex(MediaKeys.senderId(kCall, callId, saltA, zeros)))
        assertEquals("5338c1", hex(MediaKeys.senderId(kCall, callId, saltA, pk)))
        assertEquals("19bbc4", hex(MediaKeys.senderId(kCall, callId, saltB, pk)))
        assertEquals("26dd22", hex(MediaKeys.senderId(kCall, callId2, saltA, pk)))
    }

    /** AES-256-GCM through whatever provider this device ships. */
    @Test
    fun packetVectorsThroughDeviceAesGcm() {
        val key = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, zeros)
        val sid = MediaKeys.senderId(kCall, callId, saltA, zeros)

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

        // And the header is authenticated here too, not only in the JVM test.
        val pkt = MediaPacket.encrypt(0x01, sid, 7, key, "payload".toByteArray())!!
        val flipped = pkt.copyOf().also { it[0] = 0x04 }
        assertEquals(null, MediaPacket.decrypt(flipped, key = key))
        assertArrayEquals("payload".toByteArray(), MediaPacket.decrypt(pkt, key = key))
    }

    /**
     * A HELLO2 built and verified with libsodium's Ed25519, byte-identical
     * to the packet the C test pins. Uses a fixed seed, so the signature is
     * deterministic and comparable across implementations.
     */
    @Test
    fun helloVectorThroughLibsodium() {
        val seed = ByteArray(32) { 0x11 }
        val sk = seed + pk
        val helloKey = MediaKeys.helloKey(kCall, callId)

        val signed = MediaHello.Hello(
            flags = MediaHello.FLAG_VIDEO or MediaHello.FLAG_AUDIO or MediaHello.FLAG_IDENTITY,
            keyVersion = 7, callId = callId, senderSalt = saltA,
            width = 640, height = 480, fps = 25, pk = pk)

        val pkt = MediaHello.build(signed, helloKey, sk)
        assertEquals(
            "7e03009e07000007101112131415161718191a1b1c1d1e1f" +
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf028001e01900" +
            "d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737" +
            "2b9b4a45c4c2ad7d835677dc6260ee0e27bf2946ef62d0b7cc0f523f30ace05c" +
            "05b6c4f80cb90ca59da76b41cdaeec7f45833f1eb7ebba16cbad2577d9bc9505" +
            "fdab26ee8e43dcce31a1bd07ae56e7da",
            hex(pkt))

        val r = MediaHello.parse(pkt, pkt.size, helloKey)
        assertEquals(MediaHello.Status.OK, r.status)
        assertNotNull(r.hello)
        assertEquals(signed, r.hello)
    }

    /**
     * Two participants at counter zero, keyed by their own salts, through the
     * production primitives end to end. This is the property the whole
     * scheme rests on, checked on the hardware that has to honour it.
     */
    @Test
    fun twoSendersAtZeroDoNotCollideOnDevice() {
        val keyA = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltA, zeros)
        val keyB = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, saltB, zeros)
        val sidA = MediaKeys.senderId(kCall, callId, saltA, zeros)
        val sidB = MediaKeys.senderId(kCall, callId, saltB, zeros)

        val a = MediaPacket.encrypt(0x01, sidA, 0, keyA, "same".toByteArray())!!
        val b = MediaPacket.encrypt(0x01, sidB, 0, keyB, "same".toByteArray())!!
        assertTrue(hex(a) != hex(b))
        assertEquals(null, MediaPacket.decrypt(b, key = keyA))
        assertArrayEquals("same".toByteArray(), MediaPacket.decrypt(b, key = keyB))
    }

    /** The invite layout, which is what lets the two ends agree on a call at all. */
    @Test
    fun inviteVectorOnDevice() {
        val pkt = CallInvite.build(CallInvite.Invite(
            flags = CallInvite.FLAG_AUDIO, callId = callId,
            port = 45000, host = "192.168.0.108"))
        assertEquals(
            "0101" + "101112131415161718191a1b1c1d1e1f" + "afc8" + "0d" +
            "3139322e3136382e302e313038",
            hex(pkt))
        assertEquals(CallInvite.Status.OK, CallInvite.parse(pkt).status)
    }
}
