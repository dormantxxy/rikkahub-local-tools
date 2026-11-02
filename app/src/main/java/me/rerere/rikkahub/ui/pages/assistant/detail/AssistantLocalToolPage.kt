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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import me.rerere.rikkahub.data.localtools.LINUX_PACKAGES
import me.rerere.rikkahub.data.localtools.TOOL_EXTENSIONS
import me.rerere.rikkahub.data.localtools.LocalToolsManager
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

    val extStates by toolsManager.extensionStates.collectAsStateWithLifecycle()
    val linuxEnv by toolsManager.linuxEnv.collectAsStateWithLifecycle()
    val linuxPkgs by toolsManager.linuxPackages.collectAsStateWithLifecycle()
    val installingLinuxPkg by toolsManager.installingLinuxPkg.collectAsStateWithLifecycle()
    val linuxPkgError by toolsManager.linuxPkgError.collectAsStateWithLifecycle()
    // 只支持 root / shizuku：无权限时 CLI 相关不可用
    var hasPriv by remember { mutableStateOf(false) }
    val navController = LocalNavController.current

    LaunchedEffect(Unit) {
        hasPriv = toolsManager.isRootAvailable() || toolsManager.isShizukuAvailable()
        toolsManager.refreshPackages()
        toolsManager.refreshLinuxPackages()
    }

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
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            "${linuxEnv.message} ${linuxEnv.bytesRead / 1024}KB" + (total?.let { " / ${it / 1024}KB" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            linuxEnv.error?.let { err ->
                item(
                    headlineContent = { Text(err, color = MaterialTheme.colorScheme.error) },
                    trailingContent = { TextButton(onClick = { toolsManager.dismissLinuxErrors() }) { Text("关闭") } },
                )
            }
        }

        // ===== 环境内软件包 =====
        if (linuxEnv.installed) {
            CardGroup(title = { Text("安装软件包（apk，任意包可用）") }) {
                for (lp in LINUX_PACKAGES) {
                    val installed = linuxPkgs[lp.pkg] == true
                    val busy = lp.pkg in installingLinuxPkg
                    item(
                        headlineContent = { Text(lp.label) },
                        supportingContent = { Text(if (busy) "安装中..." else lp.desc) },
                        trailingContent = {
                            when {
                                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                installed -> Text("已安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                !hasPriv -> Text("不可用", style = MaterialTheme.typography.bodySmall)
                                else -> FilledTonalButton(onClick = { scope.launch { toolsManager.installLinuxPackage(lp.pkg) } }) { Text("安装") }
                            }
                        },
                    )
                }
                linuxPkgError?.let { err ->
                    item(
                        headlineContent = { Text(err, color = MaterialTheme.colorScheme.error) },
                        trailingContent = { TextButton(onClick = { toolsManager.dismissLinuxErrors() }) { Text("关闭") } },
                    )
                }
            }
        }

        // ===== 工具扩展（在线下载） =====
        CardGroup(title = { Text("工具扩展（在线下载）") }) {
            item(
                headlineContent = { Text("点「安装」后从镜像站在线下载，APK 内不内置任何工具") },
                supportingContent = { Text("下载源：GitHub Release（ghfast/ghproxy 镜像自动回退）") },
            )
            for (spec in TOOL_EXTENSIONS) {
                val st = extStates[spec.id]
                val installed = st?.installed == true
                val installing = st?.installing == true
                item(
                    headlineContent = { Text("${spec.name}  ${spec.version}") },
                    supportingContent = {
                        Column {
                            Text(if (installing) (st?.message ?: "下载中...") else "${spec.desc} · ${spec.sizeMb}MB")
                            if (installing) {
                                val total = st?.totalBytes
                                if (total != null && total > 0) {
                                    LinearProgressIndicator(
                                        progress = { (st.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Text(
                                    "${st.bytesRead / 1024 / 1024}MB" + (st.totalBytes?.let { " / ${it / 1024 / 1024}MB" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            st?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    trailingContent = {
                        when {
                            installing -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            installed -> TextButton(
                                onClick = { scope.launch { toolsManager.removeExtension(spec) } },
                            ) { Text("卸载") }
                            else -> FilledTonalButton(
                                onClick = { scope.launch { toolsManager.installExtension(spec) } },
                            ) { Text("安装") }
                        }
                    },
                )
            }
        }
    }
}
