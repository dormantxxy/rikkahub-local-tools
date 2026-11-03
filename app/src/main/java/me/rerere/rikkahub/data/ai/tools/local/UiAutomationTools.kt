package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.shellQuote

/** 点击屏幕坐标（需 root/Shizuku）。 */
internal fun buildTapTool(executor: ShellExecutor): Tool = Tool(
    name = "tap",
    description = "点击 Android 屏幕坐标（需 root/Shizuku）。Params: x, y。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject { put("type", "number"); put("description", "x 坐标") })
                put("y", buildJsonObject { put("type", "number"); put("description", "y 坐标") })
            },
            required = listOf("x", "y"),
        )
    },
    execute = {
        val p = it.jsonObject
        val x = p["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("x is required")
        val y = p["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("y is required")
        val r = executor.execute("input tap $x $y 2>&1", timeoutMillis = 15_000, mode = executor.preferredMode())
        listOf(UIMessagePart.Text(buildJsonObject { put("exit_code", r.exitCode); put("output", r.output) }.toString()))
    }
)

/** 滑动屏幕（需 root/Shizuku）。 */
internal fun buildSwipeTool(executor: ShellExecutor): Tool = Tool(
    name = "swipe",
    description = "滑动 Android 屏幕（需 root/Shizuku）。Params: x1, y1, x2, y2, duration(ms, 默认 300)。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x1", buildJsonObject { put("type", "number"); put("description", "起点 x") })
                put("y1", buildJsonObject { put("type", "number"); put("description", "起点 y") })
                put("x2", buildJsonObject { put("type", "number"); put("description", "终点 x") })
                put("y2", buildJsonObject { put("type", "number"); put("description", "终点 y") })
                put("duration", buildJsonObject { put("type", "number"); put("description", "时长 ms（默认 300）") })
            },
            required = listOf("x1", "y1", "x2", "y2"),
        )
    },
    execute = {
        val p = it.jsonObject
        fun n(k: String): Int = p[k]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("$k is required")
        val duration = p["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300
        val r = executor.execute("input swipe ${n("x1")} ${n("y1")} ${n("x2")} ${n("y2")} $duration 2>&1", timeoutMillis = 20_000, mode = executor.preferredMode())
        listOf(UIMessagePart.Text(buildJsonObject { put("exit_code", r.exitCode); put("output", r.output) }.toString()))
    }
)

/** 输入文本（需 root/Shizuku）。 */
internal fun buildInputTextTool(executor: ShellExecutor): Tool = Tool(
    name = "input_text",
    description = "向当前焦点输入文本（需 root/Shizuku）。Params: text。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("description", "要输入的文本") })
            },
            required = listOf("text"),
        )
    },
    execute = {
        val p = it.jsonObject
        val text = p["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
        val escaped = text.replace(" ", "%s")
        val r = executor.execute("input text ${escaped.shellQuote()} 2>&1", timeoutMillis = 20_000, mode = executor.preferredMode())
        listOf(UIMessagePart.Text(buildJsonObject { put("exit_code", r.exitCode); put("output", r.output) }.toString()))
    }
)

/** 获取当前界面控件树（需 root/Shizuku）。 */
internal fun buildUiDumpTool(executor: ShellExecutor): Tool = Tool(
    name = "ui_dump",
    description = "获取当前 Android 界面的 UI 控件树 XML（需 root/Shizuku）。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val r = executor.execute(
            "uiautomator dump /data/local/tmp/ui_dump.xml >/dev/null 2>&1; cat /data/local/tmp/ui_dump.xml 2>/dev/null | head -c 200000",
            timeoutMillis = 60_000,
            mode = executor.preferredMode(),
        )
        listOf(UIMessagePart.Text(r.output.ifBlank { "（未获取到控件树，exit=${r.exitCode}）" }.take(200_000)))
    }
)

/** 截屏（需 root/Shizuku）。 */
internal fun buildScreenshotTool(context: Context, executor: ShellExecutor): Tool = Tool(
    name = "screenshot",
    description = "截屏（需 root/Shizuku），保存 PNG 到应用私有目录并返回文件路径。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val mode = executor.preferredMode()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val out = File(dir, "screen_${System.currentTimeMillis()}.png")
        val r = executor.execute("screencap -p ${out.absolutePath.shellQuote()} 2>&1; ls -la ${out.absolutePath.shellQuote()} 2>&1", timeoutMillis = 60_000, mode = mode)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("mode", mode.name.lowercase())
            put("exit_code", r.exitCode)
            put("path", out.absolutePath)
            put("exists", out.exists())
            put("size", if (out.exists()) out.length() else 0)
            put("output", r.output.take(10_000))
        }.toString()))
    }
)
