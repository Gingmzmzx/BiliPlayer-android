package com.netessx.biliplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 一行歌词。[timeMs] 为该行开始时间（毫秒）。 */
data class LyricLine(val timeMs: Long, val text: String)

/** 一首歌的歌词记录，按 bvid 存。[manual] 表示用户自己填的，不该被自动搜索覆盖。 */
data class LyricsEntry(
    val bvid: String,
    val trackName: String = "",
    val artistName: String = "",
    val synced: String = "",
    val plain: String = "",
    val manual: Boolean = false,
    /** 歌词偏移（毫秒）。正数表示让歌词「延后」出现，用于修正与音频的不同步。 */
    val offsetMs: Long = 0L,
) {
    /** LRC 能真的解析出时间行才算「有同步歌词」。 */
    val hasSyncedLines: Boolean get() = synced.isNotBlank() && parseLrc(synced).isNotEmpty()

    val hasContent: Boolean get() = hasSyncedLines || plain.isNotBlank()

    /** 用于展示的行：有 LRC 就用 LRC，否则用纯文本按行拆分。 */
    fun lines(): List<LyricLine> {
        if (synced.isNotBlank()) {
            val parsed = parseLrc(synced)
            if (parsed.isNotEmpty()) return parsed
        }
        if (plain.isBlank()) return emptyList()
        // 纯文本没有时间轴：给每行一个递增的伪时间，只用于静态展示
        return plain.split('\n').mapIndexed { i, t -> LyricLine(i * 1000L, t.trim()) }
    }
}

/** 解析 LRC：支持 `[mm:ss.xx]` / `[mm:ss.xxx]` / 一行多个时间戳；忽略 `[ar:]` 等元信息。 */
fun parseLrc(lrc: String): List<LyricLine> {
    val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    val out = mutableListOf<LyricLine>()
    lrc.lineSequence().forEach { raw ->
        val matches = timeTag.findAll(raw).toList()
        if (matches.isEmpty()) return@forEach
        val text = raw.substring(matches.last().range.last + 1).trim()
        if (text.isEmpty()) return@forEach
        matches.forEach { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
            val fracRaw = m.groupValues[3]
            val ms = when (fracRaw.length) {
                0 -> 0L
                1 -> fracRaw.toLong() * 100
                2 -> fracRaw.toLong() * 10
                else -> fracRaw.take(3).toLong()
            }
            out.add(LyricLine(min * 60_000 + sec * 1000 + ms, text))
        }
    }
    return out.sortedBy { it.timeMs }
}

/** 返回当前播放位置对应的行下标；还没到第一行时返回 -1。 */
fun activeLineIndex(lines: List<LyricLine>, positionSec: Double): Int {
    if (lines.isEmpty()) return -1
    val ms = (positionSec * 1000).toLong()
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= ms) idx = i else break
    }
    return idx
}

/**
 * lrclib.net 客户端。
 *
 * 只做歌词检索，不涉及 B 站。服务端要求带能识别应用的 User-Agent，
 * 这里统一写 `BiliPlayerAndroid/<version>`。失败一律返回空列表，不抛异常。
 */
object LrcLib {

    private const val TAG = "LrcLib"
    private const val TIMEOUT_MS = 8_000

    /** 接口根地址，可在设置里改成自建镜像。 */
    private var baseUrl: String = Preferences.DEFAULT_LRCLIB_BASE

    /** 每台设备/每次安装都不同的 User-Agent，初始化时算一次。 */
    private var userAgent: String = "BiliPlayerAndroid/" + BuildConfig.VERSION_NAME

    fun init(context: Context) {
        val ctx = context.applicationContext
        baseUrl = Preferences.lrcLibBaseUrl(ctx).trimEnd('/')
        userAgent = buildUserAgent(ctx)
    }

    /**
     * 每台设备不重复，但不含任何设备信息：
     * 只有应用名/版本 + 一个本地随机生成的安装串 + 项目地址。
     * 不读取型号、厂商、系统版本、分辨率，也不碰 IMEI / Android ID 等任何硬件或系统标识。
     */
    private fun buildUserAgent(ctx: Context): String =
        "BiliPlayerAndroid/${BuildConfig.VERSION_NAME}" +
            " (id/${Preferences.installId(ctx)})" +
            " https://github.com/Gingmzmzx/BiliPlayer-android"

