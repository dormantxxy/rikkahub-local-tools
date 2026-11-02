package me.rerere.rikkahub.data.localtools

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "LocalToolsManager"

/** 完整 Linux 环境安装状态 */
data class LinuxEnvState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val message: String = "",
    val error: String? = null,
)

/**
 * 本地 CLI 环境：Alpine + APK 内置 proot。
 *
 * - 只走公共镜像在线下载（阿里云/清华/官方），不需要任何自托管资源
 * - 装好后 `apk add` 任意软件包（git/python/node/gcc/ffmpeg…）
 * - 只支持 root / shizuku 执行
 */
class LocalToolsManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
) {
    private val alpineDir: File get() = File(context.filesDir, "alpine")
    private val prootBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")
    private val prootLoader: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")

    fun isLinuxEnvInstalled(): Boolean = File(alpineDir, "bin/sh").exists()

    /** 兼容旧调用 */
    fun isRootfsInstalled(): Boolean = isLinuxEnvInstalled()

    private val _linuxEnv = MutableStateFlow(LinuxEnvState(installed = isLinuxEnvInstalled()))
    val linuxEnv: StateFlow<LinuxEnvState> = _linuxEnv.asStateFlow()

    private val _bundleStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val bundleStates: StateFlow<Map<String, Boolean>> = _bundleStates.asStateFlow()

    private val _installingBundles = MutableStateFlow<Set<String>>(emptySet())
    val installingBundles: StateFlow<Set<String>> = _installingBundles.asStateFlow()

    private val _linuxPkgError = MutableStateFlow<String?>(null)
    val linuxPkgError: StateFlow<String?> = _linuxPkgError.asStateFlow()

    fun dismissErrors() {
        _linuxEnv.update { it.copy(error = null) }
        _linuxPkgError.value = null
    }

    /** 安装完整 Linux 环境（3.9MB，用系统 curl 下载，多镜像回退） */
    suspend fun installLinuxEnv() {
        val mode = shellExecutor.preferredMode()
        if (mode == null) {
            _linuxEnv.value = LinuxEnvState(error = "需要 root 或 Shizuku 权限")
            return
        }
        _linuxEnv.value = LinuxEnvState(installing = true, message = "下载中...")
        try {
            val archive = File(context.cacheDir, "alpine-${LinuxEnv.VERSION}.tar.gz")
            archive.delete()
            var ok = false
            var lastErr = ""
            for (url in LinuxEnv.MIRRORS) {
                val host = url.substringAfter("//").substringBefore("/")
                _linuxEnv.update { it.copy(message = "下载中（$host）...") }
                val r = shellExecutor.execute(
                    "/system/bin/curl -fL --connect-timeout 20 --max-time 600 " +
                        "-o ${archive.absolutePath.shellQuote()} ${url.shellQuote()} 2>&1; " +
                        "chmod 644 ${archive.absolutePath.shellQuote()} 2>/dev/null",
                    timeoutMillis = 660_000, mode = mode,
                )
                if (archive.exists() && archive.length() > 100_000) { ok = true; break }
                lastErr = r.output.take(300)
                archive.delete()
            }
            if (!ok) throw IllegalStateException("下载失败：$lastErr")

            _linuxEnv.update { it.copy(message = "解压中...") }
            alpineDir.deleteRecursively()
            alpineDir.mkdirs()
            val r = shellExecutor.execute(
                "tar -xzf ${archive.absolutePath.shellQuote()} -C ${alpineDir.absolutePath.shellQuote()} 2>&1",
                timeoutMillis = 300_000, mode = mode,
            )
            if (r.exitCode != 0) throw IllegalStateException("解压失败：${r.output.take(200)}")

            // 写入国内 apk 源
            File(alpineDir, "etc/apk/repositories").writeText(LinuxEnv.APK_REPOS.joinToString("\n") + "\n")
            archive.delete()
            _linuxEnv.value = LinuxEnvState(installed = true, message = "已安装")
            refreshBundles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installLinuxEnv failed", e)
            _linuxEnv.value = LinuxEnvState(error = e.message ?: "安装失败")
        }
    }

    suspend fun removeLinuxEnv() {
        val mode = shellExecutor.preferredMode() ?: return
        shellExecutor.execute("rm -rf ${alpineDir.absolutePath.shellQuote()}", mode = mode)
        _linuxEnv.value = LinuxEnvState()
        _linuxPackages.value = emptyMap()
    }

    /** 安装一组工具（apk add 整组包） */
    suspend fun installBundle(bundleId: String) {
        val bundle = CLI_BUNDLES.firstOrNull { it.id == bundleId } ?: return
        val mode = shellExecutor.preferredMode()
        if (mode == null) { _linuxPkgError.value = "需要 root 或 Shizuku"; return }
        if (!isLinuxEnvInstalled()) { _linuxPkgError.value = "请先安装完整 Linux 环境（3.9MB）"; return }
        _installingBundles.update { it + bundleId }
        _linuxPkgError.value = null
        try {
            val pkgs = bundle.pkgs.joinToString(" ")
            val r = runInLinux("apk update >/dev/null 2>&1; apk add --no-cache $pkgs 2>&1", 1_800_000, mode)
            if (r.exitCode != 0) _linuxPkgError.value = "安装 ${bundle.label} 失败：${r.output.take(400)}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _linuxPkgError.value = "安装 ${bundle.label} 失败：${e.message}"
        } finally {
            _installingBundles.update { it - bundleId }
            refreshBundles()
        }
    }

    /** 安装任意单个包（自定义） */
    suspend fun installRawPackage(pkg: String) {
        val mode = shellExecutor.preferredMode()
        if (mode == null) { _linuxPkgError.value = "需要 root 或 Shizuku"; return }
        if (!isLinuxEnvInstalled()) { _linuxPkgError.value = "请先安装完整 Linux 环境（3.9MB）"; return }
        _installingBundles.update { it + pkg }
        _linuxPkgError.value = null
        try {
            val r = runInLinux("apk update >/dev/null 2>&1; apk add --no-cache $pkg 2>&1", 1_800_000, mode)
            if (r.exitCode != 0) _linuxPkgError.value = "安装 $pkg 失败：${r.output.take(400)}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _linuxPkgError.value = "安装 $pkg 失败：${e.message}"
        } finally {
            _installingBundles.update { it - pkg }
            refreshBundles()
        }
    }

    suspend fun refreshBundles() {
        if (!isLinuxEnvInstalled()) {
            _bundleStates.value = CLI_BUNDLES.associate { it.id to false }
            _linuxEnv.update { it.copy(installed = false) }
            return
        }
        _linuxEnv.update { it.copy(installed = true) }
        val mode = shellExecutor.preferredMode() ?: return
        val probes = CLI_BUNDLES.map { it.probe }.distinct()
        val r = runInLinux(
            "for b in ${probes.joinToString(" ")}; do command -v \$b >/dev/null 2>&1 && echo \"\$b=OK\"; done",
            60_000, mode,
        )
        val found = r.stdout.lineSequence().filter { it.contains("=OK") }
            .map { it.substringBefore('=').trim() }.toSet()
        _bundleStates.value = CLI_BUNDLES.associate { it.id to found.contains(it.probe) }
    }

    /** 在 proot(Alpine) 里执行 */
    private suspend fun runInLinux(command: String, timeoutMillis: Long, mode: ShellMode): ShellResult {
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val inner = "export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "LANG=C.UTF-8 GIT_TERMINAL_PROMPT=0; $command"
        val cmd = buildString {
            append("export PROOT_LOADER=${prootLoader.absolutePath.shellQuote()} ")
            append("PROOT_TMP_DIR=${tmp.absolutePath.shellQuote()} TMPDIR=${tmp.absolutePath.shellQuote()} PROOT_NO_SECCOMP=1; ")
            append(prootBin.absolutePath.shellQuote())
            append(" -r ${alpineDir.absolutePath.shellQuote()} --link2symlink -0")
            append(" -b /dev -b /proc -b /sys -b /storage:/storage")
            append(" -b ${context.filesDir.absolutePath.shellQuote()}:/appfiles")
            append(" -b /data/local/tmp:/tmp")
            append(" -w /root /bin/sh -c ${inner.shellQuote()}")
        }
        return shellExecutor.execute(cmd, timeoutMillis = timeoutMillis, mode = mode)
    }

    /** 供 AI 工具调用：在完整 Linux 环境里执行命令 */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        val mode = shellExecutor.preferredMode()
            ?: return ShellResult(-1, "", "需要 root 或 Shizuku 权限")
        if (!isLinuxEnvInstalled()) {
            return ShellResult(-1, "", "请先在「本地工具」安装完整 Linux 环境（3.9MB）")
        }
        return runInLinux(command, timeoutMillis, mode)
    }

    fun hasPrivilege(): Boolean = shellExecutor.hasPrivilege()
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()
}
