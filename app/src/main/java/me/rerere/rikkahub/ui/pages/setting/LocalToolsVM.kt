package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.localtools.LocalToolStatus
import me.rerere.rikkahub.data.localtools.LocalToolType
import me.rerere.rikkahub.data.localtools.LocalToolsManager
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ShellMode
import me.rerere.rikkahub.data.localtools.ShellResult

/** 本地工具页面状态 */
data class LocalToolsUiState(
    val tools: Map<LocalToolType, LocalToolStatus> = emptyMap(),
    val toolVersions: Map<LocalToolType, String> = emptyMap(),
    val toolErrors: Map<LocalToolType, String> = emptyMap(),
    val installing: Set<LocalToolType> = emptySet(),
    val rootAvailable: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val selectedShellMode: ShellMode = ShellMode.NORMAL,
    val commandOutput: String = "",
    val running: Boolean = false,
    val currentPath: String = "/storage/emulated/0",
    val fileEntries: List<FileEntryUi> = emptyList(),
)

data class FileEntryUi(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

class LocalToolsVM(
    private val context: Context,
    private val toolsManager: LocalToolsManager,
    private val shellExecutor: ShellExecutor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalToolsUiState())
    val uiState: StateFlow<LocalToolsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            toolsManager.refreshTools()
            val tools = toolsManager.toolsState.value
            val statuses = tools.mapValues { it.value.status }
            val versions = tools.mapValues { it.value.version }
            val errors = tools.mapValues { it.value.error ?: "" }
            _uiState.value = _uiState.value.copy(
                tools = statuses,
                toolVersions = versions,
                toolErrors = errors,
                rootAvailable = toolsManager.isRootAvailable(),
                shizukuAvailable = toolsManager.isShizukuAvailable(),
            )
        }
    }

    fun toggleTool(type: LocalToolType, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                _uiState.value = _uiState.value.copy(installing = _uiState.value.installing + type)
                toolsManager.setToolEnabled(type, true)
                _uiState.value = _uiState.value.copy(installing = _uiState.value.installing - type)
            } else {
                toolsManager.setToolEnabled(type, false)
            }
            refresh()
        }
    }

    fun installTool(type: LocalToolType) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(installing = _uiState.value.installing + type)
            toolsManager.installTool(type)
            _uiState.value = _uiState.value.copy(installing = _uiState.value.installing - type)
            refresh()
        }
    }

    fun uninstallTool(type: LocalToolType) {
        viewModelScope.launch {
            toolsManager.uninstallTool(type)
            refresh()
        }
    }

    fun setShellMode(mode: ShellMode) {
        _uiState.value = _uiState.value.copy(selectedShellMode = mode)
    }

    fun runCommand(command: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(running = true)
            val result = shellExecutor.execute(
                command = command,
                mode = _uiState.value.selectedShellMode,
                timeoutMillis = 120_000,
            )
            _uiState.value = _uiState.value.copy(
                commandOutput = formatResult(result),
                running = false,
            )
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(currentPath = path)
            refreshFiles(path)
        }
    }

    fun refreshFiles(path: String = _uiState.value.currentPath) {
        viewModelScope.launch {
            val entries = shellExecutor.listDirectory(path)
            _uiState.value = _uiState.value.copy(
                currentPath = path,
                fileEntries = entries.map { FileEntryUi(it.name, it.path, it.isDirectory, it.sizeBytes) },
            )
        }
    }

    private fun formatResult(result: ShellResult): String {
        return buildString {
            append("exit code: ").append(result.exitCode).append('\n')
            if (result.stdout.isNotBlank()) {
                append("--- stdout ---\n").append(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                if (result.stdout.isNotBlank()) append('\n')
                append("--- stderr ---\n").append(result.stderr)
            }
        }
    }
}
