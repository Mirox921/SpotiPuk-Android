package com.project.puk.catalog

import com.project.puk.core.Lang

import android.content.Context
import android.net.Uri
import com.project.puk.api.Card
import com.project.puk.api.Section
import com.project.puk.core.Net
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class CategoryArtwork { Rotated, Full, Gradient, Typography }

internal data class CatalogCategory(
    val id: String,
    val title: String,
    val color: Long,
    val imageUrl: String = "",
    val composition: CategoryArtwork = CategoryArtwork.Full,
)
internal data class CatalogPage(val sections: List<Section>, val fromHome: Boolean = false)

internal interface CatalogProvider {
    fun categories(): List<CatalogCategory>
    suspend fun page(id: String): CatalogPage?
}

internal class CatalogRepository(private vararg val providers: CatalogProvider) {
    fun categories(): List<CatalogCategory> = providers.flatMap { it.categories() }.distinctBy { it.id }

    suspend fun page(id: String): CatalogPage? {
        for (provider in providers) {
            provider.page(id)?.takeIf { page -> page.sections.any { it.items.isNotEmpty() } }?.let { return it }
        }
        return null
    }
}

internal class RecentCatalogProvider(private val tracks: List<Card>) : CatalogProvider {
    private val category = CatalogCategory("recent", Lang.t(Lang.Key.LIB_RECENT_ON_SPOTIPUK), 0xFF513542, tracks.firstOrNull()?.imageUrl.orEmpty())
    override fun categories() = listOf(category).filter { tracks.isNotEmpty() }

    override suspend fun page(id: String) = if (id == category.id && tracks.isNotEmpty()) {
        CatalogPage(listOf(Section(Lang.t(Lang.Key.CAT_HISTORY_IN_SPOTIPUK), tracks)))
    } else null
}

internal class HomeCatalogProvider(home: List<Section>) : CatalogProvider {
    private val sections = home.mapNotNull { section ->
        val music = section.items.filter { it.kind in musicKinds && mediaUri.matches(it.uri) }
            .distinctBy { it.uri }
        section.copy(items = music).takeIf { music.isNotEmpty() }
    }
    private val category = CatalogCategory(
        id = "for-you",
        title = Lang.t(Lang.Key.LIB_FOR_YOU),
        color = 0xFF57392F,
        imageUrl = sections.asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl.orEmpty(),
    )

    override fun categories() = listOf(category).filter { sections.isNotEmpty() }

    override suspend fun page(id: String): CatalogPage? = snapshot(id)

    fun snapshot(id: String): CatalogPage? {
        if (id == category.id) {
            return sections.takeIf { it.isNotEmpty() }?.let { CatalogPage(it, fromHome = true) }
        }
        val genre = themes[id] ?: return null
        val seen = mutableSetOf<String>()
        val themed = sections.mapNotNull { section ->
            val sectionMatches = genre.containsMatchIn(section.title)
            val matching = section.items.filter { card ->
                val matches = sectionMatches ||
                    (card.kind == "playlist" && genre.containsMatchIn(card.title))
                matches && seen.add(card.uri)
            }
            section.copy(items = matching).takeIf { matching.isNotEmpty() }
        }
        return themed.takeIf { it.isNotEmpty() }?.let { CatalogPage(it, fromHome = true) }
    }

