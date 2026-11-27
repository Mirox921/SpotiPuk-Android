package com.project.puk.player

import com.project.puk.core.Lang

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.datasource.DefaultDataSource
import com.project.puk.core.Log
import com.project.puk.api.Spotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.project.puk.connect.Connect
import kotlinx.coroutines.withContext
import com.project.puk.core.Auth
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The native playback engine. One persistent ExoPlayer instance lives for the
 * whole app; tracks are swapped via setMediaSource. Encrypted MP4 audio gets a
 * Widevine DRM session whose licenses are requested from Spotify's audio
 * license endpoint. The UI and MediaSessionService observe [state].
 */
@UnstableApi
class PukPlayer(private val context: Context) {

    data class QueueItem(
        val uri: String,
        val title: String,
        val artist: String,
        val albumUri: String,
        val imageUrl: String,
        val durationMs: Long,
        val artistUri: String = "",
        val artistUris: List<String> = emptyList(),
    )

    enum class Shuffle { OFF, ON, SMART }

    enum class Repeat { OFF, QUEUE, TRACK }

    data class State(
        val queue: List<QueueItem> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
        val licenseReady: Boolean = false,
        val loadId: Long = 0,
        val seekRevision: Long = 0,
        val waitingForLicenseRetry: Boolean = false,
        val shuffle: Shuffle = Shuffle.OFF,
        val loadingSuggestions: Boolean = false,
        val suggestedUris: Set<String> = emptySet(),
        val repeat: Repeat = Repeat.OFF,
        val sleepTimerMinutes: Int? = null,
        val queueContext: String? = null,
        val autoAdvance: Boolean = true,
        val smoothTransitions: Boolean = false,
        val speed: Float = 1f,
        val isVideo: Boolean = false,
        val videoAvailable: Boolean? = null,
        val videoQualities: List<Int> = emptyList(),
        val videoQuality: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val prefs = com.project.puk.core.AppPrefs.prefs(context)
    val state = MutableStateFlow(State(
        autoAdvance = prefs.getBoolean("auto_advance", true),
        smoothTransitions = prefs.getBoolean("smooth_transitions", false),
    ))
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val restoringSession = MutableStateFlow(true)

    /** Look-ahead resolved sources keyed by track uri. */
    private val resolved = LinkedHashMap<String, SourceResolver.AudioSource>()
    private var resolutionAccount: String? = null

    private var resolverJob: Job? = null
    private data class ManifestPrefetch(
        val uri: String,
        val result: Deferred<SourceResolver.TrackManifest?>,
        val startNow: CompletableDeferred<Unit>,
    )

    private var manifestPrefetch: ManifestPrefetch? = null
    private var licenseRetryJob: Job? = null
    private var suggestionsJob: Job? = null
    private var ownershipTimeoutJob: Job? = null
    private var shuffleRevision = 0L
    private var originalQueue: List<QueueItem>? = null
    private var nextLoadId = 0L
    private var progressRunnable: Runnable? = null
    private var activeSource: MediaSource? = null
    private var playWhenLicensed = false
    private val sleepTimer = SleepTimer(
        schedule = { task, delayMs -> main.postDelayed(task, delayMs) },
        cancel = { task -> main.removeCallbacks(task) },
        onChanged = { minutes -> state.value = state.value.copy(sleepTimerMinutes = minutes) },
        onExpired = { pause() },
    )

    private val listeningStats = ListeningStats(context)
    private val statsWrites = Channel<() -> Unit>(Channel.UNLIMITED)
    private val listeningTracker = ListeningTracker { slice ->
        statsWrites.trySend { listeningStats.record(slice) }
    }
    private var listeningAccount: String? = null

    internal fun statsEnabled(account: String): Boolean = prefs.getBoolean("listening_stats_$account", true)

    internal fun setStatsEnabled(account: String, enabled: Boolean) = onMain {
        listeningTracker.end()
        prefs.edit().putBoolean("listening_stats_$account", enabled).apply()
        listeningAccount = account.takeIf { enabled && it == Auth.account(context)?.id }
        if (player.isPlaying) sampleListening()
    }

    internal suspend fun listeningWeek(account: String, start: Long, end: Long): ListeningWeek =
        withContext(Dispatchers.IO) { listeningStats.week(account, start, end) }

    internal suspend fun recentListening(account: String): List<com.project.puk.api.Card> =
        withContext(Dispatchers.IO) { listeningStats.recent(account) }

    internal suspend fun listeningHistory(account: String): List<ListeningHistoryEntry> =
        withContext(Dispatchers.IO) { listeningStats.history(account) }

    internal suspend fun clearListeningStats(account: String) {
        withContext(Dispatchers.Main.immediate) {
            listeningTracker.end()
            val cleared = CompletableDeferred<Unit>()
            statsWrites.send {
                runCatching { listeningStats.clear(account) }.fold(
                    { cleared.complete(Unit) }, { cleared.completeExceptionally(it) },
                )
            }
            cleared.await()
        }
    }

    private fun sampleListening(positionMs: Long = player.currentPosition, playing: Boolean = player.isPlaying) {
        val snapshot = state.value
        val track = current()?.takeIf { it.uri == player.currentMediaItem?.mediaId }
        listeningTracker.sample(
            listeningAccount, snapshot.loadId, track, snapshot.queueContext,
            positionMs, SystemClock.elapsedRealtime(), System.currentTimeMillis(), playing,
        )
    }

    private data class ArtistPolicy(val generation: Long, val refreshAt: Long, val blocked: Set<String>)
    private var artistPolicy: ArtistPolicy? = null

    internal fun updateArtistPolicy(uri: String, blocked: Boolean) = onMain {
        val policy = artistPolicy?.takeIf { it.generation == Auth.generation }
        val current = policy ?: ArtistPolicy(Auth.generation, 0L, emptySet())
        artistPolicy = current.copy(blocked = if (blocked) current.blocked + uri else current.blocked - uri)
        if (blocked && !jamSuppressesCheckpoints && !waitingForConnectOwnership && current()?.hasArtist(setOf(uri)) == true) {
            prepareCurrent(autoplay = state.value.playing, direction = 1)
        }
    }

    private suspend fun activeArtistPolicy(): Set<String> {
        val generation = Auth.generation
        val now = SystemClock.elapsedRealtime()
        val cached = artistPolicy?.takeIf { it.generation == generation }
        val local = Spotify.localBlockedArtists(context)
        if (cached != null && now < cached.refreshAt) return cached.blocked + local
        val blocked = withTimeoutOrNull(2_000) { Spotify.blockedArtists(context) }
        if (generation != Auth.generation) return emptySet()
        val latest = artistPolicy?.takeIf { it.generation == generation }
        if (latest !== cached && latest != null) return latest.blocked + Spotify.localBlockedArtists(context)
        artistPolicy = ArtistPolicy(generation, now + if (blocked == null) 10_000 else 60_000, blocked ?: cached?.blocked.orEmpty())
        return artistPolicy?.blocked.orEmpty() + local
    }

    private suspend fun blockedByArtist(item: QueueItem, blocked: Set<String>): Boolean {
        if (blocked.isEmpty() || !item.uri.startsWith("spotify:track:")) return false
        if (item.hasArtist(blocked)) return true
        if (item.artistUris.isNotEmpty()) return false
        return Spotify.track(context, item.uri)?.artistUris.orEmpty().any(blocked::contains)
    }

    private fun QueueItem.hasArtist(artists: Set<String>): Boolean = artistUri in artists || artistUris.any(artists::contains)

    private val sessionStore = PlaybackSessionStore(context)
    private val checkpoints = Channel<PlaybackSessionSnapshot?>(Channel.CONFLATED)
    private var checkpointState: State? = null
    private var checkpointAt = 0L
    private var waitingForConnectOwnership = false

    init {
        scope.launch(Dispatchers.Main.immediate) {
            combine(state, Connect.clusterActiveDevice) { snapshot, device ->
                Triple(snapshot.queue.getOrNull(snapshot.index), snapshot.playing, device)
            }.distinctUntilChanged().collect {
                PlaybackWidget.render(context, state.value)
            }
        }
        scope.launch {
            for (write in statsWrites) {
                runCatching(write).onFailure { Log.i("Player", "Listening history write failed") }
            }
        }
        scope.launch {
            for (snapshot in checkpoints) {
                runCatching { sessionStore.write(snapshot?.encode()) }
                    .onFailure { Log.i("Player", "Playback checkpoint could not be saved") }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            val initial = state.value
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    val account = Auth.account(context)?.id.orEmpty()
                    if (account.isBlank() || !Auth.isLoggedIn(context)) null
                    else sessionStore.read()?.let { PlaybackSessionSnapshot.decode(it, account) }
                }.getOrNull()
            }
            if (snapshot != null && state.value === initial) {
                val currentItems = snapshot.queue.groupBy { it }.mapValues { ArrayDeque(it.value) }
                originalQueue = snapshot.originalQueue?.map { item ->
                    currentItems[item]?.removeFirstOrNull() ?: item
                }
                val metadataDuration = snapshot.queue[snapshot.index].durationMs
                val duration = metadataDuration.takeIf { it > 0 } ?: snapshot.durationMs
                val position = if (duration > 0) snapshot.positionMs.coerceAtMost(duration) else snapshot.positionMs
                state.value = initial.copy(
                    queue = snapshot.queue, index = snapshot.index, positionMs = position,
                    durationMs = duration, shuffle = snapshot.shuffle, repeat = snapshot.repeat,
                    suggestedUris = snapshot.suggestions, queueContext = snapshot.queueContext,
                )
                checkpointState = state.value
                checkpointAt = SystemClock.elapsedRealtime()
            }
            restoringSession.value = false
        }
        scope.launch(Dispatchers.Main.immediate) {
            state.collect { current ->
                val last = checkpointState
                if (current.index in current.queue.indices && (
                    last == null || current.queue !== last.queue || current.index != last.index ||
                        current.playing != last.playing || current.seekRevision != last.seekRevision ||
                        current.durationMs != last.durationMs ||
                        current.shuffle != last.shuffle || current.repeat != last.repeat ||
                        (current.playing && SystemClock.elapsedRealtime() - checkpointAt >= 10_000L)
                    )
                ) savePlaybackCheckpoint()
            }
        }
    }

