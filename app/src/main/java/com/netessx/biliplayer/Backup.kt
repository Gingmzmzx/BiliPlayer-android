package com.netessx.biliplayer

import android.content.Context
import org.json.JSONObject

/**
 * 偏好与歌词的导入导出。
 *
 * 导出成一个 JSON 文件（存哪由用户通过系统文件选择器决定），
 * 包含所有歌曲的单曲偏好（分P / 开始秒 / 结束秒）与歌词缓存（含偏移）。
 * 导入时按 bvid 合并覆盖，不动其它内容。
 */
object Backup {

    const val SUGGESTED_NAME = "biliplayer-backup.json"

    data class ImportResult(val preferences: Int, val lyrics: Int)

    /** org.json 会把 JSON null 读成字符串 "null"，这里统一洗净。 */
    private fun JSONObject.text(key: String): String {
        val v = opt(key) ?: return ""
        if (v === JSONObject.NULL) return ""
        val s = v.toString()
        return if (s.equals("null", ignoreCase = true)) "" else s
    }

    fun export(context: Context): String {
        val root = JSONObject()
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("preferences", JSONObject(Preferences.exportPreferencesJson(context)))

        val lyrics = JSONObject()
        LyricsStore.snapshot().forEach { (bvid, e) ->
            lyrics.put(
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
        root.put("lyrics", lyrics)
        return root.toString(2)
    }

    /** 返回 null 表示文件不是本应用导出的格式。 */
    fun import(context: Context, json: String): ImportResult? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        val prefsObj = root.optJSONObject("preferences")
        val prefCount = if (prefsObj != null) {
            Preferences.importPreferencesJson(context, prefsObj.toString())
        } else 0

        val lyricsObj = root.optJSONObject("lyrics")
        val entries = mutableMapOf<String, LyricsEntry>()
        lyricsObj?.keys()?.forEach { bvid ->
            val o = lyricsObj.optJSONObject(bvid) ?: return@forEach
            val entry = LyricsEntry(
                bvid = bvid,
                trackName = o.text("trackName"),
                artistName = o.text("artistName"),
                synced = o.text("synced"),
                plain = o.text("plain"),
                manual = o.optBoolean("manual", false),
                offsetMs = o.optLong("offsetMs", 0L),
            )
            if (entry.hasContent) entries[bvid] = entry
        }
        LyricsStore.merge(context, entries)

        return ImportResult(prefCount, entries.size)
    }
}
