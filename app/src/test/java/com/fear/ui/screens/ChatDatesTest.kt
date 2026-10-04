package com.fear.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Раскладка сообщений по дням.
 *
 * Проверяется то, что ломается тихо и раз в году: граница суток в местном
 * часовом поясе, «вчера» в день перевода часов и на стыке годов. В самом
 * приложении такую ошибку увидишь один день из трёхсот шестидесяти пяти, и
 * то если будешь смотреть.
 */
class ChatDatesTest {

    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Instant =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant()

    @Test
    fun `один день считается по местному календарю, а не по UTC`() {
        // 23:30 и 00:30 по Москве - разные дни, хотя по UTC оба ещё 5 августа.
        val late = at(moscow, 2026, 8, 5, 23, 30)
        val justAfter = at(moscow, 2026, 8, 6, 0, 30)
        assertFalse(sameLocalDay(late, justAfter, moscow))

        // Час между ними - меньше суток, так что вычитание миллисекунд здесь
        // сказало бы «тот же день».
        assertTrue(justAfter.toEpochMilli() - late.toEpochMilli() < 24 * 3600 * 1000L)
    }

    @Test
    fun `утро и вечер одного дня - один день`() {
        assertTrue(
            sameLocalDay(
                at(moscow, 2026, 8, 5, 0, 1),
                at(moscow, 2026, 8, 5, 23, 59),
                moscow,
            ),
        )
    }

    @Test
    fun `сегодня, вчера и позавчера`() {
        val now = at(moscow, 2026, 8, 5, 12, 0)
        assertEquals(DayLabelKind.TODAY, dayLabelKind(at(moscow, 2026, 8, 5, 0, 5), now, moscow))
        assertEquals(DayLabelKind.YESTERDAY, dayLabelKind(at(moscow, 2026, 8, 4, 23, 55), now, moscow))
        assertEquals(DayLabelKind.THIS_YEAR, dayLabelKind(at(moscow, 2026, 8, 3, 12, 0), now, moscow))
    }

    @Test
    fun `год пишется только когда он не нынешний`() {
        val now = at(moscow, 2026, 8, 5, 12, 0)
        assertEquals(DayLabelKind.THIS_YEAR, dayLabelKind(at(moscow, 2026, 1, 1, 12, 0), now, moscow))
        assertEquals(DayLabelKind.OTHER_YEAR, dayLabelKind(at(moscow, 2025, 12, 31, 12, 0), now, moscow))
    }

    @Test
    fun `первое января - вчера прошлого года, а не «в этом году»`() {
        val now = at(moscow, 2026, 1, 1, 10, 0)
        assertEquals(
            DayLabelKind.YESTERDAY,
            dayLabelKind(at(moscow, 2025, 12, 31, 22, 0), now, moscow),
        )
    }

    @Test
    fun `перевод часов не сдвигает «вчера»`() {
        // В Берлине 29 марта 2026 сутки короче на час: между 12:00 28-го и
        // 12:00 29-го проходит 23 часа, а не 24.
        val berlin = ZoneId.of("Europe/Berlin")
        val now = at(berlin, 2026, 3, 29, 12, 0)
        val dayBefore = at(berlin, 2026, 3, 28, 12, 0)
        assertEquals(23 * 3600 * 1000L, now.toEpochMilli() - dayBefore.toEpochMilli())
        assertEquals(DayLabelKind.YESTERDAY, dayLabelKind(dayBefore, now, berlin))

        // И осенью, когда сутки на час длиннее.
        val autumnNow = at(berlin, 2026, 10, 25, 12, 0)
        val autumnBefore = at(berlin, 2026, 10, 24, 12, 0)
        assertEquals(25 * 3600 * 1000L, autumnNow.toEpochMilli() - autumnBefore.toEpochMilli())
        assertEquals(DayLabelKind.YESTERDAY, dayLabelKind(autumnBefore, autumnNow, berlin))
    }
}
