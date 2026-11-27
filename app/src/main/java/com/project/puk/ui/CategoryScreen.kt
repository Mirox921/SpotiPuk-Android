package com.project.puk.ui

import com.project.puk.core.Lang

import com.project.puk.ui.theme.EmberIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.project.puk.ui.theme.emberPress
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.project.puk.catalog.CatalogCategory
import com.project.puk.catalog.CategoryArtwork
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.project.puk.api.Card
import com.project.puk.api.Section
import com.project.puk.api.TrackRow
import com.project.puk.catalog.CatalogRepository
import com.project.puk.catalog.RecentCatalogProvider
import com.project.puk.catalog.EditorialCatalogProvider
import com.project.puk.catalog.HomeCatalogProvider
import kotlinx.coroutines.CancellationException

@Composable
internal fun CategoryScreen(
    scroll: LazyListState,
    id: String,
    recentTracks: List<Card>,
    homeSections: List<Section>?,
    onBack: () -> Unit,
    onOpen: (Target) -> Unit,
    onPlay: (List<TrackRow>, Int) -> Unit,
    onTrackActions: (TrackRow) -> Unit,
) {
    val context = LocalContext.current
    val language = Lang.current
    val editorial = remember(context, language) { EditorialCatalogProvider(context.applicationContext) }
    val home = remember(homeSections, language) { HomeCatalogProvider(homeSections.orEmpty()) }
    val relevantHistory = if (id == "recent") recentTracks else emptyList()
    val repository = remember(editorial, home, relevantHistory) {
        CatalogRepository(home, RecentCatalogProvider(relevantHistory), editorial)
    }
    val category = remember(repository, id) { repository.categories().firstOrNull { it.id == id } }
    var page by remember(id, home, editorial) { mutableStateOf(home.snapshot(id) ?: editorial.fallback(id)) }
    var loading by remember(id) { mutableStateOf(page == null) }
    var failed by remember(id) { mutableStateOf(false) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, repository, retry) {
        loading = page == null
        failed = false
        try {
            page = repository.page(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = scroll,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 20.dp + LocalBrowseBottomPadding.current),
    ) {
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.BottomStart) {
                category?.let { CategoryBackdrop(it, Modifier.matchParentSize(), MaterialTheme.colorScheme.background) }
                Text(
                    category?.title ?: Lang.t(Lang.Key.CAT_CATEGORY),
                    Modifier.padding(start = 16.dp, top = 54.dp, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.headlineLarge,
                )
                IconButton(onBack, Modifier.align(Alignment.TopStart).padding(start = 4.dp)) {
                    Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.CAT_BACK))
                }
            }
        }
        item {
            Text(
                when {
                    id == "recent" -> Lang.t(Lang.Key.CAT_PLAYED_ON_PHONE)
                    page?.fromHome == true -> Lang.t(Lang.Key.CAT_FROM_SPOTIFY_HOME)
                    else -> Lang.t(Lang.Key.CAT_SPOTIPUK_PICKS)
                },
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val sections = page?.sections.orEmpty()
        if (loading) item {
            CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
        } else if (failed && sections.isEmpty()) {
            item { Text(Lang.t(Lang.Key.CAT_LOAD_FAILED), Modifier.padding(horizontal = 16.dp)) }
            item {
                TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(Lang.t(Lang.Key.LIB_RETRY))
                }
            }
        } else if (sections.isEmpty()) item {
            val message = if (id == "recent") Lang.t(Lang.Key.CAT_RECENT_EMPTY) else Lang.t(Lang.Key.CAT_NO_PICKS_YET)
            Text(message, Modifier.padding(horizontal = 16.dp))
        }
        sections.forEach { section ->
            item {
                Text(section.displayTitle, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            }
            val tracks = section.items.filter { it.kind == "track" }.map {
                TrackRow(it.uri, it.uri.substringAfterLast(':'), it.title, it.subtitle, "", "", it.imageUrl, 0)
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(section.items, key = { it.uri }) { card ->
                        val index = tracks.indexOfFirst { it.uri == card.uri }
                        CardTile(card, showPlay = index >= 0, onTrackActions = tracks.getOrNull(index)?.let { track -> { onTrackActions(track) } }, onClick = {
                            if (index >= 0) onPlay(tracks, index) else card.target()?.let(onOpen)
                        })
                    }
                }
            }
        }
    }
}


@Composable
internal fun CategoryTile(category: CatalogCategory, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 96.dp).emberPress(press, 0.985f).clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(press, LocalIndication.current, onClick = onClick),
    ) {
        CategoryTileArtwork(category, Modifier.matchParentSize())
        Text(
            category.title,
            Modifier.fillMaxWidth().padding(14.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryTileArtwork(category: CatalogCategory, modifier: Modifier) {
    val tint = Color(category.color)
    val dark = lerp(tint, MaterialTheme.colorScheme.background, 0.62f)
    val style = category.composition
    val background = if (style == CategoryArtwork.Gradient) {
        Brush.linearGradient(listOf(dark, tint, lerp(tint, MaterialTheme.colorScheme.primary, 0.23f)))
    } else {
        Brush.horizontalGradient(listOf(dark, tint))
    }
    Box(modifier.background(background)) {
        if (category.imageUrl.isNotBlank()) {
            val artwork = when (style) {
                CategoryArtwork.Full -> Modifier.matchParentSize()
                CategoryArtwork.Rotated -> Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.52f)
                    .aspectRatio(1f).offset(x = 9.dp, y = 13.dp).rotate(17f).clip(RoundedCornerShape(7.dp))
                CategoryArtwork.Gradient -> Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.54f)
                    .aspectRatio(1f).offset(x = 6.dp, y = 14.dp).clip(RoundedCornerShape(9.dp))
                CategoryArtwork.Typography -> Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.34f)
                    .aspectRatio(1f).offset(x = 3.dp, y = 6.dp).clip(RoundedCornerShape(9.dp))
            }
            AsyncImage(category.imageUrl, contentDescription = null, modifier = artwork, contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.linearGradient(
            0f to dark,
            0.55f to dark.copy(alpha = 0.96f),
            0.80f to dark.copy(alpha = 0.25f),
            1f to Color.Transparent,
        )))
    }
}

@Composable
private fun CategoryBackdrop(category: CatalogCategory, modifier: Modifier, base: Color) {
    Box(modifier.background(Brush.horizontalGradient(listOf(base, MaterialTheme.colorScheme.surfaceContainerHigh)))) {
        if (category.imageUrl.isNotBlank()) {
            AsyncImage(
                model = category.imageUrl,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.76f).fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to base,
            0.28f to base,
            0.70f to base.copy(alpha = 0.38f),
            1f to base.copy(alpha = 0.20f),
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to base.copy(alpha = 0.25f),
            0.55f to Color.Transparent,
            1f to base,
        )))
    }
}
