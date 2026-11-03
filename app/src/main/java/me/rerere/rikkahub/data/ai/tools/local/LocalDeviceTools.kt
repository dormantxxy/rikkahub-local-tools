package me.rerere.rikkahub.data.ai.tools.local

import android.util.Base64
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

private val DANGEROUS = listOf("rm -rf /", "mkfs", "dd if=", "> /dev/block", "reboot", "shutdown", "pm uninstall", "wipe ", "fastboot", "mv /*")
private fun isDangerous(text: String): Boolean { val t = text.lowercase(); return DANGEROUS.any { t.contains(it) } }

/** 设备 Shell：root(su) 优先，否则普通权限。 */
internal fun buildRunShellTool(executor: ShellExecutor, defaultDir: String): Tool = Tool(
    name = "run_shell_command",
    description = "Execute a shell command on the Android device (root via su if available, else normal app permission). Params: command, cwd(optional), timeout_seconds(optional).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run") })
                put("cwd", buildJsonObject { put("type", "string"); put("description", "Working directory (optional)") })
                put("timeout_seconds", buildJsonObject { put("type", "number"); put("description", "Timeout seconds (optional, default 60)") })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { isDangerous(it.toString()) },
    execute = {
        val p = it.jsonObject
        val cmd = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val cwd = p["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } ?: defaultDir
        val timeoutMs = (p["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.times(1000) ?: 60_000.0)
            .toLong().coerceIn(1_000L, 600_000L)
        val mode = executor.preferredMode()
        val r = executor.execute(cmd, cwd = cwd, timeoutMillis = timeoutMs, mode = mode)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("mode", mode.name.lowercase()); put("exit_code", r.exitCode); put("timed_out", r.timedOut)
            put("stdout", r.stdout.take(60_000)); put("stderr", r.stderr.take(20_000))
        }.toString()))
    }
)

/** 文件管理（list/read/write/delete/mkdir/copy/move/exists/search/info）。 */
internal fun buildManageFilesTool(executor: ShellExecutor): Tool = Tool(
    name = "manage_files",
    description = "Manage files on the Android device. Actions: list, read, write, delete, mkdir, copy, move, exists, search, info. Params: action(required), path, dst, content.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("description", "list|read|write|delete|mkdir|copy|move|exists|search|info") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Target path") })
                put("dst", buildJsonObject { put("type", "string"); put("description", "Destination (copy/move)") })
                put("content", buildJsonObject { put("type", "string"); put("description", "Text content (write)") })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { isDangerous(it.toString()) },
    execute = {
        val p = it.jsonObject
        val action = p["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
        val dst = p["dst"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
        val content = p["content"]?.jsonPrimitive?.contentOrNull
        val mode = executor.preferredMode()
        val cmd: String = when (action) {
            "list" -> "ls -la ${(path ?: error("path is required for list")).shellQuote()} 2>&1"
            "read" -> "cat ${(path ?: error("path is required for read")).shellQuote()} 2>&1 | head -c 60000"
            "write" -> {
                val pth = path ?: error("path is required for write")
                val b64 = Base64.encodeToString((content ?: "").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val parent = pth.substringBeforeLast('/', "")
                (if (parent.isNotBlank()) "mkdir -p ${parent.shellQuote()}; " else "") +
                    "printf '%s' ${b64.shellQuote()} | base64 -d > ${pth.shellQuote()} 2>&1 && echo WROTE_OK"
            }
            "delete" -> "rm -rf ${(path ?: error("path is required for delete")).shellQuote()} 2>&1 && echo DELETED"
            "mkdir" -> "mkdir -p ${(path ?: error("path is required for mkdir")).shellQuote()} 2>&1 && echo MKDIR_OK"
            "copy" -> "cp -rf ${(path ?: error("path required")).shellQuote()} ${(dst ?: error("dst required")).shellQuote()} 2>&1 && echo COPIED"
            "move" -> "mv -f ${(path ?: error("path required")).shellQuote()} ${(dst ?: error("dst required")).shellQuote()} 2>&1 && echo MOVED"
            "exists" -> "test -e ${(path ?: error("path required")).shellQuote()} && echo YES || echo NO"
            "search" -> "grep -rn ${(content ?: path ?: error("content/pattern required for search")).shellQuote()} ${(path ?: "/").shellQuote()} 2>/dev/null | head -200"
            "info" -> "ls -ld ${(path ?: error("path required for info")).shellQuote()} 2>&1"
            else -> error("unknown action: $action")
        }
        val r = executor.execute(cmd, timeoutMillis = 120_000, mode = mode)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("action", action); put("mode", mode.name.lowercase()); put("exit_code", r.exitCode); put("output", r.output.take(80_000))
        }.toString()))
    }
)
