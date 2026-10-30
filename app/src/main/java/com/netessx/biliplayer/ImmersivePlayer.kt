package com.netessx.biliplayer

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
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
    onCollapse: () -> Unit,
    haze: HazeState? = null,
) {
    var showTimerPanel by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var prefIndex by remember { mutableStateOf<Int?>(null) }
    val lines = remember(lyricsEntry?.bvid, lyricsEntry?.synced, lyricsEntry?.plain) {
        lyricsEntry?.lines().orEmpty()
    }
    val synced = lyricsEntry?.hasSyncedLines == true
    val pos = uiState.currentTime - (lyricsEntry?.offsetMs ?: 0L) / 1000.0
    val active = if (synced) activeLineIndex(lines, pos) else -1
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 返回键优先关闭当前浮层，而不是直接退出播放器
    BackHandler(
        enabled = showTimerPanel || showPlaylist || showVolume || prefIndex != null
    ) {
        when {
            prefIndex != null -> prefIndex = null
            showVolume -> showVolume = false
            showPlaylist -> showPlaylist = false
            showTimerPanel -> showTimerPanel = false
        }
    }

    // 从迷你条展开、收回迷你条都用同一套上下滑动动画
    var visible by remember { mutableStateOf(false) }
    var collapsing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val collapse: () -> Unit = {
        if (!collapsing) {
            collapsing = true
            visible = false
            scope.launch {
                delay(200)
                onCollapse()
            }
        }
    }
    LaunchedEffect(Unit) { visible = true }

    LiquidGlassScaffold(cover = uiState.currentCover, haze = haze) {
        // 背景始终在，避免播放页入场前闪一帧白屏；只对内容做展开动画
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(170)) +
                slideInVertically(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 360f)
                ) { it / 4 } +
                scaleIn(
                    initialScale = 0.94f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                ),
            exit = fadeOut(tween(150)) +
                slideOutVertically(tween(200)) { it / 4 } +
                scaleOut(
                    targetScale = 0.94f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                )
        ) {
        Box(Modifier.fillMaxSize()) {

        Column(
            Modifier
                .fillMaxSize()
                // 背景铺满整屏，内容避让状态栏/手势条
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (landscape) {
                // 横屏：左边封面+标题+控制，右边歌词
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    Column(Modifier.weight(0.42f).fillMaxHeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleBackButton(collapse)
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier
                                    .size(88.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(GlassSoft)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                            ) {
                                if (uiState.currentCover.isNotBlank()) {
                                    CoverBitmap(uiState.currentCover, Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    uiState.currentTitle.ifBlank { "加载中…" },
                                    style = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 26.sp
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                BasicText(
                                    uiState.currentBvid,
                                    style = TextStyle(color = TextDim, fontSize = 10.sp, letterSpacing = 1.1.sp)
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        FloatingControls(
                            uiState = uiState,
                            onLyricsClick = onOpenLyrics,
                            onTimerClick = { showTimerPanel = true },
                            onPlaylistClick = { showPlaylist = true },
                            onVolumeClick = { showVolume = true }
                        )
                    }
                    LyricsPane(
                        lines = lines,
                        synced = synced,
                        active = active,
                        bvid = lyricsEntry?.bvid.orEmpty(),
                        offsetMs = lyricsEntry?.offsetMs ?: 0L,
                        coverUrl = uiState.currentCover,
                        showCoverWhenEmpty = false,
                        onOpenLyrics = onOpenLyrics,
                        modifier = Modifier.weight(0.58f).fillMaxHeight()
                    )
                }
            } else {
                // 竖屏：标题（右侧菜单按钮）→ 歌词 → 底部悬浮控制条
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleBackButton(collapse)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
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
                    }
                }
                Spacer(Modifier.height(18.dp))

                LyricsPane(
                    lines = lines,
                    synced = synced,
                    active = active,
                    bvid = lyricsEntry?.bvid.orEmpty(),
                    offsetMs = lyricsEntry?.offsetMs ?: 0L,
                    coverUrl = uiState.currentCover,
                    showCoverWhenEmpty = true,
                    onOpenLyrics = onOpenLyrics,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))
                FloatingControls(
                    uiState = uiState,
                    onLyricsClick = onOpenLyrics,
                    onTimerClick = { showTimerPanel = true },
                    onPlaylistClick = { showPlaylist = true },
                    onVolumeClick = { showVolume = true }
                )
            }
        }

        // ---------- 定时面板 ----------
        if (showTimerPanel) {
            GlassDialog(onDismiss = { showTimerPanel = false }) { close ->
                TimerPanel(
                    currentAction = uiState.sleepAction,
                    onPick = { sec, action ->
                        PlayerController.setSleepTimer(sec, action)
                        close()
                    }
                )
            }
        }

        // ---------- 播放列表抽屉：可拖动高度 ----------
        if (showPlaylist) {
            GlassBottomSheet(
                onDismiss = { showPlaylist = false; prefIndex = null },
                initialFraction = 0.64f
            ) { close ->
                PlaylistSheet(
                    tracks = uiState.playlist,
                    currentIndex = uiState.currentIndex,
                    onPlay = { PlayerController.playIndex(it) },
                    onLongPress = { prefIndex = it },
                    onClose = close
                )
            }
        }

        // ---------- 音量 ----------
        if (showVolume) {
            GlassSidePanel(
                onDismiss = { showVolume = false },
                width = 96.dp,
                height = 340.dp
            ) {
                VolumePanel(volume = uiState.volume) { PlayerController.setVolume(it) }
            }
        }

        // 单曲偏好浮层（覆盖在抽屉之上）
        prefIndex?.let { index ->
            uiState.playlist.getOrNull(index)?.let { track ->
                GlassDialog(onDismiss = { prefIndex = null }) { close ->
                    PrefSheet(
                        index = index,
                        track = track,
                        onDismiss = close
                    )
                }
            }
        }
        }
    }
    }
}

/** 左上角圆形返回按钮：收起播放页，回到带底部导航的主界面。 */
@Composable
private fun CircleBackButton(onClick: () -> Unit) {
    LiquidCircleButton(size = 44.dp, onClick = onClick) {
        val iconColor = TextPrimary
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 2.dp.toPx()
            drawLine(
                iconColor,
                Offset(w * 0.72f, h * 0.15f),
                Offset(w * 0.30f, h * 0.5f),
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                iconColor,
                Offset(w * 0.30f, h * 0.5f),
                Offset(w * 0.72f, h * 0.85f),
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

/** 右上角的圆形菜单按钮：三条横线，自绘。 */
@Composable
private fun CircleMenuButton(onClick: () -> Unit) {
    LiquidCircleButton(size = 48.dp, onClick = onClick) {
        val iconColor = TextPrimary
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 2.dp.toPx()
            listOf(0.2f, 0.5f, 0.8f).forEach { fy ->
                drawLine(iconColor, Offset(w * 0.1f, h * fy), Offset(w * 0.9f, h * fy), stroke)
            }
        }
    }
}

private enum class MenuGlyph { STATS, SETTINGS, ABOUT, EXIT, LIST }

/** 右侧滑出的菜单：统计 / 设置 / 关于 /（退出沉浸）/ 返回列表。 */
@Composable
private fun ImmersiveMenuPanel(
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onExit: (() -> Unit)?,
    onBackToList: () -> Unit,
) {
    // 面板内容整体可滚动：横屏高度不足时也能滑到「返回列表」
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        BasicText(
            "BiliPlayer",
            style = TextStyle(color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 22.dp)
        )
        BasicText(
            "v${BuildConfig.VERSION_NAME}",
            style = TextStyle(color = TextDim, fontSize = 10.sp),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(22.dp))

        MenuRow(MenuGlyph.STATS, "统计", onStats)
        MenuRow(MenuGlyph.SETTINGS, "设置", onSettings)
        MenuRow(MenuGlyph.ABOUT, "关于", onAbout)
        if (onExit != null) MenuRow(MenuGlyph.EXIT, "退出沉浸", onExit)

        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 6.dp)
                .height(1.dp)
                .background(GlassBorder)
        )
        MenuRow(MenuGlyph.LIST, "返回列表", onBackToList)
    }
}

/** 菜单项：圆角图标块 + 文字 + 右箭头。 */
@Composable
private fun MenuRow(glyph: MenuGlyph, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .liquidPress(onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidIconTile {
            Canvas(Modifier.size(20.dp)) { drawMenuGlyph(glyph) }
        }
        Spacer(Modifier.width(14.dp))
        BasicText(
            label,
            style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f)
        )
        Canvas(Modifier.size(10.dp)) {
            val w = size.width
            val h = size.height
            drawLine(
                Glyph,
                Offset(w * 0.2f, h * 0.15f),
                Offset(w * 0.75f, h * 0.5f),
                1.6.dp.toPx()
            )
            drawLine(
                Glyph,
                Offset(w * 0.75f, h * 0.5f),
                Offset(w * 0.2f, h * 0.85f),
                1.6.dp.toPx()
            )
        }
    }
}

/** 菜单图标全部自绘，避免引入 Material 图标。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMenuGlyph(glyph: MenuGlyph) {
    val w = size.width
    val h = size.height
    val stroke = 1.8.dp.toPx()
    when (glyph) {
        MenuGlyph.STATS -> listOf(0.24f to 0.5f, 0.5f to 0.28f, 0.76f to 0.62f).forEach { (cx, top) ->
            drawLine(
                Glyph,
                Offset(w * cx, h * top),
                Offset(w * cx, h * 0.85f),
                stroke * 2f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        MenuGlyph.SETTINGS -> {
            drawLine(Glyph, Offset(w * 0.12f, h * 0.34f), Offset(w * 0.88f, h * 0.34f), stroke)
            drawCircle(Accent, radius = stroke * 1.7f, center = Offset(w * 0.36f, h * 0.34f))
            drawLine(Glyph, Offset(w * 0.12f, h * 0.68f), Offset(w * 0.88f, h * 0.68f), stroke)
            drawCircle(Accent, radius = stroke * 1.7f, center = Offset(w * 0.64f, h * 0.68f))
        }

        MenuGlyph.ABOUT -> {
            drawCircle(
                Glyph,
                radius = w * 0.42f,
                center = Offset(w / 2f, h / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            drawCircle(Glyph, radius = stroke * 0.9f, center = Offset(w / 2f, h * 0.32f))
            drawLine(
                Glyph,
                Offset(w / 2f, h * 0.46f),
                Offset(w / 2f, h * 0.7f),
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        MenuGlyph.EXIT -> {
            drawCircle(
                Glyph,
                radius = w * 0.38f,
                center = Offset(w / 2f, h / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            drawLine(
                Accent,
                Offset(w * 0.5f, h * 0.12f),
                Offset(w * 0.5f, h * 0.46f),
                stroke * 1.4f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        MenuGlyph.LIST -> listOf(0.24f, 0.5f, 0.76f).forEach { fy ->
            drawCircle(Accent, stroke * 0.9f, Offset(w * 0.14f, h * fy))
            drawLine(
                Glyph,
                Offset(w * 0.32f, h * fy),
                Offset(w * 0.9f, h * fy),
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

/**
 * 歌词滚动区。竖屏时无歌词会显示封面；横屏时封面在左侧，这里只留提示。
 */
