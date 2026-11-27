package com.project.puk

import com.project.puk.api.Spotify
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyArtistPageTest {
    @Test fun parsesOnlyAvailableArtistFactsAndAllOverviewReleaseGroups() {
        val page = Spotify.artistPage(JSONObject("""{
            "profile":{"name":"Artist","verified":true,"biography":{"text":"Bio"}},
            "stats":{"monthlyListeners":123,"followers":45},
            "discography":{
                "topTracks":{"items":[{"track":{"uri":"spotify:track:one","name":"One"}}]},
                "albums":{"items":[{"releases":{"items":[{"uri":"spotify:album:a","name":"Album","coverArt":{"sources":[{"url":"https://cover","width":640}]},"date":{"isoString":"2025-01-01T00:00:00Z"}}]}}]},
                "singles":{"items":[{"releases":{"items":[{"uri":"spotify:album:s","name":"Single","coverArt":{"sources":[{"url":"https://cover","width":640}]}}]}}]}
            }
        }"""))
        assertEquals("Artist", page.name)
        assertEquals("Bio", page.biography)
        assertTrue(page.verified)
        assertEquals(123L, page.monthlyListeners)
        assertEquals(listOf("album", "single"), page.releases.map { it.type })
        assertEquals("2025-01-01", page.releases.first().date)
        assertEquals("spotify:track:one", page.topTracks.single().uri)
        val missing = Spotify.artistPage(JSONObject("{}"))
        assertNull(missing.monthlyListeners)
        assertFalse(missing.verified)
        assertTrue(missing.gallery.isEmpty())
    }
    @Test fun ignoresNullBiographyFromSpotify() {
        for (text in listOf("null", "\"null\"", "\"  NULL  \"")) {
            val node = JSONObject("""{"profile":{"biography":{"text":$text}}}""")
            assertEquals("", Spotify.artistPage(node).biography)
        }
    }

    @Test fun discographyUsesRawOffsetsAndRealReleaseTypes() {
        val page = Spotify.artistReleasePage(JSONObject("""{
            "totalCount":3,"items":[
                {"releases":{"items":[{"uri":"spotify:local:missing","name":"Unavailable"}]}},
                {"releases":{"items":[{"uri":"spotify:album:a","name":"Release","type":"SINGLE","date":{"year":2026}}]}}
            ]
        }"""), 0, 2)!!
        assertEquals(2, page.next)
        assertEquals(3, page.total)
        assertEquals("single", page.items.single().type)
        assertEquals("2026", page.items.single().date)
        assertNull(Spotify.artistReleasePage(JSONObject("{}"), 0, 50))
    }

}
