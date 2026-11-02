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
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellContext

private const val TAG = "LocalToolsManager"
private const val ROOT = "alpine"

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
 * 与项目里的「工作区 rootfs」完全相同的实现方式（RootfsInstaller + ProotShellRunner）：
 *  - 下载/解压在 app 内完成（不需要 root）
 *  - proot 是 APK 里自带的 .so，普通权限即可执行
 *  - 装好后 apk add 任意包
 */
class LocalToolsManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
) {
    private val ws = WorkspaceManager(File(context.filesDir, "localtools_ws"))
    private val rootfsInstaller = RootfsInstaller(ws)
    private val prootRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir))

    fun isLinuxEnvInstalled(): Boolean =
        runCatching { WorkspaceManager.isUsableRootfs(ws.linuxDir(ROOT)) }.getOrDefault(false)

    /** 兼容旧调用 */
    fun isRootfsInstalled(): Boolean = isLinuxEnvInstalled()

    private val _linuxEnv = MutableStateFlow(LinuxEnvState(installed = isLinuxEnvInstalled()))
    val linuxEnv: StateFlow<LinuxEnvState> = _linuxEnv.asStateFlow()

    private val _packageStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val packageStates: StateFlow<Map<String, Boolean>> = _packageStates.asStateFlow()

    private val _installingPackages = MutableStateFlow<Set<String>>(emptySet())
    val installingPackages: StateFlow<Set<String>> = _installingPackages.asStateFlow()

    private val _linuxPkgError = MutableStateFlow<String?>(null)
    val linuxPkgError: StateFlow<String?> = _linuxPkgError.asStateFlow()

    fun dismissErrors() {
        _linuxEnv.update { it.copy(error = null) }
        _linuxPkgError.value = null
    }

    /** 安装完整 Linux 环境（app 内下载 + 解压，无需 root） */
    suspend fun installLinuxEnv() {
        _linuxEnv.value = LinuxEnvState(installing = true, message = "准备下载...")
        try {
            ws.ensureWorkspace(ROOT)
            withContext(Dispatchers.IO) {
                rootfsInstaller.install(ROOT, LinuxEnv.MIRRORS.first()) { p ->
                    val msg = when (p.stage) {
                        RootfsInstallStage.DOWNLOADING -> buildString {
                            append("下载中 ")
                            append(p.bytesRead / 1024)
                            append("KB")
                            p.totalBytes?.let { append(" / "); append(it / 1024); append("KB") }
                        }
                        RootfsInstallStage.EXTRACTING -> "解压中 ${p.entriesExtracted} 个文件"
                        RootfsInstallStage.INSTALLED -> "完成"
                    }
                    _linuxEnv.value = LinuxEnvState(installing = true, message = msg)
                }
            }
            // 写入国内 apk 源（app 自己写，属主正确）
            runCatching {
                File(ws.linuxDir(ROOT), "etc/apk").mkdirs()
                File(ws.linuxDir(ROOT), "etc/apk/repositories")
                    .writeText(LinuxEnv.APK_REPOS.joinToString("\n") + "\n")
            }
            _linuxEnv.value = LinuxEnvState(installed = true, message = "已安装")
            refreshPackages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installLinuxEnv failed", e)
            _linuxEnv.value = LinuxEnvState(error = e.message ?: "安装失败")
        }
    }

    suspend fun removeLinuxEnv() {
        runCatching { ws.deleteWorkspace(ROOT) }
        _linuxEnv.value = LinuxEnvState()
        _packageStates.value = emptyMap()
    }

    /** 安装单个扩展（apk add，走 proot，不需要 root） */
    suspend fun installPackage(pkg: String) {
        if (!isLinuxEnvInstalled()) { _linuxPkgError.value = "请先安装完整 Linux 环境（3.9MB）"; return }
        _installingPackages.update { it + pkg }
        _linuxPkgError.value = null
        try {
            val r = runInLinux("apk update >/dev/null 2>&1; apk add --no-cache $pkg 2>&1", 1_800_000)
            if (r.exitCode != 0) _linuxPkgError.value = "安装 $pkg 失败：${r.output.take(400)}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _linuxPkgError.value = "安装 $pkg 失败：${e.message}"
        } finally {
            _installingPackages.update { it - pkg }
            refreshPackages()
        }
    }

    suspend fun refreshPackages() {
        if (!isLinuxEnvInstalled()) {
            _packageStates.value = STORE_PACKAGES.associate { it.pkg to false }
            _linuxEnv.update { it.copy(installed = false) }
            return
        }
        _linuxEnv.update { it.copy(installed = true) }
        val bins = STORE_PACKAGES.map { it.bin }.distinct()
        val r = runInLinux(
            "for b in ${bins.joinToString(" ")}; do command -v \$b >/dev/null 2>&1 && echo \"\$b=OK\"; done",
            60_000,
        )
        val found = r.stdout.lineSequence().filter { it.contains("=OK") }
            .map { it.substringBefore('=').trim() }.toSet()
        _packageStates.value = STORE_PACKAGES.associate { it.pkg to found.contains(it.bin) }
    }

    /** 在 proot(Alpine) 里执行（与工作区 rootfs 同一套 ProotShellRunner，无需 root） */
    private suspend fun runInLinux(command: String, timeoutMillis: Long): ShellResult {
        val linuxDir = ws.linuxDir(ROOT)
        if (!runCatching { WorkspaceManager.isUsableRootfs(linuxDir) }.getOrDefault(false)) {
            return ShellResult(-1, "", "Linux 环境未安装")
        }
        val inner = "export HOME=/root LANG=C.UTF-8 GIT_TERMINAL_PROMPT=0 " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        val ctx = WorkspaceShellContext(
            root = ROOT,
            command = inner,
            cwd = "/root",
            filesDir = ws.filesDir(ROOT),
            linuxDir = linuxDir,
            tempDir = ws.tempDir(ROOT),
            workingDir = ws.filesDir(ROOT),
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

    /** 供 AI 工具调用 */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        if (!isLinuxEnvInstalled()) {
            return ShellResult(-1, "", "请先在扩展商店安装「基础环境」（3.9MB）")
        }
        return runInLinux(command, timeoutMillis)
    }

    /** 已安装的命令名（给 AI 工具描述用，省 token） */
    fun installedCommands(): List<String> = _packageStates.value.filterValues { it }.keys.sorted()

    /** 在 Alpine 仓库里真实搜索（apk search），返回 包名 to 描述 */
    suspend fun searchRemote(query: String, limit: Int = 40): List<Pair<String, String>> {
        if (query.isBlank() || !isLinuxEnvInstalled()) return emptyList()
        val r = runInLinux("apk search -v ${query.shellQuote()} 2>/dev/null | head -$limit", 60_000)
        return r.stdout.lineSequence()
            .filter { it.isNotBlank() && it.contains(" - ") }
            .map { line ->
                val left = line.substringBefore(" - ").trim()
                val desc = line.substringAfter(" - ").trim()
                left.substringBeforeLast("-", left) to desc
            }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
            .toList()
    }

    fun hasPrivilege(): Boolean = shellExecutor.hasPrivilege()
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()
}
