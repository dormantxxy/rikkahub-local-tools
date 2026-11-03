package me.rerere.rikkahub.ui.pages.extensions.toolchains

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.localtools.ExtensionInstaller
import me.rerere.rikkahub.data.localtools.InstallPhase
import me.rerere.rikkahub.data.localtools.InstalledRecord
import me.rerere.rikkahub.data.localtools.MobiCodeCatalog
import me.rerere.rikkahub.data.localtools.ShizukuHelper
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ToolchainExtension

data class ToolchainItem(
    val ext: ToolchainExtension,
    val installed: InstalledRecord?,
) {
    val isInstalled: Boolean get() = installed != null

    /** 已安装且有新版本可更新。 */
    val hasUpdate: Boolean get() = installed != null && compareVersions(ext.version, installed.version) > 0
}

/** 版本号比较（支持 epoch 如 1:0.16-1 与多段数字）。 */
fun compareVersions(a: String, b: String): Int {
    fun parse(v: String): Pair<Long, List<Long>> {
        val epochMatch = Regex("^(\\d+):(.+)$").find(v)
        val epoch = epochMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val rest = epochMatch?.groupValues?.get(2) ?: v
        val nums = Regex("\\d+").findAll(rest).map { it.value.toLongOrNull() ?: 0L }.toList()
        return epoch to nums
    }
    val (epochA, numsA) = parse(a)
    val (epochB, numsB) = parse(b)
    if (epochA != epochB) return epochA.compareTo(epochB)
    for (i in 0 until maxOf(numsA.size, numsB.size)) {
        val x = numsA.getOrElse(i) { 0L }
        val y = numsB.getOrElse(i) { 0L }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

data class InstallProgress(
    val id: String,
    val phase: InstallPhase,
    val downloaded: Long = 0,
    val total: Long = 0,
    val extracted: Int = 0,
    val entries: Int = 0,
    val currentEntry: String? = null,
    val dependencyNote: String? = null,
) {
    val percent: Int get() = when (phase) {
        InstallPhase.DOWNLOADING -> if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        InstallPhase.EXTRACTING -> if (entries > 0) ((extracted * 100) / entries).toInt().coerceIn(0, 100) else 0
        InstallPhase.INSTALLING -> 100
    }
    val text: String get() = when (phase) {
        InstallPhase.DOWNLOADING -> if (total > 0) "下载中 ${percent}%  ${humanSize(downloaded)}/${humanSize(total)}" else "下载中 ${humanSize(downloaded)}"
        InstallPhase.EXTRACTING -> if (entries > 0) {
            val entry = currentEntry?.let { " · " + it.take(40) } ?: ""
            "解压中 ${percent}%  $extracted/$entries$entry"
        } else "解压中…"
        InstallPhase.INSTALLING -> dependencyNote ?: "安装中（赋权限/登记）…"
    }
}

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0; val mb = kb / 1024.0; val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}

class ToolchainsVM(
    private val installer: ExtensionInstaller,
    private val shellExecutor: ShellExecutor,
    private val context: Context,
    private val appEventBus: AppEventBus,
) : ViewModel() {

    /** 内置精选扩展（16 个，直接来自 assets）。 */
    val builtIn: List<ToolchainExtension> = MobiCodeCatalog.extensions(context)

    /** Termux 在线索引（约 3000+，运行时从镜像站拉取缓存）。 */
    private val _termuxPackages = MutableStateFlow<List<ToolchainExtension>>(emptyList())
    val termuxPackages: StateFlow<List<ToolchainExtension>> = _termuxPackages.asStateFlow()

    private val _termuxLoading = MutableStateFlow(false)
    val termuxLoading: StateFlow<Boolean> = _termuxLoading.asStateFlow()

    private val _termuxError = MutableStateFlow<String?>(null)
    val termuxError: StateFlow<String?> = _termuxError.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    private var installJob: Job? = null
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow("精选")
    val category: StateFlow<String> = _category.asStateFlow()

    /** 每个扩展当前选中的版本（id -> version）。 */
    private val _selectedVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val selectedVersions: StateFlow<Map<String, String>> = _selectedVersions.asStateFlow()

    /**
     * 商店显示列表：
     * - 搜索词为空：按分类显示全部可用扩展（内置精选 + 在线索引 + 已安装额外包）
     * - 搜索词非空：搜索全部扩展并按分类筛选
     */
    val items: StateFlow<List<ToolchainItem>> =
        combine(installer.installed, _query, _termuxPackages, _category) { map, q, termux, cat ->
            val builtInIds = builtIn.map { it.id }.toSet()
            val installedOnly = map.values
                .filter { it.id !in builtInIds }
                .sortedBy { it.installedAt }
                .map { recordToExtension(it) }
            val all = (builtIn + termux + installedOnly).distinctBy { it.id }
            val pool: List<ToolchainExtension> = if (q.isNotBlank()) {
                all.filter { matches(it, q) }
            } else {
                when (cat) {
                    "精选" -> builtIn + installedOnly
                    "全部" -> all
                    "已安装" -> all.filter { map.containsKey(it.id) }
                    else -> all.filter { MobiCodeCatalog.categoryLabel(it.category) == cat }
                }
            }
            val filtered = if (q.isNotBlank() && cat != "全部" && cat != "精选" && cat != "已安装") {
                pool.filter { MobiCodeCatalog.categoryLabel(it.category) == cat }
            } else pool
            filtered.map { ToolchainItem(it, map[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.Lazily, builtIn.map { ToolchainItem(it, null) })

    private val _privilege = MutableStateFlow(computePrivilege())
    val privilege: StateFlow<String> = _privilege.asStateFlow()

    init {
        // 打开商店即后台加载 Termux 在线索引（加载完成后包会显示在对应分类列表里）
        ensureTermuxIndex()
        // 订阅 Shizuku 授权结果事件，自动刷新权限状态
        viewModelScope.launch {
            appEventBus.events.collect { event ->
                if (event is AppEvent.ShizukuPermissionUpdated) {
                    _privilege.value = computePrivilege()
                }
            }
        }
        // 同时观察 ShizukuHelper 的全局权限状态（binder 到达/断开、授权变化都会触发）
        viewModelScope.launch {
            combine(ShizukuHelper.permissionGranted, ShizukuHelper.binderAlive) { _, _ -> }
                .collect { _privilege.value = computePrivilege() }
        }
    }

    /** 加载/刷新 Termux 在线索引。 */
    fun ensureTermuxIndex(force: Boolean = false) {
        if (_termuxLoading.value) return
        if (!force && _termuxPackages.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _termuxLoading.value = true
            _termuxError.value = null
            runCatching { MobiCodeCatalog.ensureTermuxIndex(context, force) }
                .onSuccess {
                    _termuxPackages.value = it
                    if (it.isEmpty()) _termuxError.value = "索引为空，请检查网络"
                }
                .onFailure { _termuxError.value = it.message ?: "加载扩展索引失败" }
            _termuxLoading.value = false
        }
    }

    val privilegeAvailable: Boolean get() = _privilege.value == "root" || _privilege.value == "shizuku"

    fun installedRecord(id: String): InstalledRecord? = installer.installed.value[id]

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotBlank() && _termuxPackages.value.isEmpty()) ensureTermuxIndex()
    }

    fun setCategory(cat: String) {
        _category.value = cat
    }

    fun selectedVersion(ext: ToolchainExtension): String =
        _selectedVersions.value[ext.id] ?: ext.availableVersions.first().version

    fun selectVersion(ext: ToolchainExtension, version: String) {
        _selectedVersions.value = _selectedVersions.value + (ext.id to version)
    }

    private fun matches(e: ToolchainExtension, q: String): Boolean =
        e.name.contains(q, true) || e.description.contains(q, true) ||
            e.id.contains(q, true) || e.category.contains(q, true) || e.publisher.contains(q, true)

    private fun recordToExtension(rec: InstalledRecord): ToolchainExtension = ToolchainExtension(
        id = rec.id,
        name = rec.name.ifBlank { rec.id },
        version = rec.version,
        publisher = rec.publisher.ifBlank { "MobiCode" },
        description = rec.description,
        category = rec.category.ifBlank { "MIRROR" },
        downloadSize = rec.downloadBytes,
        downloadUrl = rec.downloadUrl,
        sha256 = "",
        repositoryUrl = "",
        licenseName = "",
        minimumAppVersion = 0,
        format = rec.format,
    )

    private fun computePrivilege(): String = when {
        shellExecutor.isRootAvailable() -> "root"
        ShizukuHelper.hasPermission() -> "shizuku"
        ShizukuHelper.isBinderAlive() -> "shizuku_unauthorized"
        else -> "none"
    }

    fun refreshPrivilege() { _privilege.value = computePrivilege() }

    fun requestShizuku() {
        ShizukuHelper.requestPermission()
        viewModelScope.launch { delay(1500); refreshPrivilege() }
    }

    /** 安装指定版本的扩展（自动安装依赖，点击后立即显示进度）。 */
    fun install(ext: ToolchainExtension) {
        if (_progress.value != null) return
        val version = ext.availableVersions.firstOrNull { it.version == selectedVersion(ext) }
            ?: ext.availableVersions.first()
        val target = ext.copy(
            version = version.version,
            downloadUrl = version.downloadUrl,
            sha256 = version.sha256,
            downloadSize = version.downloadSize,
            format = version.format,
        )
        // 立即显示进度（不等网络响应）
        _progress.value = InstallProgress(
            id = ext.id,
            phase = InstallPhase.DOWNLOADING,
            downloaded = 0,
            total = version.downloadSize,
            dependencyNote = "准备下载…",
        )
        installJob = viewModelScope.launch {
            _message.value = null
            val result = installer.install(target) { upd ->
                _progress.value = InstallProgress(
                    id = ext.id, phase = upd.phase,
                    downloaded = upd.downloaded, total = upd.total,
                    extracted = upd.extracted, entries = upd.entries,
                    currentEntry = upd.currentEntry,
                    dependencyNote = upd.dependencyNote,
                )
            }
            _progress.value = null
            installJob = null
            _message.value = result.fold(
                onSuccess = { "已安装 ${ext.name} ${version.version}" },
                onFailure = { "安装失败：${it.message ?: "未知错误"}" },
            )
        }
    }

    /** 取消当前安装并删除下载缓存。 */
    fun cancelInstall() {
        val id = _progress.value?.id
        installJob?.cancel()
        installJob = null
        _progress.value = null
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) {
                File(context.filesDir, "extensions/_tmp_$id").deleteRecursively()
                _message.value = "已取消安装"
            }
        }
    }

    fun uninstall(ext: ToolchainExtension) {
        if (_progress.value != null) return
        viewModelScope.launch {
            _message.value = null
            val result = installer.uninstall(ext.id)
            _message.value = result.fold(
                onSuccess = { "已卸载 ${ext.name}" },
                onFailure = { "卸载失败：${it.message ?: "未知错误"}" },
            )
        }
    }

    fun clearMessage() { _message.value = null }
}
