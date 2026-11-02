package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Web 浏览工具：直接抓取网页/接口内容并转成可读文本，不需要前台渲染界面。
 */
internal fun buildWebFetchTool(): Tool = Tool(
    name = "fetch_web_page",
    description = """
        Fetch a web page or HTTP API and return readable text (HTML stripped, scripts/styles removed).
        Background only, no UI rendering. Useful for reading docs, articles, search results, APIs.
        Provide url; optionally max_chars (default 12000).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full http(s) URL to fetch")
                })
                put("max_chars", buildJsonObject {
                    put("type", "number")
                    put("description", "Max characters to return (optional, default 12000)")
                })
            },
            required = listOf("url")
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
        val maxChars = (params["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 12_000)
            .coerceIn(500, 100_000)

        val (status, contentType, body) = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            try {
                val code = conn.responseCode
                val ct = conn.contentType ?: ""
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                Triple(code, ct, text)
            } finally {
                conn.disconnect()
            }
        }

        val isHtml = contentType.contains("html", ignoreCase = true) || body.trimStart().startsWith("<")
        val text = if (isHtml) htmlToText(body) else body
        val payload = buildJsonObject {
            put("url", url)
            put("status", status)
            put("content_type", contentType)
            put("truncated", text.length > maxChars)
            put("text", text.take(maxChars))
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

/** 极简 HTML→文本，避免额外依赖 */
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