    /**
     * While a Jam session is active the periodic checkpoints must not
     * overwrite the user's personal queue snapshot (owner correction #2):
     * the personal state was captured right before joining and is restored
     * on leave via [restorePersonalQueue].
     */
    @Volatile
    var jamSuppressesCheckpoints: Boolean = false

    /**
     * Transport bridge for Jam (the server is the single source of truth for
     * the shared queue): while a session is active, user-initiated transport
     * commands — system media buttons, notification, player screen, headset —
     * are offered to the Jam server instead of being applied locally.
     * Returning true consumes the command; the server broadcast then drives
     * the engine through [jamApplyingServerCommand]-guarded paths.
     */
    interface TransportDelegate {
        fun onPlay(): Boolean
        fun onPause(): Boolean
        fun onNext(): Boolean
        fun onPrevious(): Boolean
        fun onSeek(positionMs: Long): Boolean
        /** Queue-row tap: the server picks the item by uri. */
        fun onPlayUri(uri: String): Boolean
    }

    @Volatile
    var jamTransportDelegate: TransportDelegate? = null

    /** True while the Jam controller applies a server-issued command to the engine. */
    @Volatile
    var jamApplyingServerCommand: Boolean = false

    /** Captures the current personal state right before joining a Jam. */
    fun capturePersonalQueue() = onMain {
        jamSuppressesCheckpoints = false
        savePlaybackCheckpoint()
        jamSuppressesCheckpoints = true
    }

