package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.localtools.LocalToolsManager
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ToolLogEntry
import me.rerere.rikkahub.data.localtools.ToolLogStore
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
    private val toolsManager: LocalToolsManager,
) {
    val javascriptTool by lazy { buildJavascriptTool() }
    val timeTool by lazy { buildTimeInfoTool() }
    val clipboardTool by lazy { buildClipboardTool(context) }
    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }
    val askUserTool by lazy { buildAskUserTool() }
    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }
    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }
    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }
    val chartDisplayTool by lazy { buildChartDisplayTool() }

    val runShellTool by lazy { buildRunShellTool(shellExecutor, settingsStore, context.filesDir.absolutePath) }
    val manageFilesTool by lazy { buildManageFilesTool(shellExecutor, settingsStore) }
    val runCliTool by lazy { buildRunCliTool(shellExecutor, toolsManager, settingsStore, context.filesDir.absolutePath) }
    val webFetchTool by lazy { buildWebFetchTool() }
    val searchCodeTool by lazy { buildSearchCodeTool(shellExecutor, toolsManager, settingsStore) }
    val fileTreeTool by lazy { buildFileTreeTool(shellExecutor, toolsManager, settingsStore) }

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
        // 只支持 root / Shizuku：无权限时这些 CLI 类工具不注册，避免 AI 调用报错
        val privileged = shellExecutor.hasPrivilege()
        if (privileged && options.contains(LocalToolOption.Shell)) tools.add(runShellTool)
        if (privileged && options.contains(LocalToolOption.Files)) tools.add(manageFilesTool)
        if (privileged && options.contains(LocalToolOption.Cli)) tools.add(runCliTool)
        if (privileged && options.contains(LocalToolOption.SearchCode)) tools.add(searchCodeTool)
        if (privileged && options.contains(LocalToolOption.FileTree)) tools.add(fileTreeTool)
        if (options.contains(LocalToolOption.WebFetch)) tools.add(webFetchTool)
        return tools.map { it.logged() }
    }

    /** 记录每次工具调用的参数/结果/耗时，供「任务计划」面板查看 */
    private fun Tool.logged(): Tool {
        val origin = this
        return origin.copy(
            execute = { args ->
                val start = System.currentTimeMillis()
                try {
                    val result = origin.execute(args)
                    val summary = result.joinToString("\n") { it.toString() }
                    ToolLogStore.add(
                        ToolLogEntry(
                            tool = origin.name,
                            args = args.toString().take(500),
                            result = summary.take(500),
                            ok = true,
                            durationMs = System.currentTimeMillis() - start,
                        )
                    )
                    result
                } catch (e: Throwable) {
                    ToolLogStore.add(
                        ToolLogEntry(
                            tool = origin.name,
                            args = args.toString().take(500),
                            result = (e.message ?: e.javaClass.simpleName).take(500),
                            ok = false,
                            durationMs = System.currentTimeMillis() - start,
                        )
                    )
                    throw e
                }
            }
        )
    }
}
