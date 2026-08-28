package com.netessx.biliplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 一条收藏夹视频。 */
data class BiliTrack(val bvid: String, val title: String = "", val cover: String = "")

/**
 * 复刻 Python 项目 user.py：
 * 在无头浏览器中打开 `https://space.bilibili.com/{uid}/favlist?ftype=create`，
 * 打开目标收藏夹，抓取 `.bili-video-card` 卡片得到 {bvid, title, cover} 列表。
 * 完全基于 DOM，不请求任何 B 站 API。
 *
 * 打开收藏夹优先方式：从侧栏元素直接提取 fid 并导航到 `?fid=...`（不依赖点击）；
 * 提取不到时回退为真实触摸点击。
 */
class BiliUser(
    private val browser: HeadlessBrowser,
    private val onLog: (String) -> Unit = {},
    private val onHint: (String) -> Unit = {},
    private val onProgress: (String) -> Unit = {},
    private val onFetchProgress: (Float) -> Unit = {},
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var contentWaitStarted = false
    private var manualHinted = false
    private val accumulatedTracks = mutableListOf<BiliTrack>()
    private var totalPages = 0
    private var totalItems = 0
    private var currentPage = 0

    fun getFavlist(
        uid: String,
        favName: String,
        onResult: (List<BiliTrack>) -> Unit,
        onError: (String) -> Unit,
    ) {
        var stage = 0 // 0=等待侧栏, 1=已打开等待内容
        var sidebarWaitStarted = false
        contentWaitStarted = false
        manualHinted = false
        accumulatedTracks.clear()
        log("开始抓取 UID=$uid 收藏夹=$favName")
        browser.onPageStarted = { _ -> browser.evaluate(STEALTH_JS) }
        browser.onPageFinished = { _ ->
            browser.evaluate(STEALTH_JS)
            // onPageFinished 会触发多次，用 sidebarWaitStarted 保证只启动一次侧栏等待
            if (stage == 0 && !sidebarWaitStarted) {
                sidebarWaitStarted = true
                log("页面已加载，等待收藏夹侧栏…")
                waitForJs(
                    SIDEBAR_JS,
                    timeoutMs = 40000,
                    onReady = {
                        stage = 1
                        openFolder(uid, favName, onResult, onError)
                    },
                    onTimeout = {
                        hintManualClick(favName)
                        stage = 1
                        waitForContent(favName, onResult, onError)
                    },
                )
            } else if (stage == 1 && !contentWaitStarted) {
                contentWaitStarted = true
                waitForContent(favName, onResult, onError)
            }
        }
        browser.load("https://space.bilibili.com/$uid/favlist?ftype=create")
    }

    private fun openFolder(uid: String, favName: String, onResult: (List<BiliTrack>) -> Unit, onError: (String) -> Unit) {
        val selector = ".fav-sidebar-item[title=\"${escapeCssAttr(favName)}\"]"
        browser.evaluate(EXTRACT_FID_JS(selector)) { raw ->
            val obj = parseObj(raw)
            if (obj != null && obj.optBoolean("found", false)) {
                val fid = obj.optString("fid", "")
                if (fid.isNotBlank()) {
                    log("从侧栏提取到收藏夹 fid=$fid，直接导航打开…")
                    // 导航会触发 onPageFinished -> stage==1 -> waitForContent
                    browser.load("https://space.bilibili.com/$uid/favlist?fid=$fid&ftype=create")
                    return@evaluate
                }
            }
            log("未能提取 fid，尝试触摸点击…")
            browser.touchClick(selector) { found ->
                log(if (found) "已触摸点击收藏夹「$favName」" else "未在侧栏找到收藏夹「$favName」，等待默认内容…")
                if (!contentWaitStarted) {
                    contentWaitStarted = true
                    waitForContent(favName, onResult, onError)
                }
            }
        }
    }

    private fun waitForContent(favName: String, onResult: (List<BiliTrack>) -> Unit, onError: (String) -> Unit) {
        val detailJs = "(function(){" +
            "var el=document.querySelector('.favlist-info-detail__title-row');" +
            "return !!(el&&(el.textContent||'').indexOf(${json(favName)})>=0);})()"
        waitForJs(
            detailJs,
            timeoutMs = 20000,
            onReady = { waitForCards(favName, onResult, onError) },
            onTimeout = {
                // 提示用户手动点击收藏夹，然后继续等待（不结束抓取、不回到输入页）
                hintManualClick(favName)
                waitForContent(favName, onResult, onError)
            },
        )
    }

    private fun waitForCards(favName: String, onResult: (List<BiliTrack>) -> Unit, onError: (String) -> Unit) {
        waitForJs(
            CARDS_JS,
            timeoutMs = 20000,
            onReady = { scrapeCards(onResult) },
            onTimeout = {
                log("未检测到视频卡片（收藏夹可能为空或未打开），请在上方页面确认后程序自动继续…")
                waitForCards(favName, onResult, onError)
            },
        )
    }

    private fun hintManualClick(favName: String) {
        if (!manualHinted) {
            manualHinted = true
            val msg = "自动点击未成功，请在上方 WebView 页面中手动点击收藏夹「$favName」，点击后程序会自动继续…"
            log(msg)
            onHint(msg)
        }
    }

    private fun scrapeCards(onResult: (List<BiliTrack>) -> Unit) {
        browser.evaluate(SCRAPE_JS) { raw ->
            val tracks = parseTracks(raw)
            log("当前页抓取 ${tracks.size} 条视频，累计 ${accumulatedTracks.size + tracks.size} 条")
            accumulatedTracks.addAll(tracks)
            // 抓取分页信息并上报进度（onProgress 在 scrapePagination 内部调用）
            scrapePagination {
                // 检查是否有下一页
                checkNextPage(onResult)
            }
        }
    }

    private fun scrapePagination(callback: (String) -> Unit) {
        val js = "(function(){var el=document.querySelector('.vui_pagenation-go__count');return el?(el.textContent||'').trim():'';})()"
        browser.evaluate(js) { raw ->
            val text = try { JSONTokener(raw).nextValue().toString() } catch (e: Exception) { raw }
            // 解析 "共 X 页 / Y 个，跳至"
            val pageMatch = Regex("共\\s*(\\d+)\\s*页").find(text)
            val itemMatch = Regex("/\\s*(\\d+)\\s*个").find(text)
            if (pageMatch != null) totalPages = pageMatch.groupValues[1].toIntOrNull() ?: 0
            if (itemMatch != null) totalItems = itemMatch.groupValues[1].toIntOrNull() ?: 0
            currentPage++
            val progressText = "第${currentPage}页/共${totalPages}页, 已抓取${accumulatedTracks.size}个/共${totalItems}个"
            onProgress(progressText)
            if (totalPages > 0) onFetchProgress(currentPage.toFloat() / totalPages.toFloat())
            callback(text)
        }
    }

    private fun checkNextPage(onResult: (List<BiliTrack>) -> Unit) {
        // 上一页/下一页 class 相同，需通过文本内容区分；第一页时上一页 disabled
        val checkJs = """
            (function(){
              var btns = document.querySelectorAll('button.vui_pagenation--btn-side');
              for (var i = 0; i < btns.length; i++) {
                var b = btns[i];
                var txt = (b.textContent || '').trim();
                if (txt.indexOf('下一页') >= 0) {
                  var disabled = b.disabled || b.classList.contains('vui_pagenation--btn-disabled') || b.hasAttribute('disabled');
                  return JSON.stringify({found:true, disabled:disabled, index:i});
                }
              }
              return JSON.stringify({found:false});
            })()
        """.trimIndent()
        browser.evaluate(checkJs) { raw ->
            val obj = parseObj(raw)
            if (obj != null && obj.optBoolean("found") && !obj.optBoolean("disabled")) {
                // 有下一页且未禁用，点击下一页（通过 nth-child 精确定位）
                val idx = obj.optInt("index", 0)
                val clickJs = "(function(){var btns=document.querySelectorAll('button.vui_pagenation--btn-side');var b=btns[$idx];if(b)b.click();return !!b;})()"
                browser.evaluate(clickJs) { clickRaw ->
                    if (clickRaw.trim().equals("true", ignoreCase = true)) {
                        log("点击下一页，等待加载…")
                        mainHandler.postDelayed({
                            waitForJs(CARDS_JS, timeoutMs = 15000,
                                onReady = { scrapeCards(onResult) },
                                onTimeout = { log("下一页加载超时，返回已抓取内容"); onResult(accumulatedTracks.toList()) }
                            )
                        }, 1500)
                    } else {
                        log("未找到下一页按钮，返回已抓取内容")
                        onResult(accumulatedTracks.toList())
                    }
                }
            } else {
                log("没有更多页，共抓取 ${accumulatedTracks.size} 条视频")
                onResult(accumulatedTracks.toList())
            }
        }
    }

    private fun waitForJs(conditionJs: String, timeoutMs: Long, onReady: () -> Unit, onTimeout: () -> Unit) {
        val startTime = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                browser.evaluate(conditionJs) { raw ->
                    if (isTruthy(raw)) {
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

    private fun isTruthy(raw: String): Boolean = raw.trim().equals("true", ignoreCase = true)

    private fun parseTracks(raw: String): List<BiliTrack> {
        return try {
            val value = JSONTokener(raw).nextValue()
            val arr = JSONArray(value.toString())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BiliTrack(o.optString("bvid"), o.optString("title"), o.optString("cover"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseObj(raw: String): JSONObject? {
        return try {
            val value = JSONTokener(raw).nextValue()
            if (value is JSONObject) value else JSONObject(value.toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun json(s: String): String = JSONObject.quote(s)

    private fun escapeCssAttr(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog(message)
    }

    companion object {
        private const val TAG = "BiliUser"
        private const val POLL_MS = 500L

        private val SIDEBAR_JS =
            "(function(){ return !!document.querySelector('.items') || !!document.querySelector('.fav-sidebar-item'); })()"

        private val CARDS_JS =
            "(function(){return document.querySelectorAll('.bili-video-card').length>0;})()"

        private fun EXTRACT_FID_JS(selector: String): String = """
            (function(){
              var el = document.querySelector(${JSONObject.quote(selector)});
              if (!el) return JSON.stringify({found:false});
              var id = el.getAttribute('data-id') || el.getAttribute('data-fid') || '';
              if (!id) {
                var a = el.querySelector('a');
                if (a) {
                  var m = (a.href || '').match(/[?&]fid=(\d+)/);
                  if (m) id = m[1];
                }
              }
              if (!id) return JSON.stringify({found:false});
              return JSON.stringify({found:true, fid:id});
            })()
        """.trimIndent()

        // 参考项目 resources/stealth.js，尽量模拟真实 Chrome。
        // 每步独立 try/catch：WebView 中部分属性不可重定义，且脚本会重复注入。
        private val STEALTH_JS = """
            (() => {
              function def(obj, key, value) {
                try { Object.defineProperty(obj, key, value); } catch (e) {}
              }
              def(navigator, 'webdriver', { get: () => false });
              if (!window.chrome) { window.chrome = { runtime:{}, loadTimes:function(){}, csi:function(){}, app:{} }; }
              if (!window.chrome.runtime) { window.chrome.runtime = {}; }
              def(navigator, 'plugins', {
                get: () => {
                  const arr = [
                    {name:'Chrome PDF Plugin', filename:'internal-pdf-viewer', description:'Portable Document Format'},
                    {name:'Chrome PDF Viewer', filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai', description:''},
                    {name:'Native Client', filename:'internal-nacl-plugin', description:''},
                  ];
                  arr.item = (i)=>arr[i]||null; arr.namedItem = (n)=>arr.find(p=>p.name===n)||null; arr.refresh = ()=>{};
                  return arr;
                }
              });
              def(navigator, 'mimeTypes', {
                get: () => {
                  const arr = [
                    {type:'application/pdf', suffixes:'pdf', description:'Portable Document Format'},
                    {type:'text/pdf', suffixes:'pdf', description:'Portable Document Format'},
                  ];
                  arr.item = (i)=>arr[i]||null; arr.namedItem = (m)=>arr.find(x=>x.type===m)||null;
                  return arr;
                }
              });
              def(navigator, 'languages', { get: () => ['zh-CN','zh','en-US','en'] });
              def(navigator, 'language', { get: () => 'zh-CN' });
              def(navigator, 'hardwareConcurrency', { get: () => 8 });
              def(navigator, 'deviceMemory', { get: () => 8 });
              def(navigator, 'platform', { get: () => 'Win32' });
              try {
                const originalQuery = window.navigator.permissions.query;
                window.navigator.permissions.query = function (parameters) {
                  if (parameters.name === 'notifications') {
                    return Promise.resolve({ state: Notification.permission, onchange: null });
                  }
                  return originalQuery.call(this, parameters);
                };
              } catch (e) {}
              try {
                if (navigator.connection) {
                  Object.defineProperty(navigator.connection, 'rtt', { get: () => 50 });
                }
              } catch (e) {}
            })();
        """.trimIndent()

        private val SCRAPE_JS = """
            (function() {
                const seen = new Set();
                const result = [];
                for (const card of document.querySelectorAll('.bili-video-card')) {
                    const titleA = card.querySelector('.bili-video-card__title a');
                    if (!titleA) continue;
                    const href = titleA.href || '';
                    const m = href.match(/\/video\/([^/?]+)/);
                    if (!m) continue;
                    const bvid = m[1];
                    if (seen.has(bvid)) continue;
                    seen.add(bvid);
                    const title = (titleA.textContent || '').trim();
                    const img = card.querySelector('.b-img__inner');
                    let cover = '';
                    if (img) {
                        cover = (img.src || '').trim();
                        if (cover.startsWith('//')) cover = 'https:' + cover;
                    }
                    result.push({bvid: bvid, title: title || bvid, cover: cover});
                }
                return JSON.stringify(result);
            })()
        """.trimIndent()
    }
}
