package com.netessx.biliplayer

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 定时关闭到达后的动作。 */
enum class SleepAction { PAUSE, QUIT }

/** 播放器对外 UI 状态。 */
data class PlayerUiState(
    val isFetching: Boolean = false,
    val fetchError: String? = null,
    val fetchHint: String? = null,
    val paginationInfo: String? = null,
    val fetchProgress: Float = 0f,
    val playlist: List<BiliTrack> = emptyList(),
    val currentIndex: Int = -1,
    val currentBvid: String = "",
    val currentTitle: String = "",
    val currentCover: String = "",
    val currentP: Int = 0,
    val isPlaying: Boolean = false,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Int = 30,
    val playMode: PlayMode = PlayMode.SHUFFLE,
    val logLines: List<String> = emptyList(),
    val sleepRemaining: Long? = null,
    val sleepAction: SleepAction = SleepAction.PAUSE,
    /** 正在播放的列表 id（来自 [PlaylistStore]）；空串表示不是从列表库播的。 */
    val currentPlaylistId: String = "",
    /** 正在同步哪个列表，空串表示没有同步在进行。 */
    val syncingPlaylistId: String = "",
    /** 歌词检索中（自动或手动）。 */
    val lyricsSearching: Boolean = false,
    /** 歌词检索结果提示。 */
    val lyricsStatus: String? = null,
    /** 当前播放位置对应的歌词行（用于灵动岛/通知与桌面歌词）。无歌词时为空串。 */
    val currentLyricLine: String = "",
    /** 是否开启桌面歌词悬浮窗。 */
    val desktopLyrics: Boolean = false,
    /** 当前这次搜索拿到的候选歌词（供用户挑选）。 */
    val lyricsSearchResults: List<LyricsEntry> = emptyList(),
)

/**
 * 播放器控制器单例：持有无头浏览器与播放引擎，向 Compose 暴露 [state]。
 * 与前台服务同进程，由 [PlayerService] 负责保活后台播放。
 */
object PlayerController {

    private lateinit var appContext: Context
    private var browser: HeadlessBrowser? = null
    private var player: BiliMusicPlayer? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sleepJob: Job? = null
    private var statsJob: Job? = null

    private val _webView = MutableStateFlow<WebView?>(null)
    val webView: StateFlow<WebView?> = _webView

    /** 一次性提示（Toast）。用 SharedFlow 而不是状态字段，避免重组时重复弹。 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    /** 当前播放的列表 id；空串表示不在列表库里。 */
    private var currentPlaylistId: String = ""

