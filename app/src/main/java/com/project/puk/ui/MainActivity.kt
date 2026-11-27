package com.project.puk.ui

import com.project.puk.core.Lang

import com.project.puk.ui.theme.EmberIcons
import com.project.puk.ui.theme.EmberAnimatedIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.content.Intent
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.BackEventCompat
import kotlinx.coroutines.flow.collect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import com.project.puk.ui.theme.EmberMotion
import com.project.puk.ui.theme.emberInactive
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.DrawableRes
import com.project.puk.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.project.puk.api.Spotify
import com.project.puk.api.Card
import com.project.puk.api.Section
import com.project.puk.core.Auth
import com.project.puk.connect.Connect
import com.project.puk.player.PlaybackService
import com.project.puk.player.PukPlayer
import com.project.puk.ui.theme.SpotifyTheme

class MainActivity : ComponentActivity() {
    private var incomingSpotifyLink by mutableStateOf<String?>(null)
    private var pendingJamCode by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        absorbIntent(intent)
    }

    private fun absorbIntent(intent: Intent) {
        val data = intent.dataString ?: return
        if (data.contains("open.spotify.com") || data.startsWith("spotify:")) {
            incomingSpotifyLink = data
        } else if (data.startsWith("spotipuk://jam/join/")) {
            // Server-backed Jam invite (spec section 2); the code is validated
            // server-side, the link carries no secrets.
            val code = data.removePrefix("spotipuk://jam/join/").uppercase()
            if (code.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) pendingJamCode = code
        } else if (data.contains("jam.spotipuk.stream/j/")) {
            // HTTPS App Link приглашения (https://jam.spotipuk.stream/j/<code>);
            // до верификации домена ссылка может приходить как обычный VIEW.
            val code = data.substringAfter("/j/", "").substringBefore("?").trim().uppercase()
            if (code.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) pendingJamCode = code
        }
    }

    override fun onStop() {
        PlaybackService.engine?.savePlaybackCheckpoint()
        super.onStop()
    }

    /**
     * Кап фреймрейта на тяжёлых экранах каталога: на панелях 120 Гц стеклянный
     * UI не укладывается в бюджет кадра 8.3 мс и дрожит между 120/60 fps;
     * стабильные 60 прокручиваются плавно (см. перф-диагностику 2026-11-27).
     * null снимает кап — экран возвращается на родную частоту панели.
     */
    internal fun setPreferredDisplayMode(fps: Float?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val modeId = fps?.let { f ->
            display.supportedModes
                .filter {
                    it.physicalWidth == display.mode.physicalWidth &&
                        it.physicalHeight == display.mode.physicalHeight
                }
                .minByOrNull { kotlin.math.abs(it.refreshRate - f) }?.modeId
        } ?: 0
        val attrs = window.attributes
        val rate = fps ?: 0f
        // preferredDisplayModeId — прямой пин режима (OxygenOS уважает);
        // preferredRefreshRate — мягкий запрос частоты: HyperOS игнорирует
        // пин режима во время касания (вендорский touch-boost гонит 120 Гц),
        // но буст обязан учитывать preferredRefreshRate приложения.
        if (attrs.preferredDisplayModeId != modeId || attrs.preferredRefreshRate != rate) {
            attrs.preferredDisplayModeId = modeId
            attrs.preferredRefreshRate = rate
            window.attributes = attrs
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.project.puk.core.Lang.load(this)
        absorbIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Каждый resume: Jam восстанавливает WS сразу (без backoff)
                // и перепроверяет «мертвый» сокет — см. JamController.onAppResume.
                com.project.puk.jam.JamRuntime.controller(applicationContext).onAppResume()
                while (true) {
                    if (Auth.isLoggedIn(this@MainActivity)) Connect.refreshNowPlaying(applicationContext)
                    delay(4_000)
                }
            }
        }
        // Автопроверка обновлений при старте; при новой версии покажется диалог
        // (ui/AppUpdateUi). Ручная проверка — Настройки → «Обновления».
        lifecycleScope.launch {
            when (val result = com.project.puk.core.AppUpdater.check(applicationContext)) {
                is com.project.puk.core.AppUpdater.CheckResult.Newer -> AppUpdateUi.offer(result.info)
                else -> Unit
            }
        }
        setContent {
            SpotifyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(incomingSpotifyLink, pendingJamCode) {
                        incomingSpotifyLink = null
                    }
                    // Диалог «Доступно обновление» поверх любого экрана.
                    AppUpdateHost()
                    AppLangDialog()
                }
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector, val activeIcon: ImageVector)

internal val LocalBrowseBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }

/** Denser dock tint on text-heavy routes so long text stays readable. */
internal val LocalDenseGlass = staticCompositionLocalOf { false }

private data class BrowseDestination(
    val tab: Int,
    val route: ContentRoute?,
    val depth: Int,
    val settings: Boolean,
    val stats: Boolean,
    val profilePage: String?,
)

private data class BrowsePlayback(
    val item: com.project.puk.player.PukPlayer.QueueItem?,
    val index: Int,
    val playing: Boolean,
    val autoAdvance: Boolean,
    val sleepTimerMinutes: Int?,
)

// Tab labels re-resolve on each composition so the language switch applies live.
private val TAB_ICONS = listOf(
    Triple(com.project.puk.core.Lang.Key.TAB_HOME, EmberIcons.Home, EmberIcons.HomeActive),
    Triple(com.project.puk.core.Lang.Key.TAB_SEARCH, EmberIcons.Search, EmberIcons.SearchActive),
    Triple(com.project.puk.core.Lang.Key.TAB_LIBRARY, EmberIcons.LibraryMusic, EmberIcons.LibraryActive),
)

@Composable
private fun tabs(): List<Tab> = TAB_ICONS.map { (key, icon, activeIcon) ->
    Tab(com.project.puk.core.Lang.t(key), icon, activeIcon)
}

private sealed interface AccountChange {
    data object Add : AccountChange
    data object Logout : AccountChange
    data class Switch(val id: String) : AccountChange
}

