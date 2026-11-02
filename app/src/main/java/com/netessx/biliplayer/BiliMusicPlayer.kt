package com.netessx.biliplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
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

    /** 返回指定 bvid 的偏好（分P/开始秒/结束秒），由 PlayerController 注入。 */
    var getPreference: ((String) -> TrackPreference)? = null

    var onStateUpdate: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    /** 视频真正开始播放后回调一次（用于静默预取评论等）。 */
    var onVideoReady: (() -> Unit)? = null

    /**
     * 视频失效（页面出现「视频不见了」）时回调，参数是失效的 bvid。
     * 播放器自己会把它从内存播放列表里删掉并切下一首；
     * 这个回调交给 PlayerController 去同步删掉本地缓存里的那一条。
     */
    var onTrackUnavailable: ((String) -> Unit)? = null

    var currentIndex: Int = -1
        private set
    var currentBvid: String = ""
        private set
    var currentTitle: String = ""
        private set
    var currentCover: String = ""
        private set
    var currentP: Int = 0
        private set
    var currentBeginTime: Int = 0
        private set
    var currentEndTime: Int = 0
        private set
    var isPlaying: Boolean = false
        private set
    var currentTime: Double = 0.0
        private set
    var duration: Double = 0.0
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random()
    private val shuffleQueue = mutableListOf<Int>()
    private var pollTick: Runnable? = null
    private var loadingVideo = false
    private var lostCount = 0
    private var videoReadyHandled = false
    private var resumePosition = 0.0
    private var recoveryCount = 0

    init {
        // 必须在页面开始加载前安装：伪登录、完整评论区、登录弹窗拦截
        browser.documentStartScript = ANTI_LOGIN_JS
    }

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
        // 随机模式：用无重复洗牌队列，随机选起始曲目；其它模式从第 1 首开始
        if (playMode == PlayMode.SHUFFLE) {
            shuffleQueue.clear()
            playIndex(shuffleNext())
        } else {
            playIndex(0)
        }
    }

    /** 停止播放：停止轮询并暂停当前视频。 */
    fun stop() {
        stopPolling()
        loadingVideo = false
        browser.evaluate("(function(){var v=document.querySelector('video');if(v)v.pause();})()")
        isPlaying = false
        emit()
    }

    fun playIndex(index: Int) {
        if (playlist.isEmpty()) return
        resumePosition = 0.0 // 正常切歌不续播
        val i = ((index % playlist.size) + playlist.size) % playlist.size
        currentIndex = i
        loadCurrent()
    }

    /** 追加到当前内存队列，不写回本地歌单。 */
    fun appendTrack(track: BiliTrack): Boolean {
        if (track.bvid.isBlank()) return false
        if (playlist.any { it.bvid == track.bvid }) return false
        playlist = playlist + track
        emit()
        return true
    }

    /** 临时追加到当前内存队列；不写回本地歌单。 */
    fun addToQueue(track: BiliTrack): Boolean {
        val ok = appendTrack(track)
        if (ok) log("已临时加入播放队列：${track.title.ifBlank { track.bvid }}")
        return ok
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
        val pref = getPreference?.invoke(track.bvid) ?: TrackPreference()
        currentBvid = track.bvid
        currentTitle = track.title
        currentCover = track.cover
        currentP = pref.p ?: 0
        currentBeginTime = pref.beginTime ?: 0
        currentEndTime = pref.endTime ?: 0
        currentTime = 0.0
        duration = 0.0
        isPlaying = false
        loadingVideo = true
        lostCount = 0
        videoReadyHandled = false
        emit()
        log("播放第 ${currentIndex + 1}/${playlist.size}: $currentBvid${if (currentP > 0) "（第${currentP}P）" else ""}")

        browser.onPageFinished = { _ ->
            // 注入 stealth（尽力而为，仿照 python 的 init script）
            browser.evaluate(STEALTH_JS)
            waitForVideo()
        }
        val base = "https://www.bilibili.com/video/${track.bvid}"
        val url = if (currentP > 0) "$base?p=$currentP" else base
        browser.load(url)
    }

    /** 按当前歌曲重新加载（改分 P 等偏好后调用）。 */
    fun reloadCurrent() {
        if (playlist.isNotEmpty() && currentIndex in playlist.indices) {
            loadCurrent()
        }
    }

    /** 从播放列表删除指定项；删除当前项则跳到下一首。 */
    fun removeAt(index: Int) {
        if (index !in playlist.indices) return
        val wasCurrent = index == currentIndex
        playlist = playlist.toMutableList().also { it.removeAt(index) }
        shuffleQueue.clear() // 播放列表变化后重建洗牌队列，避免索引错位
        if (playlist.isEmpty()) {
            stopPolling()
            currentIndex = -1
            currentBvid = ""
            currentTitle = ""
            currentCover = ""
            currentP = 0
            currentBeginTime = 0
            currentEndTime = 0
            isPlaying = false
            emit()
            return
        }
        if (wasCurrent) {
            currentIndex = currentIndex.coerceAtMost(playlist.size - 1)
            loadCurrent()
        } else {
            if (index < currentIndex) currentIndex -= 1
            emit()
        }
    }

    /**
     * 等 `<video>` 出现。轮询期间顺便探测「视频不见了」错误页——
     * 那种页面永远不会有 `<video>`，等满超时只会白等，不如立刻判定失效。
     */
    private fun waitForVideo() {
        val startTime = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                browser.evaluate(VIDEO_PROBE_JS) { raw ->
                    when (unwrapString(raw)) {
                        "ok" -> onVideoReady()
                        "gone" -> onVideoGone()
                        else -> if (System.currentTimeMillis() - startTime >= VIDEO_WAIT_MS) {
                            log("等待 <video> 元素超时")
                            loadingVideo = false
                            emit()
                        } else {
                            mainHandler.postDelayed(this, POLL_MS)
                        }
                    }
                }
            }
        }
        mainHandler.post(tick)
    }

    /**
     * 当前视频失效：从播放列表删掉这一条，然后播下一首。
     * [removeAt] 删除当前项时本身就会加载下一首；列表被删空则自动停止。
     */
    private fun onVideoGone() {
        val goneBvid = currentBvid
        val goneIndex = currentIndex
        if (goneIndex !in playlist.indices) return
        log("视频不见了，已从播放列表移除：$goneBvid")
        onTrackUnavailable?.invoke(goneBvid)
        stopPolling()
        removeAt(goneIndex)
    }



    private fun onVideoReady() {
        // onPageFinished 会触发多次，这里保证每首歌只执行一次初始化逻辑
        if (videoReadyHandled) return
        videoReadyHandled = true
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
                    // 若设置了开播秒（beginTime），跳到对应时间点
                    if (currentBeginTime > 0) {
                        browser.evaluate(
                            "(function(){var v=document.querySelector('video');if(v)v.currentTime=${currentBeginTime};})()"
                        )
                    }
                    // 视频状态丢失后恢复：续播到上次位置
                    if (resumePosition > 0) {
                        browser.evaluate(
                            "(function(){var v=document.querySelector('video');if(v)v.currentTime=${resumePosition};})()"
                        )
                        log("恢复续播: ${resumePosition}秒")
                        resumePosition = 0.0
                    }
                    loadingVideo = false
                    isPlaying = true
                    log("开始播放: $currentTitle ($currentBvid)${if (currentEndTime > 0) "（${currentEndTime}s 截断）" else ""}")
                    emit()
                    startPolling()
                    onVideoReady?.invoke()
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
            recoveryCount = 0
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
            // 自动截断（endTime）：到达结束时间即切下一首
            if (currentEndTime > 0 && currentTime >= currentEndTime) {
                log("到达结束时间 ${currentEndTime}s，自动切下一首")
                stopPolling()
                advanceTrack()
                return
            }
            if (ended || nearEnd) {
                log("播放结束: $currentTitle")
                stopPolling()
                advanceTrack()
                return
            }
            mainHandler.postDelayed(self, POLL_MS)
        } else {
            // 播放途中视频被下架：页面会换成「视频不见了」，直接删掉换下一首，
            // 不要走"状态丢失→重载"那条路（重载多少次都还是这个错误页）
            if (obj != null && obj.optBoolean("gone", false)) {
                onVideoGone()
                return
            }
            if (++lostCount >= MAX_LOST) {
                // 视频状态丢失：自动重载网页恢复播放，尽量续播到上次位置
                if (++recoveryCount > MAX_RECOVERY) {
                    log("多次恢复失败，停止轮询")
                    return
                }
                recoverFromLoss()
                return
            }
            mainHandler.postDelayed(self, POLL_MS)
        }
    }

    /** 视频状态丢失后自动重载当前视频，并记录上次位置以便续播。 */
    private fun recoverFromLoss() {
        val lastPos = currentTime
        log("视频状态丢失，自动重新加载${if (lastPos > 0) "（从 ${lastPos} 秒续播）" else ""}")
        resumePosition = lastPos
        if (playlist.isNotEmpty() && currentIndex in playlist.indices) {
            loadCurrent()
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
                if (paused)
                    "(function(){window.__biliSetManualPause&&window.__biliSetManualPause(false);var v=document.querySelector('video');if(v)v.play();})()"
                else
                    "(function(){window.__biliSetManualPause&&window.__biliSetManualPause(true);var v=document.querySelector('video');if(v)v.pause();})()"
            )
        }
    }

    private fun advanceTrack() {
        if (playlist.isEmpty()) return
        val nextIndex = when (playMode) {
            PlayMode.REPEAT_ONE -> currentIndex
            PlayMode.SEQUENTIAL -> (currentIndex + 1) % playlist.size
            PlayMode.SHUFFLE -> shuffleNext()
        }
        playIndex(nextIndex)
    }

    /**
     * 返回下一个不重复的随机曲目索引。
     * 用一份洗牌好的索引队列顺序播放；整轮播完后重建队列（并把当前曲目放到最后，
     * 避免跨轮立刻重复当前曲）。
     */
    private fun shuffleNext(): Int {
        if (shuffleQueue.isEmpty()) {
            val indices = (0 until playlist.size).toMutableList()
            if (playlist.size > 1 && currentIndex in indices) {
                indices.remove(currentIndex)
                indices.shuffle()
                indices.add(currentIndex)
            } else {
                indices.shuffle()
            }
            shuffleQueue.addAll(indices)
        }
        return shuffleQueue.removeAt(0)
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
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    companion object {
        private const val TAG = "BiliMusicPlayer"
        private const val POLL_MS = 500L
        private const val MAX_LOST = 20
        private const val MAX_RECOVERY = 3
        private const val VIDEO_WAIT_MS = 40_000L

        /**
         * 探测播放页状态：
         *   "ok"   — 已经有 <video>，可以开始播放
         *   "gone" — 出现「视频不见了」错误页，视频确实失效
         *   "wait" — 都还没有，继续等
         * 只认 .error-text 里的文案，避免把普通加载中误判成失效。
         */
        private val VIDEO_PROBE_JS = """
            (function(){
              if (document.querySelector('video')) return 'ok';
              var t = document.querySelector('.error-text');
              var s = (t && (t.innerText || t.textContent)) || '';
              if (/视频不见了|视频不存在|视频已失效/.test(s)) return 'gone';
              return 'wait';
            })()
        """.trimIndent()

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

        /**
         * document-start 注入：参考 vruses/beefreely 的未登录体验修复。
         *
         * - 已登录（存在 DedeUserID__ckMd5）时完全跳过，避免干扰真实账号；
         * - 伪造 DedeUserID 与 nav / player 响应，消除登录弹窗与评论区遮罩；
         * - 评论区请求去掉 cookie（credentials: omit），解除未登录只显示三条的限制；
         * - 屏蔽 miniLogin 脚本并隐藏登录弹层。
         */
        private val ANTI_LOGIN_JS = """
            (function(){
              try {
                if (window.__biliAntiLoginInstalled) return;
                window.__biliAntiLoginInstalled = true;
                if (window.top !== window.self) return;
                if (location.hostname === 'passport.bilibili.com') return;

                // 真实登录标记存在时不启用伪登录
                if (/(?:^|;\s*)DedeUserID__ckMd5=([^;]+)/.test(document.cookie)) return;
                window.__biliAntiLoginActive = true;

                // 1. 伪 DedeUserID：部分前端逻辑只检查这个字段
                if (!/(?:^|;\s*)DedeUserID=/.test(document.cookie)) {
                  var fakeMid = String(Math.floor(Math.random() * 9007199254740000) + 1);
                  document.cookie = 'DedeUserID=' + fakeMid + '; path=/; domain=.bilibili.com';
                }

                // 2. 屏蔽登录脚本与登录弹层
                var origAppend = Node.prototype.appendChild;
                Node.prototype.appendChild = function(child){
                  try {
                    if (child && child.tagName === 'SCRIPT' && child.src &&
                        (child.src.indexOf('miniLogin') >= 0 ||
                         child.src.indexOf('passport.bilibili.com') >= 0)) {
                      return child;
                    }
                  } catch(e) {}
                  return origAppend.call(this, child);
                };
                function hideLoginLayers(){
                  try {
                    if (!document.head || document.getElementById('bili-anti-login-style')) return;
                    var style = document.createElement('style');
                    style.id = 'bili-anti-login-style';
                    style.textContent = '.bili-mini-mask,.bili-mini-register,.bili-mini-login,.bili-mini-mask-wrp,.bili-login-dialog,.bili-login-card,.login-panel,.login-mask{display:none!important}';
                    document.head.appendChild(style);
                  } catch(e) {}
                }
                hideLoginLayers();
                document.addEventListener('DOMContentLoaded', hideLoginLayers);

                // 3. 伪登录响应
                var MOCK_NAV = {
                  code: 0, message: '0', ttl: 1,
                  data: {
                    isLogin: true, mid: 3493281916783362, uname: 'bilibili',
                    face: 'https://i0.hdslb.com/bfs/face/0c84b9f4ad546d3f20324809d45fc439a2a8ddab.jpg',
                    email_verified: 1, mobile_verified: 1, money: 9999.99, moral: 70,
                    level_info: { current_level: 6, current_min: 28800, current_exp: 29050, next_exp: '--' },
                    vipStatus: 0, vipType: 0, is_senior_member: 1, answer_status: 0,
                    official: { role: 0, title: '', desc: '', type: -1 },
                    officialVerify: { type: -1, desc: '' },
                    wallet: { mid: 3493281916783362, bcoin_balance: 0, coupon_balance: 0, coupon_due_time: 0 },
                    wbi_img: { img_url: '', sub_url: '' }
                  }
                };
                var MOCK_ARCHIVE_RELATION = {
                  code: 0, message: '0', ttl: 1,
                  data: { attention: false, favorite: false, season_fav: false, like: false, dislike: false, coin: 0 }
                };
                var MOCK_RELATION = {
                  code: 0, message: '0', ttl: 1,
                  data: {
                    relation: { mid: 0, attribute: 0, mtime: 0, tag: null, special: 0 },
                    be_relation: { mid: 0, attribute: 0, mtime: 0, tag: null, special: 0 }
                  }
                };
                var MOCK_SPACE_SEARCH = {
                  code: 0, message: '0', ttl: 1,
                  data: {
                    list: { slist: [], tlist: {}, vlist: [] },
                    page: {}, episodic_button: {}, is_risk: false, gaia_res_type: 0, gaia_data: null
                  }
                };

                function mockFor(url){
                  if (url.indexOf('/x/web-interface/nav') >= 0) return MOCK_NAV;
                  if (url.indexOf('/x/web-interface/archive/relation') >= 0) return MOCK_ARCHIVE_RELATION;
                  if (url.indexOf('/x/web-interface/relation') >= 0) return MOCK_RELATION;
                  return null;
                }
                function patchPlayer(obj){
                  try {
                    var data = obj && obj.data ? obj.data : obj;
                    if (data) {
                      data.login_mid = 3493281916783362;
                      data.need_login_subtitle = false;
                      if (!data.level_info) data.level_info = {};
                      data.level_info.current_level = 6;
                    }
                  } catch(e) {}
                  return obj;
                }
                function isComment(url){
                  return url.indexOf('/x/v2/reply/wbi/main') >= 0 ||
                         url.indexOf('/x/v2/reply/reply') >= 0;
                }
                function stripCommentDevice(url){
                  if (url.indexOf('/x/v2/reply/reply') < 0) return url;
                  try {
                    var u = new URL(url, location.href);
                    u.searchParams.delete('x-bili-device-req-json');
                    return u.href;
                  } catch(e) { return url; }
                }
                function jsonResponse(obj){
                  return new Response(JSON.stringify(obj), {
                    status: 200,
                    statusText: 'OK',
                    headers: { 'Content-Type': 'application/json; charset=utf-8' }
                  });
                }

                // 4. fetch 钩子
                if (typeof window.fetch === 'function') {
                  var origFetch = window.fetch;
                  window.fetch = function(input, init){
                    var url = '';
                    try { url = typeof input === 'string' ? input : (input && input.url) || ''; } catch(e) {}
                    try {
                      if (isComment(url)) {
                        url = stripCommentDevice(url);
                        init = Object.assign({}, init || {});
                        init.credentials = 'omit';
                        input = (typeof input === 'string') ? url : new Request(url, input);
                      }
                      var promise = origFetch.call(this, input, init);
                      if (url.indexOf('/x/player/wbi/v2') >= 0) {
                        return promise.then(function(res){
                          return res.clone().json().then(function(obj){
                            return jsonResponse(patchPlayer(obj));
                          }).catch(function(){ return res; });
                        });
                      }
                      if (url.indexOf('/x/space/wbi/arc/search') >= 0) {
                        return promise.then(function(res){
                          return res.clone().json().then(function(obj){
                            return (obj && obj.code) ? jsonResponse(MOCK_SPACE_SEARCH) : res;
                          }).catch(function(){ return res; });
                        });
                      }
                      var mock = mockFor(url);
                      if (mock) return promise.then(function(){ return jsonResponse(mock); });
                      return promise;
                    } catch(e) {
                      return origFetch.apply(this, arguments);
                    }
                  };
                }

                // 5. XHR 钩子
                try {
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(method, url){
                    var target = String(url || '');
                    if (target.indexOf('/x/v2/reply/reply') >= 0) {
                      target = stripCommentDevice(target);
                      arguments[1] = target;
                    }
                    this.__biliUrl = target;
                    return origOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function(body){
                    var url = this.__biliUrl || '';
                    if (isComment(url)) {
                      try { this.withCredentials = false; } catch(e) {}
                    }
                    if (url.indexOf('/x/player/wbi/v2') >= 0) this.__biliPatchPlayer = true;
                    if (url.indexOf('/x/space/wbi/arc/search') >= 0) this.__biliSpaceSearch = true;
                    var mock = mockFor(url);
                    if (mock) this.__biliMock = mock;
                    return origSend.apply(this, arguments);
                  };
                  var descText = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
                  var descResponse = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');
                  function mockXhr(xhr){
                    if (xhr.__biliMock) return xhr.__biliMock;
                    if (!xhr.__biliPatchPlayer && !xhr.__biliSpaceSearch) return null;
                    try {
                      var obj = null;
                      if (xhr.responseType === 'json') {
                        obj = descResponse.get.call(xhr);
                      } else if (xhr.responseType === '' || xhr.responseType === 'text') {
                        obj = JSON.parse(descText.get.call(xhr));
                      }
                      if (!obj) return null;
                      if (xhr.__biliSpaceSearch) {
                        xhr.__biliMock = obj.code ? MOCK_SPACE_SEARCH : obj;
                      } else {
                        xhr.__biliMock = patchPlayer(obj);
                      }
                      return xhr.__biliMock;
                    } catch(e) { return null; }
                  }
                  if (descText && descText.get) {
                    Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
                      configurable: true,
                      get: function(){
                        var mock = mockXhr(this);
                        return mock ? JSON.stringify(mock) : descText.get.call(this);
                      }
                    });
                  }
                  if (descResponse && descResponse.get) {
                    Object.defineProperty(XMLHttpRequest.prototype, 'response', {
                      configurable: true,
                      get: function(){
                        var mock = mockXhr(this);
                        if (mock) return this.responseType === 'json' ? mock : JSON.stringify(mock);
                        return descResponse.get.call(this);
                      }
                    });
                  }
                } catch(e) {}
              } catch(e) {}
            })();
        """.trimIndent()

        private val ANTI_PAUSE_JS = """
            (function(){
              // 真实登录时保持原有行为；伪登录状态仍需要防暂停
              if (document.cookie.indexOf('DedeUserID__ckMd5') >= 0 && !window.__biliAntiLoginActive) return;
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
              // 6. 手动暂停标记（App 通过 window.__biliSetManualPause 设置）
              window.__biliManualPause = false;
              window.__biliSetManualPause = function(v){ window.__biliManualPause = !!v; };
              // 7. 页面隐藏时拦截一切自动暂停；回到前台自动续播
              var origPause = HTMLMediaElement.prototype.pause;
              HTMLMediaElement.prototype.pause = function(){
                if (document.hidden && !window.__biliManualPause) return;
                return origPause.apply(this, arguments);
              };
              document.addEventListener('visibilitychange', function(){
                if (document.hidden) return;
                var v = document.querySelector('video');
                if (!window.__biliManualPause && v && v.paused) { try { v.play(); } catch(e){} }
              });
            })();
        """.trimIndent()

        private val GET_STATE_JS = """
            (function(){
              var v = document.querySelector('video');
              if (!v) {
                var t = document.querySelector('.error-text');
                var s = (t && (t.innerText || t.textContent)) || '';
                var gone = /视频不见了|视频不存在|视频已失效/.test(s);
                return JSON.stringify({ok:false, gone:gone});
              }
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
