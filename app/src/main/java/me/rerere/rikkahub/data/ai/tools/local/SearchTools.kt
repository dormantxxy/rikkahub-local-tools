package me.rerere.rikkahub.data.ai.tools.local

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

/** 代码检索：设备上 grep。 */
internal fun buildSearchCodeTool(executor: ShellExecutor, defaultDir: String): Tool = Tool(
    name = "search_code",
    description = "Search text across files on the device (grep). Params: pattern(required), path(default app dir), include(glob like *.kt), max_results(default 100), case_sensitive(default false).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject { put("type", "string"); put("description", "Text or regex") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Directory to search") })
                put("include", buildJsonObject { put("type", "string"); put("description", "File filter e.g. *.kt") })
                put("max_results", buildJsonObject { put("type", "number"); put("description", "Max matches (default 100)") })
                put("case_sensitive", buildJsonObject { put("type", "boolean"); put("description", "Case sensitive (default false)") })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val p = it.jsonObject
        val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull ?: error("pattern is required")
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } ?: defaultDir
        val include = p["include"]?.jsonPrimitive?.contentOrNull
        val maxResults = (p["max_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val caseSensitive = p["case_sensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val flags = if (caseSensitive) "-rn" else "-rni"
        val inc = if (include.isNullOrBlank()) "" else "--include=${include.shellQuote()} "
        val cmd = "grep $flags $inc-- ${pattern.shellQuote()} ${path.shellQuote()} 2>/dev/null | head -$maxResults"
        val r = executor.execute(cmd, timeoutMillis = 120_000, mode = executor.preferredMode())
        listOf(UIMessagePart.Text(buildJsonObject {
            put("pattern", pattern); put("path", path); put("exit_code", r.exitCode); put("output", r.stdout.take(60_000))
        }.toString()))
    }
)

/** 文件树。 */
internal fun buildFileTreeTool(executor: ShellExecutor, defaultDir: String): Tool = Tool(
    name = "list_file_tree",
    description = "List a directory tree on the device. Params: path(default app dir), depth(default 3), max_entries(default 300).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Root dir") })
                put("depth", buildJsonObject { put("type", "number"); put("description", "Max depth (default 3)") })
                put("max_entries", buildJsonObject { put("type", "number"); put("description", "Max entries (default 300)") })
            }
        )
    },
    execute = {
        val p = it.jsonObject
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } ?: defaultDir
        val depth = (p["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 3).coerceIn(1, 10)
        val maxEntries = (p["max_entries"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300).coerceIn(1, 2000)
        val cmd = "find ${path.shellQuote()} -maxdepth $depth -not -path '*/.git/*' 2>/dev/null | head -$maxEntries"
        val r = executor.execute(cmd, timeoutMillis = 60_000, mode = executor.preferredMode())
        listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path); put("output", (r.stdout.ifBlank { r.stderr }).take(60_000))
        }.toString()))
    }
)
