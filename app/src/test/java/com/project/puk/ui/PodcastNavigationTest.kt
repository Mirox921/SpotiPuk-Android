package com.project.puk.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class PodcastNavigationTest {
    private val id = "0VjIjW4GlUZAMYd2vXMi3b"

    @Test fun spotifyUrisAndWebLinksResolveToTheSamePodcastRoutes() {
        for ((kind, target) in listOf(
            "episode" to Target.Episode("spotify:episode:$id"),
            "show" to Target.Show("spotify:show:$id"),
            "track" to Target.Track("spotify:track:$id"),
            "album" to Target.Album("spotify:album:$id"),
            "playlist" to Target.Playlist("spotify:playlist:$id"),
            "artist" to Target.Artist("spotify:artist:$id"),
        )) {
            assertEquals(target, spotifyLinkTarget("spotify:$kind:$id"))
            assertEquals(target, spotifyLinkTarget("https://open.spotify.com/intl-ru/$kind/$id?si=shared#fragment"))
            assertEquals(target, spotifyLinkTarget("https://WWW.SPOTIFY.COM/$kind/$id"))
        }
    }

    @Test fun malformedAndForeignLinksCannotBecomeSpotifyRoutes() {
        assertNull(spotifyLinkTarget("https://example.com/spotify.com/episode/$id"))
        assertNull(spotifyLinkTarget("https://open.spotify.com.evil.test/episode/$id"))
        assertNull(spotifyLinkTarget("spotify:episode:invalid"))
        assertNull(spotifyLinkTarget("spotify:episode:$id:extra"))
        assertNull(spotifyLinkTarget("https://open.spotify.com/episode/$id/extra"))
    }

    @Test fun descriptionPreservesTextAndAnnotatesTheOriginalUrls() {
        val text = "Read https://example.com/episode then https://example.org/about."
        val annotated = annotatedEpisodeDescription(text, Color.Red)
        assertEquals(text, annotated.text)
        val links = annotated.getStringAnnotations(0, text.length)
        assertEquals(2, links.size)
        links.forEach { link -> assertEquals(link.item, text.substring(link.start, link.end)) }
        assertTrue(annotated.getStringAnnotations(0, 4).isEmpty())
    }
}
