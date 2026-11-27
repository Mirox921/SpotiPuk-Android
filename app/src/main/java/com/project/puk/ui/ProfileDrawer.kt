package com.project.puk.ui

import com.project.puk.core.Lang

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.project.puk.api.Card
import com.project.puk.api.Spotify
import com.project.puk.api.TrackRow
import com.project.puk.core.Auth
import com.project.puk.player.ListeningHistoryEntry
import com.project.puk.player.PukPlayer
import com.project.puk.ui.theme.EmberIconButton
import com.project.puk.ui.theme.EmberIcons
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ProfileDrawer(
    account: Auth.Account?, savedAccounts: List<Auth.Account>, onProfile: () -> Unit,
    onAddAccount: () -> Unit, onSwitchAccount: (String) -> Unit,
    onStats: () -> Unit, onRecent: () -> Unit,
    onUpdates: () -> Unit, onSettings: () -> Unit,
    onJam: () -> Unit, onAbout: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(.9f),
        drawerShape = RoundedCornerShape(0.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onProfile).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountAvatar(account, Modifier.size(52.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account?.name?.ifBlank { Lang.t(Lang.Key.LIB_PROFILE) } ?: Lang.t(Lang.Key.LIB_PROFILE), style = MaterialTheme.typography.titleLarge)
                            Text(Lang.t(Lang.Key.DRAWER_VIEW_PROFILE), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        savedAccounts.filterNot { it.id == account?.id }.forEach { saved ->
                            key(saved.id) {
                                val nickname = saved.name.ifBlank { saved.id }
                                Column(
                                    Modifier.width(80.dp)
                                        .clickable(role = Role.Button) { onSwitchAccount(saved.id) }
                                        .semantics(mergeDescendants = true) { contentDescription = Lang.t(Lang.Key.DRAWER_SWITCH_ACCOUNT, nickname) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AccountAvatar(saved, Modifier.size(48.dp))
                                    Text(
                                        nickname,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            IconButton(onClick = onAddAccount, modifier = Modifier.size(48.dp)) {
                                Icon(EmberIcons.Add, Lang.t(Lang.Key.DRAWER_ADD_ACCOUNT), Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
            DrawerAction(EmberIcons.Stats, Lang.t(Lang.Key.DRAWER_LISTENING_STATS), onClick = onStats)
            DrawerAction(EmberIcons.History, Lang.t(Lang.Key.LIB_ORDER_RECENTS), onClick = onRecent)
            DrawerAction(EmberIcons.Updates, Lang.t(Lang.Key.DRAWER_UPDATES), badge = AppUpdateUi.updateAvailable, onClick = onUpdates)
            DrawerAction(EmberIcons.Settings, Lang.t(Lang.Key.DRAWER_SETTINGS_PRIVACY), onClick = onSettings)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerAction(EmberIcons.Groups, Lang.t(Lang.Key.DRAWER_JAM), onClick = onJam)
            DrawerAction(EmberIcons.Info, Lang.t(Lang.Key.DRAWER_ABOUT), onClick = onAbout)
        }
    }
}

@Composable
private fun DrawerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String? = null, badge: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box {
            Icon(icon, null, Modifier.size(26.dp))
            if (badge) {
                // Ember dot: a newer app version is waiting in «Обновления».
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AccountAvatar(account: Auth.Account?, modifier: Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        if (!account?.imageUrl.isNullOrBlank()) Artwork(account?.imageUrl.orEmpty(), Modifier.fillMaxSize(), circular = true)
        else Box(contentAlignment = Alignment.Center) {
            Text(account?.name?.take(1)?.uppercase() ?: "S", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

internal fun openAccountWeb(context: Context, url: String) {
    openExternalSpotify(context, url)
}

internal fun shareProfile(context: Context, account: Auth.Account?) {
    if (account?.id.isNullOrBlank()) return
    val url = "https://open.spotify.com/user/${Uri.encode(account?.id.orEmpty())}"
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), Lang.t(Lang.Key.DRAWER_INVITE_FRIENDS)))
    }
}

@Composable
internal fun AddAccountSheet(
    accounts: List<Auth.Account>, activeId: String?, onDismiss: () -> Unit,
    onSelect: (String) -> Unit, onAdd: () -> Unit,
) {
    EmberActionSheet(onDismiss, Lang.t(Lang.Key.DRAWER_ACCOUNTS)) {
        accounts.forEach { account ->
            Row(Modifier.fillMaxWidth().clickable(onClick = { onSelect(account.id) }).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountAvatar(account, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name.ifBlank { account.id }, style = MaterialTheme.typography.titleMedium)
                    Text(if (account.id == activeId) Lang.t(Lang.Key.DRAWER_CURRENT_ACCOUNT) else Lang.t(Lang.Key.DRAWER_TAP_TO_SWITCH), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (account.id == activeId) Icon(EmberIcons.Check, Lang.t(Lang.Key.DRAWER_CURRENT_ACCOUNT), tint = MaterialTheme.colorScheme.primary)
            }
        }
        EmberSheetCard(EmberIcons.Add, Lang.t(Lang.Key.DRAWER_SIGN_IN_OTHER), Lang.t(Lang.Key.DRAWER_SAVED_ACCOUNTS_STAY), onAdd)
    }
}

@Composable
internal fun ProfileDestination(page: String, account: Auth.Account?, player: PukPlayer, onBack: () -> Unit, onOpen: (Target) -> Unit, onPlay: (List<TrackRow>, Int) -> Unit, onTrackActions: (TrackRow) -> Unit, onEditProfileImage: () -> Unit = {}) {
    if (page == "recent") {
        RecentActivityScreen(account?.id.orEmpty(), player, onBack, onOpen, onPlay, onTrackActions)
        return
    }
    if (page == "profile") {
        UserProfileScreen(account, onBack, onOpen, onEditProfileImage)
        return
    }
    // "updates" — the only remaining drawer page: check + in-app update + changelog.
    UpdatesScreen(onBack)
}

internal fun publicPlaylistCountLabel(count: Int): String {
    if (count <= 0) return Lang.t(Lang.Key.DRAWER_NO_PUBLIC_PLAYLISTS)
    val noun = when {
        count % 100 in 11..14 -> Lang.t(Lang.Key.DRAWER_PLAYLISTS_GEN)
        count % 10 == 1 -> Lang.t(Lang.Key.DRAWER_PLAYLIST_ONE)
        count % 10 in 2..4 -> Lang.t(Lang.Key.DRAWER_PLAYLISTS_FEW)
        else -> Lang.t(Lang.Key.DRAWER_PLAYLISTS_GEN)
    }
    val adjective = if (count % 10 == 1 && count % 100 != 11) Lang.t(Lang.Key.DRAWER_PUBLIC_ONE) else Lang.t(Lang.Key.DRAWER_PUBLIC_MANY)
    return "$count $adjective $noun"
}

@Composable
private fun UserProfileScreen(account: Auth.Account?, onBack: () -> Unit, onOpen: (Target) -> Unit, onEditProfileImage: () -> Unit = {}) {
    val context = LocalContext.current
    var playlists by remember(account?.id) { mutableStateOf<List<Spotify.UserProfilePlaylist>?>(null) }
    LaunchedEffect(account?.id) {
        val id = account?.id
        playlists = if (id.isNullOrBlank()) emptyList() else Spotify.userPublicPlaylists(context, id)
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ProfilePageHeader(Lang.t(Lang.Key.LIB_PROFILE), onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = .19f), Color.Transparent)))
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box {
                            AccountAvatar(account, Modifier.size(108.dp))
                            if (account != null) {
                                FilledTonalIconButton(
                                    onClick = onEditProfileImage,
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                ) {
                                    Icon(androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_edit_playlist), Lang.t(Lang.Key.DRAWER_EDIT_PROFILE_PHOTO))
                                }
                            }
                        }
                        Text(
                            account?.name?.ifBlank { Lang.t(Lang.Key.LIB_PROFILE) } ?: Lang.t(Lang.Key.LIB_PROFILE),
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (account != null) {
                            OutlinedButton(onClick = { shareProfile(context, account) }) {
                                Icon(EmberIcons.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(Lang.t(Lang.Key.DRAWER_SHARE_PROFILE))
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(EmberIcons.LibraryMusic, null, tint = colors.primary)
                    Text(Lang.t(Lang.Key.DRAWER_PUBLIC_PLAYLISTS), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    playlists?.takeIf { it.isNotEmpty() }?.let { loaded ->
                        Surface(shape = CircleShape, color = colors.surfaceContainerHigh) {
                            Text(loaded.size.toString(), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            val loaded = playlists
            when {
                loaded == null -> item {
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(Lang.t(Lang.Key.DRAWER_LOADING_PLAYLISTS), color = colors.onSurfaceVariant)
                    }
                }
                loaded.isEmpty() -> item {
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                        Text(
                            Lang.t(Lang.Key.DRAWER_PLAYLISTS_EMPTY),
                            Modifier.fillMaxWidth().padding(16.dp),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                else -> items(loaded, key = { it.uri }) { playlist ->
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                        ListRow(Card(playlist.uri, playlist.name, "", playlist.imageUrl, "playlist"), showKind = false) {
                            onOpen(Target.Playlist(playlist.uri))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfilePageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        EmberIconButton(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_BACK), onBack)
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
    }
}

@Composable
private fun RecentActivityScreen(account: String, player: PukPlayer, onBack: () -> Unit, onOpen: (Target) -> Unit, onPlay: (List<TrackRow>, Int) -> Unit, onTrackActions: (TrackRow) -> Unit) {
    val context = LocalContext.current
    var filter by rememberSaveable(account) { mutableStateOf("all") }
    var saved by rememberSaveable(account) { mutableStateOf(false) }
    var history by remember(account) { mutableStateOf<List<ListeningHistoryEntry>?>(null) }
    var episodes by remember(account) { mutableStateOf<List<TrackRow>?>(null) }
    var problem by remember(account) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(account, revision) {
        problem = null
        try { history = player.listeningHistory(account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problem = Lang.t(Lang.Key.DRAWER_HISTORY_LOAD_FAILED) }
    }
    LaunchedEffect(account, filter, saved, revision) {
        if (filter == "podcasts" && saved) {
            problem = null
            try { episodes = Spotify.savedEpisodes(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = Lang.t(Lang.Key.DRAWER_SAVED_LOAD_FAILED) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ProfilePageHeader(Lang.t(Lang.Key.LIB_ORDER_RECENTS), onBack)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filter != "all") IconButton(onClick = { filter = "all"; saved = false }) { Icon(EmberIcons.Close, Lang.t(Lang.Key.DRAWER_RESET_FILTERS)) }
            listOf("music" to Lang.t(Lang.Key.LIB_MUSIC), "podcasts" to Lang.t(Lang.Key.LIB_PODCASTS)).filter { filter == "all" || it.first == filter }.forEach { (id, label) ->
                FilterChip(selected = filter == id, onClick = { filter = if (filter == id) "all" else id; saved = false }, label = { Text(label) }, shape = CircleShape, colors = recentFilterColors(), border = null)
            }
            if (filter == "podcasts") FilterChip(selected = saved, onClick = { saved = !saved }, label = { Text(Lang.t(Lang.Key.DRAWER_SAVED)) }, shape = CircleShape, colors = recentFilterColors(), border = null)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (problem != null) item { Text(checkNotNull(problem), Modifier.padding(16.dp)); TextButton(onClick = { revision++ }) { Text(Lang.t(Lang.Key.LIB_RETRY)) } }
            if (filter == "podcasts" && saved) {
                val rows = episodes
                if (rows == null && problem == null) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                if (rows?.isEmpty() == true) item { Text(Lang.t(Lang.Key.DRAWER_NO_SAVED_EPISODES), Modifier.padding(16.dp)) }
                items(rows.orEmpty(), key = { it.uri }) { row ->
                    ListRow(Card(row.uri, row.name, row.artists, row.imageUrl, "episode")) { onOpen(Target.Episode(row.uri)) }
                }
            } else {
                val rows = history?.filter { filter == "all" || (it.card.kind == "episode") == (filter == "podcasts") }
                if (rows == null && problem == null) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                if (rows?.isEmpty() == true) item { Text(Lang.t(Lang.Key.DRAWER_LOCAL_LISTENS_EMPTY), Modifier.padding(16.dp)) }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val formatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.forLanguageTag("ru"))
                rows.orEmpty().groupBy { Instant.ofEpochMilli(it.endedMs).atZone(zone).toLocalDate() }.forEach { (date, entries) ->
                    item(key = "date:$date") { Text(if (date == today) Lang.t(Lang.Key.DRAWER_TODAY) else date.format(formatter), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall) }
                    items(entries, key = { it.card.uri }) { entry ->
                        val card = entry.card
                        val row = TrackRow(uri = card.uri, id = card.uri.substringAfterLast(':'), name = card.title, artists = card.subtitle, albumName = "", albumUri = "", imageUrl = card.imageUrl, durationMs = 0L)
                        if (card.kind == "episode") ListRow(card) { onOpen(Target.Episode(card.uri)) }
                        else RecentTrackRow(row, { onPlay(listOf(row), 0) }, { onTrackActions(row) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTrackRow(row: TrackRow, onPlay: () -> Unit, onActions: () -> Unit) {
    Row(Modifier.fillMaxWidth().contextGesture(row.name, onPlay, onActions).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(row.imageUrl, Modifier.size(64.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(row.artists, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun recentFilterColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

/** Spotify profile editor. Keep native playback owned by PukPlayer. */
@Composable
internal fun AccountWebScreen(url: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var editorView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var editorReady by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editorView, editorReady, editorError) {
        val view = editorView ?: return@LaunchedEffect
        if (editorReady || editorError != null) return@LaunchedEffect
        repeat(200) {
            kotlinx.coroutines.delay(100)
            view.evaluateJavascript("window.__pukProfileReady === true") { result ->
                if (editorView === view && result == "true") editorReady = true
            }
            if (editorReady) return@LaunchedEffect
        }
        editorError = Lang.t(Lang.Key.DRAWER_PHOTO_EDITOR_FAILED)
    }
    var fileCallback by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }
    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_BACK)) }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            Lang.t(Lang.Key.DRAWER_TAP_PHOTO_HINT),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { root ->
                android.webkit.WebView(root).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Spotify's mobile player blocks this page before the editor mounts.
                    // Retain the installed Chromium version while requesting desktop UI.
                    settings.userAgentString = settings.userAgentString
                        .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                        .replace(" Mobile", "")
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    val jar = android.webkit.CookieManager.getInstance()
                    jar.setAcceptCookie(true)
                    jar.setAcceptThirdPartyCookies(this, true)
                    Auth.applySession(context)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                            // The web player checks DRM support even for profile editing.
                            val origin = request.origin
                            if (origin.scheme == "https" && origin.host == "open.spotify.com" &&
                                request.resources.contains(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                            ) {
                                request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                            } else {
                                request.deny()
                            }
                        }

                        override fun onShowFileChooser(
                            webView: android.webkit.WebView?,
                            callback: android.webkit.ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?,
                        ): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            imagePicker.launch("image/*")
                            return true
                        }
                    }
                    // Install before Spotify scripts where supported, including SPA startup.
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                            this, PROFILE_EDITOR_SCRIPT, setOf("https://open.spotify.com"),
                        )
                    }
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            editorReady = false
                            editorError = null
                        }

                        override fun onReceivedError(
                            view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                editorError = Lang.t(Lang.Key.DRAWER_PHOTO_EDITOR_FAILED)
                            }
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, finishedUrl: String?) {
                            if (Uri.parse(finishedUrl.orEmpty()).host == "open.spotify.com") {
                                view?.evaluateJavascript(PROFILE_EDITOR_SCRIPT, null)
                            }
                        }
                    }
                    editorView = this
                    loadUrl(url)
                }
            },
            update = { view ->
                view.alpha = if (editorReady) 1f else 0f
                view.importantForAccessibility = if (editorReady) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            },
            onRelease = { view ->
                if (editorView === view) editorView = null
                view.stopLoading()
                view.destroy()
            },
        )
            if (!editorReady) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable { },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    if (editorError == null) {
                        CircularProgressIndicator()
                        Text(Lang.t(Lang.Key.DRAWER_PHOTO_EDITOR_LOADING), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(editorError.orEmpty(), Modifier.padding(horizontal = 24.dp), textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            editorError = null
                            editorView?.reload()
                        }) { Text(Lang.t(Lang.Key.LIB_RETRY)) }
                    }
                }
            }
        }
    }
}

/** Hide surrounding controls without removing React nodes or editor portals. */
private const val PROFILE_EDITOR_SCRIPT = """
(function () {
  if (window.__pukProfileEditor) return;
  window.__pukProfileEditor = true;
  // Hiding controls alone does not stop audio. Block HTML media playback locally.
  HTMLMediaElement.prototype.play = function () {
    return Promise.reject(new DOMException('Profile editor only', 'NotAllowedError'));
  };
  function install() {
    if (!document.head || !document.body) return;
    var style = document.createElement('style');
    style.textContent =
      '#onetrust-consent-sdk,#onetrust-banner-sdk,#onetrust-pc-sdk,' +
      '.onetrust-pc-dark-filter,.ot-sdk-container[role=dialog]{display:none!important;pointer-events:none!important}' +
      'nav,[role="navigation"],[data-testid="left-sidebar"],' +
      '[data-testid="top-bar"],[data-testid="topbar"],' +
      '[data-testid="now-playing-bar"],[data-testid="now-playing-widget"],' +
      '[data-testid="player-bar"],footer{display:none!important}' +
      'html,body{min-width:0!important;width:100%!important;margin:0!important;' +
      'height:100vh!important;min-height:100vh!important}' +
      '[role=dialog][aria-modal=true]{max-height:calc(100vh - 24px)!important;overflow-y:auto!important}' +
      '[data-puk-profile-path]{display:block!important;position:relative!important;' +
      'grid-area:auto!important;min-width:0!important;width:100%!important;' +
      'max-width:100%!important;height:auto!important;min-height:0!important;' +
      'margin:0!important;padding:0!important;transform:none!important;' +
      'contain:none!important;overflow:visible!important}' +
      '[data-testid="user-image"]{width:200px!important;height:200px!important;margin:32px auto!important}' +
      '[data-puk-profile-hidden]{display:none!important}';
    document.head.appendChild(style);
    var readyPending = false;
    function strip() {
      // Reject optional cookies when OneTrust is available; otherwise suppress
      // only its banner/backdrop. Never click a blanket accept button.
      if (window.OneTrust && typeof window.OneTrust.RejectAll === 'function' &&
          document.querySelector('#onetrust-banner-sdk:not([style*="display: none"])')) {
        if (!window.__pukCookiesDismissed) {
          window.__pukCookiesDismissed = true;
          window.OneTrust.RejectAll();
        }
      }
      document.querySelectorAll('audio,video').forEach(function (media) { media.pause(); });
      var main = document.querySelector('main');
      if (!main) return;
      var keep = main.querySelector('[data-testid="user-image"]');
      if (!keep) return;
      // Collapse the desktop grid and retain the avatar's complete ancestor path.
      // React portals for editing/upload must stay outside the hidden branches.
      while (keep && keep !== document.body) {
        var parent = keep.parentElement;
        if (!parent) break;
        Array.from(parent.children).forEach(function (el) {
          if (el !== keep) {
            var portal = el.matches('[role="dialog"],[role="menu"],[aria-modal="true"]') ||
              el.querySelector('[role="dialog"],[role="menu"],[aria-modal="true"]');
            if (portal) el.removeAttribute('data-puk-profile-hidden');
            else el.setAttribute('data-puk-profile-hidden', '');
          }
        });
        if (parent !== document.body) parent.setAttribute('data-puk-profile-path', '');
        keep = parent;
      }
      if (!window.__pukProfileReady && !readyPending) {
        readyPending = true;
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            readyPending = false;
            var avatar = main.querySelector('[data-testid="user-image"]');
            var button = avatar && avatar.querySelector('[data-testid="edit-image-button"]');
            var images = avatar ? Array.from(avatar.querySelectorAll('img')) : [];
            var rect = avatar && avatar.getBoundingClientRect();
            if (button && rect.width > 0 && rect.left >= 0 && rect.right <= innerWidth + 1 &&
                images.every(function (img) { return img.complete; })) {
              window.__pukProfileReady = true;
            } else {
              setTimeout(strip, 100);
            }
          });
        });
      }
    }
    strip();
    new MutationObserver(strip).observe(document.body, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install, {once:true});
  else install();
})();
"""
