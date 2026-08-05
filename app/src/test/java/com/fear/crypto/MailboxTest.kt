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

    @Test
    fun `адрес считается от ключа пары, а не от чего-то ещё`() {
        val a = Mailbox.address(kPm, bc)
        assertEquals(Mailbox.ADDR_BYTES, a.size)

        // Другой ключ пары - другой ящик.
        val other = Mailbox.address(ByteArray(32) { (it + 1).toByte() }, bc)
        assertEquals(false, hex(a) == hex(other))
    }

    @Test
    fun `адрес совпадает с вектором из identity_c`() {
        /* BLAKE2b(key = 00..1f, "fear.inbox.v1", 32) - посчитано libsodium
         * тем же кодом, что и на ПК. Разъехавшись, две стороны спрашивали бы
         * разные ящики и молча не видели писем друг друга, поэтому вектор
         * закреплён здесь, а не выводится ещё раз этой же формулой. */
        assertEquals(
            "e1aca044c1f5b89af56de8d28da68920347aacd0545fb2627351ed5f2c0066b1",
            hex(Mailbox.address(kPm, bc)),
        )
    }
}
