package com.fear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameRateLimiterTest {

    /** Сколько кадров пропустит предел [limit] из потока камеры [cameraFps] за [seconds]. */
    private fun admitted(limit: Int, cameraFps: Double, seconds: Int, jitterMs: Double = 0.0,
                         seed: Int = 1): Int {
        val l = FrameRateLimiter(limit)
        val rnd = Random(seed)
        val frames = (cameraFps * seconds).toInt()
        var n = 0
        for (i in 0 until frames) {
            val t = 1000.0 + i * 1000.0 / cameraFps + (rnd.nextDouble() * 2 - 1) * jitterMs
            if (l.admit(t.toLong())) n++
        }
        return n
    }

    @Test
    fun thirtyIntoTwentyAdmitsTwoFramesInThree() {
        // Живой звонок: телефон с настройкой 20 слал около 31 кадра в секунду.
        val n = admitted(limit = 20, cameraFps = 30.0, seconds = 10)
        assertTrue("ожидалось около 200, вышло $n", n in 199..201)
    }

    @Test
    fun aSlowerCameraPassesEveryFrame() {
        assertEquals(150, admitted(limit = 20, cameraFps = 15.0, seconds = 10))
    }

    @Test
    fun anEqualRatePassesEveryFrameDespiteJitter() {
        assertEquals(200, admitted(limit = 20, cameraFps = 20.0, seconds = 10, jitterMs = 2.0))
    }

    @Test
    fun jitteredThirtyStaysAtTwentyOverTime() {
        val n = admitted(limit = 20, cameraFps = 30.0, seconds = 30, jitterMs = 3.0, seed = 7)
        assertTrue("ожидалось 20 ± 0,5 кадра/с, вышло ${n / 30.0}", n in 585..615)
    }

    @Test
    fun aPauseDoesNotCauseABurstAfterwards() {
        val l = FrameRateLimiter(20)
        for (i in 0 until 30) l.admit(1000L + i * 33)       // секунда на 30 кадрах
        // Пауза две секунды, потом снова 30 кадров в секунду.
        var n = 0
        var last = -1L
        for (i in 0 until 15) {                                // полсекунды
            val t = 4000L + i * 33
            if (l.admit(t)) {
                if (last >= 0) assertTrue("кадры не идут залпом", t - last >= 33)
                last = t
                n++
            }
        }
        assertTrue("за полсекунды около 10 кадров, а не 15: $n", n in 9..11)
    }
}
