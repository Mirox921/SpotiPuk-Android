package com.project.puk.jam

import kotlin.math.abs

/**
 * Pure clock/position sync math for Jam (owner correction #4). No Android
 * dependencies, fully covered by [JamSyncTest].
 */
object JamSync {

    // Drift thresholds (ms). Старт-рассинхрон ~0,4 c (владелец) должен
    // корректироваться: порог игнорирования 350 мс, умеренный гэп требует
    // 2 подтверждений подряд (2 × 2 c проверок), крупный — сразу.
    const val DRIFT_IGNORE_MS = 350L
    const val DRIFT_SEEK_IMMEDIATE_MS = 4_000L
    const val DRIFT_CONFIRMATIONS = 2

    /** Пауза после seek-коррекции: позиция должна устаканиться, иначе seek гоняется сам за собой. */
    const val SEEK_COOLDOWN_MS = 5_000L

    // Тонкая коррекция скоростью (spec раздел 5): гэпы между 80 и 350 мс,
    // которые seek не трогает, но которые держат устойчивые ~190 мс,
    // стягиваются плавным изменением скорости 0,5–2 % (высота тона
    // сохраняется — ExoPlayer time-stretch).
    const val NUDGE_IGNORE_MS = 80L
    const val NUDGE_RELEASE_MS = 40L
    const val NUDGE_CONFIRMATIONS = 3
    const val NUDGE_MIN_FACTOR = 0.005
    const val NUDGE_MAX_FACTOR = 0.02
    /** Гэп, закрывающийся за столько мс фактором 1.0 (масштаб 100 мс → 1,25 %). */
    const val NUDGE_SCALE_MS = 8_000.0

    /** How long a scheduled local start may wait before the server start moment. */
    const val MIN_START_LEAD_MS = 400L
    const val MAX_START_LEAD_MS = 6_000L

    /** Sampled RTT/offset measurement derived from one PING/PONG exchange. */
    data class Sample(val rttMs: Long, val offsetMs: Long)

    /**
     * NTP-lite estimate. `sentElapsed`/`receivedElapsed` are monotonic
     * (SystemClock.elapsedRealtime); `serverTimeMs` is the server wall clock
     * from the pong. offset = serverTime - localWallTime at the midpoint.
     */
    fun sample(sentElapsed: Long, receivedElapsed: Long, serverTimeMs: Long, localWallMs: Long): Sample {
        val rtt = (receivedElapsed - sentElapsed).coerceAtLeast(0)
        val offset = serverTimeMs - (localWallMs + rtt / 2)
        return Sample(rtt, offset)
    }

    /**
     * Robust estimate from the recent window. Midpoint offset carries
     * ±RTT/2 uncertainty, so only samples whose RTT is close to the best one
     * are allowed to vote: a slow exchange (one GC pause / WiFi power save
     * bubble) previously could become the median and shift the clock by
     * hundreds of ms. Offset = median of the kept samples, RTT = best RTT.
     */
    fun offsetOf(samples: List<Sample>): Sample? {
        val usable = samples.filter { it.rttMs <= 5_000 }
        if (usable.isEmpty()) return null
        val bestRtt = usable.minOf { it.rttMs }
        val keep = usable.filter { it.rttMs <= (bestRtt * 2.5 + 50).toLong() }
        if (keep.isEmpty()) return null
        val medianOffset = keep.map { it.offsetMs }.sorted()[keep.size / 2]
        return Sample(bestRtt, medianOffset)
    }

    /** Server wall-clock mapped onto local time: local wall now + offset. */
    fun serverTimeNow(offsetMs: Long?, localWallMs: Long): Long = localWallMs + (offsetMs ?: 0L)

    /** When to leave the wait: how many ms from now the server start moment is. */
    fun startDelayMs(startAtServerMs: Long, offsetMs: Long?, localWallMs: Long): Long {
        val serverNow = serverTimeNow(offsetMs, localWallMs)
        return (startAtServerMs - serverNow).coerceIn(MIN_START_LEAD_MS, MAX_START_LEAD_MS)
    }

    enum class DriftPlan { HOLD, NUDGE_DOWN, NUDGE_UP, RESET_SPEED, SEEK }

    /**
     * Полный план коррекции по замеренному рассинхрону.
     *
     * @param signedErrorMs  engine position − цель (цель = серверная позиция +
     *                       локальная аудио-задержка): >0 — движок впереди.
     * @param confirmations  сколько подряд замеров гэп держится ≥ [NUDGE_IGNORE_MS].
     * @param nudging        идёт ли уже тонкая коррекция скоростью.
     */
    fun driftPlan(signedErrorMs: Long, confirmations: Long, nudging: Boolean): DriftPlan {
        val gap = abs(signedErrorMs)
        return when {
            gap >= DRIFT_SEEK_IMMEDIATE_MS -> DriftPlan.SEEK
            gap >= DRIFT_IGNORE_MS ->
                if (confirmations >= DRIFT_CONFIRMATIONS) DriftPlan.SEEK
                else if (nudging) currentNudge(signedErrorMs)
                else DriftPlan.HOLD
            gap >= NUDGE_IGNORE_MS -> currentNudge(signedErrorMs, confirmations, nudging)
            nudging && gap >= NUDGE_RELEASE_MS -> currentNudge(signedErrorMs)
            else -> if (nudging) DriftPlan.RESET_SPEED else DriftPlan.HOLD
        }
    }

    /** Направление тонкой коррекции: движок впереди — замедлить, позади — ускорить. */
    private fun currentNudge(signedErrorMs: Long, confirmations: Long = Long.MAX_VALUE, nudging: Boolean = true): DriftPlan =
        if (!nudging && confirmations < NUDGE_CONFIRMATIONS) DriftPlan.HOLD
        else if (signedErrorMs > 0) DriftPlan.NUDGE_DOWN else DriftPlan.NUDGE_UP

    /** Скорость тонкой коррекции: 100 мс гэп → ~1,3 %, зажим 0,5–2 %. */
    fun nudgeFactor(gapMs: Long): Double =
        (gapMs / NUDGE_SCALE_MS).coerceIn(NUDGE_MIN_FACTOR, NUDGE_MAX_FACTOR)

    /** Expected local position given the server-authoritative playback state. */
    fun expectedPositionMs(basePositionMs: Long, playing: Boolean, speed: Double, sinceLocalMs: Long, nowLocalMs: Long): Long {
        if (!playing) return basePositionMs
        val elapsed = (nowLocalMs - sinceLocalMs).coerceAtLeast(0)
        return basePositionMs + (elapsed * speed).toLong()
    }

    /** Guard for the scheduled start: never start noticeably too early. */
    fun canStartNow(startAtServerMs: Long, offsetMs: Long?, localWallMs: Long, toleranceMs: Long = 250L): Boolean {
        val serverNow = serverTimeNow(offsetMs, localWallMs)
        return startAtServerMs - serverNow <= toleranceMs
    }
}
