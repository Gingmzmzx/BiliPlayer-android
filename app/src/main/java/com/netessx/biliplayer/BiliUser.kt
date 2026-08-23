package com.netessx.biliplayer

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 一条收藏夹视频。 */
data class BiliTrack(val bvid: String, val title: String = "", val cover: String = "")

/**
 * 复刻 Python 项目 user.py：
 * 在无头浏览器中打开 `https://space.bilibili.com/{uid}/favlist?ftype=create`，
 * 点击目标收藏夹，抓取 `.bili-video-card` 卡片得到 {bvid, title, cover} 列表。
 * 完全基于 DOM 抓取，不请求任何 B 站 API。
 */
class BiliUser(
    private val browser: HeadlessBrowser,
    private val onLog: (String) -> Unit = {},
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getFavlist(
        uid: String,
        favName: String,
        onResult: (List<BiliTrack>) -> Unit,
        onError: (String) -> Unit,
    ) {
        var started = false
        log("开始抓取 UID=$uid 收藏夹=$favName")
        // 提前注入 stealth（尽力而为，仿照 python 的 init script）
        browser.onPageStarted = { _ -> browser.evaluate(STEALTH_JS) }
        browser.onPageFinished = { _ ->
            browser.evaluate(STEALTH_JS)
            if (!started) {
                started = true
                log("页面已加载，等待收藏夹侧栏…")
                waitForJs(
                    "(function(){ return !!document.querySelector('.items') || !!document.querySelector('.fav-sidebar-item'); })()",
                    timeoutMs = 40000,
                    onReady = { clickFolder(favName, onResult, onError) },
                    onTimeout = { onError("收藏夹列表加载超时（UID 不存在或页面加载失败）") },
                )
            }
        }
        browser.load("https://space.bilibili.com/$uid/favlist?ftype=create")
    }

    private fun clickFolder(favName: String, onResult: (List<BiliTrack>) -> Unit, onError: (String) -> Unit) {
        val selector = ".fav-sidebar-item[title=\"${escapeCssAttr(favName)}\"]"
        browser.touchClick(selector) { found ->
            log(if (found) "已点击收藏夹「$favName」，等待内容加载…" else "未找到收藏夹「$favName」，等待默认选中内容…")
            // 无论是否点中（可能收藏夹已在默认选中态），都等待内容加载
            val detailJs = "(function(){" +
                "var el=document.querySelector('.favlist-info-detail__title-row');" +
                "return !!(el&&(el.textContent||'').indexOf(${json(favName)})>=0);})()"
            waitForJs(
                detailJs,
                timeoutMs = 40000,
                onReady = {
                    waitForJs(
                        "(function(){return document.querySelectorAll('.bili-video-card').length>0;})()",
                        timeoutMs = 40000,
                        onReady = { scrapeCards(onResult) },
                        onTimeout = { onError("未找到收藏夹「$favName」或其内容为空") },
                    )
                },
                onTimeout = { onError("未找到收藏夹「$favName」或加载超时") },
            )
        }
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

    private fun json(s: String): String = JSONObject.quote(s)

    private fun escapeCssAttr(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun log(message: String) {
        onLog(message)
    }

    companion object {
        private const val POLL_MS = 500L

        // 参考项目 resources/stealth.js，尽量模拟真实 Chrome。
        // 每步独立 try/catch：WebView 中部分属性不可重定义（如 navigator.webdriver），
        // 且脚本会随 onPageStarted/onPageFinished 重复注入，避免二次注入抛错中断后续补丁。
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
