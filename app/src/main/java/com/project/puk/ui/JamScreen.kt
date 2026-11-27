package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.project.puk.jam.JamClient
import com.project.puk.api.Card
import com.project.puk.api.TrackRow
import com.project.puk.player.PukPlayer
import com.project.puk.jam.JamController
import com.project.puk.jam.isSupportedTrackUri
import com.project.puk.jam.JamPermissions
import com.project.puk.jam.JamParticipant
import com.project.puk.jam.JamQueueEntry
import com.project.puk.ui.theme.EmberIcons
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SpotiPuk Jam screen (compact layout): сервер-backed shared sessions.
 * Distinct from the official Spotify Jam handoff (spec section 0).
 */

/** Публичная HTTPS-ссылка (App Links); spotipuk:// остаётся совместимым fallback. */
internal fun jamInviteHttpsLink(code: String): String =
    com.project.puk.BuildConfig.JAM_INVITE_ORIGIN + "/j/" + code

/** Код из любой формы ссылки: https://jam.spotipuk.stream/j/<code> или spotipuk://jam/join/<code>. */
internal fun jamCodeFromLink(text: String): String = text
    .substringAfter("/j/", text.substringAfter("spotipuk://jam/join/", ""))
    .substringBefore("?")
    .trim()
    .uppercase()
    .take(8)

