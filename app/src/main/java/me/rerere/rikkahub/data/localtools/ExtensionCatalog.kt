package me.rerere.rikkahub.data.localtools

/**
 * 内置工具扩展包（与 MobiCode Studio 相同做法）：
 * 每个扩展是一个自包含 payload 目录（bin/ + lib/ + share/），
 * 直接解压后用 payload/bin/xxx 执行，wrapper 自己用 $0 推导 PREFIX，无需 rootfs/proot。
 */
data class ToolExtension(
    val id: String,
    val name: String,
    val version: String,
    val desc: String,
    /** 检测是否已安装用的命令名 */
    val bin: String,
    /** payload 内相对可执行路径 */
    val binRel: String,
    val sizeMb: Int,
    val group: String,
)

val TOOL_EXTENSIONS: List<ToolExtension> = listOf(
    ToolExtension("git", "Git", "2.55.0", "版本控制（含 curl/less）", "git", "bin/git", 28, "开发"),
    ToolExtension("ripgrep", "ripgrep", "14.1.1", "高速代码搜索 rg", "rg", "bin/rg", 3, "开发"),
    ToolExtension("openssh", "OpenSSH", "10.5p1", "ssh / scp / sftp", "ssh", "bin/ssh", 22, "网络"),
    ToolExtension("sqlite", "SQLite", "3.53.4", "sqlite3 命令行", "sqlite3", "bin/sqlite3", 8, "数据"),
)

/** 扩展包下载源：GitHub Release + 国内镜像 */
object ExtensionMirrors {
    private const val OWNER_REPO = "dormantxxy/RikkaHub"
    private const val TAG = "tools-v1"

    /** 依次尝试：ghfast → ghproxy.net → 直连 */
    val MIRRORS = listOf(
        "https://ghfast.top/https://github.com",
        "https://ghproxy.net/https://github.com",
        "https://github.com",
    )

    fun assetName(spec: ToolExtension): String = "${spec.id}-${spec.version}.tar.gz"

    fun url(spec: ToolExtension, mirror: String): String =
        "$mirror/$OWNER_REPO/releases/download/$TAG/${assetName(spec)}"
}
