package com.xverse.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xverse.app.di.ServiceLocator
import com.xverse.app.ui.navigation.XTab
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 应用级共享 ViewModel：主题模式、未读角标、Tab 状态、WebView 命令。
 */
class MainViewModel(locator: ServiceLocator) : ViewModel() {

    // ---- 主题与语言 ----
    val themeMode = locator.settings.themeMode
    val appLanguage = locator.settings.appLanguage
    val customMonetEnabled = locator.settings.customMonetEnabled
    val customMonetColor = locator.settings.customMonetColor

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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                MainViewModel((app as XVerseApp).locator)
            }
        }
    }
}

/** 跨 Tab 浏览器命令总线（进程级单例） */
object CommandBus {
    /**
     * 浏览器命令通道。
     * Buffered Channel 保留订阅建立前的冷启动深链，也不会合并相同命令；
     * 这可避免连续点击或首次启动时的导航事件被静默丢弃。
     */
    private val commandChannel = Channel<BrowserCommand>(
        capacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands = commandChannel.receiveAsFlow()
    /**
     * Tab 切换事件通道。
     * 同样使用事件语义；重复选择 HOME 仍会被逐次投递。
     */
    private val tabChannel = Channel<XTab>(
        capacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val tabs = tabChannel.receiveAsFlow()

    fun push(cmd: BrowserCommand) {
        commandChannel.trySend(cmd)
    }

    /** 请求切换底栏 Tab（事件语义，同值重复 emit 也会投递） */
    fun selectTab(tab: XTab) {
        tabChannel.trySend(tab)
    }

    private const val EVENT_BUFFER_CAPACITY = 64
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

}
