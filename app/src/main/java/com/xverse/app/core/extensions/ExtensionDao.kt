package com.xverse.app.core.extensions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY installedAt ASC")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE enabled = 1")
    fun observeEnabled(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE enabled = 1")
    suspend fun getEnabled(): List<ExtensionEntity>

    @Query("SELECT * FROM extensions WHERE id = :id")
    suspend fun getById(id: String): ExtensionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ext: ExtensionEntity)

    @Query("UPDATE extensions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun delete(id: String)
}
