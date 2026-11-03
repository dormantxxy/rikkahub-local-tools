package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ToolchainExecutor
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
    private val toolchainExecutor: ToolchainExecutor,
) {
    private val defaultDir = context.filesDir.absolutePath
    private val rikkahubWorkDir = java.io.File(context.filesDir, "RikkaHub").apply { mkdirs() }.absolutePath

    val javascriptTool by lazy { buildJavascriptTool() }
    val timeTool by lazy { buildTimeInfoTool() }
    val clipboardTool by lazy { buildClipboardTool(context) }
    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }
    val askUserTool by lazy { buildAskUserTool() }
    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }
    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }
    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }
    val chartDisplayTool by lazy { buildChartDisplayTool() }

    // ===== 本地工具（设备 Shell / 文件 / 检索 / 文件树 / 网页浏览 / CLI 工具链） =====
    val runShellTool by lazy { buildRunShellTool(shellExecutor, defaultDir) }
    val manageFilesTool by lazy { buildManageFilesTool(shellExecutor, defaultDir) }
    val searchCodeTool by lazy { buildSearchCodeTool(shellExecutor, defaultDir) }
    val fileTreeTool by lazy { buildFileTreeTool(shellExecutor, defaultDir) }
    val webFetchTool by lazy { buildWebFetchTool() }
    val runCliTool by lazy { buildRunCliTool(toolchainExecutor, rikkahubWorkDir) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.TimeInfo)) tools.add(timeTool)
        if (options.contains(LocalToolOption.Clipboard)) tools.add(clipboardTool)
        if (options.contains(LocalToolOption.Tts)) tools.add(ttsTool)
        if (options.contains(LocalToolOption.AskUser)) tools.add(askUserTool)
        if (options.contains(LocalToolOption.ScreenTime)) tools.add(screenTimeTool)
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) tools.add(chartDisplayTool)
        if (options.contains(LocalToolOption.Shell)) tools.add(runShellTool)
        if (options.contains(LocalToolOption.Files)) tools.add(manageFilesTool)
        if (options.contains(LocalToolOption.SearchCode)) tools.add(searchCodeTool)
        if (options.contains(LocalToolOption.FileTree)) tools.add(fileTreeTool)
        if (options.contains(LocalToolOption.WebFetch)) tools.add(webFetchTool)
        // run_cli 依赖 root/Shizuku：读 App 启动时缓存的权限状态（轻量，不启动 su）
        if (options.contains(LocalToolOption.Cli) && toolchainExecutor.hasPrivilege()) tools.add(runCliTool)
        return tools
    }
}
