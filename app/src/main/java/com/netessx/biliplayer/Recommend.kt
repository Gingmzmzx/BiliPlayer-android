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
import java.io.File

/**
 * 推荐合集。
 *
 * 固定抓取指定 UP 的合集页（多页），只读 DOM，不调用任何 B 站 API。
 * 列表页顺带抓每个合集最新的几个视频；点开合集后重新进入合集详情并翻页抓全部视频。
 */
data class RecommendVideo(
    val bvid: String,
    val title: String,
    val cover: String,
    val url: String,
)

data class RecommendItem(
    val id: String,
    val title: String,
    val cover: String,
    val count: Int,
    val type: String,
    val url: String,
    val videos: List<RecommendVideo> = emptyList(),
)

data class RecommendDetail(
    val itemId: String,
    val title: String,
    val videos: List<RecommendVideo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val progress: String? = null,
    val updateNotice: String? = null,
)

private data class CachedCollection(
    val title: String,
    val videos: List<RecommendVideo>,
    val updatedAt: Long,
)

data class RecommendState(
    val items: List<RecommendItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val progress: String? = null,
    val detail: RecommendDetail? = null,
)

object RecommendStore {

    private const val MAX_PAGES = 40
    private const val FEATURED_TITLE = "私人致享2.0"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(RecommendState())
    val state: StateFlow<RecommendState> = _state

    private var appContext: Context? = null
    private var host: ViewGroup? = null
    private var browser: HeadlessBrowser? = null
    private var listsUrl: String = Preferences.DEFAULT_RECOMMEND_SOURCE

    private var listRunId = 0
    private var listPage = 1
    private var listAwaitingPage = false
    private val listSeen = LinkedHashMap<String, RecommendItem>()

    private var detailRunId = 0
    private var detailPage = 1
    private var detailAwaitingLists = false
    private var detailItem: RecommendItem? = null
    private val detailSeen = LinkedHashMap<String, RecommendVideo>()

    private val detailCache = mutableMapOf<String, CachedCollection>()
    private var cacheLoaded = false
    private var detailBaseline: Set<String> = emptySet()
    private var detailHadCache = false

    fun init(context: Context) {
        appContext = context.applicationContext
        listsUrl = Preferences.recommendSource(context)
        loadCache(context)
    }

    /** 修改来源后立即重新抓取。 */
    fun updateSource(context: Context, source: String) {
        val normalized = Preferences.normalizeRecommendSource(source)
        Preferences.saveRecommendSource(context, normalized)
        init(context)
        _state.value = RecommendState()
        refresh(host)
    }

    fun ensureLoaded(hostView: ViewGroup? = null) {
        if (hostView != null) host = hostView
        if (_state.value.items.isNotEmpty() || _state.value.loading) return
        refresh(hostView)
    }

    fun refresh(hostView: ViewGroup? = null) {
        if (hostView != null) host = hostView
        if (_state.value.loading) return
        val b = ensureBrowser() ?: return
        val id = ++listRunId
        listSeen.clear()
        listPage = 1
        listAwaitingPage = true
        _state.value = RecommendState(loading = true, progress = "正在打开合集页…")

        b.load(listsUrl)
        scope.launch {
            delay(60000)
            if (id == listRunId && _state.value.loading) {
                failList(id, "抓取超时，请稍后重试")
            }
        }
    }

    fun openCollection(item: RecommendItem) {
        if (_state.value.detail?.loading == true) return
        val b = ensureBrowser() ?: return
        // 详情页要重新从合集列表页进入；取消可能还在跑的列表刷新
        listRunId++
        listAwaitingPage = false
        val id = ++detailRunId
        detailItem = item
        detailSeen.clear()
        detailPage = 1
        detailAwaitingLists = true
        val cached = detailCache[item.id]
        detailBaseline = cached?.videos?.map { it.bvid }?.toSet().orEmpty()
        detailHadCache = !cached?.videos.isNullOrEmpty()
        _state.update {
            it.copy(
                loading = false,
                progress = null,
                detail = RecommendDetail(
                    itemId = item.id,
                    title = item.title,
                    videos = cached?.videos.orEmpty(),
                    loading = true,
                    progress = if (detailHadCache) "正在检查更新…" else "正在打开合集…"
                )
            )
        }
        b.load(listsUrl)
        scope.launch {
            delay(60000)
            if (id == detailRunId && _state.value.detail?.loading == true) {
                failDetail(id, "抓取超时，请稍后重试")
            }
        }
    }

