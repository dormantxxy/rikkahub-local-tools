package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File

/**
 * 聚合后的一体化"原生工具链环境"。
 * 把所有已装扩展的 payload/bin 汇总成 PATH、payload/lib 汇总成 LD_LIBRARY_PATH。
 * 工作目录/ HOME 统一放到 files/RikkaHub 下（RikkaHub 作为工作目录）。
 */
data class ToolchainEnv(
    val path: String,
    val ldLibraryPath: String,
    val home: String,
    val tmp: String,
    val workDir: String,
    val binDirs: List<String>,
    val libDirs: List<String>,
    // 每个扩展自动追加的环境变量（来自 payload/bin 下 wrapper 脚本的 export 声明）
    val envVars: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = binDirs.isEmpty() && libDirs.isEmpty()
}

object ToolchainEnvironment {
    const val HOST_PATH =
        "/system/bin:/system/xbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"

    /** RikkaHub 工作目录（默认 CLI 工作目录）。 */
    fun workDir(context: Context): File =
        File(context.filesDir, "RikkaHub").apply { mkdirs() }

    /**
     * MobiCode Studio 同款基础运行时环境变量（RUNTIME_VARIABLE_ORDER，共 10 个）：
     * HOME / TMPDIR / GOTMPDIR / GOCACHE / GOMODCACHE / CARGO_HOME /
     * MOBICODE_PROJECT_ROOT / MOBICODE_EXEC_DIR / GIT_CONFIG_NOSYSTEM / GIT_CONFIG_GLOBAL。
     */
    fun baseEnvVars(context: Context): Map<String, String> {
        val base = workDir(context)
        val home = File(base, ".home").apply { mkdirs() }
        val tmp = File(base, ".tmp").apply { mkdirs() }
        fun sub(name: String): String = File(tmp, name).apply { mkdirs() }.absolutePath
        val gitConfig = File(base, ".mobicode/gitconfig").apply {
            parentFile?.mkdirs()
            if (!exists()) {
                runCatching {
                    writeText("[init]\n    defaultBranch = main\n[core]\n    autocrlf = false\n[safe]\n    directory = *\n")
                }
            }
        }
        return linkedMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "GOTMPDIR" to sub("go-tmp"),
            "GOCACHE" to sub("go-cache"),
            "GOMODCACHE" to sub("go-mod-cache"),
            "CARGO_HOME" to sub("cargo-home"),
            "MOBICODE_PROJECT_ROOT" to base.absolutePath,
            "MOBICODE_EXEC_DIR" to base.absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to gitConfig.absolutePath,
        )
    }

    fun build(context: Context, installer: ExtensionInstaller): ToolchainEnv {
        val base = workDir(context)
        val home = File(base, ".home").apply { mkdirs() }
        val tmp = File(base, ".tmp").apply { mkdirs() }
        // 新安装的扩展优先（同名命令以最新安装的为准，如 termux.git 覆盖旧 mobicode git）
        val records = installer.installed.value.values.sortedByDescending { it.installedAt }

        // 聚合所有已装扩展的 PATH / LD_LIBRARY_PATH / CPATH（照搬 MobiCode enabledEnvironment*Directories）
        val binDirs = records.flatMap { rec ->
            rec.envVars["PATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        }.distinct()
        val libDirs = records.flatMap { rec ->
            rec.envVars["LD_LIBRARY_PATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        }.distinct()
        val includeDirs = records.flatMap { rec ->
            rec.envVars["CPATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        }.distinct()

        // 最终环境变量 = 基础 10 个 + 全局聚合 + 各扩展的独立变量
        val envVars = LinkedHashMap<String, String>()
        envVars.putAll(baseEnvVars(context))
        envVars["PATH"] = (binDirs + HOST_PATH).joinToString(":")
        if (libDirs.isNotEmpty()) {
            envVars["LD_LIBRARY_PATH"] = libDirs.joinToString(":")
            envVars["LIBRARY_PATH"] = libDirs.joinToString(":")
        }
        if (includeDirs.isNotEmpty()) envVars["CPATH"] = includeDirs.joinToString(":")
        // 扩展自己的非聚合变量（OPENSSL_CONF / SSL_CERT_FILE / OPENSSL_MODULES / NODE_PATH / MOBICODE_SYSROOT）
        for (rec in records) {
            // Termux git 需要 GIT_EXEC_PATH 才能找到 git-remote-https 等 helper
            // 只取第一个（records 已按安装时间新→旧排序，优先最新 git）
            val gitExec = File(rec.payloadDir, "libexec/git-core")
            if (gitExec.isDirectory && envVars["GIT_EXEC_PATH"].isNullOrBlank()) {
                envVars["GIT_EXEC_PATH"] = gitExec.absolutePath
            }
            rec.envVars.forEach { (k, v) ->
                if (k != "PATH" && k != "LD_LIBRARY_PATH" && k != "LIBRARY_PATH" && k != "CPATH") {
                    // SSL_CERT_FILE 优先取 ca-certificates 的证书，避免被其他扩展覆盖
                    if (k == "SSL_CERT_FILE" && !envVars["SSL_CERT_FILE"].isNullOrBlank()) {
                        return@forEach
                    }
                    envVars[k] = v
                }
            }
        }
        // 优先使用 ca-certificates 的证书（即使其他扩展自带 cert，也用它保证 HTTPS 兼容）
        val caCert = File(context.filesDir, "extensions/termux.ca.certificates")
            .listFiles()?.asSequence()
            ?.map { File(it, "payload/etc/tls/cert.pem") }
            ?.firstOrNull { it.isFile }
        if (caCert != null) envVars["SSL_CERT_FILE"] = caCert.absolutePath

        return ToolchainEnv(
            path = envVars["PATH"] ?: HOST_PATH,
            ldLibraryPath = envVars["LD_LIBRARY_PATH"] ?: "",
            home = home.absolutePath,
            tmp = tmp.absolutePath,
            workDir = base.absolutePath,
            binDirs = binDirs,
            libDirs = libDirs,
            envVars = envVars,
        )
    }

}
