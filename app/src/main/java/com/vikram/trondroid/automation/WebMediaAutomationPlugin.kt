package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebMediaAutomationPlugin : AutomationPlugin {
    override val id = "userbot:webmedia"
    override val commands = listOf(
        AutomationCommand("ytsearch", "Search YouTube and return matching video links.", "ytsearch <query>"),
        AutomationCommand("ytinfo", "Find a YouTube video and show its link.", "ytinfo <query or video URL>"),
        AutomationCommand("lyrics", "Search LRCLIB for song lyrics.", "lyrics <song title>"),
        AutomationCommand("song", "Search for a track and return a YouTube result.", "song <track name>"),
        AutomationCommand("pic", "Find an image using Wikimedia Commons.", "pic <query>"),
        AutomationCommand("img", "Alias for image search.", "img <query>")
    )

    override fun onCommand(context: AutomationContext) {
        val query = context.arguments.joinToString(" ").trim()
        if (query.isBlank()) return context.reply("Usage: .${context.command} <query>")
        context.reply("Searching…")
        Thread({
            val result = runCatching {
                when (context.command) {
                    "lyrics" -> lyrics(query)
                    "pic", "img" -> image(context, query)
                    "ytsearch", "ytinfo", "song" -> youtube(query)
                    else -> "Unsupported command."
                }
            }.getOrElse { "Search failed: ${it.message ?: "unknown error"}" }
            if (result.isNotBlank()) context.reply(result.take(3900))
        }, "TronDroid-WebMedia").apply { isDaemon = true }.start()
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "TronDroid/1.0")
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun youtube(query: String): String {
        val id = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(get("https://www.youtube.com/results?search_query=${encode(query)}"))?.groupValues?.get(1)
            ?: return "No YouTube result found."
        return "YouTube result for: $query\nhttps://www.youtube.com/watch?v=$id"
    }

    private fun lyrics(query: String): String {
        val root = org.json.JSONArray(get("https://lrclib.net/api/search?q=${encode(query)}"))
        if (root.length() == 0) return "No lyrics found for: $query"
        val item = root.optJSONObject(0) ?: return "No lyrics found."
        val title = item.optString("trackName", query)
        val artist = item.optString("artistName")
        val body = item.optString("plainLyrics").ifBlank { item.optString("syncedLyrics") }
        if (body.isBlank()) return "Lyrics are unavailable for $title."
        return "$title — $artist\n\n$body"
    }

    private fun image(context: AutomationContext, query: String): String {
        val root = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=${encode(query)}&gsrnamespace=6&gsrlimit=5&prop=imageinfo&iiprop=url&iiurlwidth=1200&format=json"))
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return "No Wikimedia images found."
        val iterator = pages.keys()
        val gateway = context.telegram ?: return "Telegram session is unavailable."
        var sent = 0
        while (iterator.hasNext() && sent < 3) {
            val page = pages.optJSONObject(iterator.next()) ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val url = info.optString("thumburl").ifBlank { info.optString("url") }
            if (url.startsWith("https://")) {
                gateway.sendPhotoUrl(context.message.chatId, url, page.optString("title").removePrefix("File:")) { response ->
                    if (response is TdApi.Error) context.reply("Image send failed: ${response.message}")
                }
                sent++
            }
        }
        return if (sent == 0) "No sendable images found." else "Sending $sent image result(s)."
    }
}
