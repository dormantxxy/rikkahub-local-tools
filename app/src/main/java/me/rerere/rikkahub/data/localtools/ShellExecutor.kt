package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
        if (stderr.isNotBlank()) { if (isNotEmpty()) append('\n'); append(stderr) }
    }
}

/** 执行权限模式 */
enum class ShellMode { NORMAL, ROOT, SHIZUKU }

/**
 * 统一 Shell 执行器。
 * - NORMAL：普通 app 权限
 * - ROOT：su（root）
 * - SHIZUKU：通过 Shizuku.newProcess（root 启动的 Shizuku = root 域，可 exec data 目录、seccomp 关）
 *
 * 原生工具链执行走 [executeToolchain]：root 优先，其次 Shizuku，二者都没有才报错。
 * 所有执行都可被协程取消（取消即杀整棵进程树）。
 */
class ShellExecutor(
    private val context: Context,
) {
    companion object {
        const val HOST_PATH =
            "/sbin:/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"
    }

    fun isRootAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", "su -c id 2>/dev/null").redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(8, TimeUnit.SECONDS) && out.contains("uid=0")
    }.getOrDefault(false)

    fun isShizukuAvailable(): Boolean = ShizukuHelper.isBinderAlive()
    fun isShizukuGranted(): Boolean = ShizukuHelper.hasPermission()

    fun hasPrivilege(): Boolean = isRootAvailable() || isShizukuGranted()

    /** root 优先，其次 Shizuku。 */
    fun preferredMode(): ShellMode = when {
        isRootAvailable() -> ShellMode.ROOT
        isShizukuGranted() -> ShellMode.SHIZUKU
        else -> ShellMode.NORMAL
    }

    /** 只能 root / Shizuku（工具链直跑用），都没有返回 null。 */
    fun privilegedMode(): ShellMode? = when {
        isRootAvailable() -> ShellMode.ROOT
        isShizukuGranted() -> ShellMode.SHIZUKU
        else -> null
    }

    suspend fun execute(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = 60_000,
        mode: ShellMode = ShellMode.NORMAL,
    ): ShellResult = withContext(Dispatchers.IO) {
        val process = try {
            when (mode) {
                ShellMode.NORMAL -> startNormal(command, cwd)
                ShellMode.ROOT -> startRoot(command, cwd)
                ShellMode.SHIZUKU -> startShizuku(command, cwd)
            }
        } catch (e: Throwable) {
            return@withContext ShellResult(-1, "", e.message ?: "执行失败")
        }
        awaitProcess(process, timeoutMillis)
    }

    /** 在聚合后的原生工具链环境执行（root 优先，其次 Shizuku）。 */
    suspend fun executeToolchain(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = 60_000,
        env: ToolchainEnv,
    ): ShellResult {
        val mode = privilegedMode()
            ?: return ShellResult(-1, "", "需要 root 或 Shizuku 权限才能直跑原生工具链（当前都不可用）")
        val script = buildString {
            if (!cwd.isNullOrBlank()) append("cd ").append(cwd.shellQuote()).append(" && ")
            append("export PATH=").append(env.path.shellQuote()).append("; ")
            if (env.ldLibraryPath.isNotBlank()) append("export LD_LIBRARY_PATH=").append(env.ldLibraryPath.shellQuote()).append("; ")
            append("export HOME=").append(env.home.shellQuote()).append("; ")
            append("export TMPDIR=").append(env.tmp.shellQuote()).append("; ")
            append("export LANG=en_US.UTF-8; ")
            append(command)
        }
        return withContext(Dispatchers.IO) {
            val process = try {
                if (mode == ShellMode.ROOT) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", script))
                } else {
                    startShizuku(script, null)
                }
            } catch (e: Throwable) {
                return@withContext ShellResult(-1, "", (e.message ?: "执行失败") + "（mode=$mode）")
            }
            awaitProcess(process, timeoutMillis)
        }
    }

    private fun startNormal(command: String, cwd: String?): Process {
        val b = ProcessBuilder("/system/bin/sh", "-c", command)
        if (!cwd.isNullOrBlank()) b.directory(File(cwd))
        b.environment()["PATH"] = HOST_PATH
        val p = b.start(); runCatching { p.outputStream.close() }; return p
    }

    private fun startRoot(command: String, cwd: String?): Process {
        val full = buildString {
            if (!cwd.isNullOrBlank()) { append("cd "); append(cwd.shellQuote()); append(" && ") }
            append(command)
        }
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", full))
        runCatching { p.outputStream.close() }; return p
    }

    /** 通过 Shizuku.newProcess 执行（反射兼容 public/private）。 */
    private fun startShizuku(command: String, cwd: String?): Process {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val types = arrayOf(Array<String>::class.java, Array<String>::class.java, String::class.java)
        val method = runCatching { clazz.getMethod("newProcess", *types) }
            .getOrElse { clazz.getDeclaredMethod("newProcess", *types).apply { isAccessible = true } }
        val cmd = arrayOf("/system/bin/sh", "-c", command)
        val env = arrayOf("PATH=$HOST_PATH")
        val p = method.invoke(null, cmd, env, cwd ?: "/") as Process
        runCatching { p.outputStream.close() }; return p
    }

    private suspend fun awaitProcess(process: Process, timeoutMillis: Long): ShellResult {
        val outBuf = StringBuilder(); val errBuf = StringBuilder()
        val outT = daemonReader(process.inputStream, outBuf)
        val errT = daemonReader(process.errorStream, errBuf)
        val finished = try {
            withTimeout(timeoutMillis) { while (process.isAlive) delay(25); true }
        } catch (_: TimeoutCancellationException) { false }
        catch (e: CancellationException) { killTree(process); throw e }
        if (!finished) { killTree(process); runCatching { process.waitFor(2, TimeUnit.SECONDS) } }
        outT.join(2_000); errT.join(2_000)
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(code, outBuf.toString(), errBuf.toString(), timedOut = !finished)
    }

    /** 取消/超时时杀掉整棵进程树（su/Shizuku 会 fork 子进程，单杀父不够）。 */
    private fun killTree(process: Process) {
        runCatching {
            val pid = process.pid()
            if (pid > 0) {
                Runtime.getRuntime().exec(arrayOf(
                    "/system/bin/sh", "-c",
                    "pkill -9 -P $pid 2>/dev/null; kill -9 -$pid 2>/dev/null; kill -9 $pid 2>/dev/null"
                )).waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }
        runCatching { process.destroyForcibly() }
    }

    private fun daemonReader(stream: InputStream, buf: StringBuilder): Thread {
        val t = Thread {
            runCatching {
                stream.bufferedReader().use { r ->
                    val c = CharArray(4096)
                    while (true) { val n = r.read(c); if (n < 0) break; synchronized(buf) { buf.append(c, 0, n) } }
                }
            }
        }
        t.isDaemon = true; t.start(); return t
    }

    suspend fun fileExists(path: String): Boolean =
        execute("test -e ${path.shellQuote()} && echo YES || echo NO", mode = preferredMode()).stdout.trim() == "YES"

    suspend fun listDirectory(path: String): List<FileEntry> {
        val r = execute("ls -la ${path.shellQuote()} 2>/dev/null | tail -n +2", mode = ShellMode.NORMAL)
        if (!r.isSuccess) return emptyList()
        return r.stdout.lineSequence().filter { it.isNotBlank() }.mapNotNull { parseLsLine(it, path) }.toList()
    }
}

data class FileEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long, val path: String)

private fun parseLsLine(line: String, basePath: String): FileEntry? {
    if (line.length < 40) return null
    val type = line.firstOrNull() ?: return null
    val name = line.substringAfterLast(" ").trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    val size = line.split(" ").filter { it.isNotBlank() }.getOrNull(4)?.toLongOrNull() ?: 0
    return FileEntry(name, type == 'd', size, if (basePath.endsWith("/")) "$basePath$name" else "$basePath/$name")
}

/** POSIX 单引号转义 */
fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
