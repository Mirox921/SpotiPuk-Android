package com.project.puk.api

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
)

/** One role group of track credits, e.g. "Авторы музыки" or "Продюсеры". */
data class CreditGroup(
    val role: String,
    val names: List<String>,
)

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
)

data class HomeFacet(val id: String, val label: String, val children: List<HomeFacet>)

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
    val title: String,
    val startMs: Long,
    val endMs: Long,
)
