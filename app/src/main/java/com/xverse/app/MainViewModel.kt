package com.xverse.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.di.ServiceLocator
import com.xverse.app.ui.navigation.XTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 应用级共享 ViewModel：主题模式、未读角标、Tab 状态、WebView 命令。
 */
class MainViewModel(private val locator: ServiceLocator) : ViewModel() {

    // ---- 主题 ----
    val themeMode = locator.settings.themeMode

    // ---- 未读角标 ----
    private val _historyUnread = MutableStateFlow(0)
    val historyUnread: StateFlow<Int> = _historyUnread
    private val _logUnread = MutableStateFlow(0)
    val logUnread: StateFlow<Int> = _logUnread

    // ---- 浏览器命令（跨 Tab 指令） ----
    val browserCommands = MutableStateFlow<BrowserCommand?>(null)

    // ---- Tab 导航（跨 Tab 指令：历史点击回跳等） ----
    private val _navTab = MutableStateFlow<XTab?>(null)
    /** 请求切换的 Tab（MainActivity 消费后应置空） */
    val navTab: StateFlow<XTab?> = _navTab

    fun selectTab(tab: XTab) {
        _navTab.value = tab
    }

    /** 消费后清空（避免 StateFlow 同值去重导致连续导航失效） */
    fun clearNavTab() {
        _navTab.value = null
    }

    /** 历史点击回跳：切到首页并加载推文 */
    fun openInBrowser(url: String) {
        CommandBus.push(BrowserCommand.GoHome)
        CommandBus.push(BrowserCommand.LoadUrl(url))
        selectTab(XTab.HOME)
    }

    init {
        viewModelScope.launch {
            CommandBus.commands.collect { cmd ->
                browserCommands.value = cmd
            }
        }
        // Tab 导航指令（历史点击回跳等）
        viewModelScope.launch {
            CommandBus.tabs.collect { tab ->
                if (tab != null) {
                    _navTab.value = tab
                }
            }
        }
    }

    fun goHome() {
        CommandBus.push(BrowserCommand.GoHome)
    }

    fun openDeepLink(url: String) {
        CommandBus.push(BrowserCommand.GoHome) // 先切回首页
        CommandBus.push(BrowserCommand.LoadUrl(url))
        selectTab(XTab.HOME)
    }

    fun markRead(tab: XTab) {
        when (tab) {
            XTab.HISTORY -> _historyUnread.value = 0
            XTab.LOGS -> _logUnread.value = 0
            else -> {}
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(AppInstance.locator) as T
            }
        }
    }
}

/** 跨 Tab 浏览器命令总线（进程级单例） */
object CommandBus {
    val commands = MutableStateFlow<BrowserCommand?>(null)
    val tabs = MutableStateFlow<XTab?>(null)
    fun push(cmd: BrowserCommand) {
        commands.value = cmd
    }

    /** 请求切换底栏 Tab */
    fun selectTab(tab: XTab) {
        tabs.value = tab
    }
}

/** 浏览器命令（跨 Tab 指令） */
sealed class BrowserCommand {
    data object GoHome : BrowserCommand()
    data class LoadUrl(val url: String) : BrowserCommand()
}

/** 全局 App 实例访问（避免 Factory 持有 context） */
object AppInstance {
    lateinit var locator: ServiceLocator
}
