package com.vikram.trondroid.automation

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class UtilityExtrasAutomationPlugin : AutomationPlugin {
    override val id = "userbot:utilityextras"
    override val commands = listOf(
        AutomationCommand("short", "Shorten an HTTP or HTTPS URL using is.gd.", "short <url>"),
        AutomationCommand("unshort", "Resolve a shortened URL without downloading its content.", "unshort <url>"),
        AutomationCommand("ud", "Look up an English word definition.", "ud <word>")
    )
    override fun onCommand(context: AutomationContext) {
        val value = context.arguments.joinToString(" ").trim()
        if (value.isBlank()) return context.reply("Usage: .${context.command} <value>")
        Thread({
            val response = runCatching {
                when (context.command) {
                    "short" -> {
                        require(value.startsWith("https://") || value.startsWith("http://")) { "Provide a valid HTTP(S) URL." }
                        val url = "https://is.gd/create.php?format=simple&url=" + URLEncoder.encode(value, Charsets.UTF_8.name())
                        get(url).trim().also { require(it.startsWith("https://")) { it } }
                    }
                    "unshort" -> {
                        require(value.startsWith("https://") || value.startsWith("http://")) { "Provide a valid HTTP(S) URL." }
                        resolve(value)
                    }
                    "ud" -> {
                        val json = org.json.JSONArray(get("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(value, Charsets.UTF_8.name())))
                        val entry = json.optJSONObject(0) ?: return@runCatching "No definition found."
                        val meanings = entry.optJSONArray("meanings") ?: return@runCatching "No definition found."
                        buildString {
                            append(entry.optString("word", value))
                            for (i in 0 until minOf(meanings.length(), 3)) {
                                val meaning = meanings.optJSONObject(i) ?: continue
                                append("\n\n").append(meaning.optString("partOfSpeech"))
                                val defs = meaning.optJSONArray("definitions") ?: continue
                                for (j in 0 until minOf(defs.length(), 2)) {
                                    append("\n• ").append(defs.optJSONObject(j)?.optString("definition").orEmpty())
                                }
                            }
                        }
                    }
                    else -> "Unsupported command."
                }
            }.getOrElse { "Request failed: ${it.message ?: "unknown error"}" }
            context.reply(response.take(3900))
        }, "TronDroid-UtilityExtras").apply { isDaemon = true }.start()
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

    private fun resolve(raw: String): String {
        val current = URL(raw)
        require(current.protocol == "https" || current.protocol == "http") { "Unsupported URL scheme." }
        val connection = current.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "TronDroid/1.0")
        return try {
            connection.connect()
            val location = connection.getHeaderField("Location")
            location?.let { URL(current, it).toString() } ?: raw
        } finally { connection.disconnect() }
    }
}
