package me.rerere.rikkahub.ui.pages.extensions.toolchains

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
}

data class InstallProgress(
    val id: String,
    val phase: InstallPhase,
    val downloaded: Long = 0,
    val total: Long = 0,
    val extracted: Int = 0,
    val entries: Int = 0,
) {
    val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    val text: String get() = when (phase) {
        InstallPhase.DOWNLOADING -> if (total > 0) "下载中 ${percent}%  ${humanSize(downloaded)}/${humanSize(total)}" else "下载中 ${humanSize(downloaded)}"
        InstallPhase.EXTRACTING -> if (entries > 0) "解压中 $extracted/$entries" else "解压中…"
        InstallPhase.INSTALLING -> "安装中（赋权限/登记）…"
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

    val extensions: List<ToolchainExtension> = MobiCodeCatalog.extensions(context)

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val emptyItems = extensions.map { ToolchainItem(it, null) }

    val items: StateFlow<List<ToolchainItem>> = combine(installer.installed, _query) { map, q ->
        val base = extensions.map { ToolchainItem(it, map[it.id]) }
        if (q.isBlank()) base else base.filter { matches(it.ext, q) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyItems)

    // 远程搜索结果（镜像站目录），安装后加入列表尾部
    private val _remoteResults = MutableStateFlow<List<ToolchainExtension>>(emptyList())
    val remoteResults: StateFlow<List<ToolchainExtension>> = _remoteResults.asStateFlow()

    fun searchRemote(query: String) {
        if (query.isBlank()) {
            _remoteResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val results = MobiCodeCatalog.searchRemote(context, query)
            _remoteResults.value = results
        }
    }

    private val _privilege = MutableStateFlow(computePrivilege())
    val privilege: StateFlow<String> = _privilege.asStateFlow()

    init {
        // 订阅 Shizuku 授权结果事件，自动刷新权限状态
        viewModelScope.launch {
            appEventBus.events.collect { event ->
                if (event is AppEvent.ShizukuPermissionUpdated) {
                    _privilege.value = computePrivilege()
                }
            }
        }
    }

    val privilegeAvailable: Boolean get() = _privilege.value == "root" || _privilege.value == "shizuku"

    /** 查询指定扩展是否已安装（供远程搜索结果展示） */
    fun installedRecord(id: String): InstalledRecord? = installer.installed.value[id]

    fun setQuery(q: String) {
        _query.value = q
        searchRemote(q)
    }

    private fun matches(e: ToolchainExtension, q: String): Boolean =
        e.name.contains(q, true) || e.description.contains(q, true) ||
            e.id.contains(q, true) || e.category.contains(q, true) || e.publisher.contains(q, true)

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

    fun install(ext: ToolchainExtension) {
        if (_progress.value != null) return
        viewModelScope.launch {
            _message.value = null
            val result = installer.install(ext) { upd ->
                _progress.value = InstallProgress(
                    id = ext.id, phase = upd.phase,
                    downloaded = upd.downloaded, total = upd.total,
                    extracted = upd.extracted, entries = upd.entries,
                )
            }
            _progress.value = null
            _message.value = result.fold(
                onSuccess = { "已安装 ${ext.name} ${ext.version}" },
                onFailure = { "安装失败：${it.message ?: "未知错误"}" },
            )
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
