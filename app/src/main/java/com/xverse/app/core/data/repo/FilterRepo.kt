package com.xverse.app.core.data.repo

import com.xverse.app.core.data.db.FilterRule
import com.xverse.app.core.data.db.FilterRuleDao
import kotlinx.coroutines.flow.Flow

/**
 * 过滤规则仓库。
 */
class FilterRepo(private val dao: FilterRuleDao) {

    fun observeAll(): Flow<List<FilterRule>> = dao.observeAll()

    suspend fun getEnabled(): List<FilterRule> = dao.getEnabled()

    suspend fun insert(rule: FilterRule): Long = dao.insert(rule)

    suspend fun insertAll(rules: List<FilterRule>) = dao.insertAll(rules)

    suspend fun update(rule: FilterRule) = dao.update(rule)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteBySource(source: String) = dao.deleteBySource(source)

    suspend fun getBySource(source: String): List<FilterRule> = dao.getBySource(source)
}