    /**
     * Re-applies the saved personal queue after leaving Jam, paused.
     * `force` (user-confirmed restore) also stops Jam audio that kept
     * playing after the session ended.
     */
    fun restorePersonalQueue(force: Boolean = false) = onMain {
        jamSuppressesCheckpoints = false
        if (!force && state.value.queue.isNotEmpty() && state.value.playing) {
            // Something personal is already playing locally: leave it alone.
            return@onMain
        }
        if (force) player.pause()
        scope.launch(Dispatchers.Main.immediate) {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    val account = Auth.account(context)?.id.orEmpty()
                    if (account.isBlank() || !Auth.isLoggedIn(context)) null
                    else sessionStore.read()?.let { PlaybackSessionSnapshot.decode(it, account) }
                }.getOrNull()
            } ?: return@launch
            val current = state.value
            if (current.queue.isNotEmpty() && current.index in current.queue.indices &&
                current.queue[current.index].uri == snapshot.queue.getOrNull(snapshot.index)?.uri
            ) {
                // Same content is already in place: only stop the noise.
                player.pause()
                return@launch
            }
            val duration = snapshot.queue.getOrNull(snapshot.index)?.durationMs?.takeIf { it > 0 }
                ?: snapshot.durationMs
            state.value = current.copy(
                queue = snapshot.queue, index = snapshot.index,
                positionMs = snapshot.positionMs, durationMs = duration,
                shuffle = snapshot.shuffle, repeat = snapshot.repeat,
                suggestedUris = snapshot.suggestions, queueContext = snapshot.queueContext,
                playing = false, buffering = false,
            )
        }
    }

    fun savePlaybackCheckpoint() = onMain {
        if (waitingForConnectOwnership || jamSuppressesCheckpoints) return@onMain
        val current = state.value
        if (current.index !in current.queue.indices) return@onMain
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return@onMain
        val currentItem = current.queue[current.index]
        val position = if (activeSource != null && player.currentMediaItem?.mediaId == currentItem.uri) {
            player.currentPosition.coerceAtLeast(0)
        } else current.positionMs
        checkpoints.trySend(
            PlaybackSessionSnapshot(
                account, current.queue, current.index, position, current.durationMs,
                current.shuffle, current.repeat, originalQueue, current.suggestedUris, current.playing,
                queueContext = current.queueContext,
            ),
        )
        checkpointState = current
        checkpointAt = SystemClock.elapsedRealtime()
    }

    /** The single ExoPlayer the MediaSession binds to. Created lazily on main. */
    val player: ExoPlayer by lazy { buildPlayer() }

    // ---- queue commands ----

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    fun current(): QueueItem? = state.value.queue.getOrNull(state.value.index)

    /**
     * Live engine position (audio-clock extrapolated, ±10 ms), not the
     * 500 ms-stale [State.positionMs] snapshot the progress loop publishes.
     * Main thread only. Null when nothing matching the Jam queue is loaded.
     */
    fun livePositionMs(): Long? {
        if (activeSource == null) return null
        if (player.currentMediaItem?.mediaId != current()?.uri) return null
        return player.currentPosition
    }

    /** Media id of the actually loaded source, null when nothing is attached. */
    fun loadedSourceUri(): String? =
        activeSource?.let { player.currentMediaItem?.mediaId }

    /** True when the source for this uri is already resolved (no new license round needed). */
    fun hasResolvedSource(uri: String): Boolean = resolved.containsKey(uri)

    /**
     * Jam drift correction (spec section 5): raw playback rate change WITHOUT
     * touching the user's speed preference — [State.speed] stays authoritative
     * and the next attach resets to it. Pitch is preserved by the time-stretch.
     */
    fun applyDriftSpeed(totalSpeed: Float) = onMain {
        if (activeSource == null) return@onMain
        player.playbackParameters = player.playbackParameters.withSpeed(totalSpeed)
    }

    fun playQueue(
        items: List<QueueItem>, startIndex: Int, autoplay: Boolean = true, positionMs: Long = 0L,
        queueContext: String? = null,
        awaitConnectOwnership: Boolean = false,
    ) = onMain {
        if (startIndex !in items.indices) return@onMain
        if (awaitConnectOwnership) savePlaybackCheckpoint()
        waitingForConnectOwnership = awaitConnectOwnership
        ownershipTimeoutJob?.cancel()
        if (awaitConnectOwnership) {
            // Ownership confirmation normally arrives within ~20s; a timeout
            // keeps checkpoints (and play/pause) alive if Connect is lost.
            ownershipTimeoutJob = scope.launch {
                delay(25_000)
                if (waitingForConnectOwnership) {
                    waitingForConnectOwnership = false
                    savePlaybackCheckpoint()
                }
            }
        }
        discardManifestPrefetch()
        resolved.clear()
        suggestionsJob?.cancel()
        shuffleRevision++
        originalQueue = null
        val previous = state.value
        state.value = State(
            queue = items, index = startIndex, buffering = true, positionMs = positionMs,
            durationMs = items[startIndex].durationMs,
            repeat = previous.repeat, sleepTimerMinutes = previous.sleepTimerMinutes,
            autoAdvance = previous.autoAdvance, smoothTransitions = previous.smoothTransitions, queueContext = queueContext,
        )
        prepareCurrent(autoplay, positionMs)
    }

    /**
     * Jam-only live queue sync (reorder/add/remove broadcasts): replaces the
     * visible queue and current index WITHOUT re-preparing the loaded source
     * — ExoPlayer holds a single item, so a plain state swap keeps the audio
     * playing through any queue change. Invalid while another context owns
     * the player.
     */
    fun applyJamQueue(items: List<QueueItem>, currentIndex: Int) = onMain {
        val current = state.value
        if (current.queueContext?.startsWith("jam:") != true) return@onMain
        if (items.isEmpty()) return@onMain
        val safeIndex = currentIndex.coerceIn(0, items.lastIndex)
        if (current.queue === items) return@onMain
        state.value = current.copy(queue = items, index = safeIndex)
    }

    /**
     * Fills in artwork for Jam queue entries pushed without it (guest apps
     * may send only uri/title): updates the visible queue without touching
     * the loaded item or playback state.
     */
    fun updateQueueItemArtwork(uri: String, imageUrl: String) = onMain {
        if (imageUrl.isBlank()) return@onMain
        val current = state.value
        if (current.queue.none { it.uri == uri && it.imageUrl.isBlank() }) return@onMain
        state.value = current.copy(queue = current.queue.map {
            if (it.uri == uri && it.imageUrl.isBlank()) it.copy(imageUrl = imageUrl) else it
        })
    }

    internal fun confirmConnectOwnership(loadId: Long, resumePlayback: Boolean) = onMain {
        ownershipTimeoutJob?.cancel()
        if (!waitingForConnectOwnership || state.value.loadId != loadId) return@onMain
        waitingForConnectOwnership = false
        savePlaybackCheckpoint()
        if (resumePlayback) resume(loadId)
    }

    fun playItem(item: QueueItem) = onMain {
        val queue = state.value.queue
        val existing = queue.indexOfFirst { it.uri == item.uri }
        if (existing >= 0) {
            skipTo(existing)
        } else {
            playQueue(listOf(item), 0)
        }
    }

    fun enqueue(item: QueueItem) = enqueue(listOf(item))

    fun enqueue(items: List<QueueItem>) = onMain {
        val blocked = artistPolicy?.takeIf { it.generation == Auth.generation }?.blocked.orEmpty() + Spotify.localBlockedArtists(context)
        val accepted = if (jamSuppressesCheckpoints) items else items.filterNot { it.hasArtist(blocked) }
        if (accepted.isEmpty()) return@onMain
        val snapshot = state.value
        if (snapshot.queue.isEmpty()) {
            playQueue(accepted, 0, autoplay = false)
        } else {
            originalQueue = originalQueue?.plus(accepted)
            state.value = snapshot.copy(queue = snapshot.queue + accepted)
        }
    }

    fun setAutoAdvance(enabled: Boolean) = onMain {
        prefs.edit().putBoolean("auto_advance", enabled).apply()
        state.value = state.value.copy(autoAdvance = enabled)
        if (!enabled) discardManifestPrefetch()
    }

    /** Manual queue edits apply to the normal local order. Shuffle retains its original ordering. */
    fun setSmoothTransitions(enabled: Boolean) = onMain {
        prefs.edit().putBoolean("smooth_transitions", enabled).apply()
        state.value = state.value.copy(smoothTransitions = enabled)
        if (enabled && player.isPlaying) updateTransitionEnvelope()
        else clearTransitionEnvelope()
    }

    private var fadeIntoTrack = false
    private var volumeBeforeFade: Float? = null
    private val fadeTick = Runnable { updateTransitionEnvelope() }

    private fun clearTransitionEnvelope() {
        main.removeCallbacks(fadeTick)
        volumeBeforeFade?.let { player.volume = it }
        volumeBeforeFade = null
        fadeIntoTrack = false
    }

    private fun transitionAllowed(snapshot: State): Boolean =
        snapshot.smoothTransitions && !jamSuppressesCheckpoints && !waitingForConnectOwnership &&
            !snapshot.isVideo && current()?.uri?.startsWith("spotify:track:") == true &&
            Connect.clusterActiveDevice.value.let { it.isBlank() || Connect.isLocalDevice(it) }

    private fun updateTransitionEnvelope() {
        main.removeCallbacks(fadeTick)
        val snapshot = state.value
        if (!transitionAllowed(snapshot)) {
            clearTransitionEnvelope()
            return
        }
        if (!player.isPlaying) return
        val position = player.currentPosition
        val duration = player.duration
        val following = snapshot.queue.getOrNull(snapshot.index + 1)
            ?: snapshot.queue.firstOrNull()?.takeIf { snapshot.repeat == Repeat.QUEUE }
        val fadeOut = snapshot.autoAdvance && snapshot.repeat != Repeat.TRACK && following?.uri?.startsWith("spotify:track:") == true
        val gain = trackTransitionGain(position, duration, fadeIntoTrack, fadeOut)
        if (gain < 1f) {
            val normalVolume = volumeBeforeFade ?: player.volume.also { volumeBeforeFade = it }
            player.volume = normalVolume * gain
        } else if (volumeBeforeFade != null) {
            player.volume = checkNotNull(volumeBeforeFade)
            volumeBeforeFade = null
        }
        if (position >= TRACK_FADE_MS) fadeIntoTrack = false
        val nearFade = fadeIntoTrack || (fadeOut && duration > 0 && duration - position < TRACK_FADE_MS + 500)
        main.postDelayed(fadeTick, if (nearFade) 40L else 400L)
    }

    fun canEditQueue(): Boolean = !waitingForConnectOwnership && originalQueue == null &&
        state.value.shuffle == Shuffle.OFF && !state.value.loadingSuggestions

    fun enqueueNext(item: QueueItem) = onMain {
        if (!canEditQueue() || item.hasArtist(artistPolicy?.takeIf { it.generation == Auth.generation }?.blocked.orEmpty() + Spotify.localBlockedArtists(context))) return@onMain
        val snapshot = state.value
        if (snapshot.index !in snapshot.queue.indices) enqueue(item)
        else applyQueueEdit(snapshot.queue, QueueEdit(snapshot.queue.toMutableList().apply {
            add(snapshot.index + 1, item.copy())
        }, snapshot.index))
    }

    fun removeQueueItem(expectedQueue: List<QueueItem>, index: Int) = onMain {
        applyQueueEdit(expectedQueue, removeQueueEntry(expectedQueue, state.value.index, index))
    }

    fun moveQueueItemNext(expectedQueue: List<QueueItem>, index: Int) = onMain {
        applyQueueEdit(expectedQueue, moveQueueEntryNext(expectedQueue, state.value.index, index))
    }

    internal fun reorderUpcoming(expectedQueue: List<QueueItem>, from: Int, to: Int) = onMain {
        applyQueueEdit(expectedQueue, reorderUpcomingEntries(expectedQueue, state.value.index, from, to))
    }

    fun clearUpcoming(expectedQueue: List<QueueItem>) = onMain {
        applyQueueEdit(expectedQueue, clearUpcomingEntries(expectedQueue, state.value.index))
    }

    private fun applyQueueEdit(expectedQueue: List<QueueItem>, edit: QueueEdit<QueueItem>?) {
        if (edit == null || !canEditQueue() || state.value.queue !== expectedQueue) return
        discardManifestPrefetch()
        state.value = state.value.copy(queue = edit.items, index = edit.current)
        savePlaybackCheckpoint()
    }


    // ---- video episodes ----

    /** License endpoint of the active video manifest; null while playing audio. */
    @Volatile
    private var videoLicenseUrl: String? = null

    /** CDN tokens in the manifest are short-lived: a Source error re-resolves it. */
    @Volatile
    private var videoRetryCount = 0

    @Volatile
    private var lastVideoManifest: VideoSource.VideoManifestData? = null

    /** Runtime probe: does this episode carry a playable video source? */
    fun probeEpisodeVideo(uri: String) = scope.launch {
        val has = try {
            VideoSource.episodeVideoId(context, uri) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        withContext(Dispatchers.Main.immediate) {
            if (current()?.uri == uri) {
                state.value = state.value.copy(videoAvailable = has)
            }
        }
    }

    /** Swaps the single player onto the episode video source, keeping position and rate. */
    fun playEpisodeVideo() = onMain {
        videoRetryCount = 0
        switchToEpisodeVideo()
    }

    private fun switchToEpisodeVideo() {
        val item = current() ?: return
        if (!item.uri.startsWith("spotify:episode:")) return
        val snapshot = state.value
        val autoplay = player.playWhenReady || playWhenLicensed
        val positionMs = if (activeSource != null) player.currentPosition else snapshot.positionMs
        resolverJob?.cancel()
        licenseRetryJob?.cancel()
        discardManifestPrefetch()
        activeSource = null
        player.pause()
        playWhenLicensed = autoplay && !waitingForConnectOwnership
        val loadId = ++nextLoadId
        state.value = state.value.copy(
            isVideo = true, playing = false, buffering = true, error = null,
            positionMs = positionMs, loadId = loadId, licenseReady = false,
            waitingForLicenseRetry = false,
        )
        resolverJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val videoId = VideoSource.episodeVideoId(context, item.uri)
                    ?: error("no video source")
                val data = VideoSource.resolveManifest(context, videoId)
                    ?: error("video manifest unavailable")
                val dashFile = VideoSource.buildDashFile(context, data)
                    ?: error("video manifest file unavailable")
                ensureActive()
                if (state.value.loadId != loadId || current()?.uri != item.uri) return@launch
                videoLicenseUrl = data.licenseServer
                lastVideoManifest = data
                prepareDash(dashFile, item, positionMs, autoplay = playWhenLicensed, durationMs = data.durationMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                if (state.value.loadId != loadId || current()?.uri != item.uri) return@launch
                Log.w("Player", "video fallback to audio: ${failure.message}")
                // Start outside this resolver: prepareCurrent cancels the previous job.
                main.post {
                    if (state.value.loadId == loadId) playEpisodeAudio()
                }
            }
        }
    }

    /** Returns the single player to the episode audio source. */
    fun playEpisodeAudio() = onMain {
        if (current() == null) return@onMain
        val snapshot = state.value
        val autoplay = player.playWhenReady || playWhenLicensed
        val positionMs = if (activeSource != null) player.currentPosition else snapshot.positionMs
        state.value = snapshot.copy(isVideo = false, buffering = true, error = null)
        prepareCurrent(autoplay = autoplay, positionMs = positionMs)
    }

    /** Caps adaptive video height; 0 = Auto. */
    fun selectVideoQuality(height: Int) = onMain {
        val capped = if (height > 0) height else Int.MAX_VALUE
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, capped)
            .build()
        state.value = state.value.copy(videoQuality = height)
    }

    /** Called when the video surface leaves the screen: audio keeps flowing. */
    fun detachVideoSurface() = onMain {
        if (state.value.isVideo) player.clearVideoSurfaceView(null)
    }

    fun attachVideoSurface(surface: android.view.SurfaceView) = onMain {
        player.setVideoSurfaceView(surface)
    }

    fun releaseVideoSurface(surface: android.view.SurfaceView) = onMain {
        player.clearVideoSurfaceView(surface)
    }

    /** Prepares the DASH source built from the episode video manifest. */
    private fun prepareDash(dashFile: File, item: QueueItem, positionMs: Long, autoplay: Boolean, durationMs: Long) {
        clearTransitionEnvelope()
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .apply { if (item.imageUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(item.imageUrl)) }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(item.uri)
            .setUri(android.net.Uri.fromFile(dashFile))
            .setMediaMetadata(metadata)
            .setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setForceSessionsForAudioAndVideoTracks(true)
                    .build(),
            )
            .build()
        val drmManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .setPlayClearSamplesWithoutKeys(false)
            .build(VideoDrmCallback(context, videoLicenseUrl.orEmpty()))
        val dashFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
            .setDrmSessionManagerProvider { drmManager }
        val mediaSource = dashFactory.createMediaSource(mediaItem)
        activeSource = mediaSource
        playWhenLicensed = false
        state.value = state.value.copy(licenseReady = true)
        if (fadeIntoTrack && transitionAllowed(state.value)) {
            volumeBeforeFade = player.volume
            player.volume = trackTransitionGain(positionMs, item.durationMs, fadeIn = true, fadeOut = false) * player.volume
        }
        player.setMediaSource(mediaSource, positionMs)
        player.playbackParameters = player.playbackParameters.withSpeed(state.value.speed)
        player.prepare()
        player.playWhenReady = autoplay
        state.value = state.value.copy(
            playing = false,
            buffering = true,
            isVideo = true,
            durationMs = if (durationMs > 0) durationMs else item.durationMs,
            videoQualities = lastVideoManifest?.videoProfiles
                ?.map { it.height }?.filter { it > 0 }?.distinct()?.sortedDescending().orEmpty(),
        )
        startProgressLoop()
        Log.i("Player", "attached video '${item.title}' at ${positionMs}ms")
    }

    fun setSleepTimer(minutes: Int?) = onMain {
        sleepTimer.set(minutes)
    }

    fun togglePlayPause() {
        main.post {
            if (waitingForConnectOwnership) return@post
            when {
                licenseRetryJob?.isActive == true -> {
                    licenseRetryJob?.cancel()
                    licenseRetryJob = null
                    state.value = state.value.copy(waitingForLicenseRetry = false)
                }
                state.value.buffering -> Unit
                state.value.error == LICENSE_RATE_LIMITED -> retryWhenLicenseAvailable()
                state.value.error != null -> prepareCurrent(autoplay = true, positionMs = state.value.positionMs)
                player.isPlaying || playWhenLicensed -> pause()
                else -> resume()
            }
        }
    }

    fun pause() {
        // Checked synchronously: the server-apply guard is reset by the time
        // a main.post body would run, which made every server-issued pause
        // re-enter the transport delegate as a user command.
        if (!jamApplyingServerCommand && jamTransportDelegate?.onPause() == true) return
        main.post {
            if (licenseRetryJob?.isActive == true) {
                licenseRetryJob?.cancel()
                licenseRetryJob = null
                state.value = state.value.copy(waitingForLicenseRetry = false)
            }
            playWhenLicensed = false
            player.pause()
        }
    }

    fun resume(expectedLoadId: Long? = null) = onMain {
        if (!jamApplyingServerCommand && jamTransportDelegate?.onPlay() == true) return@onMain
        if (waitingForConnectOwnership) return@onMain
        if (expectedLoadId != null && state.value.loadId != expectedLoadId) return@onMain
        if (expectedLoadId == null && state.value.error == LICENSE_RATE_LIMITED) {
            if (licenseRetryJob?.isActive != true) retryWhenLicenseAvailable()
            return@onMain
        }
        if (expectedLoadId == null && current() != null && (state.value.error != null || (activeSource == null && !state.value.buffering))) {
            prepareCurrent(autoplay = true, positionMs = state.value.positionMs)
            return@onMain
        }
        playWhenLicensed = !state.value.licenseReady && state.value.error == null && current() != null
        if (state.value.licenseReady) player.play()
    }

    /** Podcast-style relative skip (negative = back). */
    fun skipBy(ms: Long) = onMain {
        if (activeSource == null) return@onMain
        val limit = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0L, limit))
    }

    /** Playback rate for podcast listening; sticks for the session. */
    fun setSpeed(speed: Float) = onMain {
        if (activeSource == null) return@onMain
        player.playbackParameters = player.playbackParameters.withSpeed(speed)
        state.value = state.value.copy(speed = speed)
    }

    fun seekTo(ms: Long) = onMain {
        if (!jamApplyingServerCommand && jamTransportDelegate?.onSeek(ms) == true) return@onMain
        clearTransitionEnvelope()
        if (activeSource != null) player.seekTo(ms)
        else if (current() != null && !state.value.buffering) {
            val current = state.value
            state.value = current.copy(
                positionMs = ms.coerceIn(0, current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE),
                seekRevision = current.seekRevision + 1,
            )
        }
    }

    fun setShuffle(mode: Shuffle) = onMain {
        val snapshot = state.value
        val current = snapshot.queue.getOrNull(snapshot.index) ?: return@onMain
        if (mode == snapshot.shuffle) return@onMain
        suggestionsJob?.cancel()
        shuffleRevision++
        discardManifestPrefetch()

        val original = originalQueue ?: snapshot.queue
        val originalIndex = original.indexOfFirst { it === current }
        // Keep a playing suggestion until the listener moves to another track.
        val restored = if (originalIndex >= 0) original else original.toMutableList().apply {
            val preceding = snapshot.queue.take(snapshot.index).lastOrNull { candidate ->
                original.any { it === candidate }
            }
            add(original.indexOfFirst { it === preceding } + 1, current)
        }
        val queue = if (mode == Shuffle.OFF) restored
            else listOf(current) + original.filterIndexed { index, _ -> index != originalIndex }.shuffled()
        originalQueue = if (mode == Shuffle.OFF && originalIndex >= 0) null else original
        state.value = snapshot.copy(
            queue = queue,
            index = queue.indexOfFirst { it === current },
            shuffle = mode,
            loadingSuggestions = mode == Shuffle.SMART,
            suggestedUris = if (originalIndex < 0) setOf(current.uri) else emptySet(),
        )

        if (mode == Shuffle.SMART) {
            val artist = current.artist.substringBefore(',').trim()
            val excluded = original.mapTo(mutableSetOf(current.uri)) { it.uri }
            val revision = shuffleRevision
            suggestionsJob = scope.launch {
                val radio = Spotify.artistRadio(context, current.artistUri)
                val radioTracks = radio?.let { Spotify.playlist(context, it, limit = 30)?.tracks }.orEmpty()
                val blocked = Spotify.localBlockedArtists(context) + Spotify.blockedArtists(context).orEmpty()
                val tracks = radioTracks.ifEmpty { Spotify.relatedTracks(context, artist) }.filterNot { it.artistUri in blocked || it.artistUris.any(blocked::contains) }
                onMain {
                    val latest = state.value
                    if (revision != shuffleRevision) return@onMain
                    val suggestions = tracks.filter { excluded.add(it.uri) }.shuffled().take(5).map {
                        QueueItem(it.uri, it.name, it.artists, it.albumUri, it.imageUrl, it.durationMs, it.artistUri, it.artistUris)
                    }
                    val mixed = latest.queue.toMutableList()
                    suggestions.forEachIndexed { index, suggestion ->
                        mixed.add((latest.index + (index + 1) * 4).coerceAtMost(mixed.size), suggestion)
                    }
                    state.value = latest.copy(
                        queue = mixed,
                        loadingSuggestions = false,
                        suggestedUris = latest.suggestedUris + suggestions.map { it.uri },
                    )
                }
            }
        }
    }

    fun cycleRepeat() = onMain {
        val snapshot = state.value
        state.value = snapshot.copy(repeat = Repeat.entries[(snapshot.repeat.ordinal + 1) % Repeat.entries.size])
    }

    /** Absolute repeat set for external session clients (car / Assistant). */
    fun setRepeat(mode: Repeat) = onMain {
        val snapshot = state.value
        if (mode != snapshot.repeat) state.value = snapshot.copy(repeat = mode)
    }

    fun next() = onMain {
        if (!jamApplyingServerCommand && jamTransportDelegate?.onNext() == true) return@onMain
        val snapshot = state.value
        val next = snapshot.index + 1
        skipToDirection(if (next > snapshot.queue.lastIndex && snapshot.repeat == Repeat.QUEUE) 0 else next, 1)
    }

    fun previous() = onMain {
        if (!jamApplyingServerCommand && jamTransportDelegate?.onPrevious() == true) return@onMain
        val snapshot = state.value
        val previous = snapshot.index - 1
        skipToDirection(if (previous < 0 && snapshot.repeat == Repeat.QUEUE) snapshot.queue.lastIndex else previous, -1)
    }

    fun skipTo(newIndex: Int) = onMain {
        if (!jamApplyingServerCommand) {
            val targetUri = state.value.queue.getOrNull(newIndex)?.uri
            if (targetUri != null && jamTransportDelegate?.onPlayUri(targetUri) == true) return@onMain
        }
        skipToDirection(newIndex, 0)
    }

    private fun skipToDirection(newIndex: Int, direction: Int, autoplay: Boolean = true, fadeIn: Boolean = false) = onMain {
        val snapshot = state.value
        val selected = snapshot.queue.getOrNull(newIndex) ?: return@onMain
        val temporary = snapshot.queue.getOrNull(snapshot.index)
        val original = originalQueue
        if (snapshot.shuffle != Shuffle.SMART && selected !== temporary &&
            temporary?.uri in snapshot.suggestedUris && original != null
        ) {
            val queue = snapshot.queue.filterNot { it === temporary }
            state.value = snapshot.copy(queue = queue, index = queue.indexOfFirst { it === selected },
                positionMs = 0L, durationMs = selected.durationMs, suggestedUris = emptySet())
            if (snapshot.shuffle == Shuffle.OFF) originalQueue = null
        } else {
            state.value = snapshot.copy(index = newIndex, positionMs = 0L, durationMs = selected.durationMs)
        }
        prepareCurrent(autoplay = autoplay, direction = direction, fadeIn = fadeIn)
    }

    fun stop(expectedLoadId: Long? = null, keepLastLocal: Boolean = false) {
        onMain {
            if (expectedLoadId != null && state.value.loadId != expectedLoadId) return@onMain
            if (activeSource != null) sampleListening(playing = false)
            listeningTracker.end()
            listeningAccount = null
            if (keepLastLocal) savePlaybackCheckpoint() else checkpoints.trySend(null)
            waitingForConnectOwnership = false
            checkpointState = null
            clearTransitionEnvelope()
            resolverJob?.cancel()
            suggestionsJob?.cancel()
            shuffleRevision++
            originalQueue = null
            discardManifestPrefetch()
            licenseRetryJob?.cancel()
            progressRunnable?.let { main.removeCallbacks(it) }
            progressRunnable = null
            activeSource = null
            playWhenLicensed = false
            player.stop()
            player.clearMediaItems()
            setSleepTimer(null)
            state.value = State(autoAdvance = state.value.autoAdvance, smoothTransitions = state.value.smoothTransitions)
        }
    }

    // ---- internals ----

    private fun buildPlayer(): ExoPlayer {
        val built = ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .build()
        built.addListener(listener)
        Log.i("Player", "exo player built")
        return built
    }

    private fun retryWhenLicenseAvailable() {
        val snapshot = state.value
        val waitMs = SourceResolver.licenseRetryDelayMs()
        if (waitMs == 0L) {
            prepareCurrent(autoplay = true, positionMs = snapshot.positionMs)
            return
        }
        state.value = snapshot.copy(waitingForLicenseRetry = true)
        licenseRetryJob = scope.launch(Dispatchers.Main.immediate) {
            delay(waitMs)
            licenseRetryJob = null
            if (state.value.loadId == snapshot.loadId) {
                prepareCurrent(autoplay = true, positionMs = snapshot.positionMs)
            }
        }
    }

    private fun prepareCurrent(autoplay: Boolean, positionMs: Long = 0L, direction: Int = 0, fadeIn: Boolean = false) {
        clearTransitionEnvelope()
        fadeIntoTrack = fadeIn
        listeningTracker.end()
        val item = current() ?: return
        val account = Auth.account(context)?.id?.takeIf { it.isNotBlank() } ?: return
        if (resolutionAccount != account) {
            resolved.clear()
            resolutionAccount = account
        }
        licenseRetryJob?.cancel()
        licenseRetryJob = null
        resolverJob?.cancel()
        videoRetryCount = 0
        lastVideoManifest = null
        videoLicenseUrl = null
        val pending = manifestPrefetch?.takeIf { it.uri == item.uri }
        if (pending == null) discardManifestPrefetch() else manifestPrefetch = null
        resolverJob = scope.launch(Dispatchers.Main.immediate) {
            activeSource = null
            // Keep the paused timeline until its replacement is available. An empty
            // timeline removes Media3's notification and foreground service immediately.
            player.pause()
            playWhenLicensed = autoplay && !waitingForConnectOwnership
            state.value = state.value.copy(
                playing = false,
                buffering = true,
                error = null,
                licenseReady = false,
                isVideo = false,
                videoQualities = emptyList(),
                waitingForLicenseRetry = false,
                durationMs = item.durationMs,
                loadId = ++nextLoadId,
            )
            if (!waitingForConnectOwnership && !jamSuppressesCheckpoints && item.uri.startsWith("spotify:track:")) {
                val blocked = activeArtistPolicy()
                ensureActive()
                if (blockedByArtist(item, blocked)) {
                    val snapshot = state.value
                    for (distance in 1 until snapshot.queue.size) {
                        if (direction == 0) break
                        val raw = snapshot.index + direction * distance
                        val candidate = if (snapshot.repeat == Repeat.QUEUE) Math.floorMod(raw, snapshot.queue.size) else raw
                        val track = snapshot.queue.getOrNull(candidate) ?: break
                        val denied = blockedByArtist(track, blocked)
                        ensureActive()
                        if (state.value.queue !== snapshot.queue || state.value.index != snapshot.index) break
                        if (!denied) {
                            pending?.result?.cancel()
                            skipToDirection(candidate, 0, autoplay, fadeIn)
                            return@launch
                        }
                    }
                    pending?.result?.cancel()
                    player.clearMediaItems()
                    state.value = state.value.copy(buffering = false, error = Lang.t(Lang.Key.PUK_BLOCKED_ARTIST))
                    return@launch
                }
            }
            val source = try {
                resolved[item.uri] ?: run {
                    pending?.startNow?.complete(Unit)
                    val prefetched = pending?.result?.await()
                    if (prefetched != null) Log.d("Player", "Reusing prefetched track manifest")
                    SourceResolver.resolveWithRetry(context, item.uri, prefetched)
                }
            } finally {
                pending?.result?.cancel()
            }
            ensureActive()
            if (Auth.account(context)?.id != account) return@launch
            if (source == null) {
                player.clearMediaItems()
                state.value = state.value.copy(buffering = false, error = Lang.t(Lang.Key.PUK_TRACK_UNAVAILABLE))
                return@launch
            }
            resolved[item.uri] = source
            while (resolved.size > 32) resolved.remove(resolved.keys.first())
            attach(source, item, positionMs)
        }
    }

    private fun attach(source: SourceResolver.AudioSource, item: QueueItem, positionMs: Long) {
        videoLicenseUrl = null
        val encrypted = source.segments.pssh.isNotBlank()

        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .apply { if (item.imageUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(item.imageUrl)) }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(item.uri)
            .setUri(android.net.Uri.parse(source.url))
            .setMediaMetadata(metadata)
            .apply {
                if (encrypted) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setForceSessionsForAudioAndVideoTracks(true)
                            .build(),
                    )
                }
            }
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
        if (encrypted) {
            val drmManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setPlayClearSamplesWithoutKeys(false)
                .build(PukDrmCallback(context, source.spclient))
            mediaSourceFactory.setDrmSessionManagerProvider { drmManager }
        }

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
        activeSource = mediaSource
        if (encrypted) {
            mediaSource.addDrmEventListener(main, object : DrmSessionEventListener {
                override fun onDrmKeysLoaded(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
                    if (activeSource === mediaSource && current()?.uri == item.uri) {
                        state.value = state.value.copy(licenseReady = true)
                        Log.i("Player", "Widevine keys loaded")
                        if (playWhenLicensed) {
                            playWhenLicensed = false
                            player.play()
                        }
                    }
                }

                override fun onDrmSessionManagerError(
                    windowIndex: Int,
                    mediaPeriodId: MediaSource.MediaPeriodId?,
                    error: Exception,
                ) {
                    if (activeSource !== mediaSource || current()?.uri != item.uri || state.value.licenseReady) return
                    playWhenLicensed = false
                    val rateLimited = generateSequence<Throwable>(error) { it.cause }
                        .any { it is SourceResolver.LicenseRateLimited }
                    state.value = state.value.copy(
                        playing = false,
                        buffering = false,
                        error = if (rateLimited) LICENSE_RATE_LIMITED else if (!hasNetwork()) LICENSE_NETWORK_REQUIRED else Lang.t(Lang.Key.PUK_LICENSE_FAILED),
                    )
                    // Лимит — не фатально: сами повторяем после бэкоффа (одна
                    // попытка на окно), чтобы пользователь не тыкал Play вслепую.
                    if (rateLimited && licenseRetryJob?.isActive != true) retryWhenLicenseAvailable()
                }
            })
        }
        state.value = state.value.copy(licenseReady = !encrypted, isVideo = false)
        player.setMediaSource(mediaSource, positionMs)
        player.prepare()
        player.playWhenReady = !encrypted && playWhenLicensed
        if (!encrypted) playWhenLicensed = false
        state.value = state.value.copy(
            playing = false,
            buffering = true,
            durationMs = if (source.segments.totalDurationMs > 0) source.segments.totalDurationMs else item.durationMs,
        )
        startProgressLoop()
        Log.i(
            "Player",
            "attached '${item.title}' fmt=${source.file.format} ${source.file.bitrate}kbps " +
                "drm=$encrypted dur=${source.segments.totalDurationMs}ms",
        )
    }

    private fun startProgressLoop() {
        progressRunnable?.let { main.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                val p = player
                if (activeSource != null && p.mediaItemCount > 0 && p.currentMediaItem?.mediaId == current()?.uri) {
                    if (p.isPlaying && listeningAccount != null) sampleListening()
                    state.value = state.value.copy(
                        positionMs = p.currentPosition,
                        playing = p.isPlaying,
                        buffering = p.playbackState == Player.STATE_BUFFERING,
                        durationMs = if (p.duration > 0) p.duration else state.value.durationMs,
                    )
                }
                main.postDelayed(this, 500L)
            }
        }
        progressRunnable = tick
        main.post(tick)
    }

    private fun discardManifestPrefetch() {
        manifestPrefetch?.result?.cancel()
        manifestPrefetch = null
    }

    private fun prefetchNext() {
        val snapshot = state.value
        val next = snapshot.queue.getOrNull(snapshot.index + 1) ?: return
        if (!snapshot.autoAdvance || manifestPrefetch != null || resolved.containsKey(next.uri)) return
        val startNow = CompletableDeferred<Unit>()
        val result = scope.async(Dispatchers.Main.immediate) {
            val selected = withTimeoutOrNull(3_000) { startNow.await() } != null
            if (!selected && (!state.value.playing || state.value.loadId != snapshot.loadId ||
                SourceResolver.licenseRetryDelayMs() > 0)
            ) return@async null
            SourceResolver.loadManifest(context, next.uri)
        }
        manifestPrefetch = ManifestPrefetch(next.uri, result, startNow)
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (listeningAccount != null) sampleListening(oldPosition.positionMs, playing = false)
            if (listeningAccount != null && player.isPlaying) sampleListening(newPosition.positionMs)
            if (reason == Player.DISCONTINUITY_REASON_SEEK && activeSource != null) {
                val snapshot = state.value
                state.value = snapshot.copy(positionMs = newPosition.positionMs, seekRevision = snapshot.seekRevision + 1)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) updateTransitionEnvelope() else main.removeCallbacks(fadeTick)
            if (isPlaying) {
                listeningAccount = Auth.account(context)?.id?.takeIf { it.isNotBlank() && statsEnabled(it) }
            }
            if (listeningAccount != null) sampleListening(playing = isPlaying)
            state.value = state.value.copy(playing = isPlaying)
            events.tryEmit(if (isPlaying) "play" else "pause")
            if (isPlaying && state.value.licenseReady) prefetchNext()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (activeSource == null) return
            val buffering = playbackState == Player.STATE_BUFFERING
            state.value = state.value.copy(buffering = buffering)
            if (playbackState == Player.STATE_ENDED) {
                val snapshot = state.value
                if (snapshot.repeat == Repeat.TRACK) {
                    player.seekTo(0L)
                    player.play()
                } else if (jamSuppressesCheckpoints) {
                    // Jam mode: the server decides the next track (owner
                    // correction #3) — pause locally, report via events.
                    player.pause()
                    events.tryEmit("ended")
                } else if (snapshot.autoAdvance && (snapshot.index < snapshot.queue.lastIndex || snapshot.repeat == Repeat.QUEUE)) {
                    val next = if (snapshot.index < snapshot.queue.lastIndex) snapshot.index + 1 else 0
                    skipToDirection(next, 1, fadeIn = transitionAllowed(snapshot))
                } else {
                    player.pause()
                    player.seekTo(0L)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (activeSource == null || player.currentMediaItem?.mediaId != current()?.uri) return
            discardManifestPrefetch()
            Log.e("Player", "playback error: ${error.errorCodeName}", error)
            // Video CDN tokens expire mid-playback: a Source error in video
            // mode re-resolves the manifest and resumes at the same position.
            if (state.value.isVideo) {
                if (videoRetryCount < 2) {
                    videoRetryCount++
                    Log.i("Player", "video source error, re-resolving manifest (try $videoRetryCount)")
                    switchToEpisodeVideo()
                } else {
                    playEpisodeAudio()
                }
                return
            }
            val rateLimited = generateSequence<Throwable>(error) { it.cause }
                .any { it is SourceResolver.LicenseRateLimited }
            // A dead cached manifest would replay the same error on every retry;
            // drop it so the next play re-resolves from scratch.
            resolved.remove(player.currentMediaItem?.mediaId)
            state.value = state.value.copy(
                playing = false,
                buffering = false,
                licenseReady = false,
                positionMs = player.currentPosition,
                error = if (rateLimited) LICENSE_RATE_LIMITED else if (error.errorCode in 6000..6999 && !hasNetwork()) LICENSE_NETWORK_REQUIRED else error.errorCodeName,
            )
            playWhenLicensed = false
            // Лимит лицензий: вежливый автоповтор после бэкоффа без участия
            // пользователя (см. waitForLicense — каждая попытка раньше срока
            // отклоняется локально, запросов в сеть не больше одного на окно).
            if (rateLimited && licenseRetryJob?.isActive != true) retryWhenLicenseAvailable()
        }
    }

    private fun hasNetwork(): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        internal const val LICENSE_NETWORK_REQUIRED = "license_network_required"
        internal const val LICENSE_RATE_LIMITED = "license_rate_limited"
    }

    fun release() = onMain {
        clearTransitionEnvelope()
        sleepTimer.set(null)
        resolverJob?.cancel()
        discardManifestPrefetch()
        licenseRetryJob?.cancel()
        progressRunnable?.let { main.removeCallbacks(it) }
        progressRunnable = null
        player.release()
    }
}

