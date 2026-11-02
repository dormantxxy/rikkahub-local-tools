package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.datastore.AutomationConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingAutomationPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cfg = settings.automationConfig

    fun update(block: (AutomationConfig) -> AutomationConfig) {
        scope.launch {
            settingsStore.update { it.copy(automationConfig = block(it.automationConfig)) }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("自动化") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

            CardGroup(title = { Text("总开关") }) {
                switchItem(
                    checked = cfg.enabled,
                    onCheckedChange = { v -> update { it.copy(enabled = v) } },
                    leadingContent = { CardGroupIcon(HugeIcons.Zap, containerColor = containerColor, contentColor = contentColor) },
                    headlineContent = { Text("启用自动化") },
                    supportingContent = { Text("模型生成出错时自动识别并执行恢复方案（手动中断不处理）") },
                )
            }

            CardGroup(title = { Text("错误识别与恢复方案") }) {
                switchItem(
                    checked = cfg.autoRetryNetwork,
                    onCheckedChange = { v -> update { it.copy(autoRetryNetwork = v) } },
                    leadingContent = { CardGroupIcon(HugeIcons.Internet, containerColor = contentColor, contentColor = containerColor) },
                    headlineContent = { Text("网络异常") },
                    supportingContent = { Text("识别：连接超时 / 断网 / IO 异常 → 方案：延迟后自动重试（最多 2 次）") },
                )
                switchItem(
                    checked = cfg.autoResumeThinking,
                    onCheckedChange = { v -> update { it.copy(autoResumeThinking = v) } },
                    leadingContent = { CardGroupIcon(HugeIcons.Brain02, containerColor = contentColor, contentColor = containerColor) },
                    headlineContent = { Text("思考中断") },
                    supportingContent = { Text("识别：思考/推理流中断 → 方案：在原位继续生成，接上思考流程") },
                )
                switchItem(
                    checked = cfg.autoRecoverContext,
                    onCheckedChange = { v -> update { it.copy(autoRecoverContext = v) } },
                    leadingContent = { CardGroupIcon(HugeIcons.AlertCircle, containerColor = contentColor, contentColor = containerColor) },
                    headlineContent = { Text("上下文上限") },
                    supportingContent = { Text("识别：超出上下文长度 / token 上限 → 方案：自动继续尝试（最多 2 次）") },
                )
            }
        }
    }
}
