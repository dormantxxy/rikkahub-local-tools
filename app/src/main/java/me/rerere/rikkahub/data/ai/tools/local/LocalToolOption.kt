package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    @Serializable
    @SerialName("shell")
    data object Shell : LocalToolOption()

    @Serializable
    @SerialName("files")
    data object Files : LocalToolOption()

    @Serializable
    @SerialName("cli")
    data object Cli : LocalToolOption()

    @Serializable
    @SerialName("web_fetch")
    data object WebFetch : LocalToolOption()

    @Serializable
    @SerialName("search_code")
    data object SearchCode : LocalToolOption()

    @Serializable
    @SerialName("file_tree")
    data object FileTree : LocalToolOption()

    @Serializable
    @SerialName("tasks")
    data object Tasks : LocalToolOption()
}
