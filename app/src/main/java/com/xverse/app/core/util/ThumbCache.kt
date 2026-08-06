package com.xverse.app.core.util

import android.content.Context
import android.graphics.BitmapFactory
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 缩略图本地缓存：拉取网络缩略图落盘到 filesDir/thumb/，返回本地路径。
 * 历史页与下载中心共用 —— 列表显示只读本地，零网络、切 Tab 不重载。
 * 缓存键为调用方给定的文件名前缀（历史=history-{id}，下载=download-{id}），
 * 避免与下载文件本身混淆。
 */
object ThumbCache {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun thumbDir(context: Context): File =
        File(context.filesDir, "thumb").apply { mkdirs() }

    /** 由缓存名（如 history-3）拼接磁盘路径 */
    fun pathFor(context: Context, key: String): String =
        File(thumbDir(context), "$key.jpg").absolutePath

    /** 拉取网络缩略图并落盘。失败静默返回空串（列表仍可显示占位）。 */
    suspend fun persist(context: Context, key: String, url: String): String {
        return try {
            withContext(Dispatchers.IO) {
                if (url.isBlank()) return@withContext ""
                val target = File(thumbDir(context), "$key.jpg")
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ""
                    val bytes = resp.body?.bytes() ?: return@withContext ""
                    if (bytes.isEmpty()) return@withContext ""
                    target.writeBytes(bytes)
                }
                target.absolutePath
            }
        } catch (e: Exception) {
            LogStore.log(LogCategory.HISTORY, "缩略图落盘失败: $url（${e.message ?: ""}）")
            ""
        }
    }

    /** 校验本地缩略图是否有效（存在且可解码）。只读边界，不整图解码，秒回。 */
    fun isValid(path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) return false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts) != null && opts.outWidth > 0 && opts.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }
}
