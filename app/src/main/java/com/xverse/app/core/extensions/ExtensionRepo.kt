package com.xverse.app.core.extensions

import kotlinx.coroutines.flow.Flow

/**
 * 扩展仓库：扩展元数据的观察与启停/删除。
 * 仿 FilterRepo 模式，纯 Room 薄封装。
 */
class ExtensionRepo(private val dao: ExtensionDao) {

    fun observeAll(): Flow<List<ExtensionEntity>> = dao.observeAll()

    fun observeEnabled(): Flow<List<ExtensionEntity>> = dao.observeEnabled()

    suspend fun getEnabled(): List<ExtensionEntity> = dao.getEnabled()

    suspend fun getAll(): List<ExtensionEntity> = dao.getAll()

    suspend fun getById(id: String): ExtensionEntity? = dao.getById(id)

    suspend fun insert(ext: ExtensionEntity) = dao.insert(ext)

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun setSource(id: String, source: String) = dao.setSource(id, source)

    suspend fun delete(id: String) = dao.delete(id)
}
