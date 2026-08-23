package com.netessx.biliplayer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.netessx.biliplayer.ui.theme.BiliPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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

private enum class AppTab(val label: String, val icon: String) {
    PLAYER("播放器", "▶"),
    PLAYLIST("播放列表", "📋"),
    LOG("日志", "📄"),
    ABOUT("关于", "ℹ️"),
}

@Composable
fun BiliPlayerApp() {
    val context = LocalContext.current
    val uiState by PlayerController.state.collectAsState()
    val webView by PlayerController.webView.collectAsState()
    var inMain by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(true) }
    var currentTab by remember { mutableStateOf(AppTab.PLAYER) }

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

    // 抓取成功后进入主界面；"返回设置"后需重新抓取才再次进入
    LaunchedEffect(uiState.isFetching) {
        if (!uiState.isFetching && uiState.playlist.isNotEmpty()) inMain = true
    }

    // 设置页仅在抓取期间显示 WebView（保证收藏夹页面能渲染出侧栏/卡片）；平时隐藏避免挤压表单
    val showWebPanel = when {
        !inMain -> uiState.isFetching
        currentTab == AppTab.PLAYER -> showWebView
        else -> false
    }

    Scaffold(
        bottomBar = {
            if (inMain) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Text(tab.icon, fontSize = 18.sp) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!inMain) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(if (showWebPanel) 240.dp else 0.dp))
                    Box(Modifier.weight(1f)) { SetupScreen(uiState = uiState) }
                }
            } else {
                when (currentTab) {
                    AppTab.PLAYER -> Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(if (showWebPanel) 240.dp else 0.dp))
                        Box(Modifier.weight(1f)) {
                            PlayerScreen(
                                uiState = uiState,
                                showWebView = showWebView,
                                onToggleWebView = { showWebView = !showWebView },
                                onBack = { inMain = false }
                            )
                        }
                    }
                    AppTab.PLAYLIST -> PlaylistScreen(uiState = uiState, onPlay = { index ->
                        PlayerController.playIndex(index)
                        currentTab = AppTab.PLAYER
                    })
                    AppTab.LOG -> LogScreen(uiState)
                    AppTab.ABOUT -> AboutScreen()
                }
            }

            // WebView 始终挂载（设置页/播放页占用顶部空间，其余页高度为 0）
            val wv = webView
            if (wv != null) {
                AndroidView(
                    factory = { wv },
                    update = {
                        it.translationX = 0f
                        it.translationY = 0f
                        it.alpha = if (showWebPanel) 1f else 0f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(if (showWebPanel) 240.dp else 0.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(uiState: PlayerUiState) {
    // 抓取中：显示加载提示（WebView 面板在顶部由外层布局展示）
    if (uiState.isFetching) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("BiliPlayer", style = MaterialTheme.typography.headlineMedium)
            Text("正在抓取收藏夹…", style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }

    val context = LocalContext.current
    var uid by remember { mutableStateOf(Preferences.uid(context)) }
    var favName by remember { mutableStateOf(Preferences.favName(context)) }

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
                Preferences.saveIdentity(context, uid.trim(), favName.trim())
                PlayerService.start(context)
                PlayerController.fetchAndStart(uid.trim(), favName.trim())
            },
            enabled = uid.isNotBlank() && favName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始播放")
        }
        uiState.fetchError?.let {
            Text("错误: $it", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PlayerScreen(uiState: PlayerUiState, showWebView: Boolean, onToggleWebView: () -> Unit, onBack: () -> Unit) {
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
            Row {
                TextButton(onClick = onToggleWebView) {
                    Text(if (showWebView) "隐藏页面" else "显示页面")
                }
                TextButton(onClick = onBack) { Text("返回设置") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                // 隐藏 WebView 时，在标题上方展示方形封面
                if (!showWebView && uiState.currentCover.isNotBlank()) {
                    RemoteImage(
                        url = uiState.currentCover,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistScreen(uiState: PlayerUiState, onPlay: (Int) -> Unit) {
    var prefIndex by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("播放列表 (${uiState.playlist.size})·长按设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (uiState.playlist.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("播放列表为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(uiState.playlist) { index, track ->
                    ListItem(
                        headlineContent = {
                            Text(
                                (if (index == uiState.currentIndex) "▶ " else "") + track.title.ifBlank { track.bvid },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = { Text(track.bvid) },
                        leadingContent = {
                            if (track.cover.isNotBlank()) {
                                RemoteImage(track.cover, Modifier.size(44.dp))
                            } else {
                                Box(
                                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) { Text("无", fontSize = 10.sp) }
                            }
                        },
                        colors = if (index == uiState.currentIndex)
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else ListItemDefaults.colors(),
                        modifier = Modifier.combinedClickable(
                            onClick = { onPlay(index) },
                            onLongClick = { prefIndex = index }
                        )
                    )
                }
            }
        }
    }
    prefIndex?.let { index ->
        TrackPrefDialog(
            uiState = uiState,
            index = index,
            onDismiss = { prefIndex = null },
            onChanged = { prefIndex = null }
        )
    }
}

@Composable
private fun TrackPrefDialog(
    uiState: PlayerUiState,
    index: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val track = uiState.playlist.getOrNull(index) ?: return
    val initial = remember(track.bvid) { Preferences.preference(context, track.bvid) }
    var pPart by remember(track.bvid) { mutableStateOf(initial.p) }
    var beginText by remember(track.bvid) { mutableStateOf(initial.beginTime?.toString() ?: "") }
    var endText by remember(track.bvid) { mutableStateOf(initial.endTime?.toString() ?: "") }
    var showPMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("偏好设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    track.title.ifBlank { track.bvid },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    track.bvid,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分P", modifier = Modifier.width(48.dp))
                    Box {
                        OutlinedButton(onClick = { showPMenu = true }) {
                            Text(pPart?.let { "第${it}P" } ?: "自动", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = showPMenu, onDismissRequest = { showPMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("自动（默认）") },
                                onClick = { pPart = null; showPMenu = false }
                            )
                            (1..10).forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("第${p}P") },
                                    onClick = { pPart = p; showPMenu = false }
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开始秒", modifier = Modifier.width(48.dp))
                    OutlinedTextField(
                        value = beginText,
                        onValueChange = { beginText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("0") }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("结束秒", modifier = Modifier.width(48.dp))
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("播完为止") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PlayerController.setTrackPreference(
                    index,
                    TrackPreference(
                        p = pPart,
                        beginTime = beginText.trim().toIntOrNull(),
                        endTime = endText.trim().toIntOrNull(),
                    )
                )
                onChanged()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    PlayerController.removeTrack(index)
                    onChanged()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun LogScreen(uiState: PlayerUiState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("日志", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        LogBox(uiState.logLines, Modifier.fillMaxSize())
    }
}

@Composable
private fun AboutScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BiliPlayer", style = MaterialTheme.typography.headlineMedium)
        Text("用无头浏览器复现的 B 站音乐播放器", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• 无头浏览器 = 离屏 WebView（桌面 UA）", style = MaterialTheme.typography.bodySmall)
                Text("• 收藏夹：DOM 抓取 space.bilibili.com/{uid}/favlist", style = MaterialTheme.typography.bodySmall)
                Text("• 播放：加载 /video/{bvid} 注入 anti_pause 后台续播", style = MaterialTheme.typography.bodySmall)
                Text("• 偏好：分P / 开始秒 / 结束秒，按 bvid 持久化", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "参考项目：github.com/Gingmzmzx/BiliPlayer（Python）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            "v0.1 · 调试用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/** 从 URL 加载封面图（B 站图片需要 Referer 防盗链头）。 */
@Composable
private fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.setRequestProperty("User-Agent", HeadlessBrowser.DESKTOP_USER_AGENT)
                BitmapFactory.decodeStream(conn.inputStream)
            } catch (e: Exception) {
                null
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("封面", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
