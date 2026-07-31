package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Mirrors tests/test_media_keys.c: same frozen vectors, hashed with
 * BouncyCastle so the check stays independent of libsodium and runs on a
 * plain JVM.
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

    private val master = ByteArray(32) { it.toByte() }
    private val saltA = ByteArray(16) { (0x10 + it).toByte() }
    private val saltB = ByteArray(16) { (0xF0 xor it).toByte() }

    @Test
    fun frozenVectors() {
        assertEquals(
            "2115ba791b04de1beea3d49fecf95748fcf788c1ea5b4901dd534b50279a14d8",
            hex(MediaKeys.derive(master, MediaKeys.STREAM_AUDIO,
                                 MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc)))
        assertEquals(
            "5dfb5ec2fe608102adb45ddb87635827891ea7b8b4b326b58ef7a27c1a12638b",
            hex(MediaKeys.derive(master, MediaKeys.STREAM_AUDIO,
                                 MediaKeys.DIR_CALLEE_TO_CALLER, saltA, bc)))
        assertEquals(
            "c653aba9d730f2f041d465c34fb906f36bdbb73fc4e6516dc66ccbb020645009",
            hex(MediaKeys.derive(master, MediaKeys.STREAM_VIDEO,
                                 MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc)))
        assertEquals(
            "0b45aa24615c1af180958bf59e949e516253a119d64baa9fd5d01f553aabb85b",
            hex(MediaKeys.derive(master, MediaKeys.STREAM_VIDEO,
                                 MediaKeys.DIR_CALLEE_TO_CALLER, saltA, bc)))
        assertEquals(
            "64050af487f07cc001e50c3bd1af8301e64084ef41a01d8a7047ca5de4522852",
            hex(MediaKeys.derive(master, MediaKeys.STREAM_AUDIO,
                                 MediaKeys.DIR_CALLER_TO_CALLEE, saltB, bc)))
    }

    @Test
    fun infoFraming() {
        val info = MediaKeys.info(MediaKeys.STREAM_VIDEO, MediaKeys.DIR_CALLEE_TO_CALLER, saltA)
        assertArrayEquals("fear.media.v1".toByteArray(Charsets.US_ASCII), info.copyOfRange(0, 13))
        assertEquals(1, info[13].toInt())
        assertEquals(1, info[14].toInt())
        assertArrayEquals(saltA, info.copyOfRange(15, 31))
    }

    @Test
    fun allFourKeysOfACallDiffer() {
        val keys = listOf(
            MediaKeys.derive(master, MediaKeys.STREAM_AUDIO, MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc),
            MediaKeys.derive(master, MediaKeys.STREAM_AUDIO, MediaKeys.DIR_CALLEE_TO_CALLER, saltA, bc),
            MediaKeys.derive(master, MediaKeys.STREAM_VIDEO, MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc),
            MediaKeys.derive(master, MediaKeys.STREAM_VIDEO, MediaKeys.DIR_CALLEE_TO_CALLER, saltA, bc),
        ).map { hex(it) }
        assertEquals(4, keys.toSet().size)
        assertEquals(false, keys.contains(hex(master)))
    }

    @Test
    fun saltAndMasterAreBound() {
        val a = hex(MediaKeys.derive(master, MediaKeys.STREAM_AUDIO,
                                     MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc))
        val bSalt = hex(MediaKeys.derive(master, MediaKeys.STREAM_AUDIO,
                                         MediaKeys.DIR_CALLER_TO_CALLEE, saltB, bc))
        assertNotEquals(a, bSalt)

        val otherMaster = master.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        val bMaster = hex(MediaKeys.derive(otherMaster, MediaKeys.STREAM_AUDIO,
                                           MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc))
        assertNotEquals(a, bMaster)
    }

    @Test
    fun theTwoEndsAgree() {
        val (callerSend, callerRecv) = MediaKeys.derivePair(
            master, MediaKeys.STREAM_AUDIO, isCaller = true, salt = saltA, hash = bc)
        val (calleeSend, calleeRecv) = MediaKeys.derivePair(
            master, MediaKeys.STREAM_AUDIO, isCaller = false, salt = saltA, hash = bc)

        assertArrayEquals(callerSend, calleeRecv)
        assertArrayEquals(calleeSend, callerRecv)
        assertNotEquals(hex(callerSend), hex(callerRecv))
        assertNotEquals(hex(calleeSend), hex(calleeRecv))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongSaltSize() {
        MediaKeys.derive(master, MediaKeys.STREAM_AUDIO,
                         MediaKeys.DIR_CALLER_TO_CALLEE, ByteArray(8), bc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownStream() {
        MediaKeys.derive(master, 7, MediaKeys.DIR_CALLER_TO_CALLEE, saltA, bc)
    }
}
