package com.project.puk.ui

import com.project.puk.api.HomeSectionLayout
import com.project.puk.api.Section

internal fun Section.usesRecommendationRows(): Boolean = when (layout) {
    HomeSectionLayout.RECOMMENDATIONS -> true
    HomeSectionLayout.UNSPECIFIED -> items.size == 1
    HomeSectionLayout.SHELF, HomeSectionLayout.SHORTCUTS -> false
}

internal fun homeSectionKeys(sections: List<Section>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return sections.map { section ->
        val identity = if (section.sourceId.isNotBlank()) {
            "source:${section.sourceId}"
        } else {
            "fallback:${section.layout}:${section.title}:${section.items.firstOrNull()?.uri.orEmpty()}"
        }
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        "${identity.length}:$identity:$occurrence"
    }
}
