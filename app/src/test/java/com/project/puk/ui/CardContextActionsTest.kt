package com.project.puk.ui

import com.project.puk.api.Card
import com.project.puk.api.Spotify
import com.project.puk.api.TrackRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardContextActionsTest {
    private val id = "0123456789abcdefghijkl"

    @Test
    fun sharesOnlySupportedSpotifyEntities() {
        for (kind in listOf("album", "playlist", "artist", "show", "audiobook")) {
            assertEquals("https://open.spotify.com/$kind/$id", card(kind, "spotify:$kind:$id").contextShareUrl())
        }
        assertNull(card("track", "spotify:track:$id").contextShareUrl())
        assertNull(card("collection", "spotify:collection:tracks").contextShareUrl())
        assertEquals("https://open.spotify.com/show/$id", card("podcast", "spotify:show:$id").contextShareUrl())
    }

    @Test
    fun rejectsInvalidOrMismatchedUris() {
        assertNull(card("album", "spotify:playlist:$id").contextShareUrl())
        assertNull(card("playlist", "spotify:playlist:fake-id").contextShareUrl())
        assertNull(card("artist", "spotify:artist:${id.dropLast(1)}/").contextShareUrl())
    }

    @Test
    fun libraryActionsReflectEntityAndSavedState() {
        assertEquals("Сохранить в медиатеку", cardLibraryLabel("album", false))
        assertEquals("Удалить из медиатеки", cardLibraryLabel("playlist", true))
        assertEquals("Подписаться", cardLibraryLabel("artist", false))
        assertEquals("Отписаться", cardLibraryLabel("artist", true))
        assertEquals("Сохранить в медиатеку", cardLibraryLabel("show", false))
        assertEquals("Удалить из медиатеки", cardLibraryLabel("audiobook", true))
        assertNull(cardLibraryLabel("collection", true))
    }

    @Test
    fun loadsAllCollectionPagesInOrder() = runBlocking {
        val first = TrackRow("first", "1", "First", "", "", "", "", 0)
        val second = first.copy(uri = "second", id = "2", name = "Second")
        val pages = mapOf(0 to Spotify.Page(listOf(first), 50), 50 to Spotify.Page(listOf(second, first), null))
        assertEquals(listOf(first, second, first), loadContextTracks(pages::get))
    }

    @Test
    fun rejectsPartialAndNonAdvancingCollections() = runBlocking {
        assertNull(loadContextTracks { offset ->
            if (offset == 0) Spotify.Page(emptyList(), 50) else null
        })
        assertNull(loadContextTracks { Spotify.Page(emptyList(), it) })
    }

    private fun card(kind: String, uri: String) = Card(uri, "Name", "", "", kind)
}
