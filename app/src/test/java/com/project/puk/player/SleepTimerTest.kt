package com.project.puk.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    private var now = 0L
    private val tasks = linkedMapOf<Runnable, Long>()
    private var minutes: Int? = null
    private var pauses = 0
    private val timer = SleepTimer(
        schedule = { task, delay -> tasks[task] = now + delay },
        cancel = { tasks.remove(it) },
        onChanged = { minutes = it },
        onExpired = { pauses++ },
    )

    private fun advance(ms: Long) {
        now += ms
        tasks.filterValues { it <= now }.keys.toList().forEach {
            tasks.remove(it)
            it.run()
        }
    }

    @Test fun expiresOnceAtSelectedDeadline() {
        timer.set(15)
        advance(899_999)
        assertEquals(0, pauses)
        assertEquals(15, minutes)
        advance(1)
        assertEquals(1, pauses)
        assertEquals(null, minutes)
        advance(900_000)
        assertEquals(1, pauses)
    }

    @Test fun replacingAndCancellingFenceAlreadyQueuedCallbacks() {
        timer.set(15)
        val stale = tasks.keys.single()
        advance(60_000)
        timer.set(30)
        stale.run()
        assertEquals(30, minutes)
        advance(900_000)
        assertEquals(0, pauses)
        timer.set(null)
        advance(1_800_000)
        assertEquals(0, pauses)
        assertEquals(null, minutes)
    }
}
