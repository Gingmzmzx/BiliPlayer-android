package com.netessx.biliplayer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 歌单曲目管理页：调整顺序、删除歌曲。 */
@Composable
internal fun PlaylistTracksScreen(playlistId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val playlists by PlaylistStore.playlists.collectAsState()
    val pl = playlists.firstOrNull { it.id == playlistId }
    val hideBottomNav = LocalHideBottomNav.current
    DisposableEffect(Unit) {
        hideBottomNav.value = true
        onDispose { hideBottomNav.value = false }
    }
    BackHandler { onBack() }

    LiquidGlassScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImmersiveChip("← 返回", onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(
                        pl?.name ?: "歌单",
                        style = TextStyle(
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BasicText(
                        "${pl?.tracks?.size ?: 0} 首",
                        style = TextStyle(color = TextMuted, fontSize = 10.sp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            val tracks = pl?.tracks.orEmpty()
            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "歌单里还没有歌曲",
                        style = TextStyle(color = TextMuted, fontSize = 13.sp)
                    )
                }
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(tracks, key = { _, t -> t.bvid }) { index, track ->
                    var dragOffset by remember(track.bvid) { mutableFloatStateOf(0f) }
                    var dragging by remember(track.bvid) { mutableStateOf(false) }
                    var rowHeightPx by remember(track.bvid) { mutableFloatStateOf(64f) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = dragOffset }
                            .onSizeChanged {
                                rowHeightPx = it.height.toFloat().coerceAtLeast(1f)
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .liquidGlass(
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 18.dp,
                                elevation = 3.dp
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .liquidGlass(
                                    shape = RoundedCornerShape(10.dp),
                                    blurRadius = 10.dp,
                                    elevation = 0.dp
                                )
                        ) {
                            if (track.cover.isNotBlank()) {
                                CoverBitmap(track.cover, Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                track.title.ifBlank { track.bvid },
                                style = TextStyle(color = TextPrimary, fontSize = 13.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            BasicText(
                                track.bvid,
                                style = TextStyle(color = TextDim, fontSize = 9.sp)
                            )
                        }
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(GlassSoft)
                                .pointerInput(track.bvid, index) {
                                    detectVerticalDragGestures(
                                        onDragStart = { dragging = true },
                                        onDragEnd = {
                                            dragging = false
                                            val steps = (dragOffset / rowHeightPx).roundToInt()
                                            val target = (index + steps)
                                                .coerceIn(0, tracks.lastIndex)
                                            if (target != index) {
                                                PlaylistStore.moveTrack(
                                                    context,
                                                    playlistId,
                                                    index,
                                                    target
                                                )
                                            }
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragging = false
                                            dragOffset = 0f
                                        }
                                    ) { change, delta ->
                                        change.consume()
                                        dragOffset += delta
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val handleColor = TextMuted
                            Canvas(Modifier.size(14.dp)) {
                                val w = size.width
                                val h = size.height
                                val stroke = 1.6.dp.toPx()
                                listOf(0.25f, 0.5f, 0.75f).forEach { fy ->
                                    drawLine(
                                        handleColor,
                                        Offset(0f, h * fy),
                                        Offset(w, h * fy),
                                        stroke
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        LiquidCircleButton(
                            size = 32.dp,
                            onClick = { PlaylistStore.removeTrackAt(context, playlistId, index) }
                        ) {
                            val color = Color(0xFFFF6B7A)
                            Canvas(Modifier.size(12.dp)) {
                                val w = size.width
                                val h = size.height
                                val stroke = 1.8.dp.toPx()
                                drawLine(color, Offset(0f, 0f), Offset(w, h), stroke)
                                drawLine(color, Offset(w, 0f), Offset(0f, h), stroke)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun TrackMoveButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    LiquidCircleButton(
        size = 32.dp,
        onClick = { if (enabled) onClick() }
    ) {
        val color = if (enabled) TextPrimary else TextDim
        Canvas(Modifier.size(12.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.8.dp.toPx()
            drawLine(color, Offset(w / 2f, h * 0.22f), Offset(w / 2f, h * 0.80f), stroke)
            if (up) {
                drawLine(color, Offset(w * 0.22f, h * 0.46f), Offset(w / 2f, h * 0.22f), stroke)
                drawLine(color, Offset(w / 2f, h * 0.22f), Offset(w * 0.78f, h * 0.46f), stroke)
            } else {
                drawLine(color, Offset(w * 0.22f, h * 0.56f), Offset(w / 2f, h * 0.80f), stroke)
                drawLine(color, Offset(w / 2f, h * 0.80f), Offset(w * 0.78f, h * 0.56f), stroke)
            }
        }
    }
}

/** 选择歌单 / 新建歌单的底部抽屉。 */
@Composable
internal fun AddToPlaylistSheet(
    track: BiliTrack,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playlists by PlaylistStore.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }

    GlassBottomSheet(
        onDismiss = onDismiss,
        initialFraction = 0.72f
    ) { close ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "添加到歌单",
                        style = TextStyle(
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    BasicText(
                        track.title.ifBlank { track.bvid },
                        style = TextStyle(color = TextMuted, fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Pill("关闭", onClick = close)
            }

            Spacer(Modifier.height(12.dp))

            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    BasicText(
                        "还没有歌单，先在下面新建一个",
                        style = TextStyle(color = TextMuted, fontSize = 12.sp)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(playlists, key = { _, p -> p.id }) { _, pl ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .liquidPress(onClick = {
                                    PlayerController.addTrackToPlaylist(pl.id, track)
                                    close()
                                })
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    pl.name,
                                    style = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                BasicText(
                                    "${pl.tracks.size} 首",
                                    style = TextStyle(color = TextMuted, fontSize = 10.sp)
                                )
                            }
                            BasicText("添加", style = TextStyle(color = Accent, fontSize = 11.sp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            BasicText(
                "新建歌单",
                style = TextStyle(color = TextMuted, fontSize = 10.sp, letterSpacing = 1.2.sp)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
                    modifier = Modifier
                        .weight(1f)
                        .liquidGlass(
                            shape = RoundedCornerShape(12.dp),
                            blurRadius = 14.dp,
                            elevation = 0.dp
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (newName.isEmpty()) {
                                BasicText(
                                    "歌单名称",
                                    style = TextStyle(color = TextDim, fontSize = 12.sp)
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Pill(
                    "新建并添加",
                    highlighted = newName.isNotBlank(),
                    enabled = newName.isNotBlank(),
                    onClick = {
                        PlaylistStore.createManual(context, newName, listOf(track))
                        Toast.makeText(context, "已新建并添加", Toast.LENGTH_SHORT).show()
                        close()
                    }
                )
            }
        }
    }
}
