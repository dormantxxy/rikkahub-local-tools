package me.rerere.rikkahub.data.localtools

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MobiCode Studio 扩展目录（.mcext 工具链）。
 *
 * 目录分两层：
 * 1. 内置目录：assets/mobicode_extensions.json（离线兜底，保证首次可用）。
 * 2. 远程目录：多个扩展仓库的 release-manifest.json（镜像站优先拉取），
 *    搜索框可搜索镜像站上任意可用的 .mcext 包，不局限于内置的几个。
 *
 * 远程目录结果会缓存到 files/extensions/catalog_cache.json，下次搜索先秒出缓存再后台刷新。
 */

@Serializable
data class CatalogFile(
    val ok: Boolean = true,
    val code: String = "",
    val data: CatalogData = CatalogData(),
)

@Serializable
data class CatalogData(
    val extensions: List<RawExtension> = emptyList(),
)

@Serializable
data class RawExtension(
    @SerialName("extension_id") val extensionId: String,
    val name: String = "",
    val version: String = "",
    val publisher: String = "",
    val description: String = "",
    val category: String = "",
    @SerialName("download_size") val downloadSize: Long = 0,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("archive_sha256") val sha256: String = "",
    @SerialName("repository_url") val repositoryUrl: String = "",
    @SerialName("license_name") val licenseName: String = "",
    @SerialName("minimum_app_version") val minimumAppVersion: Int = 0,
    val languages: List<String> = emptyList(),
    @SerialName("project_templates") val projectTemplates: List<String> = emptyList(),
)

/** release-manifest.json 的一条记录（MobiCode-Extensions 仓库）。 */
@Serializable
data class RemoteManifestEntry(
    val name: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
)

/** 远程扩展包（缓存用，可序列化）。 */
@Serializable
data class CachedRemotePackage(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val category: String,
    val downloadSize: Long,
    val downloadUrl: String,
    val sha256: String,
    val source: String = "",
)

@Serializable
data class RemoteCatalogCache(
    val updatedAt: Long = 0L,
    val packages: List<CachedRemotePackage> = emptyList(),
)

data class ToolchainExtension(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val category: String,
    val downloadSize: Long,
    val downloadUrl: String,
    val sha256: String,
    val repositoryUrl: String,
    val licenseName: String,
    val minimumAppVersion: Int,
    /** 包来源仓库（用于展示）。 */
    val sourceRepo: String = "",
)

/** 一个扩展仓库源：从它的 release-manifest.json 读取全部 .mcext 包。 */
data class ExtensionSource(
    val displayName: String,
    val repo: String,
    /** 包名前缀，如 "MobiCode-" / "ZYG-Studio-" */
    val filePrefix: String,
)

object MobiCodeCatalog {
    const val EXCLUDED_ID = "__include_all__"

    /**
     * 扩展镜像源列表（GitHub 仓库，全部通过镜像站访问，不依赖官方 API）。
     * 新增源只需在这里加一行：仓库的 main 分支根部需有 release-manifest.json。
     */
    val EXTENSION_SOURCES = listOf(
        ExtensionSource("MobiCode", "Putongren0/MobiCode-Extensions", "MobiCode-"),
        ExtensionSource("ZYG-Studio", "zunyuange/ZYG-Studio-Extensions", "ZYG-Studio-"),
    )

    /** 国内 GitHub 镜像代理（对 raw.githubusercontent.com 与 github.com 都可用）。 */
    val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    @Volatile
    private var cached: List<ToolchainExtension>? = null

    fun extensions(context: Context): List<ToolchainExtension> {
        cached?.let { return it }
        val list = runCatching {
            val text = context.assets.open("mobicode_extensions.json").bufferedReader().use { it.readText() }
            val file = json.decodeFromString(CatalogFile.serializer(), text)
            file.data.extensions
                .filter { it.extensionId != EXCLUDED_ID }
                .map { it.toModel() }
        }.getOrElse { emptyList() }
        cached = list
        return list
    }

    private fun cacheFile(context: Context): File =
        File(context.filesDir, "extensions/catalog_cache.json")

    /** 读取上次缓存的远程包（搜索先秒出，再后台刷新）。 */
    fun cachedRemotePackages(context: Context): List<ToolchainExtension> = runCatching {
        val f = cacheFile(context)
        if (!f.exists()) return emptyList()
        val cache = json.decodeFromString(RemoteCatalogCache.serializer(), f.readText())
        cache.packages.map { it.toExtension() }
    }.getOrElse { emptyList() }

    private fun saveRemoteCache(context: Context, packages: List<ToolchainExtension>) {
        runCatching {
            val f = cacheFile(context)
            f.parentFile?.mkdirs()
            val cache = RemoteCatalogCache(
                updatedAt = System.currentTimeMillis(),
                packages = packages.map { it.toCached() },
            )
            f.writeText(json.encodeToString(RemoteCatalogCache.serializer(), cache))
        }
    }

