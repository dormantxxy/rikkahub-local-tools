package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.TaskPlanStore

/** 任务计划工具：AI 可创建/查看/勾选/删除任务，面板里可视化跟踪 */
internal fun buildManageTasksTool(taskPlanStore: TaskPlanStore): Tool = Tool(
    name = "manage_tasks",
    description = """
        Manage a task plan (todo list). Actions: add (title required), list, complete (id), reopen (id),
        delete (id), clear_done. Use add to break a big goal into steps, then complete as you finish them.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("add"); add("list"); add("complete"); add("reopen"); add("delete"); add("clear_done")
                    })
                    put("description", "Operation")
                })
                put("title", buildJsonObject { put("type", "string"); put("description", "Task title (for add)") })
                put("id", buildJsonObject { put("type", "string"); put("description", "Task id (for complete/reopen/delete)") })
            },
            required = listOf("action")
        )
    },
    execute = {
        val p = it.jsonObject
        val action = p["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        when (action) {
            "add" -> {
                val title = p["title"]?.jsonPrimitive?.contentOrNull ?: error("title is required for add")
                val item = taskPlanStore.add(title)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", true); put("id", item.id); put("title", item.title)
                }.toString()))
            }
            "list" -> {
                val arr = buildJsonArray {
                    taskPlanStore.all().forEach { t ->
                        add(buildJsonObject {
                            put("id", t.id); put("title", t.title); put("done", t.done)
                        })
                    }
                }
                listOf(UIMessagePart.Text(buildJsonObject { put("tasks", arr) }.toString()))
            }
            "complete", "reopen" -> {
                val id = p["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                taskPlanStore.setDone(id, action == "complete")
                listOf(UIMessagePart.Text(buildJsonObject { put("ok", true); put("id", id) }.toString()))
            }
            "delete" -> {
                val id = p["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                taskPlanStore.delete(id)
                listOf(UIMessagePart.Text(buildJsonObject { put("ok", true) }.toString()))
            }
            "clear_done" -> {
                taskPlanStore.clearDone()
                listOf(UIMessagePart.Text(buildJsonObject { put("ok", true) }.toString()))
            }
            else -> error("unknown action: $action")
        }
    }
)
