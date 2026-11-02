package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.localtools.ROOTFS_PACKAGES
import me.rerere.rikkahub.data.localtools.LocalToolsManager
import me.rerere.rikkahub.data.localtools.RootfsDistro
import me.rerere.rikkahub.data.localtools.RootfsPackageGroup
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) },
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val toolsManager: LocalToolsManager = koinInject()
    val scope = rememberCoroutineScope()

    val envReady by toolsManager.envReady.collectAsStateWithLifecycle()
    val linkedName by toolsManager.linkedWorkspaceName.collectAsStateWithLifecycle()
    val distro by toolsManager.distro.collectAsStateWithLifecycle()
    val packageStates by toolsManager.packageStates.collectAsStateWithLifecycle()
    val installing by toolsManager.installingPackages.collectAsStateWithLifecycle()
    val packageMessages by toolsManager.packageMessages.collectAsStateWithLifecycle()
    val packageError by toolsManager.packageError.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var projectInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { toolsManager.refreshPackages() }
    LaunchedEffect(settings.projectPath) { projectInput = settings.projectPath }

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true,
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true,
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) assistant.localTools + option else assistant.localTools - option
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    fun CardGroupScope.toolItem(
        option: LocalToolOption,
        icon: ImageVector,
        @StringRes title: Int,
        @StringRes description: Int,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = { Text(stringResource(description)) },
            headlineContent = { Text(stringResource(title)) },
        )
    }

    fun CardGroupScope.toolItemStr(
        option: LocalToolOption,
        icon: ImageVector,
        title: String,
        description: String,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = { Text(description) },
            headlineContent = { Text(title) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ===== AI 可调用工具 =====
        CardGroup {
            toolItem(
                option = LocalToolOption.JavascriptEngine,
                icon = HugeIcons.JavaScript,
                title = R.string.assistant_page_local_tools_javascript_engine_title,
                description = R.string.assistant_page_local_tools_javascript_engine_desc,
            )
            toolItem(
                option = LocalToolOption.TimeInfo,
                icon = HugeIcons.Clock01,
                title = R.string.assistant_page_local_tools_time_info_title,
                description = R.string.assistant_page_local_tools_time_info_desc,
            )
            toolItem(
                option = LocalToolOption.Clipboard,
                icon = HugeIcons.ClipboardPaste,
                title = R.string.assistant_page_local_tools_clipboard_title,
                description = R.string.assistant_page_local_tools_clipboard_desc,
            )
            toolItem(
                option = LocalToolOption.Tts,
                icon = HugeIcons.VolumeHigh,
                title = R.string.assistant_page_local_tools_tts_title,
                description = R.string.assistant_page_local_tools_tts_desc,
            )
            toolItem(
                option = LocalToolOption.AskUser,
                icon = HugeIcons.MessageQuestion,
                title = R.string.assistant_page_local_tools_ask_user_title,
                description = R.string.assistant_page_local_tools_ask_user_desc,
            )
            toolItem(
                option = LocalToolOption.ScreenTime,
                icon = HugeIcons.SmartPhone01,
                title = R.string.assistant_page_local_tools_screen_time_title,
                description = R.string.assistant_page_local_tools_screen_time_desc,
            )
            toolItem(
                option = LocalToolOption.Calendar,
                icon = HugeIcons.Calendar03,
                title = R.string.assistant_page_local_tools_calendar_title,
                description = R.string.assistant_page_local_tools_calendar_desc,
            )
            toolItem(
                option = LocalToolOption.ChartDisplay,
                icon = HugeIcons.ChartLineData01,
                title = R.string.assistant_page_local_tools_chart_display_title,
                description = R.string.assistant_page_local_tools_chart_display_desc,
            )
            toolItemStr(
                option = LocalToolOption.Shell,
                icon = HugeIcons.ComputerTerminal01,
                title = "设备 Shell",
                description = "执行任意 shell 命令，权限自动 root > shizuku > 普通",
            )
            toolItemStr(
                option = LocalToolOption.Files,
                icon = HugeIcons.FolderOpen,
                title = "文件管理",
                description = "浏览/读写/复制/移动/删除/搜索文件，支持 root/shizuku/普通",
            )
            toolItemStr(
                option = LocalToolOption.Cli,
                icon = HugeIcons.Tools,
                title = "CLI 工具 (git/python/node/ssh)",
                description = "在运行环境 rootfs 里调用命令行工具，可做项目开发",
            )
            toolItemStr(
                option = LocalToolOption.WebFetch,
                icon = HugeIcons.Earth,
                title = "网页浏览",
                description = "抓取网页/接口（GET/POST/headers）返回可读文本，无需前台渲染",
            )
            toolItemStr(
                option = LocalToolOption.SearchCode,
                icon = HugeIcons.Code,
                title = "代码检索",
                description = "在项目里按关键字搜索，返回 文件:行:内容",
            )
            toolItemStr(
                option = LocalToolOption.FileTree,
                icon = HugeIcons.FolderOpen,
                title = "文件树",
                description = "列出项目目录结构，便于 AI 快速定位文件",
            )
            toolItemStr(
                option = LocalToolOption.Tasks,
                icon = HugeIcons.CheckList,
                title = "任务计划",
                description = "AI 可拆解任务并勾选进度（侧滑栏「任务计划」可查看）",
            )
        }

        // ===== 项目 =====
        CardGroup(title = { Text("项目") }) {
            formItem {
                Text(
                    "当前项目目录（AI 的 shell/文件/CLI 工具默认在此工作）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = projectInput,
                        onValueChange = { projectInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("/storage/emulated/0/…") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            scope.launch { settingsStore.update { it.copy(projectPath = projectInput.trim()) } }
                        },
                    ) { Text("应用") }
                }
            }
            item(
                headlineContent = {
                    Text(if (settings.projectPath.isBlank()) "未设置项目（默认 /storage/emulated/0）" else settings.projectPath)
                },
                supportingContent = { Text("选中后 AI 工具在 this 目录下工作") },
            )
            item(
                headlineContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { projectInput = "/storage/emulated/0/MobiCode Studio/Projects" }) { Text("Projects") }
                        TextButton(onClick = { projectInput = "/storage/emulated/0/Download" }) { Text("Download") }
                        TextButton(onClick = { projectInput = ""; scope.launch { settingsStore.update { it.copy(projectPath = "") } } }) { Text("清空") }
                    }
                },
            )
        }

        // ===== CLI 运行环境（复用工作区 rootfs） =====
        CardGroup(title = { Text("CLI 运行环境（工作区）") }) {
            item(
                headlineContent = {
                    Text(if (envReady) "已关联：${linkedName ?: "工作区"}" else "未找到已安装 rootfs 的工作区")
                },
                supportingContent = {
                    Text(
                        if (envReady) "发行版 ${distroLabel(distro)} · CLI 工具在该 rootfs 内运行"
                        else "请先在 设置 → 扩展 → 工作区 安装 rootfs，这里会自动关联"
                    )
                },
                trailingContent = {
                    if (envReady) {
                        TextButton(onClick = { scope.launch { toolsManager.refreshPackages() } }) { Text("刷新") }
                    } else {
                        FilledTonalButton(onClick = { navController.navigate(Screen.Workspaces) }) { Text("前往工作区") }
                    }
                },
            )
        }

        // ===== 工具包（按分组） =====
        for (group in RootfsPackageGroup.entries) {
            val pkgs = ROOTFS_PACKAGES.filter { it.group == group }
            if (pkgs.isEmpty()) continue
            CardGroup(title = { Text(group.title) }) {
                for (pkg in pkgs) {
                    val installed = packageStates[pkg.bin] == true
                    val busy = pkg.label in installing
                    item(
                        headlineContent = { Text(pkg.label) },
                        supportingContent = {
                            Text(if (busy) (packageMessages[pkg.label] ?: "安装中...") else pkg.desc)
                        },
                        trailingContent = {
                            when {
                                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                installed -> TextButton(
                                    enabled = envReady,
                                    onClick = { scope.launch { toolsManager.removePackage(pkg.label) } },
                                ) { Text("卸载") }
                                else -> FilledTonalButton(
                                    enabled = envReady,
                                    onClick = { scope.launch { toolsManager.installPackage(pkg.label) } },
                                ) { Text("安装") }
                            }
                        },
                    )
                }
            }
        }

        packageError?.let { err ->
            CardGroup {
                item(
                    headlineContent = { Text(err, color = MaterialTheme.colorScheme.error) },
                    trailingContent = {
                        TextButton(onClick = { toolsManager.dismissPackageError() }) { Text("关闭") }
                    },
                )
            }
        }
    }
}

private fun distroLabel(d: RootfsDistro): String = when (d) {
    RootfsDistro.ALPINE -> "Alpine (apk)"
    RootfsDistro.DEBIAN -> "Debian/Ubuntu (apt)"
    RootfsDistro.UNKNOWN -> "未知"
}
