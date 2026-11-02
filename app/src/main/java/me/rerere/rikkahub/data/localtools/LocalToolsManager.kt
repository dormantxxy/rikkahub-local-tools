package me.rerere.rikkahub.data.localtools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository

private const val TAG = "LocalToolsManager"

/** 工具下载进度（兼容旧 UI） */
data class ToolDownloadProgress(
    val toolType: LocalToolType,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val finished: Boolean = false,
)

/**
 * 本地 CLI 环境管理器。
 *
 * 直接复用「工作区」里已安装的 rootfs（proot），不再单独下载一份：
 *  - 找不到已装 rootfs 的本地工作区时，提示用户去 设置 → 扩展 → 工作区 安装
 *  - 自动识别 Alpine(apk) / Debian-Ubuntu(apt)
 *  - 工具包安装/卸载都在该工作区 rootfs 内执行
 */
class LocalToolsManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository,
) {
    companion object {
        /** rootfs 内通用环境（让 git/pip/npm 等正常工作） */
        private const val BASE_ENV =
            "export HOME=/root; export LANG=C.UTF-8; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export GIT_TERMINAL_PROMPT=0;"
        private const val APT_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn"
        private const val APK_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/alpine"
    }

    // ---- 关联的工作区 ----
    private val _linkedWorkspaceId = MutableStateFlow<String?>(null)
    val linkedWorkspaceId: StateFlow<String?> = _linkedWorkspaceId.asStateFlow()

    private val _linkedWorkspaceName = MutableStateFlow<String?>(null)
    val linkedWorkspaceName: StateFlow<String?> = _linkedWorkspaceName.asStateFlow()

    private val _linkedRootFound = MutableStateFlow(false)

    /** 是否已找到一个装有 rootfs 的本地工作区 */
    private val _envReady = MutableStateFlow(false)
    val envReady: StateFlow<Boolean> = _envReady.asStateFlow()

    private val _distro = MutableStateFlow(RootfsDistro.UNKNOWN)
    val distro: StateFlow<RootfsDistro> = _distro.asStateFlow()

    // ---- 包状态 ----
    private val _packageStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val packageStates: StateFlow<Map<String, Boolean>> = _packageStates.asStateFlow()

    private val _installingPackages = MutableStateFlow<Set<String>>(emptySet())
    val installingPackages: StateFlow<Set<String>> = _installingPackages.asStateFlow()

    private val _packageMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val packageMessages: StateFlow<Map<String, String>> = _packageMessages.asStateFlow()

    private val _packageError = MutableStateFlow<String?>(null)
    val packageError: StateFlow<String?> = _packageError.asStateFlow()

    // 旧 UI 兼容
    private val _toolsState = MutableStateFlow<Map<LocalToolType, LocalToolInfo>>(emptyMap())
    val toolsState: StateFlow<Map<LocalToolType, LocalToolInfo>> = _toolsState.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Map<LocalToolType, ToolDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<LocalToolType, ToolDownloadProgress>> = _downloadProgress.asStateFlow()

    // ---------------- 环境 ----------------

    /** 同步判断（用缓存），供 AI 工具提示用；真正执行时会重新查找 */
    fun isRootfsInstalled(): Boolean = _envReady.value

    fun dismissPackageError() { _packageError.value = null }

    /** 刷新关联工作区 + 发行版 + 包状态 */
    suspend fun refreshPackages() {
        val ws = runCatching { workspaceRepository.findLocalRootfsWorkspace() }.getOrNull()
        _linkedWorkspaceId.value = ws?.id
        _linkedWorkspaceName.value = ws?.name
        _envReady.value = ws != null
        if (ws == null) {
            _linkedRootFound.value = false
            _distro.value = RootfsDistro.UNKNOWN
            _packageStates.value = ROOTFS_PACKAGES.associate { it.bin to false }
            _toolsState.value = LocalToolType.entries.associateWith { t ->
                LocalToolInfo(t, isEnabled(settingsStore.settingsFlow.value.localToolsConfig, t), LocalToolStatus.NOT_INSTALLED)
            }
            return
        }
        _linkedRootFound.value = true
        _distro.value = detectDistro(ws.id)
        val bins = ROOTFS_PACKAGES.map { it.bin }.distinct()
        val probe = execOn(ws.id, "for b in ${bins.joinToString(" ")}; do if command -v \$b >/dev/null 2>&1; then echo \"\$b=OK\"; fi; done", 40_000)
        val found = probe.stdout.lineSequence()
            .filter { it.contains("=OK") }
            .map { it.substringBefore('=').trim() }
            .toSet()
        _packageStates.value = ROOTFS_PACKAGES.associate { it.bin to found.contains(it.bin) }
        _toolsState.value = LocalToolType.entries.associateWith { t ->
            val bin = when (t) {
                LocalToolType.GIT -> "git"
                LocalToolType.SSH -> "ssh"
                LocalToolType.PYTHON -> "python3"
            }
            LocalToolInfo(
                type = t,
                enabled = isEnabled(settingsStore.settingsFlow.value.localToolsConfig, t),
                status = if (found.contains(bin)) LocalToolStatus.INSTALLED else LocalToolStatus.NOT_INSTALLED,
            )
        }
    }

    private fun isEnabled(config: LocalToolsConfig, type: LocalToolType): Boolean = when (type) {
        LocalToolType.GIT -> config.gitEnabled
        LocalToolType.SSH -> config.sshEnabled
        LocalToolType.PYTHON -> config.pythonEnabled
    }

    private suspend fun detectDistro(workspaceId: String): RootfsDistro {
        val r = execOn(
            workspaceId,
            "if command -v apk >/dev/null 2>&1; then echo alpine; elif command -v apt-get >/dev/null 2>&1; then echo debian; else echo unknown; fi",
            30_000,
        )
        return when (r.stdout.trim()) {
            "alpine" -> RootfsDistro.ALPINE
            "debian" -> RootfsDistro.DEBIAN
            else -> RootfsDistro.UNKNOWN
        }
    }

    private suspend fun execOn(workspaceId: String, command: String, timeoutMillis: Long): ShellResult {
        return runCatching {
            val r = workspaceRepository.executeCommand(workspaceId, "$BASE_ENV $command", cwd = "", timeoutMillis = timeoutMillis)
            ShellResult(r.exitCode, r.stdout, r.stderr, r.timedOut)
        }.getOrElse { ShellResult(-1, "", it.message ?: "执行失败") }
    }

    /** 在关联工作区的 rootfs 里执行命令（供 AI 的 run_cli_tool 使用） */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        val ws = runCatching { workspaceRepository.findLocalRootfsWorkspace() }.getOrNull()
            ?: return ShellResult(-1, "", "未找到已安装 rootfs 的工作区，请先在 设置→扩展→工作区 安装")
        return execOn(ws.id, command, timeoutMillis)
    }

    // ---------------- 包 ----------------

    suspend fun installPackage(pkgLabel: String) {
        val pkg = ROOTFS_PACKAGES.firstOrNull { it.label == pkgLabel } ?: return
        _packageError.value = null
        _installingPackages.update { it + pkgLabel }
        _packageMessages.update { it + (pkgLabel to "准备中...") }
        try {
            val ws = workspaceRepository.findLocalRootfsWorkspace()
            if (ws == null) {
                _packageError.value = "请先在 设置→扩展→工作区 安装 rootfs"
                return
            }
            val distro = detectDistro(ws.id)
            _distro.value = distro
            val name = pkg.pkgFor(distro)
            _packageMessages.update { it + (pkgLabel to "安装 $name ...") }
            val cmd = when (distro) {
                RootfsDistro.ALPINE ->
                    "sed -i 's|https://dl-cdn.alpinelinux.org/alpine|$APK_MIRROR|g' /etc/apk/repositories 2>/dev/null; " +
                        "apk update >/dev/null 2>&1; apk add --no-cache $name 2>&1"
                RootfsDistro.DEBIAN ->
                    "sed -i 's|http://archive.ubuntu.com|$APT_MIRROR|g; s|http://security.ubuntu.com|$APT_MIRROR|g' /etc/apt/sources.list 2>/dev/null; " +
                        "export DEBIAN_FRONTEND=noninteractive; apt-get update >/dev/null 2>&1; apt-get install -y --no-install-recommends $name 2>&1"
                RootfsDistro.UNKNOWN ->
                    "apk add --no-cache $name 2>&1"
            }
            val r = execOn(ws.id, cmd, 1_800_000)
            if (r.exitCode != 0) {
                _packageError.value = "安装 ${pkg.label} 失败：${r.output.take(500)}"
                _packageMessages.update { it + (pkgLabel to "安装失败") }
            } else {
                _packageMessages.update { it + (pkgLabel to "已安装") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installPackage failed: $pkgLabel", e)
            _packageError.value = "安装 $pkgLabel 失败：${e.message}"
        } finally {
            _installingPackages.update { it - pkgLabel }
            refreshPackages()
        }
    }

    suspend fun removePackage(pkgLabel: String) {
        val pkg = ROOTFS_PACKAGES.firstOrNull { it.label == pkgLabel } ?: return
        val ws = workspaceRepository.findLocalRootfsWorkspace() ?: return
        _installingPackages.update { it + pkgLabel }
        try {
            val distro = detectDistro(ws.id)
            val name = pkg.pkgFor(distro)
            val cmd = when (distro) {
                RootfsDistro.ALPINE -> "apk del $name 2>&1"
                RootfsDistro.DEBIAN -> "export DEBIAN_FRONTEND=noninteractive; apt-get remove -y $name 2>&1"
                RootfsDistro.UNKNOWN -> "apk del $name 2>&1"
            }
            execOn(ws.id, cmd, 300_000)
        } finally {
            _installingPackages.update { it - pkgLabel }
            refreshPackages()
        }
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

    fun pathPrefix(): String = ""
    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
}
