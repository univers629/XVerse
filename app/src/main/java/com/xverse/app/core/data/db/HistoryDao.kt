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

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    /** 清理超过上限的记录（保留最新 N 条） */
    @Query("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimTo(keep: Int): Int

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): HistoryRecord?
}
