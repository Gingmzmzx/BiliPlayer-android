package com.netessx.biliplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 桌面歌词悬浮窗（单例）。
 *
 * 用 `TYPE_APPLICATION_OVERLAY` 挂在系统窗口上，覆盖在其它应用之上显示当前歌词。
 * 需要 `SYSTEM_ALERT_WINDOW` 权限。支持拖动改位置、改字号，位置与字号持久化。
 * 由 [PlayerService] 驱动显示/隐藏/更新；服务销毁时一并移除。
 */
object LyricsOverlay {

    private const val TAG = "LyricsOverlay"
    private const val DEFAULT_Y = 140
    private const val DEFAULT_SIZE_SP = 18f

    private var appContext: Context? = null
    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var fontSizeSp: Float = DEFAULT_SIZE_SP

    fun init(context: Context) {
        appContext = context.applicationContext
        wm = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        fontSizeSp = Preferences.lyricsFontSize(appContext!!)
    }

    fun canDraw(): Boolean {
        val ctx = appContext ?: return false
        return Settings.canDrawOverlays(ctx)
    }

    @SuppressLint("RtlHardcoded")
    fun show() {
        if (view != null) return
        val ctx = appContext ?: return
        val manager = wm ?: return
        if (!canDraw()) {
            Log.w(TAG, "没有悬浮窗权限，跳过显示桌面歌词")
            return
        }
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = fontSizeSp
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 3
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(32, 16, 32, 16)
            val screenW = ctx.resources.displayMetrics.widthPixels
            maxWidth = (screenW * 0.86f).toInt()
        }
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val locked = Preferences.lyricsOverlayLocked(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            maybeTouchableFlags(locked),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = Preferences.lyricsOverlayX(ctx)
            y = Preferences.lyricsOverlayY(ctx)
        }

        // 双指缩放改字号
        val scaleDetector = ScaleGestureDetector(ctx,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    fontSizeSp = (fontSizeSp * detector.scaleFactor).coerceIn(10f, 40f)
                    tv.textSize = fontSizeSp
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    Preferences.saveLyricsFontSize(ctx, fontSizeSp)
                }
            })

        // 单指拖动改位置，松手后记住
        tv.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(e)
                if (e.pointerCount > 1) return true

                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX
                        downRawY = e.rawY
                        startX = lp.x
                        startY = lp.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downRawX).toInt()
                        lp.y = startY + (e.rawY - downRawY).toInt()
                        runCatching { manager.updateViewLayout(tv, lp) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        Preferences.saveLyricsOverlayPos(ctx, lp.x, lp.y)
                    }
                }
                return true
            }
        })

        runCatching { manager.addView(tv, lp) }
            .onSuccess {
                view = tv
                params = lp
            }
            .onFailure { Log.w(TAG, "添加桌面歌词失败: ${it.message}") }
    }

    fun hide() {
        val tv = view ?: return
        runCatching { wm?.removeView(tv) }
            .onFailure { Log.w(TAG, "移除桌面歌词失败: ${it.message}") }
        view = null
        params = null
    }

    /** 更新歌词内容；空串时隐藏文字，避免桌面留一条空白条。 */
    fun update(text: String) {
        val tv = view ?: return
        tv.text = text
        tv.visibility = if (text.isBlank()) View.INVISIBLE else View.VISIBLE
    }

    /** 窗口 flags：不抢焦点；未锁定时可触摸（用于拖动/缩放），锁定后不拦截触摸。 */
    private fun maybeTouchableFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    fun isLocked(): Boolean =
        appContext?.let { Preferences.lyricsOverlayLocked(it) } ?: false

    /** 锁定/解锁位置。锁定后窗口不再拦截触摸，也不会被拖动。 */
    fun setLocked(locked: Boolean) {
        val ctx = appContext ?: return
        Preferences.saveLyricsOverlayLocked(ctx, locked)
        val lp = params ?: return
        val tv = view ?: return
        lp.flags = maybeTouchableFlags(locked)
        runCatching { wm?.updateViewLayout(tv, lp) }
    }

    /** 调整字号并持久化。 */
    fun setFontSize(sp: Float) {
        val size = sp.coerceIn(10f, 40f)
        fontSizeSp = size
        appContext?.let { Preferences.saveLyricsFontSize(it, size) }
        view?.textSize = size
    }

    fun currentFontSize(): Float = fontSizeSp

    /** 位置回到默认（顶部居中）。 */
    fun resetPosition() {
        val ctx = appContext ?: return
        Preferences.clearLyricsOverlayPos(ctx)
        val lp = params ?: return
        lp.x = 0
        lp.y = DEFAULT_Y
        val tv = view ?: return
        runCatching { wm?.updateViewLayout(tv, lp) }
    }
}