    /** 画面面板是否显示（由 MainActivity 跟随 Compose 布局更新）。 */
    private var webPanelVisible: Boolean = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        PlaylistStore.init(appContext)
        PlaybackStats.init(appContext)
        LyricsStore.init(appContext)
        LrcLib.init(appContext)
        startStatsTicker()
        _state.update {
            it.copy(
                volume = Preferences.defaultVolume(appContext),
                playMode = Preferences.playMode(appContext),
                desktopLyrics = Preferences.desktopLyrics(appContext),
            )
        }
    }

    /** 设置定时：seconds 秒后暂停或退出。<=0 取消。 */
    fun setSleepTimer(seconds: Long, action: SleepAction) {
        sleepJob?.cancel()
        _state.update { it.copy(sleepAction = action) }
        if (seconds <= 0) {
            _state.update { it.copy(sleepRemaining = null) }
            return
        }
        _state.update { it.copy(sleepRemaining = seconds) }
        sleepJob = scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000)
                remaining--
                _state.update { it.copy(sleepRemaining = remaining) }
            }
            _state.update { it.copy(sleepRemaining = null) }
            when (_state.value.sleepAction) {
                SleepAction.PAUSE -> {
                    if (player?.isPlaying == true) player?.togglePause()
                }
                SleepAction.QUIT -> {
                    stop()
                    PlayerService.stop(appContext)
                }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepRemaining = null) }
    }

    /**
     * 从收藏夹导入一个列表：抓取 → 缓存到 [PlaylistStore] → 立即开始播放。
     * 复刻 run.py 的 get_favlist -> BiliMusicPlayer.play，只是多了一步落库。
     */
    fun importAndStart(uid: String, favName: String) {
        if (!::appContext.isInitialized) return
        player?.stop()
        player = null
        val b = ensureBrowser()
        _state.update {
            it.copy(
                isFetching = true, fetchError = null, fetchHint = null, paginationInfo = null,
                fetchProgress = 0f, logLines = emptyList(), playlist = emptyList(),
                currentPlaylistId = "", syncingPlaylistId = "",
            )
        }
        val user = BiliUser(
            b,
            onLog = { msg -> appendLog(msg) },
            onHint = { h -> _state.update { it.copy(fetchHint = h) } },
            onProgress = { info -> _state.update { it.copy(paginationInfo = info) } },
            onFetchProgress = { p -> _state.update { it.copy(fetchProgress = p) } },
        )
        user.getFavlist(
            uid = uid,
            favName = favName,
            onResult = { tracks ->
                _state.update { it.copy(isFetching = false, fetchHint = null) }
                if (tracks.isEmpty()) {
                    _state.update { it.copy(fetchError = "收藏夹为空或不可见") }
                    return@getFavlist
                }
                val pl = PlaylistStore.add(
                    context = appContext,
                    name = Playlist.defaultName(favName, uid),
                    uid = uid,
                    favName = favName,
                    tracks = tracks,
                )
                playPlaylist(pl)
            },
            onError = { e ->
                _state.update { it.copy(isFetching = false, fetchHint = null, fetchError = e) }
            },
        )
    }

    /** 播放一个已缓存的列表——不抓取，直接用本地曲目起播。 */
    fun playPlaylist(pl: Playlist) {
        if (!::appContext.isInitialized) return
        if (pl.tracks.isEmpty()) {
            _state.update { it.copy(fetchError = "这个列表还没有内容") }
            return
        }
        currentPlaylistId = pl.id
        _state.update {
            it.copy(
                playlist = pl.tracks, fetchError = null, currentPlaylistId = pl.id,
                logLines = emptyList(), paginationInfo = null, fetchProgress = 0f,
            )
        }
        startPlayer(pl.tracks)
    }

    /** 同步：重新抓取该列表对应的收藏夹并覆盖缓存曲目。 */
    fun syncPlaylist(id: String) {
        if (!::appContext.isInitialized) return
        val pl = PlaylistStore.get(id) ?: return
        if (pl.uid.isBlank() || pl.favName.isBlank()) {
            _state.update { it.copy(fetchError = "这个列表没有记录来源收藏夹，无法同步") }
            return
        }
        val b = ensureBrowser()
        _state.update {
            it.copy(
                isFetching = true, fetchError = null, fetchHint = null, paginationInfo = null,
                fetchProgress = 0f, syncingPlaylistId = id,
            )
        }
        val user = BiliUser(
            b,
            onLog = { msg -> appendLog(msg) },
            onHint = { h -> _state.update { it.copy(fetchHint = h) } },
            onProgress = { info -> _state.update { it.copy(paginationInfo = info) } },
            onFetchProgress = { p -> _state.update { it.copy(fetchProgress = p) } },
        )
        user.getFavlist(
            uid = pl.uid,
            favName = pl.favName,
            onResult = { tracks ->
                _state.update { it.copy(isFetching = false, fetchHint = null, syncingPlaylistId = "") }
                if (tracks.isEmpty()) {
                    _state.update { it.copy(fetchError = "同步失败：收藏夹为空或不可见") }
                    return@getFavlist
                }
                val updated = PlaylistStore.replaceTracks(appContext, id, tracks)
                // 同步后曲目可能变了，裁掉已不在列表里的统计
                PlaybackStats.retainTracks(appContext, id, tracks.map { it.bvid })
                appendLog("已同步「${pl.name}」：${tracks.size} 个视频")
                // 正在播放这个列表 → 用新曲目重起，否则只更新库
                if (updated != null && currentPlaylistId == id) playPlaylist(updated)
            },
            onError = { e ->
                _state.update { it.copy(isFetching = false, fetchHint = null, syncingPlaylistId = "", fetchError = e) }
            },
        )
    }

    /** 停止播放并清空播放状态（返回列表库时调用）。 */
    fun stop() {
        player?.stop()
        player = null
        currentPlaylistId = ""
        _state.update {
            it.copy(
                playlist = emptyList(),
                currentIndex = -1,
                currentBvid = "",
                currentTitle = "",
                currentCover = "",
                currentP = 0,
                isPlaying = false,
                currentTime = 0.0,
                duration = 0.0,
                currentPlaylistId = "",
                syncingPlaylistId = "",
                paginationInfo = null,
                fetchProgress = 0f,
            )
        }
    }

    fun playPause() = player?.togglePause()
    fun next() = player?.next()
    fun prev() = player?.prev()
    fun seek(seconds: Double) = player?.seek(seconds)
    fun setVolume(v: Int) {
        Preferences.saveVolume(appContext, v)
        player?.requestVolume(v)
    }

    fun setPlayMode(mode: PlayMode) {
        Preferences.savePlayMode(appContext, mode)
        player?.requestPlayMode(mode)
    }

    /** 设置当前歌曲的分 P 偏好（null = 默认第 1P），保存并重新加载。 */
    fun setPPart(p: Int?) {
        val bvid = player?.currentBvid ?: return
        if (bvid.isBlank()) return
        val existing = Preferences.preference(appContext, bvid)
        Preferences.savePreference(appContext, bvid, existing.copy(p = p))
        player?.reloadCurrent()
    }

    /** 设置列表中指定歌曲的完整偏好（分P/开始秒/结束秒）。 */
    fun setTrackPreference(index: Int, pref: TrackPreference) {
        val track = player?.playlist?.getOrNull(index) ?: return
        Preferences.savePreference(appContext, track.bvid, pref)
        if (index == player?.currentIndex) {
            player?.reloadCurrent()
        }
    }

    /** 删除播放列表中指定项（同时清理其已保存的偏好与统计）。 */
    fun removeTrack(index: Int) {
        val track = player?.playlist?.getOrNull(index) ?: return
        Preferences.clearPreference(appContext, track.bvid)
        if (currentPlaylistId.isNotEmpty()) {
            PlaybackStats.removeTrack(appContext, currentPlaylistId, track.bvid)
        }
        player?.removeAt(index)
    }

    fun playIndex(i: Int) = player?.playIndex(i)

    /** 手动触发网页全屏（用户"显示 WebView"时调用）。 */
    fun enterFullscreen() = player?.enterWebFullscreen()

    /** 跟随 Compose 布局更新面板可见性；播放器据此决定是否尝试网页全屏。 */
    fun setWebPanelVisible(visible: Boolean) {
        webPanelVisible = visible
    }

    fun release() {
        player = null
        browser?.destroy()
        browser = null
        _webView.value = null
    }

    // ---------- 内部 ----------

    fun ensureBrowser(): HeadlessBrowser {
        return browser ?: HeadlessBrowser(appContext).also {
            it.onLog = { msg -> appendLog(msg) }
            it.ensureCreated()
            // 预热 WebView：先加载 about:blank 初始化 Chromium，避免首次真实加载慢导致抓取超时
            it.load("about:blank")
            browser = it
            _webView.value = it.view()
        }
    }

    private fun startPlayer(tracks: List<BiliTrack>) {
        val p = BiliMusicPlayer(ensureBrowser()).apply {
            playlist = tracks
            volume = _state.value.volume
            playMode = _state.value.playMode
            getPreference = { bvid -> Preferences.preference(appContext, bvid) }
            autoFullscreen = { Preferences.autoFullscreen(appContext) }
            onLog = { msg -> appendLog(msg) }
            onStateUpdate = { syncState() }
            // 视频失效被播放器剔除后，同步清理本地缓存与它的单曲偏好
            onTrackUnavailable = { bvid -> forgetTrack(bvid) }
            // 面板收起时不要尝试全屏（坐标会落在折叠页面上）
            isWebPanelVisible = { webPanelVisible }
        }
        player = p
        p.start()
    }

    /**
     * 视频失效：把它从本地缓存的播放列表里永久删掉，并清掉单曲偏好。
     * 内存里的播放列表由 BiliMusicPlayer 自己维护，这里只负责落盘那一份。
     */
    private fun forgetTrack(bvid: String) {
        if (bvid.isBlank()) return
        Preferences.clearPreference(appContext, bvid)
        // 不管这个列表是不是从库里来的，用户都该知道有首歌被跳过了
        _toast.tryEmit("视频失效，已从播放列表移除：$bvid")
        val id = currentPlaylistId
        if (id.isEmpty()) return
        PlaybackStats.removeTrack(appContext, id, bvid)
        val removed = PlaylistStore.removeTracks(appContext, id, listOf(bvid))
        if (removed.isNotEmpty()) appendLog("已从本地缓存移除失效视频：$bvid")
    }

    /** 播放中每秒累加一次听歌时长；只统计来自播放列表库的播放。 */
    private fun startStatsTicker() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (true) {
                delay(1000)
                val s = _state.value
                if (s.isPlaying && !s.isFetching &&
                    currentPlaylistId.isNotEmpty() && s.currentBvid.isNotEmpty()
                ) {
                    PlaybackStats.addSeconds(appContext, currentPlaylistId, s.currentBvid)
                }
            }
        }
    }

    // ---------- 歌词 ----------

    /** 当前曲目 id → 是否已经在搜；避免同一首重复请求。 */
    private val lyricsInFlight = mutableSetOf<String>()
    private var lastLyricsBvid: String = ""

    /**
     * 切歌时自动按「标题猜出来的歌名」搜歌词；已有歌词（含用户手填）就跳过。
     *
     * 抓取/网络偶发失败会让第一次搜索落空（手动再点一次往往就好了），
     * 所以这里做最多 3 次退避重试，并且每次都用「当时最新的标题」重新猜歌名——
     * 刚切歌时标题可能还没更新，用旧标题自然会搜不到。
     */
    private fun autoLoadLyrics(bvid: String, title: String) {
        if (bvid.isBlank()) return
        // 未同意歌词免责声明前不自动联网检索
        if (!Preferences.lyricsDisclaimerAccepted(appContext)) return
        if (LyricsStore.get(bvid)?.hasContent == true) return
        if (!lyricsInFlight.add(bvid)) return
        scope.launch {
            var lastQuery = guessSongName(title)
            var found: LyricsEntry? = null
            for (attempt in 0 until 3) {
                if (attempt > 0) delay(1500L * attempt)
                // 用户可能已经手动搜到，直接收手
                if (LyricsStore.get(bvid)?.hasContent == true) break
                val q = guessSongName(_state.value.currentTitle).ifBlank { lastQuery }
                lastQuery = q
                if (q.isBlank()) continue
                val results = LrcLib.search(q)
                val best = results.firstOrNull { it.hasContent }
                if (best != null) {
                    found = best
                    break
                }
            }
            val existing = LyricsStore.get(bvid)
            if (found != null && existing?.hasContent != true) {
                LyricsStore.put(
                    appContext,
                    found.copy(bvid = bvid, offsetMs = existing?.offsetMs ?: 0L)
                )
                appendLog("已匹配歌词：${found.trackName} - ${found.artistName}")
            } else if (existing?.hasContent != true) {
                appendLog("未找到「$lastQuery」的歌词，可手动指定歌名")
            }
            lyricsInFlight.remove(bvid)
        }
    }

    /** 用用户输入的歌名重新搜索当前曲目。 */
    fun searchLyricsForCurrent(query: String) {
        if (!::appContext.isInitialized) return
        if (!Preferences.lyricsDisclaimerAccepted(appContext)) {
            _state.update { it.copy(lyricsStatus = "请先同意歌词来源声明") }
            return
        }
        val bvid = _state.value.currentBvid
        if (bvid.isBlank()) return
        val q = query.trim()
        if (q.isEmpty()) return
        // 手动搜索优先：不因自动重试占着 in-flight 而被挡掉
        lyricsInFlight.add(bvid)
        _state.update {
            it.copy(lyricsSearching = true, lyricsStatus = null, lyricsSearchResults = emptyList())
        }
        scope.launch {
            // 网络偶发失败时再试一次，避免「手动点一遍才成功」
            var results = LrcLib.search(q)
            if (results.isEmpty()) {
                delay(1200)
                results = LrcLib.search(q)
            }
            // 只列出有内容的候选，带时间轴的排前面，交给用户自己挑
            val usable = results
                .filter { it.hasContent }
                .sortedByDescending { it.hasSyncedLines }
                .map { it.copy(bvid = bvid) }
            _state.update {
                it.copy(
                    lyricsSearching = false,
                    lyricsSearchResults = usable,
                    lyricsStatus = if (usable.isEmpty()) "没有搜到「$q」，换个歌名试试"
                    else "找到 ${usable.size} 条，点一条应用"
                )
            }
            lyricsInFlight.remove(bvid)
        }
    }

    /** 应用候选列表里的第 [index] 条歌词。 */
    fun applyLyricsResult(index: Int) {
        if (!::appContext.isInitialized) return
        val entry = _state.value.lyricsSearchResults.getOrNull(index) ?: return
        val bvid = _state.value.currentBvid
        if (bvid.isBlank()) return
        LyricsStore.put(
            appContext,
            entry.copy(bvid = bvid, offsetMs = LyricsStore.get(bvid)?.offsetMs ?: 0L)
        )
        _state.update {
            it.copy(
                lyricsSearchResults = emptyList(),
                lyricsStatus = "已应用：${entry.trackName} - ${entry.artistName}"
            )
        }
    }

    /**
     * 保存用户自己粘贴的歌词。带 `[mm:ss.xx]` 时间轴的按同步歌词处理，
     * 否则当纯文本展示。
     */
    fun saveLyricsForCurrent(text: String) {
        if (!::appContext.isInitialized) return
        val bvid = _state.value.currentBvid
        if (bvid.isBlank()) return
        val raw = text.trim()
        if (raw.isEmpty()) {
            LyricsStore.remove(appContext, bvid)
            _state.update { it.copy(lyricsStatus = "已清除该曲歌词") }
            return
        }
        val isSynced = Regex("\\[\\d{1,3}:\\d{1,2}").containsMatchIn(raw)
        LyricsStore.put(
            appContext,
            LyricsEntry(
                bvid = bvid,
                trackName = _state.value.currentTitle,
                synced = if (isSynced) raw else "",
                plain = if (isSynced) "" else raw,
                manual = true,
                offsetMs = LyricsStore.get(bvid)?.offsetMs ?: 0L,
            )
        )
        _state.update {
            it.copy(lyricsStatus = if (isSynced) "已保存同步歌词" else "已保存歌词（无时间轴）")
        }
    }

    // 当前曲目的歌词行缓存：syncState 每秒调用多次，不能每次都重新解析 LRC
    private var cachedLyricKey: String = ""
    private var cachedLyricLines: List<LyricLine> = emptyList()

    private fun lyricLineFor(bvid: String, positionSec: Double): String {
        if (bvid.isBlank()) return ""
        val entry = LyricsStore.get(bvid) ?: return ""
        if (!entry.hasSyncedLines) return ""
        val key = bvid + "|" + entry.synced.hashCode()
        if (key != cachedLyricKey) {
            cachedLyricLines = entry.lines()
            cachedLyricKey = key
        }
        // 偏移：正数让歌词延后出现
        val idx = activeLineIndex(cachedLyricLines, positionSec - entry.offsetMs / 1000.0)
        return cachedLyricLines.getOrNull(idx)?.text.orEmpty()
    }

    /**
     * 微调当前曲目的歌词偏移（毫秒）。
     * 不设上限：不同视频可能有整段前奏/片头差异，钳制反而挡住正当需求。
     */
    fun adjustLyricsOffset(deltaMs: Long) {
        if (!::appContext.isInitialized) return
        val bvid = _state.value.currentBvid
        if (bvid.isBlank()) return
        val entry = LyricsStore.get(bvid) ?: return
        val next = entry.offsetMs + deltaMs
        LyricsStore.put(appContext, entry.copy(offsetMs = next))
        _state.update { it.copy(lyricsStatus = "歌词偏移 ${next / 1000.0}s") }
    }

    /** 开关桌面歌词悬浮窗（悬浮窗本身由 PlayerService 管理）。 */
    fun setDesktopLyrics(enabled: Boolean) {
        if (!::appContext.isInitialized) return
        Preferences.saveDesktopLyrics(appContext, enabled)
        _state.update { it.copy(desktopLyrics = enabled) }
    }

    private fun syncState() {
        val p = player ?: return
        if (p.currentBvid.isNotBlank() && p.currentBvid != lastLyricsBvid) {
            lastLyricsBvid = p.currentBvid
            // 换歌时清掉上一首的检索提示，避免在弹窗里看到过期文案
            if (_state.value.lyricsStatus != null || _state.value.lyricsSearchResults.isNotEmpty()) {
                _state.update { it.copy(lyricsStatus = null, lyricsSearchResults = emptyList()) }
            }
            autoLoadLyrics(p.currentBvid, p.currentTitle)
        }
        val lyricLine = lyricLineFor(p.currentBvid, p.currentTime)
        _state.update {
            it.copy(
                playlist = p.playlist,
                currentIndex = p.currentIndex,
                currentLyricLine = lyricLine,
                currentBvid = p.currentBvid,
                currentTitle = p.currentTitle,
                currentCover = p.currentCover,
                currentP = p.currentP,
                isPlaying = p.isPlaying,
                currentTime = p.currentTime,
                duration = p.duration,
                volume = p.volume,
                playMode = p.playMode,
            )
        }
    }

    private fun appendLog(msg: String) {
        _state.update { it.copy(logLines = (it.logLines + msg).takeLast(200)) }
    }
}
