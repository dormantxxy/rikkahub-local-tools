package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shell 执行结果 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0
    val output: String get() = buildString {
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(stderr)
        }
    }
}

/** 执行权限模式 */
enum class ShellMode {
    /** 普通 app 权限 */
    NORMAL,

    /** root 权限（su） */
    ROOT,

    /** shizuku 权限 */
    SHIZUKU,
}

/**
 * 统一 Shell 执行器。
 *
 * 支持三种权限模式：
 * - [ShellMode.NORMAL]: 通过 ProcessBuilder 直接执行
 * - [ShellMode.ROOT]: 通过 su -c 执行（需要 root）
 * - [ShellMode.SHIZUKU]: 通过 Shizuku 执行（需要 Shizuku 授权）
 */
class ShellExecutor(
    private val context: Context,
) {
    /**
     * 首选执行模式：有 root 默认用 root，其次 shizuku，最后普通权限。
     * 用户要求：root-shizuku-普通 顺序。
     */
    fun preferredMode(): ShellMode? = when {
        isRootAvailable() -> ShellMode.ROOT
        isShizukuAvailable() -> ShellMode.SHIZUKU
        else -> null
    }

    /** 是否具备执行 CLI 的权限（只支持 root / shizuku，不再支持普通权限） */
    fun hasPrivilege(): Boolean = isRootAvailable() || isShizukuAvailable()

    /** 检测 shizuku 是否可用（通过反射，避免硬依赖） */
    fun isShizukuAvailable(): Boolean {
        return runCatching {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("pingBinder")
            method.invoke(null)
            true
        }.getOrDefault(false)
    }

    /** 检测 root 是否可用 */
    fun isRootAvailable(): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.outputStream.close()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor(3, TimeUnit.SECONDS)
            line?.contains("uid=0") == true
        }.getOrDefault(false)
    }

    /**
     * 执行 shell 命令。
     *
     * @param command 要执行的命令
     * @param cwd 工作目录
     * @param timeoutMillis 超时时间
     * @param mode 权限模式
     */
    suspend fun execute(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = 60_000,
        mode: ShellMode = ShellMode.SHIZUKU,
    ): ShellResult = withContext(Dispatchers.IO) {
        when (mode) {
            ShellMode.NORMAL -> executeNormal(command, cwd, timeoutMillis)
            ShellMode.ROOT -> executeRoot(command, cwd, timeoutMillis)
            ShellMode.SHIZUKU -> executeShizuku(command, cwd, timeoutMillis)
        }
    }

    /** 执行普通命令 */
    private fun executeNormal(
        command: String,
        cwd: String?,
        timeoutMillis: Long,
    ): ShellResult {
        return runCatching {
            val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            if (!cwd.isNullOrBlank()) {
                builder.directory(File(cwd))
            }
            val process = builder.start()
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching ShellResult(-1, stdout, stderr, timedOut = true)
            }
            ShellResult(process.exitValue(), stdout, stderr)
        }.getOrElse {
            ShellResult(-1, "", it.message ?: "执行失败")
        }
    }

    /** 通过 su 执行 root 命令 */
    private fun executeRoot(
        command: String,
        cwd: String?,
        timeoutMillis: Long,
    ): ShellResult {
        return runCatching {
            val fullCommand = buildString {
                if (!cwd.isNullOrBlank()) {
                    append("cd ")
                    append(cwd.shellQuote())
                    append(" && ")
                }
                append(command)
            }
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", fullCommand))
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching ShellResult(-1, stdout, stderr, timedOut = true)
            }
            ShellResult(process.exitValue(), stdout, stderr)
        }.getOrElse {
            ShellResult(-1, "", it.message ?: "root 执行失败")
        }
    }

    /** 通过 Shizuku 执行 */
    private fun executeShizuku(
        command: String,
        cwd: String?,
        timeoutMillis: Long,
    ): ShellResult {
        if (!isShizukuAvailable()) {
            return ShellResult(-1, "", "Shizuku 不可用")
        }
        return runCatching {
            // Shizuku 通过 UserService 执行 shell
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val binder = shizukuClass.getMethod("getBinder").invoke(null)
            // 反射调用 ShizukuShell
            val shellClazz = Class.forName("rikka.shizuku.ShizukuShell")
            val processMethod = shellClazz.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            val cmd = arrayOf("/system/bin/sh", "-c", command)
            val env = arrayOf("PATH=/sbin:/system/bin:/system/xbin")
            val process = processMethod.invoke(null, cmd, env, cwd ?: "/") as Process
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching ShellResult(-1, stdout, stderr, timedOut = true)
            }
            ShellResult(process.exitValue(), stdout, stderr)
        }.getOrElse {
            ShellResult(-1, "", it.message ?: "shizuku 执行失败")
        }
    }

    /** 检查文件是否存在 */
    suspend fun fileExists(path: String): Boolean {
        val result = execute("test -e ${path.shellQuote()} && echo YES || echo NO")
        return result.stdout.trim() == "YES"
    }

    /** 列出目录 */
    suspend fun listDirectory(path: String): List<FileEntry> {
        val result = execute(
            "ls -la ${path.shellQuote()} 2>/dev/null | tail -n +2",
            mode = ShellMode.NORMAL,
        )
        if (!result.isSuccess) return emptyList()
        return result.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLsLine(line, path) }
            .toList()
    }
}

/** 目录条目 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val path: String,
)

/** 解析 ls -la 输出行 */
private fun parseLsLine(line: String, basePath: String): FileEntry? {
    // 格式: drwxr-xr-x 2 root root 4096 Jan 1 00:00 name
    if (line.length < 50) return null
    val typeChar = line.firstOrNull() ?: return null
    val name = line.substringAfterLast(" ").trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    val sizeStr = line.split(" ").filter { it.isNotBlank() }.getOrNull(4) ?: "0"
    val size = sizeStr.toLongOrNull() ?: 0
    val isDir = typeChar == 'd'
    return FileEntry(
        name = name,
        isDirectory = isDir,
        sizeBytes = size,
        path = if (basePath.endsWith("/")) "$basePath$name" else "$basePath/$name",
    )
}

/** shell 引用 */
fun String.shellQuote(): String = "'${replace("'", "'\\''")}'"