    /**
     * 从所有扩展源拉取 release-manifest.json（镜像站优先），汇总出完整包列表。
     * 结果写入缓存；失败时返回已有缓存 + 本地内置目录。
     */
    suspend fun fetchRemoteExtensions(context: Context): List<ToolchainExtension> {
        val local = extensions(context)
        val fetched = LinkedHashMap<String, ToolchainExtension>() // key = 规范化包名

        for (source in EXTENSION_SOURCES) {
            val rawUrl = "https://raw.githubusercontent.com/${source.repo}/main/release-manifest.json"
            val candidates = MIRROR_PREFIXES.map { it + rawUrl } + rawUrl
            var entries: List<RemoteManifestEntry>? = null
            for (url in candidates) {
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 12_000
                    conn.readTimeout = 25_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "RikkaHub-MobiCode-Toolchain/1.0")
                    if (conn.responseCode in 200..299) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        entries = json.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(RemoteManifestEntry.serializer()), text
                        )
                    }
                    conn.disconnect()
                    if (entries != null) break
                } catch (_: Exception) {
                    // 尝试下一个镜像
                }
            }
            entries?.forEach { entry ->
                val ext = toRemoteExtension(entry, source, local) ?: return@forEach
                // 同包名（忽略大小写）只保留第一个源
                val key = ext.name.lowercase().replace(Regex("[^a-z0-9]"), "")
                if (!fetched.containsKey(key)) fetched[key] = ext
            }
        }

        val remote = fetched.values.toList()
        if (remote.isNotEmpty()) {
            saveRemoteCache(context, remote)
        }
        // 注意：不要污染内置目录缓存，远程包通过 cachedRemotePackages / searchRemote 使用
        return if (remote.isNotEmpty()) (local + remote).distinctBy { it.id }
        else (local + cachedRemotePackages(context)).distinctBy { it.id }
    }

    /** 把 release-manifest 的一条记录解析为 ToolchainExtension。 */
    private fun toRemoteExtension(
        entry: RemoteManifestEntry,
        source: ExtensionSource,
        local: List<ToolchainExtension>,
    ): ToolchainExtension? {
        val fileName = entry.name
        if (!fileName.endsWith(".mcext", ignoreCase = true)) return null
        var base = fileName.removeSuffix(".mcext").removeSuffix(".MCEXT")
        if (base.startsWith(source.filePrefix)) base = base.removePrefix(source.filePrefix)
        // 提取版本：最后一段 -vX.Y.Z（支持 26.4.0.1 这种多段）
        val versionRegex = Regex("""^(.*?)-v?(\d[\d.p-]*)$""")
        val match = versionRegex.matchEntire(base) ?: return null
        val namePart = match.groupValues[1]
        val version = match.groupValues[2]
        val displayName = namePart.replace('-', ' ').replace('_', ' ')
        // 与本地目录匹配（名称单词集合相同则视为同一扩展，复用本地元数据）
        fun words(s: String): Set<String> {
            val normalized = s.lowercase()
                .replace("node.js", "nodejs")
                .replace("node js", "nodejs")
                .replace("ripgrep", "rg")
                .replace("codex runtime", "codex")
                .replace("java android", "android java")
                .replace("php web", "php")
            return normalized.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        }
        val matched = local.firstOrNull { ext ->
            words(ext.name) == words(displayName) ||
                words(ext.id.substringAfterLast('.')) == words(displayName) ||
                words(ext.id.replace('.', ' ')) == words(displayName)
        }
        if (matched != null) {
            // 本地目录有元数据：保留本地 id/描述，更新下载信息与版本
            return matched.copy(
                version = if (version.isNotBlank()) version else matched.version,
                downloadSize = if (entry.bytes > 0) entry.bytes else matched.downloadSize,
                downloadUrl = entry.downloadUrl.ifBlank { matched.downloadUrl },
                sha256 = entry.sha256.ifBlank { matched.sha256 },
                sourceRepo = source.repo,
            )
        }
        val id = "mobicode.remote." + namePart.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.')
        return ToolchainExtension(
            id = id,
            name = displayName,
            version = version,
            publisher = source.displayName,
            description = "来自镜像站 ${source.displayName} 的扩展包",
            category = "MIRROR",
            downloadSize = entry.bytes,
            downloadUrl = entry.downloadUrl,
            sha256 = entry.sha256,
            repositoryUrl = "https://github.com/${source.repo}",
            licenseName = "",
            minimumAppVersion = 0,
            sourceRepo = source.repo,
        )
    }

    /**
     * 搜索镜像站包（只搜本地缓存 + 内置目录，毫秒级响应；目录由页面打开时预热）。
     * 返回所有匹配的包，由 UI 决定哪些展示在「镜像站搜索结果」区域。
     */
    fun searchRemote(query: String, context: Context): List<ToolchainExtension> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().trim()
        fun matches(ext: ToolchainExtension): Boolean =
            ext.name.lowercase().contains(q) ||
                ext.description.lowercase().contains(q) ||
                ext.category.lowercase().contains(q) ||
                ext.id.lowercase().contains(q) ||
                ext.publisher.lowercase().contains(q) ||
                ext.version.lowercase().contains(q)

        return (cachedRemotePackages(context) + extensions(context))
            .filter(::matches)
            .distinctBy { it.id }
    }

    fun find(context: Context, id: String): ToolchainExtension? =
        extensions(context).firstOrNull { it.id == id }

    private fun RawExtension.toModel() = ToolchainExtension(
        id = extensionId,
        name = name.ifBlank { extensionId },
        version = version,
        publisher = publisher,
        description = description,
        category = category,
        downloadSize = downloadSize,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        repositoryUrl = repositoryUrl,
        licenseName = licenseName,
        minimumAppVersion = minimumAppVersion,
    )

    private fun ToolchainExtension.toCached() = CachedRemotePackage(
        id = id,
        name = name,
        version = version,
        publisher = publisher,
        description = description,
        category = category,
        downloadSize = downloadSize,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        source = sourceRepo,
    )

    private fun CachedRemotePackage.toExtension() = ToolchainExtension(
        id = id,
        name = name,
        version = version,
        publisher = publisher,
        description = description,
        category = category,
        downloadSize = downloadSize,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        repositoryUrl = if (source.isNotBlank()) "https://github.com/$source" else "",
        licenseName = "",
        minimumAppVersion = 0,
        sourceRepo = source,
    )
}
