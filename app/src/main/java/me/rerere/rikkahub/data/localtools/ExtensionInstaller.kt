package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledRecord(
    val id: String,
    val version: String,
    val payloadDir: String,
    val installedAt: Long = 0L,
    val downloadBytes: Long = 0L,
    /** 显示名（远程安装的包不在内置目录时需要它重建列表项）。 */
    val name: String = "",
    val publisher: String = "",
    val description: String = "",
    val category: String = "",
    val downloadUrl: String = "",
    /**
     * 安装时保存的扩展环境变量（照搬 MobiCode Studio 的 ExtensionEnvironmentGroup.variables）：
     * PATH / LD_LIBRARY_PATH / LIBRARY_PATH / CPATH / MOBICODE_SYSROOT
     * + OPENSSL_CONF / SSL_CERT_FILE / OPENSSL_MODULES / NODE_PATH（按文件存在性）。
     * 安装时计算一次并持久化，运行时不再扫描文件。
     */
    val envVars: Map<String, String> = emptyMap(),
)

@Serializable
data class InstalledManifest(
    val records: List<InstalledRecord> = emptyList(),
)

/** 安装阶段（用于实时进度）。 */
enum class InstallPhase { DOWNLOADING, EXTRACTING, INSTALLING }

/** 一次进度更新。 */
data class InstallUpdate(
    val phase: InstallPhase,
    val downloaded: Long = 0,
    val total: Long = 0,
    val extracted: Int = 0,
    val entries: Int = 0,
    /** 解压阶段：当前正在解压的文件名（与工作区 rootfs 安装一致的实时体验）。 */
    val currentEntry: String? = null,
)

/**
 * 在线扩展安装器：下载 .mcext(ZIP) -> sha256 校验 -> 解压 payload/ -> 登记。
 * 下载源：国内镜像优先 + GitHub 直连兜底；每个候选 sha256 校验。
 * 通过 [install] 的 onUpdate 回调实时报告 下载/解压/安装 进度。
 */
