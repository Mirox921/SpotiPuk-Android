package com.project.puk.player

/** One-shot timer; scheduling is injected so expiry and cancellation can use virtual time. */
internal class SleepTimer(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onChanged: (Int?) -> Unit,
    private val onExpired: () -> Unit,
) {
    private var pending: Runnable? = null

    fun set(minutes: Int?) {
        pending?.let(cancel)
        pending = null
        val duration = minutes?.takeIf { it > 0 }
        onChanged(duration)
        if (duration == null) return
        val task = object : Runnable {
            override fun run() {
                if (pending !== this) return
                pending = null
                onChanged(null)
                onExpired()
            }
        }
        pending = task
        schedule(task, duration * 60_000L)
    }
}
