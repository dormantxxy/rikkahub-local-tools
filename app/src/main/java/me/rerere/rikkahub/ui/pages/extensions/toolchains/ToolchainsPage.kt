package me.rerere.rikkahub.ui.pages.extensions.toolchains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

@Composable
fun ToolchainsPage() {
    val vm: ToolchainsVM = koinViewModel()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toolchainItems by vm.items.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val privilege by vm.privilege.collectAsStateWithLifecycle()
    val remoteResults by vm.remoteResults.collectAsStateWithLifecycle()
    val remoteLoading by vm.remoteLoading.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current

    LaunchedEffect(message) {
        message?.let {
            toaster.show(message = it, type = ToastType.Info)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("工具链扩展") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "在线下载并安装原生 arm64 工具链（MobiCode .mcext）。安装后可在 run_cli 里直接使用 " +
                        "git / python3 / node / gcc(clang) / java / apktool / php / sqlite3 / rg / flutter / go / rust 等。" +
                        "原生直跑使用 root 或 Shizuku 权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索镜像站全部包（名称 / 描述 / 分类 / 包名）") },
                )
            }
            item {
                PrivilegeBanner(privilege = privilege, onRequestShizuku = { vm.requestShizuku() }, onRefresh = { vm.refreshPrivilege() })
            }
            if (remoteLoading && query.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                        Text(
                            "正在搜索镜像站…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(toolchainItems, key = { it.ext.id }) { item ->
                ToolchainCard(
                    item = item,
                    progress = progress?.takeIf { it.id == item.ext.id },
                    busy = progress != null,
                    onInstall = { vm.install(item.ext) },
                    onUninstall = { vm.uninstall(item.ext) },
                )
            }
            if (remoteResults.isNotEmpty()) {
                item {
                    Text(
                        "镜像站搜索结果（" + remoteResults.size + " 个）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(remoteResults, key = { it.id }) { ext ->
                    val installed = vm.installedRecord(ext.id)
                    ToolchainCard(
                        item = ToolchainItem(ext, installed),
                        progress = progress?.takeIf { it.id == ext.id },
                        busy = progress != null,
                        onInstall = { vm.install(ext) },
                        onUninstall = { vm.uninstall(ext) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivilegeBanner(privilege: String, onRequestShizuku: () -> Unit, onRefresh: () -> Unit) {
    when (privilege) {
        "root" -> Text(
            "✓ 已获得 root，原生工具链可直跑",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        "shizuku" -> Text(
            "✓ 已获得 Shizuku 授权，原生工具链可直跑",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        "shizuku_unauthorized" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Shizuku 正在运行，但本应用尚未授权（请在 Shizuku 里允许本应用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRequestShizuku) { Text("授予 Shizuku 权限") }
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "⚠️ 未检测到 root / Shizuku，暂时无法直跑原生工具链。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}

@Composable
private fun ToolchainCard(
    item: ToolchainItem,
    progress: InstallProgress?,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.ext.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "v${item.ext.version} · ${item.ext.publisher}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.isInstalled) {
                    Text(
                        "已安装",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (item.ext.description.isNotBlank()) {
                Text(
                    item.ext.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${humanSize(item.ext.downloadSize)} · ${item.ext.category}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item.ext.sourceRepo.ifBlank { "来源 GitHub" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    progress.text,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (item.isInstalled) {
                    OutlinedButton(onClick = onUninstall, enabled = !busy) { Text("卸载") }
                } else {
                    FilledTonalButton(onClick = onInstall, enabled = !busy) {
                        Text(if (progress != null) "安装中…" else "安装")
                    }
                }
            }
        }
    }
}
