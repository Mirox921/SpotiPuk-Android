package com.project.puk.player

import com.project.puk.core.Lang

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.project.puk.R
import com.project.puk.api.ArtistRelease
import com.project.puk.api.Card
import com.project.puk.api.Spotify
import com.project.puk.api.TrackRow
import com.project.puk.core.Auth
import com.project.puk.core.Log
import com.project.puk.jam.JamController
import com.project.puk.ui.asQueueItem
import com.project.puk.ui.toQueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure media id scheme for the Auto browse tree. Kept free of Android
 * dependencies so the parsing can be unit-tested on the JVM.
 *
 * - `auto:root`              root
 * - `auto:liked`             «Любимые треки» container
 * - `auto:library`           library cards container
 * - `auto:card|<uri>`        playlist/album/show container
 * - `auto:t|<containerId>|<index>|<uri>`  playable track inside a container;
 *   the container id may itself contain ':' but never '|'
 * - `auto:s|<uri>`           playable search-result hit (plays as a single item)
 */
object AutoMediaIds {
    const val ROOT = "auto:root"
    const val LIKED = "auto:liked"
    const val RECENT = "auto:recent"
    const val TOP = "auto:top"
    const val EPISODES = "auto:episodes"
    const val FOR_YOU = "auto:foryou"
    const val LIBRARY = "auto:library"
    const val LIKED_CONTAINER_ID = "liked"
    const val RECENT_CONTAINER_ID = "recent"
    const val TOP_CONTAINER_ID = "top"
    const val EPISODES_CONTAINER_ID = "episodes"
    private const val CARD_PREFIX = "auto:card|"
    private const val ARTIST_PREFIX = "auto:artist|"
    private const val RADIO_PREFIX = "auto:radio|"
    private const val TRACK_PREFIX = "auto:t|"
    private const val SEARCH_PREFIX = "auto:s|"
    private const val CARD_CONTAINER_PREFIX = "card:"
    private const val ARTIST_CONTAINER_PREFIX = "artist:"

    sealed interface Target {
        data object Root : Target
        data object Liked : Target
        data object Recent : Target
        data object Top : Target
        data object Episodes : Target
        data object ForYou : Target
        data object Library : Target
        data class CardContainer(val uri: String) : Target
        data class ArtistContainer(val uri: String) : Target
        data class RadioPlaylist(val uri: String) : Target
        data class Track(val containerId: String, val index: Int, val uri: String) : Target
        data class SearchHit(val uri: String) : Target
    }

    fun card(uri: String) = "$CARD_PREFIX$uri"
    fun artist(uri: String) = "$ARTIST_PREFIX$uri"
    fun radio(uri: String) = "$RADIO_PREFIX$uri"
    fun track(containerId: String, index: Int, uri: String) = "$TRACK_PREFIX$containerId|$index|$uri"
    fun search(uri: String) = "$SEARCH_PREFIX$uri"

    /** Queue context the UI passes for playlist/album/show playback, null for liked. */
    fun containerQueueContext(containerId: String): String? =
        containerId.takeIf { it.startsWith(CARD_CONTAINER_PREFIX) }?.removePrefix(CARD_CONTAINER_PREFIX)

    fun parse(id: String): Target? = when {
        id == ROOT -> Target.Root
        id == LIKED -> Target.Liked
        id == RECENT -> Target.Recent
        id == TOP -> Target.Top
        id == EPISODES -> Target.Episodes
        id == FOR_YOU -> Target.ForYou
        id == LIBRARY -> Target.Library
        id.startsWith(CARD_PREFIX) -> Target.CardContainer(id.removePrefix(CARD_PREFIX))
        id.startsWith(ARTIST_PREFIX) -> Target.ArtistContainer(id.removePrefix(ARTIST_PREFIX)).takeIf { it.uri.isNotEmpty() }
        id.startsWith(RADIO_PREFIX) -> Target.RadioPlaylist(id.removePrefix(RADIO_PREFIX)).takeIf { it.uri.isNotEmpty() }
        id.startsWith(TRACK_PREFIX) -> {
            val parts = id.removePrefix(TRACK_PREFIX).split('|')
            val index = parts.getOrNull(1)?.toIntOrNull()
            val uri = parts.getOrNull(2)
            if (parts[0].isEmpty() || index == null || index < 0 || uri.isNullOrEmpty()) null
            else Target.Track(parts[0], index, uri)
        }
        id.startsWith(SEARCH_PREFIX) -> Target.SearchHit(id.removePrefix(SEARCH_PREFIX)).takeIf { it.uri.isNotEmpty() }
        else -> null
    }
}

/**
 * Android Auto / Assistant browse tree on top of the existing playback
 * session. Playback is routed through the engine queue via [PukPlayer.playQueue]
 * and resolved back as a tagged description item so [PukForwardingPlayer]
 * keeps treating it like the session-resumption path (it must not stomp the
 * single-item ExoPlayer with a plain setMediaItems).
 */
