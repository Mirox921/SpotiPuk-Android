package com.project.puk.ui

import com.project.puk.api.EpisodePage
import com.project.puk.api.EpisodeRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PodcastContextActionsTest {
    private fun episode(date: String, position: Long = 0, playable: Boolean = true, played: Boolean = false) =
        EpisodeRow("spotify:episode:0123456789abcdefghijkl", date, "", date, 10000, "", played, position, playable)

    @Test fun scansAllPagesWithoutAssumingDateOrderAndSkipsUnavailable() = runBlocking {
        val old = episode("2020-01-01")
        val latest = episode("2025-01-01")
        val pages = mapOf(0 to EpisodePage(listOf(old), 3, 50),
            50 to EpisodePage(listOf(episode("2026-01-01", playable = false), latest), 3, null))
        assertEquals(latest, loadShowPlaybackEpisode(false, pages::get))
    }

    @Test fun resumesOnlyAnUnfinishedEpisode() = runBlocking {
        val unfinished = episode("2024-01-01", position = 2000)
        val rows = listOf(episode("2026-01-01", position = 9000, played = true),
            episode("2025-01-01"), unfinished)
        assertEquals(unfinished, loadShowPlaybackEpisode(true) { EpisodePage(rows, 3, null) })
        assertNull(loadShowPlaybackEpisode(true) { EpisodePage(emptyList(), 0, null) })
    }

    @Test fun rejectsPartialResponsesAndStalledPagination() = runBlocking {
        assertNull(loadShowPlaybackEpisode(false) { if (it == 0) EpisodePage(listOf(episode("2024")), 2, 50) else null })
        assertNull(loadShowPlaybackEpisode(false) { EpisodePage(listOf(episode("2024")), 2, it) })
    }

    @Test fun preservesCancellation() = runBlocking {
        try {
            loadShowPlaybackEpisode(false) { throw CancellationException("dismissed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun momentLinksValidateIdsAndUseWholeNonNegativeSeconds() {
        val uri = "spotify:episode:0123456789abcdefghijkl"
        assertEquals("https://open.spotify.com/episode/0123456789abcdefghijkl?t=123", episodeMomentUrl(uri, 123456))
        assertTrue(episodeMomentUrl(uri, -100)!!.endsWith("?t=0"))
        assertNull(episodeMomentUrl("spotify:track:0123456789abcdefghijkl", 1))
        assertNull(episodeMomentUrl("spotify:episode:invalid", 1))
    }
}
