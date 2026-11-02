package me.rerere.rikkahub.data.localtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolLogEntry(
    val tool: String,
    val args: String,
    val result: String,
    val ok: Boolean,
    val durationMs: Long,
    val at: Long = System.currentTimeMillis(),
)

/** 工具执行日志（内存环形缓冲，最多 300 条） */
object ToolLogStore {
    private const val MAX = 300
    private val _logs = MutableStateFlow<List<ToolLogEntry>>(emptyList())
    val logs: StateFlow<List<ToolLogEntry>> = _logs.asStateFlow()

    fun add(entry: ToolLogEntry) {
        _logs.value = (listOf(entry) + _logs.value).take(MAX)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
