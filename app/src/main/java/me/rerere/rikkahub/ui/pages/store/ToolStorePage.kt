package me.rerere.rikkahub.ui.pages.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.data.localtools.LocalToolsManager
import me.rerere.rikkahub.data.localtools.STORE_PACKAGES
import me.rerere.rikkahub.data.localtools.StoreGroups
import me.rerere.rikkahub.data.localtools.StorePackage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun ToolStorePage() {
    val mgr: LocalToolsManager = koinInject()
    val env by mgr.linuxEnv.collectAsStateWithLifecycle()
    val pkgs by mgr.packageStates.collectAsStateWithLifecycle()
    val installing by mgr.installingPackages.collectAsStateWithLifecycle()
    val err by mgr.linuxPkgError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var query by remember { mutableStateOf("") }
    var hasPriv by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        hasPriv = mgr.hasPrivilege()
        mgr.refreshPackages()
    }

    val filtered = remember(query) {
        if (query.isBlank()) STORE_PACKAGES
        else STORE_PACKAGES.filter {
            it.label.contains(query, true) || it.pkg.contains(query, true) || it.desc.contains(query, true)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("扩展商店") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ===== 基础环境（必装，其他扩展都依赖它） =====
            item("env") {
                CardGroup(title = { Text("基础环境（必装）") }) {
                    item(
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Package,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = { Text(if (env.installed) "Linux 环境已就绪" else "安装 Linux 环境（3.9MB）") },
                        supportingContent = {
                            Text(
                                if (env.installed) "所有扩展都安装并运行在此环境中"
                                else "Alpine + 内置 proot，走公共镜像（阿里云/清华/官方）"
                            )
                        },
                        trailingContent = {
                            when {
                                !hasPriv -> Text("需要 root/Shizuku", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                env.installing -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                env.installed -> TextButton(onClick = { scope.launch { mgr.removeLinuxEnv() } }) { Text("卸载") }
                                else -> FilledTonalButton(onClick = { scope.launch { mgr.installLinuxEnv() } }) { Text("安装") }
                            }
                        },
                        onClick = if (!hasPriv) ({
                            hasPriv = mgr.hasPrivilege()
                        }) else null,
                    )
                    if (env.installing) {
                        formItem {
                            Column {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(env.message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    env.error?.let { e ->
                        item(
                            headlineContent = { Text(e, color = MaterialTheme.colorScheme.error) },
                            trailingContent = { TextButton(onClick = { mgr.dismissErrors() }) { Text("关闭") } },
                        )
                    }
                }
            }

            // ===== 搜索 =====
            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索扩展（git / python / ffmpeg …）") },
                    singleLine = true,
                )
            }

            // ===== 分组列表（点击展开） =====
            for (group in StoreGroups.ORDER) {
                val groupItems = filtered.filter { it.group == group }
                if (groupItems.isEmpty()) continue
                val open = query.isNotBlank() || expanded[group] == true
                item("h_$group") {
                    CardGroup {
                        item(
                            leadingContent = {
                                CardGroupIcon(
                                    icon = if (open) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            },
                            headlineContent = { Text(group) },
                            supportingContent = { Text("${groupItems.size} 个扩展") },
                            onClick = { expanded[group] = !open },
                        )
                    }
                }
                if (open) {
                    items(groupItems, key = { it.pkg }) { p ->
                        PackageRow(
                            p = p,
                            installed = pkgs[p.pkg] == true,
                            busy = p.pkg in installing,
                            envReady = env.installed,
                            hasPriv = hasPriv,
                            onInstall = { scope.launch { mgr.installPackage(p.pkg) } },
                        )
                    }
                }
            }

            err?.let { e ->
                item("err") {
                    CardGroup {
                        item(
                            headlineContent = { Text(e, color = MaterialTheme.colorScheme.error) },
                            trailingContent = { TextButton(onClick = { mgr.dismissErrors() }) { Text("关闭") } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageRow(
    p: StorePackage,
    installed: Boolean,
    busy: Boolean,
    envReady: Boolean,
    hasPriv: Boolean,
    onInstall: () -> Unit,
) {
    CardGroup {
        item(
            headlineContent = { Text(p.label) },
            supportingContent = { Text(if (busy) "安装中..." else p.desc, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    installed -> Text("已安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    !hasPriv || !envReady -> Text("不可用", style = MaterialTheme.typography.bodySmall)
                    else -> TextButton(onClick = onInstall) { Text("安装") }
                }
            },
        )
    }
}
