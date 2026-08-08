package com.xverse.app.ui.extensions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.AppInstance
import com.xverse.app.CommandBus
import com.xverse.app.BrowserCommand
import com.xverse.app.core.extensions.ExtensionEntity
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 扩展页 ViewModel：列表、导入（文件/链接）、启停、卸载、配置覆盖层状态。
 */
class ExtensionsViewModel : ViewModel() {

    private val locator get() = AppInstance.locator

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val extensions: StateFlow<List<ExtensionEntity>> =
        locator.extensionRepo.observeAll()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                emptyList(),
            )

    // ---- 导入对话框状态 ----
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage

    // ---- 配置页覆盖层 ----
    private val _optionsExt = MutableStateFlow<ExtensionEntity?>(null)
    val optionsExt: StateFlow<ExtensionEntity?> = _optionsExt

    /** 打开配置覆盖层（扩展需有 optionsPage） */
    fun openOptions(ext: ExtensionEntity) {
        if (ext.optionsPage.isBlank()) return
        _optionsExt.value = ext
    }

    fun closeOptions() {
        _optionsExt.value = null
    }

    /** 文件选择返回后导入（SAF onActivityResult 由 UI 转发） */
    fun importFile(uri: Uri) {
        _importing.value = true
        viewModelScope.launch {
            try {
                val extId = locator.extensionImporter.importFile(uri)
                _importMessage.value = "扩展已导入"
                CommandBus.push(BrowserCommand.Reload)
            } catch (e: Exception) {
                _importMessage.value = "导入失败：${e.message}"
                LogStore.log(LogCategory.FILTER, "扩展导入失败: ${e.message}")
            } finally {
                _importing.value = false
            }
        }
    }

    /** 链接/ID 导入 */
    fun importFromUrl(input: String) {
        _importing.value = true
        viewModelScope.launch {
            try {
                val extId = locator.extensionImporter.importFromUrl(input)
                _importMessage.value = "扩展已导入"
                CommandBus.push(BrowserCommand.Reload)
            } catch (e: Exception) {
                _importMessage.value = "导入失败：${e.message}"
                LogStore.log(LogCategory.FILTER, "扩展链接导入失败: ${e.message}")
            } finally {
                _importing.value = false
            }
        }
    }

    fun setEnabled(ext: ExtensionEntity, enabled: Boolean) {
        viewModelScope.launch {
            locator.extensionRepo.setEnabled(ext.id, enabled)
            CommandBus.push(BrowserCommand.Reload)
        }
    }

    fun uninstall(ext: ExtensionEntity) {
        viewModelScope.launch {
            locator.extensionRepo.delete(ext.id)
            locator.extensionRuntime.deleteExtensionData(ext.id)
            CommandBus.push(BrowserCommand.Reload)
        }
    }

    /** 清除一次性导入提示 */
    fun clearMessage() {
        _importMessage.value = null
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ExtensionsViewModel() as T
            }
        }
    }
}
