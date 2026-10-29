package com.netessx.biliplayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.netessx.biliplayer.ui.theme.BiliPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    STATS("统计", Icons.Filled.BarChart),
    SETTINGS("设置", Icons.Filled.Settings),
    ABOUT("关于", Icons.Filled.Info),
}

/** 应用最外层页面：列表库 → 导入表单 → 播放器。 */
private enum class AppScreen { LIBRARY, IMPORT, PLAYER }

/** 沉浸模式下从播放页打开的二级页面（没有底部导航）。 */
private enum class ImmersiveSub { STATS, SETTINGS, ABOUT }

@Composable
fun BiliPlayerApp() {
    val context = LocalContext.current
    // 更新后首次启动：自动打开沉浸式界面，让用户看到新版 UI
    val upgradedFromOldVersion = remember {
        val last = Preferences.lastRunVersionCode(context)
        val upgraded = last != 0 && last != BuildConfig.VERSION_CODE
        if (last != BuildConfig.VERSION_CODE) {
            Preferences.saveLastRunVersionCode(context, BuildConfig.VERSION_CODE)
        }
        upgraded
    }
    // 沉浸界面跟随系统明暗
    val darkTheme = isSystemInDarkTheme()
    LaunchedEffect(darkTheme) { setImmersiveDarkTheme(darkTheme) }
    // 播放页与歌词浮层共用同一份背景捕获，浮层才能正确模糊底下的页面
    val playerHaze = rememberLiquidGlassState()
    val uiState by PlayerController.state.collectAsState()
    val webView by PlayerController.webView.collectAsState()
    val library by PlaylistStore.playlists.collectAsState()
    val stats by PlaybackStats.stats.collectAsState()
    val lyrics by LyricsStore.entries.collectAsState()
    var screen by remember { mutableStateOf(AppScreen.LIBRARY) }
    var showWebView by remember { mutableStateOf(Preferences.autoFullscreen(context)) }
    var currentTab by remember { mutableStateOf(AppTab.PLAYER) }
    var showLogin by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // 实验性沉浸式播放页
    var immersive by rememberSaveable { mutableStateOf(false) }
    // 统一的沉浸式界面开关：播放器 / 统计 / 设置一起切换
    var immersiveMode by remember {
        mutableStateOf(
            if (upgradedFromOldVersion) {
                // 升级后首次启动强制体验新版沉浸界面
                Preferences.saveImmersiveMode(context, true)
                true
            } else {
                Preferences.immersiveMode(context)
            }
        )
    }
    // 沉浸模式下的二级页面（统计 / 设置 / 关于），null = 播放页
    var immersiveSub by remember { mutableStateOf<ImmersiveSub?>(null) }
    // 退场动画期间仍要渲染上一个页面，否则会闪白
    var lastImmersiveSub by remember { mutableStateOf(ImmersiveSub.STATS) }
    LaunchedEffect(immersiveSub) {
        immersiveSub?.let { lastImmersiveSub = it }
    }
    // 歌词弹窗由最外层托管：常规播放页和沉浸页都能打开同一个
    var showLyricsDialog by remember { mutableStateOf(false) }
    // 首次启动的歌词来源免责声明（同意过就不再出现）
    var showLyricsDisclaimer by remember {
        mutableStateOf(!Preferences.lyricsDisclaimerAccepted(context))
    }
    // 统计页选中的列表。放在这里而不是 StatsScreen 内部：切 tab 时页面会被销毁，
    // 内部 remember 会丢；提到这里才能记住上次的选择。
    var statsSelectedId by remember { mutableStateOf<String?>(null) }
    // 列表库的弹窗状态
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    val inMain = screen == AppScreen.PLAYER

    // 不再隐藏系统栏：保留状态栏/导航栏，只让沉浸页的背景铺到它们下面

    // 更新 / 公告状态
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var noticeInfo by remember { mutableStateOf<NoticeInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    // 手动检查更新：Material 与沉浸式「关于」共用
    val checkForUpdates: () -> Unit = {
        if (!checkingUpdate) {
            checkingUpdate = true
            checkResult = null
            scope.launch {
                // 手动检查不看「忽略此版本」：请求过就一直反馈结果
                val info = AppPublisher.checkUpdate(BuildConfig.VERSION_CODE)
                checkingUpdate = false
                if (info != null) updateInfo = info
                else checkResult = "未发现新版本"
            }
        }
    }

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

    // 一次性提示（视频失效剔除等）
    LaunchedEffect(Unit) {
        PlayerController.toast.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 冷启动检查一次更新与公告（放在这里而非 onResume，避免每次切前台都请求）
    LaunchedEffect(Unit) {
        // 404 / 超时 / 网络异常都已在 AppPublisher 内部静默，拿到 null 即跳过
        val ignored = Preferences.ignoredUpdateVersionCode(context)
        AppPublisher.checkUpdate(BuildConfig.VERSION_CODE)?.let { info ->
            if (info.versionCode != ignored) updateInfo = info
        }
        val seen = Preferences.lastSeenNoticeId(context)
        AppPublisher.fetchLatestNotice()?.let { n ->
            if (n.id > seen) noticeInfo = n
        }
    }

    // 有列表开始播放（导入完成或点了库里的某一项）就进播放器
    LaunchedEffect(uiState.currentPlaylistId) {
        if (uiState.currentPlaylistId.isNotEmpty() && uiState.playlist.isNotEmpty()) {
            screen = AppScreen.PLAYER
        }
    }

    // 进入播放器时按偏好决定默认显示 WebView 还是封面；可设为直接进沉浸页
    LaunchedEffect(inMain) {
        if (inMain) {
            showWebView = Preferences.autoFullscreen(context)
            if (immersiveMode) immersive = true
        }
    }

    // 抓取/同步期间必须露出 WebView（B 站要渲染才能出侧栏和卡片）；
    // 播放器页则由"显示画面"开关决定
    val showWebPanel = when {
        uiState.isFetching -> true
        // 沉浸页不显示视频画面，只留封面背景
        immersive -> false
        inMain && currentTab == AppTab.PLAYER -> showWebView
        else -> false
    }

    // 面板每次重新露出（点了「显示页面」或切回播放器 tab）时补一次全屏：
    // 等 WebView 完成布局（高度>0）再点，避免坐标落在折叠页面上。
    // 用 showWebPanel 而不是 showWebView —— 切 tab 时面板也会收起再展开，值却不变。
    LaunchedEffect(showWebPanel) {
        PlayerController.setWebPanelVisible(showWebPanel)
        if (showWebPanel && inMain) {
            delay(500)
            PlayerController.enterFullscreen()
        }
    }

    Scaffold(
        // 沉浸界面的背景要铺到状态栏/手势条下面（否则上下各露一条白边），
        // 内容由各页面自己用 windowInsetsPadding 避让
        contentWindowInsets = if (immersiveMode || immersive) WindowInsets(0, 0, 0, 0)
        else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            // 沉浸页全屏，不要底部导航栏
            if (inMain && !immersive) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = {
                                currentTab = tab
                                // 日志是从「关于」弹出的全屏页，切 tab 时关掉，
                                // 否则它会一直盖在新页面上
                                showLogs = false
                            },
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
                showLogin -> if (immersiveMode) {
                    ImmersiveLoginScreen(onBack = { showLogin = false })
                } else {
                    LoginScreen(onBack = { showLogin = false })
                }
                showLogs -> if (immersiveMode) {
                ImmersiveLogScreen(uiState = uiState, onBack = { showLogs = false })
            } else {
                LogScreenPage(uiState = uiState, onBack = { showLogs = false })
            }
                !inMain -> {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(if (showWebPanel) 240.dp else 0.dp))
                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                AppScreen.IMPORT -> if (immersiveMode) ImmersiveImportScreen(
                                    uiState = uiState,
                                    onLoginClick = { showLogin = true },
                                    onStart = { uid, favName, autoFullscreen ->
                                        PlayerController.init(context)
                                        Preferences.saveIdentity(context, uid, favName)
                                        Preferences.saveAutoFullscreen(context, autoFullscreen)
                                        PlayerService.start(context)
                                        PlayerController.importAndStart(uid, favName)
                                    },
                                    onBack = { screen = AppScreen.LIBRARY },
                                ) else SetupScreen(
                                    uiState = uiState,
                                    onLoginClick = { showLogin = true },
                                    onBack = { screen = AppScreen.LIBRARY },
                                )
                                else -> if (immersiveMode) ImmersiveLibraryScreen(
                                    playlists = library,
                                    uiState = uiState,
                                    currentPlaylistId = uiState.currentPlaylistId,
                                    stats = stats,
                                    onImportClick = { screen = AppScreen.IMPORT },
                                    onPlay = {
                                        PlayerService.start(context)
                                        PlayerController.playPlaylist(it)
                                    },
                                    onSync = { PlayerController.syncPlaylist(it.id) },
                                    onRename = { pl, name -> PlaylistStore.rename(context, pl.id, name) },
                                    onDelete = { pl ->
                                        PlaylistStore.delete(context, pl.id)
                                        PlaybackStats.removePlaylist(context, pl.id)
                                        if (uiState.currentPlaylistId == pl.id) {
                                            PlayerController.stop()
                                            PlayerService.stop(context)
                                        }
                                    }
                                ) else LibraryScreen(
                                    playlists = library,
                                    uiState = uiState,
                                    currentPlaylistId = uiState.currentPlaylistId,
                                    stats = stats,
                                    onImportClick = { screen = AppScreen.IMPORT },
                                    onPlay = {
                                        // 走播放器前先起前台服务，否则后台/锁屏会被系统掐掉
                                        PlayerService.start(context)
                                        PlayerController.playPlaylist(it)
                                    },
                                    onSync = { PlayerController.syncPlaylist(it.id) },
                                    onRename = { renameTarget = it },
                                    onDelete = { deleteTarget = it },
                                )
                            }
                        }
                    }
                }
                else -> {
                when (currentTab) {
                    AppTab.PLAYER -> Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(if (showWebPanel) 240.dp else 0.dp))
                        Box(Modifier.weight(1f)) {
                            // 返回列表库：停止播放、清空状态、停掉前台服务
                            val goBack: () -> Unit = {
                                PlayerController.stop()
                                PlayerService.stop(context)
                                screen = AppScreen.LIBRARY
                                immersive = false
                                immersiveSub = null
                            }
                            val openLyrics: () -> Unit = { showLyricsDialog = true }
                            if (immersive) {
                                Box(Modifier.fillMaxSize()) {
                                    ImmersivePlayerScreen(
                                        uiState = uiState,
                                        lyricsEntry = lyrics[uiState.currentBvid],
                                        onOpenLyrics = openLyrics,
                                        onOpenStats = { immersiveSub = ImmersiveSub.STATS },
                                        onOpenSettings = { immersiveSub = ImmersiveSub.SETTINGS },
                                        onOpenAbout = { immersiveSub = ImmersiveSub.ABOUT },
                                        onBack = goBack,
                                        // 沉浸模式是全局开关时，不提供"退出沉浸"
                                        onExit = if (immersiveMode) null else ({ immersive = false }),
                                        haze = playerHaze
                                    )

                                    // 二级页面：从右侧滑入，返回时滑回右侧
                                    // 显式限定：外层 Column 有同名扩展，不限定会被解析成 ColumnScope 版本
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = immersiveSub != null,
                                        enter = slideInHorizontally(
                                            initialOffsetX = { it },
                                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f)
                                        ) + fadeIn(),
                                        exit = slideOutHorizontally(
                                            targetOffsetX = { it },
                                            animationSpec = tween(220)
                                        ) + fadeOut(),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        when (lastImmersiveSub) {
                                            ImmersiveSub.STATS -> ImmersiveStatsScreen(
                                                playlists = library,
                                                stats = stats,
                                                selectedId = statsSelectedId,
                                                onSelect = { statsSelectedId = it },
                                                onBack = { immersiveSub = null }
                                            )
                                            ImmersiveSub.SETTINGS -> ImmersiveSettingsScreen(
                                                immersiveMode = immersiveMode,
                                                onImmersiveModeChange = {
                                                    immersiveMode = it
                                                    Preferences.saveImmersiveMode(context, it)
                                                },
                                                onBack = { immersiveSub = null }
                                            )
                                            ImmersiveSub.ABOUT -> ImmersiveAboutScreen(
                                                checking = checkingUpdate,
                                                checkResult = checkResult,
                                                onCheckUpdate = checkForUpdates,
                                                onOpenLogs = { showLogs = true },
                                                onBack = { immersiveSub = null }
                                            )
                                        }
                                    }
                                }
                            } else {
                                PlayerScreen(
                                    uiState = uiState,
                                    lyrics = lyrics[uiState.currentBvid],
                                    showWebView = showWebView,
                                    onToggleWebView = { showWebView = !showWebView },
                                    onEnterImmersive = { immersive = true },
                                    onOpenLyrics = openLyrics,
                                    onBack = goBack
                                )
                            }
                        }
                    }
                    AppTab.PLAYLIST -> PlaylistScreen(
                        uiState = uiState,
                        playlistId = uiState.currentPlaylistId,
                        stats = stats,
                        onPlay = { index ->
                            PlayerController.playIndex(index)
                            currentTab = AppTab.PLAYER
                        }
                    )
                    AppTab.STATS -> if (immersiveMode) {
                        ImmersiveStatsScreen(
                            playlists = library,
                            stats = stats,
                            selectedId = statsSelectedId,
                            onSelect = { statsSelectedId = it }
                        )
                    } else {
                        StatsScreen(
                            playlists = library,
                            stats = stats,
                            selectedId = statsSelectedId,
                            onSelect = { statsSelectedId = it }
                        )
                    }
                    AppTab.SETTINGS -> if (immersiveMode) {
                        ImmersiveSettingsScreen(
                            immersiveMode = immersiveMode,
                            onImmersiveModeChange = {
                                immersiveMode = it
                                Preferences.saveImmersiveMode(context, it)
                            }
                        )
                    } else {
                        SettingsScreen(
                            uiState = uiState,
                            immersiveMode = immersiveMode,
                            onImmersiveModeChange = {
                                immersiveMode = it
                                Preferences.saveImmersiveMode(context, it)
                            }
                        )
                    }
                    AppTab.ABOUT -> AboutScreen(
                        checking = checkingUpdate,
                        checkResult = checkResult,
                        onOpenLogs = { showLogs = true },
                        onCheckUpdate = checkForUpdates
                    )
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

    // 沉浸式弹窗复用播放页背景；其他页面用玻璃自己的实心兜底
    val dialogHaze = if (inMain && !showLogs) playerHaze else null

    // ---------- 更新弹窗 ----------
    updateInfo?.let { info ->
        val onLater: () -> Unit = { updateInfo = null }
        val onIgnore: () -> Unit = {
            // 「忽略此版本」按 versionCode 记录
            Preferences.saveIgnoredUpdateVersionCode(context, info.versionCode)
            updateInfo = null
        }
        val onDownload: () -> Unit = {
            updateInfo = null
            downloading = true
            downloadProgress = 0
            downloadMessage = null
            scope.launch {
                // AppPublisher 内部已 try-catch，失败返回 null
                val file = AppPublisher.downloadRelease(context, info) { p -> downloadProgress = p }
                downloading = false
                when {
                    file == null -> downloadMessage = "下载失败或校验未通过，请稍后重试"
                    AppPublisher.isApk(file) -> AppPublisher.installApk(context, file)
                    else -> downloadMessage = "已下载到：${file.absolutePath}"
                }
            }
        }

        if (immersiveMode) {
            ImmersiveUpdateDialog(
                info = info,
                onLater = onLater,
                onIgnore = onIgnore,
                onDownload = onDownload,
                haze = dialogHaze
            )
        } else {
            UpdateDialog(
                info = info,
                onLater = onLater,
                onIgnore = onIgnore,
                onDownload = onDownload
            )
        }
    }

    // ---------- 公告弹窗（等更新弹窗关掉再显示，避免叠在一起）----------
    if (updateInfo == null) {
        noticeInfo?.let { n ->
            val onDismissNotice: () -> Unit = {
                // 看完才记录已读
                Preferences.saveLastSeenNoticeId(context, n.id)
                noticeInfo = null
            }
            if (immersiveMode) {
                ImmersiveNoticeDialog(
                    info = n,
                    onDismiss = onDismissNotice,
                    haze = dialogHaze
                )
            } else {
                NoticeDialog(info = n, onDismiss = onDismissNotice)
            }
        }
    }

    if (downloading) {
        if (immersiveMode) {
            ImmersiveDownloadDialog(progress = downloadProgress, haze = dialogHaze)
        } else {
            DownloadDialog(progress = downloadProgress)
        }
    }

    downloadMessage?.let { msg ->
        if (immersiveMode) {
            ImmersiveNoticeDialog(
                info = NoticeInfo(title = "提示", content = msg),
                onDismiss = { downloadMessage = null },
                haze = dialogHaze
            )
        } else {
            AlertDialog(
                onDismissRequest = { downloadMessage = null },
                title = { Text("提示") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { downloadMessage = null }) { Text("知道了") } }
            )
        }
    }

    // ---------- 歌词弹窗 ----------
    if (showLyricsDialog) {
        if (immersive) {
            ImmersiveLyricsDialog(
                uiState = uiState,
                entry = lyrics[uiState.currentBvid],
                onSearch = { q -> PlayerController.searchLyricsForCurrent(q) },
                onApply = { i -> PlayerController.applyLyricsResult(i) },
                onSave = { t -> PlayerController.saveLyricsForCurrent(t) },
                onSeek = { sec -> PlayerController.seek(sec) },
                onDismiss = { showLyricsDialog = false },
                haze = playerHaze
            )
        } else {
            LyricsDialog(
                uiState = uiState,
                entry = lyrics[uiState.currentBvid],
                onSearch = { q -> PlayerController.searchLyricsForCurrent(q) },
                onApply = { i -> PlayerController.applyLyricsResult(i) },
                onSave = { t -> PlayerController.saveLyricsForCurrent(t) },
                onSeek = { sec -> PlayerController.seek(sec) },
                onDismiss = { showLyricsDialog = false }
            )
        }
    }

    // ---------- 歌词来源免责声明（首次启动） ----------
    if (showLyricsDisclaimer) {
        val agree: () -> Unit = {
            Preferences.saveLyricsDisclaimerAccepted(context, true)
            showLyricsDisclaimer = false
        }
        // 不同意就不用这个应用：直接退出
        val disagree: () -> Unit = { context.findActivity()?.finish() }
        if (immersiveMode) {
            ImmersiveLyricsDisclaimerDialog(
                onAgree = agree,
                onDisagree = disagree
            )
        } else {
            LyricsDisclaimerDialog(
                onAgree = agree,
                onDisagree = disagree
            )
        }
    }

    // ---------- 播放列表库：重命名 / 删除 ----------
    renameTarget?.let { pl ->
        RenameDialog(
            initial = pl.name,
            onConfirm = { newName ->
                PlaylistStore.rename(context, pl.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { pl ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除播放列表") },
            text = { Text("确定删除「${pl.name}」吗？本地缓存的 ${pl.tracks.size} 首曲目会一并移除，收藏夹本身不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistStore.delete(context, pl.id)
                    PlaybackStats.removePlaylist(context, pl.id)
                    // 正在播的列表被删掉就停下来，避免继续播一份已经不存在的缓存
                    if (uiState.currentPlaylistId == pl.id) {
                        PlayerController.stop()
                        PlayerService.stop(context)
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

/**
 * 歌词来源免责声明。
 *
 * 歌词全部来自第三方 lrclib.net，本应用既不生产也不审核，
 * 因此首次启动时必须让用户明确知晓并同意，未同意前不自动联网检索歌词。
 */
@Composable
private fun LyricsDisclaimerDialog(onAgree: () -> Unit, onDisagree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDisagree,
        title = { Text("歌词来源与免责声明") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "本应用的歌词由第三方歌词库 lrclib.net 按其公开接口检索得到。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 歌词内容由 lrclib.net 及其贡献者提供。本软件不生产、不存储、不审核歌词，" +
                        "与 lrclib.net 也没有任何隶属或合作关系。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "• 歌词的准确性、完整性与合法性由来源方负责。歌词可能错漏或与音频不同步，" +
                        "你可以用偏移微调，但请自行判断是否可信。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "• 本软件及开发者不对使用歌词产生的任何直接或间接后果承担法律责任。" +
                        "如不同意，请勿使用歌词相关功能。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "• 你也可以在歌词弹窗里改用自建接口地址，或直接粘贴自有歌词。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "点击「同意并继续」表示你已阅读并接受上述说明。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意并继续") } },
        dismissButton = { TextButton(onClick = onDisagree) { Text("不同意") } }
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名播放列表") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun UpdateDialog(
    info: ReleaseInfo,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        // 强制更新时不允许点外部关闭
        onDismissRequest = { if (!info.forceUpdate) onLater() },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val meta = buildString {
                    append("版本号 ${info.versionCode}")
                    val size = formatBytes(info.size)
                    if (size.isNotEmpty()) append(" · $size")
                    append(" · ${formatDate(info.timestamp)}")
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (info.forceUpdate) {
                    Text(
                        "此为强制更新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(info.desc.ifBlank { "本次更新没有提供说明" })
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("下载更新") } },
        dismissButton = {
            Row {
                if (!info.forceUpdate) {
                    TextButton(onClick = onIgnore) { Text("忽略此版本") }
                }
                TextButton(onClick = onLater) { Text("稍后") }
            }
        }
    )
}

@Composable
private fun NoticeDialog(info: NoticeInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.title.ifBlank { "公告" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatDate(info.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // content 支持简单 HTML（b/i/u/a/p/br/ul/li 等），也兼容纯文本换行
                HtmlText(info.content)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/**
 * 渲染公告里的简单 HTML。
 *
 * 用平台 [HtmlCompat] 解析（不是 WebView）：不执行脚本、不加载远程资源，
 * 只接受基础排版标签，`<a href>` 交由 [LinkMovementMethod] 用系统浏览器打开。
 * 传进来的内容若不含任何标签，按纯文本处理，`\n` 视作换行。
 */
@Composable
private fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textSize = if (textStyle.fontSize.isSpecified) textStyle.fontSize.value else 14f
    // 纯文本里的换行在 HTML 里会被折叠，先转成 <br>
    val source = remember(html) {
        if (html.contains('<')) html else html.replace("\n", "<br>")
    }
    val spanned = remember(source, contentColor, linkColor, textSize) {
        HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(contentColor)
                setLinkTextColor(linkColor)
                this.textSize = textSize
                movementMethod = LinkMovementMethod.getInstance()
                // 链接点击用系统浏览器打开，不在应用内导航
                linksClickable = true
            }
        },
        update = { it.text = spanned }
    )
}

@Composable
private fun DownloadDialog(progress: Int) {
    AlertDialog(
        onDismissRequest = { },   // 下载中不可取消
        title = { Text("正在下载更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$progress%", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""                                  // size 是增量字段，可能为 0
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}

/** timestamp 是 epoch 毫秒，java.util.Date 直接接收毫秒，无需换算。 */
private fun formatDate(ms: Long): String {
    if (ms <= 0) return "未知时间"
    return try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    } catch (e: Exception) {
        "未知时间"
    }
}

/**
 * 抓取/同步进行中的进度条——导入页和列表库共用。
 * 总数还没拿到时用不确定进度条，不假装有百分比。
 */
@Composable
private fun FetchProgress(uiState: PlayerUiState) {
    if (!uiState.isFetching) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("正在抓取收藏夹… 请不要动上方画面", style = MaterialTheme.typography.titleMedium)
        if (uiState.fetchProgress > 0f) {
            LinearProgressIndicator(
                progress = { uiState.fetchProgress },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        uiState.paginationInfo?.let { info ->
            Text(info, style = MaterialTheme.typography.bodyMedium)
        }
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
}

/**
 * 播放列表库：应用进入的首页。
 * 点一项直接播缓存；齿轮里可以同步 / 重命名 / 删除。
 */
@Composable
private fun LibraryScreen(
    playlists: List<Playlist>,
    uiState: PlayerUiState,
    currentPlaylistId: String,
    stats: Map<String, Map<String, Long>>,
    onImportClick: () -> Unit,
    onPlay: (Playlist) -> Unit,
    onSync: (Playlist) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("播放列表", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "从收藏夹导入一次就会缓存到本地，之后打开即用，不必重新抓取",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        FetchProgress(uiState)

        uiState.fetchError?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(onClick = onImportClick, modifier = Modifier.fillMaxWidth()) {
            Text("+ 从收藏夹导入")
        }
        Spacer(Modifier.height(12.dp))

        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有播放列表，点上面的按钮从 B 站收藏夹导入一个吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(playlists, key = { _, p -> p.id }) { _, pl ->
                val playing = pl.id == currentPlaylistId
                val syncing = pl.id == uiState.syncingPlaylistId
                Card(
                    onClick = { onPlay(pl) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (playing) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            val meta = buildString {
                                append("${pl.tracks.size} 首")
                                if (pl.updatedAt > 0) append(" · 更新于 ${formatDate(pl.updatedAt)}")
                                if (pl.favName.isNotBlank()) append(" · 收藏夹「${pl.favName}」")
                            }
                            Text(
                                meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 该列表累计听歌时长
                            val listened = stats[pl.id]?.values?.sum() ?: 0L
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (listened > 0) "已听 ${formatListenTime(listened)}"
                                else "还没听过",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (listened > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (syncing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Box {
                            FilledIconButton(onClick = { menuFor = pl.id }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuFor == pl.id,
                                onDismissRequest = { menuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("同步") },
                                    onClick = { menuFor = null; onSync(pl) }
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = { menuFor = null; onRename(pl) }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = { menuFor = null; onDelete(pl) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    uiState: PlayerUiState,
    onLoginClick: () -> Unit,
    onBack: () -> Unit,
) {
    // 抓取中：显示加载提示（WebView 面板在顶部由外层布局展示）
    if (uiState.isFetching) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("BiliPlayer", style = MaterialTheme.typography.headlineMedium)
            FetchProgress(uiState)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("从收藏夹导入", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("← 返回") }
        }
        Text(
            "输入 B 站 UID 与收藏夹名称，无头浏览器将自动抓取并缓存到本地，随后开始播放",
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
                PlayerController.importAndStart(uid.trim(), favName.trim())
            },
            enabled = uid.isNotBlank() && favName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("抓取并播放")
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
private fun PlayerScreen(
    uiState: PlayerUiState,
    lyrics: LyricsEntry?,
    showWebView: Boolean,
    onToggleWebView: () -> Unit,
    onEnterImmersive: () -> Unit,
    onOpenLyrics: () -> Unit,
    onBack: () -> Unit,
) {
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
                TextButton(onClick = onEnterImmersive) { Text("沉浸") }
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("返回列表")
                }
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

        LyricsPreview(
            entry = lyrics,
            positionSec = uiState.currentTime,
            onClick = onOpenLyrics
        )

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

/**
 * 播放器上的歌词预览：当前行高亮，上下各一行做上下文。
 * 点一下打开歌词弹窗（搜索 / 手动指定歌名 / 粘贴 LRC）。
 */
@Composable
private fun LyricsPreview(entry: LyricsEntry?, positionSec: Double, onClick: () -> Unit) {
    val lines = remember(entry?.bvid, entry?.synced, entry?.plain) { entry?.lines().orEmpty() }
    // 偏移：正数让歌词延后出现。
    // 只有真正带时间轴的歌词才跟随播放；纯文本保持静止，不假装滚动。
    val pos = positionSec - (entry?.offsetMs ?: 0L) / 1000.0
    val active = if (entry?.hasSyncedLines == true) activeLineIndex(lines, pos) else -1
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    "暂无歌词 · 点此搜索或手动填写",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (offset in -2..2) {
                    val i = (if (active < 0) 0 else active) + offset
                    val line = lines.getOrNull(i) ?: continue
                    val isActive = i == active && active >= 0
                    Text(
                        line.text.ifBlank { "♪" },
                        style = if (isActive) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "点此查看全部歌词",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LyricsDialog(
    uiState: PlayerUiState,
    entry: LyricsEntry?,
    onSearch: (String) -> Unit,
    onApply: (Int) -> Unit,
    onSave: (String) -> Unit,
    onSeek: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(uiState.currentBvid) { mutableStateOf(guessSongName(uiState.currentTitle)) }
    var pasted by remember(uiState.currentBvid) { mutableStateOf(entry?.synced.orEmpty()) }
    var showPaste by remember { mutableStateOf(false) }
    val lines = remember(entry?.bvid, entry?.synced, entry?.plain) { entry?.lines().orEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌词") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (lines.isNotEmpty()) {
                    Text(
                        buildString {
                            if (entry?.manual == true) append("手动词条")
                            else append("已匹配：${entry?.trackName.orEmpty()} - ${entry?.artistName.orEmpty()}")
                            if (entry?.hasSyncedLines != true) append("（纯文本，无时间轴）")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 跟着播放位置滚动并高亮当前行；固定高度以便嵌在可滚动弹窗里
                    val listState = rememberLazyListState()
                    val active = if (entry?.hasSyncedLines == true)
                        activeLineIndex(
                            lines,
                            uiState.currentTime - (entry.offsetMs) / 1000.0
                        ) else -1
                    LaunchedEffect(active) {
                        if (active >= 0) listState.animateScrollToItem(active)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(lines) { i, line ->
                            Text(
                                line.text.ifBlank { "♪" },
                                style = if (i == active) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.bodySmall,
                                color = if (i == active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                // 同步歌词才允许点行跳转；纯文本没有时间点，点了也没意义
                                modifier = if (entry?.hasSyncedLines == true) {
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSeek(
                                                (line.timeMs + (entry?.offsetMs ?: 0L)) / 1000.0
                                            )
                                        }
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                            )
                        }
                    }
                    // 偏移微调：歌词比音频快就点「延后」，慢就点「提前」
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("偏移", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { PlayerController.adjustLyricsOffset(-500) }) {
                            Text("提前")
                        }
                        Text(
                            "%.1fs".format((entry?.offsetMs ?: 0L) / 1000.0),
                            style = MaterialTheme.typography.labelMedium
                        )
                        OutlinedButton(onClick = { PlayerController.adjustLyricsOffset(500) }) {
                            Text("延后")
                        }
                        TextButton(onClick = {
                            PlayerController.adjustLyricsOffset(-(entry?.offsetMs ?: 0L))
                        }) { Text("归零") }
                    }
                    HorizontalDivider()
                } else {
                    Text(
                        "没有找到歌词。可以改一下歌名重新搜索，或直接粘贴带时间轴的歌词（LRC）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("歌名 / 歌手") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSearch(query) },
                        enabled = !uiState.lyricsSearching && query.isNotBlank()
                    ) {
                        if (uiState.lyricsSearching) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("搜索歌词")
                    }
                    TextButton(onClick = { showPaste = !showPaste }) {
                        Text(if (showPaste) "收起" else "粘贴歌词")
                    }
                }

                // 搜索结果：列出所有可用的歌词，点一条应用
                if (uiState.lyricsSearchResults.isNotEmpty()) {
                    Text(
                        "选择歌词版本",
                        style = MaterialTheme.typography.titleSmall
                    )
                    uiState.lyricsSearchResults.forEachIndexed { i, r ->
                        Card(
                            onClick = { onApply(i) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    r.trackName.ifBlank { "(无标题)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        append(r.artistName.ifBlank { "未知歌手" })
                                        append(if (r.hasSyncedLines) " · 同步歌词" else " · 纯文本")
                                        if (r.plain.isNotBlank() && !r.hasSyncedLines) append("（无时间轴）")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (showPaste) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("LRC / 纯文本歌词") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSave(pasted) }, enabled = pasted.isNotBlank()) {
                            Text("保存")
                        }
                        TextButton(onClick = { pasted = ""; onSave("") }) { Text("清除") }
                    }
                }

                uiState.lyricsStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
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
private fun PlaylistScreen(
    uiState: PlayerUiState,
    playlistId: String,
    stats: Map<String, Map<String, Long>>,
    onPlay: (Int) -> Unit,
) {
    var prefIndex by remember { mutableStateOf<Int?>(null) }
    val perTrack = stats[playlistId].orEmpty()
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
                    val listened = perTrack[track.bvid] ?: 0L
                    ListItem(
                        headlineContent = {
                            Text(
                                (if (index == uiState.currentIndex) "▶ " else "") + track.title.ifBlank { track.bvid },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(track.bvid)
                                    if (listened > 0) append(" · 已听 ${formatListenTime(listened)}")
                                }
                            )
                        },
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

/** 秒 → 「1 小时 23 分」/「5 分 12 秒」/「42 秒」。 */
private fun formatListenTime(seconds: Long): String {
    if (seconds <= 0) return "0 秒"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "$h 小时 $m 分"
        m > 0 -> "$m 分 $s 秒"
        else -> "$s 秒"
    }
}

/** 饼图配色：B 站粉/蓝打头，其余为区分度较高的辅助色。 */
private val STATS_COLORS = listOf(
    Color(0xFFFB7299), Color(0xFF23ADE5), Color(0xFF4DD6A8), Color(0xFF7B5CFF),
    Color(0xFFFFA726), Color(0xFF26C6DA), Color(0xFFEC407A), Color(0xFF9CCC65),
    Color(0xFFFF7043), Color(0xFF5C6BC0), Color(0xFFD4A017), Color(0xFF00897B),
)

/**
 * 听歌统计页（经典 Material 版）。
 * 顶部是跨列表总时长，下面可以切换列表看各自的饼图。
 */
@Composable
private fun StatsScreen(
    playlists: List<Playlist>,
    stats: Map<String, Map<String, Long>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    fun listenedOf(id: String): Long = stats[id]?.values?.sum() ?: 0L
    val totalAll = stats.values.sumOf { m -> m.values.sum() }
    val withData = playlists.filter { listenedOf(it.id) > 0 }
    // 选中的列表被删掉时回落到时长最长的那个
    val selected = withData.firstOrNull { it.id == selectedId }
        ?: withData.maxByOrNull { listenedOf(it.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("播放统计", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "总听歌时长",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(formatListenTime(totalAll), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "共 ${withData.size} 个列表有记录 · 统计实际播放时长，拖动进度条不会增加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (withData.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有听歌记录。去播放器播几首，这里就会有数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            withData.forEach { pl ->
                FilterChip(
                    selected = pl.id == selected?.id,
                    onClick = { onSelect(pl.id) },
                    label = { Text("${pl.name} · ${formatListenTime(listenedOf(pl.id))}") }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        selected?.let { pl ->
            StatsContent(tracks = pl.tracks, perTrack = stats[pl.id].orEmpty())
        }
    }
}

/** 饼图 + 图例。每首歌一个扇区，不做「其他」合并。 */
@Composable
private fun StatsContent(
    tracks: List<BiliTrack>,
    perTrack: Map<String, Long>,
    modifier: Modifier = Modifier,
) {
    // 按 bvid 去重：同一个视频出现两次时只算一份
    val sorted = tracks
        .distinctBy { it.bvid }
        .map { it to (perTrack[it.bvid] ?: 0L) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }

    data class Slice(val label: String, val seconds: Long, val color: Color)

    val slices: List<Slice> = sorted.mapIndexed { i, (t, sec) ->
        Slice(t.title.ifBlank { t.bvid }, sec, STATS_COLORS[i % STATS_COLORS.size])
    }
    val total = sorted.sumOf { it.second }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("累计 ${formatListenTime(total)}", style = MaterialTheme.typography.titleMedium)
        Text(
            "统计的是实际播放时长，拖动进度条不会增加。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (total <= 0) return@Column
        Canvas(
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = slice.seconds.toFloat() / total.toFloat() * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true
                )
                startAngle += sweep
            }
        }
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(slice.color, RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    slice.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatListenTime(slice.seconds)} · ${slice.seconds * 100 / total}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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

/** 从「关于」进入的日志页，带返回按钮（日志已从底部导航移除）。 */
@Composable
private fun LogScreenPage(uiState: PlayerUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("返回")
            }
        }
        LogScreen(uiState)
    }
}

/**
 * 设置页：歌词来源接口地址与桌面歌词悬浮窗。
 * 歌词的搜索/挑选放在播放器的歌词弹窗里，这里只放"长期生效"的配置。
 */
@Composable
private fun SettingsScreen(
    uiState: PlayerUiState,
    immersiveMode: Boolean,
    onImmersiveModeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupMsg by remember { mutableStateOf<String?>(null) }

    // 导出：用户自己选保存位置
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
    // 导入：按 bvid 合并覆盖
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        // ---------- 界面 ----------
        Text("界面", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("沉浸式界面", style = MaterialTheme.typography.titleSmall)
                Text(
                    "播放器、统计、设置全部切换为无 Material 的沉浸版界面；关闭则用经典界面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = immersiveMode,
                onCheckedChange = onImmersiveModeChange
            )
        }

        HorizontalDivider()

        // ---------- 歌词来源 ----------
        Text("歌词来源", style = MaterialTheme.typography.titleMedium)
        Text(
            "歌词由 lrclib.net 按其公开接口提供，可换成自建镜像。搜索路径为 {地址}/search。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var base by remember { mutableStateOf(Preferences.lrcLibBaseUrl(context)) }
        OutlinedTextField(
            value = base,
            onValueChange = { base = it },
            label = { Text("lrclib 接口地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                Preferences.saveLrcLibBaseUrl(context, base)
                LrcLib.init(context)
                base = Preferences.lrcLibBaseUrl(context)
            }) { Text("保存") }
            TextButton(onClick = {
                base = Preferences.DEFAULT_LRCLIB_BASE
                Preferences.saveLrcLibBaseUrl(context, base)
                LrcLib.init(context)
            }) { Text("恢复默认") }
        }

        HorizontalDivider()

        // ---------- 桌面歌词 ----------
        Text("桌面歌词", style = MaterialTheme.typography.titleMedium)
        val canOverlay = Settings.canDrawOverlays(context)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示悬浮窗", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (canOverlay) "在其他应用上层实时显示当前歌词"
                    else "需要「显示在其他应用上层」权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = uiState.desktopLyrics && canOverlay,
                onCheckedChange = { on ->
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
                        PlayerController.setDesktopLyrics(on)
                    }
                }
            )
        }
        if (!canOverlay) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("去授予悬浮窗权限") }
        } else if (uiState.desktopLyrics) {
            var size by remember { mutableStateOf(LyricsOverlay.currentFontSize()) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("字号", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    size = (size - 2f).coerceAtLeast(10f)
                    LyricsOverlay.setFontSize(size)
                }) { Text("A-") }
                Text("${size.toInt()}", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = {
                    size = (size + 2f).coerceAtMost(40f)
                    LyricsOverlay.setFontSize(size)
                }) { Text("A+") }
                TextButton(onClick = { LyricsOverlay.resetPosition() }) { Text("重置位置") }
            }

            var locked by remember { mutableStateOf(LyricsOverlay.isLocked()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("锁定位置", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "锁定后不可拖动，也不会挡住下层的点击",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = locked,
                    onCheckedChange = {
                        locked = it
                        LyricsOverlay.setLocked(it)
                    }
                )
            }
            Text(
                "桌面上单指拖动改位置，双指捏合改字号",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        // ---------- 备份与恢复 ----------
        Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
        Text(
            "导出/导入所有歌曲的单曲偏好（分P、开始秒、结束秒）与歌词缓存（含偏移），换机或重装时用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportLauncher.launch(Backup.SUGGESTED_NAME) }) {
                Text("导出")
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }) { Text("导入") }
        }
        backupMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AboutScreen(
    checking: Boolean,
    checkResult: String?,
    onOpenLogs: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 内容比一屏长，必须可滚动，否则底部按钮/版本文本会被挤出屏幕
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
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
        LinkText(
            "官网 / 下载：https://apps.netessx.com/BiliPlayerAndroid",
            "https://apps.netessx.com/BiliPlayerAndroid"
        )
        Text("本项目是BiliPlayer的Android端实现，采用WebView并支持后台播放。由Gingmzmzx借助Claude Code开发", style = MaterialTheme.typography.bodyMedium)
        LinkText(
            "本项目仍在早期开发阶段，可能仍不稳定，存在许多bug，请您积极前往GitHub反馈，感谢您提交issue。也强烈建议您前往我的爱发电支持我：https://afdian.com/a/Gingmzmzx",
            "https://afdian.com/a/Gingmzmzx",
            textStyle = MaterialTheme.typography.bodyMedium
        )
        // 可滚动布局里不能用 weight 占位（高度无界），改用固定间距
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("运行日志")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onCheckUpdate, enabled = !checking) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checking) "检查中…" else "检查更新")
            }
            checkResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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

/** 从 Compose 的 Context 里取出宿主 Activity（用于 finish 等操作）。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
