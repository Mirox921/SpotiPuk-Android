package com.project.puk.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media id scheme of the Auto browse tree ([AutoMediaIds]) — pure JVM. */
class AutoMediaIdsTest {

    @Test
    fun `root and fixed containers parse`() {
        assertTrue(AutoMediaIds.parse(AutoMediaIds.ROOT) is AutoMediaIds.Target.Root)
        assertTrue(AutoMediaIds.parse(AutoMediaIds.LIKED) is AutoMediaIds.Target.Liked)
        assertTrue(AutoMediaIds.parse(AutoMediaIds.LIBRARY) is AutoMediaIds.Target.Library)
    }

    @Test
    fun `card roundtrip keeps spotify uri with colons`() {
        val uri = "spotify:playlist:37i9dQZF1E4vT3"
        val id = AutoMediaIds.card(uri)
        val target = AutoMediaIds.parse(id)
        assertEquals(AutoMediaIds.Target.CardContainer(uri), target)
    }

    @Test
    fun `track roundtrip inside card container`() {
        val container = "card:spotify:playlist:37i9dQZF1E4vT3"
        val uri = "spotify:track:4uLU6hCj"
        val id = AutoMediaIds.track(container, 12, uri)
        assertEquals(AutoMediaIds.Target.Track(container, 12, uri), AutoMediaIds.parse(id))
    }

    @Test
    fun `liked container track roundtrip`() {
        val id = AutoMediaIds.track(AutoMediaIds.LIKED_CONTAINER_ID, 0, "spotify:track:abc")
        assertEquals(
            AutoMediaIds.Target.Track(AutoMediaIds.LIKED_CONTAINER_ID, 0, "spotify:track:abc"),
            AutoMediaIds.parse(id),
        )
    }

    @Test
    fun `malformed track ids are rejected`() {
        assertNull(AutoMediaIds.parse("auto:t||1|spotify:track:x"))
        assertNull(AutoMediaIds.parse("auto:t|liked|x|spotify:track:x"))
        assertNull(AutoMediaIds.parse("auto:t|liked|-2|spotify:track:x"))
        assertNull(AutoMediaIds.parse("auto:t|liked"))
    }

    @Test
    fun `search hit roundtrip and rejection`() {
        assertEquals(
            AutoMediaIds.Target.SearchHit("spotify:track:xyz"),
            AutoMediaIds.parse(AutoMediaIds.search("spotify:track:xyz")),
        )
        assertNull(AutoMediaIds.parse("auto:s|"))
    }

    @Test
    fun `foreign ids are rejected`() {
        assertNull(AutoMediaIds.parse("spotify:track:abc"))
        assertNull(AutoMediaIds.parse(""))
        assertNull(AutoMediaIds.parse("auto:unknown|1"))
    }

    @Test
    fun `queue context only for card containers`() {
        assertEquals(
            "spotify:playlist:123",
            AutoMediaIds.containerQueueContext("card:spotify:playlist:123"),
        )
        assertNull(AutoMediaIds.containerQueueContext(AutoMediaIds.LIKED_CONTAINER_ID))
        assertNull(AutoMediaIds.containerQueueContext(AutoMediaIds.RECENT_CONTAINER_ID))
    }

    @Test
    fun `recent and artist containers parse`() {
        assertTrue(AutoMediaIds.parse(AutoMediaIds.RECENT) is AutoMediaIds.Target.Recent)
        val artistUri = "spotify:artist:0TnOYIS61ZL"
        assertEquals(
            AutoMediaIds.Target.ArtistContainer(artistUri),
            AutoMediaIds.parse(AutoMediaIds.artist(artistUri)),
        )
        assertNull(AutoMediaIds.parse("auto:artist|"))
    }

    @Test
    fun `recent container track roundtrip`() {
        val id = AutoMediaIds.track(AutoMediaIds.RECENT_CONTAINER_ID, 3, "spotify:track:xyz")
        assertEquals(
            AutoMediaIds.Target.Track(AutoMediaIds.RECENT_CONTAINER_ID, 3, "spotify:track:xyz"),
            AutoMediaIds.parse(id),
        )
    }
}
