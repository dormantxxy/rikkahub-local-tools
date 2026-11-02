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
    ToolExtension("python", "Python", "3.14.6", "Python 运行时 + pip", "python3", "bin/python3", 96, "开发"),
)

/** 扩展包下载源：GitHub Release + 国内镜像 */
object ExtensionMirrors {
    private const val OWNER_REPO = "dormantxxy/RikkaHub"

    /** 依次尝试：ghfast → ghproxy.net → 直连 */
    val MIRRORS = listOf(
        "https://ghfast.top/https://github.com",
        "https://ghproxy.net/https://github.com",
        "https://github.com",
    )

    /** 资产名不带版本，配合 releases/latest 自动取最新 */
    fun assetName(spec: ToolExtension): String = "${spec.id}.tar.gz"

    fun url(spec: ToolExtension, mirror: String): String =
        "$mirror/$OWNER_REPO/releases/latest/download/${assetName(spec)}"
}

/** 完整 Linux 环境（Alpine + 内置 proot）：仅 3.9MB，不依赖工作区 */
object LinuxEnv {
    const val VERSION = "3.20.3"
    /** 依次尝试的镜像（阿里云最快） */
    val MIRRORS = listOf(
        "https://mirrors.aliyun.com/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
    )
    const val APK_MAIN = "https://mirrors.aliyun.com/alpine/v3.20/main"
    const val APK_COMMUNITY = "https://mirrors.aliyun.com/alpine/v3.20/community"
}

/** 完整环境里可一键安装的常用包 */
data class LinuxPackage(val pkg: String, val label: String, val desc: String, val bin: String)

val LINUX_PACKAGES: List<LinuxPackage> = listOf(
    LinuxPackage("build-base", "编译工具 (gcc/make)", "C/C++ 编译全套", "gcc"),
    LinuxPackage("make", "make", "构建工具", "make"),
    LinuxPackage("git", "Git", "版本控制", "git"),
    LinuxPackage("python3", "Python 3", "脚本运行", "python3"),
    LinuxPackage("py3-pip", "pip", "Python 包管理", "pip3"),
    LinuxPackage("nodejs", "Node.js", "JS 运行时", "node"),
    LinuxPackage("npm", "npm", "Node 包管理", "npm"),
    LinuxPackage("ffmpeg", "FFmpeg", "音视频处理", "ffmpeg"),
    LinuxPackage("vim", "vim", "编辑器", "vim"),
    LinuxPackage("curl", "curl", "HTTP 工具", "curl"),
    LinuxPackage("jq", "jq", "JSON 处理", "jq"),
    LinuxPackage("openssh-client", "OpenSSH", "ssh/scp", "ssh"),
    LinuxPackage("zip", "zip", "压缩", "zip"),
    LinuxPackage("unzip", "unzip", "解压", "unzip"),
    LinuxPackage("sqlite", "SQLite", "sqlite3", "sqlite3"),
    LinuxPackage("rsync", "rsync", "增量同步", "rsync"),
    LinuxPackage("nano", "nano", "轻量编辑器", "nano"),
    LinuxPackage("less", "less", "分页查看", "less"),
    LinuxPackage("tree", "tree", "目录树", "tree"),
    LinuxPackage("fzf", "fzf", "模糊搜索", "fzf"),
    LinuxPackage("tmux", "tmux", "终端复用", "tmux"),
    LinuxPackage("htop", "htop", "进程监控", "htop"),
    LinuxPackage("procps", "ps/top", "进程工具", "ps"),
    LinuxPackage("diffutils", "diff", "文件比较", "diff"),
    LinuxPackage("patch", "patch", "打补丁", "patch"),
    LinuxPackage("file", "file", "文件类型", "file"),
    LinuxPackage("which", "which", "命令定位", "which"),
    LinuxPackage("wget", "wget", "下载", "wget"),
    LinuxPackage("openssl", "openssl", "加密/证书", "openssl"),
    LinuxPackage("zstd", "zstd", "zstandard 压缩", "zstd"),
    LinuxPackage("xz", "xz", "xz 压缩", "xz"),
    LinuxPackage("tar", "tar", "归档", "tar"),
    LinuxPackage("netcat-openbsd", "nc", "网络调试", "nc"),
    LinuxPackage("bind-tools", "dig", "DNS 查询", "dig"),
    LinuxPackage("traceroute", "traceroute", "路由追踪", "traceroute"),
    LinuxPackage("imagemagick", "convert", "图像处理", "convert"),
    LinuxPackage("yq", "yq", "YAML 处理", "yq"),
)
