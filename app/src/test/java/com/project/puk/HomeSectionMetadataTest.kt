package com.project.puk

import com.project.puk.api.HomeSectionLayout
import com.project.puk.api.Spotify
import com.project.puk.ui.homeSectionKeys
import com.project.puk.ui.usesRecommendationRows
import com.project.puk.core.Lang
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeSectionMetadataTest {
    @Test fun serverTypeControlsLayoutAndIdentitySurvivesReordering() {
        val sections = Spotify.parseHomeSections(JSONObject("""
            {"data":{"home":{"sectionContainer":{"sections":{"items":[
                {"uri":"spotify:section:baseline","data":{"__typename":"HomeFeedBaselineSectionData",
                    "sectionTitle":{"text":"Made for you"},"items":[
                        {"data":{"uri":"spotify:track:one","name":"One"}},
                        {"data":{"uri":"spotify:track:two","name":"Two"}}]}},
                {"uri":"spotify:section:shelf","data":{"__typename":"HomeGenericSectionData",
                    "title":{"transformedLabel":"Mixes"},"items":[
                        {"content":{"data":{"uri":"spotify:playlist:mix","name":"Mix"}}}]}},
                {"uri":"spotify:section:shortcuts","data":{"__typename":"HomeShortsSectionData","title":{"text":"For {0}"},
                    "items":[{"data":{"uri":"spotify:album:a","name":"Album"}}]}}
            ]}}}}}
        """.trimIndent()))
        assertEquals(listOf("Made for you", "Mixes", "Для тебя"), sections.map { it.displayTitle })
        assertTrue(sections[0].usesRecommendationRows())
        assertFalse(sections[1].usesRecommendationRows())
        assertEquals(HomeSectionLayout.SHORTCUTS, sections[2].layout)
        assertEquals(Lang.Key.LIB_FOR_YOU, sections[2].titleKey)

        val shelf = sections[1]
        assertEquals(homeSectionKeys(sections)[1], homeSectionKeys(listOf(shelf.copy(title = "Updated"))).single())
        assertEquals(2, homeSectionKeys(listOf(shelf, shelf)).distinct().size)
    }

    @Test fun cachedHomeFacetsKeepTheirKeysAndServerLabels() {
        val facets = Spotify.parseHomeFacets(JSONArray("""
            [
              {"id":"all","label":"Усе"},
              {"id":"music","label":"Музика","subChips":[{"id":"audiobooks","label":"Audiobooks"}]},
              {"id":"podcasts","label":"Podcasts"},
              {"id":"custom","label":"New releases"}
            ]
        """.trimIndent()))

        assertEquals(listOf("music", "podcasts", "custom"), facets.map { it.id })
        assertEquals(listOf(Lang.Key.HOME_FACET_MUSIC, Lang.Key.HOME_FACET_PODCASTS, null), facets.map { it.labelKey })
        assertEquals(Lang.Key.HOME_FACET_AUDIOBOOKS, facets.first().children.single().labelKey)
        assertEquals("Музика", facets.first().label)
        assertEquals("New releases", facets.last().displayLabel)
    }
}
