package com.project.puk.ui

import com.project.puk.api.TrackRow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistCountLabelTest {
    private val episode = TrackRow("spotify:episode:one", "one", "Episode", "Show", "", "", "", 1000)
    private val track = episode.copy(uri = "spotify:track:one")

    @Test fun countsDisplayedEpisodesWithRussianPluralForms() {
        for ((count, noun) in listOf(1 to "эпизод", 2 to "эпизода", 5 to "эпизодов", 11 to "эпизодов", 21 to "эпизод")) {
            assertEquals("$count $noun", playlistCountLabel(List(count) { episode }, false))
        }
    }

    @Test fun distinguishesMusicMixedListsAndIncompletePages() {
        assertEquals("1 трек", playlistCountLabel(listOf(track), false))
        assertEquals("2 элемента", playlistCountLabel(listOf(track, episode), false))
        assertEquals("Загружено: 1 эпизод", playlistCountLabel(listOf(episode), true))
        assertEquals("0 треков", playlistCountLabel(emptyList(), false))
    }

    @Test fun showsFullKnownCountWhilePagesAreStillLoading() {
        val firstPage = List(50) { track }
        assertEquals("454 трека", playlistCountLabel(firstPage, true, 454))
        assertEquals("454 трека", playlistCountLabel(List(100) { track }, true, 454))
        assertEquals("Загружено: 50 треков", playlistCountLabel(firstPage, true))
        assertEquals("Загружено: 50 треков", playlistCountLabel(firstPage, true, 20))
        // Completed episode lists retain their parsed count (raw totals can contain unsupported entries).
        assertEquals("1 эпизод", playlistCountLabel(listOf(episode), false, 2))
    }

    @Test fun labelsProfilePlaylistCountsWithRussianAgreement() {
        assertEquals("Нет открытых плейлистов", publicPlaylistCountLabel(0))
        assertEquals("1 открытый плейлист", publicPlaylistCountLabel(1))
        assertEquals("2 открытых плейлиста", publicPlaylistCountLabel(2))
        assertEquals("5 открытых плейлистов", publicPlaylistCountLabel(5))
        assertEquals("11 открытых плейлистов", publicPlaylistCountLabel(11))
        assertEquals("21 открытый плейлист", publicPlaylistCountLabel(21))
        assertEquals("22 открытых плейлиста", publicPlaylistCountLabel(22))
    }
}
