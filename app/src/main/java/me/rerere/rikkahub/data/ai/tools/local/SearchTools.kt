package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.localtools.LocalToolsManager
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ShellMode
import me.rerere.rikkahub.data.localtools.shellQuote

/**
 * 代码检索：在项目目录里按关键字搜索（优先用 rootfs 的 grep，功能完整）。
 */
internal fun buildSearchCodeTool(
    executor: ShellExecutor,
    toolsManager: LocalToolsManager,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "search_code",
    description = """
        Search text across files (code search). Returns file:line:content matches.
        Params: pattern (required), path (default current project dir), include (glob like *.kt),
        max_results (default 100), case_sensitive (default false).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject { put("type", "string"); put("description", "Text or regex to search") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Directory to search (default project dir)") })
                put("include", buildJsonObject { put("type", "string"); put("description", "File filter, e.g. *.kt") })
                put("max_results", buildJsonObject { put("type", "number"); put("description", "Max matches (default 100)") })
                put("case_sensitive", buildJsonObject { put("type", "boolean"); put("description", "Case sensitive (default false)") })
            },
            required = listOf("pattern")
        )
    },
    execute = {
        val p = it.jsonObject
        val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull ?: error("pattern is required")
        val projectDir = settingsStore.settingsFlow.value.projectPath
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } ?: projectDir.ifBlank { "/storage/emulated/0" }
        val include = p["include"]?.jsonPrimitive?.contentOrNull
        val maxResults = (p["max_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val caseSensitive = p["case_sensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val flags = buildString {
            append("-rn")
            if (!caseSensitive) append("i")
        }
        val includeArg = if (include.isNullOrBlank()) "" else "--include=${include.shellQuote()} "
        val cmd = "grep $flags $includeArg-- ${pattern.shellQuote()} ${path.shellQuote()} 2>/dev/null | head -$maxResults"

        // 优先 rootfs 的 GNU grep
        val r = if (toolsManager.isRootfsInstalled()) {
            toolsManager.runInRootfs(cmd, timeoutMillis = 120_000)
        } else {
            executor.execute(cmd, timeoutMillis = 120_000, mode = executor.preferredMode() ?: ShellMode.ROOT)
        }
        val payload = buildJsonObject {
            put("pattern", pattern)
            put("path", path)
            put("exit_code", r.exitCode)
            put("output", r.stdout.take(60_000))
            if (r.stderr.isNotBlank()) put("stderr", r.stderr.take(2_000))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 文件树 */
internal fun buildFileTreeTool(
    executor: ShellExecutor,
    toolsManager: LocalToolsManager,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "list_file_tree",
    description = "List a directory tree (default project dir). Params: path, depth (default 3), max_entries (default 300).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Root dir (default project dir)") })
                put("depth", buildJsonObject { put("type", "number"); put("description", "Max depth (default 3)") })
                put("max_entries", buildJsonObject { put("type", "number"); put("description", "Max entries (default 300)") })
            }
        )
    },
    execute = {
        val p = it.jsonObject
        val projectDir = settingsStore.settingsFlow.value.projectPath
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } ?: projectDir.ifBlank { "/storage/emulated/0" }
        val depth = (p["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 3).coerceIn(1, 8)
        val maxEntries = (p["max_entries"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300).coerceIn(1, 2000)
        val cmd = "cd ${path.shellQuote()} 2>/dev/null && find . -maxdepth $depth -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/.gradle/*' 2>/dev/null | head -$maxEntries"
        val r = if (toolsManager.isRootfsInstalled()) {
            toolsManager.runInRootfs(cmd, timeoutMillis = 60_000)
        } else {
            executor.execute(cmd, timeoutMillis = 60_000, mode = executor.preferredMode() ?: ShellMode.ROOT)
        }
        val payload = buildJsonObject {
            put("path", path)
            put("output", r.stdout.take(60_000))
            if (r.stderr.isNotBlank()) put("stderr", r.stderr.take(2_000))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
