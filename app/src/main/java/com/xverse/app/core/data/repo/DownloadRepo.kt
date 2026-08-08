package com.xverse.app.core.data.repo

import com.xverse.app.core.data.db.DownloadDao
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务仓库。
 */
class DownloadRepo(private val dao: DownloadDao) {

    fun observeAll(): Flow<List<DownloadTask>> = dao.observeAll()

    /** 仅 app 媒体下载（过滤扩展 GM_download 直存任务） */
    fun observeAppMedia(): Flow<List<DownloadTask>> = dao.observeAppMedia()

    suspend fun findById(id: Long): DownloadTask? = dao.findById(id)

    /** 未完成任务的候选文件名集合（多图连点入队时按任务表去重） */
    suspend fun pendingFileNames(prefix: String): Set<String> = dao.pendingFileNames(prefix).toSet()

    suspend fun insert(task: DownloadTask): Long = dao.insert(task)

    suspend fun update(task: DownloadTask) = dao.update(task)

    suspend fun setStatus(id: Long, status: DownloadStatus) = dao.setStatus(id, status)

    /** 只更新缩略图路径（详见 DAO 注释，避免整行覆盖竞态） */
    suspend fun setThumbPath(id: Long, thumbPath: String) = dao.setThumbPath(id, thumbPath)

    suspend fun delete(task: DownloadTask) = dao.delete(task)
}
