package com.fear

import kotlin.math.max
import kotlin.math.min

/**
 * Подстройка битрейта видео под то, что сеть успевает унести.
 *
 * Своего регулятора у телефона не было. Когда Wi-Fi не успевал, запись в
 * сокет просто блокировалась на заполненном буфере, камера пропускала кадры,
 * а кодировщик продолжал выдавать каждый кадр в полную величину. Худшее
 * сочетание: редкие, но тяжёлые кадры. На живом звонке втроём телефон с
 * настройкой 1973 кбит/с отправлял 2,6 Мбит/с, очередь выгрузки держалась в
 * среднем на 45 КБ, и ПК показал только 83 % его кадров - остальные пришли
 * пачкой и устарели. ПК при этом сам снижает качество, а телефон не умел.
 *
 * Сигнал затора - время, на которое запись кадра в сокет встала. Он местный
 * и прямой: блокируется только наша собственная выгрузка. RTT - сигнал
 * второй и с высоким порогом: в групповом звонке эхо адресовано никому, и
 * число описывает путь до того, кто ответил последним.
 *
 * Политика та же, что у TCP: при заторе снижаем мультипликативно и какое-то
 * время не пытаемся подняться, без затора поднимаемся понемногу. Потолок -
 * то, что человек выставил в настройках: мы никогда не шлём больше, чем он
 * разрешил, только меньше, когда сеть не тянет.
 *
 * Класс без зависимостей от Android: время приходит снаружи, поэтому вся
 * политика проверяется JVM-тестами без устройства.
 */
class BitrateGovernor(ceilingKbps: Int, floorKbps: Int = DEFAULT_FLOOR_KBPS) {

    companion object {
        /** Ниже этого видео перестаёт быть видео. */
        const val DEFAULT_FLOOR_KBPS = 150

        /** Решения принимаются раз в окно. */
        const val WINDOW_MS = 1000L

        /** Запись кадра дольше этого - не копирование в ядро, а ожидание. */
        const val SLOW_SEND_MS = 15L

        /** Сколько окна запись может простоять, прежде чем это затор. */
        const val CONGESTED_BLOCKED_MS = 100L

        /** RTT, начиная с которого это затор, если число свежее. */
        const val CONGESTED_RTT_MS = 700

        /** RTT старше этого не учитывается: звонок мог уже выздороветь. */
        const val RTT_FRESH_MS = 5000L

        /** Во сколько раз снижаем при заторе. */
        const val DECREASE_FACTOR = 0.7

        /** Сколько после снижения не пытаемся подняться. */
        const val HOLD_AFTER_DECREASE_MS = 3000L

        /** Сколько чистых окон подряд нужно для шага вверх. */
        const val CLEAN_WINDOWS_TO_INCREASE = 3

        /** Самый маленький шаг вверх. */
        const val MIN_STEP_KBPS = 40

        /**
         * Доля окна, при которой затор тяжёлый: запись стоит больше половины
         * времени. Только такой затор режет и в окне сразу после снижения.
         */
        const val SEVERE_BLOCKED_FRACTION = 0.5
    }

    val ceilingKbps: Int = max(ceilingKbps, 1)
    val floorKbps: Int = min(max(floorKbps, 1), this.ceilingKbps)

    /** Текущая цель кодировщика. Начинаем с потолка: сети стоит дать шанс. */
    var currentKbps: Int = this.ceilingKbps
        @Synchronized get
        private set

    /** Почему цель менялась в последний раз - для журнала. */
    var lastReason: String = ""
        @Synchronized get
        private set

    private var windowStart = -1L
    private var blockedMs = 0L
    private var cleanWindows = 0
    private var holdUntil = 0L
    private var rttMs = 0
    private var rttAt = -1L
    /** Метка замера RTT, уже учтённого в решении: один замер - одно решение. */
    private var rttUsedAt = -1L
    /** Прошлое окно закончилось снижением - это окно очередь ещё рассасывается. */
    private var draining = false

    /** Кадр отдан в сокет, запись заняла [sendMs]. */
    @Synchronized
    fun onFrameSent(nowMs: Long, sendMs: Long) {
        if (windowStart < 0) windowStart = nowMs
        if (sendMs > SLOW_SEND_MS) blockedMs += sendMs
    }

    /** Свежий замер RTT. Ноль и отрицательные - «не знаем», не учитываются. */
    @Synchronized
    fun onRtt(nowMs: Long, rtt: Int) {
        if (rtt <= 0) return
        rttMs = rtt
        rttAt = nowMs
    }

    /**
     * Подвести итог окна, если оно истекло.
     *
     * @return новая цель в кбит/с, если она изменилась, иначе null.
     *         Вызывать можно хоть на каждом кадре: решение - раз в окно.
     */
    @Synchronized
    fun evaluate(nowMs: Long): Int? {
        if (windowStart < 0) {
            windowStart = nowMs
            return null
        }
        if (nowMs - windowStart < WINDOW_MS) return null

        val blocked = blockedMs
        val window = nowMs - windowStart
        windowStart = nowMs
        blockedMs = 0

        /*
         * Замер RTT приходит раз в пару секунд, а окно - раз в секунду.
         * Учитываем каждый замер один раз: иначе одно и то же число резало бы
         * поток в каждом окне, пока не протухнет. На живом звонке так один
         * замер в 1006 мс четырьмя шагами опустил 742 кбит/с до 177.
         */
        val rttNew = rttAt >= 0 && rttAt != rttUsedAt && nowMs - rttAt <= RTT_FRESH_MS
        rttUsedAt = rttAt

        /*
         * Сразу после снижения очередь, накопленная до него, ещё уходит, и
         * запись по-прежнему встаёт. Это не новый затор, а старый, который
         * мы уже учли: резать снова можно, только если затор тяжёлый. RTT в
         * этом окне меряет ту же уходящую очередь и не учитывается.
         */
        val severe = blocked >= window * SEVERE_BLOCKED_FRACTION
        val sendCongested = blocked >= CONGESTED_BLOCKED_MS && (!draining || severe)
        val rttCongested = !draining && rttNew && rttMs >= CONGESTED_RTT_MS
        draining = false

        if (sendCongested || rttCongested) {
            draining = true
            cleanWindows = 0
            holdUntil = nowMs + HOLD_AFTER_DECREASE_MS
            val next = max(floorKbps, (currentKbps * DECREASE_FACTOR).toInt())
            lastReason = if (sendCongested) "send blocked $blocked ms in $window ms"
                         else "RTT $rttMs ms"
            return change(next)
        }

        cleanWindows++
        if (nowMs < holdUntil || cleanWindows < CLEAN_WINDOWS_TO_INCREASE) return null
        cleanWindows = 0
        val next = min(ceilingKbps, currentKbps + max(MIN_STEP_KBPS, currentKbps / 10))
        lastReason = "link clear"
        return change(next)
    }

    private fun change(next: Int): Int? {
        if (next == currentKbps) return null
        currentKbps = next
        return next
    }
}
