package com.fear

import com.fear.crypto.Fingerprint
import com.fear.crypto.KeyedHash
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Список доверенных ключей переживает смену формулы короткого отпечатка.
 */
class KnownKeysRelabelTest {

    private val bc = KeyedHash { data, key, outLen ->
        val d = Blake2bDigest(if (key.isEmpty()) null else key, outLen, null, null)
        d.update(data, 0, data.size)
        ByteArray(outLen).also { d.doFinal(it, 0) }
    }

    private val pkA = ByteArray(32) { it.toByte() }
    private val pkB = ByteArray(32) { (it * 7 + 3).toByte() }

    private fun key(name: String, pk: ByteArray, verified: Boolean = false) =
        IdentityManager.KnownKey(name, pk, verified)

    @Test
    fun anEntryUnderTheOldShortFormGetsTheNewName() {
        val old = Fingerprint.legacyShort(pkA, bc)
        val out = relabelLegacyShortFingerprints(listOf(key(old, pkA, verified = true)), bc)!!
        assertEquals(1, out.size)
        assertEquals(Fingerprint.short(pkA, bc), out[0].name)
        assertTrue("отметка «проверен» не теряется", out[0].verified)
    }

    @Test
    fun theOldAndTheNewEntryOfOneKeyBecomeOne() {
        // Звонок после обновления уже успел завести запись под новой меткой.
        val keys = listOf(
            key(Fingerprint.legacyShort(pkA, bc), pkA, verified = true),
            key(Fingerprint.short(pkA, bc), pkA, verified = false),
        )
        val out = relabelLegacyShortFingerprints(keys, bc)!!
        assertEquals(1, out.size)
        assertEquals(Fingerprint.short(pkA, bc), out[0].name)
        assertTrue(out[0].verified)
    }

    @Test
    fun namedEntriesAndNewLabelsAreLeftAlone() {
        val keys = listOf(
            key("alice", pkA, verified = true),            // запись чата - по имени
            key(Fingerprint.short(pkB, bc), pkB),          // уже новая метка
            key("deadbeef", pkB),                          // 8 знаков, но не от этого ключа
        )
        assertNull(relabelLegacyShortFingerprints(keys, bc))
    }

    @Test
    fun onlyTheLegacyEntryMovesAndOrderIsKept() {
        val keys = listOf(
            key("alice", pkA),
            key(Fingerprint.legacyShort(pkB, bc), pkB),
            key("bob", pkB),
        )
        val out = relabelLegacyShortFingerprints(keys, bc)!!
        assertEquals(listOf("alice", Fingerprint.short(pkB, bc), "bob"), out.map { it.name })
    }
}
