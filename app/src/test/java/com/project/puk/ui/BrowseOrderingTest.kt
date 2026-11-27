package com.project.puk.ui

import com.project.puk.api.*
import org.junit.Assert.*
import org.junit.Test

class BrowseOrderingTest {
    private fun track(name: String, added: String) = TrackRow("spotify:track:$name", name, name, "Artist", "Album", "", "", 100, addedAt = added)
    @Test fun sortsPreserveDuplicateOccurrencesAndOriginalOrder() {
        val a = track("A", "2026-01-01")
        val b = track("B", "2026-02-01")
        val rows = listOf(b, a, b)
        assertSame(rows, sortedPlaylist(rows, PlaylistSort.CUSTOM))
        assertEquals(listOf(a, b, b), sortedPlaylist(rows, PlaylistSort.TITLE))
        assertEquals(listOf(b, b, a), sortedPlaylist(rows, PlaylistSort.ADDED))
        assertEquals(listOf(b, a, b), rows)
    }
    @Test fun releaseFiltersUseTypesAndDeduplicatePagination() {
        fun release(id: String, type: String, date: String, popular: Boolean = false) = ArtistRelease(Card("spotify:album:$id", id, "", "", "album"), type, date, popular)
        val album = release("A", "album", "2025", true)
        val single = release("S", "single", "2026-01-01")
        val ep = release("E", "ep", "2024")
        assertEquals(listOf(album), filteredReleases(listOf(single, album, album), "album", false))
        assertEquals(listOf(single, ep), filteredReleases(listOf(album, ep, single), "single", false))
        assertEquals(listOf(album), filteredReleases(listOf(album, ep, single), "popular", false))
    }
}
