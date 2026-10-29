package com.netessx.biliplayer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.max

/**
 * 沉浸式页面共用的 Liquid Glass 底座。
 *
 * 视觉参考 iOS 26 Liquid Glass：
 *  - 真实背景模糊（Haze；Android 12+ 走 RenderEffect，旧系统退化为半透明底）
 *  - 乳白玻璃染色 + 对角高光 + 上下亮度不同的镜面描边
 *  - 悬浮元素带柔和投影和按压缩放反馈
 *
 * 只服务沉浸式界面；Material Design 页面完全不受影响。
 */

/** 当前页面的背景捕获状态，供玻璃面板采样。 */
internal val LocalLiquidGlass = compositionLocalOf<HazeState?> { null }

/** 玻璃形状默认圆角；比 Material 的 16dp 更大，接近 iOS 的连续圆角观感。 */
internal val GlassShape = RoundedCornerShape(28.dp)
internal val GlassCapsule = RoundedCornerShape(percent = 50)

/**
 * 沉浸式页面根容器：铺好氛围背景，并把 Haze 状态提供给子树里的玻璃面板。
 */
@Composable
internal fun rememberLiquidGlassState(): HazeState = rememberHazeState()

@Composable
internal fun LiquidGlassScaffold(
    cover: String = "",
    haze: HazeState? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // 页面可以自带状态：歌词等覆盖弹层需要复用播放页已经捕获的背景
    val fallback = rememberHazeState()
    val state = haze ?: fallback
    CompositionLocalProvider(LocalLiquidGlass provides state) {
        Box(modifier.fillMaxSize().background(Ink)) {
            LiquidBackdrop(cover = cover, haze = state)
            content()
        }
    }
}

/** 透明玻璃浮层：不铺背景，叠在已有页面上，复用其 Haze 状态。 */
@Composable
internal fun LiquidGlassOverlay(
    haze: HazeState? = LocalLiquidGlass.current,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalLiquidGlass provides haze) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/**
 * 背景层：封面模糊 → 明暗遮罩 → 粉 / 紫 / 青三层氛围光 → 顶部反光与底部暗角。
 * 没有封面时只剩氛围光，不会露出纯黑。
 */
@Composable
internal fun LiquidBackdrop(
    cover: String,
    haze: HazeState,
    modifier: Modifier = Modifier,
) {
    val palette = immersiveColors.value
    val scrim = if (palette.dark) Color.Black else Color.White
    val pink = Accent.copy(alpha = if (palette.dark) 0.30f else 0.22f)
    val violet = Color(0xFF7B61FF).copy(alpha = if (palette.dark) 0.26f else 0.18f)
    val cyan = Color(0xFF3DD6FF).copy(alpha = if (palette.dark) 0.20f else 0.14f)

    Box(modifier.fillMaxSize().hazeSource(haze)) {
        CoverBitmap(
            url = cover,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.18f
                    scaleY = 1.18f
                }
                .blur(42.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        scrim.copy(alpha = if (palette.dark) 0.52f else 0.56f),
                        scrim.copy(alpha = if (palette.dark) 0.58f else 0.62f),
                        scrim.copy(alpha = if (palette.dark) 0.70f else 0.72f),
                    )
                )
            )
        )
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val radius = max(w, h)
            drawRect(
                Brush.radialGradient(
                    colors = listOf(pink, Color.Transparent),
                    center = Offset(w * 0.10f, h * 0.08f),
                    radius = radius * 0.85f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(violet, Color.Transparent),
                    center = Offset(w * 0.92f, h * 0.34f),
                    radius = radius * 0.72f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(cyan, Color.Transparent),
                    center = Offset(w * 0.18f, h * 0.92f),
                    radius = radius * 0.78f
                )
            )
            // 顶部环境反光：玻璃厚度的来源之一
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (palette.dark) 0.10f else 0.22f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = h * 0.42f
                )
            )
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = if (palette.dark) 0.30f else 0.10f)
                    ),
                    startY = h * 0.62f,
                    endY = h
                )
            )
        }
    }
}

/**
 * 玻璃修饰符：模糊 + 染色 + 镜面描边 + 对角高光。
 *
 * 必须放在背景的 [hazeSource] 子树之外使用；[haze] 为 null 时退化为半透明实心底。
 */
