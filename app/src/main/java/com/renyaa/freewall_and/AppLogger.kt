package com.renyaa.freewall_and

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class LogEntry(
    val timestamp: Long,
    val message: String,
    val isError: Boolean = false
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

object AppLogger {
    private const val MAX_LOGS = 500
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    init {
        log("Freewall 시스템 준비됨")
    }

    fun log(message: String, isError: Boolean = false) {
        val entry = LogEntry(System.currentTimeMillis(), message, isError)
        logs.add(entry)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        listeners.forEach { listener ->
            try {
                listener(entry)
            } catch (_: Exception) {}
        }
    }

    fun getLogs(): List<LogEntry> = logs.toList()

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        logs.clear()
        log("로그가 초기화되었습니다.")
    }
}
