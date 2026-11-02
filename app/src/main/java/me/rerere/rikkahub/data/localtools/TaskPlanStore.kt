package me.rerere.rikkahub.data.localtools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class TaskItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 任务计划存储（AI 可增删改，UI 可勾选）。
 * 用 SharedPreferences + JSON，轻量、同步、无需协程。
 */
class TaskPlanStore(context: Context) {
    private val sp = context.getSharedPreferences("task_plan", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private fun load(): List<TaskItem> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<TaskItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun persist(list: List<TaskItem>) {
        _tasks.value = list
        sp.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    fun add(title: String): TaskItem {
        val item = TaskItem(title = title.trim().ifBlank { "未命名任务" })
        persist(_tasks.value + item)
        return item
    }

    fun setDone(id: String, done: Boolean) {
        persist(_tasks.value.map { if (it.id == id) it.copy(done = done) else it })
    }

    fun toggle(id: String) {
        persist(_tasks.value.map { if (it.id == id) it.copy(done = !it.done) else it })
    }

    fun delete(id: String) {
        persist(_tasks.value.filterNot { it.id == id })
    }

    fun clearDone() {
        persist(_tasks.value.filterNot { it.done })
    }

    fun all(): List<TaskItem> = _tasks.value

    companion object {
        private const val KEY = "tasks"
    }
}
