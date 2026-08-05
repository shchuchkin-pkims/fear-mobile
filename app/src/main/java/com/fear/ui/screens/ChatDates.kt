package com.fear.ui.screens

import java.time.Instant
import java.time.ZoneId

/**
 * Раскладка сообщений по дням - отдельно от разметки, чтобы её можно было
 * проверить тестом.
 *
 * Ошибаться тут легко и незаметно: сравнение самих меток времени вместо
 * календарных дней разъезжается на границе суток, а «вчера» через
 * вычитание суток из миллисекунд врёт в дни перевода часов и на стыке
 * годов. Всё это ошибки, которые видны один день в году.
 */

/** Один ли это местный календарный день. */
internal fun sameLocalDay(a: Instant, b: Instant, zone: ZoneId): Boolean =
    a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()

/** Что писать в разделителе. */
internal enum class DayLabelKind { TODAY, YESTERDAY, THIS_YEAR, OTHER_YEAR }

/**
 * Насколько давняя дата - в тех единицах, в которых её потом называют.
 *
 * «Вчера» считается календарём, а не вычитанием суток: 26 часов назад в
 * день перевода часов - это тоже вчера, а 23 часа назад на стыке годов -
 * это вчера прошлого года.
 */
internal fun dayLabelKind(timestamp: Instant, now: Instant, zone: ZoneId): DayLabelKind {
    val day = timestamp.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        day == today -> DayLabelKind.TODAY
        day == today.minusDays(1) -> DayLabelKind.YESTERDAY
        day.year == today.year -> DayLabelKind.THIS_YEAR
        else -> DayLabelKind.OTHER_YEAR
    }
}
