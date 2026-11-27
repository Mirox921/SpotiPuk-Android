package com.project.puk

import com.project.puk.api.Card
import com.project.puk.api.Section
import com.project.puk.catalog.CatalogCategory
import com.project.puk.catalog.CatalogPage
import com.project.puk.catalog.CatalogProvider
import com.project.puk.catalog.CatalogRepository
import com.project.puk.catalog.HomeCatalogProvider
import com.project.puk.ui.Target
import com.project.puk.ui.seekFraction
import com.project.puk.ui.target
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.project.puk.ui.theme.EmberColors
import com.project.puk.ui.theme.ambientArgb
import com.project.puk.ui.theme.representativeArtworkArgb
import com.project.puk.ui.theme.secondaryArtworkArgb
import org.junit.Test

class ModernizationTest {
    @Test
    fun selectsPopulatedSourceInPriorityOrder() = runBlocking {
        val category = CatalogCategory("rock", "Рок", 0)
        val album = Card("spotify:album:6dVIqQ8qmQ5GBnJ9shOYGE", "OK Computer", "Radiohead", "", "album")
        val edition = CatalogPage(listOf(Section("Альбомы", listOf(album))))
        fun provider(page: CatalogPage?) = object : CatalogProvider {
            override fun categories() = listOf(category)
            override suspend fun page(id: String) = page.takeIf { id == category.id }
        }
        val repository = CatalogRepository(provider(CatalogPage(emptyList())), provider(edition))
        assertEquals(listOf(category), repository.categories())
        assertEquals(edition, repository.page("rock"))
        assertNull(repository.page("unknown"))
        assertEquals(Target.Album(album.uri), album.target())
    }

    @Test
    fun personalCatalogPreservesRealHomeSectionsAndSkipsUnsupportedEntries() = runBlocking {
        val album = Card("spotify:album:6dVIqQ8qmQ5GBnJ9shOYGE", "OK Computer", "Radiohead", "cover", "album")
        val heading = Section("Для тебя", listOf(album))
        val unsupported = Card("spotify:section:other", "Other", "", "", "section")
        val provider = HomeCatalogProvider(listOf(
            heading.copy(items = listOf(unsupported, album, album)),
            Section("Unsupported", listOf(unsupported)),
        ))

        assertEquals("cover", provider.categories().single().imageUrl)
        assertEquals(CatalogPage(listOf(heading), fromHome = true), provider.page("for-you"))
        assertNull(provider.page("rock"))
        val unavailable = HomeCatalogProvider(emptyList())
        assertEquals(emptyList<CatalogCategory>(), unavailable.categories())
        assertNull(unavailable.page("for-you"))
    }

    @Test
    fun thematicCatalogUsesExplicitMatchesAndFallsBackWhenAbsent() = runBlocking {
        val rock = Card("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "Classic Rock", "", "", "playlist")
        val unrelated = Card("spotify:playlist:37i9dQZF1DX4JAvHpjipBk", "Rocket Music", "", "", "playlist")
        val album = Card("spotify:album:6dVIqQ8qmQ5GBnJ9shOYGE", "OK Computer", "Radiohead", "", "album")
        val rockSection = Section("Рок для вас", listOf(album))
        val provider = HomeCatalogProvider(listOf(
            rockSection,
            Section("Mixes", listOf(unrelated, rock, rock)),
        ))
        assertEquals(
            CatalogPage(listOf(rockSection, Section("Mixes", listOf(rock))), fromHome = true),
            provider.page("rock"),
        )
        assertNull(provider.page("jazz"))
    }

    @Test
    fun emberArtworkUsesVividToneAndKeepsNeutralFallback() {
        val colorfulCovers = listOf(0xFFE35C21, 0xFF305FC8, 0xFFF8E700)
        for (cover in colorfulCovers) {
            val result = Hct.fromInt(ambientArgb(cover.toInt()))
            // Ambients must stay visibly colored: vivid chroma, readable tone.
            assertTrue(result.tone in 38.0..46.0)
            assertTrue(result.chroma >= 28.0)
        }
        assertEquals(EmberColors.SurfaceContainerHigh.toArgb(), ambientArgb(0xFF070707.toInt()))
        assertEquals(EmberColors.SurfaceContainerHigh.toArgb(), ambientArgb(0xFF808080.toInt()))
    }

    @Test
    fun artworkSamplePrefersBroadColorOverTinyRedDetail() {
        val blue = 0xff25b8eb.toInt()
        val red = 0xffef111a.toInt()
        val black = 0xff101018.toInt()
        assertEquals(blue, representativeArtworkArgb(listOf(black to 520, blue to 454, red to 26)))
        assertEquals(red, representativeArtworkArgb(listOf(red to 620, black to 380)))
    }

    @Test
    fun secondaryArtworkIgnoresNearbyShadesAndSmallAccents() {
        val blue = 0xff25b8eb.toInt()
        val nearBlue = 0xff17a4dc.toInt()
        val warm = 0xffe59026.toInt()
        val red = 0xffef111a.toInt()
        assertEquals(warm, secondaryArtworkArgb(blue, listOf(blue to 55, nearBlue to 23, warm to 19, red to 3)))
        assertNull(secondaryArtworkArgb(blue, listOf(blue to 55, nearBlue to 42, red to 3)))
    }

    @Test
    fun seekFractionClampsToPlayableRange() {
        assertEquals(0.5f, seekFraction(30_000f, 60_000), 0.001f)
        assertEquals(1f, seekFraction(80_000f, 60_000), 0f)
        assertEquals(0f, seekFraction(-1f, 60_000), 0f)
        assertEquals(0f, seekFraction(30_000f, 0), 0f)
    }
    @Test
    fun videoDashMpdUsesSecondTimescaleAndUsLocale() {
        val manifest = com.project.puk.player.VideoSource.VideoManifestData(
            videoId = "abcdefabcdefabcdefabcdef",
            baseUrls = listOf("https://video-fa.scdn.co/segments/"),
            initializationTemplate = "v1/x/{{profile_id}}/inits/{{file_type}}",
            segmentTemplate = "v1/x/{{profile_id}}/{{segment_timestamp}}.{{file_type}}",
            segmentLengthSeconds = 4,
            startMs = 0,
            endMs = 13288222,
            videoProfiles = listOf(
                com.project.puk.player.VideoSource.VideoProfile("0", "video/mp4", "avc1.4d402a", 6890022L, 1920, 1080, "mp4"),
            ),
            audioProfiles = listOf(
                com.project.puk.player.VideoSource.VideoProfile("13", "audio/mp4", "mp4a.40.2", 100802L, 0, 0, "mp4"),
            ),
            licenseServer = null,
            pssh = null,
            keyId = null,
        )
        val mpd = com.project.puk.player.VideoSource.buildMpd(manifest)
        println("COMMA_IDX=" + mpd.indexOf(','))        // US locale: no comma decimals; seconds-based timeline.
        assertTrue(mpd.contains("PT13288.222S"))
        assertFalse(mpd.contains(","))
        assertTrue(mpd.contains("timescale=\"1\" duration=\"4\""))
        assertTrue(mpd.contains("<S t=\"0\" d=\"4\" r=\"3321\"/>"))
        assertTrue(mpd.contains("<S d=\"1\"/>"))
        assertTrue(mpd.contains("\$Time\$"))
        assertFalse(mpd.contains("{{"))
        assertTrue(mpd.contains("avc1.4d402a"))
        assertFalse(mpd.contains("ContentProtection"))
    }

}
