package com.netessx.biliplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.netessx.biliplayer.ui.theme.BiliPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

private enum class AppTab(val label: String, val icon: ImageVector) {
    PLAYER("播放器", Icons.Filled.PlayArrow),
    PLAYLIST("播放列表", Icons.Filled.List),
    LOG("日志", Icons.Filled.Edit),
    ABOUT("关于", Icons.Filled.Info),
}

@Composable
fun BiliPlayerApp() {
    val context = LocalContext.current
    val uiState by PlayerController.state.collectAsState()
    val webView by PlayerController.webView.collectAsState()
    var inMain by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(Preferences.autoFullscreen(context)) }
    var currentTab by remember { mutableStateOf(AppTab.PLAYER) }
    var showLogin by remember { mutableStateOf(false) }

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

    // 进入主界面时按"自动全屏"偏好决定默认显示 WebView 还是封面
    LaunchedEffect(inMain) {
        if (inMain) showWebView = Preferences.autoFullscreen(context)
    }

    // 手动"显示 WebView"后，等 WebView 完成布局（高度>0）再补全屏，避免高度仍为 0 导致坐标误点
    LaunchedEffect(showWebView) {
        if (showWebView) {
            delay(500)
            PlayerController.enterFullscreen()
        }
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
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                showLogin -> LoginScreen(onBack = { showLogin = false })
                !inMain -> {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(if (showWebPanel) 240.dp else 0.dp))
                        Box(Modifier.weight(1f)) {
                            SetupScreen(
                                uiState = uiState,
                                onLoginClick = { showLogin = true }
                            )
                        }
                    }
                }
                else -> {
                when (currentTab) {
                    AppTab.PLAYER -> Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(if (showWebPanel) 240.dp else 0.dp))
                        Box(Modifier.weight(1f)) {
                            PlayerScreen(
                                uiState = uiState,
                                showWebView = showWebView,
                                onToggleWebView = { showWebView = !showWebView },
                                onBack = {
                                    // 返回设置：停止播放、清空状态、停掉前台服务
                                    PlayerController.stop()
                                    PlayerService.stop(context)
                                    inMain = false
                                }
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
            }

            // WebView 始终挂载（设置页/播放页占用顶部空间，其余页高度为 0）
            val wv = webView
            if (wv != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(if (showWebPanel) 240.dp else 0.dp)
                ) {
                    AndroidView(
                        factory = { wv },
                        update = {
                            it.translationX = 0f
                            it.translationY = 0f
                            it.alpha = if (showWebPanel) 1f else 0f
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (inMain && showWebPanel) {
                        // 透明触摸拦截层：仅在主界面屏蔽用户点击/滑动（抓取收藏夹阶段不屏蔽，便于手动介入）
                        // 自动化 dispatchTouchEvent 直连 WebView，不受影响
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent().changes.forEach { it.consume() }
                                        }
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(uiState: PlayerUiState, onLoginClick: () -> Unit) {
    // 抓取中：显示加载提示（WebView 面板在顶部由外层布局展示）
    if (uiState.isFetching) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("BiliPlayer", style = MaterialTheme.typography.headlineMedium)
            Text("正在抓取收藏夹… 请不要动上方画面", style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            uiState.fetchHint?.let { hint ->
                Text(
                    hint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        return
    }

    val context = LocalContext.current
    var uid by remember { mutableStateOf(Preferences.uid(context)) }
    var favName by remember { mutableStateOf(Preferences.favName(context)) }
    var autoFullscreen by remember { mutableStateOf(Preferences.autoFullscreen(context)) }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = autoFullscreen,
                onCheckedChange = { autoFullscreen = it }
            )
            Text("自动全屏并隐藏控制条（不勾选则显示封面）", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(
            onClick = onLoginClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("登录 B 站（可选）")
        }
        Text(
            "登录后可以访问私密收藏夹，解锁其他功能。登录状态会保存在 WebView 中，本软件将不会将相关 Cookie 上传至任何第三方服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                PlayerController.init(context)
                Preferences.saveIdentity(context, uid.trim(), favName.trim())
                Preferences.saveAutoFullscreen(context, autoFullscreen)
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                CookieManager.getInstance().flush()
                onBack()
            }) { Text("← 返回") }
            Text("登录 B 站", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(48.dp))
        }
        Text(
            "请在下方页面登录 B 站账号，登录完成后点击「返回」",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AndroidView(
            factory = { ctx ->
                val wv = WebView(ctx)
                wv.webViewClient = object : android.webkit.WebViewClient() {}
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = HeadlessBrowser.DESKTOP_USER_AGENT
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.loadUrl("https://www.bilibili.com/")
                wv
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PlayerScreen(uiState: PlayerUiState, showWebView: Boolean, onToggleWebView: () -> Unit, onBack: () -> Unit) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0.0) }
    var showTimerMenu by remember { mutableStateOf(false) }
    var showCustomTimerDialog by remember { mutableStateOf(false) }
    val duration = if (uiState.duration > 0) uiState.duration else 1.0
    val displayed = if (scrubbing) scrubValue else uiState.currentTime

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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
            TextButton(onClick = { PlayerController.prev() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
            }
            FilledIconButton(
                onClick = { PlayerController.playPause() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp)
                )
            }
            TextButton(onClick = { PlayerController.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
            }
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("定时", modifier = Modifier.width(48.dp))
            Box {
                OutlinedButton(onClick = { showTimerMenu = true }) {
                    Text(
                        uiState.sleepRemaining?.let { formatTime(it.toDouble()) } ?: "关闭",
                        fontSize = 12.sp
                    )
                }
                DropdownMenu(expanded = showTimerMenu, onDismissRequest = { showTimerMenu = false }) {
                    listOf(
                        0L to "关闭", 300L to "5分钟", 600L to "10分钟",
                        900L to "15分钟", 1800L to "30分钟", 3600L to "60分钟"
                    ).forEach { (sec, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                PlayerController.setSleepTimer(sec, uiState.sleepAction)
                                showTimerMenu = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("自定义…") },
                        onClick = {
                            showTimerMenu = false
                            showCustomTimerDialog = true
                        }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = uiState.sleepAction == SleepAction.PAUSE,
                onClick = { PlayerController.setSleepTimer(uiState.sleepRemaining ?: 0, SleepAction.PAUSE) },
                label = { Text("暂停") },
                modifier = Modifier.padding(end = 4.dp)
            )
            FilterChip(
                selected = uiState.sleepAction == SleepAction.QUIT,
                onClick = { PlayerController.setSleepTimer(uiState.sleepRemaining ?: 0, SleepAction.QUIT) },
                label = { Text("退出") }
            )
        }
    }

    if (showCustomTimerDialog) {
        CustomTimerDialog(
            onDismiss = { showCustomTimerDialog = false },
            onConfirm = { seconds ->
                PlayerController.setSleepTimer(seconds, uiState.sleepAction)
                showCustomTimerDialog = false
            }
        )
    }
}

@Composable
private fun CustomTimerDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var minutes by remember { mutableStateOf("30") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义定时") },
        text = {
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it },
                label = { Text("分钟") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val m = minutes.trim().toLongOrNull()
                if (m != null && m > 0) onConfirm(m * 60)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
        Text("使用无头浏览器实现的 B 站音乐播放器", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• 无头浏览器 = 离屏 WebView（桌面 UA）", style = MaterialTheme.typography.bodySmall)
                Text("• 收藏夹：DOM 抓取 space.bilibili.com/{uid}/favlist", style = MaterialTheme.typography.bodySmall)
                Text("• 播放：加载 /video/{bvid} 注入 anti_pause 后台续播", style = MaterialTheme.typography.bodySmall)
                Text("• 偏好：分P / 开始秒 / 结束秒，按 bvid 持久化", style = MaterialTheme.typography.bodySmall)
            }
        }
        LinkText(
            "相关项目：桌面端：https://github.com/Gingmzmzx/BiliPlayer（Python + Playwright）",
            "https://github.com/Gingmzmzx/BiliPlayer"
        )
        LinkText(
            "本项目开源地址：https://github.com/Gingmzmzx/BiliPlayer-android",
            "https://github.com/Gingmzmzx/BiliPlayer-android"
        )
        Text("本项目是BiliPlayer的Android端实现，采用WebView并支持后台播放。由Gingmzmzx借助Claude Code开发", style = MaterialTheme.typography.bodyMedium)
        LinkText(
            "本项目仍在早期开发阶段，可能仍不稳定，存在许多bug，请您积极前往GitHub反馈，感谢您提交issue。也强烈建议您前往我的爱发电支持我：https://afdian.com/a/Gingmzmzx",
            "https://afdian.com/a/Gingmzmzx",
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.weight(1f))
        Text(
            "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · 调试用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 把文本中的 URL 渲染成可点击链接（带下划线+主题色），点击在浏览器中打开。 */
@Composable
private fun LinkText(fullText: String, url: String, textStyle: TextStyle = MaterialTheme.typography.bodySmall) {
    val context = LocalContext.current
    val annotated = buildAnnotatedString {
        val idx = fullText.indexOf(url)
        if (idx >= 0) {
            append(fullText.substring(0, idx))
            withLink(
                LinkAnnotation.Clickable(
                    tag = url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                ) { openUrl(context, url) }
            ) {
                append(url)
            }
            append(fullText.substring(idx + url.length))
        } else {
            append(fullText)
        }
    }
    Text(annotated, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

/** 从 URL 加载封面图（B 站图片需要 Referer 防盗链头），带圆角裁剪。 */
@Composable
private fun RemoteImage(url: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
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
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
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