@Composable
private fun AppRoot(incomingLink: String?, jamCode: String?, consume: () -> Unit) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(Auth.isLoggedIn(context)) }
    var change by remember { mutableStateOf<AccountChange?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    var chooseAccount by remember { mutableStateOf(false) }
    val request = change
    // Removing MainApp first disposes account requests and Jam before credentials change.
    if (request != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.CircularProgressIndicator()
                Text(Lang.t(Lang.Key.MAIN_SWITCHING_ACCOUNT))
            }
        }
        LaunchedEffect(request) {
            try {
                // Jam-сессия привязана к участнику конкретного аккаунта: при
                // смене аккаунта корректно выходим из неё раньше всего.
                com.project.puk.jam.JamRuntime.controller(context).takeIf { it.isActive }?.leave()
                Connect.stopForAccountChange(context)
                PlaybackService.engine?.stop(keepLastLocal = true)
                com.project.puk.core.TokenMinter.changingSession {
                    when (request) {
                        AccountChange.Add -> Auth.beginAddingAccount(context)
                        AccountChange.Logout -> Auth.logout(context)
                        is AccountChange.Switch -> Auth.activateAccount(context, request.id)
                    }
                    Auth.resetBrowserSession(context)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                android.widget.Toast.makeText(context, Lang.t(Lang.Key.MAIN_SWITCH_ACCOUNT_FAILED), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                loggedIn = Auth.isLoggedIn(context)
                epoch++
                change = null
            }
        }
    } else if (!loggedIn) {
        val accounts = remember(epoch) { Auth.savedAccounts(context) }
        val previousAccount = Auth.returnAccountId(context)
        androidx.activity.compose.BackHandler(enabled = previousAccount != null && !chooseAccount) {
            previousAccount?.let { change = AccountChange.Switch(it) }
        }
        Column(Modifier.fillMaxSize()) {
            if (accounts.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                    TextButton(onClick = { chooseAccount = true }) { Text(Lang.t(Lang.Key.MAIN_SAVED_ACCOUNTS)) }
                    Auth.returnAccountId(context)?.let { previous ->
                        TextButton(onClick = { change = AccountChange.Switch(previous) }) { Text(Lang.t(Lang.Key.JAM_CANCEL)) }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                LoginScreen(onLoggedIn = { loggedIn = true; epoch++ })
            }
        }
        if (chooseAccount) AddAccountSheet(
            accounts = accounts, activeId = null, onDismiss = { chooseAccount = false },
            onSelect = { chooseAccount = false; change = AccountChange.Switch(it) },
            onAdd = { chooseAccount = false; change = AccountChange.Add },
        )
    } else {
        androidx.compose.runtime.key(epoch) {
            MainApp(incomingLink, jamCode, consume,
                onLoggedOut = { change = AccountChange.Logout },
                onAddAccount = { change = AccountChange.Add },
                onSwitchAccount = { change = AccountChange.Switch(it) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(incomingLink: String?, jamCode: String?, consume: () -> Unit, onLoggedOut: () -> Unit, onAddAccount: () -> Unit, onSwitchAccount: (String) -> Unit) {
    val context = LocalContext.current
    val engine = remember { PlaybackService.engine(context) }
    val screenState = rememberSaveableStateHolder()

    var libraryRevision by remember { mutableIntStateOf(0) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var createdPlaylistNotice by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf(listOf<ContentRoute>()) }
    var showPlayer by remember { mutableStateOf(false) }
    var showRemotePlayer by remember { mutableStateOf(false) }
    var showJam by remember { mutableStateOf(false) }
    var showSpotifyJam by remember { mutableStateOf(false) }
    var showOnlineJam by remember { mutableStateOf(false) }
    var pendingJoinCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jamCode) {
        if (jamCode != null) {
            pendingJoinCode = jamCode
            showOnlineJam = true
        }
    }
    val jamScope = rememberCoroutineScope()
    // App-scoped singleton: сессия переживает composition — аудио играет через
    // PlaybackService в фоне, ведущий из фона рапортует TRACK_ENDED.
    val jamController = remember { com.project.puk.jam.JamRuntime.controller(context) }
    LaunchedEffect(jamController) {
        // Перезапуск приложения: переподключаемся к живой Jam-сессии (спека §8).
        if (jamController.hasPersistedSession()) {
            jamController.tryRestoreSession()
            showOnlineJam = true
        }
    }
    val profileDrawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(orientation) { profileDrawer.snapTo(androidx.compose.material3.DrawerValue.Closed) }
    val showProfileMenu = profileDrawer.isOpen
    var profilePage by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    // Реальная смена фото профиля: WebView на spotify.com с сессией аккаунта.
    var showAccountWeb by remember { mutableStateOf(false) }
    var profileWebEpoch by remember { mutableIntStateOf(0) }
    var account by remember { mutableStateOf(Auth.account(context)) }
    LaunchedEffect(profileWebEpoch) {
        if (profileWebEpoch == 0) return@LaunchedEffect
        val activeId = account?.id
        // profile() normally returns the saved identity. After the web editor,
        // fetch fresh profileAttributes and do not overwrite it with stale /me data.
        val refreshed = Spotify.profile(context, forceRefresh = true) ?: Spotify.refreshProfile(context)
        refreshed?.takeIf { it.id == activeId && Auth.account(context)?.id == activeId }?.let {
            Auth.saveAccount(context, it)
            account = it
        }
    }
    // Spotify folder uri the create-playlist dialog should move the new playlist into; null = root.
    var createPlaylistFolder by remember { mutableStateOf<String?>(null) }

    val prefs = remember { com.project.puk.core.AppPrefs.prefs(context) }
    var screenshotHint by remember { mutableStateOf(prefs.getBoolean("screenshot_hint", true)) }
    // The screenshot hint watches new gallery entries, which needs media read access.
    val mediaImagesPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(screenshotHint) {
        if (screenshotHint && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_IMAGES,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            mediaImagesPermission.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
        }
    }
    var profileInfo by remember { mutableStateOf(false) }
    var fullArtwork by remember { mutableStateOf(prefs.getBoolean("full_artwork", true)) }
    var warmBackground by remember { mutableStateOf(prefs.getBoolean("warm_player_background", true)) }
    var fpsCap by remember { mutableStateOf(prefs.getBoolean("scroll_fps_cap", true)) }
    // Тяжёлые экраны каталога и плеера капаем на 60 Гц: стекло на полном
    // разрешении не успевает за 120 Гц и кадры дрожат между 120/60. Лёгкие
    // экраны (настройки, статистика, профиль) и Jam остаются на родной
    // частоте панели. Тумблер — в настройках («Плавная прокрутка»).
    val heavyCatalog = !(showSettings || showStats || profilePage != null ||
        showOnlineJam || showAccountWeb || showJam || showSpotifyJam)
    LaunchedEffect(heavyCatalog, fpsCap) {
        (context as? MainActivity)?.setPreferredDisplayMode(if (heavyCatalog && fpsCap) 60f else null)
    }
    // На HyperOS вендорская «умная» частота перекрывает кап при касании:
    // предупреждаем один раз и предлагаем системную настройку 60 Гц.
    var hyperosWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (fpsCap && isHyperOs() && !prefs.getBoolean("hyperos_cap_warned", false)) hyperosWarning = true
    }
    if (hyperosWarning) {
        val dismiss = {
            hyperosWarning = false
            prefs.edit().putBoolean("hyperos_cap_warned", true).apply()
            Unit
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(Lang.t(Lang.Key.HYPEROS_CAP_TITLE)) },
            text = { Text(Lang.t(Lang.Key.HYPEROS_CAP_TEXT)) },
            confirmButton = { TextButton(onClick = dismiss) { Text(Lang.t(Lang.Key.HYPEROS_CAP_OK)) } },
        )
    }
    androidx.activity.compose.BackHandler(enabled = showProfileMenu) {
        jamScope.launch { profileDrawer.close() }
    }
    val openProfile: () -> Unit = { jamScope.launch { profileDrawer.open() }; Unit }
    fun closeProfile(action: () -> Unit) {
        jamScope.launch { profileDrawer.close(); action() }
    }
    if (showCreatePlaylist) {
        CreatePlaylistDialog(onDismiss = { showCreatePlaylist = false; createPlaylistFolder = null }) { created, _ ->
            showCreatePlaylist = false
            val destination = createPlaylistFolder
            createPlaylistFolder = null
            libraryRevision++
            if (created.registered) {
                stack = stack + ContentRoute(Target.Playlist(created.uri))
                if (destination != null) jamScope.launch {
                    Spotify.moveLibraryItem(context, created.uri, destination)
                    libraryRevision++
                }
            }
            else createdPlaylistNotice = true
        }
    }
    if (createdPlaylistNotice) {
        EmberActionSheet(
            onDismissRequest = { createdPlaylistNotice = false },
            heading = Lang.t(Lang.Key.MAIN_PLAYLIST_CREATED),
            text = { Text(Lang.t(Lang.Key.MAIN_PLAYLIST_SAVE_UNCONFIRMED)) },
            confirmButton = { TextButton(onClick = { createdPlaylistNotice = false }) { Text(Lang.t(Lang.Key.PLAYER_CLOSE)) } },
        )
    }
    if (showJam) {
        EmberActionSheet(onDismissRequest = { showJam = false }, heading = Lang.t(Lang.Key.MAIN_JAM)) {
            EmberSheetCard(
                EmberIcons.Groups, "SpotiPuk Jam",
                Lang.t(Lang.Key.MAIN_JAM_DESC),
            ) { showJam = false; showOnlineJam = true }
            EmberSheetCard(
                EmberIcons.Devices, "Spotify Jam",
                Lang.t(Lang.Key.MAIN_OPEN_INVITE_IN_SPOTIFY),
            ) { showJam = false; showSpotifyJam = true }
        }
    }
    if (showSpotifyJam) SpotifyJamDialog(onDismiss = { showSpotifyJam = false })

    LaunchedEffect(Unit) {
        Spotify.profile(context, forceRefresh = true)?.let {
            Auth.saveAccount(context, it)
            account = it
        }
        val attributes = account?.let { Spotify.enrichProfile(context, it) }
        (attributes ?: Spotify.refreshProfile(context))?.let {
            Auth.saveAccount(context, it)
            account = it
        }
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync()
        onDispose { MediaController.releaseFuture(controller) }
    }

    // Boot playback service + connect registration once.
    LaunchedEffect(Unit) {
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
        Connect.start(context)
    }

    val linkScope = rememberCoroutineScope()

    fun navigate(target: Target) {
        when {
            target is Target.Track -> linkScope.launch {
                // The fetch can outlive the user's attention (back/tab switch);
                // a late result must not hijack the current navigation state.
                val startTab = tab
                val startDepth = stack.size
                val row = runCatching { Spotify.track(context, target.uri) }.getOrNull()
                val navigatedAway = tab != startTab || stack.size < startDepth
                if (row != null && !navigatedAway) {
                    engine.playQueue(listOf(row.toQueueItem()), 0)
                    showPlayer = true
                } else if (row == null && !navigatedAway) {
                    android.widget.Toast.makeText(context, Lang.t(Lang.Key.MAIN_TRACK_UNAVAILABLE), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            target is Target.SpotifyLink -> {
                val native = spotifyLinkTarget(target.url)
                if (native != null) navigate(native)
                else {
                    openExternalSpotify(context, target.url)
                }
            }
            else -> stack = stack + ContentRoute(target)
        }
    }

    val openTarget = { target: Target -> navigate(target) }

    // Incoming Spotify links (tapped in other apps / browser) open natively.
    LaunchedEffect(incomingLink) {
        val link = incomingLink ?: return@LaunchedEffect
        var target = spotifyLinkTarget(link)
        if (target == null && link.startsWith("http")) {
            // Short links (spotify.link, *.app.link) redirect to real content
            // URLs — expand and re-parse before giving up.
            val expanded = runCatching { com.project.puk.core.Net.expand(link) }.getOrNull()
            if (expanded != null) target = spotifyLinkTarget(expanded)
        }
        if (target != null) {
            navigate(target)
            consume()
        } else {
            // Podcasts and other unsupported kinds fall through to Spotify.
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
            }
            consume()
        }
    }
    var selectedTrack by remember { mutableStateOf<PukPlayer.QueueItem?>(null) }
    val openTrackActions: (com.project.puk.api.TrackRow) -> Unit = { selectedTrack = it.toQueueItem() }
    selectedTrack?.let { track ->
        TrackActions(
            item = track,
            onDismiss = { selectedTrack = null },
            onEnqueue = engine::enqueue,
            onPlayNext = if (engine.canEditQueue()) ({ engine.enqueueNext(track) }) else null,
            onOpenEpisode = { openTarget(Target.Episode(track.uri)) },
            onOpen = openTarget,
            onLibraryChanged = { libraryRevision++ },
            onAddToJam = if (jamController.isActive && com.project.puk.jam.isSupportedTrackUri(track.uri)) ({
                jamController.addTrack(track.uri, track.title, track.artist, track.imageUrl, track.durationMs)
                selectedTrack = null
            }) else null,
        )
    }
    val playTracks: (List<com.project.puk.api.TrackRow>, Int) -> Unit = { rows, index ->
        Connect.selectLocalPlayback()
        val queueContext = when (val target = stack.lastOrNull()?.target) {
            is Target.Album -> target.uri
            is Target.Playlist -> target.uri
            Target.Liked -> LIKED_COLLECTION_URI
            else -> null
        }
        engine.playQueue(rows.map { it.toQueueItem() }, index, queueContext = queueContext)
        showPlayer = true
    }

    val devices by Connect.devices.collectAsState()
    val activeDevice by Connect.clusterActiveDevice.collectAsState()
    val transferScope = rememberCoroutineScope()
    var chooseOutput by remember { mutableStateOf(false) }
    var transferring by remember { mutableStateOf(false) }
    var transferFailed by remember { mutableStateOf(false) }
    val openOutputs = {
        transferFailed = false
        chooseOutput = true
    }
    if (chooseOutput) {
        EmberActionSheet(
            onDismissRequest = { chooseOutput = false },
            dismissEnabled = !transferring,
            heading = Lang.t(Lang.Key.MAIN_SPOTIFY_DEVICES),
            text = {
                Column {
                    TextButton(enabled = !transferring, onClick = {
                        chooseOutput = false
                        showJam = true
                    }) { Text(Lang.t(Lang.Key.MAIN_JAM)) }
                    if (devices.isEmpty()) Text(Lang.t(Lang.Key.MAIN_SEARCHING_DEVICES))
                    for (device in devices) {
                        val isActive = device.id == activeDevice
                        TextButton(
                            enabled = !transferring && !isActive,
                            onClick = {
                                transferring = true
                                transferFailed = false
                                transferScope.launch {
                                    try {
                                        if (Connect.transferPlayback(context, device.id)) {
                                            chooseOutput = false
                                            if (Connect.isLocalDevice(device.id)) showPlayer = true
                                        }
                                        else transferFailed = true
                                    } finally {
                                        transferring = false
                                    }
                                }
                            },
                        ) {
                            val name = if (Connect.isLocalDevice(device.id)) Lang.t(Lang.Key.MAIN_THIS_PHONE) else device.name
                            Text(if (isActive) Lang.t(Lang.Key.MAIN_ACCOUNT_ACTIVE, name) else name)
                        }
                    }
                    if (transferring) Text(Lang.t(Lang.Key.MAIN_TRANSFERRING))
                    if (transferFailed) Text(Connect.transferError ?: Lang.t(Lang.Key.MAIN_TRANSFER_NOT_CONFIRMED))
                }
            },
            confirmButton = { TextButton(enabled = !transferring, onClick = { chooseOutput = false }) { Text(Lang.t(Lang.Key.PLAYER_CLOSE)) } },
        )
    }
    var homeSections by remember(account?.id) { mutableStateOf<List<Section>?>(null) }
    var homeFacets by remember(account?.id) { mutableStateOf(emptyList<com.project.puk.api.HomeFacet>()) }
    var homeFacet by remember(account?.id) { mutableStateOf("") }
    var facetSections by remember(account?.id, homeFacet) { mutableStateOf<List<Section>?>(null) }
    var homeUnavailable by remember(account?.id) { mutableStateOf(false) }
    var homeRevision by remember(account?.id) { mutableIntStateOf(0) }
    LaunchedEffect(account?.id, homeRevision, homeFacet.isBlank()) {
        if (homeFacet.isBlank()) {
            val feed = Spotify.home(context)
            homeUnavailable = feed == null
            if (feed != null) {
                homeSections = feed.sections
                homeFacets = feed.facets
            } else if (homeSections == null) homeSections = emptyList()
        }
    }
    var facetUnavailable by remember(account?.id, homeFacet) { mutableStateOf(false) }
    LaunchedEffect(account?.id, homeFacet, homeRevision) {
        if (homeFacet.isNotBlank()) {
            val feed = Spotify.home(context, homeFacet)
            facetUnavailable = feed == null
            facetSections = feed?.sections.orEmpty()
        }
    }

    val restoringSession by engine.restoringSession.collectAsState()
    val engineState = engine.state.collectAsState()
    val smoothTransitions by remember(engineState) { derivedStateOf { engineState.value.smoothTransitions } }
    val playback by remember(engineState) {
        derivedStateOf(structuralEqualityPolicy()) {
            val state = engineState.value
            BrowsePlayback(
                state.queue.getOrNull(state.index), state.index, state.playing,
                state.autoAdvance, state.sleepTimerMinutes,
            )
        }
    }
    var sessionTracks by remember(account?.id) { mutableStateOf(emptyList<Card>()) }
    var storedTracks by remember(account?.id) { mutableStateOf(emptyList<Card>()) }
    LaunchedEffect(account?.id, showProfileMenu) {
        val id = account?.id?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        try {
            storedTracks = engine.recentListening(id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storedTracks = emptyList()
        }
    }
    val recentTracks = remember(sessionTracks, storedTracks) { (sessionTracks + storedTracks).distinctBy { it.uri }.take(30) }
    val playingItem = playback.item
    LaunchedEffect(playingItem?.uri, playback.playing, account?.id) {
        if (playback.playing && playingItem?.uri?.startsWith("spotify:track:") == true) {
            val card = Card(playingItem.uri, playingItem.title, playingItem.artist, playingItem.imageUrl, "track")
            sessionTracks = (listOf(card) + sessionTracks.filterNot { it.uri == card.uri }).take(30)
        }
    }
    if (profileInfo) {
        @Suppress("DEPRECATION")
        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        EmberActionSheet(onDismissRequest = { profileInfo = false }, heading = Lang.t(Lang.Key.MAIN_ABOUT)) {
            Text("SpotiPuk ${version.orEmpty()}", style = MaterialTheme.typography.titleMedium)
            Text(Lang.t(Lang.Key.MAIN_MUSIC_CLIENT_ANDROID))
        }
    }
    val remoteTrack by Connect.remoteTrack.collectAsState()
    LaunchedEffect(playback.index) {
        if (playback.index < 0) showPlayer = false
    }

    val showRemoteCard = remoteTrack != null && !playback.playing
    LaunchedEffect(showRemoteCard) {
        if (!showRemoteCard) showRemotePlayer = false
    }

    val playerVisible = showPlayer || showRemotePlayer
    var backProgress by remember { mutableStateOf(0f) }
    var backDirection by remember { mutableStateOf(1f) }
    PredictiveBackHandler(enabled = !showProfileMenu && (playerVisible || showStats || showSettings || showOnlineJam || profilePage != null || showAccountWeb || stack.isNotEmpty())) { events ->
        val animate = android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f
        try {
            events.collect { event ->
                if (animate) {
                    backDirection = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                    backProgress = event.progress
                }
            }
            when {
                showAccountWeb -> {
                    showAccountWeb = false
                    profileWebEpoch++
                }
                showOnlineJam -> {
                    showOnlineJam = false
                    showJam = false
                }
                showPlayer || showRemotePlayer -> {
                    showPlayer = false
                    showRemotePlayer = false
                }
                profilePage != null -> profilePage = null
                showStats -> showStats = false
                showSettings -> showSettings = false
                stack.isNotEmpty() -> stack = stack.dropLast(1)
            }
        } finally {
            backProgress = 0f
        }
    }

    val focusManager = LocalFocusManager.current
    val offset = with(LocalDensity.current) { 14.dp.roundToPx() }
    val libraryPages = remember(account?.id, libraryRevision) { PagedList<Card>() }
    val librarySearchPages = remember(libraryPages) { android.util.LruCache<String, PagedList<Card>>(4) }
    val searchCache = remember(account?.id) { android.util.LruCache<String, Spotify.SearchResults>(12) }
    val browse = BrowseDestination(tab, stack.lastOrNull(), stack.size, showSettings, showStats, profilePage)
    var presentedRemote by remember { mutableStateOf(false) }
    SideEffect { if (playerVisible) presentedRemote = showRemotePlayer }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = profileDrawer,
        gesturesEnabled = showProfileMenu,
        drawerContent = {
            ProfileDrawer(
                account = account,
                savedAccounts = remember(account, showProfileMenu) { Auth.savedAccounts(context) },
                onProfile = { closeProfile { profilePage = "profile" } },
                onAddAccount = { closeProfile(onAddAccount) },
                onSwitchAccount = { id -> closeProfile { onSwitchAccount(id) } },
                onStats = { closeProfile { showStats = true } },
                onRecent = { closeProfile { profilePage = "recent" } },
                onUpdates = { closeProfile { profilePage = "updates" } },
                onSettings = { closeProfile { showSettings = true } },
                onJam = { closeProfile { showJam = true } },
                onAbout = { closeProfile { profileInfo = true } },
            )
        },
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalContextLibraryChanged provides { libraryRevision++ },
            LocalArtistPolicyChanged provides { uri, blocked ->
                if (Connect.clusterActiveDevice.value.let { it.isBlank() || Connect.isLocalDevice(it) }) {
                    engine.updateArtistPolicy(uri, blocked)
                }
            },
            LocalContextOpenTarget provides openTarget,
            LocalContextJamAddCollection provides if (jamController.isActive) { uri, _ ->
                jamScope.launch {
                    runCatching {
                        val page = com.project.puk.api.Spotify.playlist(context, uri)
                        val entries = page?.tracks?.map { it.toQueueItem() } ?: return@launch
                        jamController.addPlaylist(entries, uri)
                    }
                }
            } else null,
            LocalContextPlayEpisode provides { row, show ->
                Connect.selectLocalPlayback()
                engine.playQueue(listOf(row.asQueueItem(show.title, show.uri)), 0,
                    positionMs = if (row.played) 0 else row.positionMs, queueContext = show.uri)
                showPlayer = true
            },
            LocalContextEnqueueCollection provides if (activeDevice.isBlank() || Connect.isLocalDevice(activeDevice)) {
                { tracks ->
                    val device = Connect.clusterActiveDevice.value
                    val local = device.isBlank() || Connect.isLocalDevice(device)
                    if (local) engine.enqueue(tracks.map { it.toQueueItem() })
                    local
                }
            } else null,
            LocalContextPlayCollection provides { uri, tracks ->
                Connect.selectLocalPlayback()
                engine.playQueue(tracks.map { it.toQueueItem() }, 0, queueContext = uri)
                showPlayer = true
            },
        ) {
        Scaffold(
            bottomBar = {
                Column(
                    Modifier.emberInactive(playerVisible),
                ) {
                    if (jamController.isActive) {
                        val jamUi by jamController.session.ui.collectAsState()
                        val count = jamUi.session?.participants?.count { it.isConnected } ?: 0
                        val glass = MaterialTheme.colorScheme.surfaceContainerHigh
                        val glint = MaterialTheme.colorScheme.onSurface
                        val liveColor = MaterialTheme.colorScheme.primary
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (pressed) 0.97f else 1f,
                            animationSpec = tween(EmberMotion.Fast),
                            label = "jamChipScale",
                        )
                        Surface(
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                ) { showOnlineJam = true },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .drawBehind {
                                        drawRoundRect(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    glass.copy(alpha = 0.82f),
                                                    glass.copy(alpha = 0.94f),
                                                ),
                                            ),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                                        )
                                        drawLine(
                                            glint.copy(alpha = 0.10f),
                                            Offset.Zero,
                                            Offset(size.width, 0f),
                                            strokeWidth = 1.dp.toPx(),
                                        )
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Пульсирующая точка — живая синхронизация
                                val infinite = rememberInfiniteTransition(label = "jamDot")
                                val dotAlpha by infinite.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.35f,
                                    animationSpec = infiniteRepeatable(
                                        tween(900, easing = androidx.compose.animation.core.EaseInOut),
                                    ),
                                    label = "jamDotAlpha",
                                )
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape)
                                        .background(liveColor.copy(alpha = dotAlpha)),
                                )
                                Icon(
                                    EmberIcons.Groups, null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Jam",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "$count",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    EmberIcons.PlayArrow, Lang.t(Lang.Key.MAIN_OPEN_JAM),
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val remote = remoteTrack
                    if (showRemoteCard && remote != null) {
                        RemoteNowPlaying(remote, onOpen = { showRemotePlayer = true })
                    } else if (restoringSession && playback.index < 0) {
                        RestoringMiniPlayer()
                    } else if (jamController.isActive) {
                        // Активный Jam: плашка сессии уже заменяет мини-плеер
                        // (ветка выше) — обычный мини-плеер скрыт.
                    } else {
                        MiniPlayer(player = engine, onOpen = { showPlayer = true }, onActions = { selectedTrack = it }, onOutputs = openOutputs, onLibraryChanged = { libraryRevision++ })
                    }
                    val glass = MaterialTheme.colorScheme.surfaceContainerHigh
                    val glint = MaterialTheme.colorScheme.onSurface
                    val dense = LocalDenseGlass.current
                    Row(
                        Modifier.fillMaxWidth()
                            .drawBehind {
                                drawRect(
                                Brush.verticalGradient(
                                    listOf(
                                        glass.copy(alpha = if (dense) 0.80f else 0.71f),
                                        glass.copy(alpha = if (dense) 0.94f else 0.88f),
                                    ),
                                ),
                            )
                                drawLine(glint.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset.Zero,
                                    androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                            }
                            // Navigation bars + cutout only: the IME overlays the
                            // bar instead of lifting it, so no per-frame inset
                            // relayout while the keyboard animates.
                            .windowInsetsPadding(
                                WindowInsets.navigationBars
                                    .union(WindowInsets.displayCutout)
                                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                            )
                            .selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs().forEachIndexed { index, current ->
                            val selected = tab == index
                            val source = remember { MutableInteractionSource() }
                            val tint by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                tween(EmberMotion.Standard),
                                label = "Ember navigation color",
                            )
                            Column(
                                Modifier.weight(1f).heightIn(min = 60.dp)
                                    .selectable(
                                        selected = selected,
                                        interactionSource = source,
                                        indication = null,
                                        role = Role.Tab,
                                        onClick = {
                                            focusManager.clearFocus()
                                            showSettings = false
                                            showStats = false
                                            profilePage = null
                                            tab = index
                                            stack = emptyList()
                                        },
                                    )
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                            ) {
                                EmberAnimatedIcon(if (selected) current.activeIcon else current.icon, null, source, tint = tint)
                                Text(
                                    current.label, color = tint, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                    }
                }
            },
        ) { padding ->
            val direction = LocalLayoutDirection.current
            val bottom = padding.calculateBottomPadding()
            AnimatedContent(
                targetState = browse,
                modifier = Modifier.padding(
                    start = padding.calculateStartPadding(direction),
                    // Artwork screens and tabs paint beneath the status bar.
                    // Other routes retain their existing system top inset.
                    top = if (browse.settings || browse.route?.target.let { it == null || it is Target.Playlist || it is Target.Album || it is Target.Artist || it == Target.Liked }) 0.dp else padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                )
                    // Consume only the bottom inset: tab headers must still see
                    // the status-bar inset to pad themselves out of the icons.
                    .consumeWindowInsets(
                        WindowInsets(0.dp, 0.dp, 0.dp, padding.calculateBottomPadding()),
                    )
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer { translationX = if (playerVisible) 0f else offset * backProgress * backDirection },
                transitionSpec = {
                    val forward = if (initialState.tab != targetState.tab) {
                        targetState.tab > initialState.tab
                    } else if (initialState.stats != targetState.stats) {
                        targetState.stats
                    } else if (initialState.settings != targetState.settings) {
                        targetState.settings
                    } else {
                        targetState.depth >= initialState.depth
                    }
                    val direction = if (forward) 1 else -1
                    (fadeIn(tween(EmberMotion.Standard)) +
                        slideInHorizontally(tween(EmberMotion.Standard)) { direction * offset })
                        .togetherWith(fadeOut(tween(EmberMotion.Standard)) +
                            slideOutHorizontally(tween(EmberMotion.Standard)) { -direction * offset })
                },
                label = "Ember browse",
            ) { screen ->
                val hasScrollPadding = !screen.stats && !screen.settings && screen.profilePage == null
                val denseGlass = screen.settings || screen.stats || screen.profilePage != null ||
                    (screen.route?.target is Target.Episode || screen.route?.target is Target.Show ||
                        screen.route?.target is Target.Playlist)
                androidx.compose.runtime.CompositionLocalProvider(LocalDenseGlass provides denseGlass) {
                Box(
                    Modifier.fillMaxSize().emberInactive(screen != browse || playerVisible)
                        .padding(bottom = if (hasScrollPadding) 0.dp else bottom),
                ) {
                  androidx.compose.runtime.CompositionLocalProvider(LocalBrowseBottomPadding provides bottom) {
                    when {
                        showAccountWeb -> AccountWebScreen(
                            url = "https://open.spotify.com/user/${android.net.Uri.encode(account?.id.orEmpty())}",
                            title = Lang.t(Lang.Key.MAIN_PROFILE_PHOTO),
                            onBack = { showAccountWeb = false; profileWebEpoch++ },
                        )
                        screen.profilePage != null -> ProfileDestination(
                            page = screen.profilePage, account = account, player = engine,
                            onBack = { profilePage = null }, onOpen = { target -> profilePage = null; openTarget(target) },
                            onPlay = playTracks, onTrackActions = openTrackActions,
                            onEditProfileImage = { showAccountWeb = true },
                        )
                        screen.stats && !account?.id.isNullOrBlank() -> ListeningStatsScreen(
                            account = checkNotNull(account).id, player = engine, onBack = { showStats = false },
                            onOpen = { target -> showStats = false; openTarget(target) },
                        )
                        screen.settings -> ProfileScreen(
                            account = account, onBack = { showSettings = false }, onLoggedOut = onLoggedOut,
                            fpsCap = fpsCap, onFpsCapChanged = {
                                fpsCap = it
                                prefs.edit().putBoolean("scroll_fps_cap", it).apply()
                            },
                            fullArtwork = fullArtwork, onArtworkChanged = {
                                fullArtwork = it
                                prefs.edit().putBoolean("full_artwork", it).apply()
                            },
                            warmBackground = warmBackground, onBackgroundChanged = {
                                warmBackground = it
                                prefs.edit().putBoolean("warm_player_background", it).apply()
                            },
                            autoAdvance = playback.autoAdvance, onAutoAdvanceChanged = engine::setAutoAdvance,
                            smoothTransitions = smoothTransitions, onSmoothTransitionsChanged = engine::setSmoothTransitions,
                            screenshotHint = screenshotHint, onScreenshotHintChanged = {
                                screenshotHint = it
                                prefs.edit().putBoolean("screenshot_hint", it).apply()
                            },
                            sleepTimerMinutes = playback.sleepTimerMinutes, onSleepTimerChanged = engine::setSleepTimer,
                        )
                        screen.route != null -> {
                            val route = screen.route
                            when (val target = route.target) {
                                is Target.Track -> Unit
                                is Target.Playlist -> PlaylistScreen(route, uri = target.uri, player = engine, onBack = { stack = stack.dropLast(1) }, onPlay = playTracks, onTrackActions = openTrackActions)
                                is Target.Album -> AlbumScreen(route, uri = target.uri, onBack = { stack = stack.dropLast(1) }, onPlay = playTracks, onTrackActions = openTrackActions)
                                is Target.Artist -> ArtistDetails(route, uri = target.uri, onBack = { stack = stack.dropLast(1) }, onOpen = openTarget, onPlay = playTracks, onTrackActions = openTrackActions)
                                is Target.Show -> ShowScreen(
                                    uri = target.uri,
                                    onBack = { stack = stack.dropLast(1) },
                                    onPlayEpisode = { row, showName ->
                                        Connect.selectLocalPlayback()
                                        engine.playQueue(listOf(row.asQueueItem(showName, target.uri)), 0, positionMs = if (row.played) 0 else row.positionMs)
                                    },
                                    onOpenEpisode = { stack = stack + ContentRoute(Target.Episode(it)) },
                                    onEpisodeActions = { selectedTrack = it },
                                )
                                is Target.Episode -> EpisodeScreen(
                                    uri = target.uri,
                                    player = engine,
                                    onBack = { stack = stack.dropLast(1) },
                                    onPlay = { detail ->
                                        Connect.selectLocalPlayback()
                                        engine.playQueue(listOf(detail.asQueueItem()), 0, positionMs = detail.positionMs)
                                    },
                                    onOpenShow = { stack = stack + ContentRoute(Target.Show(it)) },
                                )
                                is Target.SpotifyLink -> Unit
                                is Target.Category -> CategoryScreen(route.scroll, target.id, recentTracks, homeSections, onBack = { stack = stack.dropLast(1) }, onOpen = openTarget, onPlay = playTracks, onTrackActions = openTrackActions)
                                is Target.Liked -> LikedScreen(route, player = engine, onBack = { stack = stack.dropLast(1) }, onPlay = playTracks, onTrackActions = openTrackActions)
                            }
                        }
                        screen.tab == 0 -> screenState.SaveableStateProvider("home") {
                            HomeScreen(
                                sections = if (homeFacet.isBlank()) homeSections else facetSections,
                                sourceUnavailable = if (homeFacet.isBlank()) homeUnavailable else facetUnavailable,
                                serverFacets = homeFacets,
                                serverFacet = homeFacet,
                                onServerFacet = { homeFacet = it },
                                recentTracks = recentTracks,
                                onRetry = { homeRevision++ },
                                onOpen = openTarget,
                                onProfile = openProfile,
                                accountName = account?.name.orEmpty(), accountImageUrl = account?.imageUrl.orEmpty(),
                                onPlay = playTracks,
                                onTrackActions = openTrackActions,
                            )
                        }
                        screen.tab == 2 -> screenState.SaveableStateProvider("library") {
                            LibraryBrowser(pages = libraryPages, searchPages = librarySearchPages, onOpen = openTarget, onProfile = openProfile, accountName = account?.name.orEmpty(), accountImageUrl = account?.imageUrl.orEmpty(), onCreatePlaylist = { folder -> createPlaylistFolder = folder; showCreatePlaylist = true }, onRefresh = { libraryRevision++ })
                        }
                        screen.tab == 1 -> screenState.SaveableStateProvider("search") {
                            SearchScreen(searchCache = searchCache, recentTracks = recentTracks, homeSections = homeSections, onOpen = openTarget, onProfile = openProfile, accountName = account?.name.orEmpty(), accountImageUrl = account?.imageUrl.orEmpty(), onPlay = playTracks, onTrackActions = openTrackActions)
                        }
                    }
                  }
                }
                }
            }
        }
        AnimatedVisibility(
            visible = playerVisible,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(tween(EmberMotion.Emphasized)) { offset * 2 },
            exit = slideOutVertically(tween(EmberMotion.Standard)) { offset * 2 },
            label = "Ember now playing",
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().emberInactive(!playerVisible)
                    .graphicsLayer { translationY = offset * backProgress },
                color = MaterialTheme.colorScheme.background,
            ) {
                val remote = if (playerVisible) showRemotePlayer else presentedRemote
                when {
                    remote && remoteTrack != null -> remoteTrack?.let {
                        RemotePlayerScreen(it, onBack = { showRemotePlayer = false }, onOutputs = openOutputs)
                    }
                    !remote -> PlayerScreen(player = engine, onBack = { showPlayer = false }, onOutputs = openOutputs, fullArtwork = fullArtwork, warmBackground = warmBackground, onOpenAlbum = { uri ->
                        showPlayer = false
                        openTarget(Target.Album(uri))
                    }, onOpenShow = { uri ->
                        showPlayer = false
                        openTarget(Target.Show(uri))
                    }, onOpenArtist = { uri ->
                        showPlayer = false
                        openTarget(Target.Artist(uri))
                    }, onLibraryChanged = { libraryRevision++ }, screenshotHint = screenshotHint && showPlayer && !chooseOutput && !showJam)
                }
            }
        }
    }
    }

    // Full-screen online Jam session (over tabs and mini-player).
    if (showOnlineJam) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            JamScreen(
                controller = jamController,
                account = account,
                pendingCode = pendingJoinCode,
                onConsumePendingCode = { pendingJoinCode = null },
                onBack = { showOnlineJam = false },
                onAddMusic = { showOnlineJam = false; tab = 1 },
            )
        }
    }
}
}

