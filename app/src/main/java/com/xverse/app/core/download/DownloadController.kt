package com.xverse.app.core.download

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.core.data.repo.DownloadRepo
import com.xverse.app.core.data.repo.SettingsRepo
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 下载控制器：解析媒体 → 创建任务 → WorkManager 串行调度执行。
 * 状态机流转见 [DownloadTask]；暂停/恢复/重试均落在状态 + Worker 入队。
 */
class DownloadController(
    private val context: Context,
    private val repo: DownloadRepo,
    private val settings: SettingsRepo,
    /** 共享 MediaParser 单例（GraphQL 缓存写入同一实例，解析时才能命中） */
    private val parser: MediaParser,
    /** 与 WorkManager Worker 共享实例，确保暂停信号作用于正在下载的任务。 */
    private val downloader: Downloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化「文件名保留 + 落库」临界区：多图连点并发入队时保证文件名互不冲突 */
    private val enqueueMutex = Mutex()

    /** 解析推文媒体列表 */
    suspend fun parseTweet(tweetUrl: String): List<MediaItem> {
        return parser.parse(tweetUrl)
    }

    /** 生成文件名（%username%_%tweetid% 模板 + 清晰度后缀） */
    suspend fun buildFileName(tweetUrl: String, media: MediaItem): String {
        val template = settings.downloadFileTemplate.first().ifBlank { "%username%_%tweetid%" }
        val parsed = com.xverse.app.core.data.repo.HistoryRepo.parseTweetUrl(tweetUrl)
        val name = template
            .replace("%username%", parsed?.first ?: "user")
            .replace("%tweetid%", parsed?.second ?: "tweet")
            .replace("%quality%", media.quality.ifBlank { "orig" })
            .replace("%ext%", media.extension)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val ext = if (name.endsWith(".${media.extension}")) "" else ".${media.extension}"
        return "$name$ext"
    }

    /** 创建下载任务并入队。返回 true 表示已入队成功；false 表示入队失败（已落日志） */
    suspend fun enqueue(tweetUrl: String, media: MediaItem): Boolean {
        return try {
            // 临界区加锁：文件名唯一性在并发入队时由 Mutex + 任务表共同保证
            val (dirPath, finalName) = enqueueMutex.withLock {
                val dir = configuredDirFor(media.mediaType, media.extension)
                val base = buildFileName(tweetUrl, media)
                dir to uniquifyForDirectory(dir, base)
            }
            val task = DownloadTask(
                tweetUrl = tweetUrl,
                mediaUrl = media.url,
                fileName = finalName,
                dirPath = dirPath,
                format = media.extension,
                mediaType = media.mediaType,
                resolution = media.quality,
                totalBytes = media.size,
                status = DownloadStatus.QUEUED,
            )
            val id = repo.insert(task)
            scheduleWorker(id, task.copy(id = id))
            LogStore.log(LogCategory.DOWNLOAD, "Queued: $finalName (${media.quality})")
            // 缩略图落盘走 IO 线程，避免阻塞入队（WorkManager 已启动，不等待）。
            // 落盘后只定向更新 thumbPath 字段，不用入队快照覆盖整行——
            // 下载可能在缩略图写完前就完成（status=DONE、contentUri 已落库），整行覆盖会冲掉这些字段。
            if (media.thumbnailUrl.isNotBlank()) {
                scope.launch {
                    val thumb = com.xverse.app.core.util.ThumbCache.persist(
                        context, "download-$id", media.thumbnailUrl
                    )
                    if (thumb.isNotBlank()) {
                        repo.setThumbPath(id, thumb)
                    }
                }
            }
            true
        } catch (e: Exception) {
            LogStore.error("Failed to create download task", e)
            false
        }
    }

    /**
     * 字节直存（扩展 GM_download BLOB base64 / 分块组装通道）：
     * 下载已由扩展完成（字节在手里），这里经 MediaStore 落公共目录 + 登记任务（状态 DONE）。
     * 目录：图片 → Pictures/XVerse，视频 → Movies/XVerse，其余（zip 等）→ Download/XVerse，
     * 相册/文件管理器立即可见。文件名直接采用扩展建议值（如 X-Vault 的 {nick}-{date}-{id}.jpg），
     * 冲突时追加序号。
     */
    suspend fun enqueueDownloadBytes(bytes: ByteArray, name: String, media: MediaItem): Boolean {
        return try {
            val (dirPath, finalName) = enqueueMutex.withLock {
                val dir = configuredDirFor(media.mediaType, name.substringAfterLast('.', ""))
                dir to uniquifyForDirectory(dir, name)
            }
            val uri = writeBytesToConfiguredTarget(dirPath, finalName, bytes)
            if (uri == null) {
                LogStore.log(LogCategory.DOWNLOAD, "Extension direct save failed: $finalName")
                return false
            }
            val displayPath = if (dirPath.isBlank()) mediaStoreRelPath(finalName) else dirPath
            val task = DownloadTask(
                tweetUrl = "",
                mediaUrl = media.url,
                fileName = finalName,
                dirPath = displayPath,
                format = media.extension,
                resolution = media.quality,
                totalBytes = bytes.size.toLong(),
                status = DownloadStatus.DONE,
                contentUri = uri.toString(),
            )
            repo.insert(task)
            LogStore.log(LogCategory.DOWNLOAD, "Extension direct save: $finalName (${bytes.size} B -> $displayPath)")
            true
        } catch (e: Exception) {
            LogStore.error("Failed to directly save extension bytes", e)
            false
        }
    }

    /** 文件名 → MediaStore 相对目录（图片/视频/其余分别进公共子目录，相册可见） */
    private fun mediaStoreRelPath(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in setOf("mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v") -> "Movies/XVerse"
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp") -> "Pictures/XVerse"
            else -> "Download/XVerse"
        }
    }

    /** 经 MediaStore 写字节到公共目录，返回 content URI（失败 null） */
    private suspend fun writeBytesToMediaStore(fileName: String, bytes: ByteArray): android.net.Uri? =
        withContext(Dispatchers.IO) {
            val relPath = mediaStoreRelPath(fileName)
            val isVideo = relPath.startsWith("Movies")
            val isImage = relPath.startsWith("Pictures")
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val collection = when {
                isVideo -> android.provider.MediaStore.Video.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                isImage -> android.provider.MediaStore.Images.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                else -> android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            try {
                val u = context.contentResolver.insert(collection, values) ?: return@withContext null
                try {
                    context.contentResolver.openOutputStream(u, "w")?.use { out -> out.write(bytes) }
                } catch (e: Exception) {
                    context.contentResolver.delete(u, null, null)
                    return@withContext null
                }
                u
            } catch (e: Exception) {
                LogStore.error("Failed to write to MediaStore: $fileName", e)
                null
            }
        }

    /** 将扩展下载写入用户选中的 SAF 目录；未配置时沿用 MediaStore。 */
    private suspend fun writeBytesToConfiguredTarget(dirPath: String, fileName: String, bytes: ByteArray): Uri? =
        withContext(Dispatchers.IO) {
            if (!dirPath.startsWith("content://")) return@withContext writeBytesToMediaStore(fileName, bytes)
            try {
                val tree = DocumentFile.fromTreeUri(context, dirPath.toUri()) ?: return@withContext null
                val doc = tree.createFile(mimeFor(fileName), fileName) ?: return@withContext null
                context.contentResolver.openOutputStream(doc.uri, "w")?.use { it.write(bytes) } ?: run {
                    doc.delete()
                    return@withContext null
                }
                doc.uri
            } catch (e: Exception) {
                LogStore.error("Failed to write to SAF: $fileName", e)
                null
            }
        }

    /** 媒体类型分别使用图片、GIF、视频的目录；为空时由下载器回退公共相册目录。 */
    private suspend fun configuredDirFor(mediaType: String, extension: String): String {
        val type = mediaType.lowercase()
        return when {
            type == "gif" || extension.equals("gif", ignoreCase = true) -> settings.downloadGifDir.first()
            type == "video" || extension.lowercase() in setOf("mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v") -> settings.downloadVideoDir.first()
            else -> settings.downloadImageDir.first()
        }
    }

    /**
     * URL 直链下载（扩展 GM_download http/https 通道）：
     * 只登记任务并交给 WorkManager 流式下载，不把整个响应体堆在内存中。
     */
    suspend fun enqueueDownloadUrl(url: String, name: String, media: MediaItem): Boolean {
        return try {
            val extension = name.substringAfterLast('.', "")
                .lowercase()
                .ifBlank { media.extension.ifBlank { "bin" } }
            val mediaType = media.mediaType.takeIf { it in setOf("photo", "image", "gif", "video") }
                ?: mediaTypeForExtension(extension)
            val (dirPath, finalName) = enqueueMutex.withLock {
                val dir = configuredDirFor(mediaType, extension)
                dir to uniquifyForDirectory(dir, name)
            }
            val task = DownloadTask(
                tweetUrl = "",
                mediaUrl = url,
                fileName = finalName,
                dirPath = dirPath,
                format = extension,
                mediaType = mediaType,
                resolution = media.quality,
                totalBytes = media.size,
                status = DownloadStatus.QUEUED,
            )
            val id = repo.insert(task)
            scheduleWorker(id, task.copy(id = id))
            LogStore.log(LogCategory.DOWNLOAD, "Extension URL queued: $finalName")
            true
        } catch (e: Exception) {
            LogStore.error("Failed to download extension direct URL", e)
            false
        }
    }

    /** 暂停：置位 + 状态落库，读循环尽快中断 */
    fun pause(id: Long) {
        downloader.requestPause(id)
        scope.launch { repo.setStatus(id, DownloadStatus.PAUSED) }
    }

    /**
     * 恢复/重试：状态置回 QUEUED 并重新入队。
     * 支持 PAUSED（续传）、FAILED（重试）、以及卡在 QUEUED 未真正启动的任务
     * （前台服务启动异常/约束不满足导致 Worker 未跑，点此强制重新入队）。
     */
    fun resume(id: Long) {
        scope.launch {
            val task = repo.findById(id) ?: return@launch
            if (task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.PAUSED ||
                task.status == DownloadStatus.FAILED
            ) {
                downloader.clearPause(id)
                repo.setStatus(id, DownloadStatus.QUEUED)
                scheduleWorker(id, task.copy(status = DownloadStatus.QUEUED))
            }
        }
    }

    /** 重试：等价恢复 */
    fun retry(id: Long) = resume(id)

    /** 删除：取消任务 + 删除本地文件（MediaStore 条目 / SAF 文档 / 普通路径） */
    fun delete(id: Long) {
        scope.launch {
            val task = repo.findById(id) ?: return@launch
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
            downloader.requestPause(id)
            repo.delete(task)
            try {
                // 优先 contentUri（MediaStore 下载完成的文件在此）；SAF/普通路径回退 dirPath
                if (task.contentUri.isNotBlank()) {
                    context.contentResolver.delete(task.contentUri.toUri(), null, null)
                } else {
                    val dirPath = task.dirPath
                    if (dirPath.isNotBlank()) {
                        if (dirPath.startsWith("content://")) {
                            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, dirPath.toUri())
                            tree?.findFile(task.fileName)?.delete()
                        } else {
                            File(dirPath, task.fileName).delete()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 打开已下载文件：解析可读 content URI → 系统相册/播放器。
     * 返回 null 表示成功启动；否则返回用户可读的失败原因文案。
     * 文件未完成/不存在 → 也返回提示（不再静默无响应）。
     */
    suspend fun open(id: Long): String? {
        val task = repo.findById(id) ?: return com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.download_err_not_found)
        // URI 解析可能在 IO 上做 SAF→MediaStore 复制，放 IO 线程；Intent 启动切回 Main
        val uri = withContext(Dispatchers.IO) { resolveReadableUri(task) }
        return withContext(Dispatchers.Main) {
            if (uri == null) {
                when {
                    task.status != DownloadStatus.DONE ->
                        com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.download_err_not_finished)
                    task.contentUri.isBlank() && task.dirPath.isBlank() ->
                        com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.download_err_location_unavailable)
                    else -> com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.download_err_file_not_found)
                }
            } else {
                val mime = mimeFor(task.fileName)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    null
                } catch (e: Exception) {
                    LogStore.error("Failed to open downloaded file", e)
                    com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.download_err_no_app_to_open)
                }
            }
        }
    }

    /**
     * 计算可读 content URI。优先用下载完成时落库的 [DownloadTask.contentUri]；
     * 若为空（老任务/路径失效）则现场解析：默认目录 → FileProvider，SAF → 复制进 MediaStore（返回新 URI）。
     * SAF 文档 URI 外部应用无读取权限（会抛 SecurityException），故复制到相册集合让相册可读。
     */
    private suspend fun resolveReadableUri(task: DownloadTask): android.net.Uri? {
        val stored = task.contentUri.takeIf { it.isNotBlank() }
        if (stored != null) return stored.toUri()
        val dirPath = task.dirPath
        if (dirPath.isBlank()) return null
        return try {
            if (dirPath.startsWith("content://")) {
                importSafToMediaStore(task)
            } else {
                val file = File(dirPath, task.fileName)
                if (file.exists() && file.length() > 0) {
                    androidx.core.content.FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file
                    )
                } else null
            }
        } catch (e: Exception) {
            LogStore.error("Failed to resolve readable URI", e)
            null
        }
    }

    /** 把 SAF 文档复制进 MediaStore（相册立即可见），返回新的媒体 URI；失败返回 null */
    private suspend fun importSafToMediaStore(task: DownloadTask): android.net.Uri? =
        withContext(Dispatchers.IO) {
            try {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    context, task.dirPath.toUri()
                ) ?: return@withContext null
                val doc = tree.findFile(task.fileName) ?: return@withContext null
                val input = context.contentResolver.openInputStream(doc.uri)
                    ?: return@withContext null
                val isVideo = task.fileName.substringAfterLast('.', "").lowercase() in
                    setOf("mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v")
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                    put(
                        android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        if (isVideo) "video/mp4" else "image/jpeg",
                    )
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "Movies/XVerse" else "Pictures/XVerse",
                    )
                }
                val collection = if (isVideo) {
                    android.provider.MediaStore.Video.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                } else {
                    android.provider.MediaStore.Images.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                }
                val uri = context.contentResolver.insert(collection, values)
                    ?: return@withContext null
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        input.use { input -> input.copyTo(output, 64 * 1024) }
                    }
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext null
                }
                // 把新 URI 落库，后续打开直接走 contentUri
                repo.update(task.copy(contentUri = uri.toString()))
                uri
            } catch (e: Exception) {
                LogStore.error("Failed to import SAF to gallery", e)
                null
            }
        }

    /** 由文件名后缀推断 MIME（视频/图片/音频/其余通用） */
    private fun mimeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v" -> "video/mp4"
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "image/*"
            "mp3", "m4a", "aac", "wav", "ogg", "flac" -> "audio/*"
            else -> "application/octet-stream"
        }
    }

    private fun mediaTypeForExtension(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "bmp", "avif" -> "photo"
        "gif" -> "gif"
        "mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v" -> "video"
        else -> "file"
    }

    /** 提交下载 Worker（unique work，避免重复入队） */
    private fun scheduleWorker(id: Long, task: DownloadTask) {
        val data: Data = workDataOf(DownloadWorker.KEY_TASK_ID to id)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun uniqueName(id: Long) = "xverse-download-$id"

    /** 冲突改名：磁盘已有文件 / 未完成任务表中的同名任务，追加序号避让 */
    private suspend fun uniquify(target: File): String {
        val dot = target.name.lastIndexOf('.')
        val stem = if (dot > 0) target.name.substring(0, dot) else target.name
        val ext = if (dot > 0) target.name.substring(dot) else ""
        // 未完成任务名（多图连点场景，文件尚未创建，只能查任务表）
        val pending = repo.pendingFileNames(stem)
        if (!target.exists() && stem !in pending && "${stem}$ext" !in pending) return target.name
        var counter = 1
        var candidate = "${stem}_$counter$ext"
        while (File(target.parentFile, candidate).exists() || candidate in pending) {
            counter++
            candidate = "${stem}_$counter$ext"
        }
        return candidate
    }

    /** SAF 目录和默认目录均使用同一套冲突改名规则。 */
    private suspend fun uniquifyForDirectory(dirPath: String, fileName: String): String {
        if (!dirPath.startsWith("content://")) {
            return if (dirPath.isBlank()) {
                uniquifyPendingName(fileName)
            } else {
                uniquify(File(dirPath, fileName))
            }
        }
        val tree = DocumentFile.fromTreeUri(context, dirPath.toUri())
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val pending = repo.pendingFileNames(stem)
        var candidate = fileName
        var counter = 1
        while (tree?.findFile(candidate) != null || candidate in pending) {
            candidate = "${stem}_${counter++}$ext"
        }
        return candidate
    }

    /** MediaStore 默认目录无法用 File 查询，至少与任务表中的已保留名称去重。 */
    private suspend fun uniquifyPendingName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val pending = repo.pendingFileNames(stem)
        var candidate = fileName
        var counter = 1
        while (candidate in pending) candidate = "${stem}_${counter++}$ext"
        return candidate
    }
}