    private companion object {
        fun terms(pattern: String) = Regex("(?<![\\p{L}\\p{N}])(?:$pattern)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

        val themes = mapOf(
            "trance" to terms("trance|транс"),
            "dubstep" to terms("dubstep|дабстеп"),
            "punk" to terms("punk|панк"),
            "gaming" to terms("gaming|для игр"),
            "energy" to terms("energy|для энергии"),
            "romance" to terms("love pop|romance|романтика"),
            "soul" to terms("soul|funk|соул|фанк"),
            "melancholy" to terms("melancholy|sad hour|меланхолия"),
            "relax" to terms("relax and unwind|relax & unwind|для отдыха"),
            "pop" to terms("pop|поп"),
            "hip-hop" to terms("hip[ -]hop|rap|хип[ -]хоп|рэп"),
            "rock" to terms("rock|рок"),
            "electronic" to terms("electronic|electronica|edm|электроника"),
            "jazz" to terms("jazz|джаз"),
            "metal" to terms("metal|метал|металл"),
            "indie" to terms("indie|инди"),
            "alternative" to terms("alternative|альтернатива"),
            "chill" to terms("chill|relax|спокойная музыка"),
            "dance" to terms("dance|party|танцевальная музыка|для вечеринки"),
            "focus" to terms("focus|концентрация|для концентрации"),
            "hits" to terms("top hits|топ хитов"),
            "new" to terms("new releases|new music friday|новые релизы|новинки"),
            "techno" to terms("techno|техно"),
            "house" to terms("house|хаус"),
            "phonk" to terms("phonk|фонк"),
            "dnb" to terms("drum (?:and|&) bass|dnb"),
            "hardstyle" to terms("hardstyle|хардстайл"),
            "synthwave" to terms("synthwave|синтвейв"),
            "lofi" to terms("lo[ -]?fi|лоу[ -]фай"),
            "ambient" to terms("ambient|эмбиент"),
            "classical" to terms("classical|классическая музыка"),
            "kpop" to terms("k[ -]pop|кей[ -]поп"),
            "rnb" to terms("r[ &]+b|rhythm and blues"),
            "reggae" to terms("reggae|регги"),
            "sleep" to terms("sleep|для сна"),
            "workout" to terms("workout|тренировки"),
            "throwback" to terms("throwback|all out 80s|хиты прошлых лет"),
            "nightdrive" to terms("night drive|ночная поездка"),
            "roadtrip" to terms("road trip|в дорогу"),
        )
        val musicKinds = setOf("track", "album", "playlist", "artist")
        val mediaUri = Regex("spotify:(track|album|playlist|artist):[A-Za-z0-9]{22}")
    }
}

internal class EditorialCatalogProvider(context: Context) : CatalogProvider {
    private val editions = localizedEditions()
    private val cache = context.getSharedPreferences("catalog_oembed_v1", Context.MODE_PRIVATE)
    private val thumbnails = context.assets.open("catalog_thumbnails.json").bufferedReader().use {
        JSONObject(it.readText())
    }

    override fun categories() = editions.map { edition ->
        val first = edition.sections.first().items.first()
        edition.category.copy(imageUrl = artwork(first.uri))
    }

