package com.netessx.biliplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 无头浏览器：离屏（不挂载到任何窗口）的 [WebView]，使用桌面端 UA。
 *
 * 作为收藏夹抓取与视频播放共用的底层引擎，提供 load / evaluateJavascript /
 * 生命周期回调。所有 WebView 操作都必须发生在主线程（内部已保证）。
 */
class HeadlessBrowser(private val context: Context) {

    var onPageStarted: ((String?) -> Unit)? = null
    var onPageFinished: ((String?) -> Unit)? = null
    var onMainFrameError: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureCreated() {
        if (webView != null) return
        // 启用远程调试：电脑 Chrome 打开 chrome://inspect 即可实时查看该无头页面的 DOM/控制台
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        log("创建无头 WebView（离屏、PC 端 UA）")
        // 用 KeepVisibleWebView：切换后台/锁屏时 Chromium 仍认为窗口可见，避免浏览器层自动暂停媒体
        val web = KeepVisibleWebView(context.applicationContext)
        web.apply {
            // 给无头 WebView 一个明确的桌面视口尺寸（不挂载到窗口也能拿到宽高）
            val w = View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
            measure(w, h)
            layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = DESKTOP_USER_AGENT
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    log("页面开始加载: $url")
                    onPageStarted?.invoke(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    log("页面加载完成: $url")
                    onPageFinished?.invoke(url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        val msg = "加载出错: code=${error?.errorCode} ${error?.description}"
                        log(msg)
                        onMainFrameError?.invoke(msg)
                    }
                }
            }
        }
        webView = web
    }

    /**
     * 把无头 WebView 挂载到窗口但移动到屏幕外。
     * 关键：B 站空间页/视频页依赖渲染（requestAnimationFrame / IntersectionObserver / 懒加载），
     * 完全离屏不渲染会导致页面内容不加载。挂到窗口后照常渲染，但用户不可见。
     */
    fun attachOffscreen(parent: ViewGroup) {
        ensureCreated()
        val web = webView ?: return
        if (web.parent != null) return
        parent.addView(web, ViewGroup.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
        web.translationX = -VIEWPORT_WIDTH.toFloat() - 20f
        log("WebView 已挂载到窗口（屏幕外渲染）")
    }

    /** 暴露 WebView 实例（用于嵌入 Compose UI 显示调试）。 */
    fun view(): WebView? = webView

    /**
     * 定位 [selector] 元素并发送真实触摸点击（DOWN + UP）。
     * 等价于 Playwright page.click —— JS 的 element.click() 是非可信事件，会被 B 站拦截。
     * [onDone] 返回是否找到并点击了元素。
     */
    fun touchClick(selector: String, onDone: (Boolean) -> Unit) {
        val scrollJs = """
            (function(){
              var el = document.querySelector(${JSONObject.quote(selector)});
              if (!el) return false;
              // 滚动到视口左上角，元素顶到视口顶端
              try { el.scrollIntoView({block:'start', inline:'start'}); } catch(e){}
              return true;
            })()
        """.trimIndent()
        evaluate(scrollJs) { raw ->
            if (!raw.trim().equals("true", ignoreCase = true)) {
                onDone(false)
                return@evaluate
            }
            val web = webView ?: run { onDone(false); return@evaluate }
            // 元素已滚到左上角，固定坐标点击（留一点边距）
            val x = 50f
            val y = 40f
            log("touchClick: 固定点击 ($x, $y)")
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            web.dispatchTouchEvent(down)
            down.recycle()
            mainHandler.postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                web.dispatchTouchEvent(up)
                up.recycle()
                onDone(true)
            }, 80)
        }
    }

    /**
     * 取 [selector] 元素当前视口中心坐标，并派发真实触摸（DOWN + UP）。
     * 真实触摸是可信用户手势，可触发需要 activation 的页面交互（如全屏）。
     */
    fun touchElementCenter(selector: String, onDone: (Boolean) -> Unit) {
        val js = """
            (function(){
              var el = document.querySelector(${JSONObject.quote(selector)});
              if (!el) return JSON.stringify({found:false});
              var r = el.getBoundingClientRect();
              return JSON.stringify({
                found:true,
                x:r.left+r.width/2,
                y:r.top+r.height/2,
                vw:window.innerWidth||document.documentElement.clientWidth||1,
                vh:window.innerHeight||document.documentElement.clientHeight||1
              });
            })()
        """.trimIndent()
        evaluate(js) { raw ->
            val obj = parseJsResult(raw)
            if (obj == null || !obj.optBoolean("found", false)) {
                onDone(false)
                return@evaluate
            }
            val web = webView ?: run { onDone(false); return@evaluate }
            // 通用换算：viewPx = cssPx × (web物理尺寸 / 页面布局视口尺寸)，不同密度/缩放设备都正确
            val vw = obj.optDouble("vw", 1.0)
            val vh = obj.optDouble("vh", 1.0)
            val scaleX = if (vw > 0 && web.width > 0) web.width / vw.toFloat() else 1f
            val scaleY = if (vh > 0 && web.height > 0) web.height / vh.toFloat() else 1f
            val x = obj.optDouble("x", 0.0).toFloat() * scaleX
            val y = obj.optDouble("y", 0.0).toFloat() * scaleY
            log("touchElementCenter: css=(${obj.optDouble("x", 0.0)},${obj.optDouble("y", 0.0)}) " +
                "scale=($scaleX,$scaleY) view=($x,$y) viewport=($vw,$vh) web=(${web.width},${web.height})")
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            web.dispatchTouchEvent(down)
            down.recycle()
            mainHandler.postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                web.dispatchTouchEvent(up)
                up.recycle()
                onDone(true)
            }, 80)
        }
    }

    private fun parseJsResult(raw: String): JSONObject? {
        return try {
            val value = JSONTokener(raw).nextValue()
            if (value is JSONObject) value else JSONObject(value.toString())
        } catch (e: Exception) {
            null
        }
    }

    /** 加载一个 URL。 */
    fun load(url: String) {
        ensureCreated()
        log("加载: $url")
        webView?.loadUrl(url)
    }

    /** 在页面中执行 JS，[callback] 收到 JSON 编码的返回值（与 evaluateJavascript 一致）。 */
    fun evaluate(script: String, callback: ((String) -> Unit)? = null) {
        val web = webView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            web.evaluateJavascript(script) { r -> callback?.invoke(r) }
        } else {
            mainHandler.post { web.evaluateJavascript(script) { r -> callback?.invoke(r) } }
        }
    }

    fun destroy() {
        val w = webView ?: return
        try {
            w.stopLoading()
            w.webViewClient = WebViewClient()
            w.loadUrl("about:blank")
            w.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "销毁 WebView 异常", e)
        }
        webView = null
        log("WebView 已销毁")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    companion object {
        private const val TAG = "HeadlessBrowser"
        private const val VIEWPORT_WIDTH = 1280
        private const val VIEWPORT_HEIGHT = 720
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/**
 * 始终向 Chromium 上报"窗口可见"的 WebView 子类。
 * 当 Activity 被切到后台/锁屏时，系统会把窗口可见性改为 GONE/INVISIBLE，
 * 导致 WebContents 收到 WasHidden 而在浏览器层暂停媒体（页面 JS 无法拦截）。
 * 这里强制上报 VISIBLE，让媒体在后台/锁屏时继续播放。
 */
private class KeepVisibleWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