@Composable
private fun LyricsPane(
    lines: List<LyricLine>,
    synced: Boolean,
    active: Int,
    bvid: String,
    offsetMs: Long,
    coverUrl: String,
    showCoverWhenEmpty: Boolean,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showCoverWhenEmpty && coverUrl.isNotBlank()) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.66f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(GlassSoft)
                            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                    ) {
                        CoverBitmap(url = coverUrl, modifier = Modifier.fillMaxSize())
                    }
                }
                BasicText(
                    "暂无歌词 · 点击底部「歌词」搜索",
                    style = TextStyle(color = TextMuted, fontSize = 13.sp),
                    modifier = Modifier.clickable { onOpenLyrics() }
                )
            }
        }
        return
    }

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
    LaunchedEffect(active, viewportH, lines.size, bvid) {
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
        modifier
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
                            PlayerController.seek((line.timeMs + offsetMs) / 1000.0)
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

/** 从底部弹出的播放列表面板（只有内容，遮罩与滑入动画由外层控制）。 */
@Composable
private fun PlaylistSheet(
    tracks: List<BiliTrack>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "播放列表 (${tracks.size})",
                style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.weight(1f))
            GhostLabel("关闭", onClose)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
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
                            .background(GlassSoft)
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

    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .liquidGlass(
                shape = RoundedCornerShape(26.dp),
                blurRadius = 30.dp,
                elevation = 22.dp
            )
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
            .liquidGlass(
                shape = RoundedCornerShape(28.dp),
                blurRadius = 30.dp,
                elevation = 22.dp
            )
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
                    .liquidGlass(
                        shape = RoundedCornerShape(12.dp),
                        blurRadius = 14.dp,
                        elevation = 0.dp
                    )
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

/** 加载封面并绘制；失败/为空时保持透明，让渐变层兜底。 */
@Composable
internal fun CoverBitmap(url: String, modifier: Modifier) {
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
    onLyricsClick: () -> Unit,
    onTimerClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    onVolumeClick: () -> Unit,
) {
    val duration = if (uiState.duration > 0) uiState.duration else 1.0
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0.0) }
    val shown = if (scrubbing) scrubValue else uiState.currentTime

    Column(
        Modifier
            .fillMaxWidth()
            // 悬浮控制台：更大的模糊半径 + 更厚的镜面轮廓
            .liquidGlass(
                shape = RoundedCornerShape(32.dp),
                blurRadius = 34.dp,
                elevation = 18.dp
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 进度时间：一左一右放在进度条上方，中间是定时
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(formatClock(shown), style = TextStyle(color = TextDim, fontSize = 11.sp))
            Spacer(Modifier.weight(1f))
            // 歌词和定时放在一起
            Pill("歌词") { onLyricsClick() }
            Spacer(Modifier.width(6.dp))
            Pill(
                uiState.sleepRemaining?.let { formatClock(it.toDouble()) } ?: "定时",
                highlighted = uiState.sleepRemaining != null,
                onClick = onTimerClick
            )
            Spacer(Modifier.width(6.dp))
            LiquidCircleButton(size = 34.dp, onClick = onVolumeClick) {
                val iconColor = TextPrimary
                Canvas(Modifier.size(16.dp)) { drawSpeakerIcon(iconColor, uiState.volume) }
            }
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
            // Canvas 的 lambda 不是 @Composable，主题色要在外面取好再进来
            val trackColor = GlassBorder
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                drawRoundRect(color = trackColor, cornerRadius = CornerRadius(4f))
                val frac = (shown / duration).toFloat().coerceIn(0f, 1f)
                if (frac > 0f) {
                    drawRoundRect(
                        color = Accent,
                        size = Size(size.width * frac, size.height),
                        cornerRadius = CornerRadius(4f)
                    )
                    // 玻璃滑块：粉色光晕 + 白色实体圆点
                    val cx = size.width * frac
                    drawCircle(
                        color = Accent.copy(alpha = 0.45f),
                        radius = 8.dp.toPx(),
                        center = Offset(cx, size.height / 2f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 5.dp.toPx(),
                        center = Offset(cx, size.height / 2f)
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

/**
 * 右侧竖排音量面板。
 *
 * 拖动过程只改本地 [local]，不调用网页；松手才通过 [onCommit] 写入一次，
 * 避免每次像素移动都触发 WebView 音量同步导致卡顿。
 */
@Composable
private fun VolumePanel(volume: Int, onCommit: (Int) -> Unit) {
    var local by remember { mutableStateOf(volume) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(volume) {
        if (!dragging) local = volume
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            "$local",
            style = TextStyle(
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        fun apply(y: Float) {
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            local = (((h - y) / h).coerceIn(0f, 1f) * 100f).roundToInt()
                        }
                        apply(down.position.y)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                apply(change.position.y)
                                change.consume()
                            }
                        } while (change.pressed)
                        dragging = false
                        onCommit(local)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val trackColor = GlassBorder
            val fillColor = Accent
            Canvas(Modifier.width(8.dp).fillMaxHeight()) {
                drawRoundRect(color = trackColor, cornerRadius = CornerRadius(8f))
                val frac = (local / 100f).coerceIn(0f, 1f)
                val fillH = size.height * frac
                if (fillH > 0f) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(0f, size.height - fillH),
                        size = Size(size.width, fillH),
                        cornerRadius = CornerRadius(8f)
                    )
                }
                val cy = size.height - fillH
                drawCircle(
                    color = fillColor.copy(alpha = 0.40f),
                    radius = 11.dp.toPx(),
                    center = Offset(size.width / 2f, cy)
                )
                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = Offset(size.width / 2f, cy)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val iconColor = TextPrimary
        Canvas(Modifier.size(20.dp)) { drawSpeakerIcon(iconColor, local) }
    }
}

/** 自绘喇叭图标：音量越大，声波弧线越多。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpeakerIcon(
    color: Color,
    volume: Int,
) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.06f, h * 0.36f),
        size = Size(w * 0.18f, h * 0.28f),
        cornerRadius = CornerRadius(1.5f)
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.24f, h * 0.36f)
            lineTo(w * 0.48f, h * 0.16f)
            lineTo(w * 0.48f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.64f)
            close()
        },
        color
    )
    if (volume > 0) {
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(w * 0.44f, h * 0.32f),
            size = Size(w * 0.30f, h * 0.36f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
        )
    }
    if (volume > 55) {
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(w * 0.38f, h * 0.18f),
            size = Size(w * 0.56f, h * 0.64f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
        )
    }
}

/** 播放模式图标：顺序 / 随机 / 单曲循环，全部 Canvas 绘制。 */
@Composable
private fun ModeButton(mode: PlayMode, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .liquidPress(onClick)
            .liquidGlass(shape = CircleShape, blurRadius = 18.dp, elevation = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            when (mode) {
                PlayMode.SEQUENTIAL -> {
                    // 向右箭头
                    drawLine(Glyph, Offset(0f, h / 2f), Offset(w * 0.78f, h / 2f), stroke)
                    drawPath(Path().apply {
                        moveTo(w * 0.55f, h * 0.12f)
                        lineTo(w, h / 2f)
                        lineTo(w * 0.55f, h * 0.88f)
                        close()
                    }, Glyph)
                }
                PlayMode.SHUFFLE -> {
                    // 两条交叉线 + 箭头
                    drawLine(Glyph, Offset(0f, h * 0.2f), Offset(w, h * 0.8f), stroke)
                    drawLine(Glyph, Offset(0f, h * 0.8f), Offset(w, h * 0.2f), stroke)
                    drawPath(Path().apply {
                        moveTo(w * 0.62f, h * 0.02f)
                        lineTo(w, h * 0.2f)
                        lineTo(w * 0.68f, h * 0.36f)
                        close()
                    }, Glyph)
                }
                PlayMode.REPEAT_ONE -> {
                    // 圆环 + 中间的「1」用一个小方块近似
                    drawArc(
                        color = Glyph,
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
                    }, Glyph)
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
            .size(48.dp)
            .liquidPress(onClick)
            .liquidGlass(shape = CircleShape, blurRadius = 18.dp, elevation = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            listOf(0.2f, 0.5f, 0.8f).forEach { fy ->
                drawLine(Glyph, Offset(w * 0.16f, h * fy), Offset(w * 0.9f, h * fy), stroke)
                drawCircle(Accent, radius = stroke * 1.1f, center = Offset(0f, h * fy))
            }
        }
    }
}

private enum class Transport { PREV, PLAY, PAUSE, NEXT }

/** 用 Canvas 画播放控制图标，避免引入 Material 图标。 */
@Composable
private fun TransportButton(kind: Transport, size: Dp, onClick: () -> Unit) {
    val filled = kind == Transport.PLAY || kind == Transport.PAUSE
    Box(
        modifier = Modifier
            .size(size)
            .liquidPress(onClick)
            .liquidGlass(
                shape = CircleShape,
                blurRadius = if (filled) 22.dp else 16.dp,
                tint = if (filled) Accent.copy(alpha = 0.78f) else Color.Unspecified,
                elevation = if (filled) 14.dp else 6.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        // Canvas 里不能读 @Composable 颜色，先取出来
        val iconColor = if (filled) OnAccent else TextPrimary
        Canvas(Modifier.size(size * 0.40f)) {
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
                    iconColor
                )

                Transport.PAUSE -> {
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(w * 0.15f, 0f),
                        size = Size(w * 0.26f, h),
                        cornerRadius = CornerRadius(3f)
                    )
                    drawRoundRect(
                        color = iconColor,
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
                        iconColor
                    )
                    drawPath(
                        Path().apply {
                            moveTo(midX, 0f)
                            lineTo(x1, h / 2f)
                            lineTo(midX, h)
                            close()
                        },
                        iconColor
                    )
                    val barX = if (leftward) w * 0.02f else w * 0.86f
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(barX, 0f),
                        size = Size(w * 0.12f, h),
                        cornerRadius = CornerRadius(2f)
                    )
                }
            }
        }
    }
}

// ======================================================================
// 沉浸式听歌统计：按播放时长把封面拼成大小不一的矩形（squarified treemap）
// ======================================================================

/** 一个矩形块，坐标已归一化到 0..1。 */
private data class TreemapTile(
    val index: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * squarified treemap：体积大的先排，贪心成行，尽量让每块接近正方形。
 * [weights] 按任意顺序传入，返回的 [TreemapTile.index] 指向原下标。
 */
private fun squarify(weights: List<Float>, width: Float, height: Float): List<TreemapTile> {
    val tiles = mutableListOf<TreemapTile>()
    val total = weights.sum()
    if (total <= 0f || width <= 0f || height <= 0f) return tiles

    val order = weights.indices.sortedByDescending { weights[it] }
    val areas = order.map { weights[it] / total * width * height }

    var x = 0f
    var y = 0f
    var w = width
    var h = height
    var i = 0
    while (i < areas.size) {
        val shortSide = minOf(w, h)
        if (shortSide <= 0f) break

        // 贪心成行：把后面几项并进来，直到最差长宽比开始变糟
        var rowSum = 0f
        var bestWorst = Float.MAX_VALUE
        var count = 0
        while (i + count < areas.size) {
            val newSum = rowSum + areas[i + count]
            val worst = worstRatio(areas, i, i + count, newSum, shortSide)
            if (count > 0 && worst > bestWorst) break
            bestWorst = worst
            rowSum = newSum
            count++
        }
        if (count == 0) break

        if (w >= h) {
            // 竖条贴在左边
            val rowW = rowSum / h
            var cy = y
            for (k in 0 until count) {
                val area = areas[i + k]
                val tileH = if (k == count - 1) (y + h) - cy else area / rowW
                tiles.add(
                    TreemapTile(
                        order[i + k],
                        x / width, cy / height,
                        rowW / width, tileH / height
                    )
                )
                cy += tileH
            }
            x += rowW
            w -= rowW
        } else {
            // 横条贴在上边
            val rowH = rowSum / w
            var cx = x
            for (k in 0 until count) {
                val area = areas[i + k]
                val tileW = if (k == count - 1) (x + w) - cx else area / rowH
                tiles.add(
                    TreemapTile(
                        order[i + k],
                        cx / width, y / height,
                        tileW / width, rowH / height
                    )
                )
                cx += tileW
            }
            y += rowH
            h -= rowH
        }
        i += count
    }
    return tiles
}

private fun worstRatio(areas: List<Float>, from: Int, to: Int, sum: Float, side: Float): Float {
    val thickness = sum / side
    if (thickness <= 0f) return Float.MAX_VALUE
    var worst = 0f
    for (k in from..to) {
        val len = areas[k] / thickness
        if (len <= 0f) continue
        val ratio = maxOf(len / thickness, thickness / len)
        if (ratio > worst) worst = ratio
    }
    return worst
}

/** 秒 → 「1 小时 23 分」，统计页自用（与主界面的实现相互独立）。 */
private fun fmtDuration(seconds: Long): String {
    if (seconds <= 0) return "0 秒"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "$h 小时 $m 分"
        m > 0 -> "$m 分钟"
        else -> "$seconds 秒"
    }
}

/** 沉浸式统计页：深色 + 封面方块拼图。 */
@Composable
fun ImmersiveStatsScreen(
    playlists: List<Playlist>,
    stats: Map<String, Map<String, Long>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    fun totalOf(id: String): Long = stats[id]?.values?.sum() ?: 0L
    val withData = playlists.filter { totalOf(it.id) > 0 }
    val selected = withData.firstOrNull { it.id == selectedId }
        ?: withData.maxByOrNull { totalOf(it.id) }
    val totalAll = stats.values.sumOf { m -> m.values.sum() }

    LiquidGlassScaffold {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        @Composable
        fun Header() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    ImmersiveChip("← 返回", onBack)
                    Spacer(Modifier.width(10.dp))
                }
                BasicText(
                    "听歌统计",
                    style = TextStyle(color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.weight(1f))
                BasicText(
                    "累计 ${fmtDuration(totalAll)}",
                    style = TextStyle(color = TextMuted, fontSize = 12.sp)
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        @Composable
        fun Picker(horizontal: Boolean) {
            if (horizontal) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    withData.forEach { pl ->
                        Pill(
                            "${pl.name} · ${fmtDuration(totalOf(pl.id))}",
                            highlighted = pl.id == selected?.id
                        ) { onSelect(pl.id) }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicText(
                        "播放列表",
                        style = TextStyle(
                            color = TextMuted,
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    withData.forEach { pl ->
                        Pill(
                            "${pl.name} · ${fmtDuration(totalOf(pl.id))}",
                            highlighted = pl.id == selected?.id
                        ) { onSelect(pl.id) }
                    }
                }
            }
        }

        @Composable
        fun SelectedContent(modifier: Modifier) {
            Column(modifier.fillMaxWidth()) {
                val sel = selected ?: return@Column
                val perTrack = stats[sel.id].orEmpty()
                val entries = sel.tracks
                    .distinctBy { it.bvid }
                    .mapNotNull { t ->
                        val sec = perTrack[t.bvid] ?: 0L
                        if (sec > 0) t to sec else null
                    }
                    .sortedByDescending { it.second }
                    .take(MAX_TREEMAP_TILES)

                BasicText(
                    "${sel.name} · ${entries.size} 首有记录" +
                        if (perTrack.size > MAX_TREEMAP_TILES) "（仅显示时长前 $MAX_TREEMAP_TILES 首）" else "",
                    style = TextStyle(color = TextMuted, fontSize = 11.sp)
                )
                Spacer(Modifier.height(10.dp))

                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(28.dp),
                    elevation = 14.dp
                ) {
                    CoverTreemap(
                        entries = entries,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(5.dp)
                            .clip(RoundedCornerShape(23.dp))
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                // 和关于 / 设置一样：内容止于悬浮导航上方，不钻到栏底下
                .padding(bottom = LocalImmersiveBottomSpace.current)
        ) {
            Header()

            if (withData.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "还没有听歌记录。播几首之后，这里会按听歌时长把封面拼成一张图。",
                        style = TextStyle(color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    )
                }
                return@Column
            }

            if (landscape) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(0.36f).fillMaxHeight()) {
                        Picker(horizontal = false)
                    }
                    SelectedContent(Modifier.weight(0.64f).fillMaxHeight())
                }
            } else {
                Picker(horizontal = true)
                Spacer(Modifier.height(14.dp))
                SelectedContent(Modifier.weight(1f))
            }
        }
    }
}

private const val MAX_TREEMAP_TILES = 60

/** 用 Layout 把封面按算好的矩形摆出来；块内叠一层暗角保证文字可读。 */
@Composable
private fun CoverTreemap(
    entries: List<Pair<BiliTrack, Long>>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val weights = entries.map { it.second.toFloat() }
    val rects = remember(weights) { squarify(weights, 1f, 1f) }

    Layout(content = {
        rects.forEach { r ->
            val track = entries.getOrNull(r.index)?.first ?: return@forEach
            Box(
                Modifier
                    .background(GlassBorder)
            ) {
                if (track.cover.isNotBlank()) {
                    CoverBitmap(url = track.cover, modifier = Modifier.fillMaxSize())
                }
                // 大块才显示标题，小块放字反而糊
                if (r.width > 0.13f && r.height > 0.1f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x00000000), Color(0xAA000000)),
                                    startY = 0f
                                )
                            )
                    )
                    BasicText(
                        track.title.ifBlank { track.bvid },
                        style = TextStyle(color = TextPrimary, fontSize = 10.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    )
                }
            }
        }
    }, modifier = modifier) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val placeables = measurables.mapIndexed { i, m ->
            val r = rects.getOrNull(i)
            val pw = ((r?.width ?: 0f) * w).toInt().coerceAtLeast(1)
            val ph = ((r?.height ?: 0f) * h).toInt().coerceAtLeast(1)
            m.measure(Constraints.fixed(pw, ph))
        }
        layout(w, h) {
            placeables.forEachIndexed { i, p ->
                val r = rects.getOrNull(i) ?: return@forEachIndexed
                p.place((r.left * w).toInt(), (r.top * h).toInt())
            }
        }
    }
}

