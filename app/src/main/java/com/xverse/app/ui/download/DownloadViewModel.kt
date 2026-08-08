package com.xverse.app.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.AppInstance
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 下载中心 ViewModel：任务列表 + 操作。
 */
class DownloadViewModel : ViewModel() {

    private val locator get() = AppInstance.locator
    val controller get() = locator.downloadController

    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    init {
        viewModelScope.launch {
            // 下载中心只显示 app 下载的推文媒体；扩展 GM_download 的直存/直链任务不进列表
            locator.downloadRepo.observeAppMedia().collect { list ->
                tasks.value = list
            }
        }
    }

    fun pause(id: Long) = controller.pause(id)
    fun resume(id: Long) = controller.resume(id)
    fun retry(id: Long) = controller.retry(id)
    fun delete(id: Long) = controller.delete(id)

    /** 打开已下载文件：解析可读 content URI → 系统相册/播放器。返回可读提示文案，null 表示成功 */
    suspend fun open(id: Long): String? = controller.open(id)

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DownloadViewModel() as T
            }
        }
    }
}

/** 状态文案 */
fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.DONE -> "已完成"
    DownloadStatus.FAILED -> "失败"
}
