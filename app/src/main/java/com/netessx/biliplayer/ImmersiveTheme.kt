package com.netessx.biliplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * 沉浸式 UI 的主题层。只放颜色与明暗切换，不依赖任何页面。
 */

/** 沉浸界面的一整套配色，明暗两套随系统切换。 */
internal data class ImmersiveColors(
    val dark: Boolean,
    val ink: Color,
    val gradientTop: Color,
    val gradientBottom: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,
    val glass: Color,
    val glassBorder: Color,
    val glassSoft: Color,
    /** 旧系统没有 RenderEffect 时，玻璃面板退回的实心底色。 */
    val glassFallback: Color,
    /** 抽屉 / 弹窗的实心面板底色。 */
    val panel: Color,
    val panelAlt: Color,
) {
    companion object {
        val Dark = ImmersiveColors(
            dark = true,
            ink = Color(0xFF07080C),
            gradientTop = Color(0xFF141726),
            gradientBottom = Color(0xFF07080C),
            textPrimary = Color(0xFFF2F4FA),
            textMuted = Color(0x99F2F4FA),
            textDim = Color(0x59F2F4FA),
            glass = Color(0x1CFFFFFF),
            glassBorder = Color(0x2EFFFFFF),
            glassSoft = Color(0x12FFFFFF),
            glassFallback = Color(0xD9141720),
            panel = Color(0xF2131620),
            panelAlt = Color(0xF21A1D26),
        )
        val Light = ImmersiveColors(
            dark = false,
            ink = Color(0xFFF6F4F8),
            gradientTop = Color(0xFFFFFFFF),
            gradientBottom = Color(0xFFEFECF3),
            textPrimary = Color(0xFF1B1720),
            textMuted = Color(0x991B1720),
            textDim = Color(0x551B1720),
            glass = Color(0x52FFFFFF),
            glassBorder = Color(0x3DFFFFFF),
            glassSoft = Color(0x26FFFFFF),
            glassFallback = Color(0xEFFFFFFF),
            panel = Color(0xFAFFFFFF),
            panelAlt = Color(0xFAF7F4FA),
        )
    }
}

/** 由 BiliPlayerApp 按系统明暗写入；读它的 composable 会自动重组。 */
internal val immersiveColors = mutableStateOf(ImmersiveColors.Dark)

fun setImmersiveDarkTheme(dark: Boolean) {
    immersiveColors.value = if (dark) ImmersiveColors.Dark else ImmersiveColors.Light
}

internal val Ink: Color @Composable get() = immersiveColors.value.ink
internal val GradientTop: Color @Composable get() = immersiveColors.value.gradientTop
internal val GradientBottom: Color @Composable get() = immersiveColors.value.gradientBottom
internal val TextPrimary: Color @Composable get() = immersiveColors.value.textPrimary
internal val TextMuted: Color @Composable get() = immersiveColors.value.textMuted
internal val TextDim: Color @Composable get() = immersiveColors.value.textDim
internal val Glass: Color @Composable get() = immersiveColors.value.glass
internal val GlassBorder: Color @Composable get() = immersiveColors.value.glassBorder
internal val GlassSoft: Color @Composable get() = immersiveColors.value.glassSoft
internal val Panel: Color @Composable get() = immersiveColors.value.panel
internal val PanelAlt: Color @Composable get() = immersiveColors.value.panelAlt

/** 固定的品牌粉，明暗通用。 */
internal val Accent = Color(0xFFFB7299)

/** 品牌粉上的前景色：固定深色，浅色主题下也能保证对比。 */
internal val OnAccent = Color(0xFF230A12)

/** Canvas（DrawScope）里不能调用 @Composable getter，图标统一用中性灰。 */
internal val Glyph = Color(0xFF8E93A0)
