package com.xverse.app.core.data.repo

import com.xverse.app.core.data.db.HistoryDao
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.Constants
import kotlinx.coroutines.flow.Flow

/**
 * 历史记录仓库：读写 + 容量/保留天数管理。
 */
class HistoryRepo(private val dao: HistoryDao) {

    fun observeForAccount(accountUsername: String): Flow<List<HistoryRecord>> =
        dao.observeForAccount(accountUsername)

    fun search(accountUsername: String, q: String): Flow<List<HistoryRecord>> =
        dao.search(accountUsername, "%$q%")

    suspend fun upsert(record: HistoryRecord) {
        dao.upsert(record)
    }

    suspend fun delete(record: HistoryRecord) = dao.delete(record)

    suspend fun backfillMediaTypes(): Int = dao.backfillMediaTypes()

    suspend fun clear(accountUsername: String) = dao.clearForAccount(accountUsername)

    /** 清理历史遗留：小写 mediaviewer 孤儿记录（见 HistoryDao.deleteOrphanMediaviewer） */
    suspend fun deleteOrphanMediaviewer(): Int = dao.deleteOrphanMediaviewer()

    /** 按保留天数清理 + 按上限截断 */
    suspend fun cleanup(maxKeepDays: Int = Constants.HISTORY_MAX_KEEP_DAYS, maxRecords: Int = Constants.HISTORY_MAX_RECORDS) {
        val cutoff = System.currentTimeMillis() - maxKeepDays * 86_400_000L
        val removedByAge = dao.deleteOlderThan(cutoff)
        val removedByLimit = dao.accountUsernames().sumOf { dao.trimTo(it, maxRecords) }
        if (removedByAge + removedByLimit > 0) {
            LogStore.log(LogCategory.HISTORY, "History cleanup: expired $removedByAge, limit exceeded $removedByLimit")
        }
    }

    /** 解析推文 URL，提取 tweetId / username */
    companion object {
        private val STATUS_RE = Regex("/([^/]+)/status/(\\d+)")
        fun parseTweetUrl(url: String): Pair<String, String>? {
            val m = STATUS_RE.find(url) ?: return null
            return m.groupValues[1] to m.groupValues[2]
        }
    }
}