// ======================================================================
// 沉浸式导入 / 登录页
// ======================================================================

@Composable
fun ImmersiveImportScreen(
    uiState: PlayerUiState,
    onLoginClick: () -> Unit,
    onStart: (uid: String, favName: String, autoFullscreen: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var uid by remember { mutableStateOf(Preferences.uid(context)) }
    var fav by remember { mutableStateOf(Preferences.favName(context)) }
    var autoFullscreen by remember { mutableStateOf(Preferences.autoFullscreen(context)) }

    LiquidGlassScaffold {

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImmersiveChip("← 返回", onBack)
                Spacer(Modifier.width(10.dp))
                BasicText(
                    "从收藏夹导入",
                    style = TextStyle(color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                )
            }
            BasicText(
                "输入 B 站 UID 与收藏夹名称，抓取一次即缓存到本地。",
                style = TextStyle(color = TextMuted, fontSize = 12.sp)
            )

            ImmersiveField("UID", uid) { uid = it }
            ImmersiveField("收藏夹名称", fav) { fav = it }

            ImmersiveRow(
                "自动全屏并隐藏控制条",
                "关闭则默认显示封面",
                { TogglePill(autoFullscreen) { autoFullscreen = it } }
            )

            Pill("登录 B 站（可选）") { onLoginClick() }
            BasicText(
                "登录后可以访问私密收藏夹；Cookie 只保存在本机。",
                style = TextStyle(color = TextDim, fontSize = 10.sp)
            )

            Spacer(Modifier.height(6.dp))

            val enabled = uid.isNotBlank() && fav.isNotBlank() && !uiState.isFetching
            Box(
                Modifier
                    .fillMaxWidth()
                    .liquidPress(
                        onClick = { onStart(uid.trim(), fav.trim(), autoFullscreen) },
                        enabled = enabled
                    )
                    .liquidGlass(
                        shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp,
                        tint = if (enabled) Accent.copy(alpha = 0.78f) else Color.Unspecified,
                        elevation = 10.dp
                    )
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    if (uiState.isFetching) "抓取中…" else "抓取并播放",
                    style = TextStyle(
                        color = if (enabled) OnAccent else TextDim,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            if (uiState.isFetching) {
                BasicText(
                    uiState.paginationInfo ?: "正在抓取收藏夹… 请不要动上方画面",
                    style = TextStyle(color = Accent, fontSize = 12.sp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(GlassSoft)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(uiState.fetchProgress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Accent)
                    )
                }
                uiState.fetchHint?.let {
                    BasicText(it, style = TextStyle(color = Color(0xFFFFD166), fontSize = 11.sp))
                }
            }
            uiState.fetchError?.let {
                BasicText(it, style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 12.sp))
            }
        }
    }
}

/** 沉浸式 B 站登录页：同一套 WebView，只是外壳换成深色。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImmersiveLoginScreen(onBack: () -> Unit) {
    LiquidGlassScaffold {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(24.dp),
                        blurRadius = 24.dp,
                        elevation = 10.dp
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImmersiveChip("← 返回") {
                    CookieManager.getInstance().flush()
                    onBack()
                }
                Spacer(Modifier.width(12.dp))
                BasicText(
                    "登录 B 站",
                    style = TextStyle(color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                )
            }
            BasicText(
                "登录完成后点「返回」；登录状态只保存在本机 WebView 中",
                style = TextStyle(color = TextMuted, fontSize = 11.sp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(26.dp),
                        blurRadius = 24.dp,
                        elevation = 12.dp
                    )
                    .padding(5.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val wv = WebView(ctx)
                        wv.webViewClient = WebViewClient()
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.userAgentString = HeadlessBrowser.DESKTOP_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        wv.loadUrl("https://www.bilibili.com/")
                        wv
                    },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(21.dp))
                )
            }
        }
    }
}

// ======================================================================
// 沉浸式播放列表库（启动首页）
// ======================================================================

private fun fmtDate(ms: Long): String {
    if (ms <= 0) return ""
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }.getOrDefault("")
}

/** 冷启动 splash：预加载推荐合集，加载完成后进入主界面。 */
@Composable
fun ImmersiveSplashScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    val state by RecommendStore.state.collectAsState()
    var started by remember { mutableStateOf(false) }
    val host = (context as? android.app.Activity)?.window?.decorView as? ViewGroup

    LaunchedEffect(Unit) {
        RecommendStore.init(context)
        RecommendStore.ensureLoaded(host)
        started = true
    }
    LaunchedEffect(started, state.loading, state.items.size, state.error) {
        if (started && !state.loading) {
            delay(650)
            onReady()
        }
    }

    LiquidGlassScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BasicText(
                "BiliPlayer",
                style = TextStyle(
                    color = TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                "Liquid Glass",
                style = TextStyle(
                    color = Accent,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp
                )
            )
            Spacer(Modifier.height(30.dp))
            BasicText(
                state.progress ?: "正在准备推荐内容…",
                style = TextStyle(color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
            )
            state.error?.let {
                Spacer(Modifier.height(10.dp))
                BasicText(
                    it,
                    style = TextStyle(
                        color = Color(0xFFFF8A8A),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                )
                Spacer(Modifier.height(10.dp))
                LiquidPill("重试") { RecommendStore.refresh(host) }
            }
            Spacer(Modifier.height(24.dp))
            LiquidPill("跳过等待") { onReady() }
        }
    }
}

/** 底部导航 +（可选）迷你播放条占用的高度，由宿主页提供。 */
internal val LocalImmersiveBottomSpace = androidx.compose.runtime.compositionLocalOf { 0.dp }

internal enum class ImmersiveTab(val label: String) {
    RECOMMEND("推荐"),
    LIBRARY("歌单"),
    STATS("统计"),
    SETTINGS("设置"),
    ABOUT("关于"),
}

/** 沉浸式主界面：五个一级页面 + Liquid Glass 底部导航。 */
@Composable
fun ImmersiveHome(
    playlists: List<Playlist>,
    uiState: PlayerUiState,
    currentPlaylistId: String,
    stats: Map<String, Map<String, Long>>,
    statsSelectedId: String?,
    onSelectStats: (String) -> Unit,
    immersiveMode: Boolean,
    onImmersiveModeChange: (Boolean) -> Unit,
    checking: Boolean,
    checkResult: String?,
    onCheckUpdate: () -> Unit,
    onOpenLogs: () -> Unit,
    onImportClick: () -> Unit,
    onPlay: (Playlist) -> Unit,
    onSync: (Playlist) -> Unit,
    onRename: (Playlist, String) -> Unit,
    onDelete: (Playlist) -> Unit,
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    showMiniPlayer: Boolean = false,
    onExpandPlayer: () -> Unit = {},
) {
    val tab = ImmersiveTab.entries[
        selectedTabIndex.coerceIn(0, ImmersiveTab.entries.lastIndex)
    ]
    val haze = rememberLiquidGlassState()
    val overlayPresence = remember { GlassOverlayPresence() }

    CompositionLocalProvider(
        LocalLiquidGlass provides haze,
        LocalGlassOverlayPresence provides overlayPresence,
        LocalImmersiveBottomSpace provides if (showMiniPlayer) 170.dp else 96.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(animationSpec = tween(280)) { dir * it / 4 } +
                        fadeIn(tween(220)) +
                        scaleIn(initialScale = 0.97f)) togetherWith
                        (slideOutHorizontally(animationSpec = tween(220)) { -dir * it / 4 } +
                            fadeOut(tween(160)) +
                            scaleOut(targetScale = 0.97f))
                },
                modifier = Modifier.fillMaxSize(),
                label = "immersiveTab"
            ) { current ->
                when (current) {
                    ImmersiveTab.RECOMMEND -> RecommendHomeScreen()
                    ImmersiveTab.LIBRARY -> ImmersiveLibraryScreen(
                        playlists = playlists,
                        uiState = uiState,
                        currentPlaylistId = currentPlaylistId,
                        stats = stats,
                        onImportClick = onImportClick,
                        onPlay = onPlay,
                        onSync = onSync,
                        onRename = onRename,
                        onDelete = onDelete
                    )
                    ImmersiveTab.STATS -> ImmersiveStatsScreen(
                        playlists = playlists,
                        stats = stats,
                        selectedId = statsSelectedId,
                        onSelect = onSelectStats
                    )
                    ImmersiveTab.SETTINGS -> ImmersiveSettingsScreen(
                        immersiveMode = immersiveMode,
                        onImmersiveModeChange = onImmersiveModeChange
                    )
                    ImmersiveTab.ABOUT -> ImmersiveAboutScreen(
                        checking = checking,
                        checkResult = checkResult,
                        onCheckUpdate = onCheckUpdate,
                        onOpenLogs = onOpenLogs
                    )
                }
            }

            AnimatedVisibility(
                visible = overlayPresence.count == 0,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = showMiniPlayer,
                        enter = slideInVertically(
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f)
                        ) { it / 2 } +
                            fadeIn(tween(140)) +
                            scaleIn(
                                initialScale = 0.90f,
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                            ),
                        exit = slideOutVertically(tween(180)) { it / 2 } +
                            fadeOut(tween(120)) +
                            scaleOut(
                                targetScale = 0.90f,
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                            )
                    ) {
                        Column {
                            MiniPlayerBar(uiState = uiState, onExpand = onExpandPlayer)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    ImmersiveBottomNav(
                        selected = tab,
                        onSelect = { onTabSelected(it.ordinal) }
                    )
                }
            }
        }
    }
}

