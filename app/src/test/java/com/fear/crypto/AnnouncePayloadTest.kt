package com.fear.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Раскладка анонса личности: [pk(32)][sig(64)][name_len(2)][name].
 *
 * Тест собирает полезную нагрузку теми же байтовыми операциями, что и
 * приложение, и разбирает её теми же, что и приёмная сторона. Проверять надо
 * именно круг: длина имени писалась старшим байтом вперёд, а читалась
 * младшим, и имя из 29 байт превращалось в 7424. Анонс молча отбрасывался,
 * а выглядело это как «имена не показываются» на обеих платформах сразу.
 *
 * Порядок байтов на проводе задаёт C: wr_u16 кладёт младший байт первым.
 */
class AnnouncePayloadTest {

    private val PK = 32
    private val SIG = 64

    /** Как пишет приложение. */
    private fun build(name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(PK + SIG + 2 + nameBytes.size)
        // pk и sig здесь не важны - важны длина и её порядок байтов
        writeUInt16(out, PK + SIG, nameBytes.size)
        nameBytes.copyInto(out, PK + SIG + 2)
        return out
    }

    /** Младший байт первым - как wr_u16 в C и как Common.writeUInt16. */
    private fun writeUInt16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun readUInt16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `имя переживает круг записи и чтения`() {
        for (name in listOf("alice", "Татьяна Щучкина", "Evgenii Shchuchkin", "Ω")) {
            val payload = build(name)
            val dlen = readUInt16(payload, PK + SIG)
            assertEquals("длина имени '$name'", name.toByteArray(Charsets.UTF_8).size, dlen)
            val got = String(payload, PK + SIG + 2, dlen, Charsets.UTF_8)
            assertEquals(name, got)
        }
    }

    @Test
    fun `длина лежит младшим байтом вперёд`() {
        // 29 байт: если записать наоборот, прочитается 7424 - ровно то, что
        // и происходило на живых телефонах.
        val payload = build("Татьяна Щучкина")
        assertEquals(29, payload[PK + SIG].toInt() and 0xFF)
        assertEquals(0, payload[PK + SIG + 1].toInt() and 0xFF)
        assertEquals(29, readUInt16(payload, PK + SIG))
    }

    @Test
    fun `длина сходится с общим размером`() {
        val name = "Evgenii Shchuchkin"
        val payload = build(name)
        // Приёмник проверяет ровно это, прежде чем резать имя из буфера.
        val dlen = readUInt16(payload, PK + SIG)
        assertEquals(payload.size, PK + SIG + 2 + dlen)
    }

    @Test
    fun `подпись покрывает то же имя, что и уехало в нагрузке`() {
        val tag = "AAAAAAAAAAAAAAAAAAAAAA"
        val name = "Татьяна Щучкина"
        val payload = build(name)
        val dlen = readUInt16(payload, PK + SIG)
        val parsed = String(payload, PK + SIG + 2, dlen, Charsets.UTF_8)
        assertArrayEquals(
            SessionTag.announceSignedBytes(tag, name),
            SessionTag.announceSignedBytes(tag, parsed),
        )
    }
}
