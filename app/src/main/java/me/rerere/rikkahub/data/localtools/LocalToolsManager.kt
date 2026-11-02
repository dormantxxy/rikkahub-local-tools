package me.rerere.rikkahub.data.localtools

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellContext

private const val TAG = "LocalToolsManager"

/** 工具下载进度 */
data class ToolDownloadProgress(
    val toolType: LocalToolType,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val finished: Boolean = false,
)

/**
 * 本地 CLI 工具管理器。
 *
 * Android 上没有独立的 git/python/ssh 二进制可以直接跑，这里用 proot + Alpine rootfs：
 *  - 首次启用任一工具时，下载 Alpine minirootfs（真实镜像，可下载）
 *  - 再用 apk 安装对应包（git / openssh / python3）
 *  - 工具执行统一走 proot 环境
 */
class LocalToolsManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
) {
    companion object {
        private const val ROOTFS_ROOT = "localtools"
        private const val ALPINE_URL =
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
        private const val ALPINE_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/alpine"
    }

    private val workspaceManager = WorkspaceManager(File(context.filesDir, "localtools_ws"))
    private val rootfsInstaller = RootfsInstaller(workspaceManager)
    private val prootRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir))

    private val _toolsState = MutableStateFlow<Map<LocalToolType, LocalToolInfo>>(emptyMap())
    val toolsState: StateFlow<Map<LocalToolType, LocalToolInfo>> = _toolsState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<LocalToolType, ToolDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<LocalToolType, ToolDownloadProgress>> = _downloadProgress.asStateFlow()

    // ---------------- rootfs ----------------

    fun isRootfsInstalled(): Boolean =
        WorkspaceManager.isUsableRootfs(workspaceManager.linuxDir(ROOTFS_ROOT))

    private suspend fun ensureRootfs(onProgress: (RootfsInstallProgress) -> Unit) {
        if (isRootfsInstalled()) return
        workspaceManager.ensureWorkspace(ROOTFS_ROOT)
        withContext(Dispatchers.IO) {
            rootfsInstaller.install(ROOTFS_ROOT, ALPINE_URL) { p -> onProgress(p) }
        }
    }

    /** 在 rootfs（proot）里执行命令 */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        if (!isRootfsInstalled()) {
            return ShellResult(-1, "", "命令行环境(rootfs)未安装，请先在「本地工具」里下载")
        }
        val ctx = WorkspaceShellContext(
            root = ROOTFS_ROOT,
            command = command,
            cwd = "/root",
            filesDir = workspaceManager.filesDir(ROOTFS_ROOT),
            linuxDir = workspaceManager.linuxDir(ROOTFS_ROOT),
            tempDir = workspaceManager.tempDir(ROOTFS_ROOT),
            workingDir = workspaceManager.filesDir(ROOTFS_ROOT),
            timeoutMillis = timeoutMillis,
            bindMounts = listOf(
                WorkspaceBindMount(File("/storage"), "/storage", writable = true),
                WorkspaceBindMount(File("/sdcard"), "/sdcard", writable = true),
            ),
        )
        return withContext(Dispatchers.IO) {
            runCatching { prootRunner.execute(ctx) }
                .map { ShellResult(it.exitCode, it.stdout, it.stderr, it.timedOut) }
                .getOrElse { ShellResult(-1, "", it.message ?: "proot 执行失败") }
        }
    }

    // ---------------- 工具状态 ----------------

    suspend fun refreshTools() {
        val config = settingsStore.settingsFlow.value.localToolsConfig
        if (!isRootfsInstalled()) {
            _toolsState.value = LocalToolType.entries.associateWith { t ->
                LocalToolInfo(t, isEnabled(config, t), LocalToolStatus.NOT_INSTALLED)
            }
            return
        }
        // 一条命令批量检测，避免多次 proot 启动
        val probe = runInRootfs(
            "for b in git ssh python3; do if command -v \$b >/dev/null 2>&1; then echo \"\$b=\$(\$b --version 2>&1 | head -1)\"; fi; done",
            timeoutMillis = 30_000,
        )
        val found = mutableMapOf<String, String>()
        probe.stdout.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                found[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        _toolsState.value = LocalToolType.entries.associateWith { t ->
            val bin = binaryName(t)
            val version = found[bin]
            LocalToolInfo(
                type = t,
                enabled = isEnabled(config, t),
                status = if (version != null) LocalToolStatus.INSTALLED else LocalToolStatus.NOT_INSTALLED,
                version = version ?: "",
                executablePath = if (version != null) "/usr/bin/$bin" else null,
            )
        }
    }

    private fun isEnabled(config: LocalToolsConfig, type: LocalToolType): Boolean = when (type) {
        LocalToolType.GIT -> config.gitEnabled
        LocalToolType.SSH -> config.sshEnabled
        LocalToolType.PYTHON -> config.pythonEnabled
    }

    private fun binaryName(type: LocalToolType): String = when (type) {
        LocalToolType.GIT -> "git"
        LocalToolType.SSH -> "ssh"
        LocalToolType.PYTHON -> "python3"
    }

    private fun packageName(type: LocalToolType): String = when (type) {
        LocalToolType.GIT -> "git"
        LocalToolType.SSH -> "openssh-client"
        LocalToolType.PYTHON -> "python3"
    }

    // ---------------- 启停/安装 ----------------

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
        if (enabled) {
            val installed = _toolsState.value[type]?.status == LocalToolStatus.INSTALLED
            if (!installed) installTool(type)
        }
        refreshTools()
    }

    suspend fun installTool(type: LocalToolType) {
        _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "准备下载...")) }
        try {
            // 1) rootfs（若未安装）
            if (!isRootfsInstalled()) {
                ensureRootfs { p ->
                    val msg = when (p.stage) {
                        RootfsInstallStage.DOWNLOADING -> "下载系统环境..."
                        RootfsInstallStage.EXTRACTING -> "解压系统环境..."
                        RootfsInstallStage.INSTALLED -> "系统环境就绪"
                    }
                    _downloadProgress.update {
                        it + (type to ToolDownloadProgress(type, p.bytesRead, p.totalBytes, msg))
                    }
                }
            }
            // 2) apk 安装包
            _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "安装 ${packageName(type)}...")) }
            val pkg = packageName(type)
            val result = runInRootfs(
                "sed -i 's|https://dl-cdn.alpinelinux.org/alpine|$ALPINE_MIRROR|g' /etc/apk/repositories 2>/dev/null; " +
                    "apk update >/dev/null 2>&1; apk add --no-cache $pkg 2>&1",
                timeoutMillis = 900_000,
            )
            if (result.exitCode == 0) {
                _downloadProgress.update {
                    it + (type to ToolDownloadProgress(type, message = "安装完成", finished = true))
                }
            } else {
                _downloadProgress.update {
                    it + (type to ToolDownloadProgress(type, message = "安装失败: ${result.output.take(400)}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "installTool failed: $type", e)
            _downloadProgress.update {
                it + (type to ToolDownloadProgress(type, message = "失败: ${e.message}"))
            }
        } finally {
            refreshTools()
        }
    }

    suspend fun uninstallTool(type: LocalToolType) {
        if (isRootfsInstalled()) {
            runInRootfs("apk del ${packageName(type)} 2>&1", timeoutMillis = 180_000)
        }
        setToolEnabled(type, false)
        refreshTools()
    }

    // ---------------- 其他 ----------------

    /** 设备 PATH 前缀（CLI 工具走 proot，这里保留给设备侧调用） */
    fun pathPrefix(): String = ""

    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()

    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
}
