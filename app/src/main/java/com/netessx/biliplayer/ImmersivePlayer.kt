package com.netessx.biliplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.net.HttpURLConnection
import java.net.URL

/**
 * 实验性沉浸式播放页。
 *
 * 刻意不使用任何 Material 组件与主题：只用 foundation 的 Box/Column/LazyColumn/
 * BasicText/Canvas 自己画。视觉规则也自成一套：
 *   背景 = 封面模糊 + 冷色暗角（"亚克力"）
 *   顶部 = 视频（由外层 WebView 面板占位）
 *   下方 = 标题 → 滚动歌词 → 底部悬浮控制条
 */
private val Ink = Color(0xFF07080C)
private val Accent = Color(0xFFFB7299)
private val TextPrimary = Color(0xFFF2F4FA)
private val TextMuted = Color(0x99F2F4FA)
private val TextDim = Color(0x59F2F4FA)

/**
 * 歌词滚动的动画曲线：偏低阻尼的弹簧，到位时轻微回弹，做出「Q 弹」的手感。
 * 刻意不用 tween —— 线性/缓动都没有过冲，看起来是"滑过去"而不是"弹到位"。
 */
private val LyricScrollSpec = spring<Float>(
    dampingRatio = 0.62f,
    stiffness = 170f
)

