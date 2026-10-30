package com.netessx.biliplayer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 沉浸式 UI 的通用组件层。
 * 只放可复用控件，不依赖具体页面与业务状态。
 */

/** 控制条上的小胶囊按钮。 */
@Composable
internal fun Pill(
    text: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    LiquidPill(text = text, highlighted = highlighted, enabled = enabled, onClick = onClick)
}

/** 沉浸页顶部 / 子页的返回胶囊。 */
@Composable
internal fun ImmersiveChip(text: String, onClick: () -> Unit) {
    LiquidPill(text, onClick = onClick)
}

/** 只有文字、没有 Material 背景的小按钮。 */
@Composable
internal fun GhostLabel(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = TextMuted, fontSize = 12.sp, letterSpacing = 0.8.sp),
        modifier = Modifier.clickable { onClick() }
    )
}

/** 无 Material 的单行输入框。 */
@Composable
internal fun BareField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
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
                if (value.isEmpty()) {
                    BasicText(placeholder, style = TextStyle(color = TextDim, fontSize = 13.sp))
                }
                inner()
            }
        }
    )
}

/** 带标签的单行输入框。 */
@Composable
internal fun ImmersiveField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = TextStyle(color = TextMuted, fontSize = 11.sp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
            cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(
                    shape = RoundedCornerShape(14.dp),
                    blurRadius = 16.dp,
                    elevation = 0.dp
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
internal fun ImmersiveSection(text: String) {
    Spacer(Modifier.height(10.dp))
    BasicText(
        text,
        style = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    )
}

@Composable
internal fun ImmersiveRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .liquidGlass(
                shape = RoundedCornerShape(18.dp),
                blurRadius = 18.dp,
                elevation = 3.dp
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = TextPrimary, fontSize = 14.sp))
            if (subtitle != null) {
                BasicText(subtitle, style = TextStyle(color = TextMuted, fontSize = 11.sp))
            }
        }
        trailing()
    }
}

@Composable
internal fun ImmersiveDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(GlassBorder)
    )
}

/** 玻璃滑块开关。 */
@Composable
internal fun ImmersiveSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "switchThumb"
    )
    Box(
        Modifier
            .size(width = 52.dp, height = 32.dp)
            .liquidPress({ onChange(!checked) }, pressedScale = 0.97f)
            .liquidGlass(
                shape = GlassCapsule,
                blurRadius = 16.dp,
                tint = if (checked) Accent.copy(alpha = 0.76f) else Color.Unspecified,
                elevation = 4.dp
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(26.dp)
                .graphicsLayer {
                    translationX = t * 20.dp.toPx()
                    shadowElevation = 4.dp.toPx()
                    shape = CircleShape
                    clip = true
                }
                .background(Color.White)
        )
    }
}

@Composable
internal fun TogglePill(on: Boolean, onChange: (Boolean) -> Unit) {
    ImmersiveSwitch(checked = on, onChange = onChange)
}

internal fun formatClock(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN()) return "00:00"
    val total = seconds.toLong()
    return "%02d:%02d".format(total / 60, total % 60)
}
