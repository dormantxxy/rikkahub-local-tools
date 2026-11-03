package me.rerere.rikkahub.shizuku

import android.content.Context
import android.os.Bundle
import android.os.Process as AndroidProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku（root）进程中的 Shell UserService。
 *
 * 与 MobiCode Studio 的 ShizukuShellService 相同：
 * - 由 [rikka.shizuku.Shizuku.bindUserService] 启动（app_process，继承 Shizuku 的 root uid）
 * - 通过 AIDL [IShizukuShellService] 接收命令并返回执行结果
 * - 该类不能在 manifest 声明，也不允许被 R8 移除/混淆（见 proguard-rules.pro）
 */
class ShizukuShellService(@Suppress("UNUSED_PARAMETER") context: Context) : IShizukuShellService.Stub() {

    companion object {
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_OUTPUT = "output"
        const val KEY_TIMED_OUT = "timedOut"
        const val EXIT_INVALID = -1
        const val EXIT_TIMEOUT = 124
        private const val MAX_OUTPUT_CHARS = 0x20000
        private const val DRAIN_JOIN_MILLIS = 2_000L
    }

    override fun getUid(): Int = AndroidProcess.myUid()

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(
        argv: List<String>,
        environment: List<String>,
        workingDirectory: String?,
        timeoutSeconds: Int,
    ): Bundle {
        val result = Bundle()
        var proc: java.lang.Process? = null
        try {
            val builder = ProcessBuilder(argv)
            if (!workingDirectory.isNullOrBlank()) {
                builder.directory(File(workingDirectory))
            }
            if (environment.isNotEmpty()) {
                val env = builder.environment()
                for (entry in environment) {
                    val index = entry.indexOf('=')
                    if (index > 0) {
                        env[entry.substring(0, index)] = entry.substring(index + 1)
                    }
                }
            }
            val p = builder.start()
            proc = p
            runCatching { p.outputStream.close() }

            val output = StringBuilder()
            val stdoutThread = Thread {
                drain(p.inputStream, output)
            }.apply { isDaemon = true; start() }
            val stderrThread = Thread {
                drain(p.errorStream, output)
            }.apply { isDaemon = true; start() }

            val finished = if (timeoutSeconds > 0) {
                p.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            } else {
                p.waitFor()
                true
            }
            if (!finished) {
                runCatching { p.destroyForcibly() }
            }
            stdoutThread.join(DRAIN_JOIN_MILLIS)
            stderrThread.join(DRAIN_JOIN_MILLIS)

            result.putBoolean(KEY_TIMED_OUT, !finished)
            result.putInt(KEY_EXIT_CODE, if (finished) runCatching { p.exitValue() }.getOrDefault(EXIT_INVALID) else EXIT_TIMEOUT)
            result.putString(KEY_OUTPUT, synchronized(output) { output.toString() })
        } catch (t: Throwable) {
            result.putBoolean(KEY_TIMED_OUT, false)
            result.putInt(KEY_EXIT_CODE, EXIT_INVALID)
            result.putString(KEY_OUTPUT, t.message ?: t.toString())
        } finally {
            runCatching { proc?.destroyForcibly() }
        }
        return result
    }

    private fun drain(input: java.io.InputStream, output: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8192).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    synchronized(output) {
                        if (output.length < MAX_OUTPUT_CHARS) {
                            output.append(buffer, 0, minOf(n, MAX_OUTPUT_CHARS - output.length))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 进程结束时流被关闭，忽略
        }
    }
}
