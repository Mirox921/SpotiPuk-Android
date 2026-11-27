package com.project.puk

import com.project.puk.api.Spotify
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaylistTest {
    @Test fun emptyPlaylistStaysEmptyAndHasNoContinuation() {
        val page = Spotify.playlistPage(JSONObject("""{
            "name":"Empty", "content":{"items":[],"totalCount":0}
        }"""), 50, 0)!!
        assertEquals("Empty", page.name)
        assertTrue(page.tracks.isEmpty())
        assertEquals(0, page.total)
        assertNull(page.next)
    }

    @Test fun mixedPlaylistPreservesOrderDuplicatesAndRawPageOffsets() {
        val page = Spotify.playlistPage(JSONObject("""{
            "name":"Episodes", "content":{"totalCount":5,"items":[
                {"itemV2":{"data":{"uri":"spotify:episode:one","name":"Episode",
                    "duration":{"totalMilliseconds":123456},
                    "podcastV2":{"data":{"uri":"spotify:show:show","name":"Show"}},
                    "coverArt":{"sources":[{"url":"https://cover","width":640}]}}}},
                {"item":{"v2":{"data":{"uri":"spotify:track:two","name":"Music"}}}},
                {"itemV2":{"data":{"uri":"spotify:local:unavailable"}}},
                {"item":{"itemV2":{"data":{"uri":"spotify:episode:one","name":"Episode"}}}}
            ]}
        }"""), 4, 0)!!
        assertEquals(listOf("spotify:episode:one", "spotify:track:two", "spotify:episode:one"), page.tracks.map { it.uri })
        assertEquals("Show", page.tracks.first().artists)
        assertEquals(123456L, page.tracks.first().durationMs)
        assertEquals("https://cover", page.imageUrl)
        assertEquals(4, page.next)
        assertEquals(5, page.total)
    }

    @Test fun parsedDuplicateRowsRetainDistinctMutationIdentityAfterSkippedEntry() {
        val page = Spotify.playlistPage(JSONObject("""{
            "content":{"totalCount":53,"items":[
                {"uid":"first","itemV2":{"data":{"uri":"spotify:track:same","name":"Song"}}},
                {"uid":"unavailable","itemV2":{"data":{"uri":"spotify:local:missing"}}},
                {"uid":"second","itemV2":{"data":{"uri":"spotify:track:same","name":"Song"}}}
            ]}
        }"""), 50, 50)!!
        assertEquals(listOf("first", "second"), page.tracks.map { it.playlistUid })
        assertEquals(listOf(50, 52), page.tracks.map { it.playlistIndex })
    }

    @Test fun transcriptOnlyEnablesSeekForValidSyncedSentences() {
        fun transcript(status: String, last: Long) = Spotify.parseTranscript(JSONObject("""{
            "timeSyncedStatus":"$status",
            "section":[{"title":{"title":"Speaker"}},
                {"startMs":500,"text":{"sentence":{"text":"One"}}},
                {"startMs":$last,"fallback":{"sentence":{"text":"Two"}}}]
        }"""))!!
        assertTrue(transcript("SENTENCE_SYNCED", 1200).synced)
        assertEquals(listOf("One", "Two"), transcript("SENTENCE_SYNCED", 1200).lines.map { it.text })
        assertFalse(transcript("STATIC", 1200).synced)
        assertFalse(transcript("SENTENCE_SYNCED", 400).synced)
    }

    @Test fun artworkSelectsLargestAndSupportsNullWidthWithoutAcceptingInvalidUrls() {
        assertEquals("https://large", Spotify.coverOf(JSONObject("""{
            "coverArt":{"sources":[{"url":"https://small","width":64},{"url":"https://large","width":640}]}
        }""")))
        assertEquals("https://original", Spotify.coverOf(JSONObject("""{
            "images":{"items":[{"sources":[
                {"url":"https://small","width":64}, {"url":"https://original","width":null},
                {"url":"http://invalid","width":9999}
            ]}]}
        }""")))
    }
}