/** HyperOS/MIUI: вендорская «умная» частота перекрывает кап приложения. */
private fun isHyperOs(): Boolean = try {
    if (!android.os.Build.MANUFACTURER.equals("Xiaomi", true)) false
    else {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        listOf("ro.miui.ui.version.name", "ro.mi.os.version.name").any { key ->
            (get.invoke(null, key) as? String)?.isNotBlank() == true
        }
    }
} catch (_: Exception) { false }

@Composable
private fun ProfileScreen(
    account: Auth.Account?, onBack: () -> Unit, onLoggedOut: () -> Unit,
    fpsCap: Boolean, onFpsCapChanged: (Boolean) -> Unit,
    fullArtwork: Boolean, onArtworkChanged: (Boolean) -> Unit,
    warmBackground: Boolean, onBackgroundChanged: (Boolean) -> Unit,
    autoAdvance: Boolean, onAutoAdvanceChanged: (Boolean) -> Unit,
    smoothTransitions: Boolean, onSmoothTransitionsChanged: (Boolean) -> Unit,
    sleepTimerMinutes: Int?, onSleepTimerChanged: (Int?) -> Unit,
    screenshotHint: Boolean, onScreenshotHintChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showTimer by remember { mutableStateOf(false) }
    if (showTimer) SleepTimerDialog(sleepTimerMinutes, onSleepTimerChanged) { showTimer = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onBack) { Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_BACK)) }
            Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SETTINGS_TITLE), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(20.dp))
        AccountHeading(account)
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.PLAYBACK_SECTION), style = MaterialTheme.typography.titleLarge)
        SettingsSwitch(
            com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.AUTO_ADVANCE),
            com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.AUTO_ADVANCE_DESC),
            autoAdvance,
            onAutoAdvanceChanged,
        )
        SettingsSwitch(
            com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SMOOTH_TRANSITIONS), com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SMOOTH_TRANSITIONS_DESC),
            smoothTransitions, onSmoothTransitionsChanged,
        )
        SettingsSwitch(
            com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.FPS_CAP), com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.FPS_CAP_DESC),
            fpsCap, onFpsCapChanged,
        )
        SheetAction(
            R.drawable.ember_ctx_sleep_timer,
            sleepTimerMinutes?.let {
                com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SLEEP_TIMER) + " · $it " +
                    com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SLEEP_TIMER_MIN)
            } ?: com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SLEEP_TIMER),
        ) { showTimer = true }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.APPEARANCE_SECTION), style = MaterialTheme.typography.titleLarge)
        SettingsSwitch(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.FULL_ARTWORK), com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.FULL_ARTWORK_DESC), fullArtwork, onArtworkChanged)
        SettingsSwitch(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.WARM_BACKGROUND), com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.WARM_BACKGROUND_DESC), warmBackground, onBackgroundChanged)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            SettingsSwitch(
                com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SCREENSHOT_CARD), com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.SCREENSHOT_CARD_DESC),
                screenshotHint, onScreenshotHintChanged,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.LINKS_SECTION), style = MaterialTheme.typography.titleLarge)
        SheetAction(EmberIcons.Open, com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.OPEN_LINKS)) {
            val openDefault = Intent(
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
                } else {
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                },
                android.net.Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(openDefault) }
                .onFailure {
                    android.widget.Toast.makeText(context, com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.LINKS_UNAVAILABLE), android.widget.Toast.LENGTH_SHORT).show()
                }
        }
        Text(
            com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.OPEN_LINKS_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        // Язык — свой раздел (не внутри «О приложении»): switches the app chrome live.
        Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.LANGUAGE_SECTION), style = MaterialTheme.typography.titleLarge)
        SheetAction(EmberIcons.Language, com.project.puk.core.Lang.current.label) {
            AppLangUi.show()
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.ACCOUNT_SECTION), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (!account?.email.isNullOrBlank()) {
            Text(
                account?.email.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        account?.product?.takeIf { it.isNotBlank() }?.let {
            Text(
                if (it.equals("premium", true)) com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.TARIFF_PREMIUM) else com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.TARIFF_FREE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.ABOUT_SECTION), style = MaterialTheme.typography.titleLarge)
        @Suppress("DEPRECATION")
        val version = remember(context) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
        version?.let { Text("SpotiPuk $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // Обновления переехали в боковое меню → «Обновления» (badge + changelog + установка).
        Spacer(modifier = Modifier.padding(top = 20.dp))
        if (account == null) {
            Text(com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.PROFILE_UNAVAILABLE), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            com.project.puk.core.Lang.t(com.project.puk.core.Lang.Key.LOGOUT),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onLoggedOut()
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}


@Composable
private fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AccountHeading(account: Auth.Account?) {
    val name = account?.name.orEmpty().ifBlank { "SpotiPuk" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val image = account?.imageUrl.orEmpty()
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (image.isNotBlank()) Artwork(image, Modifier.fillMaxSize())
        }
        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RemoteNowPlaying(track: Connect.RemoteTrack, onOpen: () -> Unit) {
    FloatingPlayerSurface(track.artwork) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerMetadata(
                uri = track.artwork,
                artwork = track.artwork,
                title = track.title,
                subtitle = listOfNotNull(
                    track.artist.takeIf { it.isNotBlank() },
                    track.device.ifBlank { Lang.t(Lang.Key.MAIN_OTHER_DEVICE) },
                ).joinToString(" • "),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RemotePlayerScreen(track: Connect.RemoteTrack, onBack: () -> Unit, onOutputs: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButtonLite(Lang.t(Lang.Key.LIB_BACK_ARROW), onClick = onBack)
        Text(Lang.t(Lang.Key.PLAYER_NOW_PLAYING), style = MaterialTheme.typography.titleMedium)
        TextButtonLite(Lang.t(Lang.Key.PLAYER_SELECT_DEVICE), onOutputs)
        Artwork(track.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(track.title, style = MaterialTheme.typography.headlineSmall)
        if (track.artist.isNotBlank()) {
            Text(track.artist, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            track.device.ifBlank { Lang.t(Lang.Key.MAIN_OTHER_DEVICE) },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
internal fun SheetAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
        modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Same action row, driven by an Ember Context Icon drawable resource. */
@Composable
internal fun SheetAction(@DrawableRes iconRes: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
        modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
