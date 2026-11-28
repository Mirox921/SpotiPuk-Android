package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.painterResource
import com.project.puk.ui.theme.EmberIcons
import com.project.puk.ui.theme.EmberAnimatedIcon
import com.project.puk.ui.theme.EmberIconButton
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.project.puk.R
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.MotionDurationScale
import androidx.compose.runtime.key
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.project.puk.ui.theme.EmberMotion
import com.project.puk.ui.theme.EmberTransportButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.project.puk.ui.theme.artworkAmbient
import com.project.puk.ui.theme.artworkAmbientColors
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.project.puk.api.Spotify
import com.project.puk.player.PukPlayer
import java.util.Locale

/**
 * Full-screen player: artwork, seek bar, transport controls and a synced
 * lyrics pane that follows playback.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    player: PukPlayer,
    onBack: () -> Unit,
    onOutputs: () -> Unit,
    fullArtwork: Boolean,
    warmBackground: Boolean,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onLibraryChanged: () -> Unit,
    screenshotHint: Boolean,
) {
    val context = LocalContext.current
    val playback = player.state.collectAsState()
    val state by remember(playback) {
        derivedStateOf(structuralEqualityPolicy()) {
            val current = playback.value
            if (current.error == null) current.copy(positionMs = 0) else current
        }
    }
    val item = state.queue.getOrNull(state.index)
    val uri = item?.uri.orEmpty()
    val isEpisode = uri.startsWith("spotify:episode:")
    var canvasEnabled by rememberSaveable { mutableStateOf(false) }
    val availableCanvas = rememberAvailableCanvas(uri)
    val canvasLoad = rememberCanvasMedia(availableCanvas, canvasEnabled)
    val canvas = canvasLoad.source
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var podcastChapters by remember(uri) { mutableStateOf<List<com.project.puk.api.ChapterSpan>?>(null) }
    LaunchedEffect(uri) {
        if (isEpisode) {
            podcastChapters = runCatching { Spotify.chapters(context, uri) }.getOrNull()?.ifEmpty { null }
        }
    }
    LaunchedEffect(isEpisode, uri) {
        if (isEpisode) player.probeEpisodeVideo(uri)
    }
    var menu by remember(uri) { mutableStateOf(false) }
    var timerDialog by remember { mutableStateOf(false) }
    var queueScroll by remember { mutableStateOf<LazyListState?>(null) }
    var queueSelection by remember { mutableStateOf<Pair<List<PukPlayer.QueueItem>, Int>?>(null) }
    androidx.compose.runtime.SideEffect {
        if (queueSelection != null && queueSelection?.first !== state.queue) queueSelection = null
    }
    var queueActionTrack by remember { mutableStateOf<PukPlayer.QueueItem?>(null) }
    var shuffleMenu by remember { mutableStateOf(false) }
    var chaptersSheet by remember(uri) { mutableStateOf(false) }
    var transcriptSheet by remember(uri) { mutableStateOf(false) }
    if (transcriptSheet) {
        EpisodeTranscriptSheet(uri, item?.title.orEmpty(), playback, player::seekTo) { transcriptSheet = false }
    }
    var qualityMenu by remember(uri) { mutableStateOf(false) }
    var videoOverlay by rememberSaveable(uri) { mutableStateOf(false) }
    BackHandler(qualityMenu) { qualityMenu = false }
    LaunchedEffect(state.isVideo) {
        if (!state.isVideo) {
            qualityMenu = false
            videoOverlay = false
        }
    }
    LaunchedEffect(videoOverlay, qualityMenu) {
        if (videoOverlay && !qualityMenu) {
            delay(5000L)
            videoOverlay = false
        }
    }
    var shareCard by remember(uri) { mutableStateOf(false) }
    val capturePrompt = remember(uri) { SnackbarHostState() }
    val captureScope = rememberCoroutineScope()
    ScreenCaptureHint(screenshotHint && item != null && !menu && !timerDialog && queueScroll == null && !shareCard) {
        if (capturePrompt.currentSnackbarData == null) {
            captureScope.launch {
                if (capturePrompt.showSnackbar(
                        if (isEpisode) Lang.t(Lang.Key.PLAYER_MAKE_EPISODE_CARD_Q) else Lang.t(Lang.Key.PLAYER_MAKE_TRACK_CARD_Q), Lang.t(Lang.Key.PLAYER_CREATE),
                        withDismissAction = true, duration = SnackbarDuration.Long,
                    ) == SnackbarResult.ActionPerformed
                ) shareCard = true
            }
        }
    }
    if (shareCard && item != null) TrackShareSheet(item) { shareCard = false }
    if (chaptersSheet && item != null) {
        EmberActionSheet(onDismissRequest = { chaptersSheet = false }, heading = Lang.t(Lang.Key.PLAYER_CHAPTERS)) {
            if (podcastChapters.isNullOrEmpty()) {
                Text(
                    Lang.t(Lang.Key.PLAYER_CHAPTERS_UNAVAILABLE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                podcastChapters.orEmpty().forEach { chapter ->
                    SheetAction(
                        R.drawable.ember_ctx_chapter_seek,
                        podcastDuration(chapter.startMs) + " · " + chapter.title,
                    ) {
                        chaptersSheet = false
                        player.seekTo(chapter.startMs)
                    }
                }
            }
        }
    }
    var lyrics by remember(uri) { mutableStateOf<com.project.puk.api.Lyrics?>(null) }
    LaunchedEffect(uri) {
        if (uri.startsWith("spotify:track:")) {
            lyrics = (Spotify.lyrics(context, uri) as? Spotify.LyricsResult.Available)?.lyrics
        }
    }
    val actionEpisode = item?.takeIf { menu && it.uri.startsWith("spotify:episode:") }
    if (actionEpisode != null) {
        EpisodeActionSheet(
            item = actionEpisode,
            onDismiss = { menu = false },
            onEnqueue = { player.enqueue(actionEpisode) },
            onOpenShow = { showUri ->
                menu = false
                onOpenShow(showUri)
            },
            onLibraryChanged = onLibraryChanged,
            onSleepTimer = { timerDialog = true },
            onPlayNext = if (player.canEditQueue()) ({ player.enqueueNext(actionEpisode) }) else null,
            sharePositionMs = remember(actionEpisode.uri) { player.state.value.positionMs },
        )
    } else {
    val actionTrack = queueActionTrack ?: item?.takeIf { menu }?.let { current ->
        current.copy(durationMs = state.durationMs.takeIf { it > 0 } ?: current.durationMs)
    }
    if (actionTrack != null) {
        TrackActions(
            item = actionTrack,
            onDismiss = {
                menu = false
                queueActionTrack = null
            },
            onEnqueue = player::enqueue,
            onPlayNext = if (player.canEditQueue()) ({ player.enqueueNext(actionTrack) }) else null,
            onOpen = { target ->
                when (target) {
                    is Target.Artist -> onOpenArtist(target.uri)
                    is Target.Album -> onOpenAlbum(target.uri)
                    is Target.Show -> onOpenShow(target.uri)
                    else -> Unit
                }
            },
            onLibraryChanged = onLibraryChanged,
            onSleepTimer = if (queueActionTrack == null) ({ timerDialog = true }) else null,
        )
    }
    }
    if (timerDialog) {
        SleepTimerDialog(state.sleepTimerMinutes, player::setSleepTimer) { timerDialog = false }
    }
    queueSelection?.takeIf { it.first === state.queue }?.let { (queue, selectedIndex) ->
        val selectedItem = queue.getOrNull(selectedIndex)
        if (selectedItem != null) {
            val context = LocalContext.current
            EmberActionSheet(onDismissRequest = { queueSelection = null }, heading = selectedItem.title) {
                if (player.canEditQueue()) {
                    if (selectedIndex > state.index + 1) {
                        SheetAction(R.drawable.ember_ctx_play_next, Lang.t(Lang.Key.PLAYER_PLAY_NEXT)) {
                            if (runLocalQueueAction(context) { player.moveQueueItemNext(queue, selectedIndex) }) queueSelection = null
                        }
                    }
                    if (selectedIndex != state.index) {
                        SheetAction(R.drawable.ember_ctx_remove_from_queue, Lang.t(Lang.Key.JAM_REMOVE_FROM_QUEUE)) {
                            if (runLocalQueueAction(context) { player.removeQueueItem(queue, selectedIndex) }) queueSelection = null
                        }
                    }
                } else Text(Lang.t(Lang.Key.PLAYER_QUEUE_EDIT_NO_SHUFFLE))
                SheetAction(R.drawable.ember_ctx_open_item, Lang.t(Lang.Key.PLAYER_MORE_ACTIONS)) {
                    queueSelection = null
                    queueActionTrack = selectedItem
                }
            }
        }
    }
    queueScroll?.takeIf { queueActionTrack == null && queueSelection == null }?.let { scroll ->
        QueueDialog(
            queueScroll = scroll,
            state = state,
            onDismiss = { queueScroll = null },
            onActions = { queueSelection = state.queue to it },
            onClear = if (player.canEditQueue() && state.index < state.queue.lastIndex) ({
                runLocalQueueAction(context) { player.clearUpcoming(state.queue) }
                Unit
            }) else null,
            onMove = if (player.canEditQueue()) ({ from, to ->
                runLocalQueueAction(context) { player.reorderUpcoming(state.queue, from, to) }
            }) else null,
            onSelect = { index ->
                player.skipTo(index)
                queueScroll = null
            },
        )
    }
    val palette = MaterialTheme.colorScheme
    val ambientColors = artworkAmbientColors(if (warmBackground) item?.imageUrl.orEmpty() else "")
    val ambient by animateColorAsState(
        ambientColors.primary,
        tween(EmberMotion.Ambient),
        label = "Ember artwork ambient",
    )
    val ambientSecondary by animateColorAsState(
        ambientColors.secondary,
        tween(EmberMotion.Ambient),
        label = "Ember secondary artwork ambient",
    )
    val playPress = remember { MutableInteractionSource() }
    val playerScroll = rememberScrollState()
    val headerInset = WindowInsets.statusBars.getTop(LocalDensity.current)
    var controlsEnd by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    val compactVisible by remember(playerScroll, headerInset) {
        derivedStateOf { playerScroll.value >= controlsEnd - headerInset }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to ambient,
                0.52f to lerp(lerp(ambient, ambientSecondary, 0.45f), palette.background, 0.63f),
                1f to palette.background,
            ),
        ),
    ) {
        val artworkHeight = if (canvas != null && !landscape) {
            (maxWidth * 1.6f).coerceAtMost(maxHeight * .76f)
        } else maxWidth.coerceAtMost(maxHeight * if (landscape) .45f else .52f)
        val content = if (landscape) Modifier.align(Alignment.TopCenter).widthIn(max = 580.dp).fillMaxWidth().fillMaxHeight()
        else Modifier.fillMaxSize()
        Column(content.navigationBarsPadding().verticalScroll(playerScroll)) {
            Box(Modifier.fillMaxWidth().height(artworkHeight)) {
                if (isEpisode && state.isVideo) {
                    // Video card: centred in the artwork area, like the reference.
                    Box(
                        Modifier.fillMaxWidth().height(artworkHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 32.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                qualityMenu = false
                                videoOverlay = !videoOverlay
                            },
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                android.view.SurfaceView(ctx).also { surface ->
                                    player.attachVideoSurface(surface)
                                }
                            },
                            onRelease = { surface -> player.releaseVideoSurface(surface) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        val overlayAlpha by animateFloatAsState(
                            targetValue = if (videoOverlay) 1f else 0f,
                            animationSpec = tween(EmberMotion.Fast),
                            label = "Ember video overlay",
                        )
                        if (overlayAlpha > 0.01f) {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f * overlayAlpha)),
                            )
                            IconButton(
                                onClick = { qualityMenu = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f * overlayAlpha)),
                            ) {
                                Icon(EmberIcons.Quality, Lang.t(Lang.Key.PLAYER_VIDEO_QUALITY), tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 10.dp)) {
                            DropdownMenu(
                                expanded = qualityMenu,
                                onDismissRequest = { qualityMenu = false },
                                modifier = Modifier.heightIn(max = 280.dp).widthIn(min = 132.dp),
                            ) {
                                listOf(0 to Lang.t(Lang.Key.PLAYER_QUALITY_AUTO)).plus(state.videoQualities.map { it to "${it}p" }).forEach { (h, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(label, color = if (state.videoQuality == h) palette.primary else palette.onSurface)
                                        },
                                        leadingIcon = { Icon(EmberIcons.Quality, null, Modifier.size(24.dp)) },
                                        onClick = {
                                            player.selectVideoQuality(h)
                                            qualityMenu = false
                                            videoOverlay = false
                                        },
                                        modifier = Modifier.semantics { selected = state.videoQuality == h },
                                    )
                                }
                            }
                        }
                    }
                    }
                } else if (canvas != null) {
                    Box(Modifier.align(Alignment.Center).statusBarsPadding()
                        .padding(top = 56.dp, bottom = 12.dp).fillMaxHeight().aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, palette.onSurface.copy(alpha = .10f), RoundedCornerShape(24.dp))) {
                        Artwork(item?.imageUrl.orEmpty(), Modifier.fillMaxSize())
                        CanvasLoop(canvas, state.playing, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = .08f),
                            .7f to Color.Transparent,
                            1f to ambient.copy(alpha = .42f),
                        )))
                    }
                } else if (fullArtwork && !item?.imageUrl.isNullOrBlank()) {
                    Crossfade(item?.imageUrl.orEmpty(), Modifier.fillMaxSize(), tween(EmberMotion.Ambient), label = "Ember cover") { url ->
                        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.82f),
                        0.20f to palette.background.copy(alpha = 0.74f),
                        0.42f to Color.Transparent,
                        1f to palette.surfaceContainer,
                    )))
                } else {
                    Crossfade(
                        item?.imageUrl.orEmpty(),
                        Modifier.align(Alignment.Center).statusBarsPadding()
                            .padding(top = 42.dp, bottom = 8.dp).fillMaxHeight().aspectRatio(1f),
                        tween(EmberMotion.Ambient),
                        label = "Ember compact cover",
                    ) { url -> Artwork(url, Modifier.fillMaxSize()) }
                }
                Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(EmberIcons.KeyboardArrowLeft, contentDescription = Lang.t(Lang.Key.JAM_BACK))
                    }
                    Text(
                        Lang.t(Lang.Key.PLAYER_NOW_PLAYING),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box {
                        IconButton(onClick = { menu = true }, enabled = item != null) {
                            Icon(EmberIcons.MoreVert, Lang.t(Lang.Key.PLAYER_ACTIONS))
                        }

                    }
                }
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                if (isEpisode && state.videoAvailable == true) {
                    Row(
                        Modifier.clip(RoundedCornerShape(22.dp))
                            .background(palette.surfaceContainerHigh),
                    ) {
                        SegmentButton(Lang.t(Lang.Key.PLAYER_AUDIO), !state.isVideo, R.drawable.ember_ctx_episode_audio_mode) { player.playEpisodeAudio() }
                        SegmentButton(Lang.t(Lang.Key.PLAYER_VIDEO), state.isVideo, R.drawable.ember_ctx_episode_video_mode) { player.playEpisodeVideo() }
                    }
                } else if (isEpisode) {
                    Text(
                        Lang.t(Lang.Key.PLAYER_PODCAST),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AdaptiveEpisodeTitle(item?.title.orEmpty())
                        Text(item?.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    SaveMediaButton(uri, onLibraryChanged)
                }
                if (state.error != null && item != null) {
                    val message = when {
                        state.waitingForLicenseRetry -> Lang.t(Lang.Key.PLAYER_LICENSE_RETRY_QUEUED)
                        state.error == PukPlayer.LICENSE_NETWORK_REQUIRED -> Lang.t(Lang.Key.PUK_LICENSE_NETWORK)
                        state.error == PukPlayer.LICENSE_RATE_LIMITED -> Lang.t(Lang.Key.PLAYER_LICENSE_RATE_LIMITED)
                        state.positionMs > 0 -> Lang.t(Lang.Key.PLAYER_PLAYBACK_INTERRUPTED)
                        else -> Lang.t(Lang.Key.PLAYER_TRACK_START_FAILED)
                    }
                    Text(message, color = MaterialTheme.colorScheme.error)
                    val trackId = item.uri.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(':')
                    if (trackId != null && trackId.matches(Regex("[a-zA-Z0-9]{22}"))) {
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:track:$trackId"))
                                .setPackage("com.spotify.music")
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, Lang.t(Lang.Key.PLAYER_SPOTIFY_APP_UNAVAILABLE), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(Lang.t(Lang.Key.PLAYER_OPEN_IN_SPOTIFY)) }
                    }
                }

                if (state.error == null) PlaybackSeekBar(
                    playback,
                    player::seekTo,
                    chapterStarts = if (isEpisode) podcastChapters.orEmpty().map { it.startMs } else emptyList(),
                )

                // transport
                if (isEpisode) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            var speedMenu by remember { mutableStateOf(false) }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(palette.surfaceContainerHigh)
                                    .clickable { speedMenu = true },
                            ) {
                                Text(speedLabel(state.speed), style = MaterialTheme.typography.labelLarge)
                                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(Lang.t(Lang.Key.PLAYER_PLAYBACK_SPEED)) },
                                        leadingIcon = { Icon(EmberIcons.PlaybackSpeed, contentDescription = null, tint = palette.primary) },
                                        enabled = false,
                                        onClick = {},
                                    )
                                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = { Text(speedLabel(speed)) },
                                            modifier = Modifier.semantics { selected = state.speed == speed },
                                            trailingIcon = {
                                                if (state.speed == speed) Icon(EmberIcons.Check, contentDescription = null, tint = palette.primary)
                                                else Spacer(Modifier.size(24.dp))
                                            },
                                            onClick = { player.setSpeed(speed); speedMenu = false },
                                        )
                                    }
                                }
                            }
                            EmberTransportButton(onClick = { player.skipBy(-15_000L) }) { press ->
                                EmberAnimatedIcon(ImageVector.vectorResource(R.drawable.ember_ctx_episode_rewind_15), Lang.t(Lang.Key.PLAYER_REWIND_15S), press, Modifier.size(34.dp))
                            }
                            EmberTransportButton(
                                onClick = { player.previous() },
                                enabled = state.index > 0,
                            ) { press ->
                                EmberAnimatedIcon(EmberIcons.SkipPrevious, Lang.t(Lang.Key.PLAYER_PREVIOUS), press, Modifier.size(32.dp))
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(88.dp).background(
                                    Brush.radialGradient(
                                        0.65f to palette.primary.copy(alpha = 0.32f),
                                        1f to Color.Transparent,
                                    ),
                                    CircleShape,
                                ),
                            ) {
                                Box(
                                    modifier = Modifier.size(68.dp).clip(CircleShape)
                                        .background(palette.primary)
                                        .clickable(playPress, LocalIndication.current, onClick = player::togglePlayPause),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (state.buffering) {
                                        LinearProgressIndicator(modifier = Modifier.width(40.dp))
                                    } else {
                                        EmberAnimatedIcon(
                                            if (state.playing) EmberIcons.Pause else EmberIcons.PlayArrow,
                                            contentDescription = when {
                                                state.waitingForLicenseRetry -> Lang.t(Lang.Key.PLAYER_CANCEL_RETRY)
                                                state.playing -> Lang.t(Lang.Key.JAM_PAUSE)
                                                else -> Lang.t(Lang.Key.JAM_PLAY)
                                            },
                                            source = playPress,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(40.dp),
                                        )
                                    }
                                }
                            }
                            EmberTransportButton(onClick = { player.next() }, enabled = state.index in 0 until state.queue.lastIndex) { press ->
                                EmberAnimatedIcon(EmberIcons.SkipNext, Lang.t(Lang.Key.PLAYER_NEXT), press, Modifier.size(32.dp))
                            }
                            EmberTransportButton(onClick = { player.skipBy(15_000L) }) { press ->
                                EmberAnimatedIcon(ImageVector.vectorResource(R.drawable.ember_ctx_episode_forward_15), Lang.t(Lang.Key.PLAYER_FORWARD_15S), press, Modifier.size(34.dp))
                            }
                        }
                    }
                } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box {
                        EmberTransportButton(onClick = { shuffleMenu = true }, enabled = item != null) { press ->
                            ShuffleIcon(state.shuffle, source = press, description = shuffleLabel(state.shuffle),
                                tint = if (state.shuffle == PukPlayer.Shuffle.OFF) palette.onSurfaceVariant else palette.primary)
                        }
                        DropdownMenu(expanded = shuffleMenu, onDismissRequest = { shuffleMenu = false }) {
                            PukPlayer.Shuffle.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(shuffleLabel(mode)) },
                                    leadingIcon = {
                                        if (mode == PukPlayer.Shuffle.OFF) {
                                            Icon(EmberIcons.FormatListNumbered, contentDescription = null)
                                        } else {
                                            ShuffleIcon(mode, tint = palette.onSurface)
                                        }
                                    },
                                    trailingIcon = {
                                        if (state.shuffle == mode) Icon(EmberIcons.Check, Lang.t(Lang.Key.JAM_SELECTED), tint = palette.primary)
                                    },
                                    onClick = { player.setShuffle(mode); shuffleMenu = false },
                                )
                            }
                        }
                    }
                    EmberTransportButton(onClick = { player.previous() }, enabled = state.index > 0 || state.repeat == PukPlayer.Repeat.QUEUE) { press ->
                        EmberAnimatedIcon(EmberIcons.SkipPrevious, Lang.t(Lang.Key.PLAYER_PREVIOUS), press, Modifier.size(32.dp))
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(88.dp).background(
                            Brush.radialGradient(
                                0.65f to palette.primary.copy(alpha = 0.32f),
                                1f to Color.Transparent,
                            ),
                            CircleShape,
                        ),
                    ) {
                        Box(
                            modifier = Modifier.size(68.dp).clip(CircleShape)
                                .background(palette.primary)
                                .clickable(playPress, LocalIndication.current, onClick = player::togglePlayPause),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.buffering) {
                                LinearProgressIndicator(modifier = Modifier.width(40.dp))
                            } else {
                                EmberAnimatedIcon(
                                    if (state.playing) EmberIcons.Pause else EmberIcons.PlayArrow,
                                    contentDescription = when {
                                        state.waitingForLicenseRetry -> Lang.t(Lang.Key.PLAYER_CANCEL_RETRY)
                                        state.playing -> Lang.t(Lang.Key.JAM_PAUSE)
                                        else -> Lang.t(Lang.Key.JAM_PLAY)
                                    },
                                    source = playPress,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                    }
                    EmberTransportButton(onClick = { player.next() }, enabled = state.index in 0 until state.queue.lastIndex ||
                        state.repeat == PukPlayer.Repeat.QUEUE) { press ->
                        EmberAnimatedIcon(EmberIcons.SkipNext, Lang.t(Lang.Key.PLAYER_NEXT), press, Modifier.size(32.dp))
                    }
                    EmberTransportButton(onClick = { player.cycleRepeat() }) { press ->
                        val mode = state.repeat
                        val description = when (mode) {
                            PukPlayer.Repeat.OFF -> Lang.t(Lang.Key.PLAYER_REPEAT_QUEUE_ON)
                            PukPlayer.Repeat.QUEUE -> Lang.t(Lang.Key.PLAYER_REPEAT_TRACK_ON)
                            PukPlayer.Repeat.TRACK -> Lang.t(Lang.Key.PLAYER_REPEAT_OFF)
                        }
                        EmberAnimatedIcon(when (mode) {
                            PukPlayer.Repeat.TRACK -> EmberIcons.RepeatOne
                            PukPlayer.Repeat.QUEUE -> EmberIcons.RepeatActive
                            PukPlayer.Repeat.OFF -> EmberIcons.Repeat
                        },
                            description, press, tint = if (mode == PukPlayer.Repeat.OFF) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary)
                    }
                }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOutputs) {
                        Icon(EmberIcons.Devices, Lang.t(Lang.Key.PLAYER_SELECT_DEVICE), Modifier.size(24.dp))
                    }
                    if (availableCanvas != null) {
                        val canvasLabel = when {
                            !canvasEnabled -> Lang.t(Lang.Key.PLAYER_CANVAS_ENABLE)
                            canvasLoad.loading -> Lang.t(Lang.Key.PLAYER_CANVAS_LOADING)
                            canvas == null -> Lang.t(Lang.Key.PLAYER_CANVAS_UNAVAILABLE)
                            else -> Lang.t(Lang.Key.PLAYER_CANVAS_DISABLE)
                        }
                        IconButton(onClick = { canvasEnabled = !canvasEnabled }) {
                            Icon(painterResource(R.drawable.ember_ctx_canvas), canvasLabel, Modifier.size(24.dp),
                                tint = if (canvasEnabled && canvas != null) palette.primary else palette.onSurfaceVariant)
                        }
                    }
                    if (isEpisode && !podcastChapters.isNullOrEmpty()) {
                        IconButton(onClick = { chaptersSheet = true }) {
                            Icon(painterResource(R.drawable.ember_ctx_episode_chapters), Lang.t(Lang.Key.PLAYER_CHAPTERS), Modifier.size(24.dp))
                        }
                    }
                    if (isEpisode) {
                        IconButton(onClick = { transcriptSheet = true }) {
                            Icon(EmberIcons.Transcript, Lang.t(Lang.Key.PLAYER_TRANSCRIPT), Modifier.size(24.dp))
                        }
                    }
                    IconButton(onClick = { queueScroll = LazyListState((state.index - 1).coerceAtLeast(0)) }) {
                        Icon(painterResource(R.drawable.ember_ctx_open_queue), Lang.t(Lang.Key.PLAYER_QUEUE), Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).onGloballyPositioned { coordinates ->
                    controlsEnd = coordinates.positionInRoot().y + playerScroll.value
                })
                val artist = nowPlayingArtist(item?.artistUri.orEmpty())
                if (!isEpisode && item != null) {
                    NowPlayingArtistCard(item, artist, onOpenArtist)
                }
                if (!isEpisode) lyrics?.let { LyricsPreview(uri, it, playback) }
                if (!isEpisode && item != null) {
                    NowPlayingReleases(artist, item.albumUri, onOpenAlbum)
                    TrackDetailsCard(item.copy(durationMs = state.durationMs.takeIf { it > 0 } ?: item.durationMs))
                }
                if (isEpisode && item != null) {
                    podcastChapters?.takeIf { it.isNotEmpty() }?.let { spans ->
                        Text(
                            Lang.t(Lang.Key.PLAYER_CHAPTERS),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        spans.forEach { chapter ->
                            var showFullChapterTitle by remember(chapter.startMs) { mutableStateOf(false) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .contextGesture(
                                        title = chapter.title,
                                        onClick = { player.seekTo(chapter.startMs) },
                                        onActions = { showFullChapterTitle = true },
                                    )
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    podcastDuration(chapter.startMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (showFullChapterTitle) {
                                ChapterActionSheet(uri, chapter,
                                    onSeek = { player.seekTo(chapter.startMs) }, onDismiss = { showFullChapterTitle = false })
                            }
                        }
                    }
                }
            }
        }
        // Top fade: content passing under the transparent status bar never
        // shows clipped controls; the compact panel takes over above it.
        val fadePx = with(LocalDensity.current) { 80.dp.toPx() }
        val topFade by remember(playerScroll, fadePx) {
            derivedStateOf { (playerScroll.value / fadePx).coerceIn(0f, 1f) }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() + 44.dp })
                .background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.96f * topFade),
                        0.6f to palette.background.copy(alpha = 0.72f * topFade),
                        1f to Color.Transparent,
                    ),
                ),
        )
        AnimatedVisibility(
            visible = compactVisible && item != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(EmberMotion.Fast)),
            exit = fadeOut(tween(EmberMotion.Fast)),
        ) {
            if (item != null) {
                Row(
                    Modifier.fillMaxWidth().background(lerp(ambient, palette.background, 0.32f))
                        .statusBarsPadding().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(EmberIcons.KeyboardArrowLeft, Lang.t(Lang.Key.PLAYER_MINIMIZE))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SaveMediaButton(item.uri, onLibraryChanged)
                    IconButton(onClick = player::togglePlayPause) {
                        Icon(
                            if (state.playing) EmberIcons.Pause else EmberIcons.PlayArrow,
                            if (state.playing) Lang.t(Lang.Key.JAM_PAUSE) else Lang.t(Lang.Key.JAM_PLAY),
                        )
                    }
                }
            }
        }
        SnackbarHost(capturePrompt, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        // The status strip stays transparent while scrolling: content passes
        // beneath it, and the compact panel brings its own background once it
        // slides in, covering the strip without an extra scrim.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSeekBar(
    playback: State<PukPlayer.State>,
    onSeek: (Long) -> Unit,
    chapterStarts: List<Long> = emptyList(),
    enabledOverride: Boolean = true,
    positionOverrideMs: Long? = null,
    durationOverrideMs: Long? = null,
) {
    val state = playback.value
    val palette = MaterialTheme.colorScheme
    var seekToMs by remember(state.loadId) { mutableStateOf<Float?>(null) }
    var pendingSeek by remember(state.loadId) { mutableStateOf<Pair<Long, Float>?>(null) }
    LaunchedEffect(pendingSeek, state.seekRevision, state.error) {
        val pending = pendingSeek ?: return@LaunchedEffect
        if (state.seekRevision == pending.first && state.error == null) delay(1_000)
        pendingSeek = null
    }
    // Override mode (Jam SOLO mirrors): the engine holds no playback, so the
    // bar runs on the authoritative server position; engine feedback paths
    // (pendingSeek/seekRevision) don't apply.
    val duration = (durationOverrideMs ?: state.durationMs).coerceAtLeast(1L)
    val enginePosition = if (positionOverrideMs == null) (seekToMs ?: pendingSeek?.second ?: state.positionMs.toFloat()) else null
    val position = (positionOverrideMs?.toFloat() ?: enginePosition ?: 0f).coerceIn(0f, duration.toFloat())
    val fraction = seekFraction(position, duration)
    val enabled = enabledOverride && duration > 1L &&
        (positionOverrideMs != null || (!state.buffering && state.licenseReady && state.durationMs > 0))
    Slider(
        value = fraction,
        enabled = enabled,
        onValueChange = { seekToMs = it * duration },
        onValueChangeFinished = {
            seekToMs?.let {
                if (enabled) {
                    pendingSeek = state.seekRevision to it
                    onSeek(it.toLong())
                }
            }
            seekToMs = null
        },
        thumb = {
            Box(Modifier.size(12.dp).background(
                if (enabled) palette.primary else palette.onSurfaceVariant, CircleShape,
            ))
        },
        track = {
            BoxWithConstraints(Modifier.fillMaxWidth().height(4.dp)
                .clip(CircleShape).background(palette.tertiary.copy(alpha = 0.72f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.primary))
                // Chapter boundary marks, only for known valid spans.
                if (state.durationMs > 0) {
                    chapterStarts.filter { it in 1 until state.durationMs }.forEach { start ->
                        val mark = start.toFloat() / state.durationMs
                        if (mark > 0.01f && mark < 0.99f) {
                            Box(
                                Modifier
                                    .offset(x = maxWidth * mark)
                                    .width(2.dp).fillMaxHeight()
                                    .background(palette.onSurface.copy(alpha = 0.8f), CircleShape),
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatMs(position.toLong()), style = MaterialTheme.typography.labelSmall)
        Text(formatMs(duration), style = MaterialTheme.typography.labelSmall)
    }
}

internal fun speedLabel(speed: Float): String = when (speed) {
    0.5f -> "0,5×"; 0.75f -> "0,75×"; 1f -> "1,0×"
    1.25f -> "1,25×"; 1.5f -> "1,5×"; 1.75f -> "1,75×"
    else -> "2,0×"
}

@Composable
private fun LyricsPreview(uri: String, available: com.project.puk.api.Lyrics, playback: State<PukPlayer.State>) {
    val palette = MaterialTheme.colorScheme
    var expandedLyrics by remember(uri) { mutableStateOf(false) }
    val active by remember(available, playback) {
        derivedStateOf {
            if (available.synced) {
                val position = playback.value.positionMs
                available.lines.indexOfLast { it.startMs <= position }
            } else -1
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp)
            .clip(RoundedCornerShape(18.dp)).background(palette.surfaceContainerHigh)
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Lang.t(Lang.Key.PLAYER_LYRICS), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { expandedLyrics = !expandedLyrics }) {
                Text(if (expandedLyrics) Lang.t(Lang.Key.PLAYER_COLLAPSE) else Lang.t(Lang.Key.PLAYER_SHOW_ALL))
            }
        }
        val start = if (expandedLyrics) 0 else active.coerceAtLeast(0)
        val visible = if (expandedLyrics) available.lines else available.lines.drop(start).take(8)
        visible.forEachIndexed { index, line ->
            Text(line.text, style = MaterialTheme.typography.titleMedium,
                color = if (start + index == active) MaterialTheme.colorScheme.primary
                    else palette.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SleepTimerDialog(activeMinutes: Int?, onChange: (Int?) -> Unit, onDismiss: () -> Unit) {
    EmberActionSheet(
        onDismissRequest = onDismiss,
        heading = Lang.t(Lang.Key.PLAYER_SLEEP_TIMER),
        headingIcon = painterResource(R.drawable.ember_ctx_sleep_timer),
        text = {
            Column {
                activeMinutes?.let { Text(Lang.t(Lang.Key.PLAYER_TIMER_SET, it)) }
                listOf(15, 30, 60).forEach { minutes ->
                    EmberSheetChoice("$minutes " + Lang.t(Lang.Key.SLEEP_TIMER_MIN), selected = activeMinutes == minutes) {
                        onChange(minutes)
                        onDismiss()
                    }
                }
                if (activeMinutes != null) {
                    TextButton(onClick = {
                        onChange(null)
                        onDismiss()
                    }) { Text(Lang.t(Lang.Key.PLAYER_TIMER_DISABLE)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.PLAYER_CLOSE)) } },
    )
}

private fun shuffleLabel(mode: PukPlayer.Shuffle): String = when (mode) {
    PukPlayer.Shuffle.OFF -> Lang.t(Lang.Key.JAM_IN_ORDER)
    PukPlayer.Shuffle.ON -> Lang.t(Lang.Key.PLAYER_SHUFFLE_ON)
    PukPlayer.Shuffle.SMART -> Lang.t(Lang.Key.PLAYER_SMART_SHUFFLE)
}

@Composable
private fun ShuffleIcon(
    mode: PukPlayer.Shuffle, tint: Color, description: String? = null,
    source: MutableInteractionSource? = null,
) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (source == null) {
            Icon(EmberIcons.Shuffle, description, Modifier.size(22.dp), tint = tint)
        } else {
            EmberAnimatedIcon(EmberIcons.Shuffle, description, source, Modifier.size(22.dp), tint)
        }
        if (mode == PukPlayer.Shuffle.SMART) {
            Icon(EmberIcons.AutoAwesome, null, Modifier.align(Alignment.TopEnd).size(10.dp), tint = tint)
        }
    }
}

@Composable
private fun QueueDialog(
    queueScroll: LazyListState,
    state: PukPlayer.State,
    onDismiss: () -> Unit,
    onActions: (Int) -> Unit,
    onClear: (() -> Unit)? = null,
    onMove: ((Int, Int) -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val canJump by remember(queueScroll, state.index) {
        derivedStateOf {
            val visible = queueScroll.layoutInfo.visibleItemsInfo
            state.index >= 0 && visible.isNotEmpty() && visible.none { it.index == state.index + 1 }
        }
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f

    // --- Перестановка: механика из JamQueue (проверена на устройстве) ---
    // Арифметическая цель без чтения раскладки внутри жеста, спейсеры-якоря
    // по краям списка, плавающая карточка над списком, страховка на смерть
    // строки. Семантика очереди плеера: двигать можно только предстоящие
    // треки (после текущего) — пройденные не трогаем.
    var dragFrom by remember { mutableStateOf(-1) }       // исходный индекс захвата (очередь)
    var dragTarget by remember { mutableStateOf(-1) }
    var displacement by remember { mutableStateOf(0f) }
    var scrolledTotal by remember { mutableStateOf(0f) }
    var stride by remember { mutableStateOf(1f) }
    var startCenter by remember { mutableStateOf(0f) }    // центр захваченной строки (viewport px)
    var fingerDown by remember { mutableStateOf(false) }
    var dragVisual by remember { mutableStateOf<PukPlayer.QueueItem?>(null) }
    var dragCenterY by remember { mutableStateOf(0f) }    // центр карточки (координаты рамки диалога)
    var boxTopY by remember { mutableStateOf(0f) }
    var listTopY by remember { mutableStateOf(0f) }
    val queueNow by rememberUpdatedState(state.queue)
    val indexNow by rememberUpdatedState(state.index)
    val moveNow by rememberUpdatedState(onMove)
    val spacingPx = with(LocalDensity.current) { 4.dp.toPx() }

    // Живое превью: пары (исходный индекс в очереди, трек). Исходный индекс —
    // стабильный ключ строки: в очереди бывают дубликаты URI, а перестановка
    // должна следовать за конкретным вхождением трека.
    val display = remember(state.queue, dragFrom, dragTarget) {
        val pairs = state.queue.mapIndexed { i, item -> i to item }
        if (dragFrom < 0 || dragFrom !in pairs.indices || dragTarget !in pairs.indices || dragTarget == dragFrom) pairs
        else pairs.toMutableList().apply { add(dragTarget, removeAt(dragFrom)) }
    }

    fun retarget() {
        val queue = queueNow
        val current = indexNow
        val last = queue.lastIndex.coerceAtLeast(0)
        val upcomingFirst = minOf(current + 1, last)
        val raw = dragFrom + kotlin.math.round((displacement + scrolledTotal) / stride).toInt()
        val visible = queueScroll.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()
        val lo = first?.index ?: 0
        val hiD = visible.lastOrNull()?.index ?: (queue.lastIndex + 1)
        val windowSize = hiD - lo + 1
        // display-позиция 0 — верхний спейсер; строки очереди — позиции 1..size,
        // их очередь-индекс несут сами элементы display.
        val spacerAnchor = first != null && first.index == 0
        val anchorQ = if (spacerAnchor || first == null) -1 else display.getOrNull(first.index - 1)?.first ?: -1
        // Позиция якоря после удаления перетаскиваемого элемента (очередь-координаты).
        val anchorAfter = if (anchorQ > dragFrom) anchorQ - 1 else anchorQ
        val floor = when {
            spacerAnchor -> upcomingFirst            // спейсер не двигается — предел задаёт семантика очереди
            else -> maxOf(upcomingFirst, anchorAfter + 1)
        }.coerceAtMost(last)
        // Нижняя граница: низ окна после сдвига якоря.
        val ceil = when {
            spacerAnchor -> hiD - 1
            else -> anchorAfter + windowSize - 1
        }.coerceAtLeast(upcomingFirst).coerceAtMost(last)
        dragTarget = when {
            raw < dragFrom -> when {
                dragFrom < anchorQ -> floor.coerceAtMost(last)
                dragFrom == anchorQ -> maxOf(dragFrom, upcomingFirst)
                else -> maxOf(raw, minOf(floor, dragFrom)).coerceAtMost(dragFrom)
            }
            raw > dragFrom -> maxOf(raw, minOf(floor, ceil)).coerceAtMost(ceil)
            else -> if (dragFrom == anchorQ || spacerAnchor) maxOf(dragFrom, upcomingFirst) else maxOf(minOf(dragFrom, ceil), floor)
        }.coerceIn(minOf(upcomingFirst, last), last)
        dragCenterY = (listTopY - boxTopY) + startCenter + displacement
    }

    fun resetDrag() {
        dragFrom = -1
        dragTarget = -1
        fingerDown = false
        dragVisual = null
    }

    fun endDrag() {
        val from = dragFrom
        val target = dragTarget
        fingerDown = false
        if (from in queueNow.indices && target in queueNow.indices && target > indexNow && target != from) {
            resetDrag()
            moveNow?.invoke(from, target)
        } else resetDrag()
    }

    // Точный детектор смерти жеста: строка с pointerInput ушла из композиции
    // при живом пальце — onDragEnd не придёт никогда, завершаем сами.
    val onDragDead = {
        if (dragFrom >= 0 && fingerDown) endDrag()
        Unit
    }

    // Автопрокрутка у краёв — 800 px/с по фактическому времени; живёт, пока
    // палец на экране; на границах диапазона (после текущего / конец очереди)
    // крутить список дальше нет смысла.
    LaunchedEffect(dragFrom, fingerDown) {
        if (dragFrom < 0 || !fingerDown) return@LaunchedEffect
        val edgePx = 132f
        val speedPxPerSec = 800f
        var lastNanos = System.nanoTime()
        while (isActive) {
            delay(16)
            val now = System.nanoTime()
            val dtSec = ((now - lastNanos).coerceAtLeast(1)) / 1_000_000_000f
            lastNanos = now
            val step = speedPxPerSec * dtSec
            val bottom = queueScroll.layoutInfo.viewportEndOffset.toFloat()
            val upcomingFirst = minOf(indexNow + 1, queueNow.lastIndex)
            val overscroll = when {
                startCenter + displacement < edgePx && dragTarget > upcomingFirst -> -step
                startCenter + displacement > bottom - edgePx && dragTarget < queueNow.lastIndex -> step
                else -> 0f
            }
            if (overscroll != 0f) {
                scrolledTotal += queueScroll.scrollBy(overscroll)
                retarget()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(24.dp),
            color = palette.surfaceContainerHigh,
            border = BorderStroke(1.dp, palette.outlineVariant),
        ) {
            Box(Modifier.onGloballyPositioned { boxTopY = it.positionInRoot().y }) {
                Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(Lang.t(Lang.Key.PLAYER_QUEUE), Modifier.padding(8.dp), style = MaterialTheme.typography.headlineSmall)
                        onClear?.let { clear ->
                            TextButton(onClick = clear) {
                                Icon(painterResource(R.drawable.ember_ctx_clear_queue), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(Lang.t(Lang.Key.PLAYER_CLEAR_UP_NEXT))
                            }
                        }
                        if (canJump) {
                            TextButton(onClick = {
                                scope.launch { queueScroll.scrollToItem(state.index.coerceAtLeast(0)) }
                            }) {
                                Icon(painterResource(R.drawable.ember_ctx_jump_to_current), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(Lang.t(Lang.Key.PLAYER_JUMP_TO_CURRENT))
                            }
                        }
                    }
                    if (state.loadingSuggestions) {
                        Text(Lang.t(Lang.Key.PLAYER_FINDING_SIMILAR), Modifier.padding(8.dp), color = palette.onSurfaceVariant)
                    } else if (state.shuffle == PukPlayer.Shuffle.SMART && state.suggestedUris.isEmpty()) {
                        Text(Lang.t(Lang.Key.PLAYER_NO_SIMILAR_TRACKS), Modifier.padding(8.dp), color = palette.onSurfaceVariant)
                    }
                    LazyColumn(
                        state = queueScroll,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                            .onGloballyPositioned { listTopY = it.positionInRoot().y },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Спейсеры-якоря: перестановка их никогда не сдвигает, поэтому
                        // слот жеста может вставать на первую/последнюю позицию предстоящих.
                        item { Box(Modifier.fillMaxWidth().height(1.dp)) }
                        itemsIndexed(display, key = { _, pair -> pair.first }) { displayIndex, pair ->
                            val (origin, track) = pair
                            val current = origin == state.index
                            val dragging = dragFrom == origin && fingerDown
                            val shape = RoundedCornerShape(12.dp)
                            if (dragging) {
                                DisposableEffect(origin) {
                                    onDispose { onDragDead() }
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().clip(shape)
                                    .alpha(if (dragging) 0f else 1f)
                                    .background(if (current) palette.primary.copy(alpha = 0.12f) else Color.Transparent)
                                    .contextGesture(track.title, { onSelect(origin) }, { onActions(origin) })
                                    .semantics { selected = current }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Artwork(track.imageUrl, Modifier.size(40.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, style = MaterialTheme.typography.titleSmall,
                                        color = if (current) palette.primary else palette.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (track.artist.isNotBlank()) {
                                        Text(track.artist, style = MaterialTheme.typography.bodySmall,
                                            color = palette.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (onMove != null && origin > state.index) {
                                    val canMoveUp = origin > state.index + 1
                                    val canMoveDown = origin < state.queue.lastIndex
                                    Icon(painterResource(R.drawable.ember_ctx_reorder_queue), Lang.t(Lang.Key.JAM_REORDER),
                                        modifier = Modifier.size(44.dp).padding(8.dp)
                                            .semantics {
                                                customActions = buildList {
                                                    if (canMoveUp) add(CustomAccessibilityAction(Lang.t(Lang.Key.JAM_MOVE_UP)) {
                                                        moveNow?.invoke(origin, origin - 1)
                                                        true
                                                    })
                                                    if (canMoveDown) add(CustomAccessibilityAction(Lang.t(Lang.Key.JAM_MOVE_DOWN)) {
                                                        moveNow?.invoke(origin, origin + 1)
                                                        true
                                                    })
                                                }
                                            }
                                            .pointerInput(origin) {
                                                // Ручка: перетаскивание начинается сразу при движении —
                                                // окно long-press ломало быстрые тяги (как в Jam).
                                                detectDragGestures(
                                                    onDragStart = { _ ->
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        resetDrag()
                                                        fingerDown = true
                                                        val info = queueScroll.layoutInfo.visibleItemsInfo
                                                            .firstOrNull { it.index == displayIndex + 1 }
                                                        dragFrom = origin
                                                        dragTarget = origin
                                                        stride = (info?.size ?: 0) + spacingPx
                                                        startCenter = info?.let { it.offset + it.size / 2f } ?: 0f
                                                        displacement = 0f
                                                        scrolledTotal = 0f
                                                        dragVisual = track
                                                        dragCenterY = (listTopY - boxTopY) + startCenter
                                                    },
                                                    onDragCancel = { endDrag() },
                                                    onDragEnd = { endDrag() },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        displacement += amount.y
                                                        retarget()
                                                    },
                                                )
                                            })
                                }
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    val suggested = track.uri in state.suggestedUris
                                    Icon(
                                        if (suggested) EmberIcons.AutoAwesome else EmberIcons.PlayArrow,
                                        contentDescription = when {
                                            current -> Lang.t(Lang.Key.PLAYER_CURRENT_TRACK)
                                            suggested -> Lang.t(Lang.Key.PLAYER_SIMILAR_TRACK)
                                            else -> null
                                        },
                                        tint = if (current || suggested) palette.primary else palette.onSurfaceVariant,
                                    )
                                }

                            }
                        }
                        item { Box(Modifier.fillMaxWidth().height(1.dp)) }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(Lang.t(Lang.Key.PLAYER_CLOSE)) }
                }
                dragVisual?.let { dragged ->
                    QueueDragCard(dragged, dragCenterY)
                }
            }
        }
    }
}

/** Плавающая карточка перетаскиваемого трека: над списком, следует за пальцем. */
@Composable
private fun QueueDragCard(item: PukPlayer.QueueItem, centerY: Float) {
    var heightPx by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .offset { IntOffset(0, (centerY - heightPx / 2f).roundToInt()) }
                .onSizeChanged { heightPx = it.height }
                .fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Artwork(item.imageUrl, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.artist.isNotBlank()) {
                        Text(item.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}


@Composable
fun MiniPlayer(
    player: PukPlayer,
    onOpen: () -> Unit,
    onActions: (PukPlayer.QueueItem) -> Unit,
    onOutputs: () -> Unit,
    onLibraryChanged: () -> Unit,
) {
    val engineState = player.state.collectAsState()
    val item by remember(engineState) {
        derivedStateOf(structuralEqualityPolicy()) {
            engineState.value.let { it.queue.getOrNull(it.index) }
        }
    }
    val track = item ?: return
    val playing by remember(engineState) { derivedStateOf { engineState.value.playing } }
    val showProgress by remember(engineState) {
        derivedStateOf { engineState.value.let { it.durationMs > 0 && it.error == null } }
    }
    val pausePress = remember { MutableInteractionSource() }

    FloatingPlayerSurface(track.imageUrl) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .contextGesture(track.title, onOpen, { onActions(track) })
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            MiniPlayerMetadata(track.uri, track.imageUrl, track.title, track.artist, Modifier.weight(1f))
            EmberIconButton(EmberIcons.Devices, Lang.t(Lang.Key.PLAYER_SELECT_DEVICE), onOutputs, tint = MaterialTheme.colorScheme.primary)
            SaveMediaButton(track.uri, onLibraryChanged)
            IconButton(
                onClick = player::togglePlayPause,
                interactionSource = pausePress,
            ) {
                EmberAnimatedIcon(
                    if (playing) EmberIcons.Pause else EmberIcons.PlayArrow,
                    contentDescription = if (playing) Lang.t(Lang.Key.JAM_PAUSE) else Lang.t(Lang.Key.JAM_PLAY),
                    source = pausePress,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (showProgress) {
            LinearProgressIndicator(
                progress = { engineState.value.let { seekFraction(it.positionMs.toFloat(), it.durationMs) } },
                modifier = miniPlayerProgressModifier,
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

private val miniPlayerProgressModifier =
    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 4.dp).height(2.dp)

@Composable
internal fun RestoringMiniPlayer() {
    val colors = MaterialTheme.colorScheme
    FloatingPlayerSurface("") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest))
            Text(
                Lang.t(Lang.Key.PLAYER_RESTORING),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(modifier = miniPlayerProgressModifier)
    }
}

@Composable
internal fun FloatingPlayerSurface(artwork: String, content: @Composable () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val tone by animateColorAsState(artworkAmbient(artwork), tween(EmberMotion.Ambient), label = "Mini-player artwork")
    Surface(
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, palette.onSurface.copy(alpha = 0.12f)),
        shadowElevation = 2.dp,
        color = tone,
        contentColor = palette.onSurface,
    ) {
        Column { content() }
    }
}

private data class MiniPlayerLabel(val uri: String, val title: String, val subtitle: String)

@Composable
internal fun MiniPlayerMetadata(
    uri: String,
    artwork: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val label = MiniPlayerLabel(uri, title, subtitle)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Crossfade(artwork, Modifier.size(40.dp), tween(EmberMotion.Standard), label = "Mini-player cover") { url ->
            Artwork(url, Modifier.fillMaxSize())
        }
        AnimatedContent(
            targetState = label,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn(tween(EmberMotion.Fast, delayMillis = EmberMotion.Fast / 2)) togetherWith
                    fadeOut(tween(EmberMotion.Fast / 2))
            },
            label = "Mini-player track information",
        ) { displayed ->
            val semantics = if (displayed == label) Modifier else Modifier.clearAndSetSemantics {}
            Column(Modifier.fillMaxWidth().then(semantics)) {
                MiniPlayerTitle(displayed.uri, displayed.title)
                Text(
                    displayed.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerTitle(uri: String, title: String) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val animate = motionScale?.scaleFactor != 0f
    key(uri, title) {
        Text(
            title,
            modifier = if (animate) Modifier.basicMarquee(
                iterations = 1,
                initialDelayMillis = 1500,
                velocity = 28.dp,
            ) else Modifier,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActions(
    item: PukPlayer.QueueItem,
    onDismiss: () -> Unit,
    onEnqueue: (PukPlayer.QueueItem) -> Unit,
    onOpen: (Target) -> Unit,
    onLibraryChanged: () -> Unit,
    onSleepTimer: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onOpenEpisode: (() -> Unit)? = null,
    onAddToJam: (() -> Unit)? = null,
) {
    if (item.uri.startsWith("spotify:episode:")) {
        EpisodeActionSheet(item, onDismiss, onEnqueue = { onEnqueue(item.copy()) },
            onOpenShow = { onOpen(Target.Show(it)) }, onLibraryChanged = onLibraryChanged,
            onSleepTimer = onSleepTimer, onOpenEpisode = onOpenEpisode, onPlayNext = onPlayNext)
        return
    }
    val context = LocalContext.current
    val uri = item.uri
    var showMenu by remember(uri) { mutableStateOf(true) }
    var playlistDialog by remember(uri) { mutableStateOf(false) }
    var details by remember(uri) { mutableStateOf(false) }
    var share by remember(uri) { mutableStateOf(false) }
    if (showMenu) {
        EmberSheet(
            onDismissRequest = onDismiss,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(item.imageUrl, Modifier.size(104.dp))
                Text(
                    item.title,
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.artist,
                    Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                SaveMediaButton(uri, onLibraryChanged)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SheetAction(R.drawable.ember_ctx_add_to_playlist, Lang.t(Lang.Key.PL_ADD_TO)) {
                    showMenu = false
                    playlistDialog = true
                }
                SheetAction(R.drawable.ember_ctx_add_to_queue, Lang.t(Lang.Key.SHEET_ADD_TO_QUEUE)) {
                    if (runLocalQueueAction(context) { onEnqueue(item.copy()) }) {
                        showMenu = false
                        Toast.makeText(context, Lang.t(Lang.Key.PLAYER_ADDED_TO_QUEUE), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
                onPlayNext?.let { next ->
                    SheetAction(R.drawable.ember_ctx_play_next, Lang.t(Lang.Key.PLAYER_PLAY_NEXT)) {
                        if (runLocalQueueAction(context, next)) onDismiss()
                    }
                }
                onAddToJam?.let { addToJam ->
                    SheetAction(EmberIcons.Groups, Lang.t(Lang.Key.PLAYER_ADD_TO_JAM)) {
                        addToJam()
                        showMenu = false
                        Toast.makeText(context, Lang.t(Lang.Key.PLAYER_ADDED_TO_JAM_QUEUE), Toast.LENGTH_SHORT).show()
                    }
                }
                val link = "https://open.spotify.com/track/${item.uri.substringAfterLast(':')}"
                SheetAction(R.drawable.ember_ctx_copy_link, Lang.t(Lang.Key.SHEET_COPY_LINK)) { copyContextText(context, item.title, link) }
                if (item.artistUri.isNotEmpty()) {
                    SheetAction(R.drawable.ember_ctx_go_to_artist, Lang.t(Lang.Key.NP_OPEN_ARTIST)) {
                        onDismiss()
                        onOpen(Target.Artist(item.artistUri))
                    }
                }
                if (item.albumUri.startsWith("spotify:album:")) {
                    SheetAction(R.drawable.ember_ctx_go_to_album, Lang.t(Lang.Key.PLAYER_OPEN_ALBUM)) {
                        onDismiss()
                        onOpen(Target.Album(item.albumUri))
                    }
                }
                SheetAction(R.drawable.ember_ctx_track_details, Lang.t(Lang.Key.PLAYER_TRACK_DETAILS)) {
                    showMenu = false
                    details = true
                }
                if (onSleepTimer != null) {
                    SheetAction(R.drawable.ember_ctx_sleep_timer, Lang.t(Lang.Key.PLAYER_SLEEP_TIMER)) {
                        onDismiss()
                        onSleepTimer()
                    }
                }
                SheetAction(R.drawable.ember_ctx_share_track, Lang.t(Lang.Key.JAM_SHARE)) {
                    showMenu = false
                    share = true
                }
            }
        }
    }
    if (details) TrackDetailsSheet(item, onDismiss)
    if (share) TrackShareSheet(item, onDismiss)
    if (playlistDialog) {
        AddToPlaylistDialog(item, onDismiss, onLibraryChanged)
    }
}

@Composable
private fun SaveMediaButton(uri: String, onLibraryChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(uri) { mutableStateOf<Boolean?>(null) }
    var saving by remember(uri) { mutableStateOf(false) }
    val isEpisode = uri.startsWith("spotify:episode:")
    val canSave = isEpisode || uri.startsWith("spotify:track:")
    LaunchedEffect(uri) {
        if (canSave) {
            saved = Spotify.isSaved(context, uri)
        }
    }
    if (canSave) {
        val icon = if (isEpisode) {
            if (saved == true) EmberIcons.Bookmark else EmberIcons.BookmarkBorder
        } else {
            if (saved == true) EmberIcons.Favorite else EmberIcons.FavoriteBorder
        }
        val label = if (isEpisode) {
            if (saved == true) Lang.Key.SHEET_REMOVE_FROM_LIBRARY else Lang.Key.SHEET_SAVE_TO_LIBRARY
        } else {
            if (saved == true) Lang.Key.PLAYER_REMOVE_FROM_FAVORITES else Lang.Key.PLAYER_ADD_TO_FAVORITES
        }
        EmberIconButton(
            icon,
            Lang.t(label),
            enabled = !saving,
            tint = if (saved == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            onClick = {
                saving = true
                scope.launch {
                    val current = saved ?: Spotify.isSaved(context, uri)
                    if (current != null && Spotify.setSaved(context, uri, !current)) {
                        saved = !current
                        onLibraryChanged()
                    } else {
                        android.widget.Toast.makeText(context, Lang.t(Lang.Key.PLAYER_FAVORITES_UPDATE_FAILED), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    saving = false
                }
            },
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}

internal fun seekFraction(positionMs: Float, durationMs: Long): Float =
    if (durationMs <= 0 || !positionMs.isFinite()) 0f else (positionMs / durationMs).coerceIn(0f, 1f)

/** Compact two-state toggle for episode Audio/Video sources. */
@Composable
internal fun SegmentButton(label: String, selected: Boolean, @androidx.annotation.DrawableRes iconRes: Int? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        iconRes?.let { Icon(painterResource(it), null, Modifier.size(24.dp), tint = tint) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
