package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.localtools.ExtensionInstaller
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
    private val extensionInstaller: ExtensionInstaller,
) {
    private val defaultDir = context.filesDir.absolutePath

    /**
     * 未绑定工作区时 AI 的默认工作目录：
     * 已授予「所有文件访问」→ /storage/emulated/0/RikkaHub/tmp（AI 临时区，用户可见可清理）；
     * 否则回退到 App 私有 files/RikkaHub。
     */
    private val rikkahubWorkDir: String by lazy {
        val publicDir = java.io.File("/storage/emulated/0/RikkaHub/tmp")
        val canUsePublic = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        val dir = if (canUsePublic) publicDir else java.io.File(context.filesDir, "RikkaHub")
        runCatching { dir.mkdirs() }
        // 授权了但目录不可写时仍回退私有，避免 AI 工具全部失败
        val writable = runCatching {
            val probe = java.io.File(dir, ".probe_" + System.nanoTime())
            probe.writeText("x")
            probe.delete()
            true
        }.getOrDefault(false)
        if (writable) dir.absolutePath
        else java.io.File(context.filesDir, "RikkaHub").apply { mkdirs() }.absolutePath
    }

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
    val runShellTool by lazy { buildRunShellTool(shellExecutor, rikkahubWorkDir, toolchainExecutor, extensionInstaller) }
    val manageFilesTool by lazy { buildManageFilesTool(shellExecutor, rikkahubWorkDir) }
    val searchCodeTool by lazy { buildSearchCodeTool(shellExecutor, rikkahubWorkDir) }
    val fileTreeTool by lazy { buildFileTreeTool(shellExecutor, rikkahubWorkDir) }
    val webFetchTool by lazy { buildWebFetchTool() }
    val runCliTool by lazy { buildRunCliTool(toolchainExecutor, rikkahubWorkDir) }

    // ===== 增强工具 =====
    val envInfoTool by lazy { buildEnvInfoTool(context, toolchainExecutor, extensionInstaller) }
    val pkgSearchTool by lazy { buildPkgSearchTool(context) }
    val pkgInstallTool by lazy { buildPkgInstallTool(context, extensionInstaller) }
    val tapTool by lazy { buildTapTool(shellExecutor) }
    val swipeTool by lazy { buildSwipeTool(shellExecutor) }
    val inputTextTool by lazy { buildInputTextTool(shellExecutor) }
    val uiDumpTool by lazy { buildUiDumpTool(shellExecutor) }
    val screenshotTool by lazy { buildScreenshotTool(context, shellExecutor) }
    val logcatTool by lazy { buildLogcatTool(shellExecutor) }
    val appManagerTool by lazy { buildAppManagerTool(shellExecutor) }

    fun getTools(
        options: List<LocalToolOption>,
        /** 设备 Shell 是否需要审批（来自工作区工具审批设置，默认需要）。 */
        deviceShellApproval: Boolean = true,
    ): List<Tool> {
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
        if (options.contains(LocalToolOption.Shell)) {
            // 设备 Shell 审批开关：关闭时完全不拦截（包括风险命令）；开启时仅风险命令需要审批
            tools.add(
                if (deviceShellApproval) {
                    runShellTool.copy(needsApproval = { input -> runShellTool.needsApproval(input) })
                } else {
                    runShellTool.copy(needsApproval = { false })
                }
            )
        }
        if (options.contains(LocalToolOption.Files)) tools.add(manageFilesTool)
        if (options.contains(LocalToolOption.SearchCode)) tools.add(searchCodeTool)
        if (options.contains(LocalToolOption.FileTree)) tools.add(fileTreeTool)
        if (options.contains(LocalToolOption.WebFetch)) tools.add(webFetchTool)
        // run_cli 依赖 root/Shizuku：读 App 启动时缓存的权限状态（轻量，不启动 su）
        if (options.contains(LocalToolOption.Cli) && toolchainExecutor.hasPrivilege()) tools.add(runCliTool)
        if (options.contains(LocalToolOption.Toolchain)) {
            tools.add(envInfoTool)
            tools.add(pkgSearchTool)
            tools.add(pkgInstallTool)
        }
        // UI 自动化 / 截屏 / logcat 依赖 root 或 Shizuku：无权限时不暴露给 AI
        if (options.contains(LocalToolOption.UiAutomation) && toolchainExecutor.hasPrivilege()) {
            tools.add(tapTool)
            tools.add(swipeTool)
            tools.add(inputTextTool)
            tools.add(uiDumpTool)
        }
        if (options.contains(LocalToolOption.Screenshot) && toolchainExecutor.hasPrivilege()) tools.add(screenshotTool)
        if (options.contains(LocalToolOption.Logcat) && toolchainExecutor.hasPrivilege()) tools.add(logcatTool)
        if (options.contains(LocalToolOption.AppManager) && toolchainExecutor.hasPrivilege()) tools.add(appManagerTool)
        return tools
    }
}
