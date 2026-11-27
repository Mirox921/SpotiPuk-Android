package com.project.puk.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ListeningTrackerTest {
    private val item = PukPlayer.QueueItem("track", "Track", "Artist", "album", "", 180_000)

    @Test
    fun countsOnlyAdvancingPlaybackAndDoesNotCountRestoredPosition() {
        val saved = mutableListOf<ListeningSlice>()
        val tracker = ListeningTracker(saved::add)
        fun sample(clock: Long, position: Long, playing: Boolean = true) =
            tracker.sample("account", 1, item, "playlist", position, clock, 100_000 + clock, playing)

        sample(0, 60_000, false)
        sample(500, 60_000)
        sample(1_000, 60_500)
        sample(1_500, 61_000, false)
        sample(1_800, 120_000)
        sample(2_300, 120_000)
        sample(2_800, 170_000)
        sample(3_300, 170_200, false)

        assertEquals(listOf(1_000L, 200L), saved.map { it.listenedMs })
        assertEquals(listOf("playlist", "playlist"), saved.map { it.contextUri })
        assertNotEquals(saved[0].session, saved[1].session)
    }

    @Test
    fun periodicFlushDoesNotRepeatTimeAtPause() {
        val saved = mutableListOf<ListeningSlice>()
        val tracker = ListeningTracker(saved::add)
        for (ms in 0L..5_000L step 500L) {
            tracker.sample("account", 4, item, null, ms, ms, 500_000 + ms, true)
        }
        tracker.sample("account", 4, item, null, 5_000, 5_250, 505_250, false)
        tracker.end()
        assertEquals(5_000L, saved.single().listenedMs)
    }
}
