package com.xverse.app.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 下载中心 ViewModel：任务列表 + 操作。
 */
class DownloadViewModel(private val locator: ServiceLocator) : ViewModel() {
    val controller get() = locator.downloadController

    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val query = MutableStateFlow("")
    val selectedMediaTypes = MutableStateFlow(DownloadMediaType.entries.toSet())

    val filteredTasks = combine(tasks, query, selectedMediaTypes) { all, text, types ->
        val keyword = text.trim()
        all.filter { task ->
            task.downloadMediaType() in types && (
                keyword.isBlank() || listOf(
                    task.fileName,
                    task.tweetUrl,
                    task.format,
                    task.resolution,
                    task.status.label(),
                ).any { it.contains(keyword, ignoreCase = true) }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun setQuery(value: String) { query.value = value }

    fun toggleMediaType(type: DownloadMediaType) {
        selectedMediaTypes.value = selectedMediaTypes.value.toMutableSet().apply {
            if (!add(type)) remove(type)
        }
    }

    /** 打开已下载文件：解析可读 content URI → 系统相册/播放器。返回可读提示文案，null 表示成功 */
    suspend fun open(id: Long): String? = controller.open(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                DownloadViewModel((app as com.xverse.app.XVerseApp).locator)
            }
        }
    }
}

enum class DownloadMediaType { IMAGE, GIF, VIDEO }

/** 兼容旧下载记录：优先使用落库类型，缺失时按文件扩展名判断。 */
fun DownloadTask.downloadMediaType(): DownloadMediaType {
    return when (mediaType.lowercase()) {
        "photo", "image" -> DownloadMediaType.IMAGE
        "gif", "animated_gif" -> DownloadMediaType.GIF
        "video" -> DownloadMediaType.VIDEO
        else -> when (fileName.substringAfterLast('.', "").lowercase()) {
            "gif" -> DownloadMediaType.GIF
            "jpg", "jpeg", "png", "webp", "heic", "bmp", "avif" -> DownloadMediaType.IMAGE
            else -> DownloadMediaType.VIDEO
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
