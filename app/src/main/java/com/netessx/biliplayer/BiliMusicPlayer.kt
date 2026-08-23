package com.netessx.biliplayer

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Random

enum class PlayMode { SEQUENTIAL, SHUFFLE, REPEAT_ONE }

/**
 * 复刻 Python 项目 player.py：
 * 用无头浏览器加载 `https://www.bilibili.com/video/{bvid}`，等待 `<video>` 出现，
 * 注入 stealth + 防暂停脚本后自动播放，并通过轮询读取/控制播放状态。
 * 支持顺序 / 随机 / 单曲循环，播完自动切下一首。
 */
class BiliMusicPlayer(private val browser: HeadlessBrowser) {

    var playlist: List<BiliTrack> = emptyList()
    var playMode: PlayMode = PlayMode.SHUFFLE
    var volume: Int = 30

    var onStateUpdate: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    var currentIndex: Int = -1
        private set
    var currentBvid: String = ""
        private set
    var currentTitle: String = ""
        private set
    var isPlaying: Boolean = false
        private set
    var currentTime: Double = 0.0
        private set
    var duration: Double = 0.0
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random()
    private var pollTick: Runnable? = null
    private var loadingVideo = false
    private var lostCount = 0

    // 待处理命令（对应 Python PlayState）
    private var pendingNext = false
    private var pendingPause = false
    private var pendingVolume = -1
    private var pendingProgress = -1.0

    fun start() {
        if (playlist.isEmpty()) {
            log("播放列表为空")
            return
        }
        playIndex(0)
    }

    /** 停止轮询（切歌/重新抓取时调用）。 */
    fun stop() {
        stopPolling()
        loadingVideo = false
    }

    fun playIndex(index: Int) {
        if (playlist.isEmpty()) return
        val i = ((index % playlist.size) + playlist.size) % playlist.size
        currentIndex = i
        loadCurrent()
    }

    fun next() {
        pendingNext = true
    }

    fun prev() {
        if (playlist.isEmpty()) return
        playIndex(currentIndex - 1)
    }

    fun togglePause() {
        pendingPause = true
    }

    fun seek(seconds: Double) {
        pendingProgress = seconds.coerceAtLeast(0.0)
    }

    fun requestVolume(v: Int) {
        pendingVolume = v.coerceIn(0, 100)
    }

    fun requestPlayMode(mode: PlayMode) {
        playMode = mode
        emit()
    }

    // ---------- 内部实现 ----------

    private fun loadCurrent() {
        stopPolling()
        val track = playlist[currentIndex]
        currentBvid = track.bvid
        currentTitle = track.title
        currentTime = 0.0
        duration = 0.0
        isPlaying = false
        loadingVideo = true
        lostCount = 0
        emit()
        log("播放第 ${currentIndex + 1}/${playlist.size}: $currentBvid")

        browser.onPageFinished = { _ ->
            // 注入 stealth（尽力而为，仿照 python 的 init script）
            browser.evaluate(STEALTH_JS)
            waitForVideo()
        }
        browser.load("https://www.bilibili.com/video/${track.bvid}")
    }

    private fun waitForVideo() {
        waitForJs(
            "(function(){ return !!document.querySelector('video'); })()",
            timeoutMs = 40000,
            onReady = { onVideoReady() },
            onTimeout = {
                log("等待 <video> 元素超时")
                loadingVideo = false
                emit()
            },
        )
    }