    fun closeDetail() {
        detailRunId++
        detailAwaitingLists = false
        detailItem = null
        _state.update { it.copy(detail = null) }
    }

    private fun ensureBrowser(): HeadlessBrowser? {
        val context = appContext ?: return null
        browser?.let { return it }
        val b = HeadlessBrowser(context)
        b.ensureCreated()
        b.onLog = { }
        host?.let { b.attachOffscreen(it) }
        b.onMainFrameError = { msg ->
            if (_state.value.loading) failList(listRunId, "页面加载失败：$msg")
            if (_state.value.detail?.loading == true) {
                failDetail(detailRunId, "页面加载失败：$msg")
            }
        }
        b.onPageFinished = {
            if (listAwaitingPage && _state.value.loading) {
                listAwaitingPage = false
                val id = listRunId
                scope.launch {
                    delay(2200)
                    scrapeList(id, attempt = 0)
                }
            } else if (detailAwaitingLists && _state.value.detail?.loading == true) {
                detailAwaitingLists = false
                val id = detailRunId
                scope.launch {
                    delay(2200)
                    clickCollection(id, attempt = 0)
                }
            }
        }
        browser = b
        return b
    }

    // ---------------- 列表页 ----------------

    private fun scrapeList(id: Int, attempt: Int) {
        val b = browser ?: return
        if (id != listRunId || !_state.value.loading) return
        b.evaluate(EXTRACT_LIST_JS) { raw ->
            if (id != listRunId) return@evaluate
            val pageItems = parseItems(raw)
            val before = listSeen.size
            pageItems.forEach { listSeen.putIfAbsent(it.id, it) }
            val added = listSeen.size - before

            if (pageItems.isEmpty() || (listPage > 1 && added == 0)) {
                if (attempt < 8) {
                    _state.update { it.copy(progress = "等待页面加载…") }
                    scope.launch {
                        delay(1500)
                        scrapeList(id, attempt + 1)
                    }
                    return@evaluate
                }
                finishList(id)
                return@evaluate
            }

            _state.update {
                it.copy(
                    items = listSeen.values.toList(),
                    progress = "第 $listPage 页 · 已抓取 ${listSeen.size} 个合集"
                )
            }
            scope.launch {
                delay(400)
                nextListPage(id)
            }
        }
    }

    private fun nextListPage(id: Int) {
        val b = browser ?: return
        if (id != listRunId || !_state.value.loading) return
        if (listPage >= MAX_PAGES) {
            finishList(id)
            return
        }
        b.evaluate(NEXT_JS) { raw ->
            if (id != listRunId) return@evaluate
            if (!parseFound(raw)) {
                finishList(id)
                return@evaluate
            }
            b.evaluate(CLICK_NEXT_JS) { clicked ->
                if (id != listRunId) return@evaluate
                if (!isTrue(clicked)) {
                    finishList(id)
                    return@evaluate
                }
                listPage++
                _state.update {
                    it.copy(progress = "第 $listPage 页 · 已抓取 ${listSeen.size} 个合集")
                }
                scope.launch {
                    delay(1800)
                    scrapeList(id, attempt = 0)
                }
            }
        }
    }

    private fun finishList(id: Int) {
        if (id != listRunId) return
        val items = listSeen.values.toList()
        _state.update {
            it.copy(
                items = items,
                loading = false,
                error = if (items.isEmpty()) "没有抓到合集，页面可能改版或需要登录" else null,
                progress = null
            )
        }
    }

