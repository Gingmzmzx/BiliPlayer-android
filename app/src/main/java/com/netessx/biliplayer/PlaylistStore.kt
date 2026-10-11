package com.netessx.biliplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地播放列表库。
 *
 * 抓取一次收藏夹后把曲目缓存到 `filesDir/playlists.json`，下次直接读本地，
 * 不必重新打开浏览器抓取。用户仍可主动「同步」某个列表，重新抓取并覆盖曲目。
 *
 * 用文件而不是 SharedPreferences：一个收藏夹可能有上千条曲目，
 * 塞进 SharedPreferences 会让每次读整份配置都很贵。
 */
data class Playlist(
    val id: String,
    val name: String,
    val uid: String,
    val favName: String,
    val tracks: List<BiliTrack> = emptyList(),
    val updatedAt: Long = 0L,
) {
    companion object {
        /** 「收藏夹名 (uid)」——导入时的默认名。 */
        fun defaultName(favName: String, uid: String) = "$favName ($uid)"
    }
}

object PlaylistStore {

    private const val TAG = "PlaylistStore"
    private const val FILE_NAME = "playlists.json"

    private lateinit var appContext: Context

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    /** 只初始化一次；重复调用安全。 */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _playlists.value = readAll(appContext)
    }

    // ---------- 读 ----------

    fun get(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readAll(context: Context): List<Playlist> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        }.onFailure { Log.w(TAG, "读取播放列表失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun parse(o: JSONObject?): Playlist? {
        if (o == null) return null
        val id = o.optString("id", "").ifBlank { return null }
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val bvid = t.optString("bvid", "").ifBlank { return@mapNotNull null }
            BiliTrack(
                bvid = bvid,
                title = t.optString("title", ""),
                cover = t.optString("cover", ""),
            )
        }
        return Playlist(
            id = id,
            name = o.optString("name", ""),
            uid = o.optString("uid", ""),
            favName = o.optString("favName", ""),
            tracks = tracks,
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }

    // ---------- 写 ----------

    private fun persist() {
        if (!::appContext.isInitialized) return
        // 写临时文件再改名：中途失败也不会留下半截 JSON 把整个库弄坏
        runCatching {
            val arr = JSONArray()
            _playlists.value.forEach { p ->
                val tracks = JSONArray()
                p.tracks.forEach { t ->
                    tracks.put(
                        JSONObject()
                            .put("bvid", t.bvid)
                            .put("title", t.title)
                            .put("cover", t.cover)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("uid", p.uid)
                        .put("favName", p.favName)
                        .put("updatedAt", p.updatedAt)
                        .put("tracks", tracks)
                )
            }
            val target = file(appContext)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(arr.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "保存播放列表失败: ${it.message}") }
    }

    /** 新建一个列表，返回落库后的对象。 */
    fun add(
        context: Context,
        name: String,
        uid: String,
        favName: String,
        tracks: List<BiliTrack>,
    ): Playlist {
        init(context)
        // 同一个收藏夹只保留一条：再次导入就地覆盖曲目，避免堆出一串重名条目。
        // 保留用户改过的名字——重新导入不该把重命名结果冲掉。
        val existing = _playlists.value.firstOrNull { it.uid == uid && it.favName == favName }
        val now = System.currentTimeMillis()
        val pl = existing?.copy(
            tracks = tracks,
            updatedAt = now,
        ) ?: Playlist(
            id = now.toString(),
            name = name,
            uid = uid,
            favName = favName,
            tracks = tracks,
            updatedAt = now,
        )
        _playlists.value = if (existing != null) {
            _playlists.value.map { if (it.id == pl.id) pl else it }
        } else {
            _playlists.value + pl
        }
        persist()
        return pl
    }

    /** 同步：用新抓到的曲目覆盖，名字与 uid 保持不变。 */
    fun replaceTracks(context: Context, id: String, tracks: List<BiliTrack>): Playlist? {
        init(context)
        val old = get(id) ?: return null
        val updated = old.copy(tracks = tracks, updatedAt = System.currentTimeMillis())
        _playlists.value = _playlists.value.map { if (it.id == id) updated else it }
        persist()
        return updated
    }

    /**
     * 从列表里剔除若干条（视频失效时调用）。
     * 返回被删掉的 bvid 集合，调用方可以用来清理单曲偏好。
     */
    fun removeTracks(context: Context, id: String, bvids: Collection<String>): Set<String> {
        init(context)
        val pl = get(id) ?: return emptySet()
        val drop = bvids.toSet()
        val kept = pl.tracks.filterNot { it.bvid in drop }
        if (kept.size == pl.tracks.size) return emptySet()
        val removed = pl.tracks.filter { it.bvid in drop }.map { it.bvid }.toSet()
        _playlists.value = _playlists.value.map {
            if (it.id == id) it.copy(tracks = kept, updatedAt = System.currentTimeMillis()) else it
        }
        persist()
        return removed
    }

    fun rename(context: Context, id: String, newName: String) {
        init(context)
        val name = newName.trim()
        if (name.isEmpty()) return
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = name) else it }
        persist()
    }

    fun delete(context: Context, id: String) {
        init(context)
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persist()
    }
}
