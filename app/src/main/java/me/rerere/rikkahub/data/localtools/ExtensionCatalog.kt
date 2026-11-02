package me.rerere.rikkahub.data.localtools

/** 完整 Linux 环境（Alpine + APK 内置 proot），全部走公共镜像 */
object LinuxEnv {
    const val VERSION = "3.20.3"
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

/** 商店里的一个 CLI 扩展（= 一个 apk 包） */
data class StorePackage(
    val pkg: String,
    val label: String,
    val desc: String,
    val bin: String,
    val group: String,
)

object StoreGroups {
    const val BUILD = "构建与开发"
    const val PY = "Python"
    const val NODE = "Node.js"
    const val NET = "网络工具"
    const val TEXT = "文本处理"
    const val SYS = "系统监控"
    const val MEDIA = "媒体处理"
    const val ARCHIVE = "压缩解压"
    const val LANG = "语言运行时"
    const val DB = "数据库客户端"
    const val OPS = "运维与下载"

    val ORDER = listOf(BUILD, PY, NODE, NET, TEXT, SYS, MEDIA, ARCHIVE, LANG, DB, OPS)
}

val STORE_PACKAGES: List<StorePackage> = listOf(
    // 构建与开发
    StorePackage("git", "Git", "版本控制", "git", StoreGroups.BUILD),
    StorePackage("git-lfs", "git-lfs", "Git 大文件", "git-lfs", StoreGroups.BUILD),
    StorePackage("build-base", "GCC 编译套件", "gcc/make/libc-dev", "gcc", StoreGroups.BUILD),
    StorePackage("make", "make", "构建工具", "make", StoreGroups.BUILD),
    StorePackage("cmake", "CMake", "跨平台构建", "cmake", StoreGroups.BUILD),
    StorePackage("subversion", "Subversion", "svn 版本控制", "svn", StoreGroups.BUILD),
    StorePackage("gdb", "gdb", "调试器", "gdb", StoreGroups.BUILD),
    StorePackage("ripgrep", "ripgrep (rg)", "高速代码搜索", "rg", StoreGroups.BUILD),
    // Python
    StorePackage("python3", "Python 3", "解释器", "python3", StoreGroups.PY),
    StorePackage("py3-pip", "pip", "包管理", "pip3", StoreGroups.PY),
    StorePackage("py3-virtualenv", "virtualenv", "虚拟环境", "virtualenv", StoreGroups.PY),
    // Node
    StorePackage("nodejs", "Node.js", "JS 运行时", "node", StoreGroups.NODE),
    StorePackage("npm", "npm", "Node 包管理", "npm", StoreGroups.NODE),
    // 网络
    StorePackage("curl", "curl", "HTTP 客户端", "curl", StoreGroups.NET),
    StorePackage("wget", "wget", "下载工具", "wget", StoreGroups.NET),
    StorePackage("openssh-client", "OpenSSH", "ssh/scp/sftp", "ssh", StoreGroups.NET),
    StorePackage("rsync", "rsync", "增量同步", "rsync", StoreGroups.NET),
    StorePackage("netcat-openbsd", "netcat (nc)", "网络调试", "nc", StoreGroups.NET),
    StorePackage("bind-tools", "dig", "DNS 查询", "dig", StoreGroups.NET),
    StorePackage("whois", "whois", "域名查询", "whois", StoreGroups.NET),
    StorePackage("nmap", "nmap", "端口扫描", "nmap", StoreGroups.NET),
    StorePackage("socat", "socat", "网络转发", "socat", StoreGroups.NET),
    StorePackage("traceroute", "traceroute", "路由追踪", "traceroute", StoreGroups.NET),
    // 文本
    StorePackage("vim", "vim", "编辑器", "vim", StoreGroups.TEXT),
    StorePackage("nano", "nano", "轻量编辑器", "nano", StoreGroups.TEXT),
    StorePackage("less", "less", "分页查看", "less", StoreGroups.TEXT),
    StorePackage("jq", "jq", "JSON 处理", "jq", StoreGroups.TEXT),
    StorePackage("yq", "yq", "YAML 处理", "yq", StoreGroups.TEXT),
    StorePackage("tree", "tree", "目录树", "tree", StoreGroups.TEXT),
    StorePackage("fzf", "fzf", "模糊查找", "fzf", StoreGroups.TEXT),
    StorePackage("bat", "bat", "带高亮 cat", "bat", StoreGroups.TEXT),
    StorePackage("fd", "fd", "快速找文件", "fd", StoreGroups.TEXT),
    StorePackage("pandoc", "pandoc", "文档转换", "pandoc", StoreGroups.TEXT),
    // 系统
    StorePackage("htop", "htop", "进程监控", "htop", StoreGroups.SYS),
    StorePackage("procps", "procps (ps/top)", "进程工具", "ps", StoreGroups.SYS),
    StorePackage("lsof", "lsof", "打开的文件", "lsof", StoreGroups.SYS),
    StorePackage("strace", "strace", "系统调用跟踪", "strace", StoreGroups.SYS),
    StorePackage("duf", "duf", "磁盘用量", "duf", StoreGroups.SYS),
    StorePackage("ncdu", "ncdu", "目录占用分析", "ncdu", StoreGroups.SYS),
    StorePackage("file", "file", "文件类型", "file", StoreGroups.SYS),
    StorePackage("which", "which", "命令定位", "which", StoreGroups.SYS),
    // 媒体
    StorePackage("ffmpeg", "FFmpeg", "音视频处理", "ffmpeg", StoreGroups.MEDIA),
    StorePackage("imagemagick", "ImageMagick", "图像处理", "convert", StoreGroups.MEDIA),
    StorePackage("poppler-utils", "poppler (pdftotext)", "PDF 工具", "pdftotext", StoreGroups.MEDIA),
    StorePackage("tesseract-ocr", "tesseract", "OCR", "tesseract", StoreGroups.MEDIA),
    // 压缩
    StorePackage("zip", "zip", "压缩", "zip", StoreGroups.ARCHIVE),
    StorePackage("unzip", "unzip", "解压", "unzip", StoreGroups.ARCHIVE),
    StorePackage("7zip", "7z", "7z 压缩", "7z", StoreGroups.ARCHIVE),
    StorePackage("zstd", "zstd", "zstandard", "zstd", StoreGroups.ARCHIVE),
    StorePackage("xz", "xz", "xz 压缩", "xz", StoreGroups.ARCHIVE),
    StorePackage("tar", "tar", "归档", "tar", StoreGroups.ARCHIVE),
    // 语言
    StorePackage("go", "Go", "Go 语言", "go", StoreGroups.LANG),
    StorePackage("rust", "Rust", "Rust 工具链", "rustc", StoreGroups.LANG),
    StorePackage("openjdk17-jdk", "JDK 17", "Java 运行时", "java", StoreGroups.LANG),
    StorePackage("php83", "PHP 8.3", "PHP", "php", StoreGroups.LANG),
    StorePackage("ruby", "Ruby", "Ruby", "ruby", StoreGroups.LANG),
    StorePackage("perl", "Perl", "Perl", "perl", StoreGroups.LANG),
    StorePackage("lua5.4", "Lua 5.4", "Lua", "lua5.4", StoreGroups.LANG),
    // 数据库
    StorePackage("sqlite", "SQLite", "sqlite3", "sqlite3", StoreGroups.DB),
    StorePackage("postgresql16-client", "psql", "PostgreSQL 客户端", "psql", StoreGroups.DB),
    StorePackage("mariadb-client", "mariadb", "MySQL 客户端", "mariadb", StoreGroups.DB),
    StorePackage("redis", "redis-cli", "Redis 客户端", "redis-cli", StoreGroups.DB),
    // 运维与下载
    StorePackage("tmux", "tmux", "终端复用", "tmux", StoreGroups.OPS),
    StorePackage("aria2", "aria2", "多线程下载", "aria2c", StoreGroups.OPS),
    StorePackage("yt-dlp", "yt-dlp", "视频下载", "yt-dlp", StoreGroups.OPS),
    StorePackage("openssl", "openssl", "加密/证书", "openssl", StoreGroups.OPS),
    StorePackage("nginx", "nginx", "Web 服务器", "nginx", StoreGroups.OPS),
)
