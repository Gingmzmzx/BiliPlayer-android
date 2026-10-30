package com.netessx.biliplayer

import android.content.Context
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

data class SearchState(
    val query: String = "",
    val results: List<RecommendVideo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val progress: String? = null,
)

/**
 * B 站全站搜索：只抓搜索页 DOM，不调用 API。
 */
object SearchStore {

    private const val MAX_PAGES = 5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    private var appContext: Context? = null
    private var host: ViewGroup? = null
    private var browser: HeadlessBrowser? = null
    private var runId = 0
    private var page = 1
    private var awaitingPage = false
    private val seen = LinkedHashMap<String, RecommendVideo>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun search(context: Context, rawQuery: String, hostView: ViewGroup? = null) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        init(context)
        host = hostView ?: host
        val id = ++runId
        seen.clear()
        page = 1
        awaitingPage = true
        _state.value = SearchState(query = query, loading = true, progress = "正在搜索…")

        val b = ensureBrowser() ?: return
        val keyword = URLEncoder.encode(query, "UTF-8")
        b.load("https://search.bilibili.com/all?keyword=$keyword&from_source=web_search")

        scope.launch {
            delay(45000)
            if (id == runId && _state.value.loading) {
                fail(id, "搜索超时，请重试")
            }
        }
    }

    private fun ensureBrowser(): HeadlessBrowser? {
        val context = appContext ?: return null
        browser?.let { return it }
        val b = HeadlessBrowser(context)
        b.ensureCreated()
        b.onLog = { }
        host?.let { b.attachOffscreen(it) }
        b.onMainFrameError = { msg ->
            if (_state.value.loading) fail(runId, "页面加载失败：$msg")
        }
        b.onPageFinished = {
            if (awaitingPage && _state.value.loading) {
                awaitingPage = false
                val id = runId
                scope.launch {
                    delay(2200)
                    scrape(id, attempt = 0)
                }
            }
        }
        browser = b
        return b
    }

    private fun scrape(id: Int, attempt: Int) {
        val b = browser ?: return
        if (id != runId || !_state.value.loading) return
        b.evaluate(EXTRACT_JS) { raw ->
            if (id != runId) return@evaluate
            val videos = parse(raw)
            val before = seen.size
            videos.forEach { seen.putIfAbsent(it.bvid, it) }
            val added = seen.size - before

            if (videos.isEmpty() || (page > 1 && added == 0)) {
                if (attempt < 8) {
                    _state.update { it.copy(progress = "等待搜索结果加载…") }
                    scope.launch {
                        delay(1500)
                        scrape(id, attempt + 1)
                    }
                    return@evaluate
                }
                finish(id)
                return@evaluate
            }

            _state.update {
                it.copy(
                    results = seen.values.toList(),
                    progress = "第 $page 页 · ${seen.size} 个结果"
                )
            }
            scope.launch {
                delay(400)
                nextPage(id)
            }
        }
    }

    private fun nextPage(id: Int) {
        val b = browser ?: return
        if (id != runId || !_state.value.loading) return
        if (page >= MAX_PAGES) {
            finish(id)
            return
        }
        b.evaluate(NEXT_JS) { raw ->
            if (id != runId) return@evaluate
            if (!parseFound(raw)) {
                finish(id)
                return@evaluate
            }
            b.evaluate(CLICK_NEXT_JS) { clicked ->
                if (id != runId) return@evaluate
                if (!clicked.trim().equals("true", true)) {
                    finish(id)
                    return@evaluate
                }
                page++
                _state.update {
                    it.copy(progress = "第 $page 页 · ${seen.size} 个结果")
                }
                scope.launch {
                    delay(1800)
                    scrape(id, attempt = 0)
                }
            }
        }
    }

    private fun finish(id: Int) {
        if (id != runId) return
        val results = seen.values.toList()
        _state.update {
            it.copy(
                results = results,
                loading = false,
                error = if (results.isEmpty()) "没有抓到搜索结果" else null,
                progress = null
            )
        }
    }

    private fun fail(id: Int, message: String) {
        if (id != runId) return
        _state.update {
            it.copy(
                results = seen.values.toList(),
                loading = false,
                error = message,
                progress = null
            )
        }
    }

    private fun parse(raw: String): List<RecommendVideo> {
        return try {
            val value = JSONTokener(raw).nextValue()
            val arr = JSONArray(value.toString())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val bvid = o.optString("bvid")
                if (bvid.isBlank()) return@mapNotNull null
                val cover = o.optString("cover").let {
                    if (it.startsWith("//")) "https:$it" else it
                }
                RecommendVideo(
                    bvid = bvid,
                    title = o.optString("title"),
                    cover = cover,
                    url = o.optString("url")
                        .ifBlank { "https://www.bilibili.com/video/$bvid" }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseFound(raw: String): Boolean = runCatching {
        val value = JSONTokener(raw).nextValue()
        JSONObject(value.toString()).optBoolean("found", false)
    }.getOrDefault(false)

    private val EXTRACT_JS = """
        (function(){
          function clean(s){ return (s || '').replace(/\s+/g, ' ').trim(); }
          var cards = document.querySelectorAll('.bili-video-card');
          var out = [];
          for (var i = 0; i < cards.length; i++) {
            var card = cards[i];
            var a = card.querySelector('.bili-cover-card') || card.querySelector('a[href*="/video/"]');
            var href = a ? a.href : '';
            var m = href.match(/\/video\/(BV[0-9A-Za-z]+)/);
            if (!m) continue;
            var tEl = card.querySelector('.bili-video-card__info--tit')
              || card.querySelector('.bili-video-card__title');
            var title = tEl ? (clean(tEl.textContent) || tEl.getAttribute('title') || '') : '';
            var img = card.querySelector('img');
            var cover = img ? (img.currentSrc || img.src || img.getAttribute('data-src') || '') : '';
            out.push({ bvid: m[1], title: clean(title), cover: cover, url: href });
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    private val NEXT_JS = """
        (function(){
          var els = document.querySelectorAll('button, a, li, div');
          for (var i = 0; i < els.length; i++) {
            var el = els[i];
            var txt = (el.textContent || '').replace(/\s+/g, '').trim();
            if (txt !== '下一页' && txt !== '下页') continue;
            var cls = String(el.className || '');
            var disabled = el.disabled ||
              el.getAttribute('aria-disabled') === 'true' ||
              /disabled/i.test(cls);
            if (disabled) return JSON.stringify({ found: false });
            el.setAttribute('data-search-next', '1');
            return JSON.stringify({ found: true });
          }
          return JSON.stringify({ found: false });
        })()
    """.trimIndent()

    private const val CLICK_NEXT_JS =
        "(function(){var el=document.querySelector('[data-search-next=\"1\"]');" +
            "if(!el)return false;el.click();return true;})()"
}