    private fun cached(uri: String): JSONObject? =
        cache.getString(uri, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun artwork(uri: String): String = cached(uri)?.optString("artwork").orEmpty()
        .ifBlank { thumbnails.optString(uri) }

    fun fallback(id: String): CatalogPage? = editions.firstOrNull { it.category.id == id }
        ?.let { edition ->
            CatalogPage(edition.sections.map { section ->
                section.copy(items = section.items.map { it.copy(imageUrl = artwork(it.uri)) })
            })
        }

    override suspend fun page(id: String): CatalogPage? {
        val initial = fallback(id) ?: return null
        return CatalogPage(initial.sections.map { section ->
            section.copy(items = section.items.map { enrich(it) })
        })
    }

    private suspend fun enrich(card: Card): Card {
        val uri = card.uri
        val saved = cached(uri)
        val now = System.currentTimeMillis()
        val fresh = saved != null && now - saved.optLong("savedAt") in 0 until TimeUnit.DAYS.toMillis(7)
        val metadata = if (fresh || now < cache.getLong("retryAt", 0)) saved else {
            val link = "https://open.spotify.com/${card.kind}/${uri.substringAfterLast(':')}"
            val response = Net.get("https://open.spotify.com/oembed?url=${Uri.encode(link)}", tag = "catalog metadata")
            if (response?.code == 429) {
                val delay = response.retryAfterSeconds().coerceIn(30, 86_400)
                cache.edit().putLong("retryAt", now + delay * 1000).apply()
            }
            response?.takeIf { it.ok }?.json()?.let { data ->
                val title = data.optString("title").takeIf { it.isNotBlank() } ?: return@let null
                val artwork = data.optString("thumbnail_url").takeIf { it.startsWith("https://") }.orEmpty()
                JSONObject().put("title", title).put("artwork", artwork).put("savedAt", now)
                    .also { cache.edit().putString(uri, it.toString()).apply() }
            } ?: saved
        }
        return if (metadata == null) card else card.copy(
            title = metadata.optString("title", card.title),
            imageUrl = metadata.optString("artwork").ifBlank { card.imageUrl },
        )
    }

    private data class Edition(val category: CatalogCategory, val sections: List<Section>)

    private companion object {
        fun album(id: String, title: String, artist: String) = Card("spotify:album:$id", title, artist, "", "album")
        fun playlist(id: String, title: String, owner: String = "Spotify") =
            Card("spotify:playlist:$id", title, owner, "", "playlist")
        fun edition(id: String, title: String, color: Long, vararg sections: Section) =
            Edition(CatalogCategory(id, title, color), sections.toList())

        val discovery = album("2noRn2Aes5aoNVsU6iWThc", "Discovery", "Daft Punk")
        val memories = album("4m2880jivSbbyEGAKfITCa", "Random Access Memories", "Daft Punk")
        val emotion = album("1DFixLWuPkv3KT3TnV35m3", "Emotion (Deluxe)", "Carly Rae Jepsen")
        val emotionB = album("5ht7ItJgpBH7W6vJ5BqpPr", "EMOTION SIDE B", "Carly Rae Jepsen")
        val abbey = album("0ETFjACtuP2ADo6LFhL6HN", "Abbey Road", "The Beatles")
        val moon = album("4LH4d3cOWNNsVw41Gqt2kv", "The Dark Side of the Moon", "Pink Floyd")
        val computer = album("6dVIqQ8qmQ5GBnJ9shOYGE", "OK Computer", "Radiohead")
        val parachutes = album("6ZG5lRT77aJ3btmArcykra", "Parachutes", "Coldplay")
        val blue = album("1weenld61qoidwYuZ1GESA", "Kind of Blue", "Miles Davis")
        val blueLegacy = album("4sb0eMpDn3upAFfyi4q2rw", "Kind of Blue (Legacy Edition)", "Miles Davis")
        val hits = playlist("37i9dQZF1DXcBWIGoYBM5M", "Today’s Top Hits")
        val releases = playlist("37i9dQZF1DX4JAvHpjipBk", "New Music Friday")

        fun playlistEdition(
            id: String, title: String, color: Long, composition: CategoryArtwork,
            playlistId: String, playlistTitle: String, owner: String = "Spotify",
        ) = Edition(
            CatalogCategory(id, title, color, composition = composition),
            listOf(Section(Lang.t(Lang.Key.CAT_THEMED_PLAYLISTS), listOf(playlist(playlistId, playlistTitle, owner)))),
        )

        fun localizedEditions() = listOf(
            edition("pop", Lang.t(Lang.Key.CAT_POP), 0xFF793351, Section(Lang.t(Lang.Key.CAT_SYNTH_POP), listOf(emotion, emotionB)), Section(Lang.t(Lang.Key.CAT_HITS), listOf(hits))),
            edition("hip-hop", Lang.t(Lang.Key.CAT_HIP_HOP), 0xFF75512D, Section(Lang.t(Lang.Key.LIB_ALBUMS), listOf(
                album("7ycBtnsMtyVbbwTfJwRjSP", "To Pimp a Butterfly", "Kendrick Lamar"),
                album("1ATL5GLyefJaxhQzSPVrLX", "Scorpion", "Drake")))),
            edition("rock", Lang.t(Lang.Key.CAT_ROCK), 0xFF753834, Section(Lang.t(Lang.Key.CAT_CLASSICS), listOf(abbey, moon)),
                Section(Lang.t(Lang.Key.CAT_ALT_ROCK), listOf(computer, album("2UJcKiJxNryhL050F5Z1Fk", "Nevermind", "Nirvana")))),
            edition("electronic", Lang.t(Lang.Key.CAT_ELECTRONIC), 0xFF4B427D, Section(Lang.t(Lang.Key.CAT_ELECTRONIC_ALBUMS), listOf(discovery, memories))),
            edition("jazz", Lang.t(Lang.Key.CAT_JAZZ), 0xFF795633, Section(Lang.t(Lang.Key.CAT_MILES_DAVIS), listOf(blue, blueLegacy))),
            edition("metal", Lang.t(Lang.Key.CAT_METAL), 0xFF473F55, Section(Lang.t(Lang.Key.CAT_THRASH_METAL), listOf(
                album("2Lq2qX3hYhiuPckC8Flj21", "Master of Puppets", "Metallica"),
                album("05DePtm7oQMdL3Uzw2Jmsc", "Ride the Lightning", "Metallica")))),
            edition("indie", Lang.t(Lang.Key.CAT_INDIE), 0xFF345349, Section(Lang.t(Lang.Key.CAT_INDIE_ROCK), listOf(
                album("78bpIziExqiI9qztvNFlQu", "AM", "Arctic Monkeys"),
                album("2k8KgmDp9oHrmu0MIj4XDE", "Is This It", "The Strokes")))),
            edition("alternative", Lang.t(Lang.Key.CAT_ALTERNATIVE), 0xFF365C55, Section(Lang.t(Lang.Key.CAT_ALTERNATIVE_ALBUMS), listOf(computer, parachutes))),
            edition("chill", Lang.t(Lang.Key.CAT_CHILL_MUSIC), 0xFF325579, Section(Lang.t(Lang.Key.CAT_CALM_EVENING_ALBUMS), listOf(parachutes, blue))),
            edition("dance", Lang.t(Lang.Key.CAT_FOR_PARTY), 0xFF824754, Section(Lang.t(Lang.Key.CAT_DANCE_ALBUMS), listOf(discovery, emotion))),
            edition("focus", Lang.t(Lang.Key.CAT_FOR_FOCUS), 0xFF465A6D, Section(Lang.t(Lang.Key.CAT_JAZZ_INSTRUMENTAL), listOf(blue, blueLegacy))),
            playlistEdition("techno", Lang.t(Lang.Key.CAT_TECHNO), 0xFF303C96, CategoryArtwork.Rotated,
                "37i9dQZF1DX6J5NfMJS675", "TECHNO BUNKER"),
            playlistEdition("house", Lang.t(Lang.Key.CAT_HOUSE), 0xFF11656B, CategoryArtwork.Gradient,
                "37i9dQZF1DXa8NOEUWPn9W", "Housewerk"),
            playlistEdition("phonk", Lang.t(Lang.Key.CAT_PHONK), 0xFF912246, CategoryArtwork.Full,
                "37i9dQZF1DWWY64wDtewQt", "phonk"),
            playlistEdition("dnb", "Drum & Bass", 0xFF3D4C88, CategoryArtwork.Rotated,
                "5ABMzUESx7K7EyowE5kFCl", "Liquid Drum and Bass", "UKF"),
            playlistEdition("hardstyle", Lang.t(Lang.Key.CAT_HARDSTYLE), 0xFF9E371C, CategoryArtwork.Typography,
                "3lQHKpCakdSpvMFOtjZezP", "Hardstyle/Raw/Euphoric 🪚", "m0rten97"),
            playlistEdition("synthwave", Lang.t(Lang.Key.CAT_SYNTHWAVE), 0xFF733480, CategoryArtwork.Gradient,
                "0V3Mq7RQdMZXxrcUa76NER", "Synthwave Vibes 2026", "SkyQode"),
            playlistEdition("lofi", "Lo-fi", 0xFF7B4734, CategoryArtwork.Full,
                "1jjnYpF8gaTdtEyTx7OQsz", "Sunny Beats ☀️ [Happy Lo-Fi Beats]", "LoFi Café Records"),
            playlistEdition("ambient", Lang.t(Lang.Key.CAT_AMBIENT), 0xFF335A77, CategoryArtwork.Gradient,
                "37i9dQZF1DX3Ogo9pFvBkY", "Ambient Relaxation"),
            playlistEdition("classical", Lang.t(Lang.Key.CAT_CLASSICS), 0xFF4C527D, CategoryArtwork.Typography,
                "37i9dQZF1DWWEJlAGA9gs0", "Classical Essentials"),
            playlistEdition("kpop", "K-pop", 0xFF973764, CategoryArtwork.Rotated,
                "37i9dQZF1DX9tPFwDMOaN1", "K-Pop ON! (온)"),
            playlistEdition("rnb", "R&B", 0xFF875028, CategoryArtwork.Gradient,
                "37i9dQZF1DX4SBhb3fqCJd", "RNB X"),
            playlistEdition("reggae", Lang.t(Lang.Key.CAT_REGGAE), 0xFF35664C, CategoryArtwork.Rotated,
                "6ot1udLkomlGxNYVSJSkMf", "Reggae Classics / Roots / Rasta Sounds", "MIRAMAR"),
            playlistEdition("sleep", Lang.t(Lang.Key.CAT_FOR_SLEEP), 0xFF344289, CategoryArtwork.Full,
                "37i9dQZF1DWZd79rJ6a7lp", "Sleep"),
            playlistEdition("workout", Lang.t(Lang.Key.CAT_WORKOUT), 0xFF94382B, CategoryArtwork.Typography,
                "37i9dQZF1DX76Wlfdnj7AP", "Beast Mode"),
            playlistEdition("throwback", Lang.t(Lang.Key.CAT_THROWBACK), 0xFF743C89, CategoryArtwork.Gradient,
                "37i9dQZF1DX4UtSsGT1Sbe", "All Out 80s"),
            playlistEdition("nightdrive", Lang.t(Lang.Key.CAT_NIGHT_DRIVE), 0xFF35406E, CategoryArtwork.Full,
                "73yUcvpuVBrI4Q6gZ0kbpU", "Night Drive", "Aditssaputra"),
            playlistEdition("roadtrip", Lang.t(Lang.Key.CAT_ROAD_TRIP), 0xFF855026, CategoryArtwork.Rotated,
                "37i9dQZF1DX9wC1KY45plY", "Classic Road Trip Songs"),
            Edition(
                CatalogCategory("trance", Lang.t(Lang.Key.CAT_TRANCE), 0xFF69439B, composition = CategoryArtwork.Gradient),
                listOf(
                    Section(Lang.t(Lang.Key.CAT_MODERN_TRANCE), listOf(playlist("37i9dQZF1DX91oIci4su1D", "trance mission"))),
                    Section(Lang.t(Lang.Key.CAT_TRANCE_CLASSICS), listOf(playlist("37i9dQZF1DXbtYAdenGE9U", "Trance Classics"))),
                ),
            ),
            playlistEdition("dubstep", Lang.t(Lang.Key.CAT_DUBSTEP), 0xFF813D52, CategoryArtwork.Rotated,
                "37i9dQZF1DX5Q27plkaOQ3", "Dubstep Don"),
            playlistEdition("punk", Lang.t(Lang.Key.CAT_PUNK), 0xFF903D24, CategoryArtwork.Typography,
                "37i9dQZF1DX3LDIBRoaCDQ", "Classic Punk"),
            playlistEdition("gaming", Lang.t(Lang.Key.CAT_FOR_GAMING), 0xFF3C427F, CategoryArtwork.Full,
                "58oml4bZtN0C8mOZNNdynl", "LoFi Gaming", "Mari"),
            playlistEdition("energy", Lang.t(Lang.Key.CAT_FOR_ENERGY), 0xFFA1483B, CategoryArtwork.Gradient,
                "1RoGYBpejxNyY5fEGw7QR9", "Energy Booster: Pop", "Kane Havaror"),
            playlistEdition("romance", Lang.t(Lang.Key.CAT_ROMANCE), 0xFF8A3661, CategoryArtwork.Rotated,
                "37i9dQZF1DX50QitC6Oqtn", "Love Pop"),
            playlistEdition("soul", Lang.t(Lang.Key.CAT_SOUL_FUNK), 0xFF75483C, CategoryArtwork.Rotated,
                "37i9dQZF1DWWvhKV4FBciw", "Funk & Soul Classics"),
            playlistEdition("melancholy", Lang.t(Lang.Key.CAT_MELANCHOLY), 0xFF36446C, CategoryArtwork.Full,
                "37i9dQZF1DWSqBruwoIXkA", "sad hour"),
            playlistEdition("relax", Lang.t(Lang.Key.CAT_FOR_RELAX), 0xFF3F6558, CategoryArtwork.Gradient,
                "37i9dQZF1DWU0ScTcjJBdj", "Relax & Unwind"),
            edition("hits", Lang.t(Lang.Key.CAT_POPULAR), 0xFF6C493C, Section(Lang.t(Lang.Key.CAT_SPOTIFY_PLAYLISTS), listOf(hits))),
            edition("new", Lang.t(Lang.Key.CAT_NEW_RELEASES), 0xFF5C456D, Section(Lang.t(Lang.Key.CAT_SPOTIFY_PLAYLISTS), listOf(releases))),
        )
    }
}
