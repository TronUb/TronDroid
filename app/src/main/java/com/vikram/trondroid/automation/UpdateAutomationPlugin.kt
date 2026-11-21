package com.vikram.trondroid.automation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Android-safe replacement for the upstream server self-update command. */
class UpdateAutomationPlugin : AutomationPlugin {
    override val id = "userbot:update"
    override val commands = listOf(
        AutomationCommand("update", "Check the latest published TronDroid release.", "update")
    )

    override fun onCommand(context: AutomationContext) {
        context.reply("Checking GitHub releases…")
        Thread({
            val result = runCatching {
                val connection = URL("https://api.github.com/repos/TronUb/TronDroid/releases/latest")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "TronDroid-Android")
                try {
                    val code = connection.responseCode
                    if (code == 404) return@runCatching "No published release is available yet.\nhttps://github.com/TronUb/TronDroid/releases"
                    require(code in 200..299) { "GitHub returned HTTP $code" }
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name").ifBlank { "Latest release" }
                    val title = json.optString("name").ifBlank { tag }
                    val url = json.optString("html_url")
                    val notes = json.optString("body").take(1200)
                    buildString {
                        append("Latest TronDroid release: $title ($tag)")
                        if (notes.isNotBlank()) append("\n\n").append(notes)
                        if (url.isNotBlank()) append("\n\n").append(url)
                        append("\n\nAndroid installation is user-confirmed; this command does not silently install APKs.")
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { "Update check failed: ${it.message ?: "unknown error"}" }
            context.reply(result.take(3900))
        }, "TronDroid-UpdateCheck").apply { isDaemon = true; start() }
    }
}
