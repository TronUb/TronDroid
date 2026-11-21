package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ImageExtrasAutomationPlugin : AutomationPlugin {
    override val id = "userbot:image"
    override val commands = listOf(
        AutomationCommand("cat", "Send a random cat image.", "cat"),
        AutomationCommand("dog", "Send a random dog image.", "dog"),
        AutomationCommand("waifu", "Send a random anime-style image.", "waifu")
    )
    override fun onCommand(context: AutomationContext) {
        val url = when (context.command) {
            "cat" -> "https://cataas.com/cat"
            "dog" -> "https://dog.ceo/api/breeds/image/random"
            "waifu" -> "https://api.waifu.pics/sfw/waifu"
            else -> return
        }
        val mediaUrl = if (context.command == "cat") url else runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            try {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optString(if (context.command == "dog") "message" else "url")
            } finally { connection.disconnect() }
        }.getOrDefault("")
        if (!mediaUrl.startsWith("https://")) return context.reply("Image service did not return a valid image.")
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        gateway.sendPhotoUrl(context.message.chatId, mediaUrl, context.command) { result ->
            if (result is TdApi.Error) context.reply("Image send failed: ${result.message}")
        }
    }
}

class GoogleExtrasAutomationPlugin : AutomationPlugin {
    override val id = "userbot:google"
    override val commands = listOf(
        AutomationCommand("sauce", "Create a reverse-image search link for a replied photo.", "sauce (reply to photo)")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a photo with .sauce")
        val photo = replied.content as? TdApi.MessagePhoto
            ?: return context.reply("Reply to a Telegram photo with .sauce")
        val fileId = photo.photo.sizes.lastOrNull()?.photo?.id
            ?: return context.reply("Photo file is unavailable.")
        gateway.downloadFile(fileId) { file, error ->
            if (file == null || error != null) {
                context.reply("Photo download failed: ${error ?: "unknown error"}")
            } else {
                context.reply("Image downloaded to: ${file.local.path}\nUpload it to Google Lens to reverse-search: https://lens.google.com/")
            }
        }
    }
}
