package com.vikram.trondroid.automation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MusicSearchAutomationPlugin : AutomationPlugin {
    override val id = "userbot:musicsearch"
    override val commands = listOf(
        AutomationCommand("deezer", "Search Deezer tracks and return playable track links.", "deezer <song or artist>"),
        AutomationCommand("spotify", "Open Spotify search results for a song or artist.", "spotify <song or artist>"),
        AutomationCommand("now", "Show current Spotify playback when connected Spotify OAuth credentials are configured.", "now")
    )
    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "spotify" -> {
                val query = context.arguments.joinToString(" ").trim()
                if (query.isBlank()) return context.reply("Usage: .spotify <song or artist>")
                val encoded = URLEncoder.encode(query, "UTF-8")
                context.reply("Spotify search: https://open.spotify.com/search/$encoded")
            }
            "deezer" -> {
                val query = context.arguments.joinToString(" ").trim()
                if (query.isBlank()) return context.reply("Usage: .deezer <song or artist>")
                Thread({
                    runCatching {
                        val connection = URL("https://api.deezer.com/search?q=" + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 15000
                        try {
                            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                            val data = root.optJSONArray("data") ?: return@runCatching "No Deezer tracks found."
                            (0 until minOf(data.length(), 5)).mapNotNull { index ->
                                val item = data.optJSONObject(index) ?: return@mapNotNull null
                                val title = item.optString("title")
                                val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
                                val link = item.optString("link")
                                if (link.startsWith("https://")) "$title — $artist\n$link" else null
                            }.joinToString("\n\n").ifBlank { "No Deezer tracks found." }
                        } finally { connection.disconnect() }
                    }.onSuccess(context.reply).onFailure { context.reply("Deezer search failed: ${it.message}") }
                }, "TronDroid-Deezer").apply { isDaemon = true }.start()
            }
            "now" -> context.reply("Spotify playback status requires a Spotify OAuth access token with currently-playing permission. Configure a Spotify connection before using .now.")
        }
    }
}
