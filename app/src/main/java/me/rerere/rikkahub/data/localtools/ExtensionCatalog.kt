package me.rerere.rikkahub.data.localtools

/**
 * 完整 Linux 环境（Alpine + APK 内置 proot）：
 * 全部走公共镜像在线下载，不需要任何自建/自托管资源。
 */
object LinuxEnv {
    const val VERSION = "3.20.3"

    /** 依次尝试的公共镜像 */
    val MIRRORS = listOf(
        "https://mirrors.aliyun.com/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
    )

    val APK_REPOS = listOf(
        "https://mirrors.aliyun.com/alpine/v3.20/main",
        "https://mirrors.aliyun.com/alpine/v3.20/community",
    )
}

/** 环境里可一键安装的常用 CLI（apk 包名 → 显示） */
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
    LinuxPackage("openssh-client", "OpenSSH", "ssh/scp/sftp", "ssh"),
    LinuxPackage("rsync", "rsync", "增量同步", "rsync"),
    LinuxPackage("curl", "curl", "HTTP 工具", "curl"),
    LinuxPackage("wget", "wget", "下载", "wget"),
    LinuxPackage("jq", "jq", "JSON 处理", "jq"),
    LinuxPackage("yq", "yq", "YAML 处理", "yq"),
    LinuxPackage("vim", "vim", "编辑器", "vim"),
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
    LinuxPackage("openssl", "openssl", "加密/证书", "openssl"),
    LinuxPackage("zip", "zip", "压缩", "zip"),
    LinuxPackage("unzip", "unzip", "解压", "unzip"),
    LinuxPackage("sqlite", "SQLite", "sqlite3", "sqlite3"),
    LinuxPackage("zstd", "zstd", "zstandard", "zstd"),
    LinuxPackage("xz", "xz", "xz 压缩", "xz"),
    LinuxPackage("tar", "tar", "归档", "tar"),
    LinuxPackage("netcat-openbsd", "nc", "网络调试", "nc"),
    LinuxPackage("bind-tools", "dig", "DNS 查询", "dig"),
    LinuxPackage("traceroute", "traceroute", "路由追踪", "traceroute"),
    LinuxPackage("imagemagick", "ImageMagick", "图像处理", "convert"),
)
