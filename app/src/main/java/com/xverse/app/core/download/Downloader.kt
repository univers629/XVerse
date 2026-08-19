package com.xverse.app.core.download

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.core.data.repo.DownloadRepo
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 单文件下载器：OkHttp + Range 断点续传。
 * 进度回调、暂停/恢复（记录已下字节）、完成校验（大小）。
 *
 * 目标位置：任务 dirPath 为 `content://` 时走 SAF [DocumentFile] 写入，
 * 否则写普通文件路径。暂停：读循环检查暂停标志，中断后落盘字节数留待续传。
 */
class Downloader(
    private val context: Context,
    private val repo: DownloadRepo,
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // 强制 HTTP/1.1：twimg 的 ext_tw_video CDN 边缘会对 OkHttp 的 HTTP/2
            // 连接发 GOAWAY 关闭（ConnectionShutdownException: Connection closed），
            // 导致视频下载失败；图片/GIF 的边缘节点不关所以一直正常。curl 不受影响。
            // HTTP/1.1 对单文件下载完全够用，且规避该 CDN 兼容性问题。
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    /** 被请求暂停的任务集合 */
    private val paused = ConcurrentHashMap.newKeySet<Long>()

    /** 请求暂停任务（读循环会尽快中断） */
    fun requestPause(taskId: Long) {
        paused.add(taskId)
    }

    /** 恢复/重试前清除尚未被旧 Worker 消费的暂停信号。 */
    fun clearPause(taskId: Long) {
        paused.remove(taskId)
    }

    /**
     * 执行下载（挂起直到完成/失败/暂停）。task 需已落库。
     *
     * 每次落库前先从 DB 取最新快照再 copy：入队时缩略图是异步落盘的（scope.launch），
     * 若下载在缩略图写完前就完成，用最初传入的旧快照会覆盖掉新写入的 thumbPath。
     * 始终以 DB 最新状态为基准合并本次变更，避免丢字段。
     */
    suspend fun download(task: DownloadTask, onProgress: suspend (Int) -> Unit): DownloadStatus = withContext(Dispatchers.IO) {
        LogStore.log(LogCategory.DOWNLOAD, "Start download: ${task.fileName}")
        val target = resolveTarget(task)
        /** 读取 DB 最新快照，未找到则退回传入值 */
        suspend fun latest(): DownloadTask = repo.findById(task.id) ?: task
        try {
            // 断点续传：已有目标文件则从已下字节起
            var offset = target.size()
            val cookie = com.xverse.app.core.auth.CookieManagerReader.cookiesForFromBackground(task.mediaUrl)

            val req = Request.Builder()
                .url(task.mediaUrl)
                .header("User-Agent", CHROME_MOBILE_UA)
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 416：Range 超出资源大小，说明本地文件已 ≥ 服务器内容，视为完整
                    if (resp.code == 416 && offset > 0) {
                        repo.update(latest().copy(
                            downloadedBytes = offset,
                            totalBytes = offset,
                            progress = 100,
                            status = DownloadStatus.DONE,
                            finishedAt = System.currentTimeMillis(),
                            contentUri = resolveContentUri(target, task),
                        ))
                        LogStore.log(LogCategory.DOWNLOAD, "File already complete (416), skipping: ${task.fileName}")
                        return@withContext DownloadStatus.DONE
                    }
                    repo.update(latest().copy(status = DownloadStatus.FAILED, error = "HTTP ${resp.code}"))
                    return@withContext DownloadStatus.FAILED
                }
                val body = resp.body
                // 服务器忽略 Range 返回 200：从头重写（SAF 追加模式下忽略截断）
                if (resp.code == 200) offset = 0
                val total = if (resp.code == 206) {
                    // Content-Range: bytes 0-100/200
                    val cr = resp.header("Content-Range") ?: ""
                    cr.substringAfter('/').toLongOrNull() ?: (offset + body.contentLength())
                } else {
                    body.contentLength()
                }
                val out = target.openOutput(append = offset > 0)
                var done = offset
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                try {
                    while (true) {
                        // 暂停信号检查
                        if (paused.remove(task.id)) {
                            throw PausedException()
                        }
                        val n = body.source().read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 进度每 ~200ms 上报一次
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200 && total > 0) {
                            lastReport = now
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            repo.update(latest().copy(progress = pct, downloadedBytes = done, totalBytes = total, status = DownloadStatus.RUNNING))
                            onProgress(pct)
                        }
                    }
                    out.flush()
                } catch (e: PausedException) {
                    // 用户暂停：保存进度，留待续传
                    target.abort()
                    repo.update(latest().copy(
                        downloadedBytes = done,
                        totalBytes = total,
                        progress = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else 0,
                        status = DownloadStatus.PAUSED,
                    ))
                    return@withContext DownloadStatus.PAUSED
                } catch (e: IOException) {
                    // 网络中断：按失败处理（保留字节供重试）
                    target.abort()
                    LogStore.log(LogCategory.DOWNLOAD, "Download interrupted: ${task.fileName} (${e.message})")
                    repo.update(latest().copy(
                        downloadedBytes = done,
                        totalBytes = total,
                        status = DownloadStatus.FAILED,
                        error = "Network interrupted: ${e.message ?: ""}",
                    ))
                    return@withContext DownloadStatus.FAILED
                } finally {
                    try { out.close() } catch (_: Exception) {}
                }
                // 完成：MediaStore 需清除 IS_PENDING 让系统相册立即可见
                target.finalizeWrite()
                // 完成校验
                val finalSize = target.size()
                repo.update(latest().copy(
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    progress = 100,
                    status = DownloadStatus.DONE,
                    finishedAt = System.currentTimeMillis(),
                    contentUri = resolveContentUri(target, task),
                ))
                LogStore.log(LogCategory.DOWNLOAD, "Download completed: ${task.fileName} (${finalSize / 1024} KB)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogStore.error("Download failed: ${task.fileName}", e)
            repo.update(latest().copy(status = DownloadStatus.FAILED, error = e.message ?: "Download failed"))
            return@withContext DownloadStatus.FAILED
        }
        DownloadStatus.DONE
    }

    /** 计算可被系统查看器读取的 content URI：默认目录 → FileProvider；SAF → 文档 URI；MediaStore → 自身 URI */
    private fun resolveContentUri(target: Target, task: DownloadTask): String {
        return try {
            target.readUri(context)?.toString() ?: ""
        } catch (e: Exception) {
            LogStore.log(LogCategory.DOWNLOAD, "Failed to resolve view URI: ${task.fileName} (${e.message})")
            ""
        }
    }

    private suspend fun resolveTarget(task: DownloadTask): Target {
        // 用户已选目录时走 SAF；未配置时回退系统相册（Pictures|Movies/XVerse）。
        if (task.dirPath.startsWith("content://")) {
            return DocumentTarget(context, task.dirPath.toUri(), task.fileName)
        }
        return MediaStoreTarget(context, task.fileName, mediaType(task.fileName))
    }

    /** 由文件名后缀推断媒体类型（决定 MediaStore 集合：图片→Pictures，视频→Movies） */
    private fun mediaType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v" -> "video"
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "avif" -> "image"
            else -> "file"
        }
    }

    /** 用户暂停中断信号 */
    private class PausedException : IOException("paused by user")

    /** 下载目标抽象：File 路径 / SAF 文档 / MediaStore */
    private interface Target {
        /** 已存在字节数（断点续传起点） */
        fun size(): Long
        /** 打开追加写输出流 */
        fun openOutput(append: Boolean): OutputStream
        /** 完成后的可读位置：FileTarget→文件，DocumentTarget→文档 URI，MediaStoreTarget→插入的媒体 URI */
        fun readUri(context: Context): android.net.Uri?
        /** 写完后收尾：MediaStore 清除 IS_PENDING，让系统相册立即可见 */
        fun finalizeWrite() {}
        /** 中断/失败时清理：MediaStore 删除未完成的悬挂条目 */
        fun abort() {}
    }

    private class DocumentTarget(context: Context, treeUri: Uri, fileName: String) : Target {
        private val resolver = context.contentResolver
        private val doc: DocumentFile? = run {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@run null
            tree.findFile(fileName) ?: tree.createFile("application/octet-stream", fileName)
        }

        override fun size(): Long {
            val uri = doc?.uri ?: return 0
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
            return 0
        }

        override fun openOutput(append: Boolean): OutputStream {
            val uri = doc?.uri ?: throw IOException("SAF target unavailable")
            return resolver.openOutputStream(uri, if (append) "wa" else "wt")
                ?: throw IOException("Cannot open output stream")
        }

        override fun readUri(context: Context): android.net.Uri? = doc?.uri
    }

    /**
     * MediaStore 目标：图片→Pictures/XVerse，视频→Movies/XVerse。
     * 不请求权限，系统相册扫描即见；文件管理器也可访问。
     * 无续传（MediaStore 不能可靠断点），暂停后重试即从头写。
     */
    private class MediaStoreTarget(
        context: Context,
        fileName: String,
        mediaType: String,
    ) : Target {
        private val resolver = context.contentResolver
        private val isVideo = mediaType == "video"
        private val isImage = mediaType == "image"
        private val displayName = fileName
        private val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        private var insertedUri: android.net.Uri? = null
        private val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                when {
                    isVideo -> "Movies/XVerse"
                    isImage -> "Pictures/XVerse"
                    else -> "Download/XVerse"
                },
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        private val collectionUri: android.net.Uri = when {
            isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        override fun size(): Long {
            val uri = insertedUri ?: return 0
            // MediaStore 无断点续传，但完成校验需真实大小（写完后查询）
            try {
                val projection = arrayOf(OpenableColumns.SIZE)
                resolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                    }
                }
            } catch (_: Exception) {}
            return 0
        }

        override fun openOutput(append: Boolean): OutputStream {
            // MediaStore 无法可靠续传：始终从头部新建条目
            val uri = resolver.insert(collectionUri, values)
                ?: throw IOException("MediaStore insert failed")
            insertedUri = uri
            return resolver.openOutputStream(uri, "w")
                ?: throw IOException("Cannot open output stream")
        }

        override fun readUri(context: Context): android.net.Uri? = insertedUri

        override fun finalizeWrite() {
            val uri = insertedUri ?: return
            try {
                val cv = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, cv, null, null)
            } catch (_: Exception) {}
        }

        override fun abort() {
            val uri = insertedUri ?: return
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.6613.99 Mobile Safari/537.36"
    }
}