    /**
     * 按关键词搜索，带时间轴的条目排在前面。
     * [query] 一般是清洗过的视频标题；也可以用「歌名 歌手」。
     * 返回条目的 [LyricsEntry.bvid] 为空，由调用方填。
     */
    suspend fun search(query: String): List<LyricsEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val body = get("$baseUrl/search?q=$encoded") ?: return@runCatching emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LyricsEntry(
                    bvid = "",
                    trackName = o.text("trackName"),
                    artistName = o.text("artistName"),
                    synced = o.text("syncedLyrics"),
                    plain = o.text("plainLyrics"),
                )
            }.sortedByDescending { it.hasSyncedLines }
        }.onFailure { Log.d(TAG, "搜索失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * org.json 的 optString 会把 JSON null 变成字符串 "null"，
     * 直接拿来当歌词内容会「有内容但解析不出任何行」。统一在这里洗干净。
     */
    private fun JSONObject.text(key: String): String {
        val v = opt(key) ?: return ""
        if (v === JSONObject.NULL) return ""
        val s = v.toString()
        return if (s.equals("null", ignoreCase = true)) "" else s
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "请求失败: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** 歌词本地缓存，按 bvid 存储。落盘 `filesDir/lyrics.json`。 */
object LyricsStore {

    private const val TAG = "LyricsStore"
    private const val FILE_NAME = "lyrics.json"

    private lateinit var appContext: Context

    private val _entries = MutableStateFlow<Map<String, LyricsEntry>>(emptyMap())
    val entries: StateFlow<Map<String, LyricsEntry>> = _entries

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _entries.value = readAll(appContext)
    }

    fun get(bvid: String): LyricsEntry? = _entries.value[bvid]

    /** 全部歌词（供导出）。 */
    fun snapshot(): Map<String, LyricsEntry> = _entries.value

    /** 合并导入的歌词（同 bvid 覆盖）。 */
    fun merge(context: Context, entries: Map<String, LyricsEntry>) {
        init(context)
        if (entries.isEmpty()) return
        _entries.value = _entries.value + entries
        persist()
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 旧版本可能把 JSON null 存成了字符串 "null"，读回来时一并在洗掉。 */
    private fun JSONObject.clean(key: String): String {
        val s = optString(key, "")
        return if (s.equals("null", ignoreCase = true)) "" else s
    }

    private fun readAll(context: Context): Map<String, LyricsEntry> {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(f.readText())
            buildMap {
                root.keys().forEach { bvid ->
                    val o = root.optJSONObject(bvid) ?: return@forEach
                    put(
                        bvid,
                        LyricsEntry(
                            bvid = bvid,
                            trackName = o.clean("trackName"),
                            artistName = o.clean("artistName"),
                            synced = o.clean("synced"),
                            plain = o.clean("plain"),
                            manual = o.optBoolean("manual", false),
                            offsetMs = o.optLong("offsetMs", 0L),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读取歌词失败: ${it.message}") }
            .getOrDefault(emptyMap())
    }

    private fun persist() {
        if (!::appContext.isInitialized) return
        runCatching {
            val root = JSONObject()
            _entries.value.forEach { (bvid, e) ->
                root.put(
                    bvid,
                    JSONObject()
                        .put("trackName", e.trackName)
                        .put("artistName", e.artistName)
                        .put("synced", e.synced)
                        .put("plain", e.plain)
                        .put("manual", e.manual)
                        .put("offsetMs", e.offsetMs)
                )
            }
            val target = file(appContext)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "保存歌词失败: ${it.message}") }
    }

    fun put(context: Context, entry: LyricsEntry) {
        init(context)
        if (entry.bvid.isBlank()) return
        if (!entry.hasContent) {
            if (_entries.value.containsKey(entry.bvid)) {
                _entries.value = _entries.value - entry.bvid
                persist()
            }
            return
        }
        _entries.value = _entries.value + (entry.bvid to entry)
        persist()
    }

    fun remove(context: Context, bvid: String) {
        init(context)
        if (bvid !in _entries.value) return
        _entries.value = _entries.value - bvid
        persist()
    }
}

/**
 * 从 B 站视频标题里猜歌名。
 * 去掉【】/（）等括注、常见的 MV/字幕/分辨率标记，剩下的当作查询词。
 */
fun guessSongName(title: String): String {
    // B 站音乐区习惯用《》标注真实歌名：直接取里面那一段，比清洗后的整串更准
    Regex("[《]([^》]+)[》]").find(title)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    var s = title
    s = s.replace(Regex("[【\\[（(][^】\\]）)]*[】\\]）)]"), " ")
    s = s.replace(
        Regex("(?i)\\b(mv|pv|official|lyric\\s*video|full\\s*ver\\.?|4k|8k|1080p|720p|hdr|中文字幕|字幕|完整版|高清|无损|翻唱|cover)\\b"),
        " "
    )
    s = s.replace(Regex("\\s+"), " ").trim()
    s = s.trim(' ', '-', '—', '|', '/', '·')
    return s.ifBlank { title.trim() }
}
