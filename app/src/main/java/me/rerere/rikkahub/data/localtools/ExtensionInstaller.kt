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

    init { _installed.value = loadManifest() }

    fun isInstalled(id: String): Boolean = _installed.value.containsKey(id)
    fun payloadDirOf(id: String): String? = _installed.value[id]?.payloadDir

    @Synchronized fun refresh() { _installed.value = loadManifest() }

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
            val count = extractPayload(archive, payloadDir) { done, total ->
                onUpdate(InstallUpdate(InstallPhase.EXTRACTING, extracted = done, entries = total))
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

            val record = InstalledRecord(
                id = entry.id,
                version = entry.version,
                payloadDir = payloadDir.absolutePath,
                installedAt = System.currentTimeMillis(),
                downloadBytes = entry.downloadSize,
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
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); downloaded += n; onProgress(downloaded, total)
                }
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

    /** 解压 payload/ 到 payloadDir，按文件数回调进度；返回解压文件数。 */
    private fun extractPayload(archive: File, payloadDir: File, onProgress: (Int, Int) -> Unit): Int {
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
                if (count % 10 == 0 || count == total) onProgress(count, total)
            }
            if (total == 0) onProgress(0, 0)
        }
        return count
    }
}
