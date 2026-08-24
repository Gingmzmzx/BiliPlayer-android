package com.netessx.biliplayer

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 播放器对外 UI 状态。 */
data class PlayerUiState(
    val isFetching: Boolean = false,
    val fetchError: String? = null,
    val fetchHint: String? = null,
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

    private val _webView = MutableStateFlow<WebView?>(null)
    val webView: StateFlow<WebView?> = _webView

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _state.update {
            it.copy(
                volume = Preferences.defaultVolume(appContext),
                playMode = Preferences.playMode(appContext),
            )
        }
    }

    /** 抓取收藏夹并开始播放（复刻 run.py：get_favlist -> BiliMusicPlayer.play）。 */
    fun fetchAndStart(uid: String, favName: String) {
        if (!::appContext.isInitialized) return
        player?.stop()
        player = null
        val b = ensureBrowser()
        _state.update {
            it.copy(isFetching = true, fetchError = null, fetchHint = null, logLines = emptyList(), playlist = emptyList())
        }
        val user = BiliUser(
            b,
            onLog = { msg -> appendLog(msg) },
            onHint = { h -> _state.update { it.copy(fetchHint = h) } },
        )
        user.getFavlist(
            uid = uid,
            favName = favName,
            onResult = { tracks ->
                _state.update { it.copy(isFetching = false, fetchHint = null, playlist = tracks) }
                if (tracks.isNotEmpty()) {
                    startPlayer(tracks)
                } else {
                    _state.update { it.copy(fetchError = "收藏夹为空或不可见") }
                }
            },
            onError = { e ->
                _state.update { it.copy(isFetching = false, fetchHint = null, fetchError = e) }
            },
        )
    }

    /** 停止播放并清空播放状态（返回设置时调用）。 */
    fun stop() {
        player?.stop()
        player = null
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
            onLog = { msg -> appendLog(msg) }
            onStateUpdate = { syncState() }
        }
        player = p
        p.start()
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
