package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Mirrors tests/test_media_hello.c: the same frozen packets, byte for byte.
 *
 * Both the hash and the signature come from BouncyCastle rather than
 * libsodium, so these run on a plain JVM and, more to the point, a framing
 * mistake cannot hide behind one library agreeing with itself. If these
 * bytes ever drift from the C side, Android and desktop stop connecting.
 */
class MediaHelloTest {

    private val bcHash = KeyedHash { data, key, outLen ->
        val d = Blake2bDigest(key, outLen, null, null)
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private val bcSign = object : Ed25519Ops {
        override fun sign(msg: ByteArray, sk: ByteArray): ByteArray {
            // libsodium's secret key is seed || pk; BouncyCastle takes the seed.
            val priv = Ed25519PrivateKeyParameters(sk, 0)
            val s = Ed25519Signer()
            s.init(true, priv)
            s.update(msg, 0, msg.size)
            return s.generateSignature()
        }

        override fun verify(msg: ByteArray, sig: ByteArray, pk: ByteArray): Boolean {
            val pub = Ed25519PublicKeyParameters(pk, 0)
            val s = Ed25519Signer()
            s.init(false, pub)
            s.update(msg, 0, msg.size)
            return s.verifySignature(sig)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bin(h: String) = ByteArray(h.length / 2) {
        h.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val kCall = ByteArray(32) { it.toByte() }
    private val callId = ByteArray(16) { (0x10 + it).toByte() }
    private val salt = ByteArray(16) { (0xA0 + it).toByte() }
    private val helloKey = MediaKeys.helloKey(kCall, callId, bcHash)

    /** Seed 0x11 * 32, the same identity the C test uses. */
    private val seed = ByteArray(32) { 0x11 }
    private val pk = bin("d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737")
    private val sk = seed + pk

    private val unsignedHex =
        "7e04004e02000000101112131415161718191a1b1c1d1e1fa0a1a2a3a4a5a6a7" +
        "a8a9aaabacadaeaf000000000000000000000000000000000000000000000794" +
        "d5b3c364fe3cc20e1dcbc2445157"

    private val signedHex =
        "7e0400ae07000007101112131415161718191a1b1c1d1e1fa0a1a2a3a4a5a6a7" +
        "a8a9aaabacadaeaf028001e0190000000000000000000000000000000000d04a" +
        "b232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737338c" +
        "8397cbc659b5374df33a226fa1b0e265a37f4fcfd1e9b7107e7062369160bb93" +
        "35319895612cfc74b5142622ed8989889be0a06240e44d6879ab293c0f0e2052" +
        "8d48f4b542818915b5c5b083f9b4"

    private fun unsigned() = MediaHello.Hello(
        flags = MediaHello.FLAG_AUDIO, keyVersion = 0,
        callId = callId, senderSalt = salt)

    private fun signed() = MediaHello.Hello(
        flags = MediaHello.FLAG_VIDEO or MediaHello.FLAG_AUDIO or MediaHello.FLAG_IDENTITY,
        keyVersion = 7, callId = callId, senderSalt = salt,
        width = 640, height = 480, fps = 25, pk = pk)

    private fun reMac(b: ByteArray): ByteArray {
        val body = b.copyOfRange(0, b.size - MediaKeys.MAC_BYTES)
        MediaKeys.helloMac(helloKey, body, bcHash).copyInto(b, b.size - MediaKeys.MAC_BYTES)
        return b
    }

    @Test
    fun unsignedPacketIsByteExact() {
        val pkt = MediaHello.build(unsigned(), helloKey, null, bcHash, bcSign)
        assertEquals(MediaHello.SIZE_BASE, pkt.size)
        assertEquals(unsignedHex, hex(pkt))

        // Per-offset, so a mistake points at the field.
        assertEquals(0x7E.toByte(), pkt[0])
        assertEquals(0x04.toByte(), pkt[1])
        assertEquals(0x00.toByte(), pkt[2]); assertEquals(0x4E.toByte(), pkt[3])
        assertEquals(MediaHello.FLAG_AUDIO.toByte(), pkt[4])
        assertEquals(0.toByte(), pkt[5])
        assertArrayEquals(callId, pkt.copyOfRange(8, 24))
        assertArrayEquals(salt, pkt.copyOfRange(24, 40))
        for (i in 40 until 46) assertEquals("byte $i", 0.toByte(), pkt[i])
        // An unannounced name is all NUL, not whatever the caller had.
        for (i in 46 until 62) assertEquals("name byte $i", 0.toByte(), pkt[i])
    }

    @Test
    fun signedPacketIsByteExact() {
        val pkt = MediaHello.build(signed(), helloKey, sk, bcHash, bcSign)
        assertEquals(MediaHello.SIZE_SIGNED, pkt.size)
        assertEquals(signedHex, hex(pkt))
        assertEquals(0xAE.toByte(), pkt[3])
        assertEquals(0x07.toByte(), pkt[7])
        assertEquals(0x02.toByte(), pkt[40]); assertEquals(0x80.toByte(), pkt[41])
        assertEquals(0x01.toByte(), pkt[42]); assertEquals(0xE0.toByte(), pkt[43])
        assertEquals(25.toByte(), pkt[44])
        assertArrayEquals(pk, pkt.copyOfRange(62, 94))
    }

    @Test
    fun roundtrips() {
        for (h in listOf(unsigned(), signed())) {
            val skArg = if (h.flags and MediaHello.FLAG_IDENTITY != 0) sk else null
            val pkt = MediaHello.build(h, helloKey, skArg, bcHash, bcSign)
            val r = MediaHello.parse(pkt, pkt.size, helloKey, bcHash, bcSign)
            assertEquals(MediaHello.Status.OK, r.status)
            assertNotNull(r.hello)
            assertEquals(h, r.hello)
        }
    }

    @Test
    fun macGatesEverything() {
        val pkt = MediaHello.build(signed(), helloKey, sk, bcHash, bcSign)

        val tampered = pkt.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(MediaHello.Status.ERR_MAC,
            MediaHello.parse(tampered, tampered.size, helloKey, bcHash, bcSign).status)

        // A HELLO from another call must not be accepted here.
        val otherKey = MediaKeys.helloKey(kCall, ByteArray(16) { 0xE0.toByte() }, bcHash)
        assertEquals(MediaHello.Status.ERR_MAC,
            MediaHello.parse(pkt, pkt.size, otherKey, bcHash, bcSign).status)
    }

    @Test
    fun signatureCoversTheHeader() {
        val pkt = MediaHello.build(signed(), helloKey, sk, bcHash, bcSign)
        // Change the salt and re-MAC: only the signature can catch this.
        val evil = reMac(pkt.copyOf().also { it[24] = (it[24].toInt() xor 1).toByte() })
        assertEquals(MediaHello.Status.ERR_SIGNATURE,
            MediaHello.parse(evil, evil.size, helloKey, bcHash, bcSign).status)
    }

    @Test
    fun rejectionMatrix() {
        val pkt = MediaHello.build(signed(), helloKey, sk, bcHash, bcSign)
        fun parseOf(mutate: (ByteArray) -> Unit): MediaHello.Status {
            val b = pkt.copyOf(); mutate(b); reMac(b)
            return MediaHello.parse(b, b.size, helloKey, bcHash, bcSign).status
        }

        assertEquals(MediaHello.Status.ERR_VERSION, parseOf { it[1] = 0x02 })
        assertEquals(MediaHello.Status.ERR_RESERVED, parseOf { it[4] = (it[4].toInt() or 0x10).toByte() })
        assertEquals(MediaHello.Status.ERR_RESERVED, parseOf { it[5] = 1 })
        assertEquals(MediaHello.Status.ERR_RESERVED, parseOf { it[45] = 1 })
        // IDENTITY cleared but the packet is still 158 bytes
        assertEquals(MediaHello.Status.ERR_LENGTH,
            parseOf { it[4] = (it[4].toInt() and MediaHello.FLAG_IDENTITY.inv()).toByte() })
        // declared length disagrees with the real one
        assertEquals(MediaHello.Status.ERR_LENGTH, parseOf { it[2] = 0x00; it[3] = 0x4E })
        assertEquals(MediaHello.Status.ERR_CALLID, parseOf { for (i in 8 until 24) it[i] = 0 })

        // Truncation is caught by the MAC, before any length rule runs. That
        // ordering is the point: nothing structural is trusted until the
        // packet is proven to come from this call.
        assertEquals(MediaHello.Status.ERR_MAC,
            MediaHello.parse(pkt, pkt.size - 1, helloKey, bcHash, bcSign).status)
        assertEquals(MediaHello.Status.ERR_TOO_SHORT,
            MediaHello.parse(pkt, 8, helloKey, bcHash, bcSign).status)
    }

    @Test
    fun oldPeersAreNamedNotDropped() {
        val legacy = ByteArray(107).also { it[0] = 0x7F }
        for (n in listOf(5, 102, 107)) {
            assertEquals(MediaHello.Status.ERR_LEGACY_PEER,
                MediaHello.parse(legacy, n, helloKey, bcHash, bcSign).status)
        }
        val alien = ByteArray(MediaHello.SIZE_BASE).also { it[0] = 0x01 }
        assertEquals(MediaHello.Status.ERR_TYPE,
            MediaHello.parse(alien, alien.size, helloKey, bcHash, bcSign).status)
    }

    @Test
    fun sizeFollowsFlags() {
        assertEquals(MediaHello.SIZE_BASE, MediaHello.size(MediaHello.FLAG_AUDIO))
        assertEquals(MediaHello.SIZE_SIGNED,
            MediaHello.size(MediaHello.FLAG_AUDIO or MediaHello.FLAG_IDENTITY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesIdentityWithoutKey() {
        MediaHello.build(signed(), helloKey, null, bcHash, bcSign)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAllZeroCallId() {
        MediaHello.build(unsigned().copy(callId = ByteArray(16)), helloKey, null, bcHash, bcSign)
    }
}
