package com.project.puk.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class PlaybackSessionSnapshotTest {
    private val first = PukPlayer.QueueItem("spotify:track:first", "First", "Artist", "", "", 120_000)
    private val second = first.copy(uri = "spotify:track:second", title = "Second")
    private val snapshot = PlaybackSessionSnapshot(
        "account", listOf(second, first, first), 1, 17_000, 120_000,
        PukPlayer.Shuffle.ON, PukPlayer.Repeat.QUEUE, listOf(first, first, second),
        emptySet(), wasPlaying = true, queueContext = "spotify:playlist:test",
    )

    @Test fun preservesQueueOrderPositionAndModes() {
        assertEquals(snapshot, PlaybackSessionSnapshot.decode(snapshot.encode(), "account"))
    }

    @Test fun restoresEpisodesInMixedQueuesAndOriginalShuffleOrder() {
        val episode = first.copy(uri = "spotify:episode:episode", durationMs = 7_000_000)
        val mixed = snapshot.copy(
            queue = listOf(episode, first, episode), index = 0,
            positionMs = 100_380, durationMs = episode.durationMs,
            originalQueue = listOf(first, episode, episode),
        )
        assertEquals(mixed, PlaybackSessionSnapshot.decode(mixed.encode(), "account"))
    }

    @Test fun rejectsNonPlayableUrisAndMissingEpisodeIds() {
        for (uri in listOf("spotify:show:show", "spotify:episode:", "https://example.com")) {
            val invalid = snapshot.copy(queue = listOf(first.copy(uri = uri)), index = 0)
            assertNull(PlaybackSessionSnapshot.decode(invalid.encode(), "account"))
        }
    }

    @Test fun rejectsCrossAccountCorruptAndUnknownVersionSnapshots() {
        val encoded = snapshot.encode()
        assertNull(PlaybackSessionSnapshot.decode(encoded, "other"))
        assertNull(PlaybackSessionSnapshot.decode("{", "account"))
        assertNull(PlaybackSessionSnapshot.decode(encoded.replace("\"version\":1", "\"version\":2"), "account"))
    }

    @Test fun clampsPositionAndRejectsMissingCurrentItem() {
        assertEquals(120_000L, PlaybackSessionSnapshot.decode(snapshot.copy(positionMs = Long.MAX_VALUE).encode(), "account")?.positionMs)
        assertNull(PlaybackSessionSnapshot.decode(snapshot.copy(index = 5).encode(), "account"))
    }
}
