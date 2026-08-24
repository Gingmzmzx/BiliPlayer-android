package com.netessx.biliplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PlayerController.init(applicationContext)
        acquireWakeLock()
        createMediaSession()
        startForegroundCompat()
        // 监听播放状态变化，更新通知与 MediaSession（锁屏/蓝牙控制、标题、播放状态）
        notifJob = scope.launch {
            PlayerController.state
                .map { Triple(it.isPlaying, it.currentTitle, it.currentBvid) }
                .distinctUntilChanged()
                .collect { (playing, title, bvid) ->
                    updateMediaSession(playing, title, bvid)
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
            })
            isActive = true
        }
    }

    private fun updateMediaSession(isPlaying: Boolean, title: String, bvid: String) {
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlayerController.state.value.currentTime.toLong(),
                1f
            )
            .build()
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "BiliPlayer" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "BiliPlayer")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, bvid)
            .build()
        mediaSession?.setPlaybackState(state)
        mediaSession?.setMetadata(metadata)
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
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
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
