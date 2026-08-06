package com.xverse.app.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 下载任务状态机 */
enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, DONE, FAILED
}

/**
 * 下载任务实体。
 * status 流转：QUEUED → RUNNING → (PAUSED ↔ RUNNING) → DONE / FAILED
 */
@Entity(
    tableName = "downloads",
    indices = [Index("createdAt"), Index("status")],
)
data class DownloadTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tweetUrl: String,
    val mediaUrl: String,
    val fileName: String,
    val dirPath: String = "",
    val format: String = "mp4",
    val resolution: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    /** 本地缩略图路径（入队时从封面直链下载落盘，下载中心列表离线显示） */
    val thumbPath: String = "",
    /** 保存位置的可读 content URI（默认目录由 FileProvider 暴露；SAF 目录为文档 URI）。
     * 空串表示文件尚未落盘或位置暂不可用——打开系统查看器前需重新解析。 */
    val contentUri: String = "",
)