    private fun onVideoReady() {
        // 读取标题
        browser.evaluate("(function(){var h=document.querySelector('h1');return h?h.innerText:'';})()") { rawTitle ->
            val t = unwrapString(rawTitle)
            if (t.isNotBlank()) {
                currentTitle = t
                emit()
            }
            // 取消静音 + 设置音量 + 自动播放（mediaPlaybackRequiresUserGesture 已允许无手势播放）
            val setupJs = """
                (function(){
                  var v = document.querySelector('video');
                  if (!v) return;
                  v.muted = false;
                  v.volume = ${volume / 100.0};
                  try { v.play(); } catch(e){}
                })();
            """.trimIndent()
            browser.evaluate(setupJs) { _ ->
                // 注入防暂停/防弹窗/防自动连播
                browser.evaluate(ANTI_PAUSE_JS) { _ ->
                    loadingVideo = false
                    isPlaying = true
                    log("开始播放: $currentTitle ($currentBvid)")
                    emit()
                    startPolling()
                }
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        val tick = object : Runnable {
            override fun run() {
                if (loadingVideo) return
                if (handlePendingCommands()) return
                browser.evaluate(GET_STATE_JS) { raw -> handleState(raw, this) }
            }
        }
        pollTick = tick
        mainHandler.post(tick)
    }

    private fun handleState(raw: String, self: Runnable) {
        val obj = parseJsonObj(raw)
        if (obj != null && obj.optBoolean("ok", false)) {
            lostCount = 0
            currentTime = obj.optDouble("currentTime", currentTime)
            duration = obj.optDouble("duration", duration)
            isPlaying = !obj.optBoolean("paused", false)
            val ended = obj.optBoolean("ended", false)
            val nearEnd = duration > 0 && currentTime >= duration - 1.5
            val t = obj.optString("title", "")
            if (t.isNotBlank() && t != currentTitle) {
                currentTitle = t
            }
            emit()
            if (ended || nearEnd) {
                log("播放结束: $currentTitle")
                stopPolling()
                advanceTrack()
                return
            }
            mainHandler.postDelayed(self, POLL_MS)
        } else {
            if (++lostCount >= MAX_LOST) {
                log("视频状态丢失，停止轮询")
                return
            }
            mainHandler.postDelayed(self, POLL_MS)
        }
    }

    /** 处理待执行命令；返回 true 表示已切歌（轮询由 loadCurrent 重新启动）。 */
    private fun handlePendingCommands(): Boolean {
        if (pendingNext) {
            pendingNext = false
            stopPolling()
            advanceTrack()
            return true
        }
        if (pendingPause) {
            pendingPause = false
            toggleVideoPause()
            return false
        }
        if (pendingVolume != -1) {
            val v = pendingVolume
            pendingVolume = -1
            volume = v
            browser.evaluate("(function(){var v=document.querySelector('video');if(v)v.volume=${v / 100.0};})()")
            emit()
            return false
        }
        if (pendingProgress >= 0) {
            val p = pendingProgress
            pendingProgress = -1.0
            browser.evaluate("(function(){var v=document.querySelector('video');if(v)v.currentTime=${p};})()")
            return false
        }
        return false
    }

    private fun toggleVideoPause() {
        browser.evaluate("(function(){var v=document.querySelector('video');return v?v.paused:true;})()") { raw ->
            val paused = raw.trim().equals("true", ignoreCase = true)
            browser.evaluate(
                if (paused) "(function(){var v=document.querySelector('video');if(v)v.play();})()"
                else "(function(){var v=document.querySelector('video');if(v)v.pause();})()"
            )
        }
    }

    private fun advanceTrack() {
        if (playlist.isEmpty()) return
        val nextIndex = when (playMode) {
            PlayMode.REPEAT_ONE -> currentIndex
            PlayMode.SEQUENTIAL -> (currentIndex + 1) % playlist.size
            PlayMode.SHUFFLE -> random.nextInt(playlist.size)
        }
        playIndex(nextIndex)
    }

    private fun waitForJs(conditionJs: String, timeoutMs: Long, onReady: () -> Unit, onTimeout: () -> Unit) {
        val startTime = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                browser.evaluate(conditionJs) { raw ->
                    if (raw.trim().equals("true", ignoreCase = true)) {
                        onReady()
                    } else if (System.currentTimeMillis() - startTime >= timeoutMs) {
                        onTimeout()
                    } else {
                        mainHandler.postDelayed(this, POLL_MS)
                    }
                }
            }
        }
        mainHandler.post(tick)
    }

    private fun stopPolling() {
        pollTick?.let { mainHandler.removeCallbacks(it) }
        pollTick = null
    }

    private fun unwrapString(raw: String): String {
        return try {
            JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            raw
        }
    }

    private fun parseJsonObj(raw: String): JSONObject? {
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is JSONObject) v else JSONObject(v.toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun emit() {
        onStateUpdate?.invoke()
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }

    companion object {
        private const val POLL_MS = 500L
        private const val MAX_LOST = 20

        private val STEALTH_JS = """
            (() => {
              try { Object.defineProperty(navigator, 'webdriver', { get: () => false }); } catch(e){}
              if (!window.chrome) { window.chrome = { runtime:{}, loadTimes:function(){}, csi:function(){}, app:{} }; }
              if (!window.chrome.runtime) { window.chrome.runtime = {}; }
              try {
                Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN','zh','en-US','en'] });
                Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
              } catch(e){}
            })();
        """.trimIndent()

        private val ANTI_PAUSE_JS = """
            (function(){
              if (document.cookie.indexOf('DedeUserID') >= 0) return;
              if (window.location.hostname !== 'www.bilibili.com') return;
              // 1. 屏蔽登录弹窗脚本
              var oAppend = Node.prototype.appendChild;
              Node.prototype.appendChild = function(child){
                if (child && child.tagName === 'SCRIPT' && child.src && child.src.indexOf('miniLogin') >= 0) return null;
                return oAppend.call(this, child);
              };
              // 2. 记录手动点击
              var isClickedRecently = false;
              document.body.addEventListener('click', function(){
                isClickedRecently = true;
                setTimeout(function(){ isClickedRecently = false; }, 500);
              });
              // 3. 隐藏登录弹层
              var style = document.createElement('style');
              style.textContent = '.bili-mini-mask,.bili-mini-register,.login-panel{display:none!important}';
              document.head.appendChild(style);
              // 4. 关闭自动连播
              var btn = document.querySelector('.continuous-btn');
              if (btn) btn.click();
              // 5. 等待播放器就绪后覆盖 pause / getMediaInfo，禁止自动暂停
              var timer = setInterval(function(){
                if (window.player && window.player.pause && window.player.getMediaInfo) {
                  clearInterval(timer);
                  var op = window.player.pause;
                  window.player.pause = function(){ if (!isClickedRecently) return; return op.call(this); };
                  var gmi = window.player.getMediaInfo;
                  window.player.getMediaInfo = function(){
                    var info = gmi.call(this);
                    if (info) info.absolutePlayTime = 0;
                    return info;
                  };
                }
              }, 500);
            })();
        """.trimIndent()

        private val GET_STATE_JS = """
            (function(){
              var v = document.querySelector('video');
              if (!v) return JSON.stringify({ok:false});
              return JSON.stringify({
                ok: true,
                paused: v.paused,
                currentTime: v.currentTime,
                duration: v.duration,
                ended: v.ended,
                title: (document.querySelector('h1')?.innerText || '').trim()
              });
            })()
        """.trimIndent()
    }
}
