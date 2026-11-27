package com.project.puk.api

import com.project.puk.core.Lang

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import com.project.puk.core.Auth
import com.project.puk.core.ClientTokenManager
import com.project.puk.core.Log
import com.project.puk.core.Net
import com.project.puk.core.Pathfinder
import com.project.puk.core.TokenMinter
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * All user-data endpoints the native UI consumes. Every call silently makes
 * sure a valid bearer exists first; the persisted-query hashes are managed by
 * Pathfinder (scrape > captured > seed).
 */
object Spotify {

    // ---- generic helpers ----

    private suspend fun bearer(context: Context): String = TokenMinter.ensure(context)

    private suspend fun pfPost(context: Context, op: String, variables: JSONObject): JSONObject? {
        val token = bearer(context)
        if (token.isBlank()) return null
        return Pathfinder.queryPost(context, token, op, variables.toString())
    }

    private suspend fun pfGet(context: Context, op: String, variables: JSONObject): JSONObject? {
        val token = bearer(context)
        if (token.isBlank()) return null
        return Pathfinder.query(context, token, op, variables.toString())
    }

    data class UserProfilePlaylist(val uri: String, private val sourceName: String, val imageUrl: String, val trackCount: Int) {
        val name: String get() = sourceName.ifBlank { Lang.t(Lang.Key.JAM_PLAYLIST) }
    }

