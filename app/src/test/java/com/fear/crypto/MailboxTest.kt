package com.fear.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ящик, на байтовом уровне, где обе платформы обязаны сойтись.
 *
 * Адрес пинается вектором, посчитанным той же формулой, что в identity.c:
 * разъехавшись, телефон и ПК спрашивали бы разные ящики и молча не видели
 * писем друг друга.
 */
class MailboxTest {

    private val bc = KeyedHash { data, key, outLen ->
        val d = org.bouncycastle.crypto.digests.Blake2bDigest(
            if (key.isEmpty()) null else key, outLen, null, null,
        )
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val kPm = ByteArray(32) { it.toByte() }
    private val alice = ByteArray(32) { (32 + it).toByte() }
    private val bob = ByteArray(32) { (64 + it).toByte() }

    @Test
    fun `адрес зависит от ключа пары и от получателя`() {
        val toAlice = Mailbox.address(kPm, alice, bc)
        assertEquals(Mailbox.ADDR_BYTES, toAlice.size)

        // У пары два ящика: письма Алисе и письма Бобу лежат раздельно, и
        // никто не забирает из ящика свои же письма.
        assertEquals(false, hex(toAlice) == hex(Mailbox.address(kPm, bob, bc)))

        // Другой ключ пары - другой ящик.
        val other = Mailbox.address(ByteArray(32) { (it + 1).toByte() }, alice, bc)
        assertEquals(false, hex(toAlice) == hex(other))
    }

    @Test
    fun `адрес совпадает с вектором из identity_c`() {
        /* BLAKE2b(key = 00..1f, "fear.inbox.v2" || pk получателя, 32) - тот же
         * вектор закреплён в test_identity на ПК и посчитан третий раз
         * hashlib. Разъехавшись, две стороны спрашивали бы разные ящики и
         * молча не видели писем друг друга. */
        assertEquals(
            "f28bfab028371d40570de2bee5a7aac50c2870ee79dc76f64b06796cfc233787",
            hex(Mailbox.address(kPm, alice, bc)),
        )
        assertEquals(
            "87ce0c2c561b4dd809ec8e6931df61cf93c24134c80d9b3c53bfbcb804ed5c76",
            hex(Mailbox.address(kPm, bob, bc)),
        )
    }
}
