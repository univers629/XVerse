package com.xverse.app.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.di.ServiceLocator

/**
 * 下载 Worker：WorkManager 串行队列 + 前台通知。
 * 状态机：QUEUED → RUNNING →（PAUSED ↔ RUNNING）→ DONE / FAILED
 *
 * 暂停语义：用户从下载中心点「暂停」→ 状态置 PAUSED。
 * [Downloader] 在读取循环内轮询状态，检测到 PAUSED 即中断网络读取、
 * 落盘已下字节后以 PAUSED 结束；恢复时状态置回 QUEUED，由调度器重新入队续传。
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val locator: ServiceLocator get() = ServiceLocator.from(applicationContext)
    private val taskId: Long = inputData.getLong(KEY_TASK_ID, 0)

    override suspend fun doWork(): Result {
        val repo = locator.downloadRepo
        val task = repo.findById(taskId) ?: run {
            LogStore.log(LogCategory.DOWNLOAD, "任务不存在，跳过: $taskId")
            return Result.success()
        }
        // 已被暂停 / 删除：直接结束，不启动前台
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.DONE) {
            return Result.success()
        }

        // 提升为前台服务 + 进度通知
        setForeground(getForegroundInfo())

        repo.setStatus(task.id, DownloadStatus.RUNNING)
        LogStore.log(LogCategory.DOWNLOAD, "Worker 启动: ${task.fileName}")

        val status = locator.downloader.download(task) { pct ->
            setForeground(getForegroundInfo())
        }

        return when (status) {
            DownloadStatus.DONE -> {
                locator.downloadNotifier.finish(applicationContext, taskId.toInt(), task.copy(status = DownloadStatus.DONE))
                toast("下载完成：${task.fileName}")
                Result.success()
            }
            DownloadStatus.PAUSED -> {
                // 用户暂停：结束当前 Worker，恢复时重新入队
                locator.downloadNotifier.cancel(applicationContext, taskId.toInt())
                Result.success()
            }
            DownloadStatus.FAILED -> {
                locator.downloadNotifier.finish(applicationContext, taskId.toInt(), task.copy(status = DownloadStatus.FAILED))
                toast("下载失败：${task.fileName}")
                Result.failure()
            }
            else -> Result.success()
        }
    }

    /** 主线程 Toast（WorkManager 后台线程，经 UiExecutor 切回） */
    private fun toast(msg: String) {
        com.xverse.app.core.util.UiExecutor.post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 前台服务 + 进度通知
     *  targetSdk 34+ 强制要求运行时指定 FGS 类型（dataSync），否则抛
     *  InvalidForegroundServiceTypeException 闪退。minSdk 31，类型参数始终生效。 */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val task = locator.downloadRepo.findById(taskId)
            ?: DownloadTask(tweetUrl = "", mediaUrl = "", fileName = "准备中…", status = DownloadStatus.QUEUED)
        val n = DownloadNotifier.foreground(applicationContext, taskId.toInt(), task)
        return ForegroundInfo(
            taskId.toInt(),
            n,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