/** Posts MediaDrm key/provision requests to Spotify's audio license endpoint. */
@UnstableApi
private class PukDrmCallback(private val context: Context, private val spclient: String) : MediaDrmCallback {

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val answer = runBlocking {
            SourceResolver.requestLicense(context, spclient, request.data)
        }
        return answer ?: throw IOException("license request failed for $uuid")
    }

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val separator = if (request.defaultUrl.contains("?")) "&" else "?"
        val url = request.defaultUrl + separator +
            "signedRequest=" + java.net.URLEncoder.encode(String(request.data, Charsets.UTF_8), "UTF-8")
        val answer = runBlocking {
            com.project.puk.core.Net.postBlob(
                url,
                ByteArray(0),
                "application/octet-stream",
                emptyMap(),
                "provision",
            )?.bytes
        }
        return answer ?: throw IOException("provision failed for $uuid")
    }
}

/** Posts MediaDrm video key/provision requests to the manifest-provided endpoint. */
@UnstableApi
class VideoDrmCallback(private val context: Context, private val licenseUrl: String) : MediaDrmCallback {

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val answer = runBlocking {
            com.project.puk.core.Net.postBlob(licenseUrl, request.data, "application/octet-stream", emptyMap(), "video-license")?.bytes
        }
        return answer ?: throw IOException("video license request failed for $uuid")
    }

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val separator = if (request.defaultUrl.contains("?")) "&" else "?"
        val url = request.defaultUrl + separator +
            "signedRequest=" + java.net.URLEncoder.encode(String(request.data, Charsets.UTF_8), "UTF-8")
        val answer = runBlocking {
            com.project.puk.core.Net.postBlob(url, ByteArray(0), "application/octet-stream", emptyMap(), "provision")?.bytes
        }
        return answer ?: throw IOException("video provision failed for $uuid")
    }
}
