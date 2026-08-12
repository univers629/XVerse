package com.xverse.app.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 缩略图组件：
 *  - 有本地文件（thumbPath）→ 直接解码本地，毫秒级、零网络，切 Tab 不重载；
 *  - 无本地文件且为图片直链 → 拉网络小图兜底；
 *  - 视频直链 → 不解码，显示影片图标（视频的封面在 thumbPath）。
 *
 * 内存位图缓存：解码/拉取结果缓存到 LruCache，页面切换重组合时第一帧
 * 即命中缓存直接出图，不再闪占位图标。
 */
@Composable
fun NetworkThumb(url: String, modifier: Modifier = Modifier, thumbPath: String = "") {
    // 有本地缩略图时按图显示（无论 mediaUrl 是否 .mp4 —— 存量坏数据 mediaUrl 可能是 mp4 直链，
    // 但本地 thumbPath 已是海报帧 jpg，应显示图片而非影片图标）
    val isVideo = !thumbPath.isNotBlank() && url.contains(".mp4", ignoreCase = true)
    // 缓存键：本地缩略图路径优先，否则网络直链
    val cacheKey = if (thumbPath.isNotBlank()) thumbPath else url
    // 组合期同步命中内存缓存 → 切 Tab 回来第一帧即出图，无占位闪烁
    val cachedBitmap = remember(cacheKey) { thumbBitmapCache.get(cacheKey) }
    val bitmap by produceState<Bitmap?>(initialValue = cachedBitmap, key1 = cacheKey) {
        if (cachedBitmap != null) return@produceState
        val bmp = when {
            // 本地缩略图优先：采样解码（列表显示 ~56dp，全尺寸解码 480px 大图会慢且吃内存）
            thumbPath.isNotBlank() -> withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(thumbPath)
                    if (f.exists()) decodeSampled(f, THUMB_TARGET_PX) else null
                }.getOrNull()
            }
            // 视频无本地封面：显示图标（不解码 mp4）
            isVideo -> null
            // 图片直链兜底（历史页等无本地缩略图的场景）：采样解码网络字节
            else -> runCatching {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).build()
                    thumbClient.newCall(req).execute().use { resp ->
                        decodeSampled(resp.body.bytes(), THUMB_TARGET_PX)
                    }
                }
            }.getOrNull()
        }
        if (bmp != null) thumbBitmapCache.put(cacheKey, bmp)
        value = bmp
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                if (isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                contentDescription = if (isVideo) "视频" else "媒体",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 内存位图缓存：按字节计权，上限为运行时最大内存 1/8（至少 4MB） */
private val thumbBitmapCache = object : LruCache<String, Bitmap>(thumbCacheMaxBytes()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private fun thumbCacheMaxBytes(): Int {
    val maxMem = Runtime.getRuntime().maxMemory()
    return (maxMem / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
}

/** 采样目标边长：列表缩略图显示约 56dp（~176px @440dpi），解码到 256px 内足够清晰且快 */
private const val THUMB_TARGET_PX = 256

/** 文件采样解码：先读边界算采样率，再按需降采样，避免全尺寸解码大图 */
private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = computeSample(bounds.outWidth, bounds.outHeight, targetPx)
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }
}

/** 字节数组采样解码（网络图片兜底路径） */
private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = computeSample(bounds.outWidth, bounds.outHeight, targetPx)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }
}

/** 2 的幂采样率：让解码尺寸尽量接近但不低于 targetPx */
private fun computeSample(w: Int, h: Int, targetPx: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var sample = 1
    while (w / (sample * 2) >= targetPx && h / (sample * 2) >= targetPx) sample *= 2
    return sample
}

private val thumbClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
}
