package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.localtools.LocalToolStatus
import me.rerere.rikkahub.data.localtools.LocalToolType
import me.rerere.rikkahub.data.localtools.ShellMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun LocalToolsPage(vm: LocalToolsVM = koinViewModel()) {
    val state by vm.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("本地工具") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("localTools") {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text("CLI 工具") },
                ) {
                    item(
                        onClick = { vm.toggleTool(LocalToolType.GIT, state.tools[LocalToolType.GIT] != LocalToolStatus.INSTALLED) },
                        leadingContent = { CardGroupIcon(HugeIcons.Tools, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(toolStatusText(state, LocalToolType.GIT)) },
                        headlineContent = { Text("Git") },
                        trailingContent = {
                            if (state.installing.contains(LocalToolType.GIT)) {
                                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                            } else {
                                Switch(
                                    checked = state.tools[LocalToolType.GIT] == LocalToolStatus.INSTALLED,
                                    onCheckedChange = { enabled -> vm.toggleTool(LocalToolType.GIT, enabled) },
                                )
                            }
                        },
                    )
                    item(
                        onClick = { vm.toggleTool(LocalToolType.SSH, state.tools[LocalToolType.SSH] != LocalToolStatus.INSTALLED) },
                        leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(toolStatusText(state, LocalToolType.SSH)) },
                        headlineContent = { Text("OpenSSH") },
                        trailingContent = {
                            if (state.installing.contains(LocalToolType.SSH)) {
                                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                            } else {
                                Switch(
                                    checked = state.tools[LocalToolType.SSH] == LocalToolStatus.INSTALLED,
                                    onCheckedChange = { enabled -> vm.toggleTool(LocalToolType.SSH, enabled) },
                                )
                            }
                        },
                    )
                    item(
                        onClick = { vm.toggleTool(LocalToolType.PYTHON, state.tools[LocalToolType.PYTHON] != LocalToolStatus.INSTALLED) },
                        leadingContent = { CardGroupIcon(HugeIcons.Bash, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(toolStatusText(state, LocalToolType.PYTHON)) },
                        headlineContent = { Text("Python") },
                        trailingContent = {
                            if (state.installing.contains(LocalToolType.PYTHON)) {
                                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                            } else {
                                Switch(
                                    checked = state.tools[LocalToolType.PYTHON] == LocalToolStatus.INSTALLED,
                                    onCheckedChange = { enabled -> vm.toggleTool(LocalToolType.PYTHON, enabled) },
                                )
                            }
                        },
                    )
                }
            }

            item("permissions") {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup(
                    title = { Text("权限模式") },
                ) {
                    item(
                        onClick = { vm.setShellMode(ShellMode.NORMAL) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(if (state.selectedShellMode == ShellMode.NORMAL) "当前使用" else "普通 app 权限") },
                        headlineContent = { Text("普通权限") },
                    )
                    item(
                        onClick = { vm.setShellMode(ShellMode.ROOT) },
                        leadingContent = { CardGroupIcon(HugeIcons.Wrench01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(if (state.rootAvailable) "可用" else "不可用") },
                        headlineContent = { Text("Root 权限") },
                    )
                    item(
                        onClick = { vm.setShellMode(ShellMode.SHIZUKU) },
                        leadingContent = { CardGroupIcon(HugeIcons.Codesandbox, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(if (state.shizukuAvailable) "可用" else "不可用") },
                        headlineContent = { Text("Shizuku 权限") },
                    )
                }
            }

            item("command") {
                CommandRunnerCard(vm = vm, state = state)
            }

            item("files") {
                FileBrowserHeaderCard(vm = vm, state = state)
            }
            items(state.fileEntries, key = { it.path }) { entry ->
                FileBrowserEntryCard(entry = entry, vm = vm)
            }
        }
    }
}

@Composable
private fun CommandRunnerCard(vm: LocalToolsVM, state: LocalToolsUiState) {
    var command by rememberSaveable { mutableStateOf("") }
    CardGroup(
        title = { Text("命令执行") },
    ) {
        item(
            headlineContent = { Text("当前模式: ${modeText(state.selectedShellMode)}") },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入命令，如: ls -la /") },
                    singleLine = false,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            },
        )
        item(
            headlineContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { vm.runCommand(command) },
                        enabled = !state.running && command.isNotBlank(),
                    ) {
                        Text(if (state.running) "执行中..." else "执行")
                    }
                    TextButton(onClick = { command = "" }) { Text("清空") }
                }
            },
        )
        if (state.commandOutput.isNotBlank()) {
            item(
                headlineContent = {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = state.commandOutput,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 20,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun FileBrowserHeaderCard(vm: LocalToolsVM, state: LocalToolsUiState) {
    CardGroup(
        title = { Text("文件管理") },
    ) {
        item(
            onClick = { vm.navigateTo("/") },
            leadingContent = { CardGroupIcon(HugeIcons.Folder01, containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) },
            supportingContent = { Text(state.currentPath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            headlineContent = { Text("浏览文件系统") },
        )
    }
}

@Composable
private fun FileBrowserEntryCard(entry: FileEntryUi, vm: LocalToolsVM) {
    CardGroup {
        item(
            onClick = { if (entry.isDirectory) vm.navigateTo(entry.path) },
            leadingContent = {
                if (entry.isDirectory) {
                    CardGroupIcon(HugeIcons.Folder01, containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            },
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(if (entry.isDirectory) "目录" else formatSize(entry.sizeBytes)) },
        )
    }
}

private fun toolStatusText(state: LocalToolsUiState, type: LocalToolType): String {
    val status = state.tools[type] ?: LocalToolStatus.NOT_INSTALLED
    return when (status) {
        LocalToolStatus.INSTALLED -> "已安装 ${state.toolVersions[type] ?: ""}"
        LocalToolStatus.INSTALLING -> "安装中..."
        LocalToolStatus.NOT_INSTALLED -> "未安装（启用后自动下载）"
        LocalToolStatus.ERROR -> "错误 ${state.toolErrors[type] ?: ""}"
    }
}

private fun modeText(mode: ShellMode): String = when (mode) {
    ShellMode.NORMAL -> "普通权限"
    ShellMode.ROOT -> "Root 权限"
    ShellMode.SHIZUKU -> "Shizuku 权限"
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
