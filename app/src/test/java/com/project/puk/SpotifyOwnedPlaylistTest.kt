package com.project.puk

import com.project.puk.api.Spotify
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyOwnedPlaylistTest {
    private val uri = "spotify:playlist:0123456789abcdefghijkl"
    private fun json() = JSONObject().put("uri", uri).put("name", "Mine")
        .put("owner", JSONObject().put("id", "owner"))

    @Test fun rejectsUnknownOrDifferentOwnerAndMismatchedEntity() {
        assertNull(Spotify.ownedPlaylistDetails(json(), uri, ""))
        assertNull(Spotify.ownedPlaylistDetails(json(), uri, "someone else"))
        assertNull(Spotify.ownedPlaylistDetails(json().put("owner", JSONObject()), uri, "owner"))
        assertNull(Spotify.ownedPlaylistDetails(json(), uri + "x", "owner"))
    }

    @Test fun preservesUnknownVisibilityAndCollaborativeState() {
        val unknown = Spotify.ownedPlaylistDetails(json(), uri, "owner")!!
        assertNull(unknown.isPublic)
        val known = Spotify.ownedPlaylistDetails(json().put("public", false).put("collaborative", true), uri, "owner")!!
        assertEquals(false, known.isPublic)
        assertTrue(known.collaborative)
    }
    private fun partnerNode() = JSONObject().put("uri", uri).put("name", "Mine")
        .put("description", "Old description")
        .put("ownerV2", JSONObject().put("data", JSONObject().put("uri", "spotify:user:owner")))

    @Test fun partnerRequiresExactOwnerAndHonorsExplicitDenial() {
        assertNull(Spotify.partnerOwnedPlaylistDetails(partnerNode(), uri, "someone else"))
        assertNull(Spotify.partnerOwnedPlaylistDetails(partnerNode(), uri + "x", "owner"))
        val denied = partnerNode().put("currentUserCapabilities", JSONObject().put("canEditMetadata", false))
        assertNull(Spotify.partnerOwnedPlaylistDetails(denied, uri, "owner"))
        val owned = Spotify.partnerOwnedPlaylistDetails(partnerNode(), uri, "owner")!!
        assertTrue(owned.partner)
        assertNull(owned.isPublic)
    }

    @Test fun metadataDeltaSendsOnlyChangedFieldsAndClearsDescription() {
        val owned = Spotify.partnerOwnedPlaylistDetails(partnerNode(), uri, "owner")!!
        assertNull(Spotify.playlistMetadataOperation("Mine", "Old description", owned))
        val delta = Spotify.playlistMetadataOperation("Mine", "", owned)!!
        val attributes = delta.getJSONObject("updateListAttributes").getJSONObject("newAttributes")
        assertEquals(0, attributes.getJSONObject("values").length())
        assertEquals("LIST_DESCRIPTION", attributes.getJSONArray("noValue").getString(0))
    }
}
