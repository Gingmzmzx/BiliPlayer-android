package com.netessx.biliplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台播放服务：持有 [PlayerController]（内含无头 WebView 与播放引擎），
 * 通过 mediaPlayback 前台服务让音频在应用退到后台后继续播放。
 * 注册 MediaSession 以支持锁屏/蓝牙媒体控制，通知带媒体动作并随播放状态更新。
 */
class PlayerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notifJob: Job? = null
    private var progressJob: Job? = null
    private var lastCoverUrl: String? = null
    private var lastArt: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PlayerController.init(applicationContext)
        acquireWakeLock()
        createMediaSession()
        startForegroundCompat()
        // 进度：每次轮询更新（currentTime 每 500ms 变化），保证系统进度条准确
        progressJob = scope.launch {
            PlayerController.state
                .map { it.isPlaying to it.currentTime }
                .distinctUntilChanged()
                .collect { (playing, time) ->
                    updatePlaybackState(playing, time)
                }
        }
        // 标题/时长/封面/通知：变化时更新
        notifJob = scope.launch {
            PlayerController.state
                .map { Triple(it.isPlaying, it.currentTitle, it.currentBvid) to (it.duration to it.currentCover) }
                .distinctUntilChanged()
                .collect { (info, meta) ->
                    val (playing, title, bvid) = info
                    val (duration, cover) = meta
                    val art = if (cover.isNotBlank() && cover != lastCoverUrl) {
                        lastCoverUrl = cover
                        withContext(Dispatchers.IO) { fetchCover(cover) }
                    } else {
                        null
                    }
                    if (art != null) lastArt = art
                    updateMediaMetadata(title, bvid, duration, art)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(playing, title))
                }
        }
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "PlayerService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!PlayerController.state.value.isPlaying) PlayerController.playPause()
                }

                override fun onPause() {
                    if (PlayerController.state.value.isPlaying) PlayerController.playPause()
                }

                override fun onSkipToNext() { PlayerController.next() }
                override fun onSkipToPrevious() { PlayerController.prev() }
                override fun onSeekTo(pos: Long) { PlayerController.seek(pos / 1000.0) } // 系统传毫秒，转成秒
            })
            isActive = true
        }
    }

    /** 持续更新播放进度（PlaybackState.position），供系统进度条显示/拖动。 */
    private fun updatePlaybackState(isPlaying: Boolean, position: Double) {
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                (position * 1000).toLong(), // 单位是毫秒
                1f
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    /** 更新媒体元数据（标题/媒体ID/时长/封面）。 */
    private fun updateMediaMetadata(title: String, bvid: String, duration: Double, art: Bitmap?) {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "BiliPlayer" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "BiliPlayer")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, bvid)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (duration * 1000).toLong()) // 单位是毫秒
        if (art != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
        }
        mediaSession?.setMetadata(builder.build())
    }

    /** 从 B 站图片 URL 抓取封面（需 Referer 防盗链头）。 */
    private fun fetchCover(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("User-Agent", HeadlessBrowser.DESKTOP_USER_AGENT)
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlayerController.playPause()
            ACTION_NEXT -> PlayerController.next()
            ACTION_PREV -> PlayerController.prev()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notifJob?.cancel()
        progressJob?.cancel()
        scope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        PlayerController.release()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliPlayer::playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "后台播放", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification(false, "BiliPlayer 后台播放中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(isPlaying: Boolean, title: String): Notification {
        val prevIntent = PendingIntent.getService(
            this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ppIntent = PendingIntent.getService(
            this, 2, Intent(this, PlayerService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, PlayerService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setLargeIcon(lastArt)
            .setContentTitle("BiliPlayer")
            .setContentText(title.ifBlank { "后台播放中" })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                ppIntent
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.netessx.biliplayer.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.netessx.biliplayer.action.NEXT"
        const val ACTION_PREV = "com.netessx.biliplayer.action.PREV"
        const val ACTION_STOP = "com.netessx.biliplayer.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlayerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }
}
