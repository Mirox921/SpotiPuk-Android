package com.project.puk.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Priors for the local audio output latency (spec section 4). */
class AudioLatencyTest {

    @Test
    fun `bluetooth carries the codec and link buffer prior`() {
        assertEquals(200L, AudioLatency.estimateMs(256, 48_000, AudioLatency.Route.BLUETOOTH))
    }

    @Test
    fun `speaker prior exceeds wired at the same device properties`() {
        val speaker = AudioLatency.estimateMs(256, 48_000, AudioLatency.Route.SPEAKER)
        val wired = AudioLatency.estimateMs(256, 48_000, AudioLatency.Route.WIRED)
        assertTrue(speaker > wired)
        // 3 буфера HAL по 5 мс: 45+15 и 20+15.
        assertEquals(60L, speaker)
        assertEquals(35L, wired)
    }

    @Test
    fun `degenerate device properties never divide by zero`() {
        assertEquals(200L, AudioLatency.estimateMs(0, 0, AudioLatency.Route.BLUETOOTH))
        assertTrue(AudioLatency.estimateMs(0, 0, AudioLatency.Route.SPEAKER) > 0)
    }
}