    /** Public playlists shown on the user's profile page. Empty on failure or a blank id. */
    suspend fun userPublicPlaylists(context: Context, userId: String): List<UserProfilePlaylist> {
        val id = userId.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return emptyList()
        val url = "https://api.spotify.com/v1/users/${Uri.encode(id)}/playlists?limit=50"
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json",
            "app-platform" to "WebPlayer",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/",
            "User-Agent" to Net.DESKTOP_UA,
            "client-token" to Auth.clientToken(context),
        ).filterValues { it.isNotBlank() }
        var response = Net.get(url, headers, "profile/playlists")
        // The REST quota refills within seconds; wait out a short window and retry once.
        if (response?.code == 429) {
            val waitSeconds = response.retryAfterSeconds().takeIf { it in 1..15 } ?: 2
            Log.i("Spotify", "profile playlists rate-limited, retrying in $waitSeconds s")
            delay(waitSeconds * 1000L)
            response = Net.get(url, headers, "profile/playlists")
        }
        if (response?.ok == true) {
            val items = response.json()?.optJSONArray("items")
            val parsed = (0 until (items?.length() ?: 0)).mapNotNull { index ->
                val entry = items?.optJSONObject(index) ?: return@mapNotNull null
                val uri = entry.optString("uri")
                if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null
                val image = entry.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
                val playlist = UserProfilePlaylist(uri, entry.optString("name"), image,
                    entry.optJSONObject("tracks")?.optInt("total", 0) ?: 0)
                Triple(uri, playlist, entry.opt("public") as? Boolean)
            }
            saveVisibility(context, id, parsed.map { it.first to it.third })
            val rows = parsed.filter { it.third != false }.map { it.second }
            Log.i("Spotify", "profile playlists REST HTTP=200 total=${items?.length() ?: -1} visible=${rows.size}")
            return rows
        }
        Log.i("Spotify", "profile playlists REST HTTP=${response?.code ?: 0}, trying partner fallback")
        return partnerProfilePlaylists(context)
    }

    /**
     * Partner-endpoint fallback. Its pinned document expects an anchor track, so reuse one
     * from the account's own content. The payload carries no visibility flag, so playlists
     * cached as private by an earlier REST fetch are hidden; unknown ones stay listed.
     */
    private suspend fun partnerProfilePlaylists(context: Context): List<UserProfilePlaylist> {
        val anchor = anchorTrackUri(context) ?: run {
            Log.i("Spotify", "profile playlists fallback skipped: no anchor track")
            return emptyList()
        }
        val page = pfPost(context, "editablePlaylists", JSONObject()
            .put("offset", 0).put("limit", 50).put("textFilter", "")
            .put("folderUri", JSONObject.NULL).put("uris", JSONArray().put(anchor)))
            ?.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("editablePlaylists") ?: return emptyList()
        val items = page.optJSONArray("items") ?: return emptyList()
        val rows = (0 until items.length()).mapNotNull { index ->
            val data = items.optJSONObject(index)?.optJSONObject("item")?.optJSONObject("data") ?: return@mapNotNull null
            val uri = data.optString("uri")
            if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null
            UserProfilePlaylist(uri, data.optString("name"), "", -1)
        }
        Log.i("Spotify", "profile playlists fallback visible=${rows.size}")
        // Hide playlists the last successful REST fetch recorded as private; unknown entries stay listed.
        val hidden = cachedPrivateUris(context, Auth.account(context)?.id.orEmpty())
        // The partner payload carries no cover art; fetchPlaylist supplies covers per playlist.
        return rows.filter { it.name.isNotBlank() && it.uri !in hidden }.mapNotNull { row ->
            val node = pfPost(context, "fetchPlaylist", JSONObject()
                .put("uri", row.uri).put("offset", 0).put("limit", 1)
                .put("enableWatchFeedEntrypoint", false).put("includeEpisodeContentRatingsV2", true))
                ?.optJSONObject("data")?.optJSONObject("playlistV2")
            if (node == null) return@mapNotNull row
            row.copy(imageUrl = coverOf(node).ifBlank { row.imageUrl })
        }
    }

    /** Any track of the account that can anchor the editablePlaylists partner query. */
    private suspend fun anchorTrackUri(context: Context): String? {
        likedPage(context, 0, 1)?.items?.firstOrNull()?.uri?.let { return it }
        home(context)?.sections.orEmpty().asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.kind == "track" && it.uri.startsWith("spotify:track:") }?.uri?.let { return it }
        return relatedTracks(context, "music").firstOrNull()?.uri
    }

    // ---- home ----

    suspend fun home(context: Context, facet: String = ""): HomeFeed? {
        val captured = Pathfinder.variablesOf(context, "home")
        val base = runCatching { JSONObject(captured) }.getOrNull() ?: JSONObject()
        if (!base.has("homeEndUserIntegration")) base.put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
        if (!base.has("timeZone")) base.put("timeZone", TimeZone.getDefault().id)
        if (!base.has("includeEpisodeContentRatingsV2")) base.put("includeEpisodeContentRatingsV2", false)
        val variables = base
            .put("sp_t", Pathfinder.sessionToken())
            .put("facet", facet)
            .put("sectionItemsLimit", 10)
        val json = pfGet(context, "home", variables) ?: return null
        val home = json.optJSONObject("data")?.optJSONObject("home") ?: return null
        val sections = parseHomeSections(json)
        Log.i("Spotify", "home sections=${sections.size} sourceIds=${sections.count { it.sourceId.isNotBlank() }} " +
            "shortcuts=${sections.count { it.layout == HomeSectionLayout.SHORTCUTS }} " +
            "recommendations=${sections.count { it.layout == HomeSectionLayout.RECOMMENDATIONS }}")
        return HomeFeed(sections, parseHomeFacets(home.optJSONArray("homeChips")))
    }

    internal fun parseHomeFacets(chips: JSONArray?): List<HomeFacet> = buildList {
        if (chips == null) return@buildList
        for (index in 0 until chips.length()) {
            val chip = chips.optJSONObject(index) ?: continue
            val id = chip.optString("id").takeIf { it.isNotBlank() } ?: continue
            val label = homeSectionLabel(chip.opt("label")) ?: continue
            val facet = HomeFacet(id, label, parseHomeFacets(chip.optJSONArray("subChips")))
            if (facet.labelKey != Lang.Key.HOME_FACET_ALL) add(facet)
        }
    }.distinctBy(HomeFacet::id)

    internal fun parseHomeSections(json: JSONObject): List<Section> {
        val container = json.optJSONObject("data")?.optJSONObject("home")
            ?.optJSONObject("sectionContainer") ?: return emptyList()
        val sections = container.optJSONObject("sections")?.optJSONArray("items")
            ?: container.optJSONArray("sections") ?: return emptyList()
        return buildList {
            for (index in 0 until sections.length()) {
                val section = sections.optJSONObject(index) ?: continue
                val data = section.optJSONObject("data") ?: continue
                val sourceType = data.optString("__typename")
                val layout = when (sourceType) {
                    "HomeShortsSectionData" -> HomeSectionLayout.SHORTCUTS
                    "HomeFeedBaselineSectionData" -> HomeSectionLayout.RECOMMENDATIONS
                    "HomeGenericSectionData", "HomeGridSectionData", "HomeRecentlyPlayedSectionData" -> HomeSectionLayout.SHELF
                    else -> HomeSectionLayout.UNSPECIFIED
                }
                val title = homeSectionLabel(data.opt("title"))
                    ?: homeSectionLabel(data.opt("sectionTitle"))
                    ?: homeSectionLabel(section.opt("sectionTitle"))
                val items = section.optJSONObject("sectionItems")?.optJSONArray("items")
                    ?: data.optJSONArray("items") ?: continue
                val cards = buildList {
                    for (itemIndex in 0 until items.length()) {
                        val item = items.optJSONObject(itemIndex) ?: continue
                        val content = item.optJSONObject("content") ?: item
                        val entity = content.optJSONObject("data") ?: item.optJSONObject("data") ?: continue
                        val card = cardFrom(entity) ?: continue
                        add(card)
                    }
                }
                if (cards.isNotEmpty()) {
                    val id = (section.opt("uri") as? String).orEmpty()
                    add(Section(title.orEmpty(), cards, sourceId = id, layout = layout, titleKey = Lang.Key.LIB_FOR_YOU.takeIf { title == null }))
                }
            }
        }
    }

    private fun homeSectionLabel(value: Any?): String? {
        val text = when (value) {
            is String -> value
            is JSONObject -> sequenceOf("transformedLabel", "translatedBaseText", "text", "title")
                .mapNotNull { value.opt(it) as? String }.firstOrNull(String::isNotBlank)
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotBlank() && !it.contains(Regex("""\{[0-9]+\}""")) }
    }

    // ---- search ----

    enum class SearchType(internal val operation: String, internal val field: String) {
        TRACKS("searchTracks", "tracksV2"),
        SHOWS("searchPodcasts", "podcasts"),
        EPISODES("searchFullEpisodes", "episodes"),
        ARTISTS("searchArtists", "artists"),
        ALBUMS("searchAlbums", "albumsV2"),
        PLAYLISTS("searchPlaylists", "playlists"),
    }

    data class SearchPage(
        val tracks: List<TrackRow>,
        val cards: List<Card>,
        val next: Int?,
        val total: Int? = null,
    ) {
        fun append(page: SearchPage) = SearchPage(
            (tracks + page.tracks).distinctBy(TrackRow::uri),
            (cards + page.cards).distinctBy(Card::uri),
            page.next,
            page.total ?: total,
        )
    }

    data class SearchResults(val pages: Map<SearchType, SearchPage>) {
        val failedSections get() = SearchType.entries.size - pages.size
    }

    suspend fun search(context: Context, term: String): SearchResults {
        val pages = buildMap {
            for (type in SearchType.entries) {
                searchPage(context, term, type, 0)?.let { put(type, it) }
            }
        }
        return SearchResults(pages)
    }

    suspend fun searchSuggestions(context: Context, query: String): List<String> {
        val response = pfPost(context, "searchSuggestions", searchVariables(query, 6).apply { remove("searchTerm"); put("query", query) })
        val items = searchSection(response, "topResultsV2")?.optJSONArray("itemsV2") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val node = items.optJSONObject(index) ?: continue
                val item = node.optJSONObject("item") ?: node
                if (item.optString("__typename") != "SearchAutoCompleteEntity") continue
                val raw = item.optJSONObject("data")?.optString("text").orEmpty()
                val suggestion = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
                if (suggestion.isNotEmpty() && !suggestion.equals(query, ignoreCase = true)) add(suggestion)
            }
        }.distinct().take(5)
    }

    suspend fun searchPage(context: Context, term: String, type: SearchType, offset: Int, pageSize: Int = 20): SearchPage? {
        val query = term.trim()
        if (query.isEmpty() || offset < 0) return null
        val limit = pageSize.coerceIn(1, 20)
        val response = pfPost(context, type.operation, searchVariables(query, limit).put("offset", offset))
        return parseSearchPage(response, type, offset, limit)
    }

    internal fun parseSearchPage(json: JSONObject?, type: SearchType, offset: Int, limit: Int): SearchPage? {
        val section = searchSection(json, type.field) ?: return null
        val raw = section.optJSONArray("items") ?: return null
        val data = searchItems(raw)
        return SearchPage(
            tracks = if (type == SearchType.TRACKS) data.mapNotNull { trackFrom(it) }.distinctBy(TrackRow::uri) else emptyList(),
            cards = if (type == SearchType.TRACKS) emptyList() else data.mapNotNull(::cardFrom).distinctBy(Card::uri),
            next = nextPage(offset, raw.length(), limit, section.optInt("totalCount", -1)),
            total = section.optInt("totalCount", -1).takeIf { it >= 0 },
        )
    }

    private fun searchVariables(trimmed: String, limit: Int): JSONObject =
        JSONObject()
            .put("includePreReleases", false)
            .put("includeAlbumPreReleases", false)
            .put("numberOfTopResults", 20)
            .put("searchTerm", trimmed)
            .put("offset", 0)
            .put("limit", limit)
            .put("includeAuthors", true)
            .put("includeEpisodeContentRatingsV2", true)

    suspend fun relatedTracks(context: Context, artist: String): List<TrackRow> {
        if (artist.isBlank()) return emptyList()
        val json = pfPost(context, "searchTracks", searchVariables(artist, 20))
        val items = searchSection(json, "tracksV2")?.optJSONArray("items") ?: return emptyList()
        return searchItems(items).mapNotNull { trackFrom(it) }
    }

    private fun searchSection(json: JSONObject?, field: String): JSONObject? {
        val root = json?.optJSONObject("data") ?: return null
        return (root.optJSONObject("searchV2") ?: root.optJSONObject("search"))?.optJSONObject(field)
    }

    private fun searchItems(items: JSONArray): List<JSONObject> {
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            (item.optJSONObject("item") ?: item).optJSONObject("data")
        }
    }

    // ---- library ----

    data class Page<T>(val items: List<T>, val next: Int?, val total: Int? = null)

    private fun nextPage(offset: Int, count: Int, limit: Int, total: Int): Int? =
        (offset + count).takeIf { count > 0 && if (total >= 0) it < total else count >= limit }

    suspend fun library(context: Context, offset: Int = 0, limit: Int = 50, query: String = "", order: String? = null, folderUri: String? = null): Page<Card>? {
        val captured = runCatching { JSONObject(Pathfinder.variablesOf(context, "libraryV3")) }.getOrNull()
        val variables = (captured ?: JSONObject())
            .put("filters", JSONArray())
            .put("order", order ?: JSONObject.NULL)
            .put("textFilter", query)
            .put("features", JSONArray().put("LIKED_SONGS").put("YOUR_EPISODES_V2"))
            .put("limit", limit)
            .put("offset", offset)
            .put("flatten", query.isNotBlank() && folderUri == null)
            .put("expandedFolders", JSONArray())
            .put("folderUri", folderUri ?: JSONObject.NULL)
            .put("includeFoldersWhenFlattening", true)
        val json = pfPost(context, "libraryV3", variables) ?: return null
        val page = json.optJSONObject("data")?.optJSONObject("me")
            ?.optJSONObject("libraryV3") ?: return null
        val items = page.optJSONArray("items") ?: return null
        val cards = mutableListOf<Card>()
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val wrapper = entry.optJSONObject("item") ?: continue
            val data = wrapper.optJSONObject("data") ?: continue
            val uri = data.optString("uri").ifBlank { wrapper.optString("_uri") }
            val card = if (data.optString("__typename") == "Folder" && uri.contains(":folder:")) {
                Card(uri, data.optString("name"), "", "", "folder")
            } else cardFrom(data)
            card?.let { cards.add(it.copy(pinned = (entry.opt("pinned") as? Boolean).takeIf { entry.optBoolean("pinnable") })) }
        }
        Log.i("Spotify", "library offset=$offset order=${page.optJSONObject("selectedSortOrder")?.optString("id").orEmpty()} folder=${folderUri != null} raw=${items.length()} total=${page.optInt("totalCount", -1)} parsed=${cards.size}")
        return Page(cards, nextPage(offset, items.length(), limit, page.optInt("totalCount", -1)))
    }

    suspend fun setLibraryPinned(context: Context, uri: String, pinned: Boolean, folder: String? = null): Boolean {
        val generation = Auth.generation
        val token = bearer(context)
        if (token.isBlank() || generation != Auth.generation) return false
        val operation = if (pinned) "pinLibraryItem" else "unpinLibraryItem"
        val key = if (pinned) "pinItemInLibrary" else "unpinItemInLibrary"
        val variables = JSONObject().put("pinnableItemUri", uri)
        val result = Pathfinder.queryPost(context, token, operation, variables.toString(), fallbackToGet = false)
        val status = result?.optJSONObject("data")?.optJSONObject(key)?.optString("pinResult").orEmpty()
        if (Auth.generation != generation || result == null || (result.optJSONArray("errors")?.length() ?: 0) > 0) return false
        if (pinned && status != "SUCCESSFUL") return false
        var offset = 0
        while (Auth.generation == generation) {
            val page = library(context, offset = offset, folderUri = folder) ?: return false
            if (Auth.generation != generation) return false
            page.items.firstOrNull { it.uri == uri }?.let { return it.pinned == pinned }
            offset = page.next ?: return false
        }
        return false
    }

    private const val artistBanCollection = "https://spclient.wg.spotify.com/collection/v2"

    internal fun localBlockedArtists(context: Context): Set<String> {
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return emptySet()
        return com.project.puk.core.AppPrefs.prefs(context).getStringSet("artist_playback_block_$account", null).orEmpty()
    }

    internal fun setLocalArtistBlocked(context: Context, uri: String, blocked: Boolean): Boolean {
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return false
        if (!uri.startsWith("spotify:artist:")) return false
        val current = localBlockedArtists(context)
        val updated = if (blocked) current + uri else current - uri
        com.project.puk.core.AppPrefs.prefs(context).edit().putStringSet("artist_playback_block_$account", updated).apply()
        return true
    }

    suspend fun blockedArtists(context: Context): Set<String>? {
        val generation = Auth.generation
        val account = Auth.account(context)?.id ?: return null
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        if (generation != Auth.generation) return null
        val body = JSONObject().put("username", account).put("set", "artistban")
        val response = Net.postJson("$artistBanCollection/paging", body.toString(), playlistHeaders(context, token), "artistBan/read")
        if (response?.ok != true || generation != Auth.generation) return null
        val payload = response.json()
        val items = payload?.optJSONArray("items")
        if (items == null) return if (response.body.isBlank() || payload?.length() == 0) emptySet() else null
        return buildSet {
            for (index in 0 until items.length()) {
                val uri = items.optJSONObject(index)?.optString("uri").orEmpty()
                if (uri.startsWith("spotify:artist:")) add(uri)
            }
        }
    }

    suspend fun artistBlocked(context: Context, uri: String): Boolean? {
        if (!uri.startsWith("spotify:artist:")) return null
        val generation = Auth.generation
        val account = Auth.account(context)?.id ?: return null
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        val headers = playlistHeaders(context, token)
        if (generation != Auth.generation) return null
        val body = JSONObject().put("username", account).put("set", "artistban")
            .put("items", JSONArray().put(JSONObject().put("uri", uri)))
        val response = Net.postJson("$artistBanCollection/contains", body.toString(), headers, "artistBan/contains")
        val answer = response?.takeIf { it.ok }?.json()?.optJSONArray("found")?.opt(0) as? Boolean
        Log.i("Spotify", "artist ban lookup HTTP=${response?.code ?: 0} known=${answer != null}")
        return answer.takeIf { generation == Auth.generation }
    }

    suspend fun setArtistBlocked(context: Context, uri: String, blocked: Boolean): Boolean {
        if (!uri.startsWith("spotify:artist:")) return false
        val generation = Auth.generation
        val account = Auth.account(context)?.id ?: return false
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return false
        val headers = playlistHeaders(context, token)
        if (generation != Auth.generation) return false
        val item = JSONObject().put("uri", uri)
        if (!blocked) item.put("is_removed", true)
        val body = JSONObject().put("username", account).put("set", "artistban").put("items", JSONArray().put(item))
        val response = Net.postJson("$artistBanCollection/write", body.toString(), headers, "artistBan/write")
        if (response?.ok != true || generation != Auth.generation) return false
        val confirmed = artistBlocked(context, uri)
        return generation == Auth.generation && confirmed == blocked
    }

    suspend fun isSaved(context: Context, uri: String): Boolean? =
        pfPost(context, "areEntitiesInLibrary", JSONObject().put("uris", JSONArray().put(uri)))
            ?.optJSONObject("data")?.optJSONArray("lookup")?.optJSONObject(0)
            ?.optJSONObject("data")?.opt("saved") as? Boolean

    suspend fun setSaved(context: Context, uri: String, saved: Boolean): Boolean {
        val variables = JSONObject().put("libraryItemUris", JSONArray().put(uri))
            .put("interactionId", java.util.UUID.randomUUID().toString())
        val operation = if (saved) "addToLibrary" else "removeFromLibrary"
        val key = if (saved) "addLibraryItems" else "removeLibraryItems"
        return pfPost(context, operation, variables).mutationSucceeded(key)
    }

    data class PlaylistChoice(val uri: String, private val sourceName: String, val containsTrack: Boolean) {
        val name: String get() = sourceName.ifBlank { Lang.t(Lang.Key.JAM_PLAYLIST) }
    }

    private val playlistMetadataRetryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** uri -> public flag learned from successful REST profile fetches; consulted by the partner fallback. */
    private fun visibilityPrefs(context: Context) =
        context.getSharedPreferences("spotify_profile", Context.MODE_PRIVATE)

    private fun saveVisibility(context: Context, accountId: String, entries: List<Pair<String, Boolean?>>) {
        if (accountId.isBlank() || entries.isEmpty()) return
        val json = JSONObject()
        entries.forEach { (uri, isPublic) -> if (isPublic != null) json.put(uri, isPublic) }
        visibilityPrefs(context).edit().putString("visibility_$accountId", json.toString()).apply()
    }

    private fun cachedPrivateUris(context: Context, accountId: String): Set<String> {
        if (accountId.isBlank()) return emptySet()
        val json = runCatching { JSONObject(visibilityPrefs(context).getString("visibility_$accountId", null) ?: "{}") }
            .getOrDefault(JSONObject())
        return json.keys().asSequence().filter { !json.optBoolean(it, true) }.toSet()
    }

    data class OwnedPlaylistDetails(
        val uri: String, val name: String, val description: String,
        val isPublic: Boolean?, val collaborative: Boolean,
        internal val partner: Boolean = false,
    )

    private val playlistUriPattern = Regex("spotify:playlist:[A-Za-z0-9]{22}")

    internal fun ownedPlaylistDetails(json: JSONObject, uri: String, accountId: String): OwnedPlaylistDetails? {
        if (accountId.isBlank() || json.optJSONObject("owner")?.optString("id") != accountId) return null
        if (json.optString("uri") != uri || !playlistUriPattern.matches(uri)) return null
        return OwnedPlaylistDetails(uri, json.optString("name"), json.optString("description"),
            json.opt("public") as? Boolean, json.optBoolean("collaborative"))
    }

    internal fun partnerOwnedPlaylistDetails(node: JSONObject, uri: String, accountId: String): OwnedPlaylistDetails? {
        if (accountId.isBlank() || !playlistUriPattern.matches(uri) || node.optString("uri") != uri) return null
        val owner = node.optJSONObject("ownerV2")?.optJSONObject("data") ?: return null
        val ownerUri = owner.opt("uri") as? String
        val username = owner.opt("username") as? String
        if (ownerUri.isNullOrBlank() && username.isNullOrBlank()) return null
        if (!ownerUri.isNullOrBlank() && ownerUri != "spotify:user:$accountId") return null
        if (!username.isNullOrBlank() && username != accountId) return null
        if (node.optJSONObject("currentUserCapabilities")?.opt("canEditMetadata") == false) return null
        val name = (node.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return null
        return OwnedPlaylistDetails(uri, name, (node.opt("description") as? String).orEmpty(),
            isPublic = null, collaborative = false, partner = true)
    }

    private suspend fun partnerOwnedPlaylist(context: Context, uri: String, account: String): OwnedPlaylistDetails? =
        playlistNode(context, uri, limit = 1, offset = 0)?.let { partnerOwnedPlaylistDetails(it, uri, account) }

    /** Keep public visibility editing on Web API. The partner response can confirm metadata ownership separately. */
    suspend fun ownedPlaylistDetails(context: Context, uri: String): OwnedPlaylistDetails? {
        if (!playlistUriPattern.matches(uri)) return null
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return null
        val generation = Auth.generation
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        if (Auth.generation != generation || Auth.account(context)?.id != account) return null
        val response = if ((playlistMetadataRetryAt[account] ?: 0) <= SystemClock.elapsedRealtime()) {
            Net.get("https://api.spotify.com/v1/playlists/${uri.substringAfterLast(':')}?fields=uri,name,description,public,collaborative,owner(id)",
                mapOf("Authorization" to "Bearer $token"), "playlist/owner")
        } else null
        if (response?.code == 429) {
            val seconds = response.retryAfterSeconds().takeIf { it > 0 } ?: 30L
            playlistMetadataRetryAt[account] = SystemClock.elapsedRealtime() + seconds.coerceAtMost(300L) * 1000L
        } else if (response?.ok == true) playlistMetadataRetryAt.remove(account)

        val details = if (response?.ok == true) {
            response.json()?.let { ownedPlaylistDetails(it, uri, account) }
        } else {
            partnerOwnedPlaylist(context, uri, account)
        }
        Log.i("Spotify", "playlist owner HTTP=${response?.code ?: 0} confirmed=${details != null} partner=${details?.partner == true}")
        return details.takeIf { Auth.generation == generation && Auth.account(context)?.id == account }
    }

    internal fun playlistMetadataOperation(name: String, description: String, current: OwnedPlaylistDetails): JSONObject? {
        val values = JSONObject()
        val unset = JSONArray()
        if (name != current.name) values.put("name", name)
        if (description != current.description) {
            if (description.isBlank()) unset.put("LIST_DESCRIPTION")
            else values.put("description", description)
        }
        if (values.length() == 0 && unset.length() == 0) return null
        return JSONObject().put("kind", "UPDATE_LIST_ATTRIBUTES").put("updateListAttributes",
            JSONObject().put("newAttributes", JSONObject().put("values", values).put("noValue", unset)))
    }

    suspend fun updateOwnedPlaylist(context: Context, uri: String, name: String, description: String, isPublic: Boolean?): Boolean {
        val title = name.trim()
        if (title.isBlank() || title.length > 100 || description.length > 300) return false
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return false
        val generation = Auth.generation
        val details = ownedPlaylistDetails(context, uri) ?: return false
        if (isPublic != null && (details.isPublic == null || (isPublic && details.collaborative))) return false
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return false
        fun active() = Auth.generation == generation && Auth.account(context)?.id == account
        if (!active()) return false
        if (!details.partner) {
            val body = JSONObject().put("name", title).put("description", description)
            if (isPublic != null && isPublic != details.isPublic) body.put("public", isPublic)
            return Net.putJson("https://api.spotify.com/v1/playlists/${uri.substringAfterLast(':')}", body.toString(),
                mapOf("Authorization" to "Bearer $token"), "playlist/edit")?.ok == true && active()
        }

        val operation = playlistMetadataOperation(title, description, details) ?: return true
        val headers = playlistHeaders(context, token)
        if (!active()) return false
        val response = Net.postJson("$playlistService/playlist/${uri.substringAfterLast(':')}/changes",
            playlistDelta(operation), headers, "playlist/partner-edit")
        if (response?.ok != true) return false
        repeat(3) { attempt ->
            if (attempt > 0) delay(500L * attempt)
            if (!active()) return false
            val updated = partnerOwnedPlaylist(context, uri, account)
            if (active() && updated?.name == title && updated.description == description) return true
        }
        return false
    }

    suspend fun editablePlaylists(context: Context, trackUri: String, offset: Int): Page<PlaylistChoice>? {
        val variables = JSONObject().put("offset", offset).put("limit", 50)
            .put("textFilter", "").put("folderUri", JSONObject.NULL)
            .put("uris", JSONArray().put(trackUri))
        val page = pfPost(context, "editablePlaylists", variables)?.optJSONObject("data")
            ?.optJSONObject("me")?.optJSONObject("editablePlaylists") ?: return null
        val items = page.optJSONArray("items") ?: return null
        val choices = (0 until items.length()).mapNotNull { index ->
            val entry = items.optJSONObject(index) ?: return@mapNotNull null
            val data = entry.optJSONObject("item")?.optJSONObject("data") ?: return@mapNotNull null
            val uri = data.optString("uri")
            if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null
            PlaylistChoice(uri, data.optString("name"), entry.optBoolean("curates"))
        }
        return Page(choices, nextPage(offset, items.length(), 50, page.optInt("totalCount", -1)))
    }

    suspend fun addToPlaylist(context: Context, playlistUri: String, trackUri: String): Boolean {
        val variables = JSONObject().put("playlistUri", playlistUri)
            .put("playlistItemUris", JSONArray().put(trackUri))
            .put("newPosition", JSONObject().put("moveType", "TOP_OF_PLAYLIST").put("fromUid", JSONObject.NULL))
            .put("interactionId", JSONObject.NULL)
        val result = pfPost(context, "addToPlaylist", variables)
        return result.mutationSucceeded("addItemsToPlaylist") || result.mutationSucceeded("AddItemsToPlaylistPayload")
    }

    private fun JSONObject?.mutationSucceeded(key: String): Boolean {
        if (this == null || (optJSONArray("errors")?.length() ?: 0) > 0) return false
        val payload = optJSONObject("data")?.optJSONObject(key) ?: return false
        val type = payload.optString("__typename")
        return type != "NotFound" && !type.endsWith("Error")
    }

    data class CreatedPlaylist(val uri: String, val registered: Boolean)

    suspend fun createPlaylist(context: Context, name: String): CreatedPlaylist? {
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return null
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        val headers = playlistHeaders(context, token)
        val attributes = JSONObject().put("values", JSONObject().put("name", name))
        val update = JSONObject().put("kind", "UPDATE_LIST_ATTRIBUTES")
            .put("updateListAttributes", JSONObject().put("newAttributes", attributes))
        val created = Net.postJson("$playlistService/playlist", JSONObject().put("ops", JSONArray().put(update)).toString(),
            headers, "playlist/create")
        val uri = created?.takeIf { it.ok }?.json()?.optString("uri")
            ?.takeIf { it.startsWith("spotify:playlist:") } ?: return null
        val item = JSONObject().put("uri", uri)
            .put("attributes", JSONObject().put("timestamp", System.currentTimeMillis().toString()))
        val add = JSONObject().put("kind", "ADD")
            .put("add", JSONObject().put("items", JSONArray().put(item)).put("addFirst", true))
        return CreatedPlaylist(uri, changePlaylistRootlist(account, headers, add, "playlist/register"))
    }

    suspend fun removePlaylistFromLibrary(context: Context, uri: String): Boolean {
        if (!uri.startsWith("spotify:playlist:")) return false
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return false
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return false
        val entry = JSONObject().put("uri", uri)
        val remove = JSONObject().put("kind", "REM").put(
            "rem",
            JSONObject().put("items", JSONArray().put(entry)).put("itemsAsKey", true),
        )
        return changePlaylistRootlist(account, playlistHeaders(context, token), remove, "playlist/remove")
    }

    private const val playlistService = "https://spclient.wg.spotify.com/playlist/v2"

    private suspend fun playlistHeaders(context: Context, token: String): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $token",
            "client-token" to ClientTokenManager.get(context),
            "app-platform" to "WebPlayer",
            "spotify-app-version" to Net.WEB_PLAYER_VERSION,
            "User-Agent" to Net.DESKTOP_UA,
            "Accept" to "application/json",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/",
        )

    private fun playlistDelta(operation: JSONObject): String {
        val source = JSONObject().put("source", JSONObject().put("client", "WEBPLAYER"))
        val delta = JSONObject().put("ops", JSONArray().put(operation)).put("info", source)
        return JSONObject().put("deltas", JSONArray().put(delta)).toString()
    }

    private data class PlaylistServiceList(val revision: String, val uris: List<String>)

    private suspend fun playlistServiceList(url: String, headers: Map<String, String>): PlaylistServiceList? {
        val response = Net.get("$url?decorate=revision,length", headers, "playlist/list")
            ?.takeIf { it.ok }?.json() ?: return null
        val revision = response.optString("revision").takeIf { it.isNotBlank() } ?: return null
        val contents = response.optJSONObject("contents") ?: return null
        val items = contents.optJSONArray("items") ?: return null
        if (contents.optBoolean("truncated") || contents.optInt("pos") != 0 ||
            response.optInt("length", -1) != items.length()) return null
        val uris = (0 until items.length()).map { index ->
            items.optJSONObject(index)?.optString("uri")?.takeIf { it.isNotBlank() } ?: return null
        }
        return PlaylistServiceList(revision, uris)
    }

    private fun listOperation(kind: String, parameters: JSONObject): JSONObject =
        JSONObject().put("kind", kind).put(kind.lowercase(java.util.Locale.ROOT), parameters)

    private fun listAdd(index: Int, vararg uris: String): JSONObject = listOperation("ADD",
        JSONObject().put("fromIndex", index).put("items", JSONArray(uris.map { JSONObject().put("uri", it) })))

    private fun listRemove(index: Int, length: Int): JSONObject = listOperation("REM",
        JSONObject().put("fromIndex", index).put("length", length))

    private fun listMove(index: Int, before: Int, length: Int): JSONObject = listOperation("MOV",
        JSONObject().put("fromIndex", index).put("toIndex", before).put("length", length).put("items", JSONArray()))

    private suspend fun editPlaylistServiceList(
        context: Context,
        path: (String) -> String,
        change: (List<String>) -> Pair<List<String>, JSONArray>?,
        verifyAccess: suspend () -> Boolean = { true },
    ): Boolean {
        val generation = Auth.generation
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return false
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return false
        val headers = playlistHeaders(context, token)
        val url = "$playlistService/${path(account)}"
        val previous = playlistServiceList(url, headers) ?: return false
        val (expected, operations) = change(previous.uris) ?: return false
        val source = JSONObject().put("source", JSONObject().put("client", "WEBPLAYER"))
        val body = JSONObject().put("baseRevision", previous.revision)
            .put("deltas", JSONArray().put(JSONObject().put("ops", operations).put("info", source)))
        if (!verifyAccess() || generation != Auth.generation) return false
        if (Net.postJson("$url/changes", body.toString(), headers, "playlist/changeList")?.ok != true) return false
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(250L * attempt)
            if (generation != Auth.generation) return false
            val updated = playlistServiceList(url, headers)
            if (generation == Auth.generation && updated != null && updated.uris == expected && updated.revision != previous.revision) return true
        }
        return false
    }

    private fun folderRange(uris: List<String>, hash: String): IntRange? {
        val start = uris.indexOfFirst { it.startsWith("spotify:start-group:$hash:") }
        val end = uris.indexOf("spotify:end-group:$hash")
        return (start..end).takeIf { start >= 0 && end > start }
    }

    suspend fun changeLibraryFolder(context: Context, folder: String?, parent: String?, name: String?): Boolean {
        val hash = folder?.substringAfterLast(':') ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        return editPlaylistServiceList(context, { "user/${Uri.encode(it)}/rootlist" }, { uris ->
            val range = folderRange(uris, hash)
            val updated = uris.toMutableList()
            val ops = JSONArray()
            when {
                folder == null && name != null -> {
                    val parentRange = parent?.let { folderRange(uris, it.substringAfterLast(':')) }
                    if (parent != null && parentRange == null) return@editPlaylistServiceList null
                    val index = parentRange?.let { it.first + 1 } ?: 0
                    val markers = listOf("spotify:start-group:$hash:${Uri.encode(name)}", "spotify:end-group:$hash")
                    updated.addAll(index, markers)
                    ops.put(listAdd(index, *markers.toTypedArray()))
                }
                range != null && name != null -> {
                    val marker = "spotify:start-group:$hash:${Uri.encode(name)}"
                    if (updated[range.first] == marker) return@editPlaylistServiceList null
                    updated[range.first] = marker
                    ops.put(listRemove(range.first, 1)).put(listAdd(range.first, marker))
                }
                range != null && name == null -> {
                    updated.removeAt(range.last)
                    updated.removeAt(range.first)
                    ops.put(listRemove(range.last, 1)).put(listRemove(range.first, 1))
                }
                else -> return@editPlaylistServiceList null
            }
            updated to ops
        })
    }

    suspend fun editPlaylistTrack(context: Context, playlistUri: String, row: TrackRow, step: Int): Boolean {
        if (row.playlistIndex < 0 || row.playlistUid.isBlank() || step !in -1..1) return false
        val playlistId = playlistUri.removePrefix("spotify:playlist:")
        if (playlistId == playlistUri || !playlistId.matches(Regex("[A-Za-z0-9]{22}"))) return false
        val index = row.playlistIndex
        val changed = editPlaylistServiceList(context, { "playlist/$playlistId" }, { uris ->
            if (uris.getOrNull(index) != row.uri) return@editPlaylistServiceList null
            val destination = index + step
            if (destination !in uris.indices) return@editPlaylistServiceList null
            val updated = uris.toMutableList()
            updated.removeAt(index)
            if (step != 0) updated.add(destination, row.uri)
            val op = if (step == 0) listRemove(index, 1) else listMove(index, if (step < 0) destination else destination + 1, 1)
            updated to JSONArray().put(op)
        }, verifyAccess = {
            val owned = ownedPlaylistDetails(context, playlistUri) != null
            val current = playlist(context, playlistUri, limit = 1, offset = index)?.tracks?.singleOrNull()
            owned && current?.playlistUid == row.playlistUid && current.uri == row.uri
        })
        if (!changed) return false
        val confirmed = playlist(context, playlistUri, limit = 1, offset = index + step)?.tracks?.singleOrNull()?.playlistUid
        return if (step == 0) confirmed != row.playlistUid else confirmed == row.playlistUid
    }

    suspend fun moveLibraryItem(context: Context, uri: String, folder: String?): Boolean =
        editPlaylistServiceList(context, { "user/${Uri.encode(it)}/rootlist" }, { uris ->
            val source = when {
                uri.startsWith("spotify:playlist:") -> {
                    val index = uris.indexOf(uri)
                    (index..index).takeIf { index >= 0 && index == uris.lastIndexOf(uri) }
                }
                uri.contains(":folder:") -> folderRange(uris, uri.substringAfterLast(':'))
                else -> null
            } ?: return@editPlaylistServiceList null
            val before = if (folder == null) 0
            else folderRange(uris, folder.substringAfterLast(':'))?.let { it.first + 1 } ?: return@editPlaylistServiceList null
            if (before in source.first..(source.last + 1)) return@editPlaylistServiceList null
            val moved = uris.slice(source)
            val updated = uris.toMutableList()
            updated.subList(source.first, source.last + 1).clear()
            updated.addAll(if (before > source.last) before - moved.size else before, moved)
            updated to JSONArray().put(listMove(source.first, before, moved.size))
        })

    private suspend fun changePlaylistRootlist(
        account: String,
        headers: Map<String, String>,
        operation: JSONObject,
        tag: String,
    ): Boolean {
        val response = Net.postJson("$playlistService/user/${android.net.Uri.encode(account)}/rootlist/changes",
            playlistDelta(operation), headers, tag)
        return response?.ok == true
    }

    // ---- liked tracks ----

    suspend fun likedTracks(context: Context, limit: Int = 100): List<TrackRow> =
        likedPage(context, 0, limit)?.items.orEmpty()

    suspend fun likedPage(context: Context, offset: Int, limit: Int = 50): Page<TrackRow>? {
        val variables = JSONObject()
            .put("uri", "spotify:collection:tracks")
            .put("offset", offset)
            .put("limit", limit)
        val json = pfPost(context, "fetchLibraryTracks", variables) ?: return null
        val page = json.optJSONObject("data")?.optJSONObject("me")
            ?.optJSONObject("library")?.optJSONObject("tracks") ?: return null
        val rows = mutableListOf<TrackRow>()
        val items = page.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val trackNode = items.optJSONObject(i)?.optJSONObject("track") ?: continue
            trackFrom(trackNode.optJSONObject("data") ?: continue, trackNode.optString("_uri"))?.let(rows::add)
        }
        Log.i("Spotify", "liked -> ${rows.size} tracks")
        val total = page.optInt("totalCount", -1)
        return Page(rows, nextPage(offset, items.length(), limit, total), total.takeIf { it >= 0 })
    }

    // ---- playlist / album / artist ----

    suspend fun playlist(context: Context, uri: String, limit: Int = 200, offset: Int = 0): PlaylistPage? =
        playlistNode(context, uri, limit, offset)?.let { playlistPage(it, limit, offset) }

    private suspend fun playlistNode(context: Context, uri: String, limit: Int, offset: Int): JSONObject? {
        val variables = JSONObject()
            .put("uri", uri)
            .put("offset", offset)
            .put("limit", limit)
            .put("enableWatchFeedEntrypoint", false)
            .put("includeEpisodeContentRatingsV2", true)
        val json = pfPost(context, "fetchPlaylist", variables) ?: return null
        return json.optJSONObject("data")?.optJSONObject("playlistV2")
    }

    /** Parse both music and podcast playlists without substituting unrelated library items. */
    internal fun playlistPage(node: JSONObject, limit: Int, offset: Int): PlaylistPage? {
        val content = node.optJSONObject("content") ?: return null
        val entries = content.optJSONArray("items") ?: return null
        val rows = playlistRows(entries, offset)
        return PlaylistPage(
            owner = node.optJSONObject("ownerV2")?.optJSONObject("data")?.let { it.optString("name").ifBlank { it.optString("username") } }
                ?: node.optJSONObject("owner")?.optString("name").orEmpty(),
            name = node.optString("name"),
            imageUrl = coverOf(node).ifBlank { rows.firstOrNull()?.imageUrl.orEmpty() },
            tracks = rows,
            total = content.optInt("totalCount", -1).takeIf { it >= 0 },
            next = nextPage(offset, entries.length(), limit, content.optInt("totalCount", -1)),
        )
    }

    private fun playlistRows(entries: JSONArray, offset: Int = 0): List<TrackRow> = buildList {
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val data = entry.optJSONObject("itemV2")?.optJSONObject("data")
                ?: entry.optJSONObject("item")?.optJSONObject("itemV2")?.optJSONObject("data")
                ?: entry.optJSONObject("item")?.optJSONObject("v2")?.optJSONObject("data")
                ?: continue
            val row = trackFrom(data) ?: episodeRow(data)?.let { episode ->
                val show = data.optJSONObject("podcastV2")?.optJSONObject("data")
                    ?: data.optJSONObject("podcast")
                TrackRow(
                    uri = episode.uri,
                    id = episode.uri.substringAfterLast(':'),
                    name = episode.name,
                    artists = show?.optString("name").orEmpty()
                        .ifBlank { data.optJSONObject("publisher")?.optString("name").orEmpty() },
                    albumName = show?.optString("name").orEmpty(),
                    albumUri = show?.optString("uri").orEmpty(),
                    imageUrl = episode.imageUrl.ifBlank { show?.let(::coverOf).orEmpty() },
                    durationMs = episode.durationMs,
                )
            }
            if (row != null) add(row.copy(
                addedAt = entry.optJSONObject("addedAt")?.optString("isoString") ?: (entry.opt("addedAt") as? String).orEmpty(),
                playlistUid = (entry.opt("uid") as? String).orEmpty(),
                playlistIndex = offset + i,
            ))
        }
    }

    suspend fun album(context: Context, uri: String, limit: Int = 50, offset: Int = 0): AlbumPage? {
        val variables = JSONObject()
            .put("uri", uri)
            .put("locale", "")
            .put("offset", offset)
            .put("limit", limit)
        val json = pfPost(context, "getAlbum", variables) ?: return null
        val node = json.optJSONObject("data")?.optJSONObject("albumUnion") ?: return null
        val albumCover = coverOf(node)
        val trackPage = node.optJSONObject("tracksV2") ?: return null
        val items = trackPage.optJSONArray("items") ?: return null
        val total = trackPage.optInt("totalCount", -1)
        val tracks = buildList {
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val data = entry.optJSONObject("track")
                    ?: entry.optJSONObject("item")?.optJSONObject("data") ?: continue
                trackFrom(data.optJSONObject("data") ?: data)?.let { track ->
                    add(track.copy(
                        albumUri = track.albumUri.ifBlank { uri },
                        imageUrl = track.imageUrl.ifBlank { albumCover },
                    ))
                }
            }
        }
        val artistNames = node.optJSONObject("artists")?.optJSONArray("items")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("profile")?.optString("name") } }
            ?.joinToString(", ").orEmpty()
        val date = node.optJSONObject("date")
        val isoDate = date?.optString("isoString").orEmpty().substringBefore('T')
        val dateLength = when (date?.optString("precision")?.uppercase()) {
            "DAY" -> 10
            "MONTH" -> 7
            else -> 4
        }
        return AlbumPage(
            name = node.optString("name"),
            imageUrl = albumCover,
            artists = artistNames,
            tracks = tracks,
            total = total.takeIf { it >= 0 },
            next = nextPage(offset, items.length(), limit, total),
            releaseDate = isoDate.take(dateLength),
            label = node.opt("label") as? String ?: "",
        )
    }

    suspend fun artist(context: Context, uri: String): ArtistPage? {
        val variables = JSONObject()
            .put("uri", uri)
            .put("includePrerelease", false)
        val json = pfPost(context, "queryArtistOverview", variables) ?: return null
        val node = json.optJSONObject("data")?.optJSONObject("artistUnion") ?: return null
        return artistPage(node)
    }

    internal fun artistPage(node: JSONObject): ArtistPage {
        val discography = node.optJSONObject("discography")
        val releases = mutableListOf<ArtistRelease>()
        for ((group, type) in listOf("popularReleasesAlbums" to "popular", "albums" to "album", "singles" to "single", "compilations" to "compilation")) {
            val items = discography?.optJSONObject(group)?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val data = entry.optJSONObject("releases")?.optJSONArray("items")?.optJSONObject(0) ?: entry.optJSONObject("data") ?: entry
                val card = cardFrom(data) ?: continue
                val dateNode = data.optJSONObject("date")
                val date = dateNode?.optString("isoString").orEmpty().substringBefore('T').ifBlank {
                    (dateNode?.opt("year") as? Number)?.toInt()?.toString().orEmpty()
                }
                releases += ArtistRelease(card, data.optString("type").lowercase().ifBlank { type }, date, group == "popularReleasesAlbums")
            }
        }
        val profile = node.optJSONObject("profile")
        val stats = node.optJSONObject("stats")
        val tracks = node.optJSONObject("discography")?.optJSONObject("topTracks")?.optJSONArray("items")
        val topTracks = buildList {
            if (tracks != null) for (i in 0 until tracks.length()) {
                val entry = tracks.optJSONObject(i) ?: continue
                val track = entry.optJSONObject("track") ?: entry.optJSONObject("item")?.optJSONObject("data") ?: entry
                trackFrom(track.optJSONObject("data") ?: track)?.let(::add)
            }
        }
        val galleryItems = node.optJSONObject("visuals")?.optJSONObject("gallery")?.optJSONArray("items")
        val gallery = buildList { if (galleryItems != null) for (i in 0 until galleryItems.length()) {
            galleryItems.optJSONObject(i)?.let { image -> coverOf(image).ifBlank { image.optJSONArray("sources")?.let { sources -> coverOf(JSONObject().put("coverArt", JSONObject().put("sources", sources))) }.orEmpty() } }?.takeIf { it.isNotBlank() }?.let(::add)
        } }
        val linkItems = profile?.optJSONObject("externalLinks")?.optJSONArray("items")
        val links = buildList { if (linkItems != null) for (i in 0 until linkItems.length()) {
            val link = linkItems.optJSONObject(i) ?: continue
            val url = link.optString("url")
            if (url.startsWith("https://")) add(link.optString("name") to url)
        } }
        return ArtistPage(
            name = profile?.optString("name").orEmpty(), imageUrl = coverOf(node),
            albums = releases.map { it.card }.distinctBy { it.uri },
            biography = (profile?.optJSONObject("biography")?.opt("text") as? String)
                ?.trim()?.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty(),
            releases = releases.distinctBy { it.card.uri }, topTracks = topTracks,
            monthlyListeners = (stats?.opt("monthlyListeners") as? Number)?.toLong(),
            followers = (stats?.opt("followers") as? Number)?.toLong(),
            verified = profile?.optBoolean("verified", false) == true, gallery = gallery, links = links,
            shelves = artistShelves(node.optJSONObject("relatedContent")),
        )
    }

    private fun artistShelves(content: JSONObject?): List<Section> = buildList {
        for ((group, titleKey) in listOf(
            "featuringV2" to Lang.Key.API_FEATURING,
            "appearsOn" to Lang.Key.API_APPEARS_ON,
            "relatedArtists" to Lang.Key.API_RELATED_ARTISTS,
            "discoveredOnV2" to Lang.Key.API_DISCOVERED_ON,
        )) {
            val items = content?.optJSONObject(group)?.optJSONArray("items") ?: continue
            val cards = buildList {
                for (index in 0 until items.length()) {
                    val entry = items.optJSONObject(index) ?: continue
                    val entity = entry.optJSONObject("releases")?.optJSONArray("items")?.optJSONObject(0)
                        ?: entry.optJSONObject("data") ?: entry
                    cardFrom(entity.optJSONObject("data") ?: entity)?.let(::add)
                }
            }.distinctBy(Card::uri)
            if (cards.isNotEmpty()) add(Section("", cards, group, HomeSectionLayout.SHELF, titleKey))
        }
    }

    /** Partner discography paging with the documented catalog endpoint as fallback. */
    suspend fun artistReleases(context: Context, uri: String, offset: Int = 0): Page<ArtistRelease>? {
        val id = uri.removePrefix("spotify:artist:")
        if (id.length != 22 || !id.all { it.isLetterOrDigit() }) return null
        val partner = pfPost(context, "queryArtistDiscographyAll", JSONObject().put("uri", uri)
            .put("offset", offset).put("limit", 50).put("order", "DATE_DESC"))
            ?.optJSONObject("data")?.optJSONObject("artistUnion")?.optJSONObject("discography")?.optJSONObject("all")
        if (partner != null) return artistReleasePage(partner, offset, 50)?.also {
            Log.i("Spotify", "Artist discography offset=$offset raw=${partner.optJSONArray("items")?.length()} parsed=${it.items.size} total=${it.total} next=${it.next}")
        }
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        val response = Net.get("https://api.spotify.com/v1/artists/$id/albums?include_groups=album,single,compilation&limit=50&offset=$offset",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"), "artist/releases") ?: return null
        if (!response.ok) { Log.i("Spotify", "Artist releases HTTP ${response.code}"); return null }
        val json = response.json() ?: return null
        val items = json.optJSONArray("items") ?: return null
        val releases = buildList { for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val card = Card(item.optString("uri"), item.optString("name"), "", item.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty(), "album")
            if (card.uri.startsWith("spotify:album:") && card.title.isNotBlank()) add(ArtistRelease(card, item.optString("album_type"), item.optString("release_date")))
        } }
        return Page(releases, nextPage(offset, items.length(), 50, json.optInt("total", -1)))
    }

    internal fun artistReleasePage(node: JSONObject, offset: Int, limit: Int): Page<ArtistRelease>? {
        val entries = node.optJSONArray("items") ?: return null
        val releases = buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val data = entry.optJSONObject("releases")?.optJSONArray("items")?.optJSONObject(0) ?: entry
                val card = cardFrom(data)?.takeIf { it.uri.startsWith("spotify:album:") } ?: continue
                val date = data.optJSONObject("date")
                val stamp = date?.optString("isoString").orEmpty().substringBefore('T').ifBlank {
                    (date?.opt("year") as? Number)?.toInt()?.toString().orEmpty()
                }
                add(ArtistRelease(card, data.optString("type").lowercase(), stamp))
            }
        }
        return Page(releases, nextPage(offset, entries.length(), limit, node.optInt("totalCount", -1)), node.optInt("totalCount", -1).takeIf { it >= 0 })
    }

    suspend fun artistRadio(context: Context, uri: String): String? {
        val id = uri.removePrefix("spotify:artist:")
        if (!uri.startsWith("spotify:artist:") || id.length != 22 || !id.all { it.isLetterOrDigit() }) return null
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            ClientTokenManager.get(context).takeIf { it.isNotBlank() }?.let { put("client-token", it) }
        }
        val response = Net.get("https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/$uri?response-format=json", headers, "artist/radio") ?: return null
        if (!response.ok) { Log.i("Spotify", "Artist radio HTTP ${response.code}"); return null }
        val items = response.json()?.optJSONArray("mediaItems") ?: return null
        for (index in 0 until items.length()) {
            val playlist = items.optJSONObject(index)?.optString("uri").orEmpty()
            if (playlist.startsWith("spotify:playlist:") && playlist.substringAfterLast(':').let { it.length == 22 && it.all(Char::isLetterOrDigit) }) return playlist
        }
        return null
    }

    // ---- lyrics ----

    sealed interface LyricsResult {
        data class Available(val lyrics: Lyrics) : LyricsResult
        data object Missing : LyricsResult
        data object Failed : LyricsResult
        data object RateLimited : LyricsResult
    }

    private val lyricsByAccount = LruCache<Pair<String, String>, Lyrics>(24)

    suspend fun lyrics(context: Context, trackUri: String): LyricsResult {
        val id = trackUri.substringAfterLast(':')
        if (!trackUri.startsWith("spotify:track:") || id.isBlank()) return LyricsResult.Missing
        val key = Auth.account(context)?.id?.takeIf { it.isNotBlank() }?.let { it to trackUri }
        key?.let(lyricsByAccount::get)?.let { return LyricsResult.Available(it) }
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id" +
            "?format=json&vocalRemoval=false&market=from_token"
        val headers = buildMap {
            put("Authorization", "Bearer ${bearer(context)}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("User-Agent", Net.DESKTOP_UA)
            ClientTokenManager.get(context).takeIf { it.isNotBlank() }?.let { put("client-token", it) }
        }
        val response = Net.get(url, headers, "lyrics") ?: return LyricsResult.Failed
        if (!response.ok) {
            Log.i("Spotify", "lyrics $id http=${response.code}")
            return when (response.code) {
                404 -> LyricsResult.Missing
                429 -> LyricsResult.RateLimited
                else -> LyricsResult.Failed
            }
        }
        val node = response.json()?.optJSONObject("lyrics") ?: return LyricsResult.Failed
        val linesNode = node.optJSONArray("lines") ?: return LyricsResult.Failed
        val lines = mutableListOf<LyricsLine>()
        for (i in 0 until linesNode.length()) {
            val line = linesNode.optJSONObject(i) ?: continue
            val text = line.optString("words").trim()
            if (text.isNotEmpty()) {
                lines.add(
                    LyricsLine(
                        startMs = line.optString("startTimeMs").toLongOrNull() ?: 0L,
                        text = text,
                    ),
                )
            }
        }
        Log.i("Spotify", "lyrics response: ${lines.size} lines")
        if (lines.isEmpty()) return LyricsResult.Missing
        val lyrics = Lyrics(
            lines = lines,
            synced = node.optString("syncType") == "LINE_SYNCED",
            provider = node.optString("provider"),
        )
        if (key != null) lyricsByAccount.put(key, lyrics)
        return LyricsResult.Available(lyrics)
    }

    // ---- track credits ----

    /**
     * Loads the role-grouped credits of a track (writers, producers,
     * performers…) from the same GraphQL query the web player's credits
     * modal uses. Returns null when the operation is unavailable or the
     * track has no credits data — no invented facts.
     */
    suspend fun credits(context: Context, trackUri: String): TrackCredits? {
        if (!trackUri.startsWith("spotify:track:")) return null
        val variables = JSONObject()
            .put("trackUri", trackUri)
            .put("contributorsLimit", 100)
            .put("contributorsOffset", 0)
        val union = pfPost(context, "queryTrackCreditsGroupedModal", variables)
            ?.optJSONObject("data")?.optJSONObject("trackUnion") ?: return null
        val trackTitle = union.optString("name")

        val groups = mutableListOf<CreditGroup>()
        union.optJSONObject("creditsTrait")?.optJSONObject("contributors")
            ?.optJSONArray("items")?.let { items ->
                val byRole = LinkedHashMap<String, MutableList<String>>()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isEmpty()) continue
                    val role = item.optJSONObject("roleGroup")?.optString("name")
                        ?.trim().orEmpty()
                    byRole.getOrPut(role) { mutableListOf() }.add(name)
                }
                byRole.forEach { (role, names) -> groups.add(CreditGroup(role, names.toList())) }
            }

        val sources = mutableListOf<String>()
        union.optJSONObject("creditsTrait")?.optJSONObject("sourceNames")?.optJSONArray("items")
            ?.let { items ->
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.optString("name")?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let(sources::add)
                }
            }

        if (groups.isEmpty() && sources.isEmpty()) return null
        Log.i("Spotify", "credits '$trackTitle' groups=${groups.size} sources=${sources.size}")
        return TrackCredits(trackTitle, groups, sources)
    }

    /** Resolves a single spotify:track: URI into a full row via decorateContextTracks. */
    suspend fun track(context: Context, uri: String): TrackRow? {
        if (!uri.startsWith("spotify:track:")) return null
        val json = pfPost(context, "decorateContextTracks", JSONObject().put("uris", JSONArray().put(uri)))
        val tracks = json?.optJSONObject("data")?.optJSONArray("tracks")
        if (tracks == null) {
            Log.w("Spotify", "track resolve failed for $uri (see Pathfinder log above)")
            return null
        }
        val row = tracks.optJSONObject(0)?.let(::trackFrom)
        if (row == null) Log.w("Spotify", "track node unparsed for $uri: ${tracks.optJSONObject(0) ?: "null entry"}")
        return row
    }

    // ---- podcasts ----

    /**
     * Saved episodes appear in libraryV3 as tiny "listen-later" playlists —
     * each is fetched and flattened into playable rows.
     */
    suspend fun savedEpisodes(context: Context, limit: Int = 50): List<TrackRow> {
        val variables = JSONObject()
            .put("filters", JSONArray())
            .put("order", JSONObject.NULL)
            .put("textFilter", "")
            .put("features", JSONArray().put("YOUR_EPISODES_V2"))
            .put("limit", 50)
            .put("offset", 0)
            .put("flatten", true)
            .put("expandedFolders", JSONArray())
            .put("folderUri", JSONObject.NULL)
            .put("includeFoldersWhenFlattening", true)
        val json = pfPost(context, "libraryV3", variables) ?: return emptyList()
        val items = json.optJSONObject("data")?.optJSONObject("me")
            ?.optJSONObject("libraryV3")?.optJSONArray("items") ?: return emptyList()
        val uris = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val data = items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data") ?: continue
            if (data.optString("format") == "listen-later" &&
                data.optString("uri").startsWith("spotify:playlist:")
            ) uris.add(data.optString("uri"))
        }
        if (uris.isEmpty()) return emptyList()
        val rows = mutableListOf<TrackRow>()
        // listen-later playlists carry EPISODE rows, not tracks: parse directly.
        for (uri in uris) {
            val json = pfPost(
                context, "fetchPlaylist",
                JSONObject().put("uri", uri).put("offset", 0).put("limit", 50)
                    .put("enableWatchFeedEntrypoint", false)
                    .put("includeEpisodeContentRatingsV2", true),
            ) ?: continue
            val node = json.optJSONObject("data")?.optJSONObject("playlistV2") ?: continue
            val items = node.optJSONObject("content")?.optJSONArray("items") ?: continue
            rows.addAll(playlistRows(items))
            if (rows.distinctBy { it.uri }.size >= limit) break
        }
        Log.i("Spotify", "savedEpisodes -> ${rows.size} from ${uris.size} lists")
        return rows.distinctBy { it.uri }.take(limit.coerceAtLeast(0))
    }

    private const val CHAPTERS_HOST = "https://spclient.wg.spotify.com"

    suspend fun show(context: Context, uri: String, page: ShowDetail? = null): ShowDetail? {
        if (page == null) {
            val union = pfPost(
                context, "queryShowMetadataV2",
                JSONObject().put("uri", uri)
                    .put("includeContentCapabilityTrait", true)
                    .put("includeEpisodeContentRatingsV2", true),
            )?.optJSONObject("data")?.optJSONObject("podcastUnionV2") ?: return null
            val episodes = episodesPage(context, uri, 0)
            return ShowDetail(
                uri = uri,
                name = union.optString("name"),
                publisher = union.optJSONObject("publisher")?.optString("name").orEmpty(),
                description = union.optString("description"),
                imageUrl = coverOf(union),
                mediaType = union.optString("mediaType").ifBlank { "AUDIO" },
                episodes = episodes?.rows.orEmpty(),
                total = episodes?.total ?: 0,
                nextOffset = episodes?.nextOffset,
            )
        }
        // Pagination continuation.
        val next = episodesPage(context, uri, page.nextOffset ?: return page) ?: return page
        return page.copy(
            episodes = page.episodes + next.rows,
            nextOffset = next.nextOffset,
        )
    }

    suspend fun episodesPage(context: Context, uri: String, offset: Int): EpisodePage? {
        val union = pfPost(
            context, "queryPodcastEpisodes",
            JSONObject().put("uri", uri).put("offset", offset).put("limit", 50)
                .put("includeEpisodeContentRatingsV2", true),
        )?.optJSONObject("data")?.optJSONObject("podcastUnionV2") ?: return null
        val node = union.optJSONObject("episodesV2") ?: return null
        val items = node.optJSONArray("items") ?: return null
        val rows = mutableListOf<EpisodeRow>()
        for (i in 0 until items.length()) {
            val data = items.optJSONObject(i)?.optJSONObject("entity")?.optJSONObject("data")
                ?: items.optJSONObject(i)?.optJSONObject("data") ?: continue
            episodeRow(data)?.let(rows::add)
        }
        return EpisodePage(
            rows = rows,
            total = node.optInt("totalCount", rows.size),
            nextOffset = node.optJSONObject("pagingInfo")?.optInt("nextOffset", -1)
                ?.takeIf { it > offset },
        )
    }

    suspend fun episode(context: Context, uri: String): EpisodeDetail? {
        val union = pfPost(
            context, "getEpisodeOrChapter",
            JSONObject().put("uri", uri).put("includeEpisodeContentRatingsV2", true),
        )?.optJSONObject("data")?.optJSONObject("episodeUnionV2") ?: return null
        val show = union.optJSONObject("podcastV2")?.optJSONObject("data")
        val played = union.optJSONObject("playedState")
        val iso = union.optJSONObject("releaseDate")?.optString("isoString").orEmpty()
        return EpisodeDetail(
            uri = uri,
            name = union.optString("name"),
            description = union.optString("description"),
            showName = show?.optString("name").orEmpty(),
            showUri = show?.optString("uri").orEmpty(),
            imageUrl = coverOf(union),
            durationMs = union.optJSONObject("duration")?.optLong("totalMilliseconds", 0L) ?: 0L,
            releaseDate = if (iso.length >= 10) iso.substring(0, 10) else iso,
            playable = union.optJSONObject("playability")?.optBoolean("playable", true) ?: true,
            positionMs = played?.optLong("playPositionMilliseconds", 0L) ?: 0L,
            played = played?.optString("state").orEmpty() == "COMPLETED",
            shareUrl = union.optJSONObject("sharingInfo")?.optString("shareUrl")
                ?: "https://open.spotify.com/episode/${uri.substringAfterLast(':')}",
        )
    }

    internal data class CanvasMedia(val fileId: String?, val url: Uri?)

    internal suspend fun canvas(context: Context, trackUri: String): CanvasMedia? {
        if (!trackUri.startsWith("spotify:track:")) return null
        val generation = Auth.generation
        val response = pfPost(context, "canvas", JSONObject().put("trackUri", trackUri))
        if (generation != Auth.generation) return null
        val data = response?.optJSONObject("data")?.optJSONObject("trackUnion")?.optJSONObject("canvas")
        Log.i("Canvas", "lookup response=${response != null} available=${data != null}")
        if (data == null) return canvasCache(context, trackUri)
        val fileId = data.optString("fileId").takeIf { it.matches(Regex("[a-fA-F0-9]{16,64}")) }
        val url = Uri.parse(data.optString("url")).takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }
        if (fileId == null && url == null) return null
        return CanvasMedia(fileId, url)
    }

    private suspend fun canvasCache(context: Context, trackUri: String): CanvasMedia? {
        val generation = Auth.generation
        val token = bearer(context).takeIf { it.isNotBlank() } ?: return null
        val headers = playlistHeaders(context, token) + ("Accept" to "application/x-protobuf")
        if (generation != Auth.generation) return null
        val response = Net.postBlob("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases",
            CanvasProtocol.request(trackUri), "application/x-protobuf", headers, "canvas/cache")
        val entries = response?.takeIf { it.ok }?.let { CanvasProtocol.entries(it.bytes) }
        Log.i("Canvas", "cache HTTP=${response?.code ?: 0} entries=${entries?.size}")
        if (generation != Auth.generation) return null
        val entry = entries?.firstOrNull { it.uri == trackUri && it.type in 1L..3L } ?: return null
        val url = Uri.parse(entry.url).takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }
        val file = entry.fileId.takeIf { it.matches(Regex("[a-fA-F0-9]{16,64}")) }
        return CanvasMedia(file, url).takeIf { file != null || url != null }
    }

    suspend fun transcript(context: Context, episodeUri: String): Lyrics? {
        val id = episodeUri.removePrefix("spotify:episode:")
        if (id == episodeUri || !id.matches(Regex("[A-Za-z0-9]{22}"))) return null
        val token = bearer(context)
        if (token.isBlank()) return null
        val url = "https://spclient.wg.spotify.com/transcript-read-along/v2/episode/$id" +
            "?format=json&maxSentenceLength=500&excludeCC=true"
        val body = Net.get(url, playlistHeaders(context, token), "episodeTranscript")
            ?.takeIf { it.ok }?.json() ?: return null
        return parseTranscript(body)
    }

    internal fun parseTranscript(body: JSONObject): Lyrics? {
        val sections = body.optJSONArray("section") ?: return null
        val timed = body.optString("timeSyncedStatus").uppercase() in setOf("2", "3", "SYNCED", "SYLLABLE_SYNCED", "SENTENCE_SYNCED")
        val lines = buildList {
            for (index in 0 until sections.length()) {
                val section = sections.optJSONObject(index) ?: continue
                val sentence = section.optJSONObject("text")?.optJSONObject("sentence")
                    ?: section.optJSONObject("fallback")?.optJSONObject("sentence")
                    ?: section.optJSONObject("musicClosedCaption")
                    ?: continue
                val words = sentence.optString("text").trim()
                if (words.isNotEmpty()) add(LyricsLine(section.optLong("startMs", -1), words))
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.let {
            Lyrics(it, timed && it.all { line -> line.startMs >= 0 } && it.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }, "Spotify")
        }
    }

    suspend fun chapters(context: Context, episodeUri: String): List<ChapterSpan> {
        if (!episodeUri.startsWith("spotify:episode:")) return emptyList()
        val token = bearer(context)
        if (token.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(episodeUri, "UTF-8")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json",
            "app-platform" to "WebPlayer",
            "User-Agent" to Net.DESKTOP_UA,
        )
        val list = Net.get(
            "$CHAPTERS_HOST/playlist/v2/list/podcast-chapters/$encoded" +
                "?decorate=revision,length,attributes,timestamp,owner,capabilities",
            headers, "chapters",
        )?.takeIf { it.ok }?.json() ?: return emptyList()
        val items = list.optJSONObject("contents")?.optJSONArray("items") ?: return emptyList()
        val spans = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until items.length()) {
            val attrs = items.optJSONObject(i)?.optJSONObject("attributes")
                ?.optJSONArray("formatAttributes") ?: continue
            var startMs = -1L
            var endMs = -1L
            for (j in 0 until attrs.length()) {
                val attr = attrs.optJSONObject(j) ?: continue
                when (attr.optString("key")) {
                    "chapter.start_position_in_milliseconds" -> startMs = attr.optString("value").toLongOrNull() ?: -1L
                    "chapter.end_position_in_milliseconds" -> endMs = attr.optString("value").toLongOrNull() ?: -1L
                }
            }
            if (startMs >= 0L && endMs > startMs) spans.add(startMs to endMs)
        }
        if (spans.isEmpty()) return emptyList()
        // Chapter titles live in the transcript sections; keep only real titles.
        val episodeId = episodeUri.substringAfterLast(':')
        val titled = mutableListOf<Pair<Long, String>>()
        Net.get(
            "$CHAPTERS_HOST/transcript-read-along/v2/episode/$episodeId?format=json&maxSentenceLength=100&excludeCC=false",
            headers, "transcript",
        )?.takeIf { it.ok }?.json()?.optJSONArray("section")?.let { sections ->
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val title = section.optJSONObject("title")?.optString("title")?.trim().orEmpty()
                if (title.isNotBlank() && !title.matches(Regex("Speaker \\d+"))) {
                    titled.add(section.optLong("startMs", 0L) to title)
                }
            }
        }
        titled.sortBy { it.first }
        return spans.mapIndexed { index, (startMs, endMs) ->
            val title = titled.firstOrNull { (at, _) -> at >= startMs - 2000L && at <= startMs + 30_000L }
                ?.second.orEmpty()
            ChapterSpan(title, startMs, endMs, number = index + 1)
        }
    }

    private fun episodeRow(episode: JSONObject): EpisodeRow? {
        val uri = episode.optString("uri")
        if (!uri.startsWith("spotify:episode:")) return null
        val played = episode.optJSONObject("playedState")
        val iso = episode.optJSONObject("releaseDate")?.optString("isoString").orEmpty()
        return EpisodeRow(
            uri = uri,
            name = episode.optString("name"),
            description = episode.optString("description"),
            releaseDate = if (iso.length >= 10) iso.substring(0, 10) else iso,
            durationMs = episode.optJSONObject("duration")?.optLong("totalMilliseconds", 0L) ?: 0L,
            imageUrl = coverOf(episode),
            played = played?.optString("state").orEmpty() == "COMPLETED",
            positionMs = played?.optLong("playPositionMilliseconds", 0L) ?: 0L,
            playable = episode.optJSONObject("playability")?.optBoolean("playable", true) ?: true,
        )
    }

    // ---- account profile ----

    private var profileRetryAt = 0L

    suspend fun profile(context: Context, forceRefresh: Boolean = false): Auth.Account? {
        val saved = Auth.account(context)
        if (saved == null || forceRefresh) {
            val profile = pfPost(context, "profileAttributes", JSONObject())
                ?.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("profile")
            if (profile != null) {
                val id = profile.optString("username").ifBlank {
                    profile.optString("uri").removePrefix("spotify:user:")
                }.ifBlank { profile.optString("accountId") }
                if (id.isNotBlank()) {
                    Log.i("Spotify", "Profile identity loaded")
                    return Auth.Account(
                        id = id,
                        name = profile.optString("name").ifBlank { id },
                        email = saved?.takeIf { it.id == id }?.email.orEmpty(),
                        imageUrl = profile.optJSONObject("avatar")
                            ?.let { coverOf(JSONObject().put("images", it)) }.orEmpty(),
                        product = saved?.takeIf { it.id == id }?.product.orEmpty(),
                        country = saved?.takeIf { it.id == id }?.country.orEmpty(),
                    )
                }
            }
        }
        return if (forceRefresh) null else saved
    }

    suspend fun enrichProfile(context: Context, profile: Auth.Account): Auth.Account? {
        val account = pfPost(context, "accountAttributes", JSONObject())
            ?.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("account")
            ?: return null
        val product = account.optString("product").ifBlank { profile.product }
        val country = account.optString("country").ifBlank { profile.country }
        if (product.isBlank() && country.isBlank()) return null
        Log.i("Spotify", "Profile account attributes loaded")
        return profile.copy(product = product, country = country)
    }

    suspend fun refreshProfile(context: Context): Auth.Account? {
        repeat(3) {
            val wait = profileRetryAt - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            val account = requestProfile(context)
            if (account != null || profileRetryAt <= SystemClock.elapsedRealtime()) return account
        }
        return null
    }

    private suspend fun requestProfile(context: Context): Auth.Account? {
        val token = bearer(context)
        if (token.isBlank()) return null
        val response = Net.get(
            "https://api.spotify.com/v1/me",
            mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json",
                "User-Agent" to Net.DESKTOP_UA,
            ),
            "me",
        ) ?: return null
        if (response.code == 429) {
            val retryAfterSeconds = response.retryAfterSeconds().takeIf { it > 0 } ?: 60L
            profileRetryAt = SystemClock.elapsedRealtime() + retryAfterSeconds.coerceAtMost(120L) * 1000L
            Log.i("Spotify", "Profile retry after ${retryAfterSeconds}s")
        }
        if (!response.ok) {
            Log.w("Spotify", "Profile request returned HTTP ${response.code}")
            return null
        }
        val json = response.json() ?: return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        val image = json.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
        return Auth.Account(
            id = id,
            name = json.optString("display_name").ifBlank { id },
            email = json.optString("email"),
            imageUrl = image,
            product = json.optString("product"),
            country = json.optString("country"),
        )
    }

    // ---- parsers ----

    private fun cardFrom(data: JSONObject): Card? {
        val uri = data.optString("uri")
        if (uri.isBlank()) return null
        val kind = uri.substringAfter("spotify:").substringBefore(':').ifBlank { "unknown" }
        val title = data.optString("name")
            .ifBlank { data.optJSONObject("profile")?.optString("name").orEmpty() }
        if (title.isBlank() && kind != "collection") return null
        val subtitle = when (kind) {
            "album", "track" -> data.optJSONObject("artists")?.optJSONArray("items")
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull {
                        arr.optJSONObject(it)?.optJSONObject("profile")?.optString("name")
                    }.joinToString(", ")
                }.orEmpty()
            "podcast" -> data.optJSONObject("publisher")?.optString("name").orEmpty()
            else -> ""
        }
        return Card(uri = uri, title = title, subtitle = subtitle, imageUrl = coverOf(data), kind = kind, savedEpisodes = data.optString("format") == "listen-later")
    }

    private fun trackFrom(data: JSONObject, fallbackUri: String = ""): TrackRow? {
        val uri = data.optString("uri").ifBlank { fallbackUri }
        if (!uri.startsWith("spotify:track:")) return null
        val artistItems = data.optJSONObject("artists")?.optJSONArray("items")
        val artistNodes = if (artistItems == null) emptyList() else
            (0 until artistItems.length()).mapNotNull { artistItems.optJSONObject(it) }
        val artists = artistNodes.mapNotNull { entry ->
            val artist = entry.optJSONObject("data") ?: entry
            (artist.optJSONObject("profile")?.optString("name") ?: artist.optString("name")).takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val artistUris = artistNodes.mapNotNull { entry ->
            val artist = entry.optJSONObject("data") ?: entry
            artist.optString("uri").ifBlank { entry.optString("uri") }.takeIf { it.startsWith("spotify:artist:") }
        }.distinct()
        val albumNode = data.optJSONObject("albumOfTrack") ?: data.optJSONObject("album")
        return TrackRow(
            uri = uri,
            id = uri.substringAfterLast(':'),
            name = data.optString("name"),
            artists = artists,
            albumName = albumNode?.optString("name").orEmpty(),
            albumUri = albumNode?.optString("uri").orEmpty(),
            imageUrl = coverOf(data).ifBlank { albumNode?.let(::coverOf).orEmpty() },
            artistUri = artistUris.firstOrNull().orEmpty(),
            artistUris = artistUris,
            durationMs = data.optJSONObject("trackDuration")?.optLong("totalMilliseconds")
                ?: data.optJSONObject("duration")
                ?.optJSONObject("milliseconds")?.optLong("total", 0L)
                ?: data.optJSONObject("duration")?.optLong("totalMilliseconds", 0L)
                ?: data.optLong("durationMs", 0L),
        )
    }

    internal fun coverOf(node: JSONObject): String {
        val images = node.optJSONObject("images")
        val visuals = node.optJSONObject("visuals")
        val sources = listOfNotNull(
            visuals?.optJSONObject("avatarImage")?.optJSONArray("sources"),
            node.optJSONObject("avatarImage")?.optJSONArray("sources"),
            node.optJSONObject("coverArt")?.optJSONArray("sources"),
            images?.optJSONArray("sources"),
            images?.optJSONArray("items")?.optJSONObject(0)?.optJSONArray("sources"),
            node.optJSONArray("images"),
        )
        for (group in sources) {
            // Sources are ordered smallest-first; pick the widest variant so
            // covers stay sharp when scaled up. Entries without a width are
            // treated as full-size and win.
            var best: JSONObject? = null
            var bestWidth = -1L
            for (index in 0 until group.length()) {
                val source = group.optJSONObject(index) ?: continue
                val url = source.opt("url") as? String ?: continue
                if (!url.startsWith("https://")) continue
                // width может отсутствовать (null) — такие источники обычно
                // полноразмерные и должны выигрывать выбор.
                val width = when (val raw = source.opt("width")) {
                    null, JSONObject.NULL -> Long.MAX_VALUE / 2
                    is Number -> raw.toLong()
                    else -> raw.toString().toLongOrNull() ?: -1L
                }
                if (width > bestWidth) {
                    best = source
                    bestWidth = width
                }
            }
            best?.let { return it.optString("url") }
        }
        return ""
    }
}