@Composable
internal fun Modifier.liquidGlass(
    haze: HazeState? = LocalLiquidGlass.current,
    shape: Shape = GlassShape,
    blurRadius: Dp = 26.dp,
    tint: Color = Color.Unspecified,
    tintAlpha: Float = 1f,
    fallbackAlpha: Float = 1f,
    elevation: Dp = 10.dp,
    specular: Float = 1f,
): Modifier {
    val palette = immersiveColors.value
    val fill = if (tint.isSpecified) tint else palette.glass
    // 旧系统没有真实模糊：把染色按比例混进实心底，强调色按钮不至于退回深灰
    val fallbackFill = if (tint.isSpecified) {
        lerp(
            palette.glassFallback,
            tint.copy(alpha = 1f),
            (tint.alpha * 1.6f).coerceIn(0f, 1f)
        )
    } else {
        palette.glassFallback
    }
    val ambient = Color.Black.copy(alpha = if (palette.dark) 0.34f else 0.10f)

    var glass = this
    if (elevation > 0.dp) {
        glass = glass.shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = ambient,
            spotColor = ambient
        )
    }
    glass = glass.clip(shape)
    glass = if (haze != null) {
        glass.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tint = HazeTint(fill.copy(alpha = fill.alpha * tintAlpha)),
                blurRadius = blurRadius,
                noiseFactor = 0.035f,
                fallbackTint = HazeTint(
                    fallbackFill.copy(alpha = fallbackFill.alpha * fallbackAlpha)
                ),
            )
        )
    } else {
        glass.background(fallbackFill.copy(alpha = fallbackFill.alpha * fallbackAlpha), shape)
    }
    return glass.drawWithContent {
        drawGlassFinish(shape, palette, specular)
        drawContent()
    }
}

/** 镜面高光 + 渐变描边；画在模糊层之上、内容之下。 */
private fun DrawScope.drawGlassFinish(shape: Shape, palette: ImmersiveColors, intensity: Float) {
    if (intensity <= 0f) return
    val path = outlinePath(shape, size)
    val light = if (palette.dark) 1f else 0.62f

    // 对角乳白高光，模拟玻璃折射环境光
    drawPath(
        path,
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.16f * intensity * light),
                Color.Transparent,
                Color.Transparent,
                Color.White.copy(alpha = 0.05f * intensity)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.92f, size.height)
        )
    )

    // 左上亮、右下暗的镜面描边，是 Liquid Glass 最关键的轮廓
    drawPath(
        path,
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = (if (palette.dark) 0.72f else 0.92f) * intensity),
                Color.White.copy(alpha = 0.10f * intensity),
                Color.White.copy(alpha = (if (palette.dark) 0.40f else 0.58f) * intensity)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        ),
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun DrawScope.outlinePath(shape: Shape, size: Size): Path {
    val outline = shape.createOutline(size, layoutDirection, this)
    return when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }
}

/** 玻璃卡片：模糊背景 + 内边距，内容放在 [content]。 */
@Composable
internal fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    blurRadius: Dp = 26.dp,
    tint: Color = Color.Unspecified,
    elevation: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .liquidGlass(
                shape = shape,
                blurRadius = blurRadius,
                tint = tint,
                elevation = elevation
            )
            .padding(contentPadding),
        content = content
    )
}

/** 胶囊按钮：默认乳白玻璃，[highlighted] 时用品牌粉玻璃强调。 */
@Composable
internal fun LiquidPill(
    text: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .liquidPress(onClick, enabled = enabled)
            .liquidGlass(
                shape = GlassCapsule,
                blurRadius = 18.dp,
                tint = if (highlighted) Accent.copy(alpha = 0.72f) else Color.Unspecified,
                elevation = 5.dp
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = when {
                    highlighted -> OnAccent
                    !enabled -> TextDim
                    else -> TextPrimary
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp
            )
        )
    }
}

/** 圆形玻璃按钮；[content] 里放自绘图标。 */
@Composable
internal fun LiquidCircleButton(
    size: Dp = 48.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .liquidPress(onClick)
            .liquidGlass(shape = CircleShape, blurRadius = 20.dp, elevation = 8.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 小圆角图标块（菜单项左侧）。 */
@Composable
internal fun LiquidIconTile(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(38.dp)
            .liquidGlass(shape = RoundedCornerShape(13.dp), blurRadius = 16.dp, elevation = 0.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 按压时轻微收缩；比 Material ripple 更接近 iOS 的物理反馈。 */
@Composable
internal fun Modifier.liquidPress(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressedScale: Float = 0.955f,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 720f),
        label = "liquidPress"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/** 弹层遮罩：不用纯黑，保留背景色彩，符合玻璃质感。 */
@Composable
internal fun LiquidScrim(onClick: (() -> Unit)? = null) {
    val dark = immersiveColors.value.dark
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = if (dark) 0.24f else 0.08f),
                        Color.Black.copy(alpha = if (dark) 0.46f else 0.22f)
                    )
                )
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
    )
}

/** 底部抽屉顶部的小握把。 */
@Composable
internal fun LiquidSheetGrip(modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 38.dp, height = 4.dp)
                .clip(GlassCapsule)
                .background(Color.White.copy(alpha = if (immersiveColors.value.dark) 0.34f else 0.72f))
        )
    }
}
