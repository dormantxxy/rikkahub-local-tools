package me.rerere.rikkahub.data.localtools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 本地工具（Local Tools）配置
 *
 * 记录哪些 CLI 工具被启用、下载地址、安装状态等。
 * 这些工具运行在 app 的 files 目录下，不依赖 MobiCode Studio。
 */
@Serializable
data class LocalToolsConfig(
    /** git 是否启用 */
    val gitEnabled: Boolean = false,
    /** openssh 是否启用 */
    val sshEnabled: Boolean = false,
    /** python 是否启用 */
    val pythonEnabled: Boolean = false,
    /** 是否使用镜像站下载（默认 true，国内网络友好） */
    val useMirror: Boolean = true,
    /** 工具安装根目录（相对 app filesDir） */
    val toolsRoot: String = "localtools",
    /** git 版本 */
    val gitVersion: String = "",
    /** openssh 版本 */
    val sshVersion: String = "",
    /** python 版本 */
    val pythonVersion: String = "",
)

/** 本地工具类型 */
@Serializable
enum class LocalToolType {
    @SerialName("git")
    GIT,

    @SerialName("ssh")
    SSH,

    @SerialName("python")
    PYTHON,
}

/** 本地工具安装状态 */
@Serializable
enum class LocalToolStatus {
    @SerialName("not_installed")
    NOT_INSTALLED,

    @SerialName("installing")
    INSTALLING,

    @SerialName("installed")
    INSTALLED,

    @SerialName("error")
    ERROR,
}

/** 单个本地工具的运行时信息 */
data class LocalToolInfo(
    val type: LocalToolType,
    val enabled: Boolean,
    val status: LocalToolStatus,
    val version: String = "",
    val executablePath: String? = null,
    val error: String? = null,
)

/** 本地工具下载源（镜像站） */
object LocalToolMirrors {
    /** GitHub 原始下载地址（通过镜像加速） */
    private const val GITHUB_MIRROR_PREFIX = "https://ghfast.top/https://github.com"

    /** Git 的 Android 静态二进制（基于 termux-packages 构建） */
    const val GIT_DOWNLOAD_URL = "$GITHUB_MIRROR_PREFIX/rikkahub/rikkahub/releases/download/local-tools/git-android.tar.gz"
    const val GIT_VERSION = "2.47.0"

    /** OpenSSH 的 Android 静态二进制 */
    const val SSH_DOWNLOAD_URL = "$GITHUB_MIRROR_PREFIX/rikkahub/rikkahub/releases/download/local-tools/openssh-android.tar.gz"
    const val SSH_VERSION = "9.8p1"

    /** Python 的 Android 静态二进制 */
    const val PYTHON_DOWNLOAD_URL = "$GITHUB_MIRROR_PREFIX/rikkahub/rikkahub/releases/download/local-tools/python-android.tar.gz"
    const val PYTHON_VERSION = "3.12.4"
}
