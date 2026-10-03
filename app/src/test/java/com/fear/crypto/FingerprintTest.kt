package com.fear.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FingerprintTest {

    /** BLAKE2b из BouncyCastle: независимая от lazysodium реализация. */
    private val bc = KeyedHash { data, key, outLen ->
        val d = Blake2bDigest(if (key.isEmpty()) null else key, outLen, null, null)
        d.update(data, 0, data.size)
        ByteArray(outLen).also { d.doFinal(it, 0) }
    }

    private val pk = ByteArray(32) { it.toByte() }

    @Test
    fun matchesTheVectorPinnedOnTheCSide() {
        // Тот же эталон закреплён в tests/test_identity.c. Посчитан ещё и
        // третьей реализацией - hashlib.blake2b(digest_size=8) в Python.
        assertEquals("40:f6:8f:4a:d2:4e:57:5b", Fingerprint.of(pk, bc))
        assertEquals("40f68f4a", Fingerprint.short(pk, bc))
    }

    @Test
    fun isNotThePrefixOfBlake2b256() {
        // Так считало ядро на C до исправления - и расходилось с телефоном.
        val old = bc.blake2b(pk, ByteArray(0), 32).copyOf(8).joinToString(":") { "%02x".format(it) }
        assertEquals("cb:2f:51:60:fc:1f:7e:05", old)
        assertTrue(old != Fingerprint.of(pk, bc))
    }

    @Test
    fun theShortFormIsAlwaysTheStartOfTheFullOne() {
        val rnd = Random(42)
        repeat(100) {
            val key = ByteArray(32).also { rnd.nextBytes(it) }
            assertEquals(Fingerprint.of(key, bc).replace(":", "").take(8), Fingerprint.short(key, bc))
        }
    }
}
