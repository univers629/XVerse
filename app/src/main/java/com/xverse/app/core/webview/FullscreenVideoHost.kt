package com.xverse.app.core.webview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import kotlin.math.roundToInt

/**
 * 网页全屏（HTML5 Fullscreen API）宿主。
 *
 * WebView 不会自己全屏，它通过 [WebChromeClient.onShowCustomView] 把承载视频的视图交出来，
 * 由这里包进黑底容器、挂到窗口最上层（DecorView）铺满整窗。
 *
 * 容器必须留在本窗口内：视频是独立合成面片，Chromium 用窗口局部坐标在窗口上挖透明洞来显示它，
 * 视频视图一旦挪进另一个窗口，洞就会错位到应用窗口上。
 * 进出全屏用布局动画：SurfaceView 只跟随布局变化，alpha / scale 对它无效。
 */
class FullscreenVideoHost(private val webView: XWebView) {

    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var activity: Activity? = null
    private var backCallback: OnBackPressedCallback? = null
    private var keepScreenOnAdded = false

    private var container: FrameLayout? = null
    private var windowParent: ViewGroup? = null
    private var resizeAnimator: ValueAnimator? = null

    /** 退出动画结束后执行的收尾动作。 */
    private var pendingCleanup: (() -> Unit)? = null

    /** 是否处于网页全屏态。 */
    val isActive: Boolean get() = customView != null

    /** 转发 [WebChromeClient.onShowCustomView]。 */
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        // 上次的退出动画可能还没结束
        cancelPendingCleanup()
        if (isActive) tearDownImmediate()

        // 视图可能仍挂在原父容器上
        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        this.callback = callback

        if (!showInWindow(view)) {
            LogStore.log(LogCategory.WEBVIEW, "Fullscreen video rejected: no host container")
            customView = null
            this.callback = null
            safeCustomViewHidden(callback)
            return
        }

