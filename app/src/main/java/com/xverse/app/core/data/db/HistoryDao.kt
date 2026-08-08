package com.xverse.app.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    /** 全部记录，按访问时间倒序 */
    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun observeAll(): Flow<List<HistoryRecord>>

    @Query("SELECT * FROM history WHERE textPreview LIKE :q OR username LIKE :q OR tweetId LIKE :q ORDER BY visitedAt DESC")
    fun search(q: String): Flow<List<HistoryRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: HistoryRecord): Long

    @Delete
    suspend fun delete(record: HistoryRecord)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    /** 清理超过上限的记录（保留最新 N 条） */
    @Query("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimTo(keep: Int): Int

    /** 清理历史遗留：同一 tweetId 的小写 mediaviewer 孤儿记录（空正文、无有效媒体缩略图） */
    @Query("""
        DELETE FROM history WHERE id IN (
            SELECT h.id FROM history h
            WHERE lower(h.url) LIKE '%/mediaviewer%'
              AND h.textPreview = ''
              AND h.mediaUrl NOT LIKE '%/media/%'
            GROUP BY h.tweetId
            HAVING COUNT(*) = 1
        )
    """)
    suspend fun deleteOrphanMediaviewer(): Int
}
