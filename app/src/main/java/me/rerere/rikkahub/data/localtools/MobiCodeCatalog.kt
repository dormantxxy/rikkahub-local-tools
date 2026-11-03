package me.rerere.rikkahub.data.localtools

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MobiCode Studio 扩展目录（.mcext 工具链）。
 *
 * 只内置"目录元数据"（extensions.json），不内置任何工具链二进制。
 * 真正的 .mcext 包一律在线下载并由 ExtensionInstaller 解压登记。
 * Android 构建工具链 android.gradle.runtime 被显式排除。
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
)

object MobiCodeCatalog {
    const val EXCLUDED_ID = "__include_all__"

    /**
     * 远程目录（镜像站优先）。与 assets/mobicode_extensions.json 同构。
     * GitHub raw 直链 + 镜像代理，优先走国内镜像。
     */
    const val REMOTE_CATALOG_URL = "https://raw.githubusercontent.com/Putongren0/MobiCode-Extensions/main/extensions.json"
    val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    /**
     * 从远程目录（镜像站优先）拉取扩展列表，用于搜索镜像站的包。
     * 成功则合并到本地列表并刷新缓存；失败时回退本地列表。
     */
    suspend fun fetchRemoteExtensions(context: Context): List<ToolchainExtension> {
        val local = extensions(context)
        val candidates = MIRROR_PREFIXES.map { it + REMOTE_CATALOG_URL } + REMOTE_CATALOG_URL
        for (url in candidates) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "RikkaHub-MobiCode-Toolchain/1.0")
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val file = json.decodeFromString(CatalogFile.serializer(), text)
                    val remote = file.data.extensions
                        .filter { it.extensionId != EXCLUDED_ID }
                        .map { it.toModel() }
                    conn.disconnect()
                    if (remote.isNotEmpty()) {
                        // 合并本地+远程，远程优先，按 id 去重
                        val merged = (remote + local).distinctBy { it.id }
                        cached = merged
                        return merged
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 尝试下一个镜像
            }
        }
        return local
    }

    /** 搜索远程镜像站目录（关键字匹配 name/description/category/id）。 */
    suspend fun searchRemote(context: Context, query: String): List<ToolchainExtension> {
        if (query.isBlank()) return emptyList()
        val all = fetchRemoteExtensions(context)
        val q = query.lowercase()
        return all.filter {
            it.name.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.category.lowercase().contains(q) ||
            it.id.lowercase().contains(q) ||
            it.publisher.lowercase().contains(q)
        }
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
}