@UnstableApi
class AutoLibrarySessionCallback(
    private val context: Context,
    private val puk: PukPlayer,
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        private const val TAG = "AutoLibrary"
        private val LIKED_TITLE get() = Lang.t(Lang.Key.JAM_LIKED_TRACKS)
        private val RECENT_TITLE get() = Lang.t(Lang.Key.AUTO_RECENT)
        private val TOP_TITLE get() = Lang.t(Lang.Key.AUTO_TOP_WEEK)
        private val EPISODES_TITLE get() = Lang.t(Lang.Key.AUTO_YOUR_EPISODES)
        private val FOR_YOU_TITLE get() = Lang.t(Lang.Key.AUTO_FOR_YOU)
        private val LIBRARY_TITLE get() = Lang.t(Lang.Key.AUTO_MY_LIBRARY)
        const val CUSTOM_ACTION_LIKE = "puk.LIKE"
        const val CUSTOM_ACTION_SLEEP = "puk.SLEEP"
        const val CUSTOM_ACTION_SHUFFLE = "puk.SHUFFLE"
        private const val CACHE_TTL_MS = 5 * 60_000L
        private const val CACHE_MAX_ENTRIES = 12
        private const val AUTO_PAGE_LIMIT = 100
        private const val LIKED_MAX_ITEMS = 300
        private const val COLLECTION_PAGE_SIZE = 50
        private const val SHOW_MAX_EPISODES = 100
        private const val VOICE_RESULT_LIMIT = 25
        private const val ARTIST_MAX_RELEASES = 150
        private const val FOR_YOU_MAX_ITEMS = 30
        private val LIBRARY_KINDS = setOf("playlist", "album", "artist", "podcast", "audiobook")

        /** Legacy car UIs expose these as PlaybackStateCompat custom actions. */
        fun carLayout(liked: Boolean?, sleepMinutes: Int?, shuffle: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setSessionCommand(SessionCommand(CUSTOM_ACTION_SHUFFLE, Bundle.EMPTY))
                .setDisplayName(Lang.t(if (shuffle) Lang.Key.AUTO_SHUFFLE_OFF else Lang.Key.PLAYER_SHUFFLE_ON))
                .build(),
            CommandButton.Builder()
                .setSessionCommand(SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY))
                .setIconResId(if (liked == true) R.drawable.ember_ctx_like_track_filled else R.drawable.ember_ctx_like_track)
                .setDisplayName(Lang.t(Lang.Key.AUTO_LIKE_BUTTON))
                .build(),
            CommandButton.Builder()
                .setSessionCommand(SessionCommand(CUSTOM_ACTION_SLEEP, Bundle.EMPTY))
                .setIconResId(R.drawable.ember_ctx_sleep_timer)
                .setDisplayName(if (sleepMinutes != null) Lang.t(Lang.Key.AUTO_SLEEP_ON, sleepMinutes) else Lang.t(Lang.Key.PLAYER_SLEEP_TIMER))
                .build(),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One container entry: queue item + podcast resume position (music is 0). */
    private data class AutoEntry(val q: PukPlayer.QueueItem, val resumeMs: Long = 0L)

    private data class CachedContainer(val title: String, val entries: List<AutoEntry>, val fetchedAtMs: Long)

    private val containerCache = LinkedHashMap<String, CachedContainer>()
    private var containerAccount = Auth.generation
    private var playRequest = 0L

    private data class Selection(
        val items: List<PukPlayer.QueueItem>,
        val index: Int = 0,
        val positionMs: Long = 0L,
        val context: String? = null,
    )

    fun release() {
        scope.cancel()
    }

    // ---- custom layout buttons (like / sleep timer) ----

    /** Liked state of the current track, cached until the next toggle. */
    private var likedCache: Pair<String, Boolean>? = null

    override fun onConnect(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY))
            .add(SessionCommand(CUSTOM_ACTION_SLEEP, Bundle.EMPTY))
            .add(SessionCommand(CUSTOM_ACTION_SHUFFLE, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.accept(sessionCommands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        command: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        scope.launch {
            try {
                when (command.customAction) {
                    CUSTOM_ACTION_LIKE -> toggleLike()
                    CUSTOM_ACTION_SLEEP -> cycleSleepTimer()
                    CUSTOM_ACTION_SHUFFLE -> {
                        if (hasRemotePlayback() || JamController.activeController?.isActive == true ||
                            puk.state.value.queue.size < 2
                        ) {
                            result.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                            return@launch
                        }
                        puk.setShuffle(if (puk.state.value.shuffle == PukPlayer.Shuffle.OFF) PukPlayer.Shuffle.ON else PukPlayer.Shuffle.OFF)
                    }
                    else -> {
                        result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                        return@launch
                    }
                }
                session.setCustomLayout(currentLayout())
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (e: CancellationException) {
                result.setException(e)
            } catch (e: Exception) {
                Log.w(TAG, "custom ${command.customAction} failed: ${e.message}")
                result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return result
    }

    fun currentLayout(): ImmutableList<CommandButton> {
        val state = puk.state.value
        val liked = likedCache?.takeIf { it.first == state.queue.getOrNull(state.index)?.uri }?.second
        return carLayout(liked, state.sleepTimerMinutes, state.shuffle != PukPlayer.Shuffle.OFF)
    }

    private suspend fun toggleLike() {
        val state = puk.state.value
        val uri = state.queue.getOrNull(state.index)?.uri?.takeIf { it.startsWith("spotify:track:") }
            ?: throw IllegalStateException("Nothing playing to like")
        val cached = likedCache?.takeIf { it.first == uri }?.second
        val saved = cached ?: withContext(Dispatchers.IO) { Spotify.isSaved(context, uri) }
        if (!withContext(Dispatchers.IO) { Spotify.setSaved(context, uri, saved != true) }) {
            throw IllegalStateException("Like request failed")
        }
        likedCache = uri to (saved != true)
    }

    private suspend fun cycleSleepTimer() {
        val next = when (val current = puk.state.value.sleepTimerMinutes) {
            null -> 15
            15 -> 30
            30 -> 60
            else -> null
        }
        puk.setSleepTimer(next)
    }

    // ---- browse tree ----

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(browsableItem(AutoMediaIds.ROOT, "SpotiPuk"), params),
        )

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(children(parentId, page, pageSize)), params))
            } catch (e: CancellationException) {
                result.setException(e)
            } catch (e: Exception) {
                Log.w(TAG, "children $parentId failed: ${e.message}")
                result.setException(e)
            }
        }
        return result
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val result = SettableFuture.create<LibraryResult<MediaItem>>()
        scope.launch {
            try {
                val item: MediaItem? = when (val target = AutoMediaIds.parse(mediaId)) {
                    AutoMediaIds.Target.Root -> browsableItem(AutoMediaIds.ROOT, "SpotiPuk")
                    AutoMediaIds.Target.Liked -> browsableItem(AutoMediaIds.LIKED, LIKED_TITLE, subtitle = Lang.t(Lang.Key.AUTO_LIKED_SUBTITLE))
                    AutoMediaIds.Target.Recent -> browsableItem(AutoMediaIds.RECENT, RECENT_TITLE, subtitle = Lang.t(Lang.Key.AUTO_RECENT_SUBTITLE))
                    AutoMediaIds.Target.Top -> browsableItem(AutoMediaIds.TOP, TOP_TITLE, subtitle = Lang.t(Lang.Key.AUTO_TOP_SUBTITLE))
                    AutoMediaIds.Target.Episodes -> browsableItem(AutoMediaIds.EPISODES, EPISODES_TITLE, subtitle = Lang.t(Lang.Key.AUTO_SAVED_PODCASTS))
                    AutoMediaIds.Target.ForYou -> browsableItem(AutoMediaIds.FOR_YOU, FOR_YOU_TITLE, subtitle = Lang.t(Lang.Key.AUTO_FOR_YOU_SUBTITLE))
                    AutoMediaIds.Target.Library -> browsableItem(AutoMediaIds.LIBRARY, LIBRARY_TITLE, subtitle = Lang.t(Lang.Key.AUTO_LIBRARY_SUBTITLE))
                    is AutoMediaIds.Target.CardContainer -> cardItem(
                        containerTitle(target.uri) ?: "",
                        emptyCard(uri = target.uri),
                    )
                    is AutoMediaIds.Target.ArtistContainer -> browsableItem(
                        AutoMediaIds.artist(target.uri), Lang.t(Lang.Key.AUTO_ARTIST),
                        grid = true,
                        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
                        subtitle = Lang.t(Lang.Key.ART_RELEASES),
                    )
                    is AutoMediaIds.Target.Track -> containerEntries(target.containerId)
                        ?.entries?.getOrNull(target.index)
                        ?.takeIf { it.q.uri == target.uri }
                        ?.let { playableTrack(it, target.containerId, target.index) }
                        ?: trackItemFromUri(target.uri, mediaId)
                    is AutoMediaIds.Target.SearchHit -> trackItemFromUri(target.uri)
                    is AutoMediaIds.Target.RadioPlaylist -> MediaItem.Builder()
                        .setMediaId(AutoMediaIds.radio(target.uri))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(Lang.t(Lang.Key.AUTO_RADIO))
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                .build(),
                        )
                        .build()
                    null -> null
                }
                result.set(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            } catch (e: CancellationException) {
                result.setException(e)
            } catch (e: Exception) {
                Log.w(TAG, "item $mediaId failed: ${e.message}")
                result.setException(e)
            }
        }
        return result
    }

    override fun onSubscribe(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid())

    override fun onUnsubscribe(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid())

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val result = SettableFuture.create<LibraryResult<Void>>()
        scope.launch {
            try {
                val first = withContext(Dispatchers.IO) {
                    Spotify.searchPage(context, query, Spotify.SearchType.TRACKS, 0)
                }
                // MediaBrowserCompat waits for this callback before fetching the results.
                session.notifySearchResultChanged(browser, query, first?.total ?: first?.tracks?.size ?: 0, params)
                result.set(if (first != null) LibraryResult.ofVoid(params) else LibraryResult.ofError(SessionError.ERROR_IO))
            } catch (e: CancellationException) {
                result.setException(e)
            } catch (e: Exception) {
                session.notifySearchResultChanged(browser, query, 0, params)
                result.set(LibraryResult.ofError(SessionError.ERROR_IO))
            }
        }
        return result
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                val size = pageSize.coerceIn(1, AUTO_PAGE_LIMIT)
                val offset = page.toLong() * size
                if (offset !in 0..Int.MAX_VALUE.toLong() - size) {
                    result.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    return@launch
                }
                val items = searchTracks(query, offset.toInt(), size).map { playableSearch(it.toQueueItem()) }
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            } catch (e: CancellationException) {
                result.setException(e)
            } catch (e: Exception) {
                Log.w(TAG, "search '$query' failed: ${e.message}")
                result.setException(e)
            }
        }
        return result
    }

    private suspend fun searchTracks(query: String, offset: Int, size: Int): List<TrackRow> = withContext(Dispatchers.IO) {
        buildList {
            var cursor = offset
            val end = offset + size
            while (cursor < end) {
                val page = Spotify.searchPage(context, query, Spotify.SearchType.TRACKS, cursor, end - cursor)
                    ?: break
                addAll(page.tracks)
                cursor = page.next?.takeIf { it > cursor } ?: break
            }
        }.distinctBy(TrackRow::uri)
    }

    private suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        if (page < 0) return emptyList()
        return when (val target = AutoMediaIds.parse(parentId)) {
            AutoMediaIds.Target.Root -> {
                if (page > 0) return emptyList()
                buildList {
                    add(browsableItem(AutoMediaIds.LIKED, LIKED_TITLE, subtitle = Lang.t(Lang.Key.AUTO_LIKED_SUBTITLE)))
                    // Empty sections would render as broken "no media" tabs — hide them.
                    if (topListening().isNotEmpty()) {
                        add(browsableItem(AutoMediaIds.TOP, TOP_TITLE, subtitle = Lang.t(Lang.Key.AUTO_TOP_SUBTITLE)))
                    }
                    if (recentListening().isNotEmpty()) {
                        add(browsableItem(AutoMediaIds.RECENT, RECENT_TITLE, subtitle = Lang.t(Lang.Key.AUTO_RECENT_SUBTITLE)))
                    }
                    if (savedEpisodes().isNotEmpty()) {
                        add(browsableItem(AutoMediaIds.EPISODES, EPISODES_TITLE, subtitle = Lang.t(Lang.Key.AUTO_SAVED_PODCASTS)))
                    }
                    if (!forYouFetched() || forYouCached().isNotEmpty()) {
                        add(browsableItem(AutoMediaIds.FOR_YOU, FOR_YOU_TITLE, subtitle = Lang.t(Lang.Key.AUTO_FOR_YOU_SUBTITLE)))
                    }
                    add(browsableItem(AutoMediaIds.LIBRARY, LIBRARY_TITLE, subtitle = Lang.t(Lang.Key.AUTO_LIBRARY_SUBTITLE)))
                }
            }
            AutoMediaIds.Target.Library -> libraryCards(page, pageSize.coerceIn(1, AUTO_PAGE_LIMIT))
            AutoMediaIds.Target.Liked -> containerTracks(AutoMediaIds.LIKED_CONTAINER_ID, page, pageSize)
            AutoMediaIds.Target.Recent -> containerTracks(AutoMediaIds.RECENT_CONTAINER_ID, page, pageSize)
            AutoMediaIds.Target.Top -> containerTracks(AutoMediaIds.TOP_CONTAINER_ID, page, pageSize)
            AutoMediaIds.Target.Episodes -> containerTracks(AutoMediaIds.EPISODES_CONTAINER_ID, page, pageSize)
            AutoMediaIds.Target.ForYou -> forYouPage(page, pageSize.coerceIn(1, AUTO_PAGE_LIMIT))
            is AutoMediaIds.Target.CardContainer -> containerTracks(
                containerId(target.uri), page, pageSize,
            )
            is AutoMediaIds.Target.ArtistContainer -> artistReleasesPage(target.uri, page, pageSize.coerceIn(1, AUTO_PAGE_LIMIT))
            else -> emptyList()
        }
    }

    private suspend fun containerTracks(containerId: String, page: Int, pageSize: Int): List<MediaItem> {
        val container = containerEntries(containerId) ?: return emptyList()
        val size = pageSize.coerceIn(1, AUTO_PAGE_LIMIT)
        val from = page * size
        if (from >= container.entries.size) return emptyList()
        return container.entries.drop(from).take(size).mapIndexed { i, entry ->
            playableTrack(entry, containerId, from + i)
        }
    }

    private suspend fun libraryCards(page: Int, size: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val cards = Spotify.library(context, offset = page * size, limit = size)?.items.orEmpty()
            .filter { it.kind in LIBRARY_KINDS }
        cards.map { card ->
            when (card.kind) {
                "artist" -> {
                    artistInfo[card.uri] = card.title to card.imageUrl
                    browsableItem(
                        AutoMediaIds.artist(card.uri), card.title,
                        artwork = card.imageUrl,
                        grid = true,
                        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
                        subtitle = card.subtitle,
                    )
                }
                else -> browsableItem(
                    AutoMediaIds.card(card.uri), card.title,
                    artwork = card.imageUrl,
                    grid = true,
                    mediaType = when (card.kind) {
                        "album" -> MediaMetadata.MEDIA_TYPE_ALBUM
                        "podcast" -> MediaMetadata.MEDIA_TYPE_PODCAST
                        "audiobook" -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
                        else -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                    },
                    subtitle = card.subtitle,
                )
            }
        }
    }

    private data class ArtistDiscography(
        val radioUri: String?,
        val releases: List<ArtistRelease>,
        val fetchedAtMs: Long,
    )

    /** Artist display info (name, artwork) seeded when the library lists the artist. */
    private val artistInfo = mutableMapOf<String, Pair<String, String>>()
    private val artistCache = LinkedHashMap<String, ArtistDiscography>()

    private suspend fun artistDiscography(uri: String): ArtistDiscography? {
        val now = SystemClock.elapsedRealtime()
        artistCache[uri]?.takeIf { now - it.fetchedAtMs < CACHE_TTL_MS }?.also { artistCache[uri] = it }?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val items = mutableListOf<ArtistRelease>()
            var offset = 0
            while (offset < ARTIST_MAX_RELEASES) {
                val pageResult = Spotify.artistReleases(context, uri, offset = offset) ?: break
                items += pageResult.items
                val next = pageResult.next ?: break
                if (next <= offset) break
                offset = next
            }
            items to Spotify.artistRadio(context, uri)
        } ?: return null
        val disc = ArtistDiscography(loaded.second, loaded.first, now)
        artistCache[uri] = disc
        while (artistCache.size > CACHE_MAX_ENTRIES) {
            val eldest = artistCache.entries.iterator()
            eldest.next()
            eldest.remove()
            break
        }
        return disc
    }

    private suspend fun artistReleasesPage(uri: String, page: Int, size: Int): List<MediaItem> {
        val size = size.coerceAtLeast(1)
        val disc = artistDiscography(uri)
        val releaseItems = disc?.releases.orEmpty().drop(page * size).take(size).map { release ->
            browsableItem(
                AutoMediaIds.card(release.card.uri), release.card.title,
                artwork = release.card.imageUrl,
                grid = true,
                mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                subtitle = listOf(release.type, release.date).filter { it.isNotBlank() }.joinToString(" • "),
            )
        }
        val radio = if (page == 0) disc?.radioUri?.let { radioItem(uri, it) } else null
        return if (radio != null) listOf(radio) + releaseItems else releaseItems
    }

    private fun radioItem(artistUri: String, playlistUri: String): MediaItem {
        val info = artistInfo[artistUri]
        return MediaItem.Builder()
            .setMediaId(AutoMediaIds.radio(playlistUri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Lang.t(Lang.Key.AUTO_RADIO_OF, info?.first ?: Lang.t(Lang.Key.AUTO_RADIO_DEFAULT)))
                    .setArtist("Spotify")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .apply {
                        if (!info?.second.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(info!!.second))
                    }
                    .withStyleExtras(browsable = false, grid = false)
                    .build(),
            )
            .build()
    }

    private fun containerId(cardUri: String) = "card:$cardUri"

    private fun emptyCard(uri: String) = Card(
        uri = uri, title = "", subtitle = "", imageUrl = "",
        kind = when {
            uri.contains(":playlist:") -> "playlist"
            uri.contains(":album:") -> "album"
            else -> "podcast"
        },
    )

    private suspend fun containerTitle(cardUri: String): String? =
        cachedContainer(containerId(cardUri))?.title

    // ---- container loading (shared by browse and playback) ----

    private fun cachedContainer(containerId: String): CachedContainer? {
        if (containerAccount != Auth.generation) return null
        val now = SystemClock.elapsedRealtime()
        val cached = containerCache[containerId]?.takeIf { now - it.fetchedAtMs < CACHE_TTL_MS }
        if (cached != null) containerCache[containerId] = cached
        return cached
    }

    private suspend fun containerEntries(containerId: String): CachedContainer? {
        val generation = Auth.generation
        if (generation != containerAccount) {
            containerCache.clear()
            containerAccount = generation
        }
        cachedContainer(containerId)?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            when {
                containerId == AutoMediaIds.LIKED_CONTAINER_ID -> loadLiked()
                containerId == AutoMediaIds.RECENT_CONTAINER_ID -> loadRecent()
                containerId == AutoMediaIds.TOP_CONTAINER_ID -> loadTop()
                containerId == AutoMediaIds.EPISODES_CONTAINER_ID -> loadEpisodes()
                containerId.startsWith("card:") -> loadCard(containerId.removePrefix("card:"))
                else -> null
            }
        } ?: return null
        if (generation != Auth.generation) return null
        return CachedContainer(loaded.first, loaded.second, SystemClock.elapsedRealtime())
            .also { cached ->
                containerCache[containerId] = cached
                while (containerCache.size > CACHE_MAX_ENTRIES) {
                    val eldest = containerCache.entries.iterator()
                    eldest.next()
                    eldest.remove()
                    break
                }
            }
    }

    private suspend fun recentListening() = puk.recentListening(Auth.account(context)?.id.orEmpty())

    private suspend fun savedEpisodes() = withContext(Dispatchers.IO) {
        Spotify.savedEpisodes(context).take(SHOW_MAX_EPISODES)
    }

    private suspend fun topListening() = puk.listeningWeek(
        Auth.account(context)?.id.orEmpty(),
        System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L,
        System.currentTimeMillis(),
    ).tracks.filter { it.uri.startsWith("spotify:track:") || it.uri.startsWith("spotify:episode:") }

    /** Local listening history («Недавно»): last distinct tracks/episodes of this account. */
    private suspend fun loadRecent(): Pair<String, List<AutoEntry>> {
        val cards = recentListening()
        return RECENT_TITLE to cards.map { card ->
            AutoEntry(PukPlayer.QueueItem(
                uri = card.uri,
                title = card.title,
                artist = card.subtitle,
                albumUri = "",
                imageUrl = card.imageUrl,
                durationMs = 0L,
            ))
        }
    }

    /** Most-listened tracks of the current week («Топ недели»). */
    private suspend fun loadTop(): Pair<String, List<AutoEntry>> {
        val ranks = topListening().take(25)
        return TOP_TITLE to ranks.map { rank ->
            AutoEntry(PukPlayer.QueueItem(
                uri = rank.uri,
                title = rank.title,
                artist = rank.detail,
                albumUri = "",
                imageUrl = rank.imageUrl,
                durationMs = 0L,
            ))
        }
    }

    /** Saved podcast episodes («Ваши эпизоды»). */
    private suspend fun loadEpisodes(): Pair<String, List<AutoEntry>> {
        val rows = savedEpisodes()
        return EPISODES_TITLE to rows.map { row ->
            AutoEntry(row.toQueueItem())
        }
    }

    // ---- «Для вас» (Spotify Home recommendations) ----

    private var forYouCache: Pair<Long, List<Card>>? = null

    private fun forYouCached(): List<Card> {
        val now = SystemClock.elapsedRealtime()
        return forYouCache?.takeIf { now - it.first < CACHE_TTL_MS }?.second.orEmpty()
    }

    private fun forYouFetched(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return forYouCache != null && now - forYouCache!!.first < CACHE_TTL_MS
    }

    private suspend fun fetchForYou(): List<Card> {
        val feed = withContext(Dispatchers.IO) { Spotify.home(context) } ?: run {
            forYouCache = SystemClock.elapsedRealtime() to emptyList()
            return emptyList()
        }
        val cards = feed.sections.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.kind == "playlist" || it.kind == "album" }
            .distinctBy { it.uri }
            .take(FOR_YOU_MAX_ITEMS)
            .toList()
        forYouCache = SystemClock.elapsedRealtime() to cards
        return cards
    }

    private suspend fun forYouPage(page: Int, size: Int): List<MediaItem> {
        if (!forYouFetched()) fetchForYou()
        return forYouCached().drop(page * size).take(size).map { card ->
            browsableItem(
                AutoMediaIds.card(card.uri), card.title,
                artwork = card.imageUrl,
                grid = true,
                mediaType = if (card.kind == "album") MediaMetadata.MEDIA_TYPE_ALBUM else MediaMetadata.MEDIA_TYPE_PLAYLIST,
                subtitle = card.subtitle,
            )
        }
    }

    private suspend fun loadLiked(): Pair<String, List<AutoEntry>> {
        val rows = mutableListOf<TrackRow>()
        var offset = 0
        while (offset < LIKED_MAX_ITEMS) {
            val page = Spotify.likedPage(context, offset, AUTO_PAGE_LIMIT) ?: break
            rows += page.items
            val next = page.next ?: break
            if (next <= offset) break
            offset = next
        }
        return LIKED_TITLE to rows.map { AutoEntry(it.toQueueItem()) }
    }

    private suspend fun loadCard(cardUri: String): Pair<String, List<AutoEntry>>? = when {
        cardUri.startsWith("spotify:playlist:") || cardUri.startsWith("spotify:album:") -> loadMusicCollection(cardUri)
        else -> Spotify.show(context, cardUri)?.let { show ->
            show.name to show.episodes.filter { it.playable }.take(SHOW_MAX_EPISODES).map { ep ->
                AutoEntry(ep.asQueueItem(show.name, show.uri), if (ep.played) 0L else ep.positionMs)
            }
        }
    }

    private suspend fun loadMusicCollection(uri: String): Pair<String, List<AutoEntry>>? {
        val entries = mutableListOf<AutoEntry>()
        var title = ""
        var offset = 0
        while (true) {
            val (name, tracks, next) = if (uri.startsWith("spotify:playlist:")) {
                val page = Spotify.playlist(context, uri, limit = COLLECTION_PAGE_SIZE, offset = offset) ?: return null
                Triple(page.name, page.tracks, page.next)
            } else {
                val page = Spotify.album(context, uri, limit = COLLECTION_PAGE_SIZE, offset = offset) ?: return null
                Triple(page.name, page.tracks, page.next)
            }
            if (offset == 0) title = name
            entries += tracks.map { AutoEntry(it.toQueueItem()) }
            offset = next?.takeIf { it > offset } ?: break
        }
        return title to entries
    }

    // ---- media item builders ----

    private fun MediaMetadata.Builder.withStyleExtras(browsable: Boolean, grid: Boolean): MediaMetadata.Builder {
        val extras = Bundle()
        val style = if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        extras.putInt(
            if (browsable) MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE else MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            style,
        )
        setExtras(extras)
        return this
    }

    private fun browsableItem(
        id: String,
        title: String,
        artwork: String = "",
        grid: Boolean = false,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        subtitle: String = "",
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { id.substringAfterLast(':') })
                    .apply {
                        val text = subtitle.trim()
                        if (text.isNotEmpty()) setSubtitle(text)
                        if (artwork.isNotBlank()) setArtworkUri(android.net.Uri.parse(artwork))
                    }
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .withStyleExtras(browsable = true, grid = grid)
                    .build(),
            )
            .build()

    private fun cardItem(title: String, card: Card): MediaItem = browsableItem(
        AutoMediaIds.card(card.uri), title,
        artwork = card.imageUrl,
        grid = true,
        mediaType = when (card.kind) {
            "album" -> MediaMetadata.MEDIA_TYPE_ALBUM
            "podcast" -> MediaMetadata.MEDIA_TYPE_PODCAST
            else -> MediaMetadata.MEDIA_TYPE_PLAYLIST
        },
        subtitle = card.subtitle,
    )

    private fun playableTrack(entry: AutoEntry, containerId: String, index: Int): MediaItem {
        val q = entry.q
        return MediaItem.Builder()
            .setMediaId(AutoMediaIds.track(containerId, index, q.uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(q.title)
                    .setArtist(q.artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(if (entry.resumeMs > 0L || q.albumUri.startsWith("spotify:show") || q.uri.startsWith("spotify:episode:")) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        if (q.imageUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(q.imageUrl))
                    }
                    .withStyleExtras(browsable = false, grid = false)
                    .build(),
            )
            .build()
    }

    /** Fallback when the container cache no longer covers the tapped index. */
    private fun trackItemFromUri(uri: String, mediaId: String = AutoMediaIds.search(uri)): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(uri.substringAfterLast(':'))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    private fun playableSearch(q: PukPlayer.QueueItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(AutoMediaIds.search(q.uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(q.title)
                    .setArtist(q.artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        if (q.imageUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(q.imageUrl))
                    }
                    .build(),
            )
            .build()

    // ---- playback resolution ----

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val first = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
        // Voice search from Assistant / the car arrives here (media3 maps
        // playFromSearch to a request item with requestMetadata.searchQuery —
        // there is no separate onPlayFromSearch callback).
        val voiceQuery = first?.requestMetadata?.searchQuery?.trim().orEmpty()
        val parsed = first?.let { AutoMediaIds.parse(it.mediaId) }
        if ((parsed !is AutoMediaIds.Target.Track && parsed !is AutoMediaIds.Target.SearchHit &&
                parsed !is AutoMediaIds.Target.RadioPlaylist && parsed !is AutoMediaIds.Target.CardContainer) && voiceQuery.isEmpty()
        ) {
            // Leave foreign controllers (Bluetooth reconnect, Assistant resume)
            // on the framework path.
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val request = ++playRequest
        val generation = Auth.generation
        val job = scope.launch {
            try {
                val item = checkNotNull(first)
                if (!Auth.isLoggedIn(context)) {
                    result.setException(IllegalStateException("Not logged in"))
                    return@launch
                }
                if (JamController.activeController?.isActive == true) {
                    result.setException(IllegalStateException("Jam session owns playback"))
                    return@launch
                }
                if (hasRemotePlayback()) {
                    result.setException(IllegalStateException("Playback owned by another device"))
                    return@launch
                }
                val selection = when {
                    voiceQuery.isNotEmpty() -> selectVoiceQuery(voiceQuery)
                    parsed is AutoMediaIds.Target.Track -> selectContainerTrack(parsed, item)
                    parsed is AutoMediaIds.Target.SearchHit -> selectSingle(parsed.uri, item)
                    parsed is AutoMediaIds.Target.RadioPlaylist -> selectCollection(parsed.uri)
                    parsed is AutoMediaIds.Target.CardContainer -> selectCollection(parsed.uri)
                    else -> throw IllegalStateException("Unsupported media id")
                }
                check(request == playRequest && generation == Auth.generation &&
                    JamController.activeController?.isActive != true && !hasRemotePlayback()) {
                    "Playback selection no longer current"
                }
                val startItem = selection.items[selection.index]
                puk.playQueue(selection.items, selection.index, positionMs = selection.positionMs, queueContext = selection.context)
                val state = puk.state.value
                val current = state.queue.getOrNull(state.index)
                if (current == null || current.uri != startItem.uri) {
                    throw IllegalStateException("Queue did not adopt the Auto selection")
                }
                // Tagged like the resumption description: PukForwardingPlayer
                // recognizes it, leaves the engine-owned ExoPlayer alone and
                // routes the follow-up play() into puk.resume().
                val description = MediaItem.Builder()
                    .setMediaId(startItem.uri)
                    .setUri(startItem.uri)
                    .setTag(ResumeLocalTrack(current, state.loadId, state.index))
                    .build()
                result.set(MediaSession.MediaItemsWithStartPosition(listOf(description), 0, C.TIME_UNSET))
            } catch (e: CancellationException) {
                result.setException(e)
            } catch (e: Exception) {
                Log.w(TAG, "auto play failed: ${e.message}")
                result.setException(e)
            }
        }
        job.invokeOnCompletion { cause -> if (cause != null && !result.isDone) result.setException(cause) }
        return result
    }

    /**
     * Voice query («Ok Google, play Metallica»): play the top track hits as
     * the engine queue so next/previous keep working inside the car.
     */
    private suspend fun selectVoiceQuery(term: String): Selection {
        val rows = searchTracks(term, 0, VOICE_RESULT_LIMIT)
        check(rows.isNotEmpty()) { "No matching tracks" }
        return Selection(rows.map { it.toQueueItem() })
    }

    private suspend fun selectCollection(uri: String): Selection {
        val collection = checkNotNull(containerEntries(containerId(uri))) { "Collection unavailable" }
        check(collection.entries.isNotEmpty()) { "Collection is empty" }
        return Selection(collection.entries.map { it.q }, positionMs = collection.entries.first().resumeMs, context = uri)
    }

    private suspend fun selectContainerTrack(target: AutoMediaIds.Target.Track, description: MediaItem): Selection {
        val container = containerEntries(target.containerId)
        val queueContext = AutoMediaIds.containerQueueContext(target.containerId)
        if (container != null) {
            val entries = container.entries
            val index = target.index.takeIf { entries.getOrNull(it)?.q?.uri == target.uri }
                ?: entries.indexOfFirst { it.q.uri == target.uri }
            val entry = entries.getOrNull(index)
            if (entry != null) return Selection(entries.map { it.q }, index, entry.resumeMs, queueContext)
        }
        return selectSingle(target.uri, description, queueContext)
    }

    private fun selectSingle(uri: String, description: MediaItem, queueContext: String? = null): Selection {
        val metadata = description.mediaMetadata
        val item = PukPlayer.QueueItem(
            uri = uri,
            title = metadata.title?.toString().orEmpty().ifBlank { uri.substringAfterLast(':') },
            artist = metadata.artist?.toString().orEmpty(),
            albumUri = "",
            imageUrl = metadata.artworkUri?.toString().orEmpty(),
            durationMs = 0L,
        )
        return Selection(listOf(item), context = queueContext)
    }

    // ---- session resumption (moved verbatim from PlaybackService) ----

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val job = scope.launch {
            puk.restoringSession.first { !it }
            val state = puk.state.value
            val item = state.queue.getOrNull(state.index)
            if (!Auth.isLoggedIn(context) || item == null || hasRemotePlayback()) {
                result.setException(IllegalStateException("No local playback to resume"))
                return@launch
            }
            val description = MediaItem.Builder()
                .setMediaId(item.uri)
                .setUri(item.uri)
                .setTag(ResumeLocalTrack(item, state.loadId, state.index))
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(item.title).setArtist(item.artist)
                    .apply {
                        if (item.imageUrl.isNotBlank()) {
                            setArtworkUri(android.net.Uri.parse(item.imageUrl))
                        }
                    }.build())
                .build()
            result.set(MediaSession.MediaItemsWithStartPosition(listOf(description), 0, state.positionMs))
        }
        job.invokeOnCompletion { cause -> if (cause != null) result.setException(cause) }
        return result
    }
}
