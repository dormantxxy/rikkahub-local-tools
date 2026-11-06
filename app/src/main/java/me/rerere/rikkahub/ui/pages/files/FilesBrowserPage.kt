package me.rerere.rikkahub.ui.pages.files

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File

/** 文件浏览页的两个区域。 */
private enum class FilesArea { DOCUMENTS, RUNTIME }

/**
 * 文件浏览页。
 * - 文件目录：/storage/emulated/0/RikkaHub/
 * - 运行环境：已装扩展的安装目录（extensions/）
 */
@Composable
fun FilesBrowserPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var area by remember { mutableStateOf(FilesArea.DOCUMENTS) }
    var path by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    val storageGranted by produceState(initialValue = true) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            } else {
                true
            }
        }
    }

    val rootDir = remember(area) {
        when (area) {
            FilesArea.DOCUMENTS -> File("/storage/emulated/0/RikkaHub")
            FilesArea.RUNTIME -> File("/data/user/0/me.rerere.rikkahub/files/extensions")
        }
    }

    val entries by produceState(initialValue = emptyList<File>(), rootDir, path, refreshKey) {
        value = withContext(Dispatchers.IO) {
            val dir = if (path.isBlank()) rootDir else File(rootDir, path)
            dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }
    }

    LaunchedEffect(area) { path = "" }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("文件") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ConnectedChoiceButtons(
                options = FilesArea.entries,
                selected = area,
                onSelect = { area = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { a, selected ->
                ConnectedChoiceLabel(
                    text = when (a) {
                        FilesArea.DOCUMENTS -> "文件目录"
                        FilesArea.RUNTIME -> "运行环境"
                    },
                    selected = selected,
                    icon = if (a == FilesArea.DOCUMENTS) HugeIcons.Folder01 else HugeIcons.FolderOpen,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (path.isBlank()) rootDir.absolutePath else "${rootDir.absolutePath}/$path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (path.isNotBlank()) {
                    IconButton(onClick = { path = path.substringBeforeLast('/', "") }) {
                        Icon(HugeIcons.ArrowUp01, contentDescription = "上一级")
                    }
                }
            }

            val showPermissionHint = area == FilesArea.DOCUMENTS && !storageGranted
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    showPermissionHint -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Text("需要「所有文件访问」权限才能浏览公共目录")
                            Text(
                                "请在 设置 → 数据设置 → 所有文件访问 中授权",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    entries.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "目录为空",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries, key = { it.absolutePath }) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) {
                                        path = if (path.isBlank()) file.name else "$path/${file.name}"
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = CustomColors.listItemColors.containerColor,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (file.isDirectory) HugeIcons.Folder01 else HugeIcons.File01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!file.isDirectory) {
                    Text(
                        text = fileSizeToString(file.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
