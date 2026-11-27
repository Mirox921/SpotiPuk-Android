package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.project.puk.R
import com.project.puk.api.Card
import com.project.puk.api.Spotify
import com.project.puk.ui.theme.EmberIcons
import com.project.puk.ui.theme.emberPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBrowser(pages: PagedList<Card>, searchPages: android.util.LruCache<String, PagedList<Card>>, onOpen: (Target) -> Unit,
    onProfile: () -> Unit, accountName: String, onCreatePlaylist: (String?) -> Unit, onRefresh: () -> Unit, accountImageUrl: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() { query = ""; searchOpen = false; keyboard?.hide() }
    LaunchedEffect(searchOpen) { if (searchOpen) { searchFocus.requestFocus(); keyboard?.show() } }
    var sortMenu by remember { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf<String?>(null) }
    var serverPath by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var serverNames by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val serverFolder = serverPath.lastOrNull()
    fun leaveServerFolder() {
        serverPath = ArrayList(serverPath.dropLast(1))
        serverNames = ArrayList(serverNames.dropLast(1))
    }
    var folderCommand by remember { mutableStateOf<ServerFolderCommand?>(null) }
    var folderToEdit by remember { mutableStateOf<Card?>(null) }
    var movingServerCard by remember { mutableStateOf<Card?>(null) }
    var serverBusy by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    fun performServerChange(change: suspend () -> Boolean, onSuccess: () -> Unit = {}) {
        if (serverBusy) return
        serverBusy = true
        serverError = null
        scope.launch {
            try {
                if (change()) {
                    onSuccess()
                    onRefresh()
                } else serverError = Lang.t(Lang.Key.LIB_SERVER_CHANGE_ERROR)
            } finally { serverBusy = false }
        }
    }
    var grid by rememberSaveable { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    BackHandler(serverFolder != null) { leaveServerFolder() }
    BackHandler(searchOpen) { closeSearch() }
    val stateHolder = rememberSaveableStateHolder()
    val serverQuery = query.trim().takeIf { searchOpen }.orEmpty()
    val pageKey = listOf(serverFolder.orEmpty(), order.orEmpty(), serverQuery).joinToString("\u0000")
    val visiblePages = remember(pages, searchPages, pageKey) {
        if (serverQuery.isEmpty() && serverFolder == null && order == null) pages
        else searchPages.get(pageKey) ?: PagedList<Card>().also { searchPages.put(pageKey, it) }
    }
    val serverFolders = visiblePages.items.filter { it.kind == "folder" }.distinctBy(Card::uri)
    val serverFolderName = serverNames.lastOrNull()
    val showServerFolders = filter in setOf("", "playlist")
    val orderChoices = listOf(null to Lang.t(Lang.Key.LIB_ORDER_LIBRARY), "Alphabetical" to Lang.t(Lang.Key.LIB_ORDER_BY_NAME),
        "Recents" to Lang.t(Lang.Key.LIB_ORDER_RECENTS), "Recently Added" to Lang.t(Lang.Key.LIB_ORDER_RECENTLY_ADDED), "Creator" to Lang.t(Lang.Key.LIB_ORDER_BY_CREATOR))
    val cards = visiblePages.items.filter { it.target() != null }.distinctBy(Card::uri).filter { card ->
        filter.isEmpty() || card.kind == filter ||
            (filter == "playlist" && card.target() == Target.Liked) ||
            (filter == "podcasts" && (card.kind in setOf("show", "podcast", "episode") || card.savedEpisodes))
    }
    stateHolder.SaveableStateProvider(serverFolder ?: if (searchOpen) "root-search" else "root") {
        val scroll = rememberLazyListState()
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBrowseBottomPadding.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = .96f))) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (serverFolder == null) ProfileAvatar(onProfile, accountName, accountImageUrl)
                        else IconButton(onClick = { leaveServerFolder() }) { Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.LIB_BACK_TO_LIBRARY)) }
                        if (searchOpen) {
                            OutlinedTextField(query, { query = it }, placeholder = { Text(Lang.t(Lang.Key.LIB_SEARCH_IN_LIBRARY), maxLines = 1) }, singleLine = true,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(searchFocus),
                                leadingIcon = { IconButton(onClick = { closeSearch() }) { Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_CLOSE_SEARCH)) } },
                                trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(EmberIcons.Close, Lang.t(Lang.Key.JAM_CLEAR_SEARCH)) } } } else null,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                shape = RoundedCornerShape(12.dp))
                        } else {
                            Text(serverFolderName ?: if (serverFolder == null) Lang.t(Lang.Key.LIB_LIBRARY) else Lang.t(Lang.Key.LIB_SPOTIFY_FOLDER), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick = { searchOpen = true }) { Icon(painterResource(R.drawable.ember_ctx_search), Lang.t(Lang.Key.LIB_SEARCH_IN_LIBRARY), Modifier.size(24.dp)) }
                        }
                        IconButton(onClick = { create = true }) { Icon(painterResource(R.drawable.ember_ctx_create_playlist), Lang.t(Lang.Key.LIB_CREATE), Modifier.size(24.dp)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to Lang.t(Lang.Key.LIB_FILTER_ALL), "playlist" to Lang.t(Lang.Key.JAM_TAB_PLAYLISTS), "podcasts" to Lang.t(Lang.Key.LIB_PODCASTS), "album" to Lang.t(Lang.Key.LIB_ALBUMS), "artist" to Lang.t(Lang.Key.LIB_ARTISTS)).forEach { (id, label) ->
                            FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) }, colors = emberFilterColors())
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { sortMenu = true }, modifier = Modifier.weight(1f)) { Icon(painterResource(R.drawable.ember_ctx_sort), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(orderChoices.first { it.first == order }.second, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, Lang.t(Lang.Key.LIB_REFRESH_LIBRARY)) }
                    IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, if (grid) Lang.t(Lang.Key.LIB_SHOW_LIST) else Lang.t(Lang.Key.LIB_SHOW_GRID)) }
                }
                serverError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (grid) {
                if (showServerFolders) items(serverFolders.chunked(2), key = { "grid:${it.first().uri}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { folder -> Box(Modifier.weight(1f)) { FolderTile(folder, modifier = Modifier.fillMaxWidth(), onOpen = {
                            serverPath = ArrayList(serverPath + folder.uri)
                            serverNames = ArrayList(serverNames + folder.title)
                            closeSearch()
                        }, onActions = { folderToEdit = folder }) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                items(cards.chunked(2), key = { "grid:${it.first().uri}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { card -> Box(Modifier.weight(1f)) { CardTile(card, modifier = Modifier.fillMaxWidth(), libraryFolder = serverFolder, onMoveInLibrary = if (card.kind == "playlist") { { movingServerCard = card } } else null, onClick = { card.target()?.let(onOpen) }) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                if (showServerFolders) items(serverFolders, key = Card::uri) { folder ->
                    val open = {
                        serverPath = ArrayList(serverPath + folder.uri)
                        serverNames = ArrayList(serverNames + folder.title)
                        closeSearch()
                    }
                    Row(Modifier.fillMaxWidth().contextGesture(folder.title, open, { folderToEdit = folder })
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ember_ctx_folder), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text(folder.title, style = MaterialTheme.typography.titleSmall)
                            Text(Lang.t(Lang.Key.LIB_SPOTIFY_FOLDER), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(cards, key = { it.uri }) { card ->
                    ListRow(card, libraryFolder = serverFolder,
                        onMoveInLibrary = if (card.kind == "playlist") { { movingServerCard = card } } else null) { card.target()?.let(onOpen) }
                }
            }
            if (cards.isEmpty() && (!showServerFolders || serverFolders.isEmpty()) && (serverFolder != null || visiblePages.offset == null)) item {
                Text(if (query.isNotBlank()) Lang.t(Lang.Key.JAM_NOTHING_FOUND) else if (serverFolder != null) Lang.t(Lang.Key.LIB_FOLDER_EMPTY) else Lang.t(Lang.Key.LIB_NO_ITEMS), Modifier.padding(20.dp))
            }
            item {
                PageFooter(visiblePages) { offset ->
                    if (serverQuery.isNotEmpty() && offset == 0) delay(350)
                    Spotify.library(context, offset, query = serverQuery, order = order, folderUri = serverFolder)
                }
            }
        }
    }
    if (sortMenu) EmberActionSheet(onDismissRequest = { sortMenu = false }, heading = Lang.t(Lang.Key.LIB_SORT_LIBRARY), text = {
        orderChoices.forEach { (id, label) ->
            SheetAction(R.drawable.ember_ctx_sort, (if (order == id) "✓ " else "") + label) {
                order = id
                sortMenu = false
            }
        }
    })
    if (create) EmberActionSheet(onDismissRequest = { create = false }, heading = Lang.t(Lang.Key.LIB_CREATE), text = {
        SheetAction(R.drawable.ember_ctx_create_playlist, Lang.t(Lang.Key.LIB_SPOTIFY_PLAYLIST)) { create = false; onCreatePlaylist(serverFolder) }
        SheetAction(R.drawable.ember_ctx_folder, Lang.t(Lang.Key.LIB_SPOTIFY_FOLDER)) { create = false; folderCommand = ServerFolderCommand(null) }
    })
    folderToEdit?.let { folder ->
        EmberActionSheet(onDismissRequest = { folderToEdit = null }, heading = folder.title) {
            SheetAction(R.drawable.ember_ctx_edit_playlist, Lang.t(Lang.Key.LIB_RENAME)) {
                folderToEdit = null
                folderCommand = ServerFolderCommand(folder)
            }
            SheetAction(R.drawable.ember_ctx_delete_playlist, Lang.t(Lang.Key.LIB_DELETE_FOLDER)) {
                folderToEdit = null
                folderCommand = ServerFolderCommand(folder, delete = true)
            }
            SheetAction(R.drawable.ember_ctx_move_folder, Lang.t(Lang.Key.SHEET_MOVE_TO_SPOTIFY_FOLDER)) {
                folderToEdit = null
                movingServerCard = folder
            }
            folder.pinned?.let { pinned ->
                SheetAction(R.drawable.ember_ctx_pin_library, if (pinned) Lang.t(Lang.Key.SHEET_UNPIN) else Lang.t(Lang.Key.SHEET_PIN), enabled = !serverBusy) {
                    performServerChange({ Spotify.setLibraryPinned(context, folder.uri, !pinned, serverFolder) }) { folderToEdit = null }
                }
            }
        }
    }
    folderCommand?.let { command ->
        if (command.delete) {
            AlertDialog(onDismissRequest = { if (!serverBusy) folderCommand = null },
                title = { Text(Lang.t(Lang.Key.LIB_DELETE_FOLDER_CONFIRM, command.folder?.title.orEmpty())) },
                text = { Text(serverError ?: Lang.t(Lang.Key.LIB_DELETE_FOLDER_HINT)) },
                confirmButton = {
                    TextButton(enabled = !serverBusy, onClick = {
                        performServerChange({ Spotify.changeLibraryFolder(context, command.folder?.uri, null, null) }) { folderCommand = null }
                    }) { Text(Lang.t(Lang.Key.LIB_DELETE_FOLDER)) }
                },
                dismissButton = { TextButton(enabled = !serverBusy, onClick = { folderCommand = null }) { Text(Lang.t(Lang.Key.JAM_CANCEL)) } })
        } else FolderNameSheet(initial = command.folder?.title.orEmpty(), rename = command.folder != null,
            onDismiss = { if (!serverBusy) folderCommand = null }, onSave = { name ->
                performServerChange({ Spotify.changeLibraryFolder(context, command.folder?.uri, serverFolder, name) }) { folderCommand = null }
            }, error = serverError, busy = serverBusy)
    }
    movingServerCard?.let { card ->
        EmberActionSheet(onDismissRequest = { if (!serverBusy) movingServerCard = null }, heading = if (card.kind == "folder") Lang.t(Lang.Key.LIB_MOVE_FOLDER) else Lang.t(Lang.Key.LIB_MOVE_PLAYLIST)) {
            val destinations = (pages.items + serverFolders + searchPages.snapshot().values.flatMap { it.items })
                .filter { it.kind == "folder" && it.uri != serverFolder && it.uri != card.uri }
                .distinctBy(Card::uri)
            if (serverFolder != null) SheetAction(R.drawable.ember_ctx_move_folder, Lang.t(Lang.Key.LIB_MOVE_TO_ROOT), enabled = !serverBusy) {
                performServerChange({ Spotify.moveLibraryItem(context, card.uri, null) }) { movingServerCard = null }
            }
            for (destination in destinations) SheetAction(R.drawable.ember_ctx_folder, destination.title, enabled = !serverBusy) {
                performServerChange({ Spotify.moveLibraryItem(context, card.uri, destination.uri) }) { movingServerCard = null }
            }
            if (serverFolder == null && destinations.isEmpty()) Text(Lang.t(Lang.Key.LIB_FOLDERS_NOT_LOADED))
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private data class ServerFolderCommand(val folder: Card?, val delete: Boolean = false)

@Composable
private fun FolderNameSheet(initial: String, rename: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, error: String?, busy: Boolean) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    EmberActionSheet(onDismissRequest = onDismiss, heading = if (rename) Lang.t(Lang.Key.LIB_RENAME_FOLDER) else Lang.t(Lang.Key.LIB_SPOTIFY_FOLDER),
        dismissEnabled = !busy, confirmButton = {
        Button(enabled = name.isNotBlank() && !busy, onClick = { onSave(name.trim()) }) { Text(if (rename) Lang.t(Lang.Key.LIB_SAVE) else Lang.t(Lang.Key.LIB_CREATE)) }
    }, text = {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text(Lang.t(Lang.Key.LIB_NAME_LABEL)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && !busy) onSave(name.trim()) }))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    })
}

/** Library tile for a Spotify folder: the glyph sits on an opaque container so it keeps contrast on any background. */
@Composable
private fun FolderTile(folder: Card, modifier: Modifier = Modifier, onOpen: () -> Unit, onActions: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .emberPress(press, 0.985f)
            .clip(RoundedCornerShape(12.dp))
            .contextGesture(folder.title, onOpen, onActions, press)
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ember_ctx_folder), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(Lang.t(Lang.Key.LIB_FOLDER_LABEL), Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.background.copy(alpha = .88f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(folder.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
