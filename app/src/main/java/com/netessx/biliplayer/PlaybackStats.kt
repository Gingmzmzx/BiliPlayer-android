package com.netessx.biliplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * 听歌时长统计。
 *
 * 结构：`playlistId -> bvid -> 累计秒数`。只统计「来自播放列表库」的播放，
 * 由 [PlayerController] 在播放中每秒累加一次。
 * 落盘到 `filesDir/stats.json`，与播放列表缓存分开——删列表时一起清掉对应统计。
 */
object PlaybackStats {

    private const val TAG = "PlaybackStats"
    private const val FILE_NAME = "stats.json"

    private lateinit var appContext: Context

    private val _stats = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val stats: StateFlow<Map<String, Map<String, Long>>> = _stats

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _stats.value = readAll(appContext)
    }

    // ---------- 读 ----------

    /** 某个列表里每首歌的累计秒数。 */
    fun perTrack(playlistId: String): Map<String, Long> =
        _stats.value[playlistId] ?: emptyMap()

    /** 某个列表的总听歌秒数。 */
    fun totalSeconds(playlistId: String): Long =
        _stats.value[playlistId]?.values?.sum() ?: 0L

    fun trackSeconds(playlistId: String, bvid: String): Long =
        _stats.value[playlistId]?.get(bvid) ?: 0L

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readAll(context: Context): Map<String, Map<String, Long>> {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(f.readText())
            buildMap {
                root.keys().forEach { playlistId ->
                    val perTrack = root.optJSONObject(playlistId) ?: return@forEach
                    val m = buildMap {
                        perTrack.keys().forEach { bvid ->
                            val sec = perTrack.optLong(bvid, 0L)
                            if (sec > 0) put(bvid, sec)
                        }
                    }
                    if (m.isNotEmpty()) put(playlistId, m)
                }
            }
        }.onFailure { Log.w(TAG, "读取统计失败: ${it.message}") }
            .getOrDefault(emptyMap())
    }

    // ---------- 写 ----------

    private fun persist() {
        if (!::appContext.isInitialized) return
        runCatching {
            val root = JSONObject()
            _stats.value.forEach { (playlistId, perTrack) ->
                val o = JSONObject()
                perTrack.forEach { (bvid, sec) -> o.put(bvid, sec) }
                root.put(playlistId, o)
            }
            val target = file(appContext)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "保存统计失败: ${it.message}") }
    }

    /**
     * 累加播放时长。只在真正播放时调用（每秒一次）。
     * 统计文件很小，每秒写一次盘的代价可以忽略。
     */
    fun addSeconds(context: Context, playlistId: String, bvid: String, seconds: Long = 1L) {
        if (playlistId.isBlank() || bvid.isBlank() || seconds <= 0) return
        init(context)
        val current = _stats.value
        val perTrack = current[playlistId].orEmpty().toMutableMap()
        perTrack[bvid] = (perTrack[bvid] ?: 0L) + seconds
        _stats.value = current + (playlistId to perTrack)
        persist()
    }

    fun removeTrack(context: Context, playlistId: String, bvid: String) {
        init(context)
        val perTrack = _stats.value[playlistId] ?: return
        if (bvid !in perTrack) return
        val next = perTrack - bvid
        _stats.value = if (next.isEmpty()) _stats.value - playlistId
        else _stats.value + (playlistId to next)
        persist()
    }

    /** 同步后裁掉已不存在的曲目统计，避免总时长被下架/移除的曲目虚增。 */
    fun retainTracks(context: Context, playlistId: String, bvids: Collection<String>) {
        init(context)
        val perTrack = _stats.value[playlistId] ?: return
        val keep = bvids.toSet()
        val next = perTrack.filterKeys { it in keep }
        if (next.size == perTrack.size) return
        _stats.value = if (next.isEmpty()) _stats.value - playlistId
        else _stats.value + (playlistId to next)
        persist()
    }

    fun removePlaylist(context: Context, playlistId: String) {
        init(context)
        if (playlistId !in _stats.value) return
        _stats.value = _stats.value - playlistId
        persist()
    }
}
