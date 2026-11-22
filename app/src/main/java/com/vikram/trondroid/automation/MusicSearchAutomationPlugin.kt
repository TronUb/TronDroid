package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

private object MusicSearchHttp {
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "TronDroid-MusicSearch").apply { isDaemon = true }
    }

    fun run(context: AutomationContext, task: () -> String) {
        executor.execute {
            runCatching(task)
                .onSuccess { context.reply(it.take(3900)) }
                .onFailure { context.reply("Music request failed: ${it.message ?: "unknown error"}") }
        }
    }

    fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "TronDroid/1.0 (Android music search)")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "HTTP $code" }
            body
        } finally {
            connection.disconnect()
        }
    }

    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    fun encodePath(value: String) = encode(value).replace("+", "%20")
}

class MusicSearchAutomationPlugin : AutomationPlugin {
    override val id = "userbot:song"
    override val commands = listOf(
        AutomationCommand("song", "Search Deezer for tracks with official links and 30-second previews.", "song <song or artist>"),
        AutomationCommand("music", "Alias for song search.", "music <song or artist>"),
        AutomationCommand("deezer", "Search Deezer tracks and return previews.", "deezer <song or artist>"),
        AutomationCommand("dz", "Alias for Deezer search.", "dz <song or artist>"),
        AutomationCommand("lyrics", "Find lyrics for a song using its Deezer artist/title match.", "lyrics <song or artist>"),
        AutomationCommand("ly", "Alias for lyrics.", "ly <song or artist>")
    )

    override fun onCommand(context: AutomationContext) {
        val query = queryFrom(context)
        if (query.isBlank()) return context.reply("Give me a song name, or reply to a text/audio message.\nUsage: .song <query> or .lyrics <query>")
        when (context.command) {
            "lyrics", "ly" -> {
                context.reply("Finding lyrics for $query…")
                MusicSearchHttp.run(context) { findLyrics(query) }
            }
            else -> {
                context.reply("Searching Deezer for $query…")
                MusicSearchHttp.run(context) { searchTracks(query) }
            }
        }
    }

    private fun queryFrom(context: AutomationContext): String {
        val inline = context.arguments.joinToString(" ").trim()
        if (inline.isNotBlank()) return inline
        val replied = context.message.tdMessage?.let { context.telegram?.repliedMessage(it) } ?: return ""
        return when (val content = replied.content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessageAudio -> listOf(content.audio.title, content.audio.performer).filter(String::isNotBlank).joinToString(" ")
            is TdApi.MessageDocument -> content.document.fileName.substringBeforeLast('.')
            else -> ""
        }.trim()
    }

    private data class Track(val title: String, val artist: String, val album: String, val duration: Int, val link: String, val preview: String)

    private fun tracks(query: String): List<Track> {
        val root = JSONObject(MusicSearchHttp.get("https://api.deezer.com/search?q=${MusicSearchHttp.encode(query)}&limit=5"))
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val link = item.optString("link")
            if (!link.startsWith("https://")) return@mapNotNull null
            val preview = item.optString("preview").takeIf { it.startsWith("https://") }.orEmpty()
            Track(
                item.optString("title", "Unknown track"),
                item.optJSONObject("artist")?.optString("name").orEmpty(),
                item.optJSONObject("album")?.optString("title").orEmpty(),
                item.optInt("duration"),
                link,
                preview
            )
        }
    }

    private fun searchTracks(query: String): String {
        val results = tracks(query)
        if (results.isEmpty()) return "No Deezer tracks found for: $query"
        return buildString {
            append("Deezer results for: $query\n\n")
            results.forEachIndexed { index, track ->
                append("${index + 1}. ${track.title} — ${track.artist}\n")
                if (track.album.isNotBlank()) append("Album: ${track.album}\n")
                if (track.duration > 0) append("Duration: ${track.duration / 60}:${(track.duration % 60).toString().padStart(2, '0')}\n")
                append(track.link)
                if (track.preview.isNotBlank()) append("\n30-second preview: ${track.preview}")
                append("\n\n")
            }
        }.trim()
    }

    private fun findLyrics(query: String): String {
        val track = tracks(query).firstOrNull() ?: return "No matching track found for: $query"
        if (track.artist.isBlank()) return "The artist name was not returned by Deezer."
        val endpoint = "https://api.lyrics.ovh/v1/${MusicSearchHttp.encodePath(track.artist)}/${MusicSearchHttp.encodePath(track.title)}"
        val root = JSONObject(MusicSearchHttp.get(endpoint))
        val lyrics = root.optString("lyrics").trim()
        if (lyrics.isBlank()) return "No lyrics found for ${track.title} — ${track.artist}.\nTrack: ${track.link}"
        return buildString {
            append("${track.title} — ${track.artist}\n\n")
            append(lyrics.take(3500))
            append("\n\nTrack: ${track.link}")
        }
    }
}

class SpotifyAutomationPlugin : AutomationPlugin {
    override val id = "userbot:spotify"
    override val commands = listOf(
        AutomationCommand("spotify", "Search Spotify and open official track, album or artist results.", "spotify <song or artist>"),
        AutomationCommand("now", "Explain how to enable current Spotify playback lookup.", "now")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "spotify" -> {
                val query = context.arguments.joinToString(" ").trim()
                if (query.isBlank()) return context.reply("Usage: .spotify <song or artist>")
                context.reply("Spotify search: https://open.spotify.com/search?q=${MusicSearchHttp.encode(query)}")
            }
            "now" -> context.reply(
                "Spotify currently-playing lookup needs a Spotify OAuth access token with user-read-currently-playing permission. " +
                    "TronDroid does not currently have a registered Spotify OAuth client, so it cannot read your private playback state. " +
                    "Use .spotify <song or artist> to search Spotify."
            )
        }
    }
}
