package com.happy.consumption.util

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap

// 单一 IO 消费者负责清理和追加；消息只由生产代码构造，不接收用户原始输入。
class FileLogWriter(private val directory: File, private val clock: Clock = Clock.systemDefaultZone(),
    private val append: (File, String) -> Unit = { file, text -> file.appendText(text, Charsets.UTF_8) },
    private val delete: (File) -> Boolean = { it.delete() }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Command {
        data class Line(val date: LocalDate, val text: String) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
        data object Clean : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    init {
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        Command.Clean -> cleanFiles()
                        is Command.Line -> {
                            if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Log directory unavailable")
                            append(File(directory, "${command.date}.log"), command.text + "\n")
                        }
                        is Command.Barrier -> command.done.complete(Unit)
                    }
                } catch (e: Exception) {
                    // 日志自身的 IO 失败不能递归记录，也不能影响数据操作；保留可观测失败数。
                    failureCount++
                    if (command is Command.Barrier) command.done.complete(Unit)
                }
            }
        }
        commands.trySend(Command.Clean)
    }
    @Volatile var failureCount: Int = 0
        private set
    private fun cleanFiles() {
        if (!directory.exists()) return
        if (!directory.isDirectory) throw java.io.IOException("Log directory unavailable")
        val today = LocalDate.now(clock)
        val entries = directory.listFiles() ?: throw java.io.IOException("Log directory unreadable")
        for (file in entries) {
            if (!file.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.log"))) continue
            val date = try { LocalDate.parse(file.name.removeSuffix(".log")) }
                catch (e: java.time.DateTimeException) { failureCount++; continue }
            if (date < today.minusDays(7) && !delete(file)) throw java.io.IOException("Log cleanup failed")
        }
    }
    fun write(level: String, tag: String, message: String, error: Throwable? = null) {
        val now = clock.instant().atZone(clock.zone)
        val safeTag = tag.replace(Regex("[\\r\\n\\[\\]]"), "_")
        val safeMessage = message.replace('\n', ' ').replace('\r', ' ')
        val line = "${now.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS", java.util.Locale.US))} [$level] [${Thread.currentThread().name}] $safeTag: $safeMessage"
        // 异常消息可能含密码、JSON 或 SQL 参数，保留类型、完整帧、cause/suppressed 结构，移除消息。
        commands.trySend(Command.Line(now.toLocalDate(), line + (error?.let { "\n" + safeStack(it) } ?: "")))
    }
    private fun safeStack(error: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun appendError(item: Throwable, prefix: String) {
            if (!seen.add(item)) { append(prefix).append("[cycle]\n"); return }
            append(prefix).append(item.javaClass.name).append('\n')
            item.stackTrace.forEach { append("\tat ").append(it.toString()).append('\n') }
            item.suppressed.forEach { appendError(it, "Suppressed: ") }
            item.cause?.let { appendError(it, "Caused by: ") }
        }
        appendError(error, "")
    }
    suspend fun flush() { val done = CompletableDeferred<Unit>(); commands.send(Command.Barrier(done)); done.await() }
    suspend fun close() { flush(); commands.close(); scope.coroutineContext[Job]?.children?.forEach { it.join() }; scope.cancel() }
}

object LogHelper {
    @Volatile private var writer: FileLogWriter? = null
    fun initialize(filesRoot: File) { if (writer == null) synchronized(this) { if (writer == null) writer = FileLogWriter(File(filesRoot, "logs")) } }
    fun info(tag: String, msg: String) { writer?.write("Info", tag, msg) }
    fun error(tag: String, msg: String, throwable: Throwable) { writer?.write("Error", tag, msg, throwable) }
    suspend fun flush() { writer?.flush() }
}
