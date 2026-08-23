package com.netessx.biliplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.netessx.biliplayer.ui.theme.BiliPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BiliPlayerTheme {
                BiliPlayerApp()
            }
        }
    }
}

@Composable
fun BiliPlayerApp() {
    val context = LocalContext.current
    val uiState by PlayerController.state.collectAsState()
    val webView by PlayerController.webView.collectAsState()
    var showPlayer by remember { mutableStateOf(uiState.playlist.isNotEmpty()) }
    var showWebView by remember { mutableStateOf(true) }

    // 申请通知权限（API 33+，用于前台服务通知）
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        PlayerController.init(context)
        PlayerController.ensureBrowser()
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 调试用：实时显示无头 WebView 的页面内容
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("无头浏览器（调试视图）", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showWebView = !showWebView }) {
                Text(if (showWebView) "隐藏" else "显示")
            }
        }
        if (showWebView) {
            val wv = webView
            if (wv != null) {
                AndroidView(
                    factory = { wv },
                    update = { it.translationX = 0f; it.translationY = 0f },
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(360.dp).padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("WebView 初始化中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (showPlayer && uiState.playlist.isNotEmpty()) {
                PlayerScreen(uiState = uiState, onBack = { showPlayer = false })
            } else {
                SetupScreen(uiState = uiState, onStarted = { showPlayer = true })
            }
        }
    }
}

@Composable
private fun SetupScreen(uiState: PlayerUiState, onStarted: () -> Unit) {
    val context = LocalContext.current
    var uid by remember { mutableStateOf("227711953") }
    var favName by remember { mutableStateOf("豪听") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("BiliPlayer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "输入 B 站 UID 与收藏夹名称，无头浏览器将自动抓取并后台播放",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = uid,
            onValueChange = { uid = it },
            label = { Text("UID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = favName,
            onValueChange = { favName = it },
            label = { Text("收藏夹名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                PlayerController.init(context)
                PlayerService.start(context)
                PlayerController.fetchAndStart(uid.trim(), favName.trim())
                onStarted()
            },
            enabled = !uiState.isFetching && uid.isNotBlank() && favName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isFetching) "抓取中…" else "开始播放")
        }
        if (uiState.isFetching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        uiState.fetchError?.let {
            Text("错误: $it", color = MaterialTheme.colorScheme.error)
        }
        if (uiState.logLines.isNotEmpty()) {
            Text("日志", style = MaterialTheme.typography.titleMedium)
            LogBox(uiState.logLines, Modifier.height(200.dp))
        }
    }
}

@Composable
private fun PlayerScreen(uiState: PlayerUiState, onBack: () -> Unit) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0.0) }
    val duration = if (uiState.duration > 0) uiState.duration else 1.0
    val displayed = if (scrubbing) scrubValue else uiState.currentTime

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BiliPlayer", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("返回设置") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(uiState.currentTitle.ifBlank { "加载中…" }, style = MaterialTheme.typography.titleLarge)
                Text(
                    uiState.currentBvid,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Slider(
            value = displayed.coerceIn(0.0, duration).toFloat(),
            onValueChange = {
                scrubbing = true
                scrubValue = it.toDouble()
            },
            onValueChangeFinished = {
                PlayerController.seek(scrubValue)
                scrubbing = false
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(displayed), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(uiState.duration), style = MaterialTheme.typography.labelMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { PlayerController.prev() }) { Text("⏮", fontSize = 20.sp) }
            FilledIconButton(
                onClick = { PlayerController.playPause() },
                modifier = Modifier.size(60.dp)
            ) {
                Text(if (uiState.isPlaying) "⏸" else "▶", fontSize = 24.sp)
            }
            TextButton(onClick = { PlayerController.next() }) { Text("⏭", fontSize = 20.sp) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音量", modifier = Modifier.width(48.dp))
            Slider(
                value = uiState.volume.toFloat(),
                onValueChange = { PlayerController.setVolume(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f)
            )
            Text("${uiState.volume}", modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模式", modifier = Modifier.width(48.dp))
            PlayMode.entries.forEach { mode ->
                FilterChip(
                    selected = uiState.playMode == mode,
                    onClick = { PlayerController.setPlayMode(mode) },
                    label = { Text(modeLabel(mode)) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        Text("播放列表 (${uiState.playlist.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(uiState.playlist) { index, track ->
                ListItem(
                    headlineContent = {
                        Text(track.title.ifBlank { track.bvid }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = { Text(track.bvid) },
                    leadingContent = { if (index == uiState.currentIndex) Text("▶") },
                    colors = if (index == uiState.currentIndex)
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else ListItemDefaults.colors(),
                    modifier = Modifier.clickable { PlayerController.playIndex(index) }
                )
            }
        }

        if (uiState.logLines.isNotEmpty()) {
            Text("日志", style = MaterialTheme.typography.titleMedium)
            LogBox(uiState.logLines, Modifier.height(150.dp))
        }
    }
}

@Composable
private fun LogBox(lines: List<String>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    Card(modifier) {
        Text(
            text = lines.joinToString("\n"),
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatTime(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun modeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENTIAL -> "顺序"
    PlayMode.SHUFFLE -> "随机"
    PlayMode.REPEAT_ONE -> "单曲"
}
