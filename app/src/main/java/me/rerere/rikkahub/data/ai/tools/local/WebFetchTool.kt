package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web 浏览工具：抓取网页/接口（支持 GET/POST、headers、body），
 * 后台转换为可读文本，并提取标题与链接。无需前台渲染。
 */
internal fun buildWebFetchTool(): Tool = Tool(
    name = "fetch_web_page",
    description = """
        Fetch a web page or HTTP API in the background (no UI). Supports method GET/POST/PUT/DELETE,
        custom headers, request body. Returns status, content_type, title, readable text (HTML stripped),
        and extracted links. Params: url (required), method, headers (JSON object string), body,
        max_chars (default 12000), include_links (default true).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full http(s) URL")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "HTTP method: GET (default), POST, PUT, DELETE")
                })
                put("headers", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional JSON object of headers, e.g. {\"Authorization\":\"Bearer x\"}")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional request body (for POST/PUT)")
                })
                put("max_chars", buildJsonObject {
                    put("type", "number")
                    put("description", "Max characters of text to return (default 12000)")
                })
                put("include_links", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to extract links (default true)")
                })
            },
            required = listOf("url")
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
        val method = (params["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val headersJson = params["headers"]?.jsonPrimitive?.contentOrNull
        val body = params["body"]?.jsonPrimitive?.contentOrNull
        val maxChars = (params["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 12_000)
            .coerceIn(500, 100_000)
        val includeLinks = params["include_links"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

        val headers: Map<String, String> = runCatching {
            if (headersJson.isNullOrBlank()) emptyMap()
            else Json.parseToJsonElement(headersJson).jsonObject.entries.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: v.toString())
            }
        }.getOrDefault(emptyMap())

        val (status, contentType, rawBody) = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (!body.isNullOrEmpty() && method != "GET") {
                conn.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            try {
                val code = conn.responseCode
                val ct = conn.contentType ?: ""
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { r -> r.readText() } ?: ""
                Triple(code, ct, text)
            } finally {
                conn.disconnect()
            }
        }

        val isHtml = contentType.contains("html", ignoreCase = true) || rawBody.trimStart().startsWith("<")
        val text = if (isHtml) htmlToText(rawBody) else rawBody
        val title = if (isHtml) extractTitle(rawBody) else null
        val payload = buildJsonObject {
            put("url", url)
            put("method", method)
            put("status", status)
            put("content_type", contentType)
            title?.let { put("title", it) }
            put("truncated", text.length > maxChars)
            put("text", text.take(maxChars))
            if (includeLinks && isHtml) {
                put("links", buildJsonArray {
                    extractLinks(url, rawBody).take(50).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private val SCRIPT_STYLE_REGEX = Regex(
    "<script[^>]*>[\\s\\S]*?</script>|<style[^>]*>[\\s\\S]*?</style>",
    RegexOption.IGNORE_CASE
)
private val COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
private val BLOCK_TAG_REGEX = Regex(
    "</(p|div|br|li|tr|h[1-6]|section|article|header|footer|table|ul|ol)>",
    RegexOption.IGNORE_CASE
)
private val TAG_REGEX = Regex("<[^>]+>")
private val MULTI_SPACE_REGEX = Regex("[ \\t\\u00A0]+")
private val MULTI_NEWLINE_REGEX = Regex("\\n\\s*\\n+")
private val TITLE_REGEX = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
private val LINK_REGEX = Regex("<a[^>]+href=[\"']([^\"'#]+)[\"']", RegexOption.IGNORE_CASE)

private fun htmlToText(html: String): String {
    var s = html
    s = COMMENT_REGEX.replace(s, " ")
    s = SCRIPT_STYLE_REGEX.replace(s, " ")
    s = BLOCK_TAG_REGEX.replace(s, "\n")
    s = TAG_REGEX.replace(s, " ")
    s = unescapeHtml(s)
    s = MULTI_SPACE_REGEX.replace(s, " ")
    s = MULTI_NEWLINE_REGEX.replace(s, "\n\n")
    return s.trim()
}

private fun extractTitle(html: String): String? =
    TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { unescapeHtml(it).trim() }?.takeIf { it.isNotBlank() }

private fun extractLinks(baseUrl: String, html: String): List<String> {
    val base = runCatching { URL(baseUrl) }.getOrNull() ?: return emptyList()
    return LINK_REGEX.findAll(html).mapNotNull { m ->
        val href = m.groupValues.getOrNull(1)?.trim() ?: return@mapNotNull null
        when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> base.protocol + ":" + href
            href.startsWith("/") -> "${base.protocol}://${base.authority}$href"
            else -> runCatching { URL(base, href).toString() }.getOrNull()
        }
    }.distinct().toList()
}

private fun unescapeHtml(s: String): String = s
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&mdash;", "—")
    .replace("&ndash;", "–")
    .replace("&hellip;", "…")
