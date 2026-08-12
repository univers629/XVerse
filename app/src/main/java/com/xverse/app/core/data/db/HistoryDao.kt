package com.xverse.app.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    /** 指定登录账户的记录，按访问时间倒序。 */
    @Query("SELECT * FROM history WHERE accountUsername = :accountUsername ORDER BY visitedAt DESC")
    fun observeForAccount(accountUsername: String): Flow<List<HistoryRecord>>

    @Query("SELECT * FROM history WHERE accountUsername = :accountUsername AND (textPreview LIKE :q OR username LIKE :q OR tweetId LIKE :q) ORDER BY visitedAt DESC")
    fun search(accountUsername: String, q: String): Flow<List<HistoryRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: HistoryRecord): Long

    @Delete
    suspend fun delete(record: HistoryRecord)

    /** 存量记录补类：GIF 与视频封面共用 video_thumb 路径，统一归入视频。 */
    @Query("""
        UPDATE history SET mediaType = CASE
            WHEN lower(mediaUrl) LIKE '%/media/%' THEN 'photo'
            WHEN lower(mediaUrl) LIKE '%video_thumb%'
              OR lower(mediaUrl) LIKE 'https://video.twimg.com/%' THEN 'video'
            ELSE mediaType
        END
        WHERE mediaType = '' AND mediaUrl != ''
    """)
    suspend fun backfillMediaTypes(): Int

    @Query("DELETE FROM history WHERE accountUsername = :accountUsername")
    suspend fun clearForAccount(accountUsername: String)

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT DISTINCT accountUsername FROM history")
    suspend fun accountUsernames(): List<String>

    /** 清理超过上限的记录（保留最新 N 条） */
    @Query("DELETE FROM history WHERE id IN (SELECT id FROM history WHERE accountUsername = :accountUsername ORDER BY visitedAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimTo(accountUsername: String, keep: Int): Int

    /** 清理历史遗留：同一 tweetId 的小写 mediaviewer 孤儿记录（空正文、无有效媒体缩略图） */
    @Query("""
        DELETE FROM history WHERE id IN (
            SELECT h.id FROM history h
            WHERE lower(h.url) LIKE '%/mediaviewer%'
              AND h.textPreview = ''
              AND h.mediaUrl NOT LIKE '%/media/%'
            GROUP BY h.accountUsername, h.tweetId
            HAVING COUNT(*) = 1
        )
    """)
    suspend fun deleteOrphanMediaviewer(): Int
}
