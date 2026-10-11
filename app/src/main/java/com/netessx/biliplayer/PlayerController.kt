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

    private val _webView = MutableStateFlow<WebView?>(null)
    val webView: StateFlow<WebView?> = _webView

    /** 一次性提示（Toast）。用 SharedFlow 而不是状态字段，避免重组时重复弹。 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    /** 当前播放的列表 id；空串表示不在列表库里。 */
    private var currentPlaylistId: String = ""

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        PlaylistStore.init(appContext)
        _state.update {
            it.copy(
                volume = Preferences.defaultVolume(appContext),
                playMode = Preferences.playMode(appContext),
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

    /** 删除播放列表中指定项（同时清理其已保存的偏好）。 */
    fun removeTrack(index: Int) {
        val track = player?.playlist?.getOrNull(index) ?: return
        Preferences.clearPreference(appContext, track.bvid)
        player?.removeAt(index)
    }

    fun playIndex(i: Int) = player?.playIndex(i)

    /** 手动触发网页全屏（用户"显示 WebView"时调用）。 */
    fun enterFullscreen() = player?.enterWebFullscreen()

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
        val removed = PlaylistStore.removeTracks(appContext, id, listOf(bvid))
        if (removed.isNotEmpty()) appendLog("已从本地缓存移除失效视频：$bvid")
    }

    private fun syncState() {
        val p = player ?: return
        _state.update {
            it.copy(
                playlist = p.playlist,
                currentIndex = p.currentIndex,
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
