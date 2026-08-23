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
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var contentWaitStarted = false

    fun getFavlist(
        uid: String,
        favName: String,
        onResult: (List<BiliTrack>) -> Unit,
        onError: (String) -> Unit,
    ) {
        var stage = 0 // 0=等待侧栏, 1=已打开等待内容
        var sidebarWaitStarted = false
        contentWaitStarted = false
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
                    onTimeout = { onError("收藏夹列表加载超时（UID 不存在或页面加载失败）") },
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
            timeoutMs = 40000,
            onReady = {
                waitForJs(
                    CARDS_JS,
                    timeoutMs = 40000,
                    onReady = { scrapeCards(onResult) },
                    onTimeout = { onError("未找到收藏夹「$favName」或其内容为空") },
                )
            },
            onTimeout = { onError("未找到收藏夹「$favName」或加载超时") },
        )
    }

    private fun scrapeCards(onResult: (List<BiliTrack>) -> Unit) {
        browser.evaluate(SCRAPE_JS) { raw ->
            val tracks = parseTracks(raw)
            log("已抓取 ${tracks.size} 条视频")
            onResult(tracks)
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