        val act = webView.findHostActivity()
        this.activity = act
        if (act != null) {
            setKeepScreenOn(act, true)
            installBackCallback(act)
        }
        // 画面切进容器后需要重新计算挖洞矩形
        kickWebViewLayout()
        repaintHostWindow()
        webView.postDelayed({
            if (isActive) repaintHostWindow()
        }, REPAINT_SETTLE_DELAY_MS)
        LogStore.log(LogCategory.WEBVIEW, "Fullscreen video entered (window container)")
    }

    /** 挂到窗口最上层，并从网页区域矩形展开到整窗。 */
    private fun showInWindow(view: View): Boolean {
        val decor = webView.rootView as? ViewGroup ?: return false
        val box = newBlackContainer(view)
        val decorWidth = decor.width
        val decorHeight = decor.height
        val from = webViewRectIn(decor)

        return try {
            if (decorWidth <= 0 || decorHeight <= 0 || from == null) {
                // 窗口还没测量过：直接铺满
                decor.addView(
                    box,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            } else {
                // 先落在网页区域矩形上，避免第一帧闪到整窗
                decor.addView(
                    box,
                    FrameLayout.LayoutParams(from.width(), from.height()).apply {
                        leftMargin = from.left
                        topMargin = from.top
                    },
                )
                animateBounds(box, from, Rect(0, 0, decorWidth, decorHeight), ENTER_ANIM_MS, DecelerateInterpolator(), null)
            }
            decor.requestLayout()
            container = box
            windowParent = decor
            true
        } catch (e: Exception) {
            LogStore.log(LogCategory.WEBVIEW, "Fullscreen video attach failed: ${e.message}")
            runCatching { box.removeAllViews() }
            false
        }
    }

    private fun newBlackContainer(view: View): FrameLayout =
        FrameLayout(webView.context).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

    /** 转发 [WebChromeClient.onHideCustomView]。 */
    fun onHideCustomView() {
        if (!isActive) return
        LogStore.log(LogCategory.WEBVIEW, "Fullscreen video exited by page")
        beginExit(notifyPage = false)
    }

    /** 返回键退出：先收缩、最后才通知页面，此刻画面仍在渲染。 */
    private fun exitFullscreen() {
        LogStore.log(LogCategory.WEBVIEW, "Fullscreen video exit requested by user")
        beginExit(notifyPage = true)
    }

    /** [notifyPage] 表示页面仍处于全屏态，需由宿主动画结束后通知它退出。 */
    private fun beginExit(notifyPage: Boolean) {
        val view = customView
        val cb = callback
        val act = activity
        // 先清状态：动画期间 isActive 即为 false
        customView = null
        callback = null
        activity = null

        backCallback?.let {
            it.isEnabled = false
            it.remove()
        }
        backCallback = null
        if (act != null) setKeepScreenOn(act, false)

        val box = container
        val parent = windowParent
        container = null
        windowParent = null

        val cleanup: () -> Unit = {
            pendingCleanup = null
            // 先摘视频视图，再撤容器
            runCatching { box?.removeAllViews() }
            if (parent != null && box != null) {
                runCatching {
                    parent.removeView(box)
                    parent.requestLayout()
                }
            } else {
                (view?.parent as? ViewGroup)?.removeView(view)
            }
            if (view != null) {
                kickWebViewLayout()
                repaintHostWindow()
                // 仅在探针判定视口失效时才会重载
                webView.repairViewportAfterResize()
            }
        }
        val finish: () -> Unit = {
            if (notifyPage) safeCustomViewHidden(cb)
            cleanup()
        }

        val to = if (box != null && parent != null) webViewRectIn(parent) else null
        if (box == null || parent == null || to == null) {
            finish()
            return
        }
        val from = Rect(box.left, box.top, box.right, box.bottom)
        pendingCleanup = finish

        if (notifyPage) {
            animateBounds(box, from, to, EXIT_ANIM_MS, AccelerateInterpolator()) { finish() }
        } else {
            freezeThenShrink(box, act, from, to, finish)
        }
    }

    /** 页面已自行退出：用最后一帧冻结画面顶替视频视图，再收缩。 */
    private fun freezeThenShrink(
        box: FrameLayout,
        act: Activity?,
        from: Rect,
        to: Rect,
        finish: () -> Unit,
    ) {
        var settled = false
        val settle: (Bitmap?, Boolean) -> Unit = { bitmap, exact ->
            if (!settled) {
                settled = true
                if (bitmap == null) {
                    LogStore.log(LogCategory.WEBVIEW, "Fullscreen exit without frozen frame")
                    finish()
                } else {
                    LogStore.log(LogCategory.WEBVIEW, "Fullscreen exit with frozen frame")
                    showFrozenFrame(box, bitmap, exact)
                    animateBounds(box, from, to, EXIT_ANIM_MS, AccelerateInterpolator()) { finish() }
                }
            }
        }
        // 抓帧是异步的，超时按失败处理
        webView.postDelayed({ settle(null, false) }, FREEZE_TIMEOUT_MS)
        captureFrame(box, act, settle)
    }

    /** 抓取容器当前内容；优先进整窗抓帧，退回抓视频面片。整帧几乎全黑视为失败。 */
    private fun captureFrame(box: FrameLayout, act: Activity?, onResult: (Bitmap?, Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        if (act != null && box.width > 0 && box.height > 0) {
            val bitmap = newBitmap(box.width, box.height)
            if (bitmap != null) {
                try {
                    val rect = Rect(box.left, box.top, box.right, box.bottom)
                    PixelCopy.request(act.window, rect, bitmap, { result ->
                        onResult(if (result == PixelCopy.SUCCESS && !isMostlyBlack(bitmap)) bitmap else null, true)
                    }, handler)
                    return
                } catch (e: Exception) {
                    LogStore.log(LogCategory.WEBVIEW, "Fullscreen frame capture failed: ${e.message}")
                }
            }
        }

        val surface = findSurfaceView(box)
        val surfaceWidth = surface?.width ?: 0
        val surfaceHeight = surface?.height ?: 0
        if (surface != null && surfaceWidth > 0 && surfaceHeight > 0 &&
            surface.holder?.surface?.isValid == true
        ) {
            val bitmap = newBitmap(surfaceWidth, surfaceHeight)
            if (bitmap != null) {
                try {
                    PixelCopy.request(surface, bitmap, { result ->
                        onResult(if (result == PixelCopy.SUCCESS && !isMostlyBlack(bitmap)) bitmap else null, false)
                    }, handler)
                    return
                } catch (e: Exception) {
                    LogStore.log(LogCategory.WEBVIEW, "Fullscreen frame capture failed: ${e.message}")
                }
            }
        }
        onResult(null, false)
    }

    /** 需先摘掉视频面片，它挖的洞会让位图画不出来。 */
    private fun showFrozenFrame(box: FrameLayout, bitmap: Bitmap, exact: Boolean) {
        runCatching { box.removeAllViews() }
        val frame = ImageView(webView.context).apply {
            setImageBitmap(bitmap)
            scaleType = if (exact) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
        }
        box.addView(
            frame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun newBitmap(width: Int, height: Int): Bitmap? =
        runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()

    /** 抽样判断整帧是否几乎全黑。 */
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        var bright = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luma = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                if (luma > 40) bright++
                total++
                x += 24
            }
            y += 24
        }
        return total == 0 || bright * 100 / total < 1
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 销毁前拆除全屏容器。 */
    fun release() {
        cancelPendingCleanup()
        if (isActive) tearDownImmediate()
    }

    /** 立即拆除，不做动画。 */
    private fun tearDownImmediate() {
        val view = customView
        val act = activity
        customView = null
        callback = null
        activity = null

        backCallback?.let {
            it.isEnabled = false
            it.remove()
        }
        backCallback = null
        if (act != null) setKeepScreenOn(act, false)

        val box = container
        val parent = windowParent
        container = null
        windowParent = null
        resizeAnimator?.cancel()
        resizeAnimator = null
        pendingCleanup = null

        runCatching { box?.removeAllViews() }
        if (parent != null && box != null) {
            runCatching {
                parent.removeView(box)
                parent.requestLayout()
            }
        } else {
            (view?.parent as? ViewGroup)?.removeView(view)
        }
        if (view != null) {
            kickWebViewLayout()
            repaintHostWindow()
            webView.repairViewportAfterResize()
        }
    }

    /** 收尾上一次未完成的退出动画。 */
    private fun cancelPendingCleanup() {
        val finish = pendingCleanup ?: return
        resizeAnimator?.cancel()
        resizeAnimator = null
        finish()
    }

    /** 用布局动画插值容器矩形（SurfaceView 不跟随 alpha / scale）。 */
    private fun animateBounds(
        box: FrameLayout,
        from: Rect,
        to: Rect,
        duration: Long,
        interpolator: Interpolator,
        onEnd: (() -> Unit)?,
    ) {
        resizeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val lp = box.layoutParams as? FrameLayout.LayoutParams ?: return@addUpdateListener
                lp.width = lerp(from.width(), to.width(), t)
                lp.height = lerp(from.height(), to.height(), t)
                lp.leftMargin = lerp(from.left, to.left, t)
                lp.topMargin = lerp(from.top, to.top, t)
                box.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (resizeAnimator === this@apply) resizeAnimator = null
                    // cancel 时由调用方收尾
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
        resizeAnimator = animator
    }

    /** WebView 在 [parent] 坐标系中的矩形。 */
    private fun webViewRectIn(parent: ViewGroup): Rect? {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return null
        val viewLoc = IntArray(2)
        val parentLoc = IntArray(2)
        webView.getLocationInWindow(viewLoc)
        parent.getLocationInWindow(parentLoc)
        val left = viewLoc[0] - parentLoc[0]
        val top = viewLoc[1] - parentLoc[1]
        return Rect(left, top, left + width, top + height)
    }

    private fun kickWebViewLayout() {
        webView.requestLayout()
        webView.postInvalidateOnAnimation()
    }

    /** 整窗重排重绘一次，促使 Chromium 重算挖洞矩形。 */
    private fun repaintHostWindow() {
        val root = webView.rootView ?: return
        root.requestLayout()
        root.invalidate()
        root.postInvalidateOnAnimation()
    }

    /** 返回键优先退出全屏，而不是退出 Activity。 */
    private fun installBackCallback(act: Activity) {
        val owner = act as? ComponentActivity ?: return
        backCallback?.let {
            it.isEnabled = false
            it.remove()
        }
        val cb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitFullscreen()
            }
        }
        cb.isEnabled = true
        owner.onBackPressedDispatcher.addCallback(owner, cb)
        backCallback = cb
    }

    /** 只清理由本类添加的那一次标记。 */
    private fun setKeepScreenOn(act: Activity, on: Boolean) {
        val window = act.window
        if (on) {
            val alreadyOn = window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            if (!alreadyOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                keepScreenOnAdded = true
            }
        } else if (keepScreenOnAdded) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnAdded = false
        }
    }

    private fun safeCustomViewHidden(cb: WebChromeClient.CustomViewCallback?) {
        if (cb == null) return
        try {
            cb.onCustomViewHidden()
        } catch (e: Exception) {
            LogStore.log(LogCategory.WEBVIEW, "Fullscreen exit callback failed: ${e.message}")
        }
    }

    /** WebView 的 context 链上找 Activity；拿不到不影响全屏显示。 */
    private fun WebView.findHostActivity(): Activity? =
        context.findActivity() ?: rootView?.context?.findActivity()

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).roundToInt()

    private companion object {
        const val ENTER_ANIM_MS = 260L
        const val EXIT_ANIM_MS = 200L
        const val FREEZE_TIMEOUT_MS = 120L
        const val REPAINT_SETTLE_DELAY_MS = 350L
    }
}
