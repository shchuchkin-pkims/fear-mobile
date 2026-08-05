package com.fear.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Метка комнаты на проводе.
 *
 * Вектор закреплён, потому что то же самое вычисляет настольный клиент:
 * разойдясь, телефон и ПК оказались бы в разных комнатах и молча не видели
 * друг друга - ни ошибки, ни сообщения, просто тишина.
 *
 * Сама WireRoom пользуется android.util.Base64, которого на голой JVM нет,
 * поэтому здесь проверяется формула той же библиотекой, что и остальные
 * векторы.
 */
class WireRoomTest {

    private val bc = KeyedHash { data, key, outLen ->
        val d = org.bouncycastle.crypto.digests.Blake2bDigest(
            if (key.isEmpty()) null else key, outLen, null, null,
        )
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private fun wire(name: String): String {
        val ctx = "fear.room.v1".toByteArray(Charsets.US_ASCII)
        val digest = bc.blake2b(ctx + name.toByteArray(Charsets.UTF_8), ByteArray(0), 16)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "r:$b64"
    }

    @Test
    fun `совпадает с вектором из identity_c`() {
        assertEquals("r:z6fjUIe2RRC26KwaOZ3Gpg", wire("general"))
    }

    @Test
    fun `пустое имя тоже имеет метку`() {
        assertEquals("r:XoI6MSrHqeZYdQA_Q2HegQ", wire(""))
    }

    @Test
    fun `разные комнаты - разные метки`() {
        assertNotEquals(wire("general"), wire("work"))
    }
}
