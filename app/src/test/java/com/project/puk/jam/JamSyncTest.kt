package com.project.puk.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Threshold matrix per owner correction #4 — must stay configurable and sane. */
class JamSyncTest {

    @Test
    fun `rtt and offset derive from one exchange`() {
        // Sent at elapsed 1000, received at 1200 (rtt 200), server says 50_000.
        // Local wall at receive: 40_000 → midpoint 40_100 → offset 9_900.
        val s = JamSync.sample(sentElapsed = 1_000, receivedElapsed = 1_200, serverTimeMs = 50_000, localWallMs = 40_000)
        assertEquals(200L, s.rttMs)
        assertEquals(9_900L, s.offsetMs)
    }

    @Test
    fun `median smooths a single laggy sample`() {
        val good = JamSync.Sample(100, 1_000)
        val laggy = JamSync.Sample(4_000, 50_000)
        val median = JamSync.offsetOf(listOf(good, good, laggy, good, good))!!
        assertEquals(100L, median.rttMs)
        assertEquals(1_000L, median.offsetMs)
    }

    @Test
    fun `slow exchanges are rejected relative to the best rtt`() {
        // One power-save bubble (rtt 800) would poison the median outright;
        // robust estimator keeps only samples near the best RTT (spec 2).
        val best = JamSync.Sample(50, 1_000)
        val slow = JamSync.Sample(800, 60_000)
        val estimate = JamSync.offsetOf(listOf(best, best, slow, best))!!
        assertEquals(50L, estimate.rttMs)
        assertEquals(1_000L, estimate.offsetMs)
    }

    @Test
    fun `window of only slow samples still yields their median`() {
        val slowA = JamSync.Sample(700, 10_000)
        val slowB = JamSync.Sample(900, 12_000)
        val slowC = JamSync.Sample(1_100, 11_000)
        val estimate = JamSync.offsetOf(listOf(slowA, slowB, slowC))!!
        assertEquals(700L, estimate.rttMs)
        assertEquals(11_000L, estimate.offsetMs)
    }

    @Test
    fun `unusable rtt is filtered out`() {
        assertNull(JamSync.offsetOf(listOf(JamSync.Sample(60_000, 0))))
        assertNull(JamSync.offsetOf(emptyList()))
    }

    @Test
    fun `small drift is held without nudging`() {
        assertEquals(JamSync.DriftPlan.HOLD, JamSync.driftPlan(60, 0, nudging = false))
        assertEquals(JamSync.DriftPlan.HOLD, JamSync.driftPlan(-79, 99L, nudging = false))
    }

    @Test
    fun `nudge engages only after confirmations with the right direction`() {
        // signed > 0: движок впереди — замедлить; < 0 — ускорить.
        assertEquals(JamSync.DriftPlan.HOLD, JamSync.driftPlan(200, JamSync.NUDGE_CONFIRMATIONS - 1L, nudging = false))
        assertEquals(JamSync.DriftPlan.NUDGE_DOWN, JamSync.driftPlan(200, JamSync.NUDGE_CONFIRMATIONS.toLong(), nudging = false))
        assertEquals(JamSync.DriftPlan.NUDGE_UP, JamSync.driftPlan(-200, JamSync.NUDGE_CONFIRMATIONS.toLong(), nudging = false))
    }

    @Test
    fun `nudge continues while engaged and releases with hysteresis`() {
        // Уже идёт коррекция: продолжаем вплоть до порога отпуска.
        assertEquals(JamSync.DriftPlan.NUDGE_DOWN, JamSync.driftPlan(90, 0, nudging = true))
        assertEquals(JamSync.DriftPlan.NUDGE_DOWN, JamSync.driftPlan(45, 0, nudging = true))
        assertEquals(JamSync.DriftPlan.RESET_SPEED, JamSync.driftPlan(JamSync.NUDGE_RELEASE_MS - 1, 0, nudging = true))
        // Порог входа 80 мс, порог отпуска 40 мс — гистерезис без дребезга.
        assertEquals(JamSync.DriftPlan.HOLD, JamSync.driftPlan(45, 0, nudging = false))
    }

    @Test
    fun `moderate drift seeks only after confirmations`() {
        assertEquals(JamSync.DriftPlan.HOLD, JamSync.driftPlan(JamSync.DRIFT_IGNORE_MS + 50, JamSync.DRIFT_CONFIRMATIONS - 1L, nudging = false))
        assertEquals(JamSync.DriftPlan.SEEK, JamSync.driftPlan(JamSync.DRIFT_IGNORE_MS + 50, JamSync.DRIFT_CONFIRMATIONS.toLong(), nudging = false))
    }

    @Test
    fun `large drift seeks immediately`() {
        assertEquals(JamSync.DriftPlan.SEEK, JamSync.driftPlan(JamSync.DRIFT_SEEK_IMMEDIATE_MS, 0L, nudging = false))
        assertEquals(JamSync.DriftPlan.SEEK, JamSync.driftPlan(60_000L, 0L, nudging = true))
    }

    @Test
    fun `nudge factor scales within the 05-2 percent band`() {
        assertEquals(0.0125, JamSync.nudgeFactor(100), 1e-9)
        assertEquals(JamSync.NUDGE_MIN_FACTOR, JamSync.nudgeFactor(10), 1e-9)
        assertEquals(JamSync.NUDGE_MAX_FACTOR, JamSync.nudgeFactor(1_000), 1e-9)
    }

    @Test
    fun `start delay maps server time through offset`() {
        // Server says start at 100_000; local wall is 98_000, offset +3_000
        // → server now = 101_000 → lead = -1_000 → clamped to MIN_START_LEAD.
        val lead = JamSync.startDelayMs(100_000, 3_000, 98_000)
        assertEquals(JamSync.MIN_START_LEAD_MS, lead)
    }

    @Test
    fun `start delay respects the cap`() {
        val lead = JamSync.startDelayMs(System.currentTimeMillis() + 60_000, 0, System.currentTimeMillis())
        assertEquals(JamSync.MAX_START_LEAD_MS, lead)
    }

    @Test
    fun `canStartNow honors tolerance`() {
        val now = System.currentTimeMillis()
        assertTrue(JamSync.canStartNow(now + 200, 0, now))
        assertTrue(!JamSync.canStartNow(now + 60_000, 0, now))
    }

    @Test
    fun `expected position advances while playing only`() {
        val base = 10_000L
        assertEquals(base, JamSync.expectedPositionMs(base, playing = false, speed = 1.0, sinceLocalMs = 1, nowLocalMs = 9_999))
        assertEquals(base + 2_000, JamSync.expectedPositionMs(base, playing = true, speed = 1.0, sinceLocalMs = 1, nowLocalMs = 2_001))
    }

    @Test
    fun `start desync of about 400 ms self-corrects after confirmations`() {
        // Владелец: старт в Jam расходился на ~0,4 с и не выравнивался
        // (порог был 800 мс, умеренные гэпы не имели подтверждений).
        assertEquals(JamSync.DriftPlan.HOLD, JamSync.driftPlan(400, 0, nudging = false))
        assertEquals(
            JamSync.DriftPlan.SEEK,
            JamSync.driftPlan(400, JamSync.DRIFT_CONFIRMATIONS.toLong(), nudging = false),
        )
    }
}
