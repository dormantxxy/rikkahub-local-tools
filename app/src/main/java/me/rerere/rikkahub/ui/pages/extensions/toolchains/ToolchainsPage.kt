package me.rerere.rikkahub.ui.pages.extensions.toolchains

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.localtools.InstallPhase
import me.rerere.rikkahub.data.localtools.MobiCodeCatalog
import me.rerere.rikkahub.data.localtools.ToolchainExtension
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
    val itemList by vm.items.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val privilege by vm.privilege.collectAsStateWithLifecycle()
    val selectedVersions by vm.selectedVersions.collectAsStateWithLifecycle()
    val termuxLoading by vm.termuxLoading.collectAsStateWithLifecycle()
    val termuxError by vm.termuxError.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val listState = rememberLazyListState()

    LaunchedEffect(message) {
        message?.let {
            toaster.show(message = it, type = ToastType.Info)
            vm.clearMessage()
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // ===== 固定区：搜索框 =====
            CompactSearchBar(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            )
            // ===== 固定区：分类按钮 =====
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            ) {
                items(MobiCodeCatalog.CATEGORIES) { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { vm.setCategory(cat) },
                        label = { Text(cat, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            // ===== 固定区：加载/错误状态 =====
            if (termuxLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "正在加载在线扩展索引（3000+ 包）…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            termuxError?.let { error ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "在线索引加载失败：$error",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.ensureTermuxIndex(force = true) }) { Text("重试") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrivilegeText(privilege = privilege, onRefresh = { vm.refreshPrivilege() }, modifier = Modifier.weight(1f))
            }
            // ===== 下拉刷新 + 只有列表可滚动 =====
            PullToRefreshBox(
                isRefreshing = termuxLoading,
                onRefresh = { vm.ensureTermuxIndex(force = true) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        when {
                            query.isNotBlank() -> "搜索结果（${itemList.size}）"
                            category == "精选" -> "精选扩展"
                            else -> category
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    )
                }
                if (itemList.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (query.isBlank()) "暂无扩展" else "没有找到相关扩展",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(itemList, key = { it.ext.id }) { item ->
                    StoreCard(
                        item = item,
                        progress = progress?.takeIf { it.id == item.ext.id },
                        busy = progress != null,
                        selectedVersion = selectedVersions[item.ext.id] ?: item.ext.availableVersions.first().version,
                        onSelectVersion = { vm.selectVersion(item.ext, it) },
                        onInstall = { vm.install(item.ext) },
                        onUninstall = { vm.uninstall(item.ext) },
                        onCancel = { vm.cancelInstall() },
                    )
                }
            }
            }
        }
    }
}

/** 紧凑搜索框（正常搜索框高度）。 */
@Composable
private fun CompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.Search01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            "搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}

@Composable
private fun PrivilegeText(privilege: String, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (privilege) {
        "root" -> "✓ 已获得 root"
        "shizuku" -> "✓ 已获得 Shizuku 授权"
        "shizuku_unauthorized" -> "Shizuku 未授权（请在 Shizuku 管理器中开启）"
        else -> "⚠️ 未检测到 root / Shizuku"
    }
    val color = if (privilege == "root" || privilege == "shizuku") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
        if (privilege != "root" && privilege != "shizuku") {
            TextButton(onClick = onRefresh) { Text("刷新", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun StoreCard(
    item: ToolchainItem,
    progress: InstallProgress?,
    busy: Boolean,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val ext: ToolchainExtension = item.ext
    var versionMenuExpanded by remember { mutableStateOf(false) }
    val iconColor = categoryColor(ext.category)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(iconColor.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ext.name.take(1).uppercase(),
                    color = iconColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ext.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        item.hasUpdate -> Text(
                            "可更新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        item.isInstalled -> Text(
                            "已安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "v$selectedVersion · ${ext.publisher} · ${humanSize(ext.downloadSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ext.description.isNotBlank()) {
                    Text(
                        ext.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ext.depends.isNotEmpty()) {
                    Text(
                        "依赖 ${ext.depends.size} 个包：" + ext.depends.take(4).joinToString("、") +
                            if (ext.depends.size > 4) " 等，安装时自动安装" else "，安装时自动安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(progress.text, style = MaterialTheme.typography.labelSmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 版本选择：始终可点击选择（Termux 包也显示当前版本）
                    Box {
                        TextButton(onClick = { versionMenuExpanded = true }, enabled = !busy) {
                            Text("版本：v$selectedVersion ▾", style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(
                            expanded = versionMenuExpanded,
                            onDismissRequest = { versionMenuExpanded = false },
                        ) {
                            ext.availableVersions.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text("v${v.version}  (${humanSize(v.downloadSize)})", style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        onSelectVersion(v.version)
                                        versionMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (progress != null) {
                            OutlinedButton(onClick = onCancel) { Text("取消安装") }
                        } else if (item.isInstalled) {
                            OutlinedButton(onClick = onUninstall, enabled = !busy) { Text("卸载") }
                            if (item.hasUpdate) {
                                FilledTonalButton(onClick = onInstall, enabled = !busy) { Text("更新") }
                            }
                        } else {
                            FilledTonalButton(onClick = onInstall, enabled = !busy) { Text("安装") }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryColor(category: String): Color = when (category) {
    "LANGUAGE" -> Color(0xFF4CAF50)
    "TOOLCHAIN" -> Color(0xFF2196F3)
    "AI" -> Color(0xFF673AB7)
    "SOURCE_CONTROL" -> Color(0xFF00897B)
    "ENVIRONMENT" -> Color(0xFF009688)
    "EDITOR" -> Color(0xFF9C27B0)
    "NETWORK" -> Color(0xFF00BCD4)
    "DATABASE" -> Color(0xFFFF9800)
    "MEDIA" -> Color(0xFFE91E63)
    "GAME" -> Color(0xFFF44336)
    "LIBRARY" -> Color(0xFF795548)
    else -> Color(0xFF607D8B)
}
