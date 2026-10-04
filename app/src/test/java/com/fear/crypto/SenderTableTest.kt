package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors tests/test_media_senders.c. These are behavioural rather than
 * frozen-vector tests: nothing here reaches the wire, what matters is that
 * the rules hold under the cases that motivated them.
 */
class SenderTableTest {

    private val bc = KeyedHash { data, key, outLen ->
        val d = Blake2bDigest(key, outLen, null, null)
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private val kCall = ByteArray(32) { it.toByte() }
    private val callId = ByteArray(16) { (0x10 + it).toByte() }
    private val zeros = MediaKeys.UNSIGNED_IDBIND

    private fun salt(seed: Int) = ByteArray(16) { (seed + it).toByte() }
    private val own = salt(0x01)
    private val a = salt(0xA0)
    private val b = salt(0x5A)

    private fun table(ownSalt: ByteArray? = own) =
        SenderTable.Table(kCall, callId, ownSalt, bc)

    @Test
    fun installIsIdempotentAndSelfIsRefused() {
        val t = table()
        val (sa, ia) = t.install(a, zeros, 0)
        val (sb, ib) = t.install(b, zeros, 0)
        assertEquals(SenderTable.Status.OK, sa)
        assertEquals(SenderTable.Status.OK, sb)
        assertNotEquals(ia, ib)
        assertEquals(2, t.count())

        // A repeated HELLO is a no-op, not a way to reset a window.
        val (again, idx) = t.install(a, zeros, 0)
        assertEquals(SenderTable.Status.OK, again)
        assertEquals(ia, idx)
        assertEquals(2, t.count())

        // Our own salt echoed back would give a peer with our send keys.
        assertEquals(SenderTable.Status.ERR_SELF, t.install(own, zeros, 0).first)
        assertEquals(2, t.count())
    }

    @Test
    fun keysDifferPerSenderAndStreamAndMatchTheSender() {
        val t = table()
        val ia = t.install(a, zeros, 0).second
        val ib = t.install(b, zeros, 0).second

        val ka = t.key(ia, MediaKeys.STREAM_AUDIO)!!
        val kb = t.key(ib, MediaKeys.STREAM_AUDIO)!!
        val kav = t.key(ia, MediaKeys.STREAM_VIDEO)!!
        assertFalse(ka.contentEquals(kb))
        assertFalse(ka.contentEquals(kav))
        assertNull(t.key(99, MediaKeys.STREAM_AUDIO))

        // What the receiver derives is what that sender encrypts with.
        val expect = MediaKeys.deriveSender(kCall, MediaKeys.STREAM_AUDIO, 0, callId, a, zeros, bc)
        assertTrue(ka.contentEquals(expect))
    }

    @Test
    fun sidLookupIsOldestFirst() {
        val t = table()
        val ia = t.install(a, zeros, 0).second
        val sid = MediaKeys.senderId(kCall, callId, a, zeros, bc)
        assertEquals(listOf(ia), t.findBySid(sid))
        assertTrue(t.findBySid(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte())).isEmpty())
    }

    @Test
    fun replayWindowAcceptsReorderingAndRefusesRepeats() {
        val t = table()
        val ia = t.install(a, zeros, 0).second

        // The first packet anchors wherever it lands.
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 500))
        assertEquals(SenderTable.Verdict.REPLAY, t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 500))
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 501))
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 499))
        assertEquals(SenderTable.Verdict.REPLAY, t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 499))
        assertEquals(SenderTable.Verdict.REPLAY,
            t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 500L - SenderTable.WINDOW_BITS))

        // Counter domains are independent.
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ia, MediaKeys.STREAM_VIDEO, 1))
        assertEquals(SenderTable.Verdict.REPLAY, t.acceptSeq(ia, MediaKeys.STREAM_VIDEO, 1))
    }

    @Test
    fun forgedForwardJumpDoesNotMoveTheWindow() {
        val t = table()
        val ib = t.install(b, zeros, 0).second
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ib, MediaKeys.STREAM_AUDIO, 1))

        // One packet at an implausible counter must not drag the window past
        // everything the real sender will send.
        assertEquals(SenderTable.Verdict.JUMP,
            t.acceptSeq(ib, MediaKeys.STREAM_AUDIO, 1L + MediaKeys.MAX_CTR_JUMP + 1))
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ib, MediaKeys.STREAM_AUDIO, 2))
        // A large but plausible jump is fine: packet loss happens.
        assertEquals(SenderTable.Verdict.FRESH,
            t.acceptSeq(ib, MediaKeys.STREAM_AUDIO, 2L + MediaKeys.MAX_CTR_JUMP))
    }

    @Test
    fun tombstoneSurvivesRetireAndReinstall() {
        val t = table()
        val ia = t.install(a, zeros, 0).second
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ia, MediaKeys.STREAM_AUDIO, 900))

        assertEquals(SenderTable.Status.OK, t.retire(ia))
        assertEquals(0, t.count())
        assertNull(t.key(ia, MediaKeys.STREAM_AUDIO))
        assertEquals(SenderTable.Status.ERR_NOT_FOUND, t.retire(ia))

        val ia2 = t.install(a, zeros, 0).second
        // The window resumed rather than restarting, so the recording of 900
        // is not replayable into the new slot.
        assertEquals(SenderTable.Verdict.REPLAY, t.acceptSeq(ia2, MediaKeys.STREAM_AUDIO, 900))
        assertEquals(SenderTable.Verdict.FRESH, t.acceptSeq(ia2, MediaKeys.STREAM_AUDIO, 901))
        assertEquals(SenderTable.Verdict.REPLAY, t.acceptSeq(ia2, MediaKeys.STREAM_AUDIO, 700))
    }

    @Test
    fun fullTableRefusesRatherThanEvicting() {
        val t = table(null)
        for (i in 1..SenderTable.MAX_SLOTS) {
            val s = ByteArray(16).also { it[0] = i.toByte(); it[1] = 0x77 }
            assertEquals(SenderTable.Status.OK, t.install(s, zeros, 0).first)
        }
        assertEquals(SenderTable.MAX_SLOTS, t.count())

        val late = ByteArray(16) { 0xEE.toByte() }
        assertEquals(SenderTable.Status.ERR_FULL, t.install(late, zeros, 0).first)
        // Nobody was displaced by the attempt.
        assertEquals(SenderTable.MAX_SLOTS, t.count())

        // An already-present sender still resolves when the table is full.
        val known = ByteArray(16).also { it[0] = 1; it[1] = 0x77 }
        assertEquals(SenderTable.Status.OK, t.install(known, zeros, 0).first)
    }

    @Test
    fun identityIsPartOfTheSenderNotJustTheSalt() {
        val t = table(null)
        val pk = ByteArray(32) { 0x42 }
        val (s1, unsignedIdx) = t.install(a, zeros, 0)
        val (s2, signedIdx) = t.install(a, pk, 0)
        assertEquals(SenderTable.Status.OK, s1)
        assertEquals(SenderTable.Status.OK, s2)
        // Same salt under a different identity is a different sender, so an
        // announcement cannot be hijacked by replaying somebody else's salt.
        assertNotEquals(unsignedIdx, signedIdx)
        assertFalse(t.key(unsignedIdx, MediaKeys.STREAM_AUDIO)!!
            .contentEquals(t.key(signedIdx, MediaKeys.STREAM_AUDIO)!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAllZeroCallId() {
        SenderTable.Table(kCall, ByteArray(16), null, bc)
    }
}