/** 收起播放页后的迷你播放条，贴在底部导航上方。 */
@Composable
private fun MiniPlayerBar(uiState: PlayerUiState, onExpand: () -> Unit) {
    val lyric = uiState.currentLyricLine
    val title = uiState.currentTitle.ifBlank { "未在播放" }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .liquidGlass(
                shape = RoundedCornerShape(24.dp),
                blurRadius = 26.dp,
                elevation = 14.dp
            )
            .liquidPress(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSoft)
        ) {
            if (uiState.currentCover.isNotBlank()) {
                CoverBitmap(uiState.currentCover, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                lyric.ifBlank { title },
                style = TextStyle(
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                if (lyric.isNotBlank()) title else "正在播放",
                style = TextStyle(color = TextMuted, fontSize = 9.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        LiquidCircleButton(size = 34.dp, onClick = { PlayerController.playPause() }) {
            val iconColor = TextPrimary
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                if (uiState.isPlaying) {
                    drawRoundRect(
                        iconColor,
                        topLeft = Offset(w * 0.18f, 0f),
                        size = Size(w * 0.24f, h),
                        cornerRadius = CornerRadius(2f)
                    )
                    drawRoundRect(
                        iconColor,
                        topLeft = Offset(w * 0.58f, 0f),
                        size = Size(w * 0.24f, h),
                        cornerRadius = CornerRadius(2f)
                    )
                } else {
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.20f, 0f)
                            lineTo(w, h / 2f)
                            lineTo(w * 0.20f, h)
                            close()
                        },
                        iconColor
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        LiquidCircleButton(size = 34.dp, onClick = { PlayerController.next() }) {
            val iconColor = TextPrimary
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                drawPath(
                    Path().apply {
                        moveTo(w * 0.08f, 0f)
                        lineTo(w * 0.55f, h / 2f)
                        lineTo(w * 0.08f, h)
                        close()
                    },
                    iconColor
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.52f, 0f)
                        lineTo(w, h / 2f)
                        lineTo(w * 0.52f, h)
                        close()
                    },
                    iconColor
                )
                drawRoundRect(
                    iconColor,
                    topLeft = Offset(w * 0.86f, 0f),
                    size = Size(w * 0.12f, h),
                    cornerRadius = CornerRadius(2f)
                )
            }
        }
    }
}

