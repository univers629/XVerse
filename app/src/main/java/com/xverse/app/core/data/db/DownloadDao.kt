package com.xverse.app.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTask>>

    /** 仅 app 媒体下载（tweetUrl 非空）。扩展 GM_download 直存任务 tweetUrl 为空，不属下载中心 */
    @Query("SELECT * FROM downloads WHERE tweetUrl != '' ORDER BY createdAt DESC")
    fun observeAppMedia(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DownloadTask?

    /** 未完成任务的候选文件名集合（多图连点入队时文件名尚未落盘，按任务表去重） */
    @Query("SELECT fileName FROM downloads WHERE fileName LIKE :prefix || '%' AND status IN ('QUEUED', 'RUNNING', 'PAUSED')")
    suspend fun pendingFileNames(prefix: String): List<String>

    @Insert
    suspend fun insert(task: DownloadTask): Long

    @Update
    suspend fun update(task: DownloadTask)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus)

    /** 只更新缩略图路径字段，避免用入队快照覆盖下载完成的 status/contentUri（缩略图异步落盘晚于下载完成时） */
    @Query("UPDATE downloads SET thumbPath = :thumbPath WHERE id = :id")
    suspend fun setThumbPath(id: Long, thumbPath: String)

    @Delete
    suspend fun delete(task: DownloadTask)
}
