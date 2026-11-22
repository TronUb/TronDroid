package com.vikram.trondroid.automation

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Native YouTube search and metadata only.
 * It intentionally does not extract/download copyrighted streams or bypass YouTube access controls.
 */
class WebMediaAutomationPlugin : AutomationPlugin {
    override val id = "userbot:utube"
    override val commands = listOf(
        AutomationCommand("ytsearch", "Search YouTube and return up to five official video links.", "ytsearch <query>"),
        AutomationCommand("ytinfo", "Show title, channel and thumbnail for a YouTube URL or search query.", "ytinfo <query or YouTube URL>")
    )

    override fun onCommand(context: AutomationContext) {
        val query = context.arguments.joinToString(" ").trim()
        if (query.isBlank()) return context.reply("Usage: .${context.command} <query or YouTube URL>")
        if (query.length > 200) return context.reply("Search query must be 200 characters or fewer.")
        context.reply("Searching YouTube…")
        Thread({
            val result = runCatching {
                when (context.command) {
                    "ytsearch" -> search(query)
                    "ytinfo" -> info(query)
                    else -> "Unsupported YouTube command."
                }
            }.getOrElse { "YouTube request failed: ${it.message ?: "unknown error"}" }
            context.reply(result.take(3900))
        }, "TronDroid-YouTube").apply { isDaemon = true; start() }
    }

    private fun request(url: String, maxBytes: Int = 3 * 1024 * 1024): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            require(connection.contentLengthLong <= maxBytes || connection.contentLengthLong < 0L) { "Response is too large." }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Response exceeded the size limit." }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun search(query: String): String {
        val html = request("https://www.youtube.com/results?search_query=${encode(query)}")
        val ids = Regex("\\\"videoId\\\":\\\"([A-Za-z0-9_-]{11})\\\"")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(5)
            .toList()
        if (ids.isEmpty()) return "No YouTube results found for: $query"
        return "YouTube results for: $query\n\n" + ids.mapIndexed { index, id ->
            "${index + 1}. https://www.youtube.com/watch?v=$id"
        }.joinToString("\n")
    }

    private fun info(input: String): String {
        val id = extractVideoId(input) ?: run {
            val html = request("https://www.youtube.com/results?search_query=${encode(input)}")
            Regex("\\\"videoId\\\":\\\"([A-Za-z0-9_-]{11})\\\"")
                .find(html)?.groupValues?.get(1)
        } ?: return "No valid YouTube video was found."
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val data = JSONObject(request("https://www.youtube.com/oembed?url=${encode(watchUrl)}&format=json", 256 * 1024))
        return "Title: ${data.optString("title", "Unknown")}\nChannel: ${data.optString("author_name", "Unknown")}\nVideo: $watchUrl\nThumbnail: ${data.optString("thumbnail_url")}"
    }

    private fun extractVideoId(input: String): String? {
        val url = runCatching { URL(if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input") }.getOrNull()
            ?: return null
        val host = url.host.lowercase()
        if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")) return null
        val segments = url.path.split('/').filter { it.isNotBlank() }
        val id = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            url.path == "/watch" -> url.query?.split("&")?.firstOrNull { part -> part.startsWith("v=") }?.substringAfter("v=")
            segments.firstOrNull()?.let { it in setOf("shorts", "embed", "live") } == true -> segments.getOrNull(1)
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
}