@Composable
private fun ImmersiveBottomNav(
    selected: ImmersiveTab,
    onSelect: (ImmersiveTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .liquidGlass(
                shape = GlassCapsule,
                blurRadius = 28.dp,
                elevation = 18.dp
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ImmersiveTab.entries.forEach { tab ->
            val active = tab == selected
            val color = if (active) Accent else TextMuted
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (active) Accent.copy(alpha = 0.12f) else Color.Transparent)
                    .liquidPress(onClick = { onSelect(tab) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(Modifier.size(20.dp)) { drawNavGlyph(tab, color) }
                Spacer(Modifier.height(2.dp))
                BasicText(
                    tab.label,
                    style = TextStyle(
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    )
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (active) Accent else Color.Transparent)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNavGlyph(
    tab: ImmersiveTab,
    color: Color,
) {
    val w = size.width
    val h = size.height
    val stroke = 1.7.dp.toPx()
    when (tab) {
        ImmersiveTab.RECOMMEND -> {
            drawCircle(color, radius = w * 0.36f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawPath(
                Path().apply {
                    moveTo(w * 0.70f, h * 0.10f)
                    lineTo(w * 0.80f, h * 0.30f)
                    lineTo(w * 1.00f, h * 0.40f)
                    lineTo(w * 0.80f, h * 0.50f)
                    lineTo(w * 0.70f, h * 0.70f)
                    lineTo(w * 0.60f, h * 0.50f)
                    lineTo(w * 0.40f, h * 0.40f)
                    lineTo(w * 0.60f, h * 0.30f)
                    close()
                },
                color
            )
        }
        ImmersiveTab.LIBRARY -> {
            listOf(0.22f, 0.5f, 0.78f).forEach { fy ->
                drawCircle(color, radius = stroke * 0.8f, center = Offset(w * 0.14f, h * fy))
                drawLine(color, Offset(w * 0.30f, h * fy), Offset(w * 0.88f, h * fy), stroke)
            }
        }
        ImmersiveTab.STATS -> {
            drawLine(color, Offset(w * 0.18f, h * 0.82f), Offset(w * 0.18f, h * 0.52f), stroke * 1.8f)
            drawLine(color, Offset(w * 0.50f, h * 0.82f), Offset(w * 0.50f, h * 0.28f), stroke * 1.8f)
            drawLine(color, Offset(w * 0.82f, h * 0.82f), Offset(w * 0.82f, h * 0.44f), stroke * 1.8f)
        }
        ImmersiveTab.SETTINGS -> {
            listOf(0.32f, 0.68f).forEachIndexed { i, fy ->
                drawLine(color, Offset(w * 0.12f, h * fy), Offset(w * 0.88f, h * fy), stroke)
                drawCircle(
                    color,
                    radius = stroke * 1.4f,
                    center = Offset(w * (if (i == 0) 0.36f else 0.64f), h * fy)
                )
            }
        }
        ImmersiveTab.ABOUT -> {
            drawCircle(color, radius = w * 0.38f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawCircle(color, radius = stroke * 0.9f, center = Offset(w * 0.5f, h * 0.30f))
            drawLine(color, Offset(w * 0.5f, h * 0.44f), Offset(w * 0.5f, h * 0.70f), stroke)
        }
    }
}

/**
 * 推荐合集页：固定来源，抓取该 UP 的全部合集，先做粗略展示。
 */
@Composable
fun ImmersiveRecommendScreen() {
    val context = LocalContext.current
    val host = (context as? android.app.Activity)?.window?.decorView as? ViewGroup
    val state by RecommendStore.state.collectAsState()
    LaunchedEffect(Unit) {
        RecommendStore.init(context)
        RecommendStore.ensureLoaded(host as? ViewGroup)
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (landscape) 3 else 2

    LiquidGlassScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "推荐合集",
                        style = TextStyle(color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    )
                    BasicText(
                        "固定来源 · JLRS-LeoFM",
                        style = TextStyle(color = TextMuted, fontSize = 11.sp)
                    )
                }
                Pill(
                    "刷新",
                    enabled = !state.loading,
                    onClick = { RecommendStore.refresh(host as? ViewGroup) }
                )
            }

            Spacer(Modifier.height(12.dp))

            state.progress?.let {
                BasicText(it, style = TextStyle(color = Accent, fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
            }
            if (state.loading) {
                BasicText(
                    "首次抓取需要等页面渲染，请稍候…",
                    style = TextStyle(color = TextMuted, fontSize = 11.sp)
                )
                Spacer(Modifier.height(8.dp))
            }
            state.error?.let {
                BasicText(it, style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
            }

            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        if (state.loading) "正在抓取合集…" else "暂无推荐内容",
                        style = TextStyle(color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(state.items.chunked(columns)) { _, row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { item ->
                                RecommendCard(item, Modifier.weight(1f))
                            }
                            repeat(columns - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(LocalImmersiveBottomSpace.current + 12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RecommendCard(item: RecommendItem, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .liquidPress(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                }
            })
            .liquidGlass(
                shape = RoundedCornerShape(20.dp),
                blurRadius = 20.dp,
                elevation = 6.dp
            )
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(GlassSoft)
        ) {
            if (item.cover.isNotBlank()) {
                CoverBitmap(item.cover, Modifier.fillMaxSize())
            }
            if (item.count > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(GlassCapsule)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    BasicText(
                        "${item.count} 个视频",
                        style = TextStyle(color = Color.White, fontSize = 9.sp)
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(GlassCapsule)
                    .background(Accent.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                BasicText(
                    item.type,
                    style = TextStyle(
                        color = OnAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            item.title,
            style = TextStyle(
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        BasicText("打开 B 站合集", style = TextStyle(color = TextDim, fontSize = 10.sp))
    }
}

/** 推荐页：顶部“私人致享2.0”轮播，下面按合集展示最新视频。 */
@Composable
fun RecommendHomeScreen() {
    val context = LocalContext.current
    val state by RecommendStore.state.collectAsState()
    LaunchedEffect(Unit) {
        RecommendStore.init(context)
        RecommendStore.ensureLoaded(
            (context as? android.app.Activity)?.window?.decorView as? ViewGroup
        )
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (landscape) 3 else 2

    LiquidGlassScaffold {
        val detail = state.detail
        if (detail != null) {
            RecommendDetailPage(detail = detail, columns = columns)
        } else {
            RecommendListPage(state = state, columns = columns, landscape = landscape)
        }
    }
}

@Composable
private fun RecommendListPage(
    state: RecommendState,
    columns: Int,
    landscape: Boolean,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    "推荐合集",
                    style = TextStyle(color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                )
                BasicText(
                    "固定来源 · JLRS-LeoFM",
                    style = TextStyle(color = TextMuted, fontSize = 11.sp)
                )
            }
            Pill("刷新", enabled = !state.loading, onClick = {
                RecommendStore.refresh(
                    (context as? android.app.Activity)?.window?.decorView as? ViewGroup
                )
            })
        }

        state.progress?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TextStyle(color = Accent, fontSize = 11.sp))
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 12.sp))
        }

        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    if (state.loading) "正在抓取合集…" else "暂无推荐内容",
                    style = TextStyle(color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                )
            }
            return@Column
        }

        val featured = state.items.firstOrNull { it.title == "私人致享2.0" }
            ?: state.items.firstOrNull { it.videos.isNotEmpty() }
        val others = state.items.filter { it.id != featured?.id }

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val featuredVideos = featured?.videos.orEmpty()
            if (featuredVideos.isNotEmpty()) {
                item {
                    FeaturedCarousel(
                        videos = featuredVideos,
                        height = if (landscape) 220.dp else 200.dp
                    )
                }
            }
            itemsIndexed(others, key = { _, item -> item.id }) { _, item ->
                RecommendCollectionSection(item) {
                    RecommendStore.openCollection(item)
                }
            }
            item { Spacer(Modifier.height(LocalImmersiveBottomSpace.current + 16.dp)) }
        }
    }
}

@Composable
private fun FeaturedCarousel(videos: List<RecommendVideo>, height: Dp) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { videos.size })
    LaunchedEffect(videos.size) {
        if (videos.size <= 1) return@LaunchedEffect
        while (true) {
            delay(4200)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % videos.size)
            }
        }
    }

    Column {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(height)
        ) { page ->
            val video = videos[page]
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
                    .liquidPress(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url)))
                        }
                    })
            ) {
                CoverBitmap(video.cover, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                            startY = 0f
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(16.dp)
                ) {
                    BasicText(
                        "私人致享2.0",
                        style = TextStyle(
                            color = Accent,
                            fontSize = 10.sp,
                            letterSpacing = 1.4.sp
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        video.title.ifBlank { video.bvid },
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            videos.indices.forEach { i ->
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(if (i == pagerState.currentPage) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == pagerState.currentPage) Accent
                            else TextDim.copy(alpha = 0.45f)
                        )
                )
            }
        }
    }
}

@Composable
private fun RecommendCollectionSection(
    item: RecommendItem,
    onOpen: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .liquidPress(onClick = onOpen)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    item.title,
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    "${item.count} 个视频 · ${item.type}",
                    style = TextStyle(color = TextMuted, fontSize = 10.sp)
                )
            }
            BasicText("查看全部 ›", style = TextStyle(color = Accent, fontSize = 11.sp))
        }
        Spacer(Modifier.height(6.dp))
        if (item.videos.isEmpty()) {
            BasicText("暂无视频预览", style = TextStyle(color = TextDim, fontSize = 11.sp))
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(item.videos) { _, video ->
                    RecommendVideoCard(video, Modifier.width(154.dp))
                }
            }
        }
    }
}

@Composable
private fun RecommendVideoCard(video: RecommendVideo, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .liquidPress(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url)))
                }
            })
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(GlassSoft)
        ) {
            if (video.cover.isNotBlank()) {
                CoverBitmap(video.cover, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(5.dp))
        BasicText(
            video.title.ifBlank { video.bvid },
            style = TextStyle(color = TextPrimary, fontSize = 11.sp, lineHeight = 15.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecommendDetailPage(detail: RecommendDetail, columns: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImmersiveChip("← 返回") { RecommendStore.closeDetail() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    detail.title,
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    "${detail.videos.size} 个视频",
                    style = TextStyle(color = TextMuted, fontSize = 10.sp)
                )
            }
        }

        detail.progress?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TextStyle(color = Accent, fontSize = 11.sp))
        }
        detail.error?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 12.sp))
        }

        if (detail.videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    if (detail.loading) "正在抓取合集全部视频…" else "暂无视频",
                    style = TextStyle(color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                )
            }
            return@Column
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(detail.videos.chunked(columns)) { _, row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { video ->
                        RecommendVideoCard(video, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { Spacer(Modifier.height(LocalImmersiveBottomSpace.current + 16.dp)) }
        }
    }
}

