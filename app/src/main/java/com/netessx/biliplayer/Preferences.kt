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
    private const val KEY_LAST_NOTICE_ID = "last_notice_id"
    private const val KEY_IGNORED_UPDATE_CODE = "ignored_update_code"
    private const val KEY_DESKTOP_LYRICS = "desktop_lyrics"
    private const val KEY_LYRICS_X = "lyrics_overlay_x"
    private const val KEY_LYRICS_Y = "lyrics_overlay_y"
    private const val KEY_LYRICS_SIZE = "lyrics_overlay_size"
    private const val KEY_LYRICS_LOCKED = "lyrics_overlay_locked"
    private const val KEY_LRCLIB_BASE = "lrclib_base_url"
    private const val KEY_LYRICS_DISCLAIMER = "lyrics_disclaimer_accepted"
    private const val KEY_INSTALL_ID = "install_id"

    const val DEFAULT_LRCLIB_BASE = "https://lrclib.net/api"

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

    // ----- 已读公告 id（公告 id 单调递增，用它做去重） -----
    fun lastSeenNoticeId(context: Context): Long = sp(context).getLong(KEY_LAST_NOTICE_ID, 0L)
    fun saveLastSeenNoticeId(context: Context, id: Long) {
        sp(context).edit().putLong(KEY_LAST_NOTICE_ID, id).apply()
    }

    // ----- 桌面歌词（悬浮窗） -----
    fun desktopLyrics(context: Context): Boolean = sp(context).getBoolean(KEY_DESKTOP_LYRICS, false)
    fun saveDesktopLyrics(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DESKTOP_LYRICS, enabled).apply()
    }

    /** 悬浮窗位置（相对顶部居中的偏移，px）。 */
    fun lyricsOverlayX(context: Context): Int = sp(context).getInt(KEY_LYRICS_X, 0)
    fun lyricsOverlayY(context: Context): Int = sp(context).getInt(KEY_LYRICS_Y, 140)
    fun saveLyricsOverlayPos(context: Context, x: Int, y: Int) {
        sp(context).edit().putInt(KEY_LYRICS_X, x).putInt(KEY_LYRICS_Y, y).apply()
    }
    fun clearLyricsOverlayPos(context: Context) {
        sp(context).edit().remove(KEY_LYRICS_X).remove(KEY_LYRICS_Y).apply()
    }

    /** 悬浮窗字号（sp）。 */
    fun lyricsFontSize(context: Context): Float = sp(context).getFloat(KEY_LYRICS_SIZE, 18f)
    fun saveLyricsFontSize(context: Context, size: Float) {
        sp(context).edit().putFloat(KEY_LYRICS_SIZE, size).apply()
    }

    /** 锁定位置：锁定后悬浮窗不可拖动、也不拦截触摸。 */
    fun lyricsOverlayLocked(context: Context): Boolean =
        sp(context).getBoolean(KEY_LYRICS_LOCKED, false)
    fun saveLyricsOverlayLocked(context: Context, locked: Boolean) {
        sp(context).edit().putBoolean(KEY_LYRICS_LOCKED, locked).apply()
    }

    /** 自定义 lrclib 接口地址（可换成自建镜像）。 */
    fun lrcLibBaseUrl(context: Context): String =
        sp(context).getString(KEY_LRCLIB_BASE, DEFAULT_LRCLIB_BASE)?.ifBlank { DEFAULT_LRCLIB_BASE }
            ?: DEFAULT_LRCLIB_BASE
    fun saveLrcLibBaseUrl(context: Context, url: String) {
        val v = url.trim().trimEnd('/')
        sp(context).edit()
            .putString(KEY_LRCLIB_BASE, v.ifBlank { DEFAULT_LRCLIB_BASE })
            .apply()
    }

    /**
     * 本机安装标识：首次调用随机生成并持久化。
     * 用于给 lrclib 的 User-Agent 加上「每台设备/每次安装都不同」的尾巴，
     * 不涉及任何硬件标识（IMEI/Android ID 之类），重装即变。
     */
    fun installId(context: Context): String {
        val prefs = sp(context)
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    /** 是否已同意「歌词来源与免责声明」。 */
    fun lyricsDisclaimerAccepted(context: Context): Boolean =
        sp(context).getBoolean(KEY_LYRICS_DISCLAIMER, false)
    fun saveLyricsDisclaimerAccepted(context: Context, accepted: Boolean) {
        sp(context).edit().putBoolean(KEY_LYRICS_DISCLAIMER, accepted).apply()
    }

    // ----- 已忽略的更新版本（按 versionCode 记录，不能用 versionName） -----
    fun ignoredUpdateVersionCode(context: Context): Int = sp(context).getInt(KEY_IGNORED_UPDATE_CODE, -1)
    fun saveIgnoredUpdateVersionCode(context: Context, code: Int) {
        sp(context).edit().putInt(KEY_IGNORED_UPDATE_CODE, code).apply()
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

    /** 导出全部单曲偏好（原始 JSON 字符串，结构 `{bvid:{p,beginTime,endTime}}`）。 */
    fun exportPreferencesJson(context: Context): String =
        sp(context).getString(KEY_P_PREF, "{}")?.ifBlank { "{}" } ?: "{}"

    /** 合并导入的单曲偏好（同 bvid 覆盖）。返回导入条数。 */
    fun importPreferencesJson(context: Context, json: String): Int {
        val incoming = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        val raw = sp(context).getString(KEY_P_PREF, null)
        val root = runCatching { raw?.let { JSONObject(it) } ?: JSONObject() }
            .getOrDefault(JSONObject())
        var count = 0
        incoming.keys().forEach { bvid ->
            incoming.optJSONObject(bvid)?.let {
                root.put(bvid, it)
                count++
            }
        }
        sp(context).edit().putString(KEY_P_PREF, root.toString()).apply()
        return count
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
