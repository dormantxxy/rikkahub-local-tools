package me.rerere.rikkahub.data.localtools

/**
 * 工作区 rootfs 里可按需安装的常用工具包。
 * [apkPkg] 用于 Alpine，[aptPkg] 用于 Debian/Ubuntu；[bin] 用于检测是否已安装。
 */
data class RootfsPackage(
    val label: String,
    val desc: String,
    val bin: String,
    val apkPkg: String,
    val aptPkg: String,
    val group: RootfsPackageGroup,
) {
    fun pkgFor(distro: RootfsDistro): String = when (distro) {
        RootfsDistro.ALPINE -> apkPkg
        RootfsDistro.DEBIAN -> aptPkg
        RootfsDistro.UNKNOWN -> apkPkg
    }
}

enum class RootfsDistro { ALPINE, DEBIAN, UNKNOWN }

enum class RootfsPackageGroup(val title: String) {
    BASIC("基础工具"),
    DEV("开发工具"),
    NETWORK("网络与传输"),
    MEDIA("媒体与数据"),
}

val ROOTFS_PACKAGES: List<RootfsPackage> = listOf(
    // 基础
    RootfsPackage("Bash", "更完整的 shell", "bash", "bash", "bash", RootfsPackageGroup.BASIC),
    RootfsPackage("Coreutils", "标准 Unix 工具集", "ls", "coreutils", "coreutils", RootfsPackageGroup.BASIC),
    RootfsPackage("curl", "HTTP 请求工具", "curl", "curl", "curl", RootfsPackageGroup.BASIC),
    RootfsPackage("wget", "下载工具", "wget", "wget", "wget", RootfsPackageGroup.BASIC),
    RootfsPackage("unzip", "解压 zip", "unzip", "unzip", "unzip", RootfsPackageGroup.BASIC),
    RootfsPackage("jq", "JSON 处理", "jq", "jq", "jq", RootfsPackageGroup.BASIC),
    RootfsPackage("openssl", "加密/证书", "openssl", "openssl", "openssl", RootfsPackageGroup.BASIC),
    RootfsPackage("file", "文件类型识别", "file", "file", "file", RootfsPackageGroup.BASIC),
    // 开发
    RootfsPackage("Git", "版本控制", "git", "git", "git", RootfsPackageGroup.DEV),
    RootfsPackage("Python 3", "脚本运行", "python3", "python3", "python3", RootfsPackageGroup.DEV),
    RootfsPackage("pip", "Python 包管理", "pip3", "py3-pip", "python3-pip", RootfsPackageGroup.DEV),
    RootfsPackage("Node.js", "JS 运行时", "node", "nodejs", "nodejs", RootfsPackageGroup.DEV),
    RootfsPackage("npm", "Node 包管理", "npm", "npm", "npm", RootfsPackageGroup.DEV),
    RootfsPackage("编译工具", "gcc/make 等", "gcc", "build-base", "build-essential", RootfsPackageGroup.DEV),
    // 网络
    RootfsPackage("OpenSSH", "ssh/scp 客户端", "ssh", "openssh-client", "openssh-client", RootfsPackageGroup.NETWORK),
    RootfsPackage("rsync", "增量同步", "rsync", "rsync", "rsync", RootfsPackageGroup.NETWORK),
    RootfsPackage("sshd", "SSH 服务端（可选）", "sshd", "openssh", "openssh-server", RootfsPackageGroup.NETWORK),
    // 媒体与数据
    RootfsPackage("FFmpeg", "音视频处理（较大）", "ffmpeg", "ffmpeg", "ffmpeg", RootfsPackageGroup.MEDIA),
    RootfsPackage("SQLite", "数据库", "sqlite3", "sqlite", "sqlite3", RootfsPackageGroup.MEDIA),
    RootfsPackage("zip", "压缩 zip", "zip", "zip", "zip", RootfsPackageGroup.MEDIA),
)
