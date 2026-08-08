package com.xverse.app.core.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主线程执行器：工程规范 #3。
 * CookieManager、webView.settings 等仅允许主线程访问，后台线程需要同步读取时走 [postAndWait]。
 */
object UiExecutor {
    private val handler = Handler(Looper.getMainLooper())

    /** 是否位于主线程 */
    val isMainThread: Boolean
        get() = Looper.myLooper() == Looper.getMainLooper()

    /** 主线程执行，不等结果（异步） */
    fun post(block: () -> Unit) {
        if (isMainThread) block() else handler.post(block)
    }

    /** 主线程同步执行并等待结果；超时 10s 返回 null（供 Kotlin 侧取 Cookie 等）。 */
    fun <T> postAndWait(block: () -> T): T? {
        if (isMainThread) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        error?.let { throw it }
        return result
    }
}
