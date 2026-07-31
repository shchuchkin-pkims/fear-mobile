package com.fear.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Mirrors tests/test_call_invite.c, including the frozen bytes. The invite
 * is what lets two peers agree on a call_id at all, so a layout difference
 * between the platforms would mean Android and desktop simply cannot call
 * each other.
 */
class CallInviteTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val callId = ByteArray(16) { (0x10 + it).toByte() }

    @Test
    fun withDirectHintIsByteExact() {
        val pkt = CallInvite.build(CallInvite.Invite(
            flags = CallInvite.FLAG_AUDIO, callId = callId,
            port = 45000, host = "192.168.0.108"))
        assertEquals(CallInvite.HEADER_BYTES + 13, pkt.size)
        assertEquals(
            "0101" +
            "101112131415161718191a1b1c1d1e1f" +
            "afc8" +
            "0d" +
            "3139322e3136382e302e313038",
            hex(pkt))

        val r = CallInvite.parse(pkt)
        assertEquals(CallInvite.Status.OK, r.status)
        assertNotNull(r.invite)
        assertEquals(CallInvite.FLAG_AUDIO, r.invite!!.flags)
        assertArrayEquals(callId, r.invite.callId)
        assertEquals(45000, r.invite.port)
        assertEquals("192.168.0.108", r.invite.host)
        assertEquals(false, r.invite.hasVideo)
    }

    @Test
    fun relayedCallCarriesNoHint() {
        val pkt = CallInvite.build(CallInvite.Invite(
            flags = CallInvite.FLAG_AUDIO or CallInvite.FLAG_VIDEO, callId = callId))
        assertEquals(CallInvite.HEADER_BYTES, pkt.size)
        assertEquals("0103" + "101112131415161718191a1b1c1d1e1f" + "0000" + "00", hex(pkt))

        val r = CallInvite.parse(pkt)
        assertEquals(CallInvite.Status.OK, r.status)
        assertEquals(0, r.invite!!.port)
        assertEquals("", r.invite.host)
        assertEquals(true, r.invite.hasVideo)
    }

    @Test
    fun ipv6LiteralSurvives() {
        val pkt = CallInvite.build(CallInvite.Invite(
            flags = CallInvite.FLAG_AUDIO, callId = callId, host = "2001:db8::1"))
        assertEquals("2001:db8::1", CallInvite.parse(pkt).invite!!.host)
    }

    @Test
    fun rejectionMatrix() {
        val good = CallInvite.build(CallInvite.Invite(
            flags = CallInvite.FLAG_AUDIO, callId = callId, port = 45000, host = "host.example"))

        fun mutated(f: (ByteArray) -> Unit): CallInvite.Status {
            val b = good.copyOf(); f(b); return CallInvite.parse(b).status
        }

        assertEquals(CallInvite.Status.ERR_VERSION, mutated { it[0] = 0x02 })
        assertEquals(CallInvite.Status.ERR_RESERVED, mutated { it[1] = (it[1].toInt() or 0x80).toByte() })
        assertEquals(CallInvite.Status.ERR_CALLID, mutated { for (i in 2 until 18) it[i] = 0 })
        assertEquals(CallInvite.Status.ERR_LENGTH, mutated { it[20] = (it[20] + 1).toByte() })

        // A host from another party reaches a connect call and a log line.
        assertEquals(CallInvite.Status.ERR_HOST, mutated { it[21] = ' '.code.toByte() })
        assertEquals(CallInvite.Status.ERR_HOST, mutated { it[21] = '\n'.code.toByte() })
        assertEquals(CallInvite.Status.ERR_HOST, mutated { it[21] = ';'.code.toByte() })

        assertEquals(CallInvite.Status.ERR_LENGTH, CallInvite.parse(good, good.size - 1).status)
        assertEquals(CallInvite.Status.ERR_TOO_SHORT,
            CallInvite.parse(good, CallInvite.HEADER_BYTES - 1).status)
        assertEquals(CallInvite.Status.ERR_TOO_SHORT, CallInvite.parse(good, 0).status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAllZeroCallId() {
        CallInvite.build(CallInvite.Invite(flags = CallInvite.FLAG_AUDIO, callId = ByteArray(16)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesIllegalHost() {
        CallInvite.build(CallInvite.Invite(
            flags = CallInvite.FLAG_AUDIO, callId = callId, host = "bad host"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesReservedFlags() {
        CallInvite.build(CallInvite.Invite(flags = CallInvite.FLAG_AUDIO or 0x40, callId = callId))
    }
}
