package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The C tests (tests/test_key_schedule.c) pin these vectors; this port is
 * only correct if it reproduces them byte for byte.
 *
 * The hash here comes from BouncyCastle, not from libsodium: it keeps the
 * test runnable on a plain JVM (lazysodium-android needs a device) and it
 * makes the check independent - framing bugs cannot hide behind the same
 * library agreeing with itself.
 */
class KeyScheduleTest {

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

    private val zeroKey = ByteArray(32)
    private val onesKey = ByteArray(32) { 0xFF.toByte() }
    private val countKey = ByteArray(32) { it.toByte() }

    @Test
    fun frozenVectors() {
        assertEquals(
            "f25023dd61a1f39caf684af6fef49f8308d709b04273eba3f49a9a92e92ff05a",
            hex(KeySchedule.deriveEpochKey(zeroKey, 1, 0, bc)))
        assertEquals(
            "a99db756901e476d212f9c2536b92b046c440e8e7c1ef27520edc1a9dbabe1e9",
            hex(KeySchedule.deriveEpochKey(zeroKey, 1, 1, bc)))
        assertEquals(
            "4abc46ff02ab32f250fd2a4a57be044d707d1fedaa5348a7cb6f16c284dc77c8",
            hex(KeySchedule.deriveEpochKey(zeroKey, 2, 0, bc)))
        assertEquals(
            "c0fe04c88c024da9898edd991db408bc1837a101cb803bc228539259fe95aa20",
            hex(KeySchedule.deriveEpochKey(onesKey, 1, 0, bc)))
        assertEquals(
            "c8bf67af400d1dbd83b2027d2e9bba4ec37b1c9cf6673218829cbb0c5aa74fae",
            hex(KeySchedule.deriveEpochKey(countKey, 1, 486_000L, bc)))
        assertEquals(
            "b6ae6a8e92b082bb73e505d42b7e274c6fcd04ff4af786b57e1b8a49ef3965e0",
            hex(KeySchedule.deriveEpochKey(countKey, 7, 4_294_967_295L, bc)))
    }

    @Test
    fun infoFramingIsLittleEndian() {
        val info = KeySchedule.info(0x0201, 0x0A0B0C0DL)
        assertArrayEquals("fear.epoch.v1".toByteArray(Charsets.US_ASCII), info.copyOfRange(0, 13))
        assertArrayEquals(bin("01020d0c0b0a"), info.copyOfRange(13, 19))
    }

    @Test
    fun everyInputIsBound() {
        val base = KeySchedule.deriveEpochKey(countKey, 3, 100, bc)
        assertArrayEquals(base, KeySchedule.deriveEpochKey(countKey, 3, 100, bc))
        assertNotEquals(hex(base), hex(KeySchedule.deriveEpochKey(countKey, 3, 101, bc)))
        assertNotEquals(hex(base), hex(KeySchedule.deriveEpochKey(countKey, 4, 100, bc)))
        assertNotEquals(hex(base), hex(KeySchedule.deriveEpochKey(onesKey, 3, 100, bc)))
        assertNotEquals(hex(base), hex(countKey))
    }

    @Test
    fun epochArithmetic() {
        assertEquals(0L, KeySchedule.epochFromUnix(0))
        assertEquals(0L, KeySchedule.epochFromUnix(3599))
        assertEquals(1L, KeySchedule.epochFromUnix(3600))
        assertEquals(1L, KeySchedule.epochFromUnix(7199))
        assertEquals(486_000L, KeySchedule.epochFromUnix(486_000L * 3600L))
        // Saturates rather than wrapping onto a valid epoch.
        assertEquals(KeySchedule.MAX_EPOCH, KeySchedule.epochFromUnix(Long.MAX_VALUE))
    }

    @Test
    fun skewTolerance() {
        assertTrue(KeySchedule.epochAcceptable(100, 100))
        assertTrue(KeySchedule.epochAcceptable(99, 100))
        assertTrue(KeySchedule.epochAcceptable(101, 100))
        assertFalse(KeySchedule.epochAcceptable(98, 100))
        assertFalse(KeySchedule.epochAcceptable(102, 100))
        assertFalse(KeySchedule.epochAcceptable(0, KeySchedule.MAX_EPOCH))
    }

    @Test
    fun headerRoundtrip() {
        val hdr = KeySchedule.writeHeader(0x0201, 0x0A0B0C0DL)
        assertArrayEquals(bin("01020d0c0b0a"), hdr)
        val (ver, epoch) = KeySchedule.readHeader(hdr)
        assertEquals(0x0201, ver)
        assertEquals(0x0A0B0C0DL, epoch)

        val max = KeySchedule.writeHeader(0xFFFF, KeySchedule.MAX_EPOCH)
        val (v2, e2) = KeySchedule.readHeader(max)
        assertEquals(0xFFFF, v2)
        assertEquals(KeySchedule.MAX_EPOCH, e2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortHeader() {
        KeySchedule.readHeader(ByteArray(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongKeySize() {
        KeySchedule.deriveEpochKey(ByteArray(16), 1, 0, bc)
    }
}
