package com.xverse.app.core.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 环形内存日志 + 落盘文件。
 * 内存环形缓冲 2000 条；文件按天追加，保留最近 7 天。
 * 使用 [LogStore] 记录 app 内部事件，同时写入 android.util.Log 便于 logcat 排障。
 */
enum class LogCategory(val label: String, @androidx.annotation.StringRes val labelRes: Int) {
    WEBVIEW("WebView", com.xverse.app.R.string.logs_cat_webview),
    FILTER("Filter", com.xverse.app.R.string.logs_cat_filter),
    DOWNLOAD("Download", com.xverse.app.R.string.logs_cat_download),
    AUTH("Account", com.xverse.app.R.string.logs_cat_auth),
    HISTORY("History", com.xverse.app.R.string.logs_cat_history),
    ERROR("Error", com.xverse.app.R.string.logs_cat_error),
    GENERAL("General", com.xverse.app.R.string.logs_cat_general)
}

data class LogEntry(
    val id: Long,
    val time: Long,
    val category: LogCategory,
    val message: String,
)

object LogStore {
    private const val RING_CAPACITY = 2000
    private const val FILE_KEEP_DAYS = 7

    private val entries = ArrayDeque<LogEntry>()
    private val idGen = AtomicLong(0)
    private val fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileQueue = Channel<LogEntry>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** UI 观察用状态流 */
    val flow = MutableStateFlow<List<LogEntry>>(emptyList())

    @Volatile
    private var logDir: File? = null

    init {
        fileScope.launch {
            for (entry in fileQueue) writeEntry(entry)
        }
    }

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        // 清理超过 7 天的旧日志
        logDir?.listFiles()?.forEach { f ->
            if (f.lastModified() < System.currentTimeMillis() - FILE_KEEP_DAYS * 86_400_000L) {
                f.delete()
            }
        }
    }

    fun log(category: LogCategory, message: String) {
        val entry = LogEntry(idGen.incrementAndGet(), System.currentTimeMillis(), category, message)
        synchronized(this) {
            entries.addLast(entry)
            while (entries.size > RING_CAPACITY) entries.removeFirst()
            // Flow 也必须发布有界快照；持续 append 会绕过环形缓冲并无限占用内存。
            flow.value = entries.toList()
        }
        // 有界单消费者队列：避免主线程文件 I/O，也避免日志突发时无限创建写盘协程。
        fileQueue.trySend(entry)
        // 也打 logcat，便于 adb 排障
        val level = if (category == LogCategory.ERROR) Log.WARN else Log.INFO
        Log.println(level, "XVerse/${category.name}", message)
    }

    fun error(message: String, t: Throwable? = null) {
        log(LogCategory.ERROR, message + (t?.let { ": ${it.message}" } ?: ""))
    }

    fun current(): List<LogEntry> = synchronized(this) { entries.toList() }

    private fun writeEntry(entry: LogEntry) {
        val dir = logDir ?: return
        try {
            val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(entry.time)) + ".log"
            val f = File(dir, name)
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.time)) +
                " [${entry.category.label}] ${entry.message}\n"
            f.appendText(line)
        } catch (e: Exception) {
            Log.w("LogStore", "write log failed: ${e.message}")
        }
    }

    /** 导出日志到文件，返回文件路径 */
    fun exportToFile(): String? {
        val dir = logDir ?: return null
        val f = File(dir, "xverse-export.txt")
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        synchronized(this) {
            entries.forEach { e ->
                sb.append(fmt.format(Date(e.time)))
                    .append(" [")
                    .append(e.category.label)
                    .append("] ")
                    .append(e.message)
                    .append('\n')
            }
        }
        f.writeText(sb.toString())
        return f.absolutePath
    }
}
