package me.rerere.rikkahub.data.localtools

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "LocalToolsManager"

/** 工具下载进度（兼容旧 UI） */
data class ToolDownloadProgress(
    val toolType: LocalToolType,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val finished: Boolean = false,
)

/** 完整 Linux 环境安装状态 */
data class LinuxEnvState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val error: String? = null,
)

/** 单个扩展的 UI 状态 */
data class ExtensionState(
    val id: String,
    val installed: Boolean = false,
    val installing: Boolean = false,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val error: String? = null,
)

/**
 * 本地工具扩展管理器（与 MobiCode Studio 相同思路，不依赖 rootfs/proot）：
 *  - 扩展包在线下载（GitHub Release + 国内镜像，多源回退），APK 内不内置任何工具
 *  - 解压到 app 私有目录 files/extensions/<id>-<version>/payload
 *  - 执行 payload/bin/xxx（wrapper 自定位 PREFIX）
 */
class LocalToolsManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
) {
    private val extensionsRoot: File
        get() = File(context.filesDir, "extensions").apply { mkdirs() }

    private val _states = MutableStateFlow<Map<String, ExtensionState>>(emptyMap())
    val extensionStates: StateFlow<Map<String, ExtensionState>> = _states.asStateFlow()

    // ---- 旧 API 兼容 ----
    private val _toolsState = MutableStateFlow<Map<LocalToolType, LocalToolInfo>>(emptyMap())
    val toolsState: StateFlow<Map<LocalToolType, LocalToolInfo>> = _toolsState.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Map<LocalToolType, ToolDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<LocalToolType, ToolDownloadProgress>> = _downloadProgress.asStateFlow()

    // ---------------- 路径 ----------------

    private fun versionDir(spec: ToolExtension): File = File(extensionsRoot, "${spec.id}-${spec.version}")
    private fun payloadDir(spec: ToolExtension): File = File(versionDir(spec), "payload")
    fun binFile(spec: ToolExtension): File = File(payloadDir(spec), spec.binRel)

    fun isInstalled(spec: ToolExtension): Boolean = binFile(spec).exists()

    /** 已安装的扩展 */
    fun installedExtensions(): List<ToolExtension> = TOOL_EXTENSIONS.filter { isInstalled(it) }

    /** 是否有任意可用工具（供 AI 工具判断） */
    fun isRootfsInstalled(): Boolean = installedExtensions().isNotEmpty()

    /** 所有已装扩展的 bin 目录，拼成 PATH 前缀 */
    fun pathPrefix(): String = installedExtensions()
        .map { binFile(it).parentFile!!.absolutePath }
        .distinct()
        .joinToString(":")

    // ---------------- 状态 ----------------

    suspend fun refreshPackages() {
        val map = TOOL_EXTENSIONS.associate { spec ->
            val old = _states.value[spec.id]
            spec.id to (old?.copy(installed = isInstalled(spec), installing = old.installing) ?: ExtensionState(
                id = spec.id,
                installed = isInstalled(spec),
            ))
        }
        _states.value = map

        val config = settingsStore.settingsFlow.value.localToolsConfig
        _toolsState.value = LocalToolType.entries.associateWith { t ->
            val bin = when (t) {
                LocalToolType.GIT -> "git"
                LocalToolType.SSH -> "ssh"
                LocalToolType.PYTHON -> "python3"
            }
            LocalToolInfo(
                type = t,
                enabled = isEnabled(config, t),
                status = if (installedExtensions().any { it.bin == bin }) LocalToolStatus.INSTALLED else LocalToolStatus.NOT_INSTALLED,
            )
        }
    }

    private fun isEnabled(config: LocalToolsConfig, type: LocalToolType): Boolean = when (type) {
        LocalToolType.GIT -> config.gitEnabled
        LocalToolType.SSH -> config.sshEnabled
        LocalToolType.PYTHON -> config.pythonEnabled
    }

    // ---------------- 安装/卸载 ----------------

    suspend fun installExtension(spec: ToolExtension) {
        if (isInstalled(spec)) return
        _states.update { it + (spec.id to ExtensionState(spec.id, false, true, 0, null, "准备下载...")) }
        try {
            val archive = downloadWithMirrors(spec)
                ?: throw IllegalStateException("所有镜像下载失败")
            _states.update { it + (spec.id to (_states.value[spec.id] ?: ExtensionState(spec.id)).copy(message = "解压中...")) }
            extract(spec, archive)
            archive.delete()
            _states.update { it + (spec.id to ExtensionState(spec.id, true, false, message = "已安装")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installExtension failed: ${spec.id}", e)
            _states.update {
                it + (spec.id to ExtensionState(spec.id, false, false, error = e.message ?: "安装失败"))
            }
        } finally {
            refreshPackages()
        }
    }

    suspend fun removeExtension(spec: ToolExtension) {
        versionDir(spec).deleteRecursively()
        refreshPackages()
    }

    /** 按显示名安装（旧 API 用） */
    suspend fun installPackage(label: String) {
        val spec = TOOL_EXTENSIONS.firstOrNull { it.name == label } ?: return
        installExtension(spec)
    }

    suspend fun removePackage(label: String) {
        val spec = TOOL_EXTENSIONS.firstOrNull { it.name == label } ?: return
        removeExtension(spec)
    }

    // ---------------- 下载 ----------------

    private suspend fun downloadWithMirrors(spec: ToolExtension): File? = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, ExtensionMirrors.assetName(spec))
        for (mirror in ExtensionMirrors.MIRRORS) {
            val url = ExtensionMirrors.url(spec, mirror)
            val ok = runCatching { downloadOne(spec, url, archive) }.getOrElse {
                Log.w(TAG, "mirror failed: $mirror", it)
                false
            }
            if (ok && archive.length() > 1024) return@withContext archive
            archive.delete()
        }
        null
    }

    private fun downloadTo(url: String, target: File, onProgress: (Long, Long?) -> Unit): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code !in 200..299) return false
            val total = conn.contentLengthLong.takeIf { it > 0 }
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadOne(spec: ToolExtension, url: String, target: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url")
                return false
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        _states.update {
                            it + (spec.id to (_states.value[spec.id] ?: ExtensionState(spec.id)).copy(
                                installing = true, bytesRead = read, totalBytes = total, message = "下载中..."
                            ))
                        }
                    }
                }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun extract(spec: ToolExtension, archive: File) {
        val dir = versionDir(spec)
        dir.deleteRecursively()
        dir.mkdirs()
        val r = shellExecutor.execute(
            "tar -xzf ${archive.absolutePath.shellQuote()} -C ${dir.absolutePath.shellQuote()} 2>&1",
            timeoutMillis = 300_000,
            mode = ShellMode.NORMAL,
        )
        if (r.exitCode != 0) throw IllegalStateException("解压失败: ${r.output.take(300)}")
        // 赋予可执行权限
        shellExecutor.execute("chmod -R 755 ${dir.absolutePath.shellQuote()}", mode = ShellMode.NORMAL)
    }

    // ---------------- 执行 ----------------

    // ---------------- 完整 Linux 环境（Alpine + 内置 proot） ----------------

    private val alpineDir: File get() = File(context.filesDir, "alpine")
    private val prootBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")
    private val prootLoader: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")

    fun isLinuxEnvInstalled(): Boolean = File(alpineDir, "bin/sh").exists()

    private val _linuxEnv = MutableStateFlow(LinuxEnvState())
    val linuxEnv: StateFlow<LinuxEnvState> = _linuxEnv.asStateFlow()
    private val _linuxPackages = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val linuxPackages: StateFlow<Map<String, Boolean>> = _linuxPackages.asStateFlow()
    private val _installingLinuxPkg = MutableStateFlow<Set<String>>(emptySet())
    val installingLinuxPkg: StateFlow<Set<String>> = _installingLinuxPkg.asStateFlow()
    private val _linuxPkgError = MutableStateFlow<String?>(null)
    val linuxPkgError: StateFlow<String?> = _linuxPkgError.asStateFlow()

    fun dismissLinuxErrors() {
        _linuxEnv.update { it.copy(error = null) }
        _linuxPkgError.value = null
    }

    /** 安装完整 Linux 环境（Alpine 3.9MB + APK 内置 proot，不需要工作区） */
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
                _linuxEnv.update { it.copy(message = "下载中（${url.substringAfter("//").substringBefore("/")}）...") }
                val r = shellExecutor.execute(
                    "/system/bin/curl -fL --connect-timeout 20 --max-time 600 " +
                        "-o ${archive.absolutePath.shellQuote()} ${url.shellQuote()} 2>&1; " +
                        "chmod 644 ${archive.absolutePath.shellQuote()} 2>/dev/null; " +
                        "wc -c < ${archive.absolutePath.shellQuote()} 2>/dev/null",
                    timeoutMillis = 660_000, mode = mode,
                )
                if (archive.exists() && archive.length() > 100_000) { ok = true; break }
                lastErr = r.output.take(200)
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
            if (r.exitCode != 0) throw IllegalStateException("解压失败: ${r.output.take(200)}")
            File(alpineDir, "etc/apk/repositories").writeText(
                "${LinuxEnv.APK_MAIN}\n${LinuxEnv.APK_COMMUNITY}\n"
            )
            archive.delete()
            _linuxEnv.value = LinuxEnvState(installed = true, message = "已安装")
            refreshLinuxPackages()
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

    suspend fun installLinuxPackage(pkg: String) {
        val mode = shellExecutor.preferredMode()
        if (mode == null) { _linuxPkgError.value = "需要 root 或 Shizuku"; return }
        if (!isLinuxEnvInstalled()) { _linuxPkgError.value = "请先安装完整 Linux 环境"; return }
        _installingLinuxPkg.update { it + pkg }
        _linuxPkgError.value = null
        try {
            val r = runInLinux("apk update >/dev/null 2>&1; apk add --no-cache $pkg 2>&1", 1_800_000, mode)
            if (r.exitCode != 0) _linuxPkgError.value = "安装 $pkg 失败: ${r.output.take(400)}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _linuxPkgError.value = "安装 $pkg 失败: ${e.message}"
        } finally {
            _installingLinuxPkg.update { it - pkg }
            refreshLinuxPackages()
        }
    }

    suspend fun refreshLinuxPackages() {
        if (!isLinuxEnvInstalled()) {
            _linuxPackages.value = LINUX_PACKAGES.associate { it.pkg to false }
            return
        }
        val mode = shellExecutor.preferredMode() ?: return
        val bins = LINUX_PACKAGES.map { it.bin }.distinct()
        val r = runInLinux(
            "for b in ${bins.joinToString(" ")}; do command -v \$b >/dev/null 2>&1 && echo \"\$b=OK\"; done",
            60_000, mode,
        )
        val found = r.stdout.lineSequence().filter { it.contains("=OK") }
            .map { it.substringBefore('=').trim() }.toSet()
        _linuxPackages.value = LINUX_PACKAGES.associate { it.pkg to found.contains(it.bin) }
    }

    /** 在 proot(Alpine) 里执行命令 */
    private suspend fun runInLinux(command: String, timeoutMillis: Long, mode: ShellMode): ShellResult {
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val inner = "export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 GIT_TERMINAL_PROMPT=0; $command"
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

    // ---------------- 执行（供 AI 工具） ----------------

    /**
     * 执行 CLI 命令：
     *  - 只有 root / shizuku 可用（不再支持普通权限）
     *  - 优先走完整 Linux 环境（任意包），否则用扩展包
     */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        val mode = shellExecutor.preferredMode()
            ?: return ShellResult(-1, "", "需要 root 或 Shizuku 权限（普通模式无法运行 CLI）")
        if (isLinuxEnvInstalled()) return runInLinux(command, timeoutMillis, mode)

        val installed = installedExtensions()
        if (installed.isEmpty()) {
            return ShellResult(-1, "", "请先在「本地工具」安装扩展包或完整 Linux 环境")
        }
        val pathPrefix = installed.map { binFile(it).parentFile!!.absolutePath }.distinct().joinToString(":") +
            ":/system/bin:/system/xbin"
        val wrapped = "export HOME=/data/local/tmp PATH=$pathPrefix LANG=C.UTF-8 GIT_TERMINAL_PROMPT=0 TMPDIR=/data/local/tmp; $command"
        return shellExecutor.execute(wrapped, cwd = null, timeoutMillis = timeoutMillis, mode = mode)
    }

    // ---------------- 旧 API 兼容 ----------------

    suspend fun refreshTools() = refreshPackages()

    suspend fun setToolEnabled(type: LocalToolType, enabled: Boolean) {
        settingsStore.update { s ->
            s.copy(
                localToolsConfig = s.localToolsConfig.copy(
                    gitEnabled = if (type == LocalToolType.GIT) enabled else s.localToolsConfig.gitEnabled,
                    sshEnabled = if (type == LocalToolType.SSH) enabled else s.localToolsConfig.sshEnabled,
                    pythonEnabled = if (type == LocalToolType.PYTHON) enabled else s.localToolsConfig.pythonEnabled,
                )
            )
        }
        refreshPackages()
    }

    suspend fun installTool(type: LocalToolType) {
        val label = when (type) {
            LocalToolType.GIT -> "Git"
            LocalToolType.SSH -> "OpenSSH"
            LocalToolType.PYTHON -> "Python 3"
        }
        installPackage(label)
    }

    suspend fun uninstallTool(type: LocalToolType) {
        val label = when (type) {
            LocalToolType.GIT -> "Git"
            LocalToolType.SSH -> "OpenSSH"
            LocalToolType.PYTHON -> "Python 3"
        }
        removePackage(label)
    }

    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()

    /** 是否有执行权限（root 或 shizuku） */
    fun hasPrivilege(): Boolean = shellExecutor.hasPrivilege()
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
}
