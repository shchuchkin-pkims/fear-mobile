package com.fear.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Чем скрепляется запись в конверте ротации.
 *
 * Привязка считается от комнаты, и обе платформы обязаны брать одну и ту же
 * строку. Настольный клиент кладёт туда метку - ту самую, что едет по
 * проводу. Долгое время Android клал название, и это не ломалось вслух:
 * запись, адресованная собеседнику на ПК, у него просто «не открывалась»,
 * а выглядело это как «участник не появился, имя не показывается,
 * сообщений нет».
 *
 * Поэтому проверяется не только формула, но и то, что метка и название -
 * разные строки, а значит перепутать их означает разойтись.
 */
class RotationRoomTest {

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
        return "r:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun binding(room: String, version: Int,
                        senderPk: ByteArray, recipientPk: ByteArray): ByteArray {
        val ctx = "fear.rotation.v1".toByteArray(Charsets.US_ASCII)
        val ver = byteArrayOf((version and 0xFF).toByte(), ((version shr 8) and 0xFF).toByte())
        return bc.blake2b(
            ctx + room.toByteArray(Charsets.UTF_8) + ver + senderPk + recipientPk,
            ByteArray(0), 32,
        )
    }

    @Test
    fun `привязка от метки и от названия - это разные привязки`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val byName = binding("general", 3, a, b)
        val byWire = binding(wire("general"), 3, a, b)
        // Если бы они совпадали, перепутать было бы безобидно. Они не совпадают.
        assertNotEquals(byName.toList(), byWire.toList())
    }

    @Test
    fun `метка комнаты совпадает с вектором настольного клиента`() {
        // Тот же вектор, что закреплён в identity.c: обе стороны обязаны
        // прийти к одной строке, иначе привязки разойдутся.
        assertEquals("r:z6fjUIe2RRC26KwaOZ3Gpg", wire("general"))
    }

    @Test
    fun `привязка зависит от каждого своего слагаемого`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val base = binding(wire("general"), 3, a, b)
        assertNotEquals(base.toList(), binding(wire("work"), 3, a, b).toList())
        assertNotEquals(base.toList(), binding(wire("general"), 4, a, b).toList())
        assertNotEquals(base.toList(), binding(wire("general"), 3, b, a).toList())
    }
}
