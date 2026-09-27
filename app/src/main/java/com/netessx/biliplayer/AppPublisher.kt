package com.netessx.biliplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * AppPublisher 更新 / 公告客户端。
 *
 * 严格按 `client-integration.md` 实现：
 *  - 只比较 [ReleaseInfo.versionCode]，不比较 versionName；
 *  - `timestamp` 是 epoch **毫秒**；
 *  - 增量字段（appName/size/sha256/forceUpdate）全部有默认值，早期数据缺失不会解析崩溃；
 *  - 404 与 5xx/超时一律「静默」，不向外抛异常；
 *  - 下载直接用响应里的 `download` 绝对地址，不自行拼接；
 *  - 所有网络调用在 IO 线程，带 5 秒超时，整体包在 runCatching 里。
 */

/** 一次发布。增量字段必须给默认值。 */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val desc: String = "",
    val timestamp: Long = 0L,
    val download: String,
    // ↓ 增量字段：早期数据里可能缺失 / 为空
    val appName: String? = null,
    val size: Long = 0L,
    val sha256: String = "",
    val forceUpdate: Boolean = false,
)

/** 一条公告。 */
data class NoticeInfo(
    val title: String = "",
    val content: String = "",
    val timestamp: Long = 0L,
    val id: Long = 0L,
)

object AppPublisher {

    private const val TAG = "AppPublisher"

    // ---- 集中配置：不要散落到各处 ----
    private const val BASE_URL = "https://apps.netessx.com"
    private const val SLUG = "BiliPlayerAndroid"

    private const val TIMEOUT_MS = 5_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    private fun url(path: String) = "$BASE_URL/$SLUG$path"

    // ============================================================
    // 网络
    //  返回 (状态码, 响应体)。状态码 0 表示超时 / 断网 / 其它异常。
    // ============================================================
    private fun httpGet(target: String, readTimeout: Int = TIMEOUT_MS): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                this.readTimeout = readTimeout
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                // 服务端已返回 no-cache，这里再显式声明一次，任何中间层都不要缓存
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                code to null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                code to body
            }
        } catch (e: Exception) {
            Log.d(TAG, "httpGet 失败: ${e.message}")
            0 to null
        } finally {
            conn?.disconnect()
        }
    }

    // ============================================================
    // 检查更新
    // ============================================================

    /** 返回 null 表示「无更新 / 服务端不可用 / 应用未上线」——三者都静默。 */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = httpGet(url("/releases/latest"))
            if (code != HttpURLConnection.HTTP_OK || body == null) return@runCatching null
            parseRelease(body)
        }.getOrNull()
    }

    /** 有新版本 ⟺ response.versionCode > 本应用当前 versionCode。 */
    suspend fun checkUpdate(currentVersionCode: Int): ReleaseInfo? =
        fetchLatestRelease()?.takeIf { it.versionCode > currentVersionCode }

    /** 最新公告；404（还没发过公告）同样返回 null。 */
    suspend fun fetchLatestNotice(): NoticeInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = httpGet(url("/notices/latest"))
            if (code != HttpURLConnection.HTTP_OK || body == null) return@runCatching null
            parseNotice(body)
        }.getOrNull()
    }

    // ============================================================
    // 解析（字段名严格对应，增量字段兜底）
    // ============================================================
    private fun parseRelease(json: String): ReleaseInfo? = runCatching {
        val o = JSONObject(json)
        ReleaseInfo(
            versionName = o.optString("versionName", ""),
            versionCode = o.optInt("versionCode", 0),
            desc = o.optString("desc", ""),                 // 注意是 desc，不是 description
            timestamp = o.optLong("timestamp", 0L),         // epoch 毫秒
            download = o.optString("download", ""),         // 绝对 URL
            appName = o.optString("appName", "").ifBlank { null },   // 缺失/为空都算 null
            size = o.optLong("size", 0L),
            sha256 = o.optString("sha256", ""),
            forceUpdate = o.optBoolean("forceUpdate", false),
        )
    }.getOrNull()

    private fun parseNotice(json: String): NoticeInfo? = runCatching {
        val o = JSONObject(json)
        NoticeInfo(
            title = o.optString("title", ""),
            content = o.optString("content", ""),           // 纯文本，可能含换行
            timestamp = o.optLong("timestamp", 0L),
            id = o.optLong("id", 0L),
        )
    }.getOrNull()

    // ============================================================
    // 下载
    // ============================================================

    /**
     * 下载产物到 cacheDir/updates/。
     * 404（版本已下架）与 410（服务端文件丢失）都返回 null，调用方提示即可，不重试。
     * sha256 为空串表示早期数据，跳过校验。
     */
    suspend fun downloadRelease(
        context: Context,
        info: ReleaseInfo,
        onProgress: (Int) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val fallbackExt = guessExt(info.download)
            val safeName = info.versionName.ifBlank { info.versionCode.toString() }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            var target = File(dir, "$SLUG-$safeName$fallbackExt")

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(info.download).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                    useCaches = false
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    // 404 / 410 / 5xx —— 都当作「拿不到」
                    Log.d(TAG, "下载失败，HTTP $code")
                    return@runCatching null
                }

                // 产物不一定是 apk：优先用服务端给的文件名
                val disposition = conn.getHeaderField("Content-Disposition")
                val serverName = Regex("filename=\"?([^\";]+)\"?")
                    .find(disposition ?: "")
                    ?.groupValues?.getOrNull(1)
                if (!serverName.isNullOrBlank()) {
                    target = File(dir, serverName)
                }

                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var sum = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            sum += read
                            if (total > 0) onProgress(((sum * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            } finally {
                conn?.disconnect()
            }

            // sha256 非空则校验
            if (info.sha256.isNotBlank() && sha256Of(target) != info.sha256.lowercase()) {
                Log.w(TAG, "SHA-256 校验失败")
                target.delete()
                return@runCatching null
            }
            target
        }.getOrNull()
    }

    private fun guessExt(download: String): String =
        Regex("\\.([A-Za-z0-9]{2,5})(?:$|\\?)").find(download)
            ?.groupValues?.getOrNull(1)?.lowercase() ?: "apk"

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ============================================================
    // 安装（仅当产物确实是 apk）
    // ============================================================
    fun isApk(file: File): Boolean = file.extension.equals("apk", ignoreCase = true)

    /** Android 7.0+ 必须走 FileProvider，否则抛 FileUriExposedException。 */
    fun installApk(context: Context, apk: File) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "安装失败: ${it.message}")
        }
    }
}
