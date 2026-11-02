package me.rerere.rikkahub.data.ai.tools.local

import android.util.Base64
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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.localtools.LocalToolsManager
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.shellQuote

/**
 * 设备本地工具：Shell 命令、文件管理、CLI 工具调用。
 * 权限自动按 root > shizuku > 普通 选择。
 */

internal fun buildRunShellTool(executor: ShellExecutor, settingsStore: SettingsStore, defaultDir: String): Tool = Tool(
    name = "run_shell_command",
    description = """
        Execute a shell command on the Android device and return stdout/stderr/exit code.
        Permission is chosen automatically: root (su) if available, else Shizuku, else normal app permission.
        Use for: file operations, package inspection, running scripts, system info, etc.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "The shell command to execute")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory (optional, default app dir)")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "number")
                    put("description", "Timeout in seconds (optional, default 60)")
                })
            },
            required = listOf("command")
        )
    },
    needsApproval = { args -> isDangerousCommand(args.toString()) },
    execute = {
        val params = it.jsonObject
        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: error("command is required")
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?: settingsStore.settingsFlow.value.projectPath.ifBlank { defaultDir }
        val timeoutMs = (
            params["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.times(1000)
                ?: 60_000.0
            ).toLong().coerceIn(1_000L, 600_000L)
        val mode = executor.preferredMode()
        val result = executor.execute(command = command, cwd = cwd, timeoutMillis = timeoutMs, mode = mode)
        val payload = buildJsonObject {
            put("mode", mode.name.lowercase())
            put("exit_code", result.exitCode)
            put("timed_out", result.timedOut)
            put("stdout", result.stdout.take(60_000))
            put("stderr", result.stderr.take(20_000))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildManageFilesTool(executor: ShellExecutor, settingsStore: SettingsStore): Tool = Tool(
    name = "manage_files",
    description = """
        Manage files on the Android device filesystem. Actions:
        list (list a directory), read (read a text file), write (write text to a file),
        delete (delete file/dir), mkdir (create dir), copy (copy src to dst), move (move src to dst),
        exists (check path), search (grep text under a path), info (stat a path).
        Permission auto: root > shizuku > normal. Provide required params for each action.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("list"); add("read"); add("write"); add("delete")
                            add("mkdir"); add("copy"); add("move"); add("exists")
                            add("search"); add("info")
                        }
                    )
                    put("description", "Operation to perform")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Target path (for most actions)")
                })
                put("dst", buildJsonObject {
                    put("type", "string")
                    put("description", "Destination path (for copy/move)")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content (for write)")
                })
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to search (for search)")
                })
                put("max_chars", buildJsonObject {
                    put("type", "number")
                    put("description", "Max chars to return (optional)")
                })
            },
            required = listOf("action")
        )
    },
    needsApproval = { args ->
        val o = runCatching { args.jsonObject }.getOrNull()
        val act = o?.get("action")?.jsonPrimitive?.contentOrNull
        val pth = o?.get("path")?.jsonPrimitive?.contentOrNull ?: ""
        act == "delete" && (pth == "/" || pth.startsWith("/system") || pth.startsWith("/data") || pth.startsWith("/sdcard") && pth.count { it == '/' } <= 1)
    },
    execute = {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: error("action is required")
        val path = params["path"]?.jsonPrimitive?.contentOrNull
        val dst = params["dst"]?.jsonPrimitive?.contentOrNull
        val content = params["content"]?.jsonPrimitive?.contentOrNull
        val pattern = params["pattern"]?.jsonPrimitive?.contentOrNull
        val maxChars = (params["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60_000)
            .coerceIn(256, 200_000)
        val mode = executor.preferredMode()

        suspend fun run(cmd: String): String {
            val r = executor.execute(cmd, timeoutMillis = 60_000, mode = mode)
            val out = r.stdout.take(maxChars)
            val payload = buildJsonObject {
                put("mode", mode.name.lowercase())
                put("exit_code", r.exitCode)
                if (r.stderr.isNotBlank()) put("stderr", r.stderr.take(10_000))
                put("output", out)
            }
            return payload.toString()
        }

        val json: String = when (action) {
            "list" -> {
                val p = path ?: error("path is required for list")
                run("ls -la -- ${p.shellQuote()} 2>&1")
            }
            "read" -> {
                val p = path ?: error("path is required for read")
                run("head -c $maxChars -- ${p.shellQuote()} 2>&1")
            }
            "write" -> {
                val p = path ?: error("path is required for write")
                val text = content ?: error("content is required for write")
                val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                run("mkdir -p -- $(dirname -- ${p.shellQuote()}) && printf '%s' ${b64.shellQuote()} | base64 -d > ${p.shellQuote()} && echo OK_WRITTEN")
            }
            "delete" -> {
                val p = path ?: error("path is required for delete")
                run("rm -rf -- ${p.shellQuote()} && echo OK_DELETED")
            }
            "mkdir" -> {
                val p = path ?: error("path is required for mkdir")
                run("mkdir -p -- ${p.shellQuote()} && echo OK_MKDIR")
            }
            "copy" -> {
                val p = path ?: error("path is required for copy")
                val d = dst ?: error("dst is required for copy")
                run("cp -r -- ${p.shellQuote()} ${d.shellQuote()} && echo OK_COPIED")
            }
            "move" -> {
                val p = path ?: error("path is required for move")
                val d = dst ?: error("dst is required for move")
                run("mv -- ${p.shellQuote()} ${d.shellQuote()} && echo OK_MOVED")
            }
            "exists" -> {
                val p = path ?: error("path is required for exists")
                run("test -e ${p.shellQuote()} && echo EXISTS || echo NOT_EXISTS")
            }
            "search" -> {
                val p = path ?: error("path is required for search")
                val pat = pattern ?: error("pattern is required for search")
                run("grep -rn -- ${pat.shellQuote()} ${p.shellQuote()} 2>/dev/null | head -100")
            }
            "info" -> {
                val p = path ?: error("path is required for info")
                run("ls -ld -- ${p.shellQuote()} 2>&1; stat -c '%s bytes, mode %a, owner %U' -- ${p.shellQuote()} 2>&1")
            }
            else -> error("unknown action: $action")
        }
        listOf(UIMessagePart.Text(json))
    }
)

internal fun buildRunCliTool(executor: ShellExecutor, toolsManager: LocalToolsManager, settingsStore: SettingsStore, defaultDir: String): Tool = Tool(
    name = "run_cli_tool",
    description = """
        Run a command-line tool (git, python3, pip, ssh, curl, etc.) on the device.
        Automatically prepends the installed local-tools bin directories to PATH.
        Permission auto: root > shizuku > normal. Example: "git status", "python3 -c 'print(1)'".
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Full CLI command, e.g. 'git --version' or 'python3 script.py'")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory (optional)")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "number")
                    put("description", "Timeout seconds (optional, default 120)")
                })
            },
            required = listOf("command")
        )
    },
    needsApproval = { args -> isDangerousCommand(args.toString()) },
    execute = {
        val params = it.jsonObject
        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: error("command is required")
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?: settingsStore.settingsFlow.value.projectPath.ifBlank { defaultDir }
        val timeoutMs = (
            params["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.times(1000)
                ?: 120_000.0
            ).toLong().coerceIn(1_000L, 600_000L)
        val rootfsReady = toolsManager.isRootfsInstalled()
        val cdPrefix = if (cwd.isNullOrBlank()) "" else "cd " + cwd.shellQuote() + " && "
        val result = toolsManager.runInRootfs(cdPrefix + command, timeoutMs)
        val payload = buildJsonObject {
            put("rootfs_installed", rootfsReady)
            put("exit_code", result.exitCode)
            put("timed_out", result.timedOut)
            put("stdout", result.stdout.take(60_000))
            put("stderr", result.stderr.take(20_000))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 危险命令检测：命中需要用户二次确认 */
private val DANGEROUS_PATTERNS = listOf(
    "rm -rf /", "rm -rf /*", "mkfs", "dd if=", "> /dev/block", "format ", "reboot", "shutdown ",
    "pm uninstall", "> /system", "chmod -r 777 /", "fastboot", "wipe ", ":(){", "mv /*",
)

internal fun isDangerousCommand(text: String): Boolean {
    val t = text.lowercase()
    return DANGEROUS_PATTERNS.any { t.contains(it) }
}
