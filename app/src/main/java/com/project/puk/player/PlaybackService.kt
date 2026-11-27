package com.project.puk.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import com.project.puk.connect.Connect
import com.project.puk.jam.JamController
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import com.project.puk.core.Lang
import com.project.puk.core.Log

/**
 * Foreground playback service: exposes the engine's persistent ExoPlayer via
 * a media3 MediaSession (MediaLibraryService so Android Auto and Assistant
 * can browse the catalog through [AutoLibrarySessionCallback]). A
 * ForwardingPlayer wrapper routes next/previous presses from the
 * notification/lockscreen/Bluetooth into the engine's own queue (the
 * underlying ExoPlayer only ever holds one item).
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    companion object {
        /** The single shared engine for UI and service. */
        @Volatile
        var engine: PukPlayer? = null

        fun engine(context: Context): PukPlayer =
            engine ?: synchronized(this) {
                engine ?: PukPlayer(context.applicationContext).also { engine = it }
            }
    }

    private var session: MediaLibraryService.MediaLibrarySession? = null
    private var autoCallback: AutoLibrarySessionCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Track switches can pause ExoPlayer while the next source resolves.
        setForegroundServiceTimeoutMs(30_000L)
        val puk = engine(this)
        val wrapped = PukSessionPlayer(puk)
        val callback = AutoLibrarySessionCallback(this, puk)
        autoCallback = callback
        val mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, wrapped, callback)
            .setCustomLayout(callback.currentLayout())
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.project.puk.ui.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
            .also(::addSession)
        session = mediaSession
        scope.launch {
            puk.state.map { Triple(it.queue, it.index, it.shuffle to it.repeat) }
                .distinctUntilChanged().collect { wrapped.refreshQueue() }
        }
        scope.launch {
            combine(
                puk.state.map { it.shuffle to it.sleepTimerMinutes }.distinctUntilChanged(),
                snapshotFlow { Lang.current },
            ) { _, _ -> callback.currentLayout() }
                .collect { mediaSession.setCustomLayout(it) }
        }
        Log.i("Service", "media library session created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        engine?.savePlaybackCheckpoint()
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        autoCallback?.release()
        engine?.savePlaybackCheckpoint()
        session?.release()
        session = null
        Log.i("Service", "media library session released")
        super.onDestroy()
    }
}

internal fun hasRemotePlayback(): Boolean = Connect.clusterActiveDevice.value.let {
    it.isNotBlank() && !Connect.isLocalDevice(it)
}

@UnstableApi
internal data class ResumeLocalTrack(val item: PukPlayer.QueueItem, val loadId: Long, val index: Int)

/** Publishes the engine's queue while ExoPlayer prepares only the selected source. */
@UnstableApi
private class PukSessionPlayer(private val puk: PukPlayer) : ForwardingSimpleBasePlayer(PukForwardingPlayer(puk)) {
    private data class WindowId(val uri: String, val occurrence: Int)

    private var queue: List<PukPlayer.QueueItem>? = null
    private var windows = emptyList<MediaItemData>()

    fun refreshQueue() = invalidateState()

