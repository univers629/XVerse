package com.xverse.app.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 历史记录实体。
 * 触发点：停留推文 URL 超 3s、点击详情/媒体时写入。
 */
@Entity(
    tableName = "history",
    indices = [Index("visitedAt"), Index("url", unique = true)],
)
data class HistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val tweetId: String,
    val username: String = "",
    /** 展示名（如 "张三"），空则回退 @username */
    val displayName: String = "",
    /** 推文正文摘要（经 JS 从 tweetText 提取） */
    val textPreview: String = "",
    val mediaType: String = "",
    /** 媒体缩略图直链（pbs.twimg.com/media/...），供历史列表展示 */
    val mediaUrl: String = "",
    /** 本地缩略图路径（写历史时从 mediaUrl 缩略图落盘，历史列表离线显示） */
    val thumbPath: String = "",
    val visitedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
)
