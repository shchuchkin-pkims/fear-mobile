package com.fear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика BitrateGovernor целиком, без устройства: время подаётся снаружи.
 */
class BitrateGovernorTest {

    private val window = BitrateGovernor.WINDOW_MS

    /** Прожить одно окно: кадры каждые 50 мс с записью [sendMs]. */
    private fun liveWindow(g: BitrateGovernor, start: Long, sendMs: Long): Pair<Long, Int?> {
        var t = start
        var changed: Int? = null
        while (t < start + window) {
            t += 50
            g.onFrameSent(t, sendMs)
            g.evaluate(t)?.let { changed = it }
        }
        return t to changed
    }

    @Test
    fun startsAtTheCeilingAndDecidesNothingInsideTheFirstWindow() {
        val g = BitrateGovernor(1973)
        assertEquals(1973, g.currentKbps)
        g.onFrameSent(0, 500)
        assertNull(g.evaluate(0))
        assertNull(g.evaluate(window - 1))
    }

    @Test
    fun sustainedBlockingCutsMultiplicativelyDownToTheFloorAndNoFurther() {
        val g = BitrateGovernor(1973)
        var t = 0L
        g.evaluate(t)
        val seen = mutableListOf<Int>()
        repeat(20) {
            val (end, changed) = liveWindow(g, t, sendMs = 40)   // 20 кадров x 40 мс = 800 мс стоим
            t = end
            changed?.let { seen.add(it) }
        }
        assertEquals((1973 * 0.7).toInt(), seen.first())
        for (i in 1 until seen.size) {
            assertTrue("каждый шаг - снижение", seen[i] < seen[i - 1])
        }
        assertEquals(BitrateGovernor.DEFAULT_FLOOR_KBPS, g.currentKbps)
        // У пола больше не меняется - и об этом не сообщает.
        assertNull(liveWindow(g, t, sendMs = 40).second)
    }

    @Test
    fun oneSlowFrameIsNotCongestion() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        g.onFrameSent(100, 80)          // один кадр простоял 80 мс - меньше порога окна
        assertNull(g.evaluate(window + 1))
        assertEquals(1000, g.currentKbps)
    }

    @Test
    fun fastWritesDoNotAddUpToCongestion() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        // Сорок кадров по 10 мс: это копирование в ядро, а не ожидание сети.
        for (i in 1..40) g.onFrameSent(i * 20L, 10)
        assertNull(g.evaluate(window + 1))
        assertEquals(1000, g.currentKbps)
    }

    @Test
    fun holdsAfterACutThenClimbsBackExactlyToTheCeiling() {
        val ceiling = 1000
        val g = BitrateGovernor(ceiling)
        var t = 0L
        g.evaluate(t)
        val (afterCut, cut) = liveWindow(g, t, sendMs = 200)
        t = afterCut
        assertEquals(700, cut)

        // Пауза после снижения: три секунды никаких шагов вверх.
        var firstRaiseAt = -1L
        val raises = mutableListOf<Int>()
        repeat(60) {
            val (end, changed) = liveWindow(g, t, sendMs = 2)
            if (changed != null) {
                if (firstRaiseAt < 0) firstRaiseAt = end
                raises.add(changed)
            }
            t = end
        }
        assertTrue("подъём не раньше паузы", firstRaiseAt - afterCut >= BitrateGovernor.HOLD_AFTER_DECREASE_MS)
        for (i in 1 until raises.size) assertTrue("подъём монотонный", raises[i] > raises[i - 1])
        assertEquals("возвращаемся ровно к потолку", ceiling, g.currentKbps)
        assertTrue("и не выше", raises.all { it <= ceiling })
    }

    @Test
    fun freshHighRttIsCongestionButStaleRttIsNot() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        g.onRtt(500, 900)
        assertEquals(700, g.evaluate(window + 1))
        assertTrue(g.lastReason.startsWith("RTT"))

        val h = BitrateGovernor(1000)
        h.evaluate(0)
        h.onRtt(0, 900)
        // Окно подводим, когда замер старше RTT_FRESH_MS: звонок мог выздороветь.
        assertNull(h.evaluate(BitrateGovernor.RTT_FRESH_MS + 1))
        assertEquals(1000, h.currentKbps)
    }

    @Test
    fun unknownRttIsIgnored() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        g.onRtt(100, 0)
        g.onRtt(200, -5)
        assertNull(g.evaluate(window + 1))
    }

    @Test
    fun aCeilingBelowTheDefaultFloorIsStillHonoured() {
        val g = BitrateGovernor(100)
        assertEquals(100, g.floorKbps)
        g.evaluate(0)
        g.onFrameSent(10, 500)
        assertNull("ниже потолка, который ниже пола, не уходим", g.evaluate(window + 1))
        assertEquals(100, g.currentKbps)
    }

    /** Одно окно с заданным суммарным простоем записи, без RTT. */
    private fun blockedWindow(g: BitrateGovernor, start: Long, blockedMs: Long): Int? {
        g.onFrameSent(start + 10, blockedMs)
        return g.evaluate(start + window)
    }

    @Test
    fun oneRttSampleCutsAtMostOnce() {
        // Живой звонок: один замер в 1006 мс резал четыре окна подряд.
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        g.onRtt(100, 1006)
        assertEquals(700, g.evaluate(window))
        var t = window
        repeat(4) {
            t += window
            g.evaluate(t)
            assertTrue("тот же замер больше не режет", g.currentKbps >= 700)
        }
    }

    @Test
    fun mildBlockingRightAfterACutIsTheQueueDrainingNotNewCongestion() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        assertEquals(700, blockedWindow(g, 0, 900))                 // тяжёлый затор - режем
        assertNull("очередь ещё уходит", blockedWindow(g, window, 200))
        assertEquals(700, g.currentKbps)
        assertEquals("а окно спустя - это уже новый затор", (700 * BitrateGovernor.DECREASE_FACTOR).toInt(), blockedWindow(g, 2 * window, 200))
    }

    @Test
    fun severeBlockingKeepsCuttingEvenRightAfterACut() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        assertEquals(700, blockedWindow(g, 0, 900))
        assertEquals((700 * BitrateGovernor.DECREASE_FACTOR).toInt(), blockedWindow(g, window, 800))  // стоим 80 % окна
    }

    @Test
    fun rttMeasuringTheDrainingQueueIsIgnored() {
        val g = BitrateGovernor(1000)
        g.evaluate(0)
        assertEquals(700, blockedWindow(g, 0, 900))
        g.onRtt(window + 100, 1200)                                 // замер уходящей очереди
        assertNull(g.evaluate(2 * window))
        assertNull("и он уже учтён", g.evaluate(3 * window))
        assertEquals(700, g.currentKbps)
    }
}
