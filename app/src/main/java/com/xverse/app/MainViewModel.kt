package com.xverse.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.di.ServiceLocator
import com.xverse.app.ui.navigation.XTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 应用级共享 ViewModel：主题模式、未读角标、Tab 状态、WebView 命令。
 */
class MainViewModel(private val locator: ServiceLocator) : ViewModel() {

    // ---- 主题 ----
    val themeMode = locator.settings.themeMode

    // ---- 浏览器命令（跨 Tab 指令） ----
    // 消费方（BrowserScreen）直接 collect CommandBus.commands，无需在此转发

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

    init {
        // Tab 导航指令（历史点击回跳等）
        viewModelScope.launch {
            CommandBus.tabs.collect { tab ->
                _navTab.value = tab
            }
        }
    }

    fun goHome() {
        CommandBus.push(BrowserCommand.GoHome)
    }

    fun openDeepLink(url: String) {
        // 只需 LoadUrl：切回首页 + 加载推文由 LoadUrl 完成，GoHome 会打断加载
        CommandBus.push(BrowserCommand.LoadUrl(url))
        selectTab(XTab.HOME)
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
    /**
     * 浏览器命令通道。
     * 用 SharedFlow 而非 StateFlow：StateFlow 对相同值去重 + conflation（只留最新值），
     * 历史页重复点击同一链接时命令值不变 → 不发事件 → 无反应。
     * 也保证 GoHome + LoadUrl 连续 push 都投递，不被合并吞掉。
     */
    val commands = MutableSharedFlow<BrowserCommand>(extraBufferCapacity = 1)
    /**
     * Tab 切换事件通道。
     * 用 SharedFlow 而非 StateFlow：StateFlow 对相同值去重（conflation），
     * 历史页二次点击切回首页时 tabs 仍停在 HOME，值未变 → 不发事件 → 无法切回。
     * SharedFlow 每次 emit 都投递，无去重问题。
     */
    val tabs = MutableSharedFlow<XTab>(extraBufferCapacity = 1)
    fun push(cmd: BrowserCommand) {
        commands.tryEmit(cmd)
    }

    /** 请求切换底栏 Tab（事件语义，同值重复 emit 也会投递） */
    fun selectTab(tab: XTab) {
        tabs.tryEmit(tab)
    }
}

/** 浏览器命令（跨 Tab 指令） */
sealed class BrowserCommand {
    data object GoHome : BrowserCommand()
    data class LoadUrl(val url: String) : BrowserCommand()

    /** 瞬显打开推文：WebView 已在 x.com 应用内时走 SPA 路由（不整页重载），否则整页加载 */
    data class OpenTweet(val url: String) : BrowserCommand()

    /** 重载当前页（扩展开关/导入后立即生效） */
    data object Reload : BrowserCommand()

    /** 过滤方式切换后重建注入：清注入列表 + 重新挂全量 + reload 首页 */
    data object ReapplyInjections : BrowserCommand()

    /** 过滤带字幕（CC）视频开关：热更新页面标记，不重载 */
    data class SetCcFilter(val on: Boolean) : BrowserCommand()

    /** 过滤 AI 生成标签开关：热更新页面标记，不重载 */
    data class SetAiFilter(val on: Boolean) : BrowserCommand()

    /** 探针模式切换（adb 广播）：置 probeMode + 清注入 + reload 首页 */
    data class SetProbeMode(val on: Boolean) : BrowserCommand()
}

/** 全局 App 实例访问（避免 Factory 持有 context） */
object AppInstance {
    lateinit var locator: ServiceLocator
}
