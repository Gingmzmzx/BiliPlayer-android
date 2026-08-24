package com.netessx.biliplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 配置持久化（SharedPreferences）。对应 Python 项目的 Config + Player.preference。 */
object Preferences {
    private const val PREFS = "biliplayer_prefs"
    private const val KEY_UID = "uid"
    private const val KEY_FAV_NAME = "fav_name"
    private const val KEY_VOLUME = "default_volume"
    private const val KEY_PLAY_MODE = "play_mode"
    private const val KEY_P_PREF = "p_pref"
    private const val KEY_AUTO_FULLSCREEN = "auto_fullscreen"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ----- 身份（UID + 收藏夹名） -----
    fun uid(context: Context): String = sp(context).getString(KEY_UID, "227711953") ?: ""
    fun favName(context: Context): String = sp(context).getString(KEY_FAV_NAME, "豪听") ?: ""
    fun saveIdentity(context: Context, uid: String, favName: String) {
        sp(context).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_FAV_NAME, favName)
            .apply()
    }

    // ----- 默认音量 -----
    fun defaultVolume(context: Context): Int = sp(context).getInt(KEY_VOLUME, 30)
    fun saveVolume(context: Context, v: Int) {
        sp(context).edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply()
    }

    // ----- 播放模式 -----
    fun playMode(context: Context): PlayMode =
        runCatching { PlayMode.valueOf(sp(context).getString(KEY_PLAY_MODE, "SHUFFLE") ?: "SHUFFLE") }
            .getOrDefault(PlayMode.SHUFFLE)

    fun savePlayMode(context: Context, mode: PlayMode) {
        sp(context).edit().putString(KEY_PLAY_MODE, mode.name).apply()
    }

    // ----- 自动全屏 -----
    fun autoFullscreen(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_FULLSCREEN, true)
    fun saveAutoFullscreen(context: Context, v: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_FULLSCREEN, v).apply()
    }

    // ----- 每首歌的偏好（bvid -> {p, beginTime, endTime}） -----
    fun preference(context: Context, bvid: String): TrackPreference {
        val raw = sp(context).getString(KEY_P_PREF, null) ?: return TrackPreference()
        return try {
            val o = JSONObject(raw).optJSONObject(bvid) ?: return TrackPreference()
            TrackPreference(
                p = if (o.has("p")) o.getInt("p") else null,
                beginTime = if (o.has("beginTime")) o.getInt("beginTime") else null,
                endTime = if (o.has("endTime")) o.getInt("endTime") else null,
            )
        } catch (e: Exception) {
            TrackPreference()
        }
    }

    fun savePreference(context: Context, bvid: String, pref: TrackPreference) {
        val raw = sp(context).getString(KEY_P_PREF, null)
        val root = try { raw?.let { JSONObject(it) } ?: JSONObject() } catch (e: Exception) { JSONObject() }
        if (pref.p == null && pref.beginTime == null && pref.endTime == null) {
            root.remove(bvid)
        } else {
            val o = root.optJSONObject(bvid) ?: JSONObject()
            pref.p?.let { o.put("p", it) } ?: o.remove("p")
            pref.beginTime?.let { o.put("beginTime", it) } ?: o.remove("beginTime")
            pref.endTime?.let { o.put("endTime", it) } ?: o.remove("endTime")
            root.put(bvid, o)
        }
        sp(context).edit().putString(KEY_P_PREF, root.toString()).apply()
    }

    fun clearPreference(context: Context, bvid: String) {
        val raw = sp(context).getString(KEY_P_PREF, null) ?: return
        val root = try { JSONObject(raw) } catch (e: Exception) { return }
        root.remove(bvid)
        sp(context).edit().putString(KEY_P_PREF, root.toString()).apply()
    }
}

/** 单曲偏好：分P、开始秒、结束秒（null 表示不设置）。 */
data class TrackPreference(
    val p: Int? = null,
    val beginTime: Int? = null,
    val endTime: Int? = null,
)