class ExtensionInstaller(
    private val context: Context,
    private val shellExecutor: ShellExecutor,
) {
    companion object {
        private const val EXT_DIR = "extensions"
        private const val MANIFEST = "installed.json"
        private const val USER_AGENT = "RikkaHub-MobiCode-Toolchain/1.0"

        private val MIRROR_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://mirror.ghproxy.com/",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }
    private val extRoot: File get() = File(context.filesDir, EXT_DIR)
    private val manifestFile: File get() = File(extRoot, MANIFEST)

    private val _installed = MutableStateFlow<Map<String, InstalledRecord>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledRecord>> = _installed.asStateFlow()

    init {
        _installed.value = loadManifest()
        migrateLegacyEnvVars()
    }

    fun isInstalled(id: String): Boolean = _installed.value.containsKey(id)
    fun payloadDirOf(id: String): String? = _installed.value[id]?.payloadDir

    @Synchronized fun refresh() { _installed.value = loadManifest() }

    /** 旧版本安装的扩展没有 envVars，这里一次性补算并持久化（不是运行时扫描）。 */
    private fun migrateLegacyEnvVars() {
        val map = _installed.value.toMutableMap()
        var changed = false
        for ((id, rec) in map) {
            if (rec.envVars.isEmpty()) {
                val payloadDir = File(rec.payloadDir)
                if (payloadDir.isDirectory) {
                    map[id] = rec.copy(envVars = buildExtensionEnvVars(payloadDir))
                    changed = true
                }
            }
        }
        if (changed) {
            saveManifest(map)
            _installed.value = map
        }
    }

    private fun loadManifest(): Map<String, InstalledRecord> = runCatching {
        if (!manifestFile.exists()) return emptyMap()
        val data = json.decodeFromString(InstalledManifest.serializer(), manifestFile.readText())
        data.records.filter { File(it.payloadDir, "bin").exists() || File(it.payloadDir, "lib").exists() }
            .associateBy { it.id }
    }.getOrElse { emptyMap() }

    private fun saveManifest(map: Map<String, InstalledRecord>) {
        runCatching {
            extRoot.mkdirs()
            val tmp = File(extRoot, "$MANIFEST.tmp")
            tmp.writeText(json.encodeToString(InstalledManifest.serializer(), InstalledManifest(map.values.toList())))
            if (manifestFile.exists()) manifestFile.delete()
            tmp.renameTo(manifestFile)
        }
    }

    suspend fun install(
        entry: ToolchainExtension,
        onUpdate: (InstallUpdate) -> Unit = {},
    ): Result<InstalledRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(entry.downloadUrl.isNotBlank()) { "扩展 ${entry.name} 缺少下载地址" }
            extRoot.mkdirs()
            val workDir = File(extRoot, "_tmp_${entry.id}")
            workDir.mkdirs()
            val archive = File(workDir, "archive.mcext")

            // 1) 下载（镜像优先，实时进度）
            downloadWithMirrors(entry.downloadUrl, archive, entry.sha256) { d, t ->
                onUpdate(InstallUpdate(InstallPhase.DOWNLOADING, downloaded = d, total = t))
            }

            // 2) 解压（实时进度）
            val versionDir = File(extRoot, "${entry.id}/${entry.version}")
            versionDir.deleteRecursively()
            val payloadDir = File(versionDir, "payload")
            payloadDir.mkdirs()
            val count = extractPayload(archive, payloadDir) { done, total, current ->
                onUpdate(InstallUpdate(InstallPhase.EXTRACTING, extracted = done, entries = total, currentEntry = current))
            }
            if (count <= 0) {
                versionDir.deleteRecursively(); archive.delete(); workDir.deleteRecursively()
                error("扩展包内未找到 payload/ 内容")
            }

            // 3) 赋可执行权限（root 优先，其次 Shizuku；都无则用普通权限，app 对自己的文件有权限）
            onUpdate(InstallUpdate(InstallPhase.INSTALLING))
            runCatching {
                shellExecutor.execute(
                    "chmod -R 0755 ${payloadDir.absolutePath.shellQuote()}",
                    timeoutMillis = 120_000,
                    mode = shellExecutor.preferredMode(),
                )
            }

            archive.delete(); workDir.deleteRecursively()

            // 4) 计算并保存扩展环境变量（安装时保存，运行时不再扫描）
            val envVars = buildExtensionEnvVars(payloadDir)

            val record = InstalledRecord(
                id = entry.id,
                version = entry.version,
                payloadDir = payloadDir.absolutePath,
                installedAt = System.currentTimeMillis(),
                downloadBytes = entry.downloadSize,
                name = entry.name,
                publisher = entry.publisher,
                description = entry.description,
                category = entry.category,
                downloadUrl = entry.downloadUrl,
                envVars = envVars,
            )
            val map = _installed.value.toMutableMap()
            map[entry.id] = record
            saveManifest(map)
            _installed.value = map
            record
        }
    }

    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            File(extRoot, id).deleteRecursively()
            val map = _installed.value.toMutableMap()
            map.remove(id)
            saveManifest(map)
            _installed.value = map
        }
    }

    private fun downloadWithMirrors(
        urlStr: String,
        dest: File,
        expectedSha: String,
        onProgress: (Long, Long) -> Unit,
    ) {
        val candidates = buildList {
            MIRROR_PREFIXES.forEach { add(it + urlStr) }
            add(urlStr)
        }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                downloadOne(candidate, dest, onProgress)
                val sha = sha256Of(dest)
                if (expectedSha.isBlank() || sha.equals(expectedSha.trim(), ignoreCase = true)) return
                lastError = IllegalStateException("sha256 校验失败：$candidate")
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("下载失败：$urlStr")
    }

    private fun downloadOne(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        var conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.requestMethod = "GET"
        var code = conn.responseCode
        var redirects = 0
        while (code in 300..399 && redirects < 6) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT); requestMethod = "GET"
            }
            code = conn.responseCode
            redirects++
        }
        if (code !in 200..299) { conn.disconnect(); error("下载失败 HTTP $code：$urlStr") }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L
        var lastReported = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); downloaded += n
                    // 每 512KB 报告一次，避免高频刷新 UI（与 RootfsInstaller 一致）
                    if (downloaded - lastReported >= 512 * 1024 || downloaded == total) {
                        lastReported = downloaded
                        onProgress(downloaded, total)
                    }
                }
                if (lastReported != downloaded || downloaded == 0L) onProgress(downloaded, total)
            }
        }
        conn.disconnect()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 照搬 MobiCode Studio 的 ExtensionEnvironmentGroup.variables 构建逻辑，
     * 在安装时计算并保存每个扩展的环境变量（运行时不再扫描文件）。
     *
     * 对应关系：
     * - PATH               = payload/bin
     * - LD_LIBRARY_PATH    = libraryDirectoriesOf(payload)
     * - LIBRARY_PATH       = nativeLinkerSearchPaths(libraryDirectoriesOf(payload))
     * - CPATH              = includeDirectoriesOf(payload)
     * - MOBICODE_SYSROOT   = sysrootOf(payload)（存在时）
     * - OPENSSL_CONF / SSL_CERT_FILE / OPENSSL_MODULES / NODE_PATH（按文件存在性）
     */
    private fun buildExtensionEnvVars(payload: File): Map<String, String> {
        val vars = LinkedHashMap<String, String>()

        // PATH = payload/bin
        val binDir = File(payload, "bin")
        if (binDir.isDirectory) vars["PATH"] = binDir.absolutePath

        // LD_LIBRARY_PATH / LIBRARY_PATH = libraryDirectoriesOf(payload)
        val libDirs = libraryDirectoriesOf(payload)
        if (libDirs.isNotEmpty()) {
            vars["LD_LIBRARY_PATH"] = libDirs.joinToString(":") { it.absolutePath }
            // nativeLinkerSearchPaths = extensionDirectories + androidSystemLibraryDirectories（过滤存在、去重）
            val linkerDirs = (libDirs + androidSystemLibraryDirectories())
                .filter { it.isDirectory }
                .distinctBy { it.absolutePath }
            vars["LIBRARY_PATH"] = linkerDirs.joinToString(":") { it.absolutePath }
        }

        // CPATH = includeDirectoriesOf(payload)
        val includeDirs = includeDirectoriesOf(payload)
        if (includeDirs.isNotEmpty()) vars["CPATH"] = includeDirs.joinToString(":") { it.absolutePath }

        // MOBICODE_SYSROOT = sysrootOf(payload)
        val sysroot = listOf("sysroot", "ndk/sysroot")
            .map { File(payload, it) }
            .firstOrNull { it.isDirectory }
        if (sysroot != null) vars["MOBICODE_SYSROOT"] = sysroot.absolutePath

        // EXTENSION_CONFIG_VARIABLES（照搬 MobiCode）
        listOf(
            "etc/tls/openssl.cnf" to "OPENSSL_CONF",
            "etc/tls/cert.pem" to "SSL_CERT_FILE",
            "lib/ossl-modules" to "OPENSSL_MODULES",
            "lib/node_modules" to "NODE_PATH",
        ).forEach { (rel, key) ->
            val f = File(payload, rel)
            if (f.exists()) vars[key] = f.absolutePath
        }

        return vars
    }

    /** 照搬 MobiCode Studio 的 libraryDirectoriesOf(payload)。 */
    private fun libraryDirectoriesOf(payload: File): List<File> {
        val dirs = mutableListOf<File>()
        listOf(
            "lib",
            "sysroot/usr/lib/aarch64-linux-android",
            "sysroot/usr/lib/arm64-v8a",
            "ndk/sysroot/usr/lib/aarch64-linux-android",
            "ndk/sysroot/usr/lib/arm64-v8a",
            "lib/rustlib/aarch64-linux-android/lib",
        ).forEach { rel -> File(payload, rel).takeIf { it.isDirectory }?.let { dirs += it } }
        File(payload, "lib/clang").takeIf { it.isDirectory }
            ?.listFiles()?.filter { it.isDirectory }?.forEach { dirs += it }
        return dirs
    }

    /** 照搬 MobiCode Studio 的 includeDirectoriesOf(payload)。 */
    private fun includeDirectoriesOf(payload: File): List<File> {
        val dirs = mutableListOf<File>()
        listOf(
            "include",
            "include/c++/v1",
            "sysroot/usr/include",
            "sysroot/usr/include/aarch64-linux-android",
            "ndk/sysroot/usr/include",
            "ndk/sysroot/usr/include/aarch64-linux-android",
        ).forEach { rel -> File(payload, rel).takeIf { it.isDirectory }?.let { dirs += it } }
        File(payload, "lib/clang").takeIf { it.isDirectory }
            ?.listFiles()?.filter { it.isDirectory }?.forEach { clangDir ->
                File(clangDir, "include").takeIf { it.isDirectory }?.let { dirs += it }
            }
        return dirs
    }

    /** 照搬 MobiCode Studio 的 androidSystemLibraryDirectories()。 */
    private fun androidSystemLibraryDirectories(): List<File> =
        listOf(File("/system/lib64"), File("/system/lib")).filter { it.isDirectory }

    /** 解压 payload/ 到 payloadDir，按文件数回调进度；返回解压文件数。 */
    private fun extractPayload(archive: File, payloadDir: File, onProgress: (Int, Int, String?) -> Unit): Int {
        var count = 0
        ZipFile(archive).use { zf ->
            val all = zf.entries().toList()
            val payloadEntries = all.filter { e ->
                val n = e.name.replace('\\', '/')
                n.startsWith("payload/") && n.removePrefix("payload/").isNotEmpty()
            }
            val total = payloadEntries.size
            for (e in payloadEntries) {
                val name = e.name.replace('\\', '/')
                val rel = name.removePrefix("payload/")
                if (rel.contains("..")) continue
                val outFile = File(payloadDir, rel)
                if (e.isDirectory) { outFile.mkdirs(); continue }
                outFile.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                count++
                if (count % 10 == 0 || count == total) onProgress(count, total, rel)
            }
            if (total == 0) onProgress(0, 0, null)
        }
        return count
    }
}
