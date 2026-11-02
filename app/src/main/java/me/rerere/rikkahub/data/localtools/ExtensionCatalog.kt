package me.rerere.rikkahub.data.localtools

/**
 * 完整 Linux 环境（Alpine + APK 内置 proot），全部走公共镜像。
 */
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

/**
 * 工具扩展（一套工具 = 一个 bundle，对应 MobiCode 的 extension 粒度）。
 * 装的时候一次性 apk add 这组包。
 */
data class CliBundle(
    val id: String,
    val label: String,
    val desc: String,
    val pkgs: List<String>,
    /** 用哪个命令判断已安装 */
    val probe: String,
)

val CLI_BUNDLES: List<CliBundle> = listOf(
    CliBundle(
        "dev", "开发基础",
        "git · gcc · make · cmake",
        listOf("git", "build-base", "make", "cmake"), "git",
    ),
    CliBundle(
        "python", "Python",
        "python3 · pip",
        listOf("python3", "py3-pip"), "python3",
    ),
    CliBundle(
        "node", "Node.js",
        "node · npm",
        listOf("nodejs", "npm"), "node",
    ),
    CliBundle(
        "net", "网络工具",
        "curl · wget · ssh · rsync · nc · dig",
        listOf("curl", "wget", "openssh-client", "rsync", "netcat-openbsd", "bind-tools"), "ssh",
    ),
    CliBundle(
        "text", "文本处理",
        "vim · nano · less · jq · yq · tree · fzf",
        listOf("vim", "nano", "less", "jq", "yq", "tree", "fzf"), "jq",
    ),
    CliBundle(
        "sys", "系统监控",
        "htop · procps · lsof · strace · duf · ncdu",
        listOf("htop", "procps", "lsof", "strace", "duf", "ncdu"), "htop",
    ),
    CliBundle(
        "media", "媒体处理",
        "ffmpeg · imagemagick",
        listOf("ffmpeg", "imagemagick"), "ffmpeg",
    ),
    CliBundle(
        "archive", "压缩解压",
        "zip · unzip · 7z · zstd · xz · tar",
        listOf("zip", "unzip", "7zip", "zstd", "xz", "tar"), "zip",
    ),
    CliBundle(
        "lang", "语言运行时",
        "go · rust · java · php · ruby · perl · lua",
        listOf("go", "rust", "openjdk17-jdk", "php83", "ruby", "perl", "lua5.4"), "go",
    ),
    CliBundle(
        "db", "数据库客户端",
        "sqlite3 · psql · mariadb · redis-cli",
        listOf("sqlite", "postgresql16-client", "mariadb-client", "redis"), "sqlite3",
    ),
    CliBundle(
        "ops", "运维与下载",
        "tmux · aria2 · yt-dlp · pandoc · nmap · socat",
        listOf("tmux", "aria2", "yt-dlp", "pandoc", "nmap", "socat"), "tmux",
    ),
)
