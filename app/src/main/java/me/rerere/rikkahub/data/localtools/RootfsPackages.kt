package me.rerere.rikkahub.data.localtools

/**
 * rootfs（Alpine）里可按需安装的常用工具包。
 * 分组用于 UI 展示；[bin] 用于检测是否已安装。
 */
data class RootfsPackage(
    val pkg: String,
    val bin: String,
    val label: String,
    val desc: String,
    val group: RootfsPackageGroup,
)

enum class RootfsPackageGroup(val title: String) {
    BASIC("基础工具"),
    DEV("开发工具"),
    NETWORK("网络与传输"),
    MEDIA("媒体与数据"),
}

val ROOTFS_PACKAGES: List<RootfsPackage> = listOf(
    // 基础
    RootfsPackage("bash", "bash", "Bash", "更完整的 shell", RootfsPackageGroup.BASIC),
    RootfsPackage("coreutils", "ls", "Coreutils", "标准 Unix 工具集", RootfsPackageGroup.BASIC),
    RootfsPackage("curl", "curl", "curl", "HTTP 请求工具", RootfsPackageGroup.BASIC),
    RootfsPackage("wget", "wget", "wget", "下载工具", RootfsPackageGroup.BASIC),
    RootfsPackage("unzip", "unzip", "unzip", "解压 zip", RootfsPackageGroup.BASIC),
    RootfsPackage("jq", "jq", "jq", "JSON 处理", RootfsPackageGroup.BASIC),
    RootfsPackage("openssl", "openssl", "openssl", "加密/证书", RootfsPackageGroup.BASIC),
    RootfsPackage("file", "file", "file", "文件类型识别", RootfsPackageGroup.BASIC),
    // 开发
    RootfsPackage("git", "git", "Git", "版本控制", RootfsPackageGroup.DEV),
    RootfsPackage("python3", "python3", "Python 3", "脚本运行", RootfsPackageGroup.DEV),
    RootfsPackage("py3-pip", "pip3", "pip", "Python 包管理", RootfsPackageGroup.DEV),
    RootfsPackage("nodejs", "node", "Node.js", "JS 运行时", RootfsPackageGroup.DEV),
    RootfsPackage("npm", "npm", "npm", "Node 包管理", RootfsPackageGroup.DEV),
    RootfsPackage("build-base", "gcc", "GCC/编译工具", "编译 C/C++", RootfsPackageGroup.DEV),
    // 网络
    RootfsPackage("openssh-client", "ssh", "OpenSSH", "ssh/scp 客户端", RootfsPackageGroup.NETWORK),
    RootfsPackage("rsync", "rsync", "rsync", "增量同步", RootfsPackageGroup.NETWORK),
    RootfsPackage("openssh", "sshd", "sshd", "SSH 服务端（可选）", RootfsPackageGroup.NETWORK),
    // 媒体与数据
    RootfsPackage("ffmpeg", "ffmpeg", "FFmpeg", "音视频处理（较大）", RootfsPackageGroup.MEDIA),
    RootfsPackage("sqlite", "sqlite3", "SQLite", "数据库", RootfsPackageGroup.MEDIA),
    RootfsPackage("zip", "zip", "zip", "压缩 zip", RootfsPackageGroup.MEDIA),
)