/** QR encoder shared with the local Jam dialog (same ZXing pipeline). */
internal fun jamQrBitmap(content: String): androidx.compose.ui.graphics.ImageBitmap {
    val size = 512
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Entry state: nothing active → create/join; active session → the room. */
@Composable
fun JamScreen(
    controller: JamController,
    pendingCode: String?,
    onConsumePendingCode: () -> Unit,
    onBack: () -> Unit,
    onAddMusic: () -> Unit,
    account: com.project.puk.core.Auth.Account? = null,
) {
    val ui by controller.session.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showJoin by rememberSaveable { mutableStateOf(false) }
    var showAddMusic by rememberSaveable { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<Throwable, Lang.Key>?>(null) }

    fun joinWith(code: String) {
        joining = true
        error = null
        scope.launch {
            val result = controller.joinByCode(code)
            joining = false
            result
                .onSuccess { showJoin = false; onConsumePendingCode() }
                .onFailure { error = it to Lang.Key.JAM_JOIN_FAILED }
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra(com.google.zxing.client.android.Intents.Scan.RESULT) ?: return@rememberLauncherForActivityResult
        val code = jamCodeFromLink(text)
        if (code.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) joinWith(code)
    }
    var showInvite by rememberSaveable { mutableStateOf(false) }
    var showParticipants by rememberSaveable { mutableStateOf(false) }
    var showEndConfirm by rememberSaveable { mutableStateOf(false) }
    var showMode by rememberSaveable { mutableStateOf(false) }
    var showLinkInfo by rememberSaveable { mutableStateOf(false) }
    var showOverflow by rememberSaveable { mutableStateOf(false) }

    // A pending invite deep link opens the join flow directly (spec section 2).
    LaunchedEffect(pendingCode, ui.session?.sessionId) {
        if (pendingCode != null && ui.session?.sessionId == null) {
            showJoin = true
        }
    }

    // Server notices (частичное добавление, отказ сервера и др.): один тост на сообщение.
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show()
        controller.clearNotice()
    }

    val sessionState = ui.session
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    // Поиск по очереди — как в медиатеке: иконка слева от «Изменить порядок»,
    // поле появляется вместо заголовка «Общая очередь».
    var queueSearchOpen by remember { mutableStateOf(false) }
    var queueSearch by remember { mutableStateOf("") }
    val queueSearchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeQueueSearch() {
        queueSearch = ""
        queueSearchOpen = false
        keyboard?.hide()
    }
    LaunchedEffect(queueSearchOpen) {
        if (queueSearchOpen) {
            queueSearchFocus.requestFocus()
            keyboard?.show()
        }
    }
    BackHandler(enabled = queueSearchOpen) { closeQueueSearch() }
    // REST creation/join finishes before the first WebSocket snapshot arrives.
    // Keep the connecting screen until the room is ready instead of flashing
    // the create/join menu again (also covers a persisted session reconnect).
    if (sessionState == null && (joining || controller.isActive)) {
        Column(Modifier.fillMaxSize()) {
            ProfilePageHeader("SpotiPuk Jam", onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator()
                Text(Lang.t(Lang.Key.JAM_CONNECTING), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (sessionState == null) {
        Column(Modifier.fillMaxSize()) {
            ProfilePageHeader("SpotiPuk Jam", onBack)
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(EmberIcons.Groups, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    Lang.t(Lang.Key.JAM_LISTEN_TOGETHER_ONLINE),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    Lang.t(Lang.Key.JAM_INTRO_QUEUE_LINE).trimEnd() + " " +
                        Lang.t(Lang.Key.JAM_INTRO_OWN_ACCOUNT_LINE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (controller.restorePendingNow()) {
                    OutlinedButton(
                        onClick = {
                            controller.restorePersonalQueueNow()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Lang.t(Lang.Key.JAM_RESTORE_MY_QUEUE)) }
                    Spacer(Modifier.height(8.dp))
                }
                if (joining) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                } else {
                    Button(
                        onClick = {
                            joining = true
                            error = null
                            scope.launch {
                                val result = controller.createSession("", "", JamPermissions())
                                joining = false
                                result.onFailure { error = it to Lang.Key.JAM_CREATE_SESSION_FAILED }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Lang.t(Lang.Key.JAM_CREATE_JAM)) }
                    OutlinedButton(
                        onClick = { showJoin = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Lang.t(Lang.Key.JAM_JOIN_BY_CODE)) }
                    OutlinedButton(
                        onClick = {
                            scannerLauncher.launch(
                                android.content.Intent(context, JamScannerActivity::class.java),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Lang.t(Lang.Key.JAM_SCAN_QR_CODE)) }
                    error?.let { (failure, fallback) ->
                        Text(failure.message ?: Lang.t(fallback), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (showJoin || pendingCode != null) {
            JamJoinSheet(
                initialCode = pendingCode,
                onDismiss = { showJoin = false; onConsumePendingCode() },
                onJoin = { code -> joinWith(code) },
            )
        }
        return
    }

    // ---- active session ----
    val playback = sessionState.playback
    val me = sessionState.me()
    val isHost = me?.isHost == true
    val connectedCount = sessionState.participants.count { it.isConnected }
    val current = playback.itemId?.let { id -> sessionState.queue.firstOrNull { it.itemId == id } }
    val dragVisual = remember { JamDragVisual() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Компактная шапка: назад + название + статус связи + участники.
        JamTopBar(
            connection = ui.connection,
            connectedCount = connectedCount,
            onBack = onBack,
            onConnectionTap = { showLinkInfo = true },
            onParticipants = { showParticipants = true },
        )
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Режим воспроизведения: компактная строка (host может менять).
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(enabled = isHost) { showMode = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(EmberIcons.PlayArrow, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (sessionState.playMode == "SOLO")
                        Lang.t(Lang.Key.JAM_PLAYING_BY_PREFIX) + " " + (sessionState.participant(sessionState.soloParticipantId ?: sessionState.host?.id.orEmpty())?.name ?: Lang.t(Lang.Key.JAM_HOST_FALLBACK))
                    else Lang.t(Lang.Key.JAM_PLAYING_ALL_SYNC),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !isHost -> Lang.t(Lang.Key.JAM_SET_BY_HOST)
                        playback.state == "PLAYING" -> Lang.t(Lang.Key.JAM_MODE_SYNC_SHORT)
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isHost) {
                    Icon(
                        androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_filter),
                        Lang.t(Lang.Key.JAM_CHANGE_WHO_PLAYS),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Единая карточка текущего трека и управления.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (current?.imageUrl?.isNotBlank() == true) {
                            AsyncImage(
                                current.imageUrl,
                                null,
                                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Surface(
                                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(EmberIcons.MusicNote, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                current?.title?.ifBlank { Lang.t(Lang.Key.JAM_NOTHING_PLAYING) } ?: Lang.t(Lang.Key.JAM_NOTHING_PLAYING),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                current?.artist.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val syncLabel = when {
                                playback.state == "PLAYING" && sessionState.playMode == "SOLO" && !controller.playsLocallyNow() ->
                                    Lang.t(Lang.Key.JAM_PLAYING_AT_PREFIX) + " " + (sessionState.participant(sessionState.soloParticipantId ?: sessionState.host?.id.orEmpty())?.name ?: Lang.t(Lang.Key.JAM_HOST_GENITIVE))
                                playback.state == "PLAYING" -> Lang.t(Lang.Key.JAM_SYNC_PLAYING)
                                playback.state == "PREPARING" -> Lang.t(Lang.Key.JAM_PREPARING)
                                playback.state == "PAUSED" -> Lang.t(Lang.Key.JAM_PAUSE)
                                else -> Lang.t(Lang.Key.JAM_WAITING_FOR_QUEUE)
                            }
                            Text(
                                syncLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (current != null) {
                        // Прогресс + транспорт: play/pause точно по центру,
                        // prev/next симметрично (равные размеры по бокам).
                        if (controller.playsLocallyNow()) {
                            PlaybackSeekBar(
                                playback = controller.engineState.collectAsState(),
                                onSeek = { controller.seek(it) },
                            )
                        } else {
                            var serverPos by remember { mutableLongStateOf(0L) }
                            LaunchedEffect(sessionState.playback.stateVersion) {
                                while (true) {
                                    serverPos = controller.serverPositionNow() ?: sessionState.playback.positionMs
                                    delay(500)
                                }
                            }
                            PlaybackSeekBar(
                                playback = controller.engineState.collectAsState(),
                                onSeek = { controller.seek(it) },
                                positionOverrideMs = serverPos,
                                durationOverrideMs = current.durationMs.takeIf { it > 0 },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { controller.previous() },
                                enabled = playback.index > 0,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(EmberIcons.SkipPrevious, Lang.t(Lang.Key.JAM_PREVIOUS_TRACK), Modifier.size(26.dp))
                            }
                            FilledIconButton(
                                onClick = {
                                    if (playback.state == "PLAYING") controller.pause() else controller.play()
                                },
                                modifier = Modifier.size(56.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(
                                    if (playback.state == "PLAYING") EmberIcons.Pause else EmberIcons.PlayArrow,
                                    if (playback.state == "PLAYING") Lang.t(Lang.Key.JAM_PAUSE) else Lang.t(Lang.Key.JAM_PLAY),
                                    Modifier.size(28.dp),
                                )
                            }
                            IconButton(
                                onClick = { controller.next() },
                                enabled = playback.index < sessionState.queue.lastIndex,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(EmberIcons.SkipNext, Lang.t(Lang.Key.JAM_NEXT_TRACK), Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }

            // Заголовок очереди: точное число + поиск + перемешивание + перестановка.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (queueSearchOpen) {
                    androidx.compose.material3.OutlinedTextField(
                        queueSearch,
                        { queueSearch = it },
                        placeholder = { Text(Lang.t(Lang.Key.JAM_SEARCH_QUEUE), maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(queueSearchFocus),
                        leadingIcon = {
                            IconButton(onClick = { closeQueueSearch() }) {
                                Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_CLOSE_SEARCH), Modifier.size(20.dp))
                            }
                        },
                        trailingIcon = if (queueSearch.isNotEmpty()) {
                            { IconButton(onClick = { queueSearch = "" }) { Icon(EmberIcons.Close, Lang.t(Lang.Key.JAM_CLEAR_SEARCH), Modifier.size(20.dp)) } }
                        } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Text(
                        Lang.t(Lang.Key.JAM_SHARED_QUEUE, sessionState.queue.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(onClick = {
                        // Поиск и перестановка несовместимы: индексы фильтра не
                        // совпадают с очередью. Открываем поиск — перестановка выключается.
                        reorderMode = false
                        queueSearchOpen = true
                    }) {
                        Icon(
                            androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_search),
                            Lang.t(Lang.Key.JAM_SEARCH_QUEUE),
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!queueSearchOpen) IconButton(onClick = { reorderMode = !reorderMode }) {
                    Icon(
                        androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_reorder_queue),
                        if (reorderMode) Lang.t(Lang.Key.JAM_FINISH_REORDER) else Lang.t(Lang.Key.JAM_REORDER),
                        Modifier.size(20.dp),
                        tint = if (reorderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { controller.shuffle(!sessionState.shuffleUpcoming) }) {
                    Icon(
                        EmberIcons.Shuffle,
                        if (sessionState.shuffleUpcoming) Lang.t(Lang.Key.JAM_SHUFFLED) else Lang.t(Lang.Key.JAM_IN_ORDER),
                        tint = if (sessionState.shuffleUpcoming) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reorderMode) {
                Text(
                    Lang.t(Lang.Key.JAM_REORDER_HINT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // Общая очередь: список начинается сразу под заголовком. Поиск
            // фильтрует видимые строки (по названию и исполнителю); в режиме
            // фильтра перестановка недоступна — индексы фильтра не совпадают
            // с очередью.
            val queueFilter = queueSearch.trim()
            val visibleQueue = if (queueSearchOpen && queueFilter.isNotEmpty()) {
                sessionState.queue.filter {
                    it.title.contains(queueFilter, true) || it.artist.contains(queueFilter, true)
                }
            } else {
                sessionState.queue
            }
            if (queueSearchOpen && queueFilter.isNotEmpty() && visibleQueue.isEmpty()) {
                Text(
                    Lang.t(Lang.Key.JAM_NOTHING_FOUND),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
            JamQueue(
                queue = visibleQueue,
                playback = playback,
                reordering = reorderMode && !queueSearchOpen,
                dragVisual = dragVisual,
                addedByNameOf = { id -> sessionState.participantName(id) ?: controller.session.cachedName(id) ?: "—" },
                onRemove = { controller.removeTrack(it) },
                onOpen = { itemId ->
                    // Тап по строке: играть выбранный трек (равные права;
                    // сервер проверит и разошлёт prepare/play всем).
                    controller.play(itemId)
                },
                onMove = { itemId, toIndex -> controller.moveTrack(itemId, toIndex) },
                modifier = Modifier.weight(1f),
            )

            // Нижние действия: «Добавить музыку» — основная, «Пригласить» —
            // компактная, завершение/выход — в меню ⋯ с подтверждением.
            Row(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { showAddMusic = true },
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text(Lang.t(Lang.Key.JAM_ADD_MUSIC)) }
                IconButton(
                    onClick = { showInvite = true },
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Icon(EmberIcons.Qr, Lang.t(Lang.Key.JAM_INVITE), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(
                        onClick = { showOverflow = true },
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Icon(EmberIcons.MoreVert, Lang.t(Lang.Key.JAM_MORE), Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        if (isHost) {
                            DropdownMenuItem(
                                text = { Text(Lang.t(Lang.Key.JAM_END_JAM), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOverflow = false
                                    showEndConfirm = true
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(Lang.t(Lang.Key.JAM_LEAVE_JAM)) },
                                onClick = {
                                    showOverflow = false
                                    scope.launch { controller.leave() }
                                    onBack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Диагностика связи — только по запросу (тап по статусу в шапке).
    if (showLinkInfo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLinkInfo = false },
            title = { Text(Lang.t(Lang.Key.JAM_CONNECTION_STATE)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val stateLabel = when (ui.connection) {
                        JamClient.ConnectionState.CONNECTED -> Lang.t(Lang.Key.JAM_STATE_CONNECTED)
                        JamClient.ConnectionState.CONNECTING -> Lang.t(Lang.Key.JAM_STATE_CONNECTING)
                        JamClient.ConnectionState.RECONNECTING -> Lang.t(Lang.Key.JAM_STATE_RECONNECTING)
                        JamClient.ConnectionState.OFFLINE -> Lang.t(Lang.Key.JAM_STATE_OFFLINE)
                    }
                    Text(stateLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    Text(Lang.t(Lang.Key.JAM_CONNECTED_OF, connectedCount, sessionState.participants.size))
                    controller.session.offset?.let {
                        Text(Lang.t(Lang.Key.JAM_RTT_MS, it.rttMs))
                    }
                    me?.let { Text(Lang.t(Lang.Key.JAM_READY_STATE, it.readyState.lowercase())) }
                    if (ui.connection == JamClient.ConnectionState.RECONNECTING) {
                        Text(
                            Lang.t(Lang.Key.JAM_RECOVERY_NOTICE),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLinkInfo = false }) { Text(Lang.t(Lang.Key.JAM_DONE)) } },
        )
    }

    // Плавающая карточка перетаскиваемого трека: рисуется поверх всего
    // экрана и следует за пальцем — список её не обрезает (владелец:
    // при автопрокрутке вверх трек исчезал за границей списка).
    JamDragFloatingCard(dragVisual)

    }

    // Host confirms before ending (spec section 15).
    if (showEndConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text(Lang.t(Lang.Key.JAM_END_JAM_TITLE)) },
            text = { Text(Lang.t(Lang.Key.JAM_END_JAM_TEXT)) },
            confirmButton = {
                Button(onClick = {
                    showEndConfirm = false
                    scope.launch {
                        controller.endSession()
                        onBack()
                    }
                }) { Text(Lang.t(Lang.Key.JAM_END)) }
            },
            dismissButton = { TextButton(onClick = { showEndConfirm = false }) { Text(Lang.t(Lang.Key.JAM_CANCEL)) } },
        )
    }

    if (showInvite) JamInviteSheet(
        inviteCode = sessionState.inviteCode,
        expiresAt = sessionState.inviteExpiresAt,
        isHost = isHost,
        onDismiss = { showInvite = false },
        onRevoke = { controller.revokeInvite(); showInvite = false },
    )
    if (showAddMusic) JamAddMusicSheet(
        controller = controller,
        onDismiss = { showAddMusic = false },
    )
    if (showMode) JamModeSheet(
        participants = sessionState.participants.filter { it.isConnected },
        playMode = sessionState.playMode,
        currentSoloId = sessionState.soloParticipantId.orEmpty(),
        isHost = isHost,
        onDismiss = { showMode = false },
        onSelect = { participantId ->
            showMode = false
            if (participantId == "ALL") controller.updateMode("SYNC")
            else controller.updateMode("SOLO", participantId)
        },
    )
    if (showParticipants) JamParticipantsSheet(
        participants = sessionState.participants,
        myId = sessionState.myParticipantId,
        myAvatarUrl = account?.imageUrl,
        isHost = isHost,
        onDismiss = { showParticipants = false },
        onKick = { controller.kick(it) },
        onTransfer = { controller.transferHost(it) },
    )
}

/** Ключ якорного спейсера над очередью: стабильный якорь для drag-цели. */
private const val DRAG_SPACER_TOP_KEY = "jam-drag-spacer-top"

/**
 * Визуальное состояние перетаскивания для плавающей карточки: что тянем и
 * где центр строки в КООРДИНАТАХ ЭКРАНА (список обновляет на каждое
 * событие; null itemId = перетаскивание не идёт).
 */
class JamDragVisual {
    var itemId by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var imageUrl by mutableStateOf("")
    var centerY by mutableStateOf(0f)
}

/** Плавающая карточка: тот же вид строки, но над всем экраном и с тенью. */
@Composable
private fun JamDragFloatingCard(visual: JamDragVisual) {
    val id = visual.itemId ?: return
    var heightPx by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .offset { IntOffset(0, (visual.centerY - heightPx / 2f).roundToInt()) }
                .fillMaxWidth()
                .onSizeChanged { heightPx = it.height },
        ) {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (visual.imageUrl.isNotBlank()) {
                    AsyncImage(
                        visual.imageUrl,
                        null,
                        Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        visual.title.ifBlank { id },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        visual.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun JamTopBar(
    connection: JamClient.ConnectionState,
    connectedCount: Int,
    onBack: () -> Unit,
    onConnectionTap: () -> Unit,
    onParticipants: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_BACK)) }
        Text(
            "SpotiPuk Jam",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Компактный статус связи вместо отдельной карточки; детали по тапу.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable(onClick = onConnectionTap),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val dotColor = when (connection) {
                    JamClient.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                    JamClient.ConnectionState.OFFLINE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Text(Lang.t(Lang.Key.JAM_CONNECTED_SHORT, connectedCount), style = MaterialTheme.typography.labelMedium)
            }
        }
        IconButton(onClick = onParticipants) { Icon(EmberIcons.Groups, Lang.t(Lang.Key.JAM_PARTICIPANTS)) }
    }
}

/**
 * Общая очередь с drag-and-drop — арифметическая схема: цель и превью
 * считаются от РАВНЫХ строк (dragFrom + смещение пальца / шаг строки) БЕЗ
 * чтения раскладки во время жеста. Прежняя версия читала центры строк,
 * которые в этот момент анимировались — цель «плыла» и трек улетал (видео
 * владельца). Перетаскиваемая строка следует за пальцем (graphicsLayer),
 * соседи расступаются сразу, один MOVE_TRACK на бросок; строка удерживается
 * у пальца до подтверждения сервера (тайм-аут 900 мс), при отказе превью
 * откатывается.
 */
@Composable
private fun JamQueue(
    queue: List<JamQueueEntry>,
    playback: com.project.puk.jam.JamPlayback,
    reordering: Boolean,
    dragVisual: JamDragVisual,
    addedByNameOf: (String) -> String,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
    onMove: (itemId: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var listTopScreen by remember { mutableStateOf(0f) }
    val spacingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    var dragItemId by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableStateOf(-1) }      // базовый индекс захвата
    var dragTarget by remember { mutableStateOf(-1) }    // текущая цель (базовый индекс)
    var displacement by remember { mutableStateOf(0f) }  // смещение пальца (viewport px)
    var scrolledTotal by remember { mutableStateOf(0f) } // суммарная автопрокрутка (px)
    var stride by remember { mutableStateOf(1f) }        // шаг строки (px)
    var startCenter by remember { mutableStateOf(0f) }   // центр захваченной строки (viewport px)
    var dragOffsetY by remember { mutableStateOf(0f) }   // визуальный сдвиг строки
    var dropPending by remember { mutableStateOf(false) }
    /** Палец на экране: автопрокрутка живёт только пока он не отпущен. */
    var fingerDown by remember { mutableStateOf(false) }
    val moveNow by rememberUpdatedState(onMove)
    val itemsNow by rememberUpdatedState(queue)

    // Живое превью: перетаскиваемый элемент уже стоит на целевой позиции —
    // соседи расступаются мгновенно (без анимаций внутри жеста).
    val display = remember(queue, dragFrom, dragTarget) {
        if (dragFrom < 0 || dragFrom !in queue.indices || dragTarget !in queue.indices || dragTarget == dragFrom) queue
        else queue.toMutableList().apply { add(dragTarget, removeAt(dragFrom)) }
    }

    fun retarget() {
        val raw = dragFrom + kotlin.math.round((displacement + scrolledTotal) / stride).toInt()
        // Инвариант композиции: слот с детектором жеста обязан оставаться в
        // видимом окне и НЕ ВЫШЕ якоря в НОВОЙ раскладке. Тонкость: lo/hiD —
        // display-координаты прошлой раскладки, а перестановка сдвигает якорь,
        // когда её диапазон накрывает позицию якоря В ОЧЕРЕДИ. Поэтому граница
        // считается от позиции якоря в очереди (по ключу строки). Спейсеры по
        // краям списка — стабильные якоря: перестановка их не двигает, поэтому
        // слот может вставать в самое начало и конец очереди (раньше вставка
        // на якорной строке разбирала строку жеста — плашка пропадала,
        // владелец). Зажим к dragFrom как к границе тоже не годится: после
        // автопрокрутки исходная строка уходит из вьюпорта.
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()
        val lo = first?.index ?: 0
        val hiD = visible.lastOrNull()?.index ?: (itemsNow.lastIndex + 1)
        val last = itemsNow.lastIndex.coerceAtLeast(0)
        val windowSize = hiD - lo + 1
        val spacerAnchor = first != null && first.key == DRAG_SPACER_TOP_KEY
        val anchorQ = when {
            spacerAnchor -> -1
            first == null -> 0
            else -> itemsNow.indexOfFirst { it.itemId == first.key }.takeIf { it >= 0 } ?: (lo - 1)
        }
        // Позиция якоря после удаления перетаскиваемого элемента (очередь-координаты).
        val anchorAfter = if (anchorQ > dragFrom) anchorQ - 1 else anchorQ
        val floor = when {
            spacerAnchor -> 0                       // спейсер не двигается — слот можно в самое начало
            else -> (anchorAfter + 1).coerceAtMost(last)
        }
        // Нижняя граница: низ окна после сдвига якоря (спейсер-якорь не сдвигается).
        val ceil = when {
            spacerAnchor -> hiD - 1
            else -> anchorAfter + windowSize - 1
        }.coerceAtLeast(0)
        dragTarget = when {
            raw < dragFrom -> when {
                // Исходная строка ушла за верх вьюпорта — держим слот на
                // верхней безопасной строке, автопрокрутка довозит.
                dragFrom < anchorQ -> floor.coerceAtMost(last)
                // Захват на якорной строке: вверх можно только после того,
                // как автопрокрутка опустит якорь ниже.
                dragFrom == anchorQ -> dragFrom
                else -> maxOf(raw, minOf(floor, dragFrom)).coerceAtMost(dragFrom)
            }
            raw > dragFrom -> maxOf(raw, minOf(floor, ceil)).coerceAtMost(ceil)
            else -> if (dragFrom == anchorQ || spacerAnchor) dragFrom else maxOf(minOf(dragFrom, ceil), floor)
        }.coerceIn(0, last)
        dragOffsetY = displacement - (dragTarget - dragFrom) * stride + scrolledTotal
        // Плавающая карточка следует за пальцем в экранных координатах —
        // список её не обрезает, когда палец уходит к шапке.
        dragVisual.centerY = listTopScreen + startCenter + displacement
    }

    fun resetDrag() {
        dragItemId = null
        dragFrom = -1
        dragTarget = -1
        dropPending = false
        dragOffsetY = 0f
        fingerDown = false
        dragVisual.itemId = null
    }

    fun endDrag() {
        val dragged = itemsNow.firstOrNull { it.itemId == dragItemId }
        val target = dragTarget
        fingerDown = false
        if (dragged != null && target in itemsNow.indices && target != dragFrom) {
            moveNow(dragged.itemId, target)
            // Держим строку у пальца до подтверждённого порядка сервера;
            // тайм-аут ниже не даст зависнуть при отказе.
            dropPending = true
        } else resetDrag()
    }

    // Точный детектор смерти жеста: строка с pointerInput ушла из композиции
    // при живом пальце — onDragEnd/onDragCancel не придут никогда. Завершаем
    // броском по текущей цели, иначе карточка зависла бы навсегда. Гвард по
    // fingerDown отсекает обычное завершение (там палец отпущен раньше, чем
    // строка теряет dragging).
    val onDragDead = {
        if (dragItemId != null && fingerDown) endDrag()
        Unit
    }

    // Автопрокрутка у краёв — 800 px/с (владелец), по фактическому времени.
    // Живёт, пока палец на экране (отпустили — остановилась); удержание у края
    // докручивает список без требования движения пальца (владелец вернул
    // поведение «замер у края — докручивает»).
    LaunchedEffect(dragItemId, fingerDown) {
        if (dragItemId == null || !fingerDown) return@LaunchedEffect
        val edgePx = 132f
        val speedPxPerSec = 800f
        var lastNanos = System.nanoTime()
        while (isActive) {
            delay(16)
            val now = System.nanoTime()
            val dtSec = ((now - lastNanos).coerceAtLeast(1)) / 1_000_000_000f
            lastNanos = now
            val step = speedPxPerSec * dtSec
            val bottom = listState.layoutInfo.viewportEndOffset.toFloat()
            // Трек уже на границе диапазона — крутить список дальше нет
            // смысла: финальная позиция всё равно 0 / последняя.
            val overscroll = when {
                startCenter + displacement < edgePx && dragTarget > 0 -> -step
                startCenter + displacement > bottom - edgePx && dragTarget < itemsNow.lastIndex -> step
                else -> 0f
            }
            if (overscroll != 0f) {
                scrolledTotal += listState.scrollBy(overscroll)
                retarget()
            }
        }
    }

    // Подтверждение: сервер прислал порядок, в котором трек уже на цели —
    // отпускаем строку.
    LaunchedEffect(queue, dropPending) {
        if (!dropPending) return@LaunchedEffect
        val id = dragItemId ?: return@LaunchedEffect
        if (queue.getOrNull(dragTarget)?.itemId == id) resetDrag()
    }
    LaunchedEffect(dropPending) {
        if (!dropPending) return@LaunchedEffect
        delay(900)
        resetDrag()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.onGloballyPositioned { coords ->
            listTopScreen = coords.positionInRoot().y
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Спейсеры-якоря: перестановка при drag'е их никогда не сдвигает,
        // поэтому слот жеста может вставать в самое начало и конец очереди
        // не рискуя оказаться выше/ниже якоря и быть разобранным LazyColumn.
        item(key = DRAG_SPACER_TOP_KEY) {
            Box(Modifier.fillMaxWidth().height(1.dp))
        }
        itemsIndexed(display, key = { _, e -> e.itemId }) { position, entry ->
            val isCurrent = entry.itemId == playback.itemId
            JamQueueRow(
                // Перетаскиваемая строка невидима: её рисует плавающая
                // карточка поверх экрана; слот в списке — место вставки.
                modifier = if (dragItemId == entry.itemId) {
                    Modifier.alpha(0f)
                } else if (reordering) Modifier else Modifier.animateItem(),
                position = position,
                entry = entry,
                addedByName = addedByNameOf(entry.addedBy),
                isCurrent = isCurrent,
                dragging = dragItemId == entry.itemId,
                showHandle = reordering,
                rowTapEnabled = !reordering,
                onRemove = { onRemove(entry.itemId) },
                onOpen = { onOpen(entry.itemId) },
                onMoveUp = { moveNow(entry.itemId, position - 1) },
                onMoveDown = { moveNow(entry.itemId, position + 1) },
                onDragStart = {
                    resetDrag()
                    fingerDown = true
                    val info = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == entry.itemId }
                    dragFrom = position
                    dragTarget = position
                    stride = (info?.size ?: 0) + spacingPx
                    startCenter = info?.let { it.offset + it.size / 2f } ?: 0f
                    displacement = 0f
                    scrolledTotal = 0f
                    dragItemId = entry.itemId
                    dragVisual.itemId = entry.itemId
                    dragVisual.title = entry.title
                    dragVisual.artist = entry.artist
                    dragVisual.imageUrl = entry.imageUrl
                    dragVisual.centerY = listTopScreen + startCenter
                },
                onDragHover = { amountY ->
                    displacement += amountY
                    retarget()
                },
                onDragEnd = { endDrag() },
                onDragDead = onDragDead,
            )
        }
        item(key = "jam-drag-spacer-end") {
            Box(Modifier.fillMaxWidth().height(1.dp))
        }
    }
}

@Composable
private fun JamQueueRow(
    modifier: Modifier = Modifier,
    position: Int,
    entry: JamQueueEntry,
    addedByName: String,
    isCurrent: Boolean,
    dragging: Boolean,
    showHandle: Boolean,
    rowTapEnabled: Boolean,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit,
    onDragHover: (displacementY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragDead: () -> Unit,
) {
    // Свежие колбэки на момент жеста: pointerInput кэширует блок.
    val startNow by rememberUpdatedState(onDragStart)
    val hoverNow by rememberUpdatedState(onDragHover)
    val endNow by rememberUpdatedState(onDragEnd)
    if (dragging) {
        // Пока строка «dragging», её уход из композиции = смерть жеста без
        // колбэков (детектор жил в pointerInput этой строки).
        val deadNow by rememberUpdatedState(onDragDead)
        DisposableEffect(entry.itemId) {
            onDispose { deadNow() }
        }
    }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val rowColor = when {
        dragging -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        isCurrent -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = rowColor,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        modifier = modifier.fillMaxWidth()
            .clickable(enabled = rowTapEnabled, onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Ширина с запасом: трёхзначные номера (108, 400+) не обрезаются.
            Text(
                "${position + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent || dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 32.dp),
                textAlign = TextAlign.Start,
            )
            if (entry.imageUrl.isNotBlank()) {
                AsyncImage(
                    entry.imageUrl,
                    null,
                    Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), color = if (isCurrent) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {}
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    entry.title.ifBlank { entry.uri },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Lang.t(Lang.Key.JAM_ADDED_BY, addedByName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!showHandle) {
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(EmberIcons.Close, Lang.t(Lang.Key.JAM_REMOVE_FROM_QUEUE), Modifier.size(18.dp))
                }
            }
            if (showHandle) {
                Box(
                    Modifier.size(40.dp)
                        .pointerInput(entry.itemId) {
                            // Ручка — только для drag'а: перетаскивание
                            // начинается сразу при движении (без ожидания
                            // long-press, который отменялся при быстрой
                            // тяге и ломал весь жест — владелец).
                            detectDragGestures(
                                onDragStart = { _ ->
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    startNow()
                                },
                                onDragCancel = { endNow() },
                                onDragEnd = { endNow() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    hoverNow(amount.y)
                                },
                            )
                        }
                        .semantics {
                            customActions = buildList {
                                if (position > 0) add(
                                    CustomAccessibilityAction(Lang.t(Lang.Key.JAM_MOVE_UP)) {
                                        onMoveUp(); true
                                    },
                                )
                                add(
                                    CustomAccessibilityAction(Lang.t(Lang.Key.JAM_MOVE_DOWN)) {
                                        onMoveDown(); true
                                    },
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_reorder_queue),
                        Lang.t(Lang.Key.JAM_MOVE_HINT),
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Join by code: manual entry, deep links and QR land here (spec section 2). */
@Composable
private fun JamJoinSheet(initialCode: String?, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf(initialCode ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(Lang.Key.JAM_INVITE_CODE)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(8) },
                singleLine = true,
                label = { Text(Lang.t(Lang.Key.JAM_CODE_8_CHARS)) },
            )
        },
        confirmButton = {
            Button(onClick = { onJoin(code) }, enabled = code.length == 8) { Text(Lang.t(Lang.Key.JAM_CONNECT)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_CANCEL)) } },
    )
}

/** Invite sheet: HTTPS QR, code, copy/share, expiry; revoke for the host. */
@Composable
private fun JamInviteSheet(
    inviteCode: String,
    expiresAt: Long,
    isHost: Boolean,
    onDismiss: () -> Unit,
    onRevoke: () -> Unit,
) {
    val context = LocalContext.current
    // Основная публичная ссылка — HTTPS App Link на jam.spotipuk.stream;
    // QR содержит её же. spotipuk:// остаётся внутренним fallback.
    val link = jamInviteHttpsLink(inviteCode)
    val expires = remember(expiresAt) {
        java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(expiresAt), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(Lang.Key.JAM_INVITE_TITLE)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    jamQrBitmap(link),
                    null,
                    Modifier.size(200.dp),
                )
                Text(inviteCode, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    Lang.t(Lang.Key.JAM_CODE_EXPIRES, expires),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("SpotiPuk Jam", link))
                        android.widget.Toast.makeText(context, Lang.t(Lang.Key.JAM_LINK_COPIED), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(Lang.t(Lang.Key.JAM_COPY)) }
                    OutlinedButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .putExtra(android.content.Intent.EXTRA_TEXT, Lang.t(Lang.Key.JAM_SHARE_TEXT, link, inviteCode))
                            .setType("text/plain")
                        context.startActivity(android.content.Intent.createChooser(intent, Lang.t(Lang.Key.JAM_SHARE)))
                    }) { Text(Lang.t(Lang.Key.JAM_SHARE)) }
                }
                if (isHost) {
                    TextButton(onClick = onRevoke) {
                        Text(Lang.t(Lang.Key.JAM_REVOKE_LINK), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_DONE)) } },
    )
}

/** Participants sheet with roles, connection state and host actions. */
@Composable
private fun JamParticipantsSheet(
    participants: List<JamParticipant>,
    myId: String,
    myAvatarUrl: String?,
    isHost: Boolean,
    onDismiss: () -> Unit,
    onKick: (String) -> Unit,
    onTransfer: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(Lang.Key.JAM_PARTICIPANTS_N, participants.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                participants.forEach { participant ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(36.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    participant.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                val avatar = if (participant.id == myId) {
                                    myAvatarUrl ?: participant.avatarUrl
                                } else participant.avatarUrl
                                if (avatar.isNotBlank()) {
                                    Artwork(avatar, Modifier.fillMaxSize(), circular = true)
                                }
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(participant.name, style = MaterialTheme.typography.bodyMedium)
                            val role = if (participant.isHost) Lang.t(Lang.Key.JAM_ROLE_HOST) else Lang.t(Lang.Key.JAM_ROLE_GUEST)
                            val conn = if (participant.isConnected) Lang.t(Lang.Key.JAM_CONNECTED_LOWER) else Lang.t(Lang.Key.JAM_DISCONNECTED_LOWER)
                            Text(
                                Lang.t(Lang.Key.JAM_MEMBER_STATUS, role, conn, participant.readyState.lowercase()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isHost && participant.id != myId && !participant.isHost) {
                            TextButton(onClick = { onTransfer(participant.id) }) { Text(Lang.t(Lang.Key.JAM_TRANSFER)) }
                            IconButton(onClick = { onKick(participant.id) }) {
                                Icon(EmberIcons.Close, Lang.t(Lang.Key.JAM_KICK), Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_DONE)) } },
    )
}

/**
 * Поиск и добавление треков прямо из Jam. Статусы плейлистов считаются по
 * sourceRef из реальной очереди сервера: «Добавляется…» → «Добавлен · N» /
 * «Частично добавлен» / ошибка; живут до удаления треков партии.
 */
@Composable
private fun JamAddMusicSheet(controller: JamController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ui by controller.session.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf("tracks") }
    var query by rememberSaveable { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Card>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var removingCard by remember { mutableStateOf<Card?>(null) }

    // Search tracks (tab = tracks)
    LaunchedEffect(query, tab) {
        if (tab != "tracks") return@LaunchedEffect
        val term = query.trim()
        if (term.length < 2) {
            tracks = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        if (term != query.trim()) return@LaunchedEffect
        searching = true
        val results = runCatching { com.project.puk.api.Spotify.search(context, term) }.getOrNull()
        tracks = results?.pages
            ?.get(com.project.puk.api.Spotify.SearchType.TRACKS)?.tracks
            ?.filter { isSupportedTrackUri(it.uri) }
            ?: emptyList()
        searching = false
    }

    // Load library playlists (tab = playlists); Lang.t(Lang.Key.JAM_LIKED_TRACKS) — always first.
    LaunchedEffect(tab) {
        if (tab != "playlists") return@LaunchedEffect
        searching = true
        val results = runCatching { com.project.puk.api.Spotify.library(context) }.getOrNull()
        val liked = Card(
            uri = "spotify:collection:tracks",
            title = Lang.t(Lang.Key.JAM_LIKED_TRACKS),
            subtitle = Lang.t(Lang.Key.JAM_LIKED_SUBTITLE),
            imageUrl = "",
            kind = "playlist",
        )
        playlists = listOf(liked) + (results?.items?.filter { it.kind == "playlist" } ?: emptyList())
        searching = false
    }

    /** Живой статус плейлиста по факту очереди (sourceRef), null если нет. */
    fun playlistStatus(card: Card): Pair<String, Boolean>? {
        val ref = if (card.uri == "spotify:collection:tracks") "liked" else card.uri
        val present = ui.session?.queue?.count { it.sourceRef == ref } ?: 0
        if (present == 0) return null
        val requested = ui.addedPartitions[ref]
        return when {
            requested == null || present >= requested -> Lang.t(Lang.Key.JAM_ADDED_FULL, present) to true
            else -> Lang.t(Lang.Key.JAM_ADDED_PARTIAL, present, requested) to false
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(Lang.Key.JAM_ADD_TO_QUEUE)) },
        text = {
            Column(Modifier.height(420.dp)) {
                // Табы: Треки / Плейлисты
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = tab == "tracks",
                        onClick = { tab = "tracks" },
                        label = { Text(Lang.t(Lang.Key.JAM_TAB_TRACKS)) },
                    )
                    FilterChip(
                        selected = tab == "playlists",
                        onClick = { tab = "playlists" },
                        label = { Text(Lang.t(Lang.Key.JAM_TAB_PLAYLISTS)) },
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (tab == "tracks") {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(Lang.t(Lang.Key.JAM_SEARCH_TRACKS)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally))
                    } else if (tracks.isEmpty() && query.trim().length >= 2) {
                        Text(
                            Lang.t(Lang.Key.JAM_NOTHING_FOUND),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(tracks, key = { _, t -> t.uri }) { _, row ->
                                val done = ui.session?.queue?.any { it.uri == row.uri } == true
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (row.imageUrl.isNotBlank()) {
                                            AsyncImage(row.imageUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                        } else {
                                            Surface(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(EmberIcons.MusicNote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(row.artists, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = {
                                            controller.addTrack(row.uri, row.name, row.artists, row.imageUrl, row.durationMs)
                                        }) {
                                            Icon(
                                                if (done) EmberIcons.Check else EmberIcons.PlaylistAdd,
                                                if (done) Lang.t(Lang.Key.JAM_ADDED) else Lang.t(Lang.Key.JAM_ADD_TO_QUEUE),
                                                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Вкладка Плейлисты: полная пагинация и постоянный статус.
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally))
                    } else if (playlists.isEmpty()) {
                        Text(
                            Lang.t(Lang.Key.JAM_NO_PLAYLISTS),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(playlists, key = { _, c -> c.uri }) { _, card ->
                                var loading by remember(card.uri) { mutableStateOf(false) }
                                var loadError by remember(card.uri) { mutableStateOf(false) }
                                var loadedCount by remember(card.uri) { mutableStateOf(0) }
                                val sourceRef = if (card.uri == "spotify:collection:tracks") "liked" else card.uri
                                val isAdding = card.uri in ui.addingRefs
                                val status = playlistStatus(card)
                                // Полностью добавленный плейлист повторно добавить
                                // нельзя: статус «Добавлен» уже постоянный.
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !loading && !isAdding && status?.second != true, onClick = {
                                        loading = true
                                        loadError = false
                                        scope.launch {
                                            // Полная пагинация: один вызов возвращал
                                            // лишь страницу — «432 выглядели как 400».
                                            val entries = mutableListOf<PukPlayer.QueueItem>()
                                            var offset = 0
                                            var next: Int? = 0
                                            while (next != null) {
                                                val page = if (card.uri == "spotify:collection:tracks") {
                                                    com.project.puk.api.Spotify.likedPage(context, offset)
                                                } else {
                                                    com.project.puk.api.Spotify.playlist(context, card.uri, limit = 200, offset = offset)?.let {
                                                        com.project.puk.api.Spotify.Page(it.tracks, it.next, it.total)
                                                    }
                                                }
                                                if (page == null) {
                                                    if (entries.isEmpty()) loadError = true
                                                    break
                                                }
                                                // «Призрачные» треки (лайк остался, трек удалён из
                                                // каталога — name пустой даже у Spotify) в очередь
                                                // не тащим: играть их нельзя, а UI показал бы сырой URI.
                                                entries += page.items
                                                    .filter { isSupportedTrackUri(it.uri) && it.name.isNotBlank() }
                                                    .map { it.toQueueItem() }
                                                loadedCount = entries.size
                                                next = page.next
                                                offset = next ?: break
                                            }
                                            if (entries.isNotEmpty()) {
                                                // Порции по 400 треков (кадр ≤ 256 КБ),
                                                // статусы ведёт JamSession.addingRefs.
                                                controller.addPlaylist(entries, sourceRef)
                                                loadError = false
                                            } else if (loadedCount == 0 && !loadError) {
                                                loadError = true
                                            }
                                            loading = false
                                        }
                                    }),
                                ) {
                                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (card.imageUrl.isNotBlank()) {
                                            AsyncImage(card.imageUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                        } else {
                                            Surface(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(EmberIcons.MusicNote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val subtitle = when {
                                                isAdding -> Lang.t(Lang.Key.JAM_ADDING)
                                                loading && loadedCount > 0 -> Lang.t(Lang.Key.JAM_LOADED_N, loadedCount)
                                                loadError -> Lang.t(Lang.Key.JAM_LOAD_FAILED_RETRY)
                                                status != null -> status.first
                                                else -> card.subtitle.ifBlank { Lang.t(Lang.Key.JAM_PLAYLIST) }
                                            }
                                            Text(
                                                subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when {
                                                    loadError -> MaterialTheme.colorScheme.error
                                                    isAdding || status != null -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            when {
                                                loading || isAdding -> CircularProgressIndicator(Modifier.size(20.dp))
                                                status?.second == true -> Icon(EmberIcons.Check, Lang.t(Lang.Key.JAM_PLAYLIST_ADDED), tint = MaterialTheme.colorScheme.primary)
                                                status != null && !status.second -> Text(
                                                    Lang.t(Lang.Key.JAM_PARTIAL),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            if (status != null && !loading && !isAdding) {
                                                IconButton(onClick = { removingCard = card }, modifier = Modifier.size(36.dp)) {
                                                    Icon(
                                                        androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_delete_playlist),
                                                        Lang.t(Lang.Key.JAM_REMOVE_PLAYLIST),
                                                        Modifier.size(20.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_DONE)) } },
    )

    // Подтверждение массового удаления: убираем ВСЮ партию у всех участников.
    removingCard?.let { card ->
        val ref = if (card.uri == "spotify:collection:tracks") "liked" else card.uri
        val count = ui.session?.queue?.count { it.sourceRef == ref } ?: 0
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removingCard = null },
            title = { Text(Lang.t(Lang.Key.JAM_REMOVE_PLAYLIST_TITLE)) },
            text = {
                Text(
                    Lang.t(Lang.Key.JAM_PURGE_NOTICE, count) +
                        Lang.t(Lang.Key.JAM_ACTION_IRREVERSIBLE),
                )
            },
            confirmButton = {
                Button(onClick = {
                    removingCard = null
                    controller.removeSource(ref)
                }) { Text(Lang.t(Lang.Key.JAM_DELETE)) }
            },
            dismissButton = {
                TextButton(onClick = { removingCard = null }) { Text(Lang.t(Lang.Key.JAM_CANCEL)) }
            },
        )
    }
}

/** Выбор, у кого играет: все синхронно или конкретный участник (host-only). */
@Composable
private fun JamModeSheet(
    participants: List<JamParticipant>,
    playMode: String,
    currentSoloId: String,
    isHost: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(Lang.Key.JAM_WHERE_MUSIC_PLAYS)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isHost) {
                    Text(
                        Lang.t(Lang.Key.JAM_MODE_HOST_ONLY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModeRow(
                    title = Lang.t(Lang.Key.JAM_MODE_ALL_SYNC),
                    detail = Lang.t(Lang.Key.JAM_MODE_ALL_SYNC_DETAIL),
                    selected = playMode == "SYNC",
                    enabled = isHost,
                    onClick = { onSelect("ALL") },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                participants.forEach { participant ->
                    ModeRow(
                        title = participant.name,
                        detail = if (participant.isHost) Lang.t(Lang.Key.JAM_HOST_FALLBACK) else Lang.t(Lang.Key.JAM_PARTICIPANT_DETAIL),
                        selected = playMode == "SOLO" && participant.id == currentSoloId,
                        enabled = isHost && participant.isConnected,
                        onClick = { onSelect(participant.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_DONE)) } },
    )
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(EmberIcons.Check, Lang.t(Lang.Key.JAM_SELECTED), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