    private fun failList(id: Int, message: String) {
        if (id != listRunId) return
        _state.update {
            it.copy(
                items = listSeen.values.toList(),
                loading = false,
                error = message,
                progress = null
            )
        }
    }

    // ---------------- 合集详情 ----------------

    private fun clickCollection(id: Int, attempt: Int) {
        val b = browser ?: return
        val item = detailItem ?: return
        if (id != detailRunId || _state.value.detail?.loading != true) return
        val title = JSONObject.quote(item.title)
        b.evaluate(
            """
            (function(){
              var want = $title;
              var nodes = document.querySelectorAll('.video-list');
              for (var i = 0; i < nodes.length; i++) {
                var n = nodes[i];
                var te = n.querySelector('.video-list__title');
                var t = (te ? te.textContent : '').replace(/\s+/g, ' ').trim();
                t = t.replace(/^(合集|系列)[·:：]\s*/, '');
                if (t !== want) continue;
                var btn = null;
                var bs = n.querySelectorAll('button');
                for (var j = 0; j < bs.length; j++) {
                  if ((bs[j].textContent || '').indexOf('查看更多') >= 0) { btn = bs[j]; break; }
                }
                if (!btn) return false;
                btn.click();
                return true;
              }
              return false;
            })()
            """.trimIndent()
        ) { raw ->
            if (id != detailRunId) return@evaluate
            if (isTrue(raw)) {
                _state.update {
                    it.copy(detail = it.detail?.copy(progress = "正在读取合集视频…"))
                }
                scope.launch {
                    delay(3200)
                    scrapeDetail(id, attempt = 0)
                }
            } else if (attempt < 36) {
                // 目标合集可能在列表第 2/3 页：等 DOM，再按需翻页继续找
                if (attempt % 4 == 3) {
                    b.evaluate(NEXT_JS) { nextRaw ->
                        if (id != detailRunId) return@evaluate
                        if (!parseFound(nextRaw)) {
                            failDetail(id, "没有找到合集入口")
                            return@evaluate
                        }
                        b.evaluate(CLICK_NEXT_JS) { clicked ->
                            if (id != detailRunId) return@evaluate
                            if (!isTrue(clicked)) {
                                failDetail(id, "没有找到合集入口")
                                return@evaluate
                            }
                            scope.launch {
                                delay(1400)
                                clickCollection(id, attempt + 1)
                            }
                        }
                    }
                } else {
                    scope.launch {
                        delay(1200)
                        clickCollection(id, attempt + 1)
                    }
                }
            } else {
                failDetail(id, "没有找到合集入口")
            }
        }
    }

    private fun scrapeDetail(id: Int, attempt: Int) {
        val b = browser ?: return
        if (id != detailRunId || _state.value.detail?.loading != true) return
        b.evaluate(EXTRACT_DETAIL_JS) { raw ->
            if (id != detailRunId) return@evaluate
            val videos = parseVideos(raw)
            val before = detailSeen.size
            videos.forEach { detailSeen.putIfAbsent(it.bvid, it) }
            val added = detailSeen.size - before

            if (videos.isEmpty() || (detailPage > 1 && added == 0)) {
                if (attempt < 8) {
                    scope.launch {
                        delay(1400)
                        scrapeDetail(id, attempt + 1)
                    }
                    return@evaluate
                }
                finishDetail(id)
                return@evaluate
            }

            _state.update {
                it.copy(
                    detail = it.detail?.copy(
                        // 有缓存时先保持缓存展示，抓完再整体替换
                        videos = if (detailHadCache) it.detail.videos
                        else detailSeen.values.toList(),
                        progress = "第 $detailPage 页 · ${detailSeen.size} 个视频"
                    )
                )
            }
            scope.launch {
                delay(400)
                nextDetailPage(id)
            }
        }
    }

