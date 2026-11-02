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

    /**
     * 执行命令（PATH 含所有已装扩展的 bin）。
     * 因 targetSdk 较高，app 私有目录的可执行文件需要 root/shizuku 才能 exec，故优先提权。
     */
    suspend fun runInRootfs(command: String, timeoutMillis: Long = 180_000): ShellResult {
        val installed = installedExtensions()
        if (installed.isEmpty()) {
            return ShellResult(-1, "", "尚未安装任何工具，请先在「本地工具」里下载")
        }
        val pathPrefix = installed.map { binFile(it).parentFile!!.absolutePath }.distinct().joinToString(":") +
            ":/system/bin:/system/xbin"
        val wrapped = "export HOME=/data/local/tmp PATH=$pathPrefix LANG=C.UTF-8 GIT_TERMINAL_PROMPT=0 TMPDIR=/data/local/tmp; $command"
        return shellExecutor.execute(wrapped, cwd = null, timeoutMillis = timeoutMillis, mode = shellExecutor.preferredMode())
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
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
}
