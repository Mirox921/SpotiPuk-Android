package com.project.puk.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal object EmberIcons {
    val Home = vector(
        "Home",
        "M3 10.4 L10.6 4.3 Q12 3.1 13.4 4.3 L21 10.4 V18.5 Q21 20 19.5 20 H15 V14 H9 V20 H4.5 Q3 20 3 18.5 Z",
    )
    val HomeActive = vector(
        "HomeActive",
        "M3 10.4 L10.6 4.3 Q12 3.1 13.4 4.3 L21 10.4 V18.5 Q21 20 19.5 20 H15 V14 H9 V20 H4.5 Q3 20 3 18.5 Z",
        "M3 10.4 L10.6 4.3 Q12 3.1 13.4 4.3 L21 10.4 V18.5 Q21 20 19.5 20 H15 V14 H9 V20 H4.5 Q3 20 3 18.5 Z",
    )
    val Search = vector(
        "Search",
        "M16.5 16.5 L21 21 M11 18 A7 7 0 1 0 11 4 A7 7 0 1 0 11 18 Z",
    )
    val SearchActive = vector(
        "SearchActive",
        "M16.5 16.5 L21 21",
        "M11 18 A7 7 0 1 0 11 4 A7 7 0 1 0 11 18 Z M11 15 A4 4 0 1 1 11 7 A4 4 0 1 1 11 15 Z",
    )
    val LibraryMusic = vector(
        "LibraryMusic",
        "M4 5 V19 M8 5 V19 M13 5 L18 4 L21 18 L16 19 Z",
    )
    val LibraryActive = vector(
        "LibraryActive",
        "M4 5 V19 M8 5 V19 M13 5 L18 4 L21 18 L16 19 Z",
        "M13 5 L18 4 L21 18 L16 19 Z",
    )
    val Person = vector(
        "Person",
        "M12 12 A4 4 0 1 0 12 4 A4 4 0 1 0 12 12 Z M4 20 V19 Q4 15 9 15 H15 Q20 15 20 19 V20",
    )
    val Settings = vector(
        "Settings",
        "M4 6 H20 M4 12 H20 M4 18 H20 M8 4 V8 M16 10 V14 M10 16 V20",
    )
    val ArrowBack = vector(
        "ArrowBack",
        "M13 5 L6 12 L13 19 M6 12 H20",
    )
    val KeyboardArrowLeft = vector(
        "KeyboardArrowLeft",
        "M14 5 L7 12 L14 19",
    )
    val Close = vector(
        "Close",
        "M5 5 L19 19 M19 5 L5 19",
    )
    val MoreVert = vector(
        "MoreVert",
        "",
        "M12 3 A1.4 1.4 0 1 0 12 5.8 A1.4 1.4 0 1 0 12 3 Z M12 10.6 A1.4 1.4 0 1 0 12 13.4 A1.4 1.4 0 1 0 12 10.6 Z M12 18.2 A1.4 1.4 0 1 0 12 21 A1.4 1.4 0 1 0 12 18.2 Z",
    )
    val PlayArrow = vector(
        "PlayArrow",
        "",
        "M8 5.5 Q8 4.4 9.1 5 L19 11 Q20.7 12 19 13 L9.1 19 Q8 19.6 8 18.5 Z",
    )
    val Pause = vector(
        "Pause",
        "M9 6 V18 M15 6 V18",
    )
    val SkipNext = vector(
        "SkipNext",
        "M18.5 6 V18",
        "M5.5 6.7 Q5.5 5.2 6.8 6 L15 11.1 Q16.5 12 15 12.9 L6.8 18 Q5.5 18.8 5.5 17.3 Z",
    )
    val SkipPrevious = vector(
        "SkipPrevious",
        "M5.5 6 V18",
        "M18.5 6.7 Q18.5 5.2 17.2 6 L9 11.1 Q7.5 12 9 12.9 L17.2 18 Q18.5 18.8 18.5 17.3 Z",
    )
    val Shuffle = vector(
        "Shuffle",
        "M3 6 H6 Q8 6 10 9 M14 15 Q16 18 18 18 H21 M18 15 L21 18 L18 21 M3 18 H6 Q8 18 10 15 L14 9 Q16 6 18 6 H21 M18 3 L21 6 L18 9",
    )
    val Repeat = vector(
        "Repeat",
        "M4 11 V8 Q4 6 6 6 H19 M16 3 L19 6 L16 9 M20 13 V16 Q20 18 18 18 H5 M8 15 L5 18 L8 21",
    )
    val RepeatActive = vector(
        "RepeatActive",
        "M4 11 V8 Q4 6 6 6 H19 M16 3 L19 6 L16 9 M20 13 V16 Q20 18 18 18 H5 M8 15 L5 18 L8 21",
        "M11 12 A1 1 0 1 0 13 12 A1 1 0 1 0 11 12 Z",
    )
    val RepeatOne = vector(
        "RepeatOne",
        "M4 11 V8 Q4 6 6 6 H19 M16 3 L19 6 L16 9 M20 13 V16 Q20 18 18 18 H5 M8 15 L5 18 L8 21 M10.5 11 L12 9.5 V14.5",
    )
    val FavoriteBorder = vector(
        "FavoriteBorder",
        "M12 20 C10.9 19 3 14.4 3 8.9 C3 4.4 8.6 3.2 12 7 C15.4 3.2 21 4.4 21 8.9 C21 14.4 13.1 19 12 20 Z",
    )
    val Favorite = vector(
        "Favorite",
        "M12 20 C10.9 19 3 14.4 3 8.9 C3 4.4 8.6 3.2 12 7 C15.4 3.2 21 4.4 21 8.9 C21 14.4 13.1 19 12 20 Z",
        "M12 20 C10.9 19 3 14.4 3 8.9 C3 4.4 8.6 3.2 12 7 C15.4 3.2 21 4.4 21 8.9 C21 14.4 13.1 19 12 20 Z",
    )
    private const val BookmarkPath = "M7.5 4 H16.5 Q18.5 4 18.5 6 V20 L12 16 L5.5 20 V6 Q5.5 4 7.5 4 Z"
    val BookmarkBorder = vector("BookmarkBorder", BookmarkPath)
    val Bookmark = vector("Bookmark", BookmarkPath, BookmarkPath)
    val Transcript = vector(
        "Transcript",
        "M7.5 3.5 H16.5 Q19 3.5 19 6 V18 Q19 20.5 16.5 20.5 H7.5 Q5 20.5 5 18 V6 Q5 3.5 7.5 3.5 Z M8.5 8 H15.5 M8.5 12 H15.5 M8.5 16 H13.5",
    )
    val PlaybackSpeed = vector(
        "PlaybackSpeed",
        "M5.5 18 A8.5 8.5 0 1 1 18.5 18 M12 14 L16 10 M9 19 H15",
    )
    val Refresh = vector(
        "Refresh",
        "M19.5 10 A7.7 7.7 0 1 0 19 15.5 M19.5 5.5 V10 H15",
    )
    val SortManual = vector(
        "SortManual",
        "M9 6 H20 M9 12 H20 M9 18 H20",
        "M5 6 A1 1 0 1 0 3 6 A1 1 0 1 0 5 6 Z M5 12 A1 1 0 1 0 3 12 A1 1 0 1 0 5 12 Z M5 18 A1 1 0 1 0 3 18 A1 1 0 1 0 5 18 Z",
    )
    val SortTitle = vector(
        "SortTitle",
        "M4 6 H13 M8.5 6 V18 M6 18 H11 M16 10 H20 M16 14 H20 M16 18 H20",
    )
    val SortAdded = vector(
        "SortAdded",
        "M8 3 V7 M16 3 V7 M4 10 H20 M6 5 H18 Q20 5 20 7 V18 Q20 20 18 20 H6 Q4 20 4 18 V7 Q4 5 6 5 Z M10 15 H14",
    )
    private const val LibraryActionShelf = "M4 5 H7.5 V19 H4 Z M11 5 H14.5 V10 M11 5 V19 H12"
    val LibraryAdd = vector("LibraryAdd", "$LibraryActionShelf M18 13 V20 M14.5 16.5 H21.5")
    val LibraryRemove = vector("LibraryRemove", "$LibraryActionShelf M14.5 16.5 H21.5")
    val MoveUp = vector("MoveUp", "M12 19 V5 M6.5 10.5 L12 5 L17.5 10.5")
    val MoveDown = vector("MoveDown", "M12 5 V19 M6.5 13.5 L12 19 L17.5 13.5")
    val FormatListNumbered = vector(
        "FormatListNumbered",
        "M4 5 H20 M4 12 H16 M4 19 H12 M17 16 L21 19 L17 22 Z",
    )
    val Devices = vector(
        "Devices",
        "M3 16 V5 H17 V6 M3 16 H12 M2 19 H12 M15 8 H21 V20 H15 Z",
    )
    val Volume = vector(
        "Volume",
        "M3 9 H7 L12 5 V19 L7 15 H3 Z M16 9 Q19 12 16 15 M19 6 Q24 12 19 18",
    )
    val Timer = vector(
        "Timer",
        "M12 21 A9 9 0 1 0 12 3 A9 9 0 1 0 12 21 Z M12 7 V12 L15 14",
    )
    val History = vector(
        "History",
        "M3 10 A9 9 0 1 1 4 17 M3 5 V10 H8 M12 7 V12 L15 14",
    )
    val Add = vector(
        "Add",
        "M12 4 V20 M4 12 H20",
    )
    val Check = vector(
        "Check",
        "M4.5 12.1 L9.4 17 L19.5 7",
    )
    val PlaylistAdd = vector(
        "PlaylistAdd",
        "M4 5 H17 M4 11 H17 M4 17 H10 M17 14 V22 M13 18 H21",
    )
    val Groups = vector(
        "Groups",
        "M9 11.6 A3.2 3.2 0 1 0 9 5.2 A3.2 3.2 0 1 0 9 11.6 Z M2.5 19.5 V18.6 Q2.5 14.9 6.8 14.9 H11.2 Q15.5 14.9 15.5 18.6 V19.5 M15.4 9.7 A3.2 3.2 0 1 0 17.9 4.4 M17.4 14.9 Q21.5 15.1 21.5 18.4 V19.5",
    )
    val Qr = vector(
        "Qr",
        "M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M3 15 H9 V21 H3 Z M15 15 H18 V18 M21 15 V21 H16",
    )
    val Stats = vector(
        "Stats",
        "M5 19 V13 M10 19 V8 M15 19 V11 M20 19 V4",
    )
    val Info = vector(
        "Info",
        "M12 21 A9 9 0 1 0 12 3 A9 9 0 1 0 12 21 Z M12 11 V17 M12 7 H12.05",
    )
    val Language = vector(
        "Language",
        "M12 21 A9 9 0 1 0 12 3 A9 9 0 1 0 12 21 Z M3.3 9 H20.7 M3.3 15 H20.7 M12 3 C14.6 5.9 14.6 18.1 12 21 C9.4 18.1 9.4 5.9 12 3 Z",
    )
    val Updates = vector(
        "Updates",
        "M12 21 A9 9 0 1 0 12 3 A9 9 0 1 0 12 21 Z M12 7 V14.5 M8.7 11.2 L12 14.5 L15.3 11.2",
    )
    val Quality = vector(
        "Quality",
        "M5 5 H19 Q21 5 21 7 V17 Q21 19 19 19 H5 Q3 19 3 17 V7 Q3 5 5 5 Z M6.5 9 V15 M6.5 12 H10 M10 9 V15 M15.6 14.6 A2.6 2.6 0 1 0 15.6 9.4 A2.6 2.6 0 1 0 15.6 14.6 Z M17.4 13.8 L18.5 15.1",
    )
    val Share = vector(
        "Share",
        "M8.5 10.1 L15 6.4 M8.5 13.9 L15 17.6 M5 14.5 A2.5 2.5 0 1 0 5 9.5 A2.5 2.5 0 1 0 5 14.5 Z M18.5 7 A2.5 2.5 0 1 0 18.5 2 A2.5 2.5 0 1 0 18.5 7 Z M18.5 22 A2.5 2.5 0 1 0 18.5 17 A2.5 2.5 0 1 0 18.5 22 Z",
    )
    val Trash = vector(
        "Trash",
        "M9 4 H15 M5 6.5 H19 M8.5 6.5 L9.5 19 Q9.6 20 10.6 20 H13.4 Q14.4 20 14.5 19 L15.5 6.5 M10.3 10 V16 M13.7 10 V16",
    )
    val Open = vector(
        "Open",
        "M13.5 5 H19 V10.5 M19 5 L11.5 12.5 M17 13.5 V17.5 Q17 19 15.5 19 H6.5 Q5 19 5 17.5 V8.5 Q5 7 6.5 7 H10.5",
    )
    val Sort = vector(
        "Sort",
        "M4 6 H20 M4 12 H15 M4 18 H10",
    )
    val Filter = vector(
        "Filter",
        "M4 5 H20 L14 12 V18 L10 20 V12 Z",
    )
    val Error = vector(
        "Error",
        "M12 21 A9 9 0 1 0 12 3 A9 9 0 1 0 12 21 Z M12 6 V12 M12 16 H12.05",
    )
    val Album = vector(
        "Album",
        "M12 21 A9 9 0 1 0 12 3 A9 9 0 1 0 12 21 Z M12 14 A2 2 0 1 0 12 10 A2 2 0 1 0 12 14 Z",
    )
    val MusicNote = vector(
        "MusicNote",
        "M11 17 V4 L19 6 V9 L11 7 M11 17 C11 14 4 14 4 17 C4 20 11 21 11 17 Z",
    )
    val AutoAwesome = vector(
        "AutoAwesome",
        "M11 3 Q12 10 19 11 Q12 12 11 19 Q10 12 3 11 Q10 10 11 3 Z M19 4 H22 M20.5 2.5 V5.5",
    )

    private fun vector(name: String, outline: String, filled: String = ""): ImageVector {
        val ink = SolidColor(Color.Black)
        val builder = ImageVector.Builder(
            name, 24.dp, 24.dp, 24f, 24f,
            autoMirror = name in setOf("ArrowBack", "KeyboardArrowLeft"),
        )
        if (filled.isNotEmpty()) {
            builder.addPath(
                PathParser().parsePathString(filled).toNodes(),
                pathFillType = PathFillType.EvenOdd,
                fill = ink,
            )
        }
        if (outline.isNotEmpty()) {
            builder.addPath(
                PathParser().parsePathString(outline).toNodes(),
                stroke = ink,
                strokeLineWidth = if (name == "Pause") 3.2f else 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

@Composable
internal fun EmberAnimatedIcon(
    icon: ImageVector,
    contentDescription: String?,
    source: InteractionSource,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = if (pressed) tween(100) else spring(dampingRatio = 0.8f),
        label = "Ember icon press",
    )
    Crossfade(
        targetState = icon,
        modifier = modifier.size(24.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        animationSpec = tween(EmberMotion.Fast),
        label = "Ember icon state",
    ) { vector ->
        Icon(vector, null, modifier = Modifier.fillMaxSize(), tint = tint)
    }
}

@Composable
internal fun EmberIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    val source = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, enabled = enabled, interactionSource = source) {
        EmberAnimatedIcon(icon, description, source, tint = if (enabled) tint else LocalContentColor.current)
    }
}
