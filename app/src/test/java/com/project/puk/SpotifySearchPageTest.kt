package com.project.puk

import com.project.puk.api.Spotify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifySearchPageTest {
    @Test fun advancesByRawRowsAndKeepsOrderAcrossOverlappingPages() {
        val first = Spotify.parseSearchPage(
            JSONObject("""{"data":{"searchV2":{"tracksV2":{"totalCount":24,"items":[
                {"item":{"data":{"uri":"spotify:track:first","name":"First"}}}, {},
                {"item":{"data":{"uri":"spotify:track:second","name":"Second"}}}
            ]}}}}"""), Spotify.SearchType.TRACKS, 20, 20,
        )!!
        assertEquals(23, first.next)
        val last = Spotify.parseSearchPage(
            JSONObject("""{"data":{"searchV2":{"tracksV2":{"totalCount":24,"items":[
                {"item":{"data":{"uri":"spotify:track:second","name":"Second"}}}
            ]}}}}"""), Spotify.SearchType.TRACKS, 23, 20,
        )!!
        val combined = first.append(last)
        assertEquals(listOf("First", "Second"), combined.tracks.map { it.name })
        assertNull(combined.next)
    }

    @Test fun distinguishesAnUnavailableSectionFromAnEmptyResult() {
        val response = JSONObject("""{"data":{"searchV2":{"episodes":{"totalCount":0,"items":[]}}}}""")
        assertNull(Spotify.parseSearchPage(response, Spotify.SearchType.SHOWS, 0, 20))
        val episodes = Spotify.parseSearchPage(response, Spotify.SearchType.EPISODES, 0, 20)!!
        assertEquals(emptyList<Any>(), episodes.cards)
        assertNull(episodes.next)
    }
}
