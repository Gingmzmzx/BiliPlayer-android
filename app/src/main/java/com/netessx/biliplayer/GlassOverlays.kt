package com.netessx.biliplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 沉浸式 UI 的浮层框架。
 *
 * 所有弹窗 / 底部抽屉 / 侧边抽屉共用这里的进出动画、遮罩、返回键与关闭回调，
 * 具体内容只负责排版，不再自己写 AnimatedVisibility。
 */

internal object GlassMotion {
    const val ExitMs = 160
    const val EnterMs = 200
    val SheetSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
}

/** 当前页面打开了多少个浮层；底部导航据此隐藏，避免压住抽屉。 */
internal class GlassOverlayPresence {
    var count by mutableIntStateOf(0)
}

internal val LocalGlassOverlayPresence = compositionLocalOf { GlassOverlayPresence() }

@Composable
private fun TrackOverlayPresence() {
    val presence = LocalGlassOverlayPresence.current
    DisposableEffect(Unit) {
        presence.count++
        onDispose { presence.count-- }
    }
}

/**
 * 居中弹窗：淡入 + 轻微放大。
 * [content] 的 `close` 会先播完退场动画再调用调用方的 onDismiss。
 */
@Composable
internal fun GlassDialog(
    onDismiss: () -> Unit,
    dismissOnOutside: Boolean = true,
    backEnabled: Boolean = true,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    TrackOverlayPresence()
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            shown = false
            scope.launch {
                delay(GlassMotion.ExitMs.toLong())
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { shown = true }
    BackHandler(enabled = backEnabled) { close() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(GlassMotion.ExitMs))
        ) {
            LiquidScrim(if (dismissOnOutside) close else null)
        }
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(GlassMotion.EnterMs)) + scaleIn(initialScale = 0.93f),
            exit = fadeOut(tween(GlassMotion.ExitMs)) + scaleOut(targetScale = 0.95f)
        ) {
            content(close)
        }
    }
}

/**
 * 底部抽屉：滑入 / 滑出，顶部握把可上下拖动改变高度，拖到顶部即全屏。
 *
 * [initialFraction] 是打开时的默认高度；[minFraction] 是最低高度；
 * 高度由框架控制，内容只需要按可用高度排版（使用 `weight` 或内部滚动）。
 */
@Composable
internal fun GlassBottomSheet(
    onDismiss: () -> Unit,
    initialFraction: Float = 0.62f,
    minFraction: Float = 0.32f,
    maxFraction: Float = 1f,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    TrackOverlayPresence()
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            shown = false
            scope.launch {
                delay(GlassMotion.ExitMs.toLong())
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { shown = true }
    BackHandler { close() }

    var fraction by remember { mutableFloatStateOf(initialFraction) }
    var dragging by remember { mutableStateOf(false) }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging) snap() else GlassMotion.SheetSpring,
        label = "glassSheetFraction"
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val statusTop = with(density) {
            WindowInsets.statusBars.getTop(this).toFloat() + 8.dp.toPx()
        }
        val upper = minOf(maxFraction, ((maxH - statusTop) / maxH).coerceAtLeast(minFraction))

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(GlassMotion.ExitMs))
        ) {
            LiquidScrim(if (dismissOnOutside) close else null)
        }

        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)
            ) + fadeIn(tween(120)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(GlassMotion.ExitMs)
            ) + fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedFraction.coerceIn(minFraction, upper))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        blurRadius = 32.dp,
                        elevation = 20.dp,
                        // 抽屉内容密，稍微提高不透明度避免背景太乱
                        tintAlpha = 1.65f
                    )
            ) {
                // 拖动区：整条顶部都可以拖
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .pointerInput(upper, minFraction) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    dragging = false
                                    fraction = snapFraction(fraction, minFraction, upper)
                                },
                                onDragCancel = {
                                    dragging = false
                                    fraction = snapFraction(fraction, minFraction, upper)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                fraction = (fraction - dragAmount / maxH)
                                    .coerceIn(minFraction, upper)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .background(
                                Color.White.copy(
                                    alpha = if (immersiveColors.value.dark) 0.34f else 0.72f
                                ),
                                RoundedCornerShape(percent = 50)
                            )
                    )
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    content(close)
                }
            }
        }
    }
}

/** 右侧抽屉：滑入 / 滑出；内容自行滚动，框架负责安全区。 */
@Composable
internal fun GlassSideDrawer(
    onDismiss: () -> Unit,
    dismissOnOutside: Boolean = true,
    width: Dp = 280.dp,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    TrackOverlayPresence()
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            shown = false
            scope.launch {
                delay(GlassMotion.ExitMs.toLong())
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { shown = true }
    BackHandler { close() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(GlassMotion.ExitMs))
        ) {
            LiquidScrim(if (dismissOnOutside) close else null)
        }
        AnimatedVisibility(
            visible = shown,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)
            ) + fadeIn(tween(120)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(GlassMotion.ExitMs)
            ) + fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                Modifier
                    .padding(end = 10.dp, top = 10.dp, bottom = 10.dp)
                    .width(width)
                    .fillMaxHeight()
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        blurRadius = 30.dp,
                        elevation = 18.dp,
                        tintAlpha = 1.6f
                    )
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 8.dp)
            ) {
                content(close)
            }
        }
    }
}

/**
 * 右侧悬浮小面板：固定尺寸，从右边滑入，不是全高抽屉。
 * 用于音量条这类轻量工具。
 */
@Composable
internal fun GlassSidePanel(
    onDismiss: () -> Unit,
    width: Dp,
    height: Dp,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    TrackOverlayPresence()
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            shown = false
            scope.launch {
                delay(GlassMotion.ExitMs.toLong())
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { shown = true }
    BackHandler { close() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(GlassMotion.ExitMs))
        ) {
            LiquidScrim(if (dismissOnOutside) close else null)
        }
        AnimatedVisibility(
            visible = shown,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)
            ) + fadeIn(tween(120)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(GlassMotion.ExitMs)
            ) + fadeOut(tween(100)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            Column(
                Modifier
                    .width(width)
                    .height(height)
                    .liquidGlass(
                        shape = RoundedCornerShape(28.dp),
                        blurRadius = 28.dp,
                        elevation = 16.dp,
                        tintAlpha = 1.6f
                    )
            ) {
                content()
            }
        }
    }
}

private fun snapFraction(value: Float, min: Float, max: Float): Float = when {
    value >= max - 0.08f -> max
    value <= min + 0.05f -> min
    else -> value
}
