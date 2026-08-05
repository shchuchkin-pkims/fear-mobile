package com.fear.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Что подписывает анонс личности.
 *
 * Вектор закреплён, потому что ровно те же байты подписывает настольный
 * клиент (identity_announce_signed_bytes). Разойдись склейка хоть на байт -
 * подписи перестали бы сходиться, и каждая сторона показывала бы
 * собеседника неизвестным, ничего при этом не сломав вслух: ни ошибки, ни
 * сообщения, просто «?ABCDEFGH» вместо имени.
 */
class SessionTagTest {

    @Test
    fun `совпадает с вектором из identity_c`() {
        val got = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAA", "alice")
        val want = "fear.announce.v2AAAAAAAAAAAAAAAAAAAAAAalice"
            .toByteArray(Charsets.US_ASCII)
        assertArrayEquals(want, got)
    }

    @Test
    fun `метка входит в подпись целиком`() {
        // Иначе чужой анонс повторили бы под своей меткой и забрали имя.
        val a = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAA", "alice")
        val b = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAB", "alice")
        assertEquals(a.size, b.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `имя входит в подпись целиком`() {
        val a = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAA", "alice")
        val b = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAA", "bob")
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `метка постоянной длины разделяет части однозначно`() {
        // Метка ровно 22 знака, поэтому «где кончается метка и начинается
        // имя» не зависит от содержимого - разделитель не нужен.
        assertEquals(22, SessionTag.LENGTH)
        val ctx = "fear.announce.v2".length
        val got = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAA", "alice")
        assertEquals(ctx + SessionTag.LENGTH + "alice".length, got.size)
    }

    @Test
    fun `имя в юникоде не ломает склейку`() {
        val got = SessionTag.announceSignedBytes("AAAAAAAAAAAAAAAAAAAAAA", "Аня")
        val want = "fear.announce.v2AAAAAAAAAAAAAAAAAAAAAA".toByteArray(Charsets.US_ASCII) +
            "Аня".toByteArray(Charsets.UTF_8)
        assertArrayEquals(want, got)
    }
}