    private fun nextDetailPage(id: Int) {
        val b = browser ?: return
        if (id != detailRunId || _state.value.detail?.loading != true) return
        if (detailPage >= MAX_PAGES) {
            finishDetail(id)
            return
        }
        b.evaluate(NEXT_JS) { raw ->
            if (id != detailRunId) return@evaluate
            if (!parseFound(raw)) {
                finishDetail(id)
                return@evaluate
            }
            b.evaluate(CLICK_NEXT_JS) { clicked ->
                if (id != detailRunId) return@evaluate
                if (!isTrue(clicked)) {
                    finishDetail(id)
                    return@evaluate
                }
                detailPage++
                _state.update {
                    it.copy(
                        detail = it.detail?.copy(
                            progress = "第 $detailPage 页 · ${detailSeen.size} 个视频"
                        )
                    )
                }
                scope.launch {
                    delay(1800)
                    scrapeDetail(id, attempt = 0)
                }
            }
        }
    }

    private fun finishDetail(id: Int) {
        if (id != detailRunId) return
        val fetched = detailSeen.values.toList()
        val cached = _state.value.detail?.videos.orEmpty()
        val videos = if (fetched.isNotEmpty()) fetched else cached
        val added = fetched.count { it.bvid !in detailBaseline }
        val notice = if (detailHadCache && fetched.isNotEmpty() && added > 0) {
            "合集有 $added 个新视频，已更新"
        } else {
            null
        }

        if (fetched.isNotEmpty()) {
            val item = detailItem
            if (item != null) {
                detailCache[item.id] = CachedCollection(
                    title = item.title,
                    videos = fetched,
                    updatedAt = System.currentTimeMillis()
                )
                persistCache()
            }
        }

        _state.update {
            it.copy(
                detail = it.detail?.copy(
                    videos = videos,
                    loading = false,
                    error = if (videos.isEmpty()) "没有抓到视频，合集页可能改版" else null,
                    progress = null,
                    updateNotice = notice
                )
            )
        }
    }

    fun clearDetailNotice() {
        _state.update {
            it.copy(detail = it.detail?.copy(updateNotice = null))
        }
    }

    private fun failDetail(id: Int, message: String) {
        if (id != detailRunId) return
        _state.update {
            it.copy(
                detail = it.detail?.copy(
                    videos = detailSeen.values.toList()
                        .ifEmpty { it.detail.videos },
                    loading = false,
                    error = message,
                    progress = null
                )
            )
        }
    }

    // ---------------- 合集缓存 ----------------

