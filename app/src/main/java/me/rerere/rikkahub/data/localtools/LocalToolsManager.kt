package me.rerere.rikkahub.data.localtools

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "LocalToolsManager"

/** 工具下载进度 */
data class ToolDownloadProgress(
    val toolType: LocalToolType,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
    val finished: Boolean = false,
)

/** 本地工具管理器 */
class LocalToolsManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
) {
    private val _toolsState = MutableStateFlow<Map<LocalToolType, LocalToolInfo>>(emptyMap())
    val toolsState: StateFlow<Map<LocalToolType, LocalToolInfo>> = _toolsState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<LocalToolType, ToolDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<LocalToolType, ToolDownloadProgress>> = _downloadProgress.asStateFlow()

    /** 工具根目录 */
    private val toolsRootDir: File
        get() = File(context.filesDir, "localtools")

    /** 刷新所有工具状态 */
    suspend fun refreshTools() {
        val config = settingsStore.settingsFlow.value.localToolsConfig
        val infos = mutableMapOf<LocalToolType, LocalToolInfo>()
        for (type in LocalToolType.entries) {
            infos[type] = queryToolStatus(type, config)
        }
        _toolsState.value = infos
    }

    /** 查询单个工具状态 */
    private suspend fun queryToolStatus(
        type: LocalToolType,
        config: LocalToolsConfig,
    ): LocalToolInfo {
        val enabled = when (type) {
            LocalToolType.GIT -> config.gitEnabled
            LocalToolType.SSH -> config.sshEnabled
            LocalToolType.PYTHON -> config.pythonEnabled
        }
        val execFile = toolExecutable(type)
        val exists = execFile?.exists() == true
        return if (exists) {
            val version = queryVersion(type)
            LocalToolInfo(type, enabled, LocalToolStatus.INSTALLED, version, execFile?.absolutePath)
        } else {
            LocalToolInfo(type, enabled, LocalToolStatus.NOT_INSTALLED)
        }
    }

    /** 工具可执行文件 */
    private fun toolExecutable(type: LocalToolType): File? = when (type) {
        LocalToolType.GIT -> File(toolsRootDir, "git/bin/git").takeIf { it.exists() }
            ?: File(toolsRootDir, "git/usr/bin/git").takeIf { it.exists() }
            ?: File(toolsRootDir, "bin/git").takeIf { it.exists() }
        LocalToolType.SSH -> File(toolsRootDir, "ssh/usr/bin/ssh").takeIf { it.exists() }
            ?: File(toolsRootDir, "ssh/bin/ssh").takeIf { it.exists() }
            ?: File(toolsRootDir, "bin/ssh").takeIf { it.exists() }
        LocalToolType.PYTHON -> File(toolsRootDir, "python/bin/python3").takeIf { it.exists() }
            ?: File(toolsRootDir, "python/usr/bin/python3").takeIf { it.exists() }
            ?: File(toolsRootDir, "bin/python3").takeIf { it.exists() }
    }

    /** 查询版本 */
    private suspend fun queryVersion(type: LocalToolType): String {
        val exec = toolExecutable(type) ?: return ""
        val result = shellExecutor.execute("\"${exec.absolutePath}\" --version 2>&1 | head -1")
        return result.stdout.trim().ifEmpty { result.stderr.trim() }
    }

    /**
     * 启用/停用工具
     *
     * 启用时若未安装则自动下载安装；停用时仅改配置。
     */
    suspend fun setToolEnabled(type: LocalToolType, enabled: Boolean) {
        settingsStore.update { it ->
            it.copy(
                localToolsConfig = it.localToolsConfig.copy(
                    gitEnabled = if (type == LocalToolType.GIT) enabled else it.localToolsConfig.gitEnabled,
                    sshEnabled = if (type == LocalToolType.SSH) enabled else it.localToolsConfig.sshEnabled,
                    pythonEnabled = if (type == LocalToolType.PYTHON) enabled else it.localToolsConfig.pythonEnabled,
                )
            )
        }
        if (enabled) {
            // 若未安装则下载
            val status = queryToolStatus(type, settingsStore.settingsFlow.value.localToolsConfig)
            if (status.status == LocalToolStatus.NOT_INSTALLED) {
                installTool(type)
            }
        }
        refreshTools()
    }

    /** 安装指定工具 */
    suspend fun installTool(type: LocalToolType) {
        _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "开始下载...")) }
        try {
            val url = when (type) {
                LocalToolType.GIT -> LocalToolMirrors.GIT_DOWNLOAD_URL
                LocalToolType.SSH -> LocalToolMirrors.SSH_DOWNLOAD_URL
                LocalToolType.PYTHON -> LocalToolMirrors.PYTHON_DOWNLOAD_URL
            }
            val archive = downloadTool(type, url)
            if (archive == null) {
                _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "下载失败")) }
                return
            }
            extractTool(type, archive)
            archive.delete()
            _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "安装完成", finished = true)) }
        } catch (e: Exception) {
            Log.e(TAG, "安装工具失败: $type", e)
            _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "安装失败: ${e.message}")) }
        } finally {
            refreshTools()
        }
    }

    /** 下载工具 */
    private suspend fun downloadTool(
        type: LocalToolType,
        url: String,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            toolsRootDir.mkdirs()
            val archive = File(toolsRootDir, "${type.name.lowercase()}.tar.gz")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                _downloadProgress.update { it + (type to ToolDownloadProgress(type, message = "HTTP $code")) }
                return@runCatching null
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        _downloadProgress.update { it + (type to ToolDownloadProgress(type, read, total, "下载中...")) }
                    }
                }
            }
            archive
        }.getOrElse {
            Log.e(TAG, "下载失败: $type", it)
            null
        }
    }

    /** 解压 tar.gz */
    private suspend fun extractTool(type: LocalToolType, archive: File) {
        val targetDir = File(toolsRootDir, type.name.lowercase())
        // 清理旧目录
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        val result = shellExecutor.execute(
            "tar -xzf ${archive.absolutePath.shellQuote()} -C ${targetDir.absolutePath.shellQuote()} 2>&1",
            timeoutMillis = 300_000,
        )
        if (!result.isSuccess) {
            throw IllegalStateException("解压失败: ${result.output.take(200)}")
        }

        // 设置可执行权限
        shellExecutor.execute("chmod -R 755 ${targetDir.absolutePath.shellQuote()}")
    }

    /** 卸载工具 */
    suspend fun uninstallTool(type: LocalToolType) {
        val targetDir = File(toolsRootDir, type.name.lowercase())
        targetDir.deleteRecursively()
        setToolEnabled(type, false)
        refreshTools()
    }

    /** 获取工具安装目录 */
    fun toolInstallDir(type: LocalToolType): File = File(toolsRootDir, type.name.lowercase())

    /** 检测 shizuku 可用 */
    fun isShizukuAvailable(): Boolean = shellExecutor.isShizukuAvailable()

    /** 检测 root 可用 */
    fun isRootAvailable(): Boolean = shellExecutor.isRootAvailable()
}
