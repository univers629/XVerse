package com.xverse.app.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterRuleDao {
    @Query("SELECT * FROM filter_rules ORDER BY builtin DESC, id ASC")
    fun observeAll(): Flow<List<FilterRule>>

    @Query("SELECT * FROM filter_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<FilterRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: FilterRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<FilterRule>)

    @Update
    suspend fun update(rule: FilterRule)

    @Query("UPDATE filter_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM filter_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM filter_rules WHERE builtin = 0 AND source = :source")
    suspend fun deleteBySource(source: String)

    @Query("SELECT * FROM filter_rules WHERE source = :source")
    suspend fun getBySource(source: String): List<FilterRule>
}
