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
    private val halfC = ByteArray(16).also { it[15] = 0x01 }

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

    /**
     * Same salt-agreement vectors the C test pins, computed independently
     * with Python hashlib.blake2b(key=master, digest_size=16).
     */
    @Test
    fun saltAgreementVectors() {
        assertEquals("72f75f37beebffb6d9da8920b9045063",
            hex(MediaKeys.saltCombine(master, saltA, saltB, bc)))
        assertEquals("ef6cdd21ef8fb871e10d248a13f3be87",
            hex(MediaKeys.saltCombine(ByteArray(32), saltA, saltB, bc)))
        assertEquals("6c6d840e804427679a352df05065fd2b",
            hex(MediaKeys.saltCombine(master, saltA, halfC, bc)))
    }

    @Test
    fun saltAgreementIsCommutative() {
        assertArrayEquals(
            MediaKeys.saltCombine(master, saltA, saltB, bc),
            MediaKeys.saltCombine(master, saltB, saltA, bc))
    }

    @Test
    fun saltBindsBothHalvesAndMaster() {
        val ab = hex(MediaKeys.saltCombine(master, saltA, saltB, bc))
        assertNotEquals(ab, hex(MediaKeys.saltCombine(master, saltA, halfC, bc)))
        val otherMaster = master.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNotEquals(ab, hex(MediaKeys.saltCombine(otherMaster, saltA, saltB, bc)))
    }

    @Test
    fun rolesAreOppositeAndReflectionIsRefused() {
        val aIsCaller = MediaKeys.roleFromHalves(saltA, saltB)
        val bIsCaller = MediaKeys.roleFromHalves(saltB, saltA)
        assertEquals(true, aIsCaller)
        assertEquals(false, bIsCaller)
        // Our own half echoed back: refuse rather than pick a side.
        assertEquals(null, MediaKeys.roleFromHalves(saltA, saltA))
    }

    /** Two peers, each knowing only its own half and the received one. */
    @Test
    fun endToEndBothPeersAgree() {
        val aSalt = MediaKeys.saltCombine(master, saltA, saltB, bc)
        val bSalt = MediaKeys.saltCombine(master, saltB, saltA, bc)
        assertArrayEquals(aSalt, bSalt)

        val aRole = MediaKeys.roleFromHalves(saltA, saltB)!!
        val bRole = MediaKeys.roleFromHalves(saltB, saltA)!!
        assertNotEquals(aRole, bRole)

        for (stream in listOf(MediaKeys.STREAM_AUDIO, MediaKeys.STREAM_VIDEO)) {
            val (aTx, aRx) = MediaKeys.derivePair(master, stream, aRole, aSalt, bc)
            val (bTx, bRx) = MediaKeys.derivePair(master, stream, bRole, bSalt, bc)
            assertArrayEquals(aTx, bRx)
            assertArrayEquals(bTx, aRx)
            assertNotEquals(hex(aTx), hex(aRx))
        }
    }

    /** The Kotlin comparison must match memcmp, including the 0x80 boundary. */
    @Test
    fun halfComparisonIsUnsigned() {
        val low = ByteArray(16).also { it[0] = 0x7F }
        val high = ByteArray(16).also { it[0] = 0x80.toByte() }
        assertEquals(true, MediaKeys.roleFromHalves(low, high))
        assertEquals(false, MediaKeys.roleFromHalves(high, low))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongHalfSize() {
        MediaKeys.saltCombine(master, saltA, ByteArray(8), bc)
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