@Composable
fun ImmersivePlayerScreen(
    uiState: PlayerUiState,
    lyricsEntry: LyricsEntry?,
    onOpenLyrics: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    var showTimerPanel by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var prefIndex by remember { mutableStateOf<Int?>(null) }
    val lines = remember(lyricsEntry?.bvid, lyricsEntry?.synced, lyricsEntry?.plain) {
        lyricsEntry?.lines().orEmpty()
    }
    val synced = lyricsEntry?.hasSyncedLines == true
    val pos = uiState.currentTime - (lyricsEntry?.offsetMs ?: 0L) / 1000.0
    val active = if (synced) activeLineIndex(lines, pos) else -1

    Box(Modifier.fillMaxSize().background(Ink)) {
        AcrylicBackdrop(uiState.currentCover)

        Column(
            Modifier
                .fillMaxSize()
                // 背景铺满整屏，内容避让状态栏/手势条
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ---------- 顶部工具行 ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostLabel("歌词", onOpenLyrics)
                Spacer(Modifier.weight(1f))
                GhostLabel("退出沉浸", onExit)
                GhostLabel("返回列表", onBack)
            }

            Spacer(Modifier.height(22.dp))

            // ---------- 标题 ----------
            BasicText(
                uiState.currentTitle.ifBlank { "加载中…" },
                style = TextStyle(
                    color = TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    lineHeight = 32.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                uiState.currentBvid,
                style = TextStyle(color = TextDim, fontSize = 11.sp, letterSpacing = 1.2.sp)
            )

            Spacer(Modifier.height(18.dp))

            // ---------- 滚动歌词 ----------
            if (lines.isEmpty()) {
                // 没有歌词时中间放封面，比一句空提示好看
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (uiState.currentCover.isNotBlank()) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.66f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color(0x1AFFFFFF))
                            ) {
                                CoverBitmap(
                                    url = uiState.currentCover,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        BasicText(
                            "暂无歌词 · 点击顶部「歌词」搜索",
                            style = TextStyle(color = TextMuted, fontSize = 13.sp),
                            modifier = Modifier.clickable { onOpenLyrics() }
                        )
                    }
                }
            } else {
                // 刻意不用 LazyColumn：自己记录每行在内容里的绝对坐标，
                // 直接算出目标滚动值，位置是确定的、不会漂。
                // 行高固定（高亮只改 scale / 颜色，不改字号），所以坐标也稳定。
                val scrollState = rememberScrollState()
                var viewportH by remember { mutableStateOf(0) }
                val rowTops = remember { mutableStateMapOf<Int, Int>() }
                val rowHeights = remember { mutableStateMapOf<Int, Int>() }
                val density = LocalDensity.current
                // 上下各留半个视口空白，首尾行也能停到目标位置
                val vPad = remember(viewportH, density) {
                    with(density) {
                        // 夹在视口高度内兜底：一旦量错（历史上曾量成内容高度），
                        // 这里会算出几十万像素的 Spacer，把 Constraints 直接撑爆
                        (((viewportH - 64.dp.toPx()) / 2f)
                            .coerceIn(0f, viewportH.coerceAtLeast(0).toFloat())).toDp()
                    }
                }

                // 换歌（bvid 变）也要重新定位，否则会停在上一首的滚动位置
                LaunchedEffect(active, viewportH, lines.size, lyricsEntry?.bvid) {
                    if (viewportH <= 0) return@LaunchedEffect
                    if (active < 0) {
                        // 前奏还没到第一句：回到顶部（顶部留白让首行正好居中）
                        if (scrollState.value != 0) {
                            withFrameNanos { }
                            scrollState.scrollTo(0)
                        }
                        return@LaunchedEffect
                    }
                    repeat(3) {
                        withFrameNanos { }
                        val top = rowTops[active] ?: return@repeat
                        val h = rowHeights[active] ?: 0
                        // 让这一行的中心落在视口 42% 的高度上
                        val target = (top + h / 2f - viewportH * 0.42f)
                            .toInt()
                            .coerceIn(0, scrollState.maxValue)
                        val delta = target - scrollState.value
                        if (abs(delta) <= 1) return@LaunchedEffect
                        // animateScrollBy 接受 AnimationSpec，弹簧曲线直接生效
                        scrollState.animateScrollBy(delta.toFloat(), LyricScrollSpec)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // onSizeChanged 必须在 verticalScroll 外层：放在里面量到的是
                        // 内容总高度（可能几十万像素），不是视口高度
                        .onSizeChanged { viewportH = it.height }
                        .verticalScroll(scrollState)
                ) {
                    Spacer(Modifier.height(vPad))
                    lines.forEachIndexed { i, line ->
                        val isActive = i == active
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    rowTops[i] = coords.positionInParent().y.toInt()
                                    rowHeights[i] = coords.size.height
                                }
                                // 同步歌词才允许点行跳转
                                .then(
                                    if (synced) Modifier.clickable {
                                        PlayerController.seek(
                                            (line.timeMs + (lyricsEntry?.offsetMs ?: 0L)) / 1000.0
                                        )
                                    } else Modifier
                                )
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // 高亮强度用弹簧从 0 → 1，字号/字重保持不变：
                            // 只靠 scale + 颜色变化，不动布局，所以不会有「突然变大」的跳变
                            val emphasis by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0f,
                                animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f),
                                label = "lyricEmphasis"
                            )
                            BasicText(
                                line.text.ifBlank { "♪" },
                                style = TextStyle(
                                    color = lerp(TextDim, TextPrimary, emphasis),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 24.sp,
                                    letterSpacing = 0.4.sp * emphasis,
                                    textAlign = TextAlign.Center
                                ),
                                // 宽度只占 74%：高亮放大 1.35 倍后正好铺满整行，
                                // 否则长句放大后会溢出到屏幕外被裁掉
                                modifier = Modifier
                                    .fillMaxWidth(0.74f)
                                    .graphicsLayer {
                                        val s = 1f + 0.35f * emphasis
                                        scaleX = s
                                        scaleY = s
                                    }
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    Spacer(Modifier.height(vPad))
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 底部悬浮控制条 ----------
            FloatingControls(
                uiState = uiState,
                onTimerClick = { showTimerPanel = true },
                onPlaylistClick = { showPlaylist = true }
            )
        }

        // ---------- 定时面板：遮罩淡入 + 卡片缩放弹出 ----------
        AnimatedVisibility(
            visible = showTimerPanel,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .clickable { showTimerPanel = false }
            )
        }
        AnimatedVisibility(
            visible = showTimerPanel,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.93f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            TimerPanel(
                currentAction = uiState.sleepAction,
                onPick = { sec, action ->
                    PlayerController.setSleepTimer(sec, action)
                    showTimerPanel = false
                }
            )
        }

        // ---------- 播放列表抽屉：遮罩淡入 + 自下弹出 ----------
        AnimatedVisibility(
            visible = showPlaylist,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable { showPlaylist = false; prefIndex = null }
            )
        }
        AnimatedVisibility(
            visible = showPlaylist,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f)
            ),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlaylistSheet(
                tracks = uiState.playlist,
                currentIndex = uiState.currentIndex,
                onPlay = { PlayerController.playIndex(it) },
                onLongPress = { prefIndex = it },
                onClose = { showPlaylist = false; prefIndex = null }
            )
        }

        // 单曲偏好浮层（覆盖在抽屉之上）
        prefIndex?.let { index ->
            uiState.playlist.getOrNull(index)?.let { track ->
                PrefSheet(index = index, track = track, onDismiss = { prefIndex = null })
            }
        }
    }
}

