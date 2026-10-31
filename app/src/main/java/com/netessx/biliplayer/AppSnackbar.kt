package com.netessx.biliplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SnackbarMessage(val id: Long, val text: String)

/** 全局 Snackbar：替代 Toast，走沉浸式玻璃样式。 */
object AppSnackbar {
    private val _message = MutableStateFlow<SnackbarMessage?>(null)
    val message: StateFlow<SnackbarMessage?> = _message
    private var nextId = 0L

    fun show(text: String) {
        _message.value = SnackbarMessage(++nextId, text)
    }

    fun dismiss(id: Long) {
        if (_message.value?.id == id) _message.value = null
    }
}

@Composable
internal fun GlassSnackbarHost(modifier: Modifier = Modifier) {
    val message by AppSnackbar.message.collectAsState()
    LaunchedEffect(message?.id) {
        val id = message?.id ?: return@LaunchedEffect
        delay(3200)
        AppSnackbar.dismiss(id)
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(120))
        ) {
            val current = message ?: return@AnimatedVisibility
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp,
                        elevation = 10.dp,
                        tintAlpha = 1.4f
                    )
                    .liquidPress(
                        onClick = { AppSnackbar.dismiss(current.id) }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                BasicText(
                    current.text,
                    style = TextStyle(color = TextPrimary, fontSize = 12.sp)
                )
            }
        }
    }
}