@Composable
fun ImmersiveLibraryScreen(
    playlists: List<Playlist>,
    uiState: PlayerUiState,
    currentPlaylistId: String,
    stats: Map<String, Map<String, Long>>,
    onImportClick: () -> Unit,
    onPlay: (Playlist) -> Unit,
    onSync: (Playlist) -> Unit,
    onRename: (Playlist, String) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    BackHandler(enabled = menuFor != null || renameTarget != null || deleteTarget != null) {
        when {
            deleteTarget != null -> deleteTarget = null
            renameTarget != null -> renameTarget = null
            menuFor != null -> menuFor = null
        }
    }

    LiquidGlassScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "播放列表",
                        style = TextStyle(color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    )
                    BasicText(
                        "导入一次即缓存到本地，之后打开即用",
                        style = TextStyle(color = TextMuted, fontSize = 11.sp)
                    )
                }
                Pill("＋ 导入", highlighted = true) { onImportClick() }
            }

            Spacer(Modifier.height(16.dp))

            // 抓取/同步进度
            if (uiState.isFetching) {
                BasicText(
                    if (uiState.paginationInfo?.isNotBlank() == true) uiState.paginationInfo
                    else "正在抓取收藏夹…",
                    style = TextStyle(color = Accent, fontSize = 12.sp)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(GlassSoft)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(uiState.fetchProgress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Accent)
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            uiState.fetchError?.let {
                BasicText(
                    it,
                    style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 12.sp),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "还没有播放列表，点右上角「＋ 导入」从收藏夹导入一个",
                        style = TextStyle(color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(playlists, key = { _, p -> p.id }) { _, pl ->
                    val playing = pl.id == currentPlaylistId
                    val listened = stats[pl.id]?.values?.sum() ?: 0L
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .liquidPress(onClick = { onPlay(pl) })
                            .liquidGlass(
                                shape = RoundedCornerShape(24.dp),
                                blurRadius = 24.dp,
                                tint = if (playing) Accent.copy(alpha = 0.30f) else Color.Unspecified,
                                elevation = 8.dp
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(GlassSoft)
                        ) {
                            val cover = pl.tracks.firstOrNull()?.cover.orEmpty()
                            if (cover.isNotBlank()) {
                                CoverBitmap(url = cover, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                pl.name,
                                style = TextStyle(
                                    color = if (playing) Accent else TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            BasicText(
                                buildString {
                                    append("${pl.tracks.size} 首")
                                    if (listened > 0) append(" · 已听 ${fmtDuration(listened)}")
                                },
                                style = TextStyle(color = TextMuted, fontSize = 11.sp)
                            )
                            if (pl.updatedAt > 0) {
                                BasicText(
                                    "更新于 ${fmtDate(pl.updatedAt)}",
                                    style = TextStyle(color = TextDim, fontSize = 10.sp)
                                )
                            }
                        }
                        CircleMenuButton { menuFor = pl.id }
                    }
                }
                item {
                    Spacer(Modifier.height(LocalImmersiveBottomSpace.current + 12.dp))
                }
            }
        }

        // ---------- 列表操作（底部弹出，可拖到全屏） ----------
        val target = playlists.firstOrNull { it.id == menuFor }
        if (target != null) {
            GlassBottomSheet(
                onDismiss = { menuFor = null },
                initialFraction = 0.82f
            ) { _ ->
                val pl = target
                val listened = stats[pl.id]?.values?.sum() ?: 0L
                val perTrack = stats[pl.id].orEmpty()
                val topTracks = remember(pl.tracks, perTrack) {
                    pl.tracks.distinctBy { it.bvid }
                        .mapNotNull { t ->
                            val sec = perTrack[t.bvid] ?: 0L
                            if (sec > 0) t to sec else null
                        }
                        .sortedByDescending { it.second }
                        .take(3)
                }
                val canSync = pl.uid.isNotBlank() && pl.favName.isNotBlank()
                val cover = pl.tracks.firstOrNull()?.cover.orEmpty()

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp)
                ) {
                        // ---------- 头部：封面 + 名称 + 核心统计 ----------
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(GlassSoft)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                            ) {
                                if (cover.isNotBlank()) {
                                    CoverBitmap(url = cover, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    pl.name,
                                    style = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 24.sp
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                BasicText(
                                    buildString {
                                        append("${pl.tracks.size} 首")
                                        if (listened > 0) append(" · 已听 ${fmtDuration(listened)}")
                                    },
                                    style = TextStyle(color = TextMuted, fontSize = 11.sp)
                                )
                                if (pl.updatedAt > 0) {
                                    BasicText(
                                        "更新于 ${fmtDate(pl.updatedAt)}",
                                        style = TextStyle(color = TextDim, fontSize = 10.sp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // ---------- 来源：收藏夹 / UID ----------
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SheetInfoTag(
                                label = "来源收藏夹",
                                value = pl.favName.ifBlank { "未记录" },
                                modifier = Modifier.weight(1f)
                            )
                            SheetInfoTag(
                                label = "UID",
                                value = pl.uid.ifBlank { "未记录" },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // ---------- 常听曲目 ----------
                        BasicText(
                            "常听曲目",
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        if (topTracks.isEmpty()) {
                            BasicText(
                                "还没有听歌记录，播几首之后这里会显示听得最多的曲目。",
                                style = TextStyle(color = TextDim, fontSize = 11.sp)
                            )
                        } else {
                            topTracks.forEachIndexed { i, (track, sec) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(
                                        "${i + 1}",
                                        style = TextStyle(color = TextDim, fontSize = 11.sp),
                                        modifier = Modifier.width(16.dp)
                                    )
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(GlassSoft)
                                    ) {
                                        if (track.cover.isNotBlank()) {
                                            CoverBitmap(url = track.cover, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    BasicText(
                                        track.title.ifBlank { track.bvid },
                                        style = TextStyle(color = TextPrimary, fontSize = 13.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    BasicText(
                                        fmtDuration(sec),
                                        style = TextStyle(color = TextDim, fontSize = 10.sp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // ---------- 操作 ----------
                        BasicText(
                            "操作",
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(Modifier.height(4.dp))

                        SheetActionRow(
                            glyph = SheetGlyph.PLAY,
                            title = "播放这个列表",
                            subtitle = if (pl.id == currentPlaylistId) "正在播放 · 从头开始"
                            else "${pl.tracks.size} 首曲目，点击开始播放",
                            accent = Accent
                        ) {
                            onPlay(pl)
                            menuFor = null
                        }
                        SheetActionRow(
                            glyph = SheetGlyph.SYNC,
                            title = "同步收藏夹",
                            subtitle = if (canSync) "重新抓取并覆盖本地缓存"
                            else "没有记录来源收藏夹，无法同步",
                            accent = Accent,
                            enabled = canSync
                        ) {
                            onSync(pl)
                            menuFor = null
                        }
                        SheetActionRow(
                            glyph = SheetGlyph.RENAME,
                            title = "重命名",
                            subtitle = "只修改本机显示的名称",
                            accent = TextPrimary
                        ) {
                            renameTarget = pl
                            menuFor = null
                        }
                        SheetActionRow(
                            glyph = SheetGlyph.DELETE,
                            title = "删除列表",
                            subtitle = "移除本地缓存，不影响 B 站收藏夹",
                            accent = Color(0xFFFF6B7A)
                        ) {
                            deleteTarget = pl
                            menuFor = null
                        }

                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            LiquidPill("取消") { menuFor = null }
                        }
                    }
            }
        }

        // ---------- 重命名 ----------
        renameTarget?.let { pl ->
            GlassDialog(onDismiss = { renameTarget = null }) { close ->
                ImmersiveInputDialog(
                    title = "重命名播放列表",
                    initial = pl.name,
                    placeholder = "名称",
                    confirmText = "保存",
                    onConfirm = { name ->
                        onRename(pl, name)
                        close()
                    },
                    onDismiss = close
                )
            }
        }

        // ---------- 删除确认 ----------
        deleteTarget?.let { pl ->
            GlassDialog(onDismiss = { deleteTarget = null }) { close ->
                ImmersiveConfirmDialog(
                    title = "删除播放列表",
                    message = "确定删除「${pl.name}」吗？本地缓存的 ${pl.tracks.size} 首曲目会一并移除，收藏夹本身不受影响。",
                    confirmText = "删除",
                    onConfirm = {
                        onDelete(pl)
                        close()
                    },
                    onDismiss = close
                )
            }
        }
    }
}

/** 播放列表详情里的信息胶囊（来源收藏夹 / UID）。 */
@Composable
private fun SheetInfoTag(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSoft)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(color = TextDim, fontSize = 9.sp, letterSpacing = 0.8.sp)
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            value,
            style = TextStyle(color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private enum class SheetGlyph { PLAY, SYNC, RENAME, DELETE }

/** 播放列表详情里的整行操作：图标 + 标题 + 说明。 */
@Composable
private fun SheetActionRow(
    glyph: SheetGlyph,
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .liquidPress(onClick, enabled = enabled)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (enabled) 0.16f else 0.06f))
                .border(
                    1.dp,
                    accent.copy(alpha = if (enabled) 0.30f else 0.10f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            val iconColor = if (enabled) accent else TextDim
            Canvas(Modifier.size(18.dp)) { drawSheetGlyph(glyph, iconColor) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                style = TextStyle(
                    color = if (enabled) TextPrimary else TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            BasicText(
                subtitle,
                style = TextStyle(color = if (enabled) TextMuted else TextDim, fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (enabled) {
            val chevron = TextDim
            Canvas(Modifier.size(10.dp)) {
                val w = size.width
                val h = size.height
                drawLine(chevron, Offset(w * 0.2f, h * 0.15f), Offset(w * 0.75f, h * 0.5f), 1.4.dp.toPx())
                drawLine(chevron, Offset(w * 0.75f, h * 0.5f), Offset(w * 0.2f, h * 0.85f), 1.4.dp.toPx())
            }
        }
    }
}

/** 播放列表详情操作图标，全部自绘。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSheetGlyph(
    glyph: SheetGlyph,
    color: Color,
) {
    val w = size.width
    val h = size.height
    val stroke = 1.7.dp.toPx()
    when (glyph) {
        SheetGlyph.PLAY -> drawPath(
            Path().apply {
                moveTo(w * 0.22f, 0f)
                lineTo(w, h / 2f)
                lineTo(w * 0.22f, h)
                close()
            },
            color
        )

        SheetGlyph.SYNC -> {
            drawArc(
                color = color,
                startAngle = -55f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(w * 0.08f, h * 0.08f),
                size = Size(w * 0.84f, h * 0.84f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.62f, 0f)
                    lineTo(w * 0.98f, h * 0.12f)
                    lineTo(w * 0.64f, h * 0.32f)
                    close()
                },
                color
            )
        }

        SheetGlyph.RENAME -> {
            drawLine(
                color,
                Offset(w * 0.20f, h * 0.80f),
                Offset(w * 0.80f, h * 0.20f),
                stroke * 1.5f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.08f, h * 0.92f)
                    lineTo(w * 0.30f, h * 0.84f)
                    lineTo(w * 0.16f, h * 0.70f)
                    close()
                },
                color
            )
        }

        SheetGlyph.DELETE -> {
            drawLine(
                color,
                Offset(w * 0.14f, h * 0.24f),
                Offset(w * 0.86f, h * 0.24f),
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color,
                Offset(w * 0.38f, h * 0.12f),
                Offset(w * 0.62f, h * 0.12f),
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.22f, h * 0.32f),
                size = Size(w * 0.56f, h * 0.56f),
                cornerRadius = CornerRadius(2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            drawLine(color, Offset(w * 0.42f, h * 0.46f), Offset(w * 0.42f, h * 0.74f), stroke * 0.8f)
            drawLine(color, Offset(w * 0.58f, h * 0.46f), Offset(w * 0.58f, h * 0.74f), stroke * 0.8f)
        }
    }
}

/** 沉浸式输入弹窗。 */
@Composable
private fun ImmersiveInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .liquidGlass(
                shape = RoundedCornerShape(26.dp),
                blurRadius = 30.dp,
                elevation = 22.dp
            )
            .clickable(enabled = false) {}
            .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BasicText(title, style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(12.dp),
                        blurRadius = 14.dp,
                        elevation = 0.dp
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            BasicText(placeholder, style = TextStyle(color = TextDim, fontSize = 13.sp))
                        }
                        inner()
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(confirmText, highlighted = text.isNotBlank()) {
                    if (text.isNotBlank()) onConfirm(text)
                }
                Pill("取消") { onDismiss() }
            }
        }
}

/** 沉浸式确认弹窗。 */
@Composable
private fun ImmersiveConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .liquidGlass(
                shape = RoundedCornerShape(26.dp),
                blurRadius = 30.dp,
                elevation = 22.dp
            )
            .clickable(enabled = false) {}
            .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BasicText(title, style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            BasicText(message, style = TextStyle(color = TextMuted, fontSize = 12.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(confirmText, highlighted = true) { onConfirm() }
                Pill("取消") { onDismiss() }
            }
        }
}

// ======================================================================
// 沉浸式设置页
// ======================================================================

/** 沉浸式「关于」页：版本、开源地址、官网，全部自绘。 */
@Composable
fun ImmersiveAboutScreen(
    checking: Boolean = false,
    checkResult: String? = null,
    onCheckUpdate: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    LiquidGlassScaffold {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        @Composable
        fun Header() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    ImmersiveChip("← 返回", onBack)
                    Spacer(Modifier.width(10.dp))
                }
                BasicText(
                    "关于",
                    style = TextStyle(color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        @Composable
        fun IdentitySection() {
            BasicText(
                "BiliPlayer",
                style = TextStyle(color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            )
            BasicText(
                "用无头浏览器实现的 B 站音乐播放器",
                style = TextStyle(color = TextMuted, fontSize = 13.sp)
            )
            BasicText(
                "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = TextStyle(color = TextDim, fontSize = 12.sp)
            )
        }

        @Composable
        fun DiagnosticsSection() {
            ImmersiveDivider()
            ImmersiveSection("更新与诊断")
            ImmersiveRow(
                "检查更新",
                checkResult ?: "手动检查是否有新版本",
                {
                    Pill(
                        if (checking) "检查中…" else "检查",
                        enabled = !checking,
                        onClick = onCheckUpdate
                    )
                }
            )
            ImmersiveRow(
                "运行日志",
                "查看抓取与播放日志",
                { Pill("打开", onClick = onOpenLogs) }
            )
        }

        @Composable
        fun LyricsSection() {
            ImmersiveDivider()
            ImmersiveSection("歌词")
            BasicText(
                "歌词由第三方歌词库 lrclib.net 提供，本软件不生产、不审核，也不承担由此产生的法律责任。",
                style = TextStyle(color = TextMuted, fontSize = 11.sp)
            )
        }

        @Composable
        fun LinksSection() {
            ImmersiveDivider()
            ImmersiveSection("链接")
            listOf(
                "官网 / 下载" to "https://apps.netessx.com/BiliPlayerAndroid",
                "GitHub 仓库" to "https://github.com/Gingmzmzx/BiliPlayer-android",
                "上游桌面版" to "https://github.com/Gingmzmzx/BiliPlayer"
            ).forEach { (label, url) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .liquidPress(onClick = { open(url) })
                        .liquidGlass(
                            shape = RoundedCornerShape(18.dp),
                            blurRadius = 18.dp,
                            elevation = 4.dp
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicText(label, style = TextStyle(color = TextPrimary, fontSize = 14.sp))
                    BasicText(url, style = TextStyle(color = Accent, fontSize = 11.sp))
                }
            }
        }

        if (landscape) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Header()
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IdentitySection()
                        DiagnosticsSection()
                        Spacer(Modifier.height(LocalImmersiveBottomSpace.current))
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LyricsSection()
                        LinksSection()
                        Spacer(Modifier.height(LocalImmersiveBottomSpace.current))
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Header()
                IdentitySection()
                LyricsSection()
                DiagnosticsSection()
                LinksSection()
                Spacer(Modifier.height(LocalImmersiveBottomSpace.current + 24.dp))
            }
        }
    }
}

@Composable
fun ImmersiveSettingsScreen(
    immersiveMode: Boolean,
    onImmersiveModeChange: (Boolean) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf(Preferences.lrcLibBaseUrl(context)) }
    var font by remember { mutableStateOf(LyricsOverlay.currentFontSize()) }
    var locked by remember { mutableStateOf(LyricsOverlay.isLocked()) }
    var desktop by remember { mutableStateOf(Preferences.desktopLyrics(context)) }
    // 权限状态每次组合时重读：从系统设置回来后能立刻反映
    val canOverlay = Settings.canDrawOverlays(context)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(Backup.export(context).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            backupMsg = if (ok) "已导出偏好与歌词" else "导出失败"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: return@runCatching null
                    Backup.import(context, text)
                }.getOrNull()
            }
            backupMsg = if (result != null)
                "已导入 ${result.preferences} 条偏好、${result.lyrics} 条歌词"
            else "导入失败：文件格式不支持"
        }
    }

    LiquidGlassScaffold {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        @Composable
        fun Header() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    ImmersiveChip("← 返回", onBack)
                    Spacer(Modifier.width(10.dp))
                }
                BasicText(
                    "设置",
                    style = TextStyle(color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        @Composable
        fun GeneralSection() {
            ImmersiveRow(
                "沉浸式界面",
                "播放器 / 统计 / 设置全部切换为无 Material 的沉浸版",
                { TogglePill(immersiveMode, onImmersiveModeChange) }
            )
            ImmersiveDivider()

            ImmersiveSection("歌词来源")
            BasicText(
                "歌词由 lrclib.net 提供，可换自建镜像；搜索路径为 {地址}/search。",
                style = TextStyle(color = TextMuted, fontSize = 11.sp)
            )
            BasicTextField(
                value = base,
                onValueChange = { base = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(12.dp),
                        blurRadius = 14.dp,
                        elevation = 0.dp
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("保存") {
                    Preferences.saveLrcLibBaseUrl(context, base)
                    LrcLib.init(context)
                    base = Preferences.lrcLibBaseUrl(context)
                }
                Pill("恢复默认") {
                    base = Preferences.DEFAULT_LRCLIB_BASE
                    Preferences.saveLrcLibBaseUrl(context, base)
                    LrcLib.init(context)
                }
            }
            ImmersiveDivider()
        }

        @Composable
        fun DesktopSection() {
            ImmersiveSection("桌面歌词")
            ImmersiveRow(
                "显示悬浮窗",
                if (canOverlay) "在其他应用上层实时显示当前歌词" else "需要「显示在其他应用上层」权限",
                {
                    TogglePill(desktop && canOverlay) { on ->
                        if (on && !Settings.canDrawOverlays(context)) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        } else {
                            desktop = on
                            PlayerController.setDesktopLyrics(on)
                        }
                    }
                }
            )
            if (!canOverlay) {
                Pill("去授予悬浮窗权限") {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            } else if (desktop) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicText("字号", style = TextStyle(color = TextMuted, fontSize = 12.sp))
                    Pill("A-") {
                        font = (font - 2f).coerceAtLeast(10f)
                        LyricsOverlay.setFontSize(font)
                    }
                    BasicText("${font.toInt()}", style = TextStyle(color = TextPrimary, fontSize = 12.sp))
                    Pill("A+") {
                        font = (font + 2f).coerceAtMost(40f)
                        LyricsOverlay.setFontSize(font)
                    }
                    Pill("重置位置") { LyricsOverlay.resetPosition() }
                }
                ImmersiveRow(
                    "锁定位置",
                    "锁定后不可拖动，也不会挡住下层的点击",
                    {
                        TogglePill(locked) {
                            locked = it
                            LyricsOverlay.setLocked(it)
                        }
                    }
                )
                BasicText(
                    "桌面上单指拖动改位置，双指捏合改字号",
                    style = TextStyle(color = TextDim, fontSize = 10.sp)
                )
            }
            ImmersiveDivider()
        }

        @Composable
        fun BackupSection() {
            ImmersiveSection("备份与恢复")
            BasicText(
                "导出/导入所有歌曲的单曲偏好与歌词缓存（JSON），换机或重装时用。",
                style = TextStyle(color = TextMuted, fontSize = 11.sp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("导出") { exportLauncher.launch(Backup.SUGGESTED_NAME) }
                Pill("导入") {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
            backupMsg?.let {
                BasicText(it, style = TextStyle(color = Accent, fontSize = 11.sp))
            }
            Spacer(Modifier.height(16.dp))
        }

        if (landscape) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Header()
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GeneralSection()
                        Spacer(Modifier.height(LocalImmersiveBottomSpace.current))
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DesktopSection()
                        BackupSection()
                        Spacer(Modifier.height(LocalImmersiveBottomSpace.current))
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Header()
                GeneralSection()
                DesktopSection()
                BackupSection()
                Spacer(Modifier.height(LocalImmersiveBottomSpace.current + 24.dp))
            }
        }
    }
}

// ======================================================================
// 沉浸式歌词弹窗：搜索 / 应用 / 微调偏移 / 粘贴 LRC
// ======================================================================

@Composable
fun ImmersiveLyricsDialog(
    uiState: PlayerUiState,
    entry: LyricsEntry?,
    onSearch: (String) -> Unit,
    onApply: (Int) -> Unit,
    onSave: (String) -> Unit,
    onSeek: (Double) -> Unit,
    onDismiss: () -> Unit,
    haze: HazeState? = null,
) {
    var query by remember(uiState.currentBvid) {
        mutableStateOf(guessSongName(uiState.currentTitle))
    }
    var pasted by remember(uiState.currentBvid) {
        mutableStateOf(entry?.synced.orEmpty())
    }
    var showPaste by remember { mutableStateOf(false) }
    val lines = remember(entry?.bvid, entry?.synced, entry?.plain) {
        entry?.lines().orEmpty()
    }
    val synced = entry?.hasSyncedLines == true
    val active = remember(lines, synced, uiState.currentTime, entry?.offsetMs) {
        if (synced) {
            activeLineIndex(lines, uiState.currentTime - (entry?.offsetMs ?: 0L) / 1000.0)
        } else {
            -1
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(active)
    }

    val meta = when {
        entry == null -> "还没有歌词，先搜索或粘贴一份"
        lines.isEmpty() -> "已保存词条，但没有可显示的文本"
        entry.manual -> "手动词条" + if (!synced) "（纯文本，无时间轴）" else ""
        else -> buildString {
            append("已匹配：")
            append(entry.trackName.ifBlank { "未知曲目" })
            append(" - ")
            append(entry.artistName.ifBlank { "未知歌手" })
            if (!synced) append("（纯文本，无时间轴）")
        }
    }

    LiquidGlassOverlay(haze = haze) {
        GlassBottomSheet(
            onDismiss = onDismiss,
            initialFraction = 0.9f
        ) { close ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            "歌词",
                            style = TextStyle(
                                color = TextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        BasicText(
                            "搜索、微调或粘贴 LRC",
                            style = TextStyle(color = TextMuted, fontSize = 11.sp)
                        )
                    }
                    LiquidPill("关闭", onClick = close)
                }

                Spacer(Modifier.height(10.dp))
                BasicText(meta, style = TextStyle(color = Accent, fontSize = 11.sp))
                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (lines.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(GlassSoft)
                                .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                itemsIndexed(lines) { i, line ->
                                    val isActive = i == active
                                    BasicText(
                                        line.text.ifBlank { "♪" },
                                        style = TextStyle(
                                            color = if (isActive) Accent else TextMuted,
                                            fontSize = if (isActive) 16.sp else 13.sp,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            lineHeight = if (isActive) 22.sp else 18.sp,
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (synced) {
                                                    Modifier.clickable {
                                                        onSeek(
                                                            (line.timeMs + (entry?.offsetMs ?: 0L)) / 1000.0
                                                        )
                                                    }
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BasicText("偏移", style = TextStyle(color = TextMuted, fontSize = 11.sp))
                            LiquidPill("提前") { PlayerController.adjustLyricsOffset(-500) }
                            BasicText(
                                "%.1fs".format((entry?.offsetMs ?: 0L) / 1000.0),
                                style = TextStyle(color = TextPrimary, fontSize = 11.sp)
                            )
                            LiquidPill("延后") { PlayerController.adjustLyricsOffset(500) }
                            LiquidPill("归零") {
                                PlayerController.adjustLyricsOffset(-(entry?.offsetMs ?: 0L))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    } else {
                        BasicText(
                            "没有找到歌词。可以改一下歌名重新搜索，或直接粘贴带时间轴的歌词（LRC）。",
                            style = TextStyle(color = TextMuted, fontSize = 12.sp)
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    BasicText(
                        "搜索",
                        style = TextStyle(
                            color = TextMuted,
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    BareField("歌名 / 歌手", query) { query = it }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidPill(
                            if (uiState.lyricsSearching) "搜索中…" else "搜索歌词",
                            highlighted = uiState.lyricsSearching ||
                                (query.isNotBlank() && !uiState.lyricsSearching),
                            enabled = !uiState.lyricsSearching && query.isNotBlank()
                        ) { onSearch(query) }
                        LiquidPill(if (showPaste) "收起粘贴" else "粘贴歌词") {
                            showPaste = !showPaste
                        }
                    }

                    if (uiState.lyricsSearchResults.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        BasicText(
                            "选择歌词版本",
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        uiState.lyricsSearchResults.forEachIndexed { i, r ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(GlassSoft)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                    .liquidPress(onClick = { onApply(i) })
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                BasicText(
                                    r.trackName.ifBlank { "(无标题)" },
                                    style = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                BasicText(
                                    buildString {
                                        append(r.artistName.ifBlank { "未知歌手" })
                                        append(if (r.hasSyncedLines) " · 同步歌词" else " · 纯文本")
                                        if (r.plain.isNotBlank() && !r.hasSyncedLines) {
                                            append("（无时间轴）")
                                        }
                                    },
                                    style = TextStyle(color = TextMuted, fontSize = 10.sp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    if (showPaste) {
                        Spacer(Modifier.height(14.dp))
                        BasicText(
                            "粘贴歌词",
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        BasicTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                            cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlass(
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 16.dp,
                                    elevation = 0.dp
                                )
                                .padding(12.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (pasted.isEmpty()) {
                                        BasicText(
                                            "LRC / 纯文本歌词",
                                            style = TextStyle(color = TextDim, fontSize = 12.sp)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiquidPill(
                                "保存",
                                highlighted = pasted.isNotBlank(),
                                enabled = pasted.isNotBlank()
                            ) { onSave(pasted) }
                            LiquidPill("清除") {
                                pasted = ""
                                onSave("")
                            }
                        }
                    }

                    uiState.lyricsStatus?.let {
                        Spacer(Modifier.height(10.dp))
                        BasicText(it, style = TextStyle(color = Accent, fontSize = 11.sp))
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ======================================================================
// 沉浸式运行日志
// ======================================================================

@Composable
fun ImmersiveLogScreen(uiState: PlayerUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    LaunchedEffect(uiState.logLines.size) {
        if (uiState.logLines.isNotEmpty()) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    fun exportPayload(): String = buildString {
        appendLine("BiliPlayer v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("导出时间：${fmtDate(System.currentTimeMillis())}")
        appendLine("日志行数：${uiState.logLines.size}")
        appendLine()
        if (uiState.logLines.isEmpty()) {
            appendLine("(空)")
        } else {
            appendLine(uiState.logLines.joinToString("\n"))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = exportPayload()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            exportMsg = if (ok) "日志已导出" else "导出失败"
        }
    }

    LiquidGlassScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImmersiveChip("← 返回", onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "运行日志",
                        style = TextStyle(color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    )
                    BasicText(
                        "抓取 / 播放 / 错误",
                        style = TextStyle(color = TextMuted, fontSize = 11.sp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Pill(
                    "导出",
                    enabled = uiState.logLines.isNotEmpty(),
                    onClick = {
                        val stamp = java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmmss",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        exportLauncher.launch("BiliPlayer-log-$stamp.txt")
                    }
                )
            }

            exportMsg?.let {
                Spacer(Modifier.height(8.dp))
                BasicText(it, style = TextStyle(color = Accent, fontSize = 11.sp))
            }

            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxSize()
                    .liquidGlass(
                        shape = RoundedCornerShape(24.dp),
                        blurRadius = 26.dp,
                        elevation = 12.dp
                    )
            ) {
                val lines = uiState.logLines
                BasicText(
                    text = if (lines.isEmpty()) "暂无日志" else lines.joinToString("\n"),
                    style = TextStyle(
                        color = if (lines.isEmpty()) TextDim else TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(14.dp)
                )
            }
        }
    }
}

// ======================================================================
// 沉浸式更新 / 公告 / 下载弹窗
// ======================================================================

@Composable
fun ImmersiveUpdateDialog(
    info: ReleaseInfo,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    onDownload: () -> Unit,
    haze: HazeState? = null,
) {
    LiquidGlassOverlay(haze = haze) {
        GlassDialog(
            onDismiss = onLater,
            dismissOnOutside = !info.forceUpdate,
            backEnabled = !info.forceUpdate
        ) { _ ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        blurRadius = 32.dp,
                        elevation = 22.dp
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            "发现新版本",
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp
                            )
                        )
                        BasicText(
                            "v${info.versionName}",
                            style = TextStyle(
                                color = TextPrimary,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    if (info.forceUpdate) {
                        Box(
                            Modifier
                                .clip(GlassCapsule)
                                .background(Accent.copy(alpha = 0.18f))
                                .border(1.dp, Accent.copy(alpha = 0.38f), GlassCapsule)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            BasicText(
                                "强制更新",
                                style = TextStyle(
                                    color = Accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }

                BasicText(
                    buildString {
                        append("版本号 ${info.versionCode}")
                        val size = fmtBytes(info.size)
                        if (size.isNotEmpty()) append(" · $size")
                        if (info.timestamp > 0) append(" · ${fmtDate(info.timestamp)}")
                    },
                    style = TextStyle(color = TextDim, fontSize = 11.sp)
                )

                if (info.forceUpdate) {
                    BasicText(
                        "此为强制更新，安装后才能继续使用",
                        style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 11.sp)
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    BasicText(
                        info.desc.ifBlank { "本次更新没有提供说明" },
                        style = TextStyle(
                            color = TextMuted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    )
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidPill("下载更新", highlighted = true) { onDownload() }
                    Spacer(Modifier.weight(1f))
                    if (!info.forceUpdate) {
                        LiquidPill("忽略此版本") { onIgnore() }
                    }
                    LiquidPill("稍后") { onLater() }
                }
            }
        }
    }
}

@Composable
fun ImmersiveNoticeDialog(
    info: NoticeInfo,
    onDismiss: () -> Unit,
    haze: HazeState? = null,
) {
    LiquidGlassOverlay(haze = haze) {
        GlassDialog(onDismiss = onDismiss) { close ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        blurRadius = 32.dp,
                        elevation = 22.dp
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BasicText(
                    info.title.ifBlank { "公告" },
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 26.sp
                    )
                )
                if (info.timestamp > 0) {
                    BasicText(
                        fmtDate(info.timestamp),
                        style = TextStyle(color = TextDim, fontSize = 10.sp)
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ImmersiveHtmlText(info.content)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    LiquidPill("知道了", highlighted = true) { close() }
                }
            }
        }
    }
}

@Composable
fun ImmersiveDownloadDialog(progress: Int, haze: HazeState? = null) {
    LiquidGlassOverlay(haze = haze) {
        GlassDialog(
            onDismiss = {},
            dismissOnOutside = false,
            backEnabled = false
        ) { _ ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(28.dp),
                        blurRadius = 30.dp,
                        elevation = 20.dp
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BasicText(
                    "正在下载更新",
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                BasicText(
                    "请保持应用在前台",
                    style = TextStyle(color = TextMuted, fontSize = 11.sp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(GlassCapsule)
                        .background(GlassBorder)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((progress / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(GlassCapsule)
                            .background(Accent)
                    )
                }
                BasicText(
                    "$progress%",
                    style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

/** 公告里的简单 HTML，用平台解析，不执行脚本、不加载远程资源。 */
@Composable
private fun ImmersiveHtmlText(html: String, modifier: Modifier = Modifier) {
    val contentColor = TextPrimary.toArgb()
    val linkColor = Accent.toArgb()
    val source = remember(html) {
        if (html.contains('<')) html else html.replace("\n", "<br>")
    }
    val spanned = remember(source) {
        HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(contentColor)
                setLinkTextColor(linkColor)
                textSize = 13f
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = {
            it.setTextColor(contentColor)
            it.setLinkTextColor(linkColor)
            it.text = spanned
        }
    )
}

private fun fmtBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}

/** 沉浸式歌词来源免责声明（首次启动）。 */
@Composable
fun ImmersiveLyricsDisclaimerDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    LiquidGlassOverlay(haze = null) {
        GlassDialog(onDismiss = onDisagree) { _ ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        blurRadius = 32.dp,
                        elevation = 22.dp
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BasicText(
                    "歌词来源与免责声明",
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "本应用的歌词由第三方歌词库 lrclib.net 按其公开接口检索得到。",
                            "• 歌词内容由 lrclib.net 及其贡献者提供。本软件不生产、不存储、不审核歌词，与 lrclib.net 也没有任何隶属或合作关系。",
                            "• 歌词的准确性、完整性与合法性由来源方负责。歌词可能错漏或与音频不同步，你可以用偏移微调，但请自行判断是否可信。",
                            "• 本软件及开发者不对使用歌词产生的任何直接或间接后果承担法律责任。如不同意，请勿使用歌词相关功能。",
                            "• 你也可以在歌词弹窗里改用自建接口地址，或直接粘贴自有歌词。",
                            "点击「同意并继续」表示你已阅读并接受上述说明。"
                        ).forEach { text ->
                            BasicText(
                                text,
                                style = TextStyle(
                                    color = if (text.startsWith("点击")) TextDim else TextMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidPill("同意并继续", highlighted = true) { onAgree() }
                    LiquidPill("不同意") { onDisagree() }
                }
            }
        }
    }
}