    private fun loadCache(context: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        runCatching {
            val file = File(context.filesDir, "recommend_cache.json")
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val collections = root.optJSONObject("collections") ?: return
            collections.keys().forEach { key ->
                val o = collections.optJSONObject(key) ?: return@forEach
                val videos = parseVideoArray(o.optJSONArray("videos"))
                if (videos.isNotEmpty()) {
                    detailCache[key] = CachedCollection(
                        title = o.optString("title"),
                        videos = videos,
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                }
            }
        }
    }

    private fun persistCache() {
        val context = appContext ?: return
        val kept = detailCache.entries
            .sortedByDescending { it.value.updatedAt }
            .take(30)
        val keptIds = kept.map { it.key }.toSet()
        detailCache.keys.retainAll(keptIds)

        val collections = JSONObject()
        kept.forEach { (id, cached) ->
            val videos = JSONArray()
            cached.videos.forEach { v ->
                videos.put(
                    JSONObject()
                        .put("bvid", v.bvid)
                        .put("title", v.title)
                        .put("cover", v.cover)
                        .put("url", v.url)
                )
            }
            collections.put(
                id,
                JSONObject()
                    .put("title", cached.title)
                    .put("updatedAt", cached.updatedAt)
                    .put("videos", videos)
            )
        }
        val root = JSONObject().put("collections", collections)
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(context.filesDir, "recommend_cache.json").writeText(root.toString())
            }
        }
    }

    // ---------------- 解析 ----------------

    private fun parseItems(raw: String): List<RecommendItem> {
        return try {
            val value = JSONTokener(raw).nextValue()
            val arr = JSONArray(value.toString())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val title = o.optString("title")
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                RecommendItem(
                    id = id,
                    title = title,
                    cover = normalizeUrl(o.optString("cover")),
                    count = o.optInt("count"),
                    type = o.optString("type").ifBlank { "合集" },
                    url = o.optString("url").ifBlank { listsUrl },
                    videos = parseVideoArray(o.optJSONArray("videos"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseVideos(raw: String): List<RecommendVideo> {
        return try {
            val value = JSONTokener(raw).nextValue()
            parseVideoArray(JSONArray(value.toString()))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseVideoArray(arr: JSONArray?): List<RecommendVideo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val bvid = o.optString("bvid")
            if (bvid.isBlank()) return@mapNotNull null
            RecommendVideo(
                bvid = bvid,
                title = o.optString("title"),
                cover = normalizeUrl(o.optString("cover")),
                url = o.optString("url")
                    .ifBlank { "https://www.bilibili.com/video/$bvid" }
            )
        }
    }

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    private fun parseFound(raw: String): Boolean = runCatching {
        val value = JSONTokener(raw).nextValue()
        JSONObject(value.toString()).optBoolean("found", false)
    }.getOrDefault(false)

    private fun isTrue(raw: String): Boolean =
        raw.trim().equals("true", ignoreCase = true)

    private val EXTRACT_LIST_JS = """
        (function(){
          function clean(s){ return (s || '').replace(/\s+/g, ' ').trim(); }
          var root = document.querySelector('#app main .space-lists .lists-content > div');
          if (!root) root = document.querySelector('.space-lists .lists-content .contet-list');
          if (!root) return JSON.stringify([]);
          var nodes = root.querySelectorAll('.video-list');
          var out = [];
          for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var titleEl = node.querySelector('.video-list__title');
            var title = clean(titleEl ? titleEl.textContent : '');
            if (!title) continue;
            var type = title.indexOf('系列') === 0 ? '系列' : '合集';
            title = title.replace(/^(合集|系列)[·:：]\s*/, '');
            var descEl = node.querySelector('.video-list__desc');
            var dm = clean(descEl ? descEl.textContent : '').match(/(\d+)/);
            var count = dm ? parseInt(dm[1], 10) : 0;

            var videos = [];
            var cards = node.querySelectorAll('.video-list__item .bili-video-card');
            for (var v = 0; v < cards.length && v < 6; v++) {
              var card = cards[v];
              var a = card.querySelector('.bili-cover-card') || card.querySelector('a[href*="/video/"]');
              var href = a ? a.href : '';
              var bm = href.match(/\/video\/(BV[0-9A-Za-z]+)/);
              if (!bm) continue;
              var vtEl = card.querySelector('.bili-video-card__title');
              var vt = vtEl ? (vtEl.getAttribute('title') || clean(vtEl.textContent)) : '';
              var vi = card.querySelector('img');
              var vc = vi ? (vi.currentSrc || vi.src || vi.getAttribute('data-src') || '') : '';
              videos.push({ bvid: bm[1], title: clean(vt), cover: vc, url: href });
            }

            var cover = videos.length ? videos[0].cover : '';
            out.push({
              id: type + '|' + title,
              title: title,
              cover: cover,
              count: count,
              type: type,
              url: location.href,
              videos: videos
            });
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    private val EXTRACT_DETAIL_JS = """
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
            var tEl = card.querySelector('.bili-video-card__title');
            var title = tEl ? (tEl.getAttribute('title') || clean(tEl.textContent)) : '';
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
            el.setAttribute('data-rec-next', '1');
            return JSON.stringify({ found: true });
          }
          return JSON.stringify({ found: false });
        })()
    """.trimIndent()

    private const val CLICK_NEXT_JS =
        "(function(){var el=document.querySelector('[data-rec-next=\"1\"]');" +
            "if(!el)return false;el.click();return true;})()"
}
