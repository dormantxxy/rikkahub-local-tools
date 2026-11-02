package me.rerere.rikkahub.data.localtools

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
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

/** 工具下载进度（兼容旧 UI） */
data class ToolDownloadProgress(
    val toolType: LocalToolType,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val finished: Boolean = false,
)

/**
 * 本地 CLI 环境管理器：proot + Alpine rootfs。
 *
 * 与 workspace 的 rootfs 安装体验一致：暴露 rootfsProgress / rootfsError，
 * 安装完成后再按包安装工具（git / python3 / nodejs / ffmpeg ...）。
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
        /** rootfs 内通用环境（让 git/pip/npm 等正常工作） */
        private const val BASE_ENV =
            "export HOME=/root; export LANG=C.UTF-8; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export GIT_TERMINAL_PROMPT=0;"
    }

    private val workspaceManager = WorkspaceManager(File(context.filesDir, "localtools_ws"))
    private val rootfsInstaller = RootfsInstaller(workspaceManager)
    private val prootRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir))

    // ---- rootfs 状态（和 workspace 一致） ----
    private val _rootfsProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val rootfsProgress: StateFlow<RootfsInstallProgress?> = _rootfsProgress.asStateFlow()

    private val _rootfsError = MutableStateFlow<String?>(null)
    val rootfsError: StateFlow<String?> = _rootfsError.asStateFlow()

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

    // ---------------- rootfs ----------------

    fun isRootfsInstalled(): Boolean =
        WorkspaceManager.isUsableRootfs(workspaceManager.linuxDir(ROOTFS_ROOT))

    /** 安装/重装 rootfs（与 workspace 相同的进度与错误模型） */
    suspend fun installRootfs(force: Boolean = false) {
        _rootfsError.value = null
        _rootfsProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
        try {
            workspaceManager.ensureWorkspace(ROOTFS_ROOT)
            if (force && isRootfsInstalled()) {
                workspaceManager.deleteWorkspace(ROOTFS_ROOT)
                workspaceManager.ensureWorkspace(ROOTFS_ROOT)
            }
            withContext(Dispatchers.IO) {
                rootfsInstaller.install(ROOTFS_ROOT, ALPINE_URL) { p -> _rootfsProgress.value = p }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed", e)
            _rootfsError.value = e.message ?: "Rootfs 安装失败"
        } finally {
            _rootfsProgress.value = null
            refreshPackages()
        }
    }

    fun dismissRootfsError() { _rootfsError.value = null }
    fun dismissPackageError() { _packageError.value = null }

    /** 在 rootfs（proot）里执行命令 */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        if (!isRootfsInstalled()) {
            return ShellResult(-1, "", "运行环境未安装，请先在「本地工具」里安装 rootfs")
        }
        val ctx = WorkspaceShellContext(
            root = ROOTFS_ROOT,
            command = "$BASE_ENV $command",
            cwd = "/root",
            filesDir = workspaceManager.filesDir(ROOTFS_ROOT),
            linuxDir = workspaceManager.linuxDir(ROOTFS_ROOT),
            tempDir = workspaceManager.tempDir(ROOTFS_ROOT),
            workingDir = workspaceManager.filesDir(ROOTFS_ROOT),
            timeoutMillis = timeoutMillis,
            bindMounts = listOf(
                WorkspaceBindMount(File("/storage"), "/storage", writable = true),
                WorkspaceBindMount(File("/sdcard"), "/sdcard", writable = true),
                WorkspaceBindMount(context.filesDir, "/appfiles", writable = true),
            ),
        )
        return withContext(Dispatchers.IO) {
            runCatching { prootRunner.execute(ctx) }
                .map { ShellResult(it.exitCode, it.stdout, it.stderr, it.timedOut) }
                .getOrElse { ShellResult(-1, "", it.message ?: "proot 执行失败") }
        }
    }

    // ---------------- 包 ----------------

    /** 刷新所有包的安装状态（一条命令批量检测） */
    suspend fun refreshPackages() {
        if (!isRootfsInstalled()) {
            _packageStates.value = ROOTFS_PACKAGES.associate { it.pkg to false }
            _toolsState.value = LocalToolType.entries.associateWith { t ->
                LocalToolInfo(t, isEnabled(settingsStore.settingsFlow.value.localToolsConfig, t), LocalToolStatus.NOT_INSTALLED)
            }
            return
        }
        val bins = ROOTFS_PACKAGES.joinToString(" ") { it.bin }
        val probe = runInRootfs(
            "for b in $bins; do if command -v \$b >/dev/null 2>&1; then echo \"\$b=OK\"; fi; done",
            timeoutMillis = 40_000,
        )
        val found = probe.stdout.lineSequence()
            .mapNotNull { line -> line.substringBefore('=').trim().takeIf { line.contains("=OK") } }
            .toSet()
        _packageStates.value = ROOTFS_PACKAGES.associate { it.pkg to found.contains(it.bin) }
        // 同步旧 toolsState
        _toolsState.value = LocalToolType.entries.associateWith { t ->
            val pkg = when (t) {
                LocalToolType.GIT -> "git"
                LocalToolType.SSH -> "openssh-client"
                LocalToolType.PYTHON -> "python3"
            }
            LocalToolInfo(
                type = t,
                enabled = isEnabled(settingsStore.settingsFlow.value.localToolsConfig, t),
                status = if (_packageStates.value[pkg] == true) LocalToolStatus.INSTALLED else LocalToolStatus.NOT_INSTALLED,
                version = "",
            )
        }
    }

    private fun isEnabled(config: LocalToolsConfig, type: LocalToolType): Boolean = when (type) {
        LocalToolType.GIT -> config.gitEnabled
        LocalToolType.SSH -> config.sshEnabled
        LocalToolType.PYTHON -> config.pythonEnabled
    }

    /** 安装单个包（自动确保 rootfs 已安装） */
    suspend fun installPackage(pkg: String) {
        _packageError.value = null
        _installingPackages.update { it + pkg }
        _packageMessages.update { it + (pkg to "准备中...") }
        try {
            if (!isRootfsInstalled()) {
                _packageMessages.update { it + (pkg to "先安装运行环境...") }
                installRootfs()
                if (!isRootfsInstalled()) {
                    _packageError.value = "运行环境安装失败，请先在上方安装 rootfs"
                    return
                }
            }
            _packageMessages.update { it + (pkg to "安装 $pkg ...") }
            val r = runInRootfs(
                "sed -i 's|https://dl-cdn.alpinelinux.org/alpine|$ALPINE_MIRROR|g' /etc/apk/repositories 2>/dev/null; " +
                    "apk update >/dev/null 2>&1; apk add --no-cache $pkg 2>&1",
                timeoutMillis = 1_800_000,
            )
            if (r.exitCode != 0) {
                _packageError.value = "安装 $pkg 失败：${r.output.take(500)}"
                _packageMessages.update { it + (pkg to "安装失败") }
            } else {
                _packageMessages.update { it + (pkg to "已安装") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installPackage failed: $pkg", e)
            _packageError.value = "安装 $pkg 失败：${e.message}"
        } finally {
            _installingPackages.update { it - pkg }
            refreshPackages()
        }
    }

    suspend fun removePackage(pkg: String) {
        if (!isRootfsInstalled()) return
        _installingPackages.update { it + pkg }
        try {
            runInRootfs("apk del $pkg 2>&1", timeoutMillis = 300_000)
        } finally {
            _installingPackages.update { it - pkg }
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
        if (enabled) {
            val pkg = when (type) {
                LocalToolType.GIT -> "git"
                LocalToolType.SSH -> "openssh-client"
                LocalToolType.PYTHON -> "python3"
            }
            if (_packageStates.value[pkg] != true) installPackage(pkg)
        }
        refreshPackages()
    }

    suspend fun installTool(type: LocalToolType) {
        val pkg = when (type) {
            LocalToolType.GIT -> "git"
            LocalToolType.SSH -> "openssh-client"
            LocalToolType.PYTHON -> "python3"
        }
        installPackage(pkg)
    }

    suspend fun uninstallTool(type: LocalToolType) {
        val pkg = when (type) {
            LocalToolType.GIT -> "git"
            LocalToolType.SSH -> "openssh-client"
            LocalToolType.PYTHON -> "python3"
        }
        removePackage(pkg)
        setToolEnabled(type, false)
    }

    fun pathPrefix(): String = ""
    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
}