/** 从底部弹出的播放列表面板（只有内容，遮罩与滑入动画由外层控制）。 */
@Composable
private fun PlaylistSheet(
    tracks: List<BiliTrack>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Color(0xF2131620))
            .clickable(enabled = false) {}
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "播放列表 (${tracks.size})",
                style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.weight(1f))
            GhostLabel("关闭", onClose)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks) { i, t ->
                val current = i == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPlay(i) },
                            onLongClick = { onLongPress(i) }
                        )
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 封面缩略图
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x1AFFFFFF))
                    ) {
                        if (t.cover.isNotBlank()) {
                            CoverBitmap(
                                url = t.cover,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        "%02d".format(i + 1),
                        style = TextStyle(color = TextDim, fontSize = 11.sp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        t.title.ifBlank { t.bvid },
                        style = TextStyle(
                            color = if (current) Accent else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current) {
                        Spacer(Modifier.width(8.dp))
                        BasicText("正在播放", style = TextStyle(color = Accent, fontSize = 10.sp))
                    }
                }
            }
        }
    }
}

/** 单曲偏好编辑浮层（分P / 开始秒 / 结束秒），不依赖 Material 输入框。 */
@Composable
private fun PrefSheet(index: Int, track: BiliTrack, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initial = remember(index, track.bvid) { Preferences.preference(context, track.bvid) }
    var pText by remember(index, track.bvid) { mutableStateOf(initial.p?.toString() ?: "") }
    var beginText by remember(index, track.bvid) { mutableStateOf(initial.beginTime?.toString() ?: "") }
    var endText by remember(index, track.bvid) { mutableStateOf(initial.endTime?.toString() ?: "") }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF21A1D26))
                .clickable(enabled = false) {}
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BasicText(
                "单曲偏好",
                style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            )
            BasicText(
                track.title.ifBlank { track.bvid },
                style = TextStyle(color = TextMuted, fontSize = 12.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            BareField("分 P（留空为第 1P）", pText) { pText = it.filter(Char::isDigit) }
            BareField("开始秒（跳过片头）", beginText) { beginText = it.filter(Char::isDigit) }
            BareField("结束秒（自动切歌）", endText) { endText = it.filter(Char::isDigit) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("保存", highlighted = true) {
                    PlayerController.setTrackPreference(
                        index,
                        TrackPreference(
                            p = pText.toIntOrNull(),
                            beginTime = beginText.toIntOrNull(),
                            endTime = endText.toIntOrNull()
                        )
                    )
                    onDismiss()
                }
                Pill("清除") {
                    PlayerController.setTrackPreference(index, TrackPreference())
                    onDismiss()
                }
                Pill("取消") { onDismiss() }
            }
        }
    }
}

/** 无 Material 的输入框：底色块 + BasicTextField。 */
@Composable
private fun BareField(placeholder: String, value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
        cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x1FFFFFFF))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = TextStyle(color = TextDim, fontSize = 13.sp))
                }
                inner()
            }
        }
    )
}

/** 定时关闭浮层：时长 + 到点暂停/退出，全部自绘。 */
@Composable
private fun TimerPanel(
    currentAction: SleepAction,
    onPick: (Long, SleepAction) -> Unit,
) {
    var action by remember { mutableStateOf(currentAction) }
    var customText by remember { mutableStateOf("") }

    Column(
        Modifier
            .padding(horizontal = 28.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF21A1D26))
            .clickable(enabled = false) {}
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BasicText(
            "定时关闭",
            style = TextStyle(color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        )

        // 到点做什么
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("到点暂停", highlighted = action == SleepAction.PAUSE) { action = SleepAction.PAUSE }
            Pill("到点退出", highlighted = action == SleepAction.QUIT) { action = SleepAction.QUIT }
        }

        // 预设时长
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("关闭") { onPick(0L, action) }
            Pill("5 分钟") { onPick(300L, action) }
            Pill("10 分钟") { onPick(600L, action) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("15 分钟") { onPick(900L, action) }
            Pill("30 分钟") { onPick(1800L, action) }
            Pill("60 分钟") { onPick(3600L, action) }
        }

        // 自定义分钟数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = customText,
                onValueChange = { customText = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1FFFFFFF))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (customText.isEmpty()) {
                            BasicText(
                                "自定义分钟数",
                                style = TextStyle(color = TextDim, fontSize = 13.sp)
                            )
                        }
                        inner()
                    }
                }
            )
            Pill("设置", highlighted = customText.isNotBlank()) {
                val minutes = customText.toLongOrNull()
                if (minutes != null && minutes > 0) onPick(minutes * 60, action)
            }
        }

        BasicText(
            "点浮层外取消",
            style = TextStyle(color = TextDim, fontSize = 11.sp),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

/** 只有文字、没有 Material 背景的小按钮。 */
@Composable
private fun GhostLabel(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = TextMuted, fontSize = 12.sp, letterSpacing = 0.8.sp),
        modifier = Modifier.clickable { onClick() }
    )
}

