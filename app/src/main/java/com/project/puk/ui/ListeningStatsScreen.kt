package com.project.puk.ui

import com.project.puk.core.Lang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.project.puk.api.Spotify
import com.project.puk.ui.theme.EmberIcons
import androidx.compose.material3.Icon
import com.project.puk.player.ListeningRank
import com.project.puk.player.ListeningWeek
import com.project.puk.player.PukPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// Catalog art for stats cards is stable; a session-scoped uri→image cache
// lets reopening the screen skip the metadata round-trips entirely.
private val statArtCache = android.util.LruCache<String, String>(96)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListeningStatsScreen(account: String, player: PukPlayer, onBack: () -> Unit, onOpen: (Target) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var offset by rememberSaveable(account) { mutableIntStateOf(0) }
    var enabled by remember(account) { mutableStateOf(player.statsEnabled(account)) }
    var refreshing by remember(account) { mutableIntStateOf(0) }
    var result by remember(account, offset, refreshing) { mutableStateOf<ListeningWeek?>(null) }
    var problem by remember(account, offset, refreshing) { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val start = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(offset.toLong())
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var pastWeeks by remember(account, refreshing) { mutableStateOf<List<Pair<LocalDate, ListeningWeek>>>(emptyList()) }
    LaunchedEffect(account, refreshing) {
        val monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        try {
            pastWeeks = (1L..8L).map { ago ->
                val date = monday.minusWeeks(ago)
                date to player.listeningWeek(account, date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    date.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli())
            }.filter { it.second.timeMs > 0 }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problem = Lang.t(Lang.Key.STATS_PAST_WEEKS_FAILED) }
    }
    val dates = "${start.format(formatter)} — ${start.plusDays(6).format(formatter)}"

    LaunchedEffect(account, start, refreshing) {
        try {
            result = player.listeningWeek(account, start.atStartOfDay(zone).toInstant().toEpochMilli(),
                start.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            problem = Lang.t(Lang.Key.STATS_LOAD_FAILED)
        }
    }
    val resolvedArtists = remember(account, start) { mutableStateMapOf<String, ListeningRank>() }
    LaunchedEffect(account, result) {
        result?.artists?.forEach { artist ->
            // One child coroutine per entity: resolutions run in parallel
            // instead of queueing behind every prior catalog round-trip.
            launch {
                try {
                    var uri = artist.uri
                    if (uri.isBlank() && artist.sourceUri.startsWith("spotify:track:")) {
                        val key = "tr:" + artist.sourceUri
                        uri = statArtCache.get(key) ?: Spotify.track(context, artist.sourceUri)?.artistUri.orEmpty()
                            .also { if (it.isNotBlank()) statArtCache.put(key, it) }
                    }
                    if (uri.startsWith("spotify:artist:")) {
                        val image = statArtCache.get(uri)
                            ?: Spotify.artist(context, uri)?.imageUrl?.also { statArtCache.put(uri, it) }
                        image?.let { resolvedArtists[artist.title] = artist.copy(uri = uri, imageUrl = it) }
                    } else if (artist.sourceUri.startsWith("spotify:episode:")) {
                        val episode = Spotify.episode(context, artist.sourceUri)
                        if (episode != null && episode.showUri.isNotBlank()) {
                            val key = "show:" + episode.showUri
                            val image = statArtCache.get(key)
                                ?: Spotify.show(context, episode.showUri)?.imageUrl?.also { statArtCache.put(key, it) }
                            image?.let { resolvedArtists[artist.title] = artist.copy(uri = episode.showUri, imageUrl = it) }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Local statistics remain usable without catalog metadata. */ }
            }
        }
    }
    if (confirming) {
        EmberActionSheet(
            onDismissRequest = { confirming = false }, heading = Lang.t(Lang.Key.STATS_CLEAR_CONFIRM),
            dismissEnabled = !clearing,
            confirmButton = {
                Button(enabled = !clearing, onClick = {
                    clearing = true
                    scope.launch {
                        try {
                            player.clearListeningStats(account)
                            confirming = false
                            refreshing++
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            confirming = false
                            problem = Lang.t(Lang.Key.STATS_CLEAR_FAILED)
                        } finally {
                            clearing = false
                        }
                    }
                }) { Text(Lang.t(Lang.Key.STATS_CLEAR)) }
            },
        ) { Text(Lang.t(Lang.Key.STATS_CLEAR_WARNING)) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stickyHeader {
            Surface(color = colors.background) { ProfilePageHeader(Lang.t(Lang.Key.DRAWER_LISTENING_STATS), onBack) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { offset++ }) { Text(Lang.t(Lang.Key.STATS_EARLIER)) }
                TextButton(enabled = offset > 0, onClick = { offset-- }) { Text(Lang.t(Lang.Key.STATS_LATER)) }
            }
            Text(if (offset == 0) Lang.t(Lang.Key.STATS_THIS_WEEK) else Lang.t(Lang.Key.STATS_PREV_WEEK) + " · $offset", style = MaterialTheme.typography.titleMedium)
            Text(dates, color = colors.onSurfaceVariant)
        }
        val week = result
        if (problem != null) item {
            Text(checkNotNull(problem), color = colors.error)
            TextButton(onClick = { refreshing++ }) { Text(Lang.t(Lang.Key.LIB_RETRY)) }
        }
        if (week == null && problem == null) item { CircularProgressIndicator() }
        if (week != null) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(Lang.t(Lang.Key.STATS_MINUTES_LISTENED), "${week.timeMs / 60_000}", Modifier.weight(1f))
                    StatTile(Lang.t(Lang.Key.STATS_UNIQUE_TRACKS), "${week.uniqueTracks}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatEntityCard(Lang.t(Lang.Key.STATS_FAVORITE_ARTIST), week.artists.firstOrNull()?.let { resolvedArtists[it.title] ?: it }, true, Modifier.weight(1f), onOpen)
                    StatEntityCard(Lang.t(Lang.Key.STATS_FAVORITE_TRACK), week.tracks.firstOrNull(), false, Modifier.weight(1f), onOpen)
                }
                Text(Lang.t(Lang.Key.STATS_LOCAL_LISTENS), Modifier.padding(top = 12.dp), color = colors.onSurfaceVariant)
            }
            if (week.artists.isNotEmpty()) {
                item { Text(Lang.t(Lang.Key.STATS_TOP_ARTISTS), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(week.artists) { index, artist -> ListeningRankRow(index, resolvedArtists[artist.title] ?: artist, true, onOpen) }
            }
            if (week.tracks.isNotEmpty()) {
                item { Text(Lang.t(Lang.Key.STATS_TOP_TRACKS), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(week.tracks) { index, track -> ListeningRankRow(index, track, false, onOpen) }
                item {
                    Button(enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                shareEmberImage(context) {
                                    renderListeningCard(context, week, dates, colors.background.toArgb(), colors.onSurface.toArgb(), colors.primary.toArgb())
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                problem = Lang.t(Lang.Key.STATS_CARD_PREPARE_FAILED)
                            } finally {
                                exporting = false
                            }
                        }
                    }) { Text(Lang.t(Lang.Key.STATS_SHARE_CARD)) }
                }
            }
        }
        if (offset == 0 && pastWeeks.isNotEmpty()) {
            item { Text(Lang.t(Lang.Key.STATS_PAST_WEEKS), style = MaterialTheme.typography.headlineSmall) }
            itemsIndexed(pastWeeks, key = { _, entry -> entry.first.toEpochDay() }) { _, (date, summary) ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        offset = java.time.temporal.ChronoUnit.WEEKS.between(date, LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toInt()
                    },
                    shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${date.format(formatter)} — ${date.plusDays(6).format(formatter)}", style = MaterialTheme.typography.titleMedium)
                        Text(summary.tracks.firstOrNull()?.title ?: summary.artists.firstOrNull()?.title.orEmpty(), maxLines = 2)
                        Text(formatListening(summary.timeMs), color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Lang.t(Lang.Key.STATS_COLLECT_ON_PHONE), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    player.setStatsEnabled(account, it)
                })
            }
            TextButton(onClick = { confirming = true }) { Text(Lang.t(Lang.Key.STATS_CLEAR_STATS), color = colors.error) }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier) {
    Surface(modifier.height(176.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = if (value.all { it.isDigit() }) MaterialTheme.typography.headlineLarge.copy(fontSize = 48.sp) else MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun ListeningRank.destination(): Target? = when {
    uri.startsWith("spotify:artist:") -> Target.Artist(uri)
    uri.startsWith("spotify:track:") -> Target.Track(uri)
    uri.startsWith("spotify:episode:") -> Target.Episode(uri)
    uri.startsWith("spotify:show:") -> Target.Show(uri)
    else -> null
}

@Composable
private fun StatEntityCard(label: String, rank: ListeningRank?, artist: Boolean, modifier: Modifier, onOpen: (Target) -> Unit) {
    val target = rank?.destination()
    Surface(modifier.then(if (target != null) Modifier.clickable { onOpen(target) } else Modifier),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EntityArtwork(rank, artist, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(rank?.title ?: Lang.t(Lang.Key.STATS_START_LISTENING), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (rank != null) Text(formatListening(rank.timeMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntityArtwork(rank: ListeningRank?, artist: Boolean, modifier: Modifier) {
    if (!rank?.imageUrl.isNullOrBlank()) Artwork(rank?.imageUrl.orEmpty(), modifier, circular = artist)
    else Surface(modifier, shape = if (artist) CircleShape else RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(if (artist) EmberIcons.Person else EmberIcons.MusicNote, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ListeningRankRow(index: Int, item: ListeningRank, artist: Boolean, onOpen: (Target) -> Unit) {
    val target = item.destination()
    Surface(Modifier.fillMaxWidth().then(if (target != null) Modifier.clickable { onOpen(target) } else Modifier),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${index + 1}", color = MaterialTheme.colorScheme.primary)
            EntityArtwork(item, artist, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.detail.isNotBlank()) Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(formatListening(item.timeMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatListening(ms: Long): String = if (ms < 60_000) "${ms / 1_000} " + Lang.t(Lang.Key.STATS_SEC) else "${ms / 60_000} " + Lang.t(Lang.Key.STATS_MIN)

private suspend fun renderListeningCard(
    context: Context,
    week: ListeningWeek,
    dates: String,
    background: Int,
    foreground: Int,
    accent: Int,
): Bitmap {
    // Catalog art for the favorites: resolve missing urls, then decode via
    // Coil (software bitmaps — the card is drawn on a plain Canvas).
    val artist = week.artists.firstOrNull()?.let { statArtFor(context, it) }
    val track = week.tracks.firstOrNull()?.let { statArtFor(context, it) }
    val artistArt = loadCardArt(context, artist?.imageUrl)
    val trackArt = loadCardArt(context, track?.imageUrl)

    val image = Bitmap.createBitmap(900, 1300, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    canvas.drawColor(background)
    // Soft accent glow from the top-right corner.
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(760f, 40f, 640f, accent, background, android.graphics.Shader.TileMode.CLAMP)
        alpha = 46
    }
    canvas.drawRect(0f, 0f, 900f, 780f, glow)

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    fun line(text: String, y: Float, size: Float, color: Int, bold: Boolean = false, x: Float = 60f, max: Float = 780f, align: Paint.Align = Paint.Align.LEFT) {
        paint.textSize = size
        paint.color = color
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = align
        val drawn = TextUtils.ellipsize(text, paint, max, TextUtils.TruncateAt.END).toString()
        if (align == Paint.Align.CENTER) canvas.drawText(drawn, x, y, paint) else canvas.drawText(drawn, x, y, paint)
    }

    line("SpotiPuk", 96f, 40f, accent, true)
    line(Lang.t(Lang.Key.DRAWER_LISTENING_STATS), 172f, 56f, foreground, true)
    line(dates, 228f, 30f, foreground)
    canvas.drawRect(60f, 264f, 840f, 266f, Paint().apply { color = accent; alpha = 70 })

    line(formatListening(week.timeMs), 420f, 104f, accent, true)
    line(Lang.t(Lang.Key.STATS_UNIQUE_TRACKS) + " · ${week.uniqueTracks}", 478f, 34f, foreground)

    // Favorite tiles: artist (circular) and track (rounded square) with art.
    val tileTop = 540f
    val tileBottom = 1080f
    val panelPaint = Paint().apply { color = foreground; alpha = 14 }
    val edge = 8f
    canvas.drawRoundRect(56f, tileTop, 436f, tileBottom, edge, edge, panelPaint)
    canvas.drawRoundRect(464f, tileTop, 844f, tileBottom, edge, edge, panelPaint)

    val artCenterY = 706f
    val radius = 124f
    fun drawArt(src: Bitmap?, cx: Float, cy: Float, r: Float, circular: Boolean, fallback: String) {
        if (src != null) {
            canvas.save()
            if (circular) {
                val path = android.graphics.Path().apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) }
                canvas.clipPath(path)
                val s = minOf(src.width, src.height).toFloat()
                val srcRect = android.graphics.Rect(
                    ((src.width - s) / 2).toInt(), ((src.height - s) / 2).toInt(),
                    ((src.width + s) / 2).toInt(), ((src.height + s) / 2).toInt(),
                )
                canvas.drawBitmap(src, srcRect, android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = accent }
                canvas.drawCircle(cx, cy, r + 3f, ring)
            } else {
                val clip = android.graphics.Path().apply { addRoundRect(cx - r, cy - r, cx + r, cy + r, 26f, 26f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                val s = minOf(src.width, src.height).toFloat()
                val srcRect = android.graphics.Rect(
                    ((src.width - s) / 2).toInt(), ((src.height - s) / 2).toInt(),
                    ((src.width + s) / 2).toInt(), ((src.height + s) / 2).toInt(),
                )
                canvas.drawBitmap(src, srcRect, android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; alpha = 26 }
            if (circular) canvas.drawCircle(cx, cy, r, fill) else canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, 26f, 26f, fill)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 96f
            paint.color = accent
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(fallback.take(1).uppercase(), cx, cy + 34f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    val tileLabel = { text: String, cx: Float, y: Float -> line(text, y, 24f, accent, true, x = cx, max = 356f, align = Paint.Align.CENTER) }
    val tileName = { text: String, cx: Float, y: Float -> line(text, y, 38f, foreground, true, x = cx, max = 350f, align = Paint.Align.CENTER) }

    drawArt(artistArt, 246f, artCenterY, radius, circular = true, fallback = artist?.title.orEmpty().ifBlank { Lang.t(Lang.Key.STATS_ARTIST_INITIAL) })
    tileLabel(Lang.t(Lang.Key.STATS_CARD_FAV_ARTIST), 246f, 902f)
    tileName(artist?.title.orEmpty().ifBlank { Lang.t(Lang.Key.STATS_NO_DATA) }, 246f, 956f)
    artist?.let { line(formatListening(it.timeMs), 1004f, 28f, foreground, x = 246f, max = 340f, align = Paint.Align.CENTER) }

    drawArt(trackArt, 654f, artCenterY, radius, circular = false, fallback = track?.title.orEmpty().ifBlank { Lang.t(Lang.Key.STATS_TRACK_INITIAL) })
    tileLabel(Lang.t(Lang.Key.STATS_CARD_FAV_TRACK), 654f, 902f)
    tileName(track?.title.orEmpty().ifBlank { Lang.t(Lang.Key.STATS_NO_DATA) }, 654f, 956f)
    track?.let { line(formatListening(it.timeMs), 1004f, 28f, foreground, x = 654f, max = 340f, align = Paint.Align.CENTER) }

    canvas.drawRect(60f, 1170f, 840f, 1172f, Paint().apply { color = accent; alpha = 50 })
    line(Lang.t(Lang.Key.STATS_LOCAL_LISTENS) + " · $dates", 1238f, 28f, foreground, x = 60f)
    return image
}

/** Resolve missing catalog art for a rank entry (cached via statArtCache). */
private suspend fun statArtFor(context: Context, rank: ListeningRank): ListeningRank {
    if (rank.imageUrl.isNotBlank()) return rank
    return runCatching {
        when {
            rank.uri.startsWith("spotify:artist:") -> {
                val image = statArtCache.get(rank.uri)
                    ?: Spotify.artist(context, rank.uri)?.imageUrl?.also { statArtCache.put(rank.uri, it) }.orEmpty()
                rank.copy(imageUrl = image)
            }
            rank.sourceUri.startsWith("spotify:episode:") -> {
                val episode = Spotify.episode(context, rank.sourceUri) ?: return rank
                val key = "show:${episode.showUri}"
                val image = statArtCache.get(key)
                    ?: Spotify.show(context, episode.showUri)?.imageUrl?.also { statArtCache.put(key, it) }.orEmpty()
                rank.copy(imageUrl = image)
            }
            else -> {
                val src = rank.uri.ifBlank { rank.sourceUri }
                if (src.startsWith("spotify:track:")) rank.copy(imageUrl = Spotify.track(context, src)?.imageUrl.orEmpty()) else rank
            }
        }
    }.getOrDefault(rank)
}

/** Decode art for the share card; software bitmap so Canvas can draw it. */
private suspend fun loadCardArt(context: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        val request = coil.request.ImageRequest.Builder(context)
            .data(url)
            .size(460)
            .allowHardware(false)
            .build()
        coil.Coil.imageLoader(context).execute(request).drawable?.toBitmap()
    }.getOrNull()
}