    override fun getState(): State {
        val base = super.getState()
        val snapshot = puk.state.value
        val current = snapshot.queue.getOrNull(snapshot.index) ?: return base
        if (queue !== snapshot.queue) {
            val occurrences = mutableMapOf<String, Int>()
            windows = snapshot.queue.map { entry ->
                val occurrence = occurrences.getOrDefault(entry.uri, 0)
                occurrences[entry.uri] = occurrence + 1
                val metadata = MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.artist)
                    .setDurationMs(entry.durationMs.takeIf { it > 0 })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                if (entry.imageUrl.isNotBlank()) metadata.setArtworkUri(Uri.parse(entry.imageUrl))
                MediaItemData.Builder(WindowId(entry.uri, occurrence))
                    .setMediaItem(MediaItem.Builder().setMediaId(entry.uri).setMediaMetadata(metadata.build()).build())
                    .setDurationUs(if (entry.durationMs > 0) entry.durationMs * 1000 else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
            queue = snapshot.queue
        }
        val commands = base.availableCommands.buildUpon()
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .build()
        val builder = base.buildUpon()
            .setAvailableCommands(commands)
            .setPlaylist(windows)
            .setCurrentMediaItemIndex(snapshot.index)
        // The resolving track can differ from the previous source held by ExoPlayer.
        if (puk.player.currentMediaItem?.mediaId != current.uri) {
            builder.setContentPositionMs(snapshot.positionMs)
                .setContentBufferedPositionMs { snapshot.positionMs }
                .setTotalBufferedDurationMs { 0 }
                .clearPositionDiscontinuity()
        }
        return builder.build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val snapshot = puk.state.value
        if (seekCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM && mediaItemIndex in snapshot.queue.indices) {
            if (!hasRemotePlayback()) {
                if (mediaItemIndex == snapshot.index) puk.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
                else puk.skipTo(mediaItemIndex)
            }
            return Futures.immediateVoidFuture()
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}

/**
 * Routes transport commands through the engine so media controls honor
 * the same queue and license state as the player screen.
 */
@UnstableApi
private class PukForwardingPlayer(
    private val puk: PukPlayer,
) : Player by puk.player {

    private val exo: ExoPlayer get() = puk.player
    private var staleResume = false

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val resume = mediaItems.singleOrNull()?.localConfiguration?.tag as? ResumeLocalTrack
        if (resume == null) {
            exo.setMediaItems(mediaItems, startIndex, startPositionMs)
            return
        }
        // The description carries metadata only. PukPlayer must resolve the stream and DRM.
        val state = puk.state.value
        staleResume = state.loadId != resume.loadId || state.index != resume.index ||
            state.queue.getOrNull(state.index) !== resume.item
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = Player.Commands.Builder()
            .addAll(exo.availableCommands)
        // During a Jam session skip decisions belong to the server: NEXT/PREV
        // are host-only, so guests get the skip buttons hidden instead of
        // eating silent taps (correction #3).
        val jam = JamController.activeController
        val canSkipLocally = jam?.takeIf { it.isActive }?.canSkipFromSession() ?: true
        if (canSkipLocally) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        getAvailableCommands().contains(command)

    override fun hasNextMediaItem(): Boolean =
        puk.state.value.index + 1 < puk.state.value.queue.size

    override fun hasPreviousMediaItem(): Boolean = puk.state.value.index > 0

    override fun play() {
        val stale = staleResume
        staleResume = false
        if (!stale && !hasRemotePlayback()) puk.resume()
    }

    override fun pause() {
        puk.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun seekTo(positionMs: Long) {
        // Route through the engine so the Jam transport delegate sees it.
        puk.seekTo(positionMs)
    }

    override fun seekToNext() {
        puk.next()
    }

    override fun seekToNextMediaItem() {
        puk.next()
    }

    override fun seekToPrevious() {
        puk.previous()
    }

    override fun seekToPreviousMediaItem() {
        puk.previous()
    }

    override fun stop() {
        puk.stop()
    }

    // Shuffle/repeat belong to the engine queue (the ExoPlayer playlist is a
    // single item), so session clients like Android Auto control the engine.

    override fun getShuffleModeEnabled(): Boolean =
        puk.state.value.shuffle != PukPlayer.Shuffle.OFF

    override fun setShuffleModeEnabled(enabled: Boolean) {
        puk.setShuffle(if (enabled) PukPlayer.Shuffle.ON else PukPlayer.Shuffle.OFF)
    }

    override fun getRepeatMode(): Int = when (puk.state.value.repeat) {
        PukPlayer.Repeat.QUEUE -> Player.REPEAT_MODE_ALL
        PukPlayer.Repeat.TRACK -> Player.REPEAT_MODE_ONE
        PukPlayer.Repeat.OFF -> Player.REPEAT_MODE_OFF
    }

    override fun setRepeatMode(mode: Int) {
        puk.setRepeat(when (mode) {
            Player.REPEAT_MODE_ALL -> PukPlayer.Repeat.QUEUE
            Player.REPEAT_MODE_ONE -> PukPlayer.Repeat.TRACK
            else -> PukPlayer.Repeat.OFF
        })
    }
}