/**
 * 亚克力背景：封面 → 高斯模糊 → 冷色暗角 + 氛围渐变。
 * 没有封面时退化为深色渐变，不会露出纯黑。
 */
@Composable
private fun AcrylicBackdrop(cover: String) {
    Box(Modifier.fillMaxSize()) {
        CoverBitmap(url = cover, modifier = Modifier.fillMaxSize().blur(64.dp))
        // 亚克力色彩层：只压暗到能保证文字对比，别把封面盖死（之前 90%+ 不透明导致整屏发黑）
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0x8C0A0D16),
                        Color(0x9907080C),
                        Color(0xB307080C)
                    )
                )
            )
        )
        // 一点粉色氛围光，避免纯灰
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0x33FB7299), Color(0x00000000)),
                    center = Offset(0f, 0f),
                    radius = 900f
                )
            )
        )
    }
}

/** 加载封面并绘制；失败/为空时保持透明，让渐变层兜底。 */
@Composable
private fun CoverBitmap(url: String, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.setRequestProperty("User-Agent", HeadlessBrowser.DESKTOP_USER_AGENT)
                val bmp: Bitmap? = BitmapFactory.decodeStream(conn.inputStream)
                bmp?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

/** 底部浮动控制条：播放模式 / 定时 / 进度 / 上一首 / 播放暂停 / 下一首，全部自绘。 */
@Composable
private fun FloatingControls(
    uiState: PlayerUiState,
    onTimerClick: () -> Unit,
    onPlaylistClick: () -> Unit,
) {
    val duration = if (uiState.duration > 0) uiState.duration else 1.0
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0.0) }
    val shown = if (scrubbing) scrubValue else uiState.currentTime

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0x1FFFFFFF))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // 进度时间：一左一右放在进度条上方，中间是定时
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(formatClock(shown), style = TextStyle(color = TextDim, fontSize = 11.sp))
            Spacer(Modifier.weight(1f))
            Pill(
                uiState.sleepRemaining?.let { formatClock(it.toDouble()) } ?: "定时",
                highlighted = uiState.sleepRemaining != null,
                onClick = onTimerClick
            )
            Spacer(Modifier.weight(1f))
            BasicText(formatClock(uiState.duration), style = TextStyle(color = TextDim, fontSize = 11.sp))
        }

        // 进度条：拖动即跳转
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubbing = true },
                        onDragEnd = {
                            PlayerController.seek(scrubValue)
                            scrubbing = false
                        },
                        onDragCancel = { scrubbing = false }
                    ) { change, _ ->
                        scrubValue = (change.position.x / size.width).coerceIn(0f, 1f) * duration
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                drawRoundRect(color = Color(0x33FFFFFF), cornerRadius = CornerRadius(3f))
                val frac = (shown / duration).toFloat().coerceIn(0f, 1f)
                if (frac > 0f) {
                    drawRoundRect(
                        color = Accent,
                        size = Size(size.width * frac, size.height),
                        cornerRadius = CornerRadius(3f)
                    )
                }
            }
        }
        // 传输区：模式图标 | 上一首 | 播放暂停 | 下一首 | 播放列表
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeButton(uiState.playMode) {
                val next = when (uiState.playMode) {
                    PlayMode.SEQUENTIAL -> PlayMode.SHUFFLE
                    PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
                    PlayMode.REPEAT_ONE -> PlayMode.SEQUENTIAL
                }
                PlayerController.setPlayMode(next)
            }
            TransportButton(kind = Transport.PREV, size = 44.dp) { PlayerController.prev() }
            TransportButton(
                kind = if (uiState.isPlaying) Transport.PAUSE else Transport.PLAY,
                size = 64.dp
            ) { PlayerController.playPause() }
            TransportButton(kind = Transport.NEXT, size = 44.dp) { PlayerController.next() }
            PlaylistButton { onPlaylistClick() }
        }
    }
}

