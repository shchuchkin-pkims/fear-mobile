package com.fear.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Идентификатор личной комнаты, выведенный под ключом пары.
 *
 * Вектор закреплён потому же, почему и остальные: то же самое вычисляет
 * настольный клиент, и разойдясь, две стороны оказались бы в разных комнатах
 * и молча не видели друг друга.
 *
 * Сам вывод живёт в IdentityManager, которому нужен ключ из хранилища
 * устройства, поэтому здесь проверяется формула - той же библиотекой, которой
 * проверяются остальные векторы.
 */
class PmRoomTest {

    private val bc = KeyedHash { data, key, outLen ->
        val d = org.bouncycastle.crypto.digests.Blake2bDigest(
            if (key.isEmpty()) null else key, outLen, null, null,
        )
        d.update(data, 0, data.size)
        val out = ByteArray(outLen)
        d.doFinal(out, 0)
        out
    }

    private fun roomIdV2(kPm: ByteArray): String {
        val digest = bc.blake2b("fear.pm.room.v2".toByteArray(Charsets.US_ASCII), kPm, 16)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "pm:$b64"
    }

    @Test
    fun `совпадает с вектором из identity_c`() {
        val kPm = ByteArray(32) { it.toByte() }
        assertEquals("pm:2cqzkCvp122e_u-J5N7BnQ", roomIdV2(kPm))
    }

    @Test
    fun `другой ключ пары - другая комната`() {
        val a = roomIdV2(ByteArray(32) { it.toByte() })
        val b = roomIdV2(ByteArray(32) { 0x5A })
        assertEquals(false, a == b)
    }
}
