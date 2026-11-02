package me.rerere.rikkahub.ui.pages.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.data.localtools.TaskPlanStore
import me.rerere.rikkahub.data.localtools.ToolLogStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TaskPlanPage() {
    val store: TaskPlanStore = koinInject()
    val tasks by store.tasks.collectAsStateWithLifecycle()
    val logs by ToolLogStore.logs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var input by remember { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("任务计划") },
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
            item("input") {
                CardGroup(title = { Text("新增任务") }) {
                    formItem {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入任务，如：检查构建错误") },
                                singleLine = true,
                            )
                            IconButton(
                                onClick = {
                                    if (input.isNotBlank()) {
                                        store.add(input)
                                        input = ""
                                    }
                                },
                            ) {
                                Icon(HugeIcons.Add01, contentDescription = "添加")
                            }
                        }
                    }
                }
            }

            item("tasks") {
                CardGroup(title = { Text("任务（${tasks.count { !it.done }}/${tasks.size} 未完成）") }) {
                    if (tasks.isEmpty()) {
                        item(headlineContent = { Text("暂无任务，AI 也可以通过 manage_tasks 创建") })
                    } else {
                        for (t in tasks) {
                            item(
                                headlineContent = {
                                    Text(
                                        text = t.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                leadingContent = {
                                    Checkbox(checked = t.done, onCheckedChange = { store.setDone(t.id, it) })
                                },
                                trailingContent = {
                                    IconButton(onClick = { store.delete(t.id) }) {
                                        Icon(HugeIcons.Delete01, contentDescription = "删除")
                                    }
                                },
                            )
                        }
                        item(
                            headlineContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { store.clearDone() }) { Text("清除已完成") }
                                }
                            },
                        )
                    }
                }
            }

            item("logsHeader") {
                CardGroup(title = { Text("工具执行日志（最近 ${logs.size} 条）") }) {
                    item(
                        headlineContent = { Text("实时记录 AI 调用工具的参数与结果") },
                        trailingContent = {
                            TextButton(onClick = { ToolLogStore.clear() }) { Text("清空") }
                        },
                    )
                }
            }

            items(logs) { entry ->
                CardGroup {
                    item(
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.TransactionHistory,
                                containerColor = if (entry.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                contentColor = if (entry.ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        headlineContent = {
                            Text("${entry.tool}  ${timeFmt.format(Date(entry.at))}  ${entry.durationMs}ms")
                        },
                        supportingContent = {
                            Column {
                                Text("参数: ${entry.args}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("结果: ${entry.result}", maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        },
                    )
                }
            }
        }
    }
}