/** 播放模式图标：顺序 / 随机 / 单曲循环，全部 Canvas 绘制。 */
@Composable
private fun ModeButton(mode: PlayMode, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            when (mode) {
                PlayMode.SEQUENTIAL -> {
                    // 向右箭头
                    drawLine(TextMuted, Offset(0f, h / 2f), Offset(w * 0.78f, h / 2f), stroke)
                    drawPath(Path().apply {
                        moveTo(w * 0.55f, h * 0.12f)
                        lineTo(w, h / 2f)
                        lineTo(w * 0.55f, h * 0.88f)
                        close()
                    }, TextMuted)
                }
                PlayMode.SHUFFLE -> {
                    // 两条交叉线 + 箭头
                    drawLine(TextMuted, Offset(0f, h * 0.2f), Offset(w, h * 0.8f), stroke)
                    drawLine(TextMuted, Offset(0f, h * 0.8f), Offset(w, h * 0.2f), stroke)
                    drawPath(Path().apply {
                        moveTo(w * 0.62f, h * 0.02f)
                        lineTo(w, h * 0.2f)
                        lineTo(w * 0.68f, h * 0.36f)
                        close()
                    }, TextMuted)
                }
                PlayMode.REPEAT_ONE -> {
                    // 圆环 + 中间的「1」用一个小方块近似
                    drawArc(
                        color = TextMuted,
                        startAngle = 40f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = Offset(0f, 0f),
                        size = Size(w, h),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                    )
                    drawPath(Path().apply {
                        moveTo(w * 0.72f, h * 0.04f)
                        lineTo(w * 0.98f, h * 0.28f)
                        lineTo(w * 0.66f, h * 0.34f)
                        close()
                    }, TextMuted)
                    drawRoundRect(
                        color = Accent,
                        topLeft = Offset(w * 0.44f, h * 0.34f),
                        size = Size(w * 0.12f, h * 0.32f),
                        cornerRadius = CornerRadius(1f)
                    )
                }
            }
        }
    }
}

/** 播放列表按钮：三条横线 + 圆点，自绘。 */
@Composable
private fun PlaylistButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            listOf(0.2f, 0.5f, 0.8f).forEach { fy ->
                drawLine(TextMuted, Offset(w * 0.16f, h * fy), Offset(w * 0.9f, h * fy), stroke)
                drawCircle(Accent, radius = stroke * 1.1f, center = Offset(0f, h * fy))
            }
        }
    }
}

/** 控制条上的小胶囊按钮（自绘，不用 Material Chip）。 */
@Composable
private fun Pill(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(
            color = if (highlighted) Ink else TextMuted,
            fontSize = 11.sp,
            letterSpacing = 0.4.sp
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) Accent else Color(0x1FFFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}


private enum class Transport { PREV, PLAY, PAUSE, NEXT }

/** 用 Canvas 画播放控制图标，避免引入 Material 图标。 */
@Composable
private fun TransportButton(kind: Transport, size: Dp, onClick: () -> Unit) {
    val filled = kind == Transport.PLAY || kind == Transport.PAUSE
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) Color(0x26FFFFFF) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.42f)) {
            val w = this.size.width
            val h = this.size.height
            when (kind) {
                Transport.PLAY -> drawPath(
                    Path().apply {
                        moveTo(w * 0.18f, 0f)
                        lineTo(w * 0.95f, h / 2f)
                        lineTo(w * 0.18f, h)
                        close()
                    },
                    TextPrimary
                )

                Transport.PAUSE -> {
                    drawRoundRect(
                        color = TextPrimary,
                        topLeft = Offset(w * 0.15f, 0f),
                        size = Size(w * 0.26f, h),
                        cornerRadius = CornerRadius(3f)
                    )
                    drawRoundRect(
                        color = TextPrimary,
                        topLeft = Offset(w * 0.59f, 0f),
                        size = Size(w * 0.26f, h),
                        cornerRadius = CornerRadius(3f)
                    )
                }

                Transport.PREV, Transport.NEXT -> {
                    val leftward = kind == Transport.PREV
                    val x0 = if (leftward) w else 0f
                    val x1 = if (leftward) 0f else w
                    val midX = if (leftward) w * 0.55f else w * 0.45f
                    drawPath(
                        Path().apply {
                            moveTo(x0, 0f)
                            lineTo(midX, h / 2f)
                            lineTo(x0, h)
                            close()
                        },
                        TextPrimary
                    )
                    drawPath(
                        Path().apply {
                            moveTo(midX, 0f)
                            lineTo(x1, h / 2f)
                            lineTo(midX, h)
                            close()
                        },
                        TextPrimary
                    )
                    val barX = if (leftward) w * 0.02f else w * 0.86f
                    drawRoundRect(
                        color = TextPrimary,
                        topLeft = Offset(barX, 0f),
                        size = Size(w * 0.12f, h),
                        cornerRadius = CornerRadius(2f)
                    )
                }
            }
        }
    }
}

private fun formatClock(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN()) return "00:00"
    val total = seconds.toLong()
    return "%02d:%02d".format(total / 60, total % 60)
}
