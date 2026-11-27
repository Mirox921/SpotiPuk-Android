package com.project.puk.api

import com.project.puk.core.Lang

/** Small, UI-facing data shapes. Parsing lives next to the queries in Spotify.kt. */

data class TrackRow(
    val uri: String,
    val id: String,
    val name: String,
    val artists: String,
    val albumName: String,
    val albumUri: String,
    val imageUrl: String,
    val durationMs: Long,
    val artistUri: String = "",
    val addedAt: String = "",
    val playlistUid: String = "",
    val playlistIndex: Int = -1,
    val artistUris: List<String> = emptyList(),
)

data class Card(
    val uri: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val kind: String, // playlist / album / artist / podcast / track
    val savedEpisodes: Boolean = false,
    val pinned: Boolean? = null,
) {
    val displayTitle: String get() = when {
        savedEpisodes -> Lang.t(Lang.Key.AUTO_YOUR_EPISODES)
        kind == "collection" -> Lang.t(Lang.Key.JAM_LIKED_TRACKS)
        else -> title
    }
}

/** One role group of track credits, e.g. "Авторы музыки" or "Продюсеры". */
data class CreditGroup(
    val role: String,
    val names: List<String>,
) {
    val displayRole: String get() = role.ifBlank { Lang.t(Lang.Key.API_OTHERS) }
}

/** Full credits of a track as reported by the web player's credits query. */
data class TrackCredits(
    val trackName: String,
    val groups: List<CreditGroup>,
    val sourceNames: List<String>,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && sourceNames.isEmpty()
}

data class Section(
    val title: String,
    val items: List<Card>,
    val sourceId: String = "",
    val layout: HomeSectionLayout = HomeSectionLayout.UNSPECIFIED,
    val titleKey: Lang.Key? = null,
) {
    val displayTitle: String get() = titleKey?.let { Lang.t(it) } ?: title
}

data class HomeFacet(val id: String, val label: String, val children: List<HomeFacet>) {
    internal val labelKey = Lang.homeFacetKey(label)
    val displayLabel: String get() = labelKey?.let { Lang.t(it) } ?: label
}

data class HomeFeed(val sections: List<Section>, val facets: List<HomeFacet>)

enum class HomeSectionLayout {
    UNSPECIFIED, SHELF, SHORTCUTS, RECOMMENDATIONS,
}

data class PlaylistPage(
    val name: String,
    val imageUrl: String,
    val tracks: List<TrackRow>,
    val total: Int?,
    val next: Int? = null,
    val owner: String = "",
)

data class AlbumPage(
    val name: String,
    val imageUrl: String,
    val artists: String,
    val tracks: List<TrackRow>,
    val total: Int?,
    val next: Int?,
    val releaseDate: String = "",
    val label: String = "",
)

data class ArtistPage(
    val name: String,
    val imageUrl: String,
    val albums: List<Card>,
    val biography: String = "",
    val releases: List<ArtistRelease> = emptyList(),
    val topTracks: List<TrackRow> = emptyList(),
    val monthlyListeners: Long? = null,
    val followers: Long? = null,
    val verified: Boolean = false,
    val gallery: List<String> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val shelves: List<Section> = emptyList(),
)

data class ArtistRelease(val card: Card, val type: String, val date: String, val popular: Boolean = false)

data class LyricsLine(val startMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricsLine>,
    val synced: Boolean,
    val provider: String,
)

// ---- podcasts ----

data class EpisodeRow(
    val uri: String,
    val name: String,
    val description: String,
    val releaseDate: String,
    val durationMs: Long,
    val imageUrl: String,
    val played: Boolean,
    val positionMs: Long,
    val playable: Boolean,
)

data class EpisodePage(
    val rows: List<EpisodeRow>,
    val total: Int,
    val nextOffset: Int?,
)

data class ShowDetail(
    val uri: String,
    val name: String,
    val publisher: String,
    val description: String,
    val imageUrl: String,
    val mediaType: String, // AUDIO / VIDEO / MIXED
    val episodes: List<EpisodeRow>,
    val total: Int,
    val nextOffset: Int?,
)

data class EpisodeDetail(
    val uri: String,
    val name: String,
    val description: String,
    val showName: String,
    val showUri: String,
    val imageUrl: String,
    val durationMs: Long,
    val releaseDate: String,
    val playable: Boolean,
    val positionMs: Long,
    val played: Boolean,
    val shareUrl: String,
)

/** One podcast chapter span; title may be empty when the source has none. */
data class ChapterSpan(
    private val sourceTitle: String,
    val startMs: Long,
    val endMs: Long,
    private val number: Int? = null,
) {
    val title: String get() = sourceTitle.ifBlank { number?.let { Lang.t(Lang.Key.API_CHAPTER, it) }.orEmpty() }
}
