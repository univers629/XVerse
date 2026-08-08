package com.xverse.app.core.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 环形内存日志 + 落盘文件。
 * 内存环形缓冲 2000 条；文件按天追加，保留最近 7 天。
 * 使用 [LogStore] 记录 app 内部事件，同时写入 android.util.Log 便于 logcat 排障。
 */
enum class LogCategory(val label: String) {
    WEBVIEW("WebView"),
    FILTER("过滤"),
    DOWNLOAD("下载"),
    AUTH("账号"),
    HISTORY("历史"),
    ERROR("错误"),
    GENERAL("一般")
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
    private val idGen = AtomicInteger(0)

    /** UI 观察用状态流 */
    val flow = MutableStateFlow<List<LogEntry>>(emptyList())

    @Volatile
    private var logDir: File? = null

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
        val entry = LogEntry(idGen.incrementAndGet().toLong(), System.currentTimeMillis(), category, message)
        synchronized(this) {
            entries.addLast(entry)
            while (entries.size > RING_CAPACITY) entries.removeFirst()
        }
        flow.value = flow.value + entry
        // 落盘（追加行）
        val dir = logDir
        if (dir != null) {
            try {
                val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(entry.time)) + ".log"
                val f = File(dir, name)
                val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.time)) +
                    " [${category.label}] ${entry.message}\n"
                f.appendText(line)
            } catch (e: Exception) {
                Log.w("LogStore", "write log failed: ${e.message}")
            }
        }
        // 也打 logcat，便于 adb 排障
        val level = if (category == LogCategory.ERROR) Log.WARN else Log.INFO
        Log.println(level, "XVerse/${category.name}", message)
    }

    fun error(message: String, t: Throwable? = null) {
        log(LogCategory.ERROR, message + (t?.let { ": ${it.message}" } ?: ""))
    }

    fun current(): List<LogEntry> = synchronized(this) { entries.toList() }

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
