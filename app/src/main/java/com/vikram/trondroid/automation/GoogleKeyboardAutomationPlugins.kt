package com.vikram.trondroid.automation

import android.net.Uri
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class GoogleImagesAutomationPlugin : AutomationPlugin {
    override val id = "userbot:google"
    override val commands = listOf(
        AutomationCommand("img", "Search Wikimedia Commons for images and send up to three results.", "img [count 1-3] <query>"),
        AutomationCommand("pic", "Alias for image search.", "pic <query>"),
        AutomationCommand("sauce", "Open Google Lens for a replied image so you can run a reverse-image search.", "sauce (reply to photo)")
    )
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TronDroid-ImageSearch").apply { isDaemon = true }
    }

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "img", "pic" -> searchImages(context)
            "sauce" -> reverseSearch(context)
        }
    }

    private fun searchImages(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val raw = context.message.text.trim().substringAfter(" ", "").trim()
        if (raw.isBlank()) return context.reply("Usage: .img [count 1-3] <query>\nExample: .img 3 mountain landscape")
        val first = raw.substringBefore(' ')
        val count = first.toIntOrNull()?.coerceIn(1, 3) ?: 3
        val query = if (first.toIntOrNull() != null) raw.substringAfter(' ', "").trim() else raw
        if (query.isBlank()) return context.reply("Enter an image search query.")
        context.reply("Searching Wikimedia Commons for: $query")
        executor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val endpoint = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$encoded&gsrnamespace=6&gsrlimit=$count&prop=imageinfo&iiprop=url&iiurlwidth=1280&format=json"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 12_000
                connection.readTimeout = 18_000
                connection.setRequestProperty("User-Agent", "TronDroid/1.0 (Android image search)")
                val json = try {
                    require(connection.responseCode in 200..299) { "Image search returned HTTP ${connection.responseCode}" }
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally { connection.disconnect() }
                val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages")
                    ?: return@execute context.reply("No images found for: $query")
                val urls = mutableListOf<String>()
                val keys = pages.keys()
                while (keys.hasNext()) {
                    val page = pages.getJSONObject(keys.next())
                    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val imageUrl = info.optString("thumburl").ifBlank { info.optString("url") }
                    if (imageUrl.startsWith("https://")) urls += imageUrl
                }
                if (urls.isEmpty()) return@execute context.reply("No usable image results found.")
                context.reply("Found ${urls.size} image(s). Sending…")
                urls.forEachIndexed { index, imageUrl ->
                    gateway.sendPhotoUrl(context.message.chatId, imageUrl, "Image ${index + 1} · $query") { result ->
                        if (result is TdApi.Error) context.reply("Image ${index + 1} failed: ${result.message}")
                    }
                }
            } catch (error: Exception) {
                context.reply("Image search failed: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun reverseSearch(context: AutomationContext) {
        val replied = context.message.tdMessage?.let { context.telegram?.repliedMessage(it) }
            ?: return context.reply("Reply to a photo or animation with .sauce.")
        val isImage = replied.content is TdApi.MessagePhoto || replied.content is TdApi.MessageAnimation
        if (!isImage) return context.reply("The replied message does not contain a supported image.")
        // Google Lens requires the user to submit the image to Google's service.
        // Open the official Lens page rather than silently uploading private media.
        context.reply("Google Lens reverse search: https://lens.google.com/\nOpen the link and upload the replied image yourself. TronDroid does not upload private photos to third-party search services.")
    }
}

/**
 * Creates URL-button keyboards in a Telegram message. No bot token, external
 * service, permission or background process is needed.
 */
class KeyboardAutomationPlugin : AutomationPlugin {
    override val id = "userbot:keyboard"
    override val commands = listOf(
        AutomationCommand("kbd", "Create a one-button URL keyboard.", "kbd <button text> | <https URL> | <message>"),
        AutomationCommand("keyboard", "Alias for kbd.", "keyboard <button text> | <https URL> | <message>")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val chat = gateway.cachedChat(context.message.chatId)
        val isGroup = chat?.type is TdApi.ChatTypeBasicGroup || chat?.type is TdApi.ChatTypeSupergroup
        if (!isGroup) return context.reply("Use .kbd in a group chat.")
        val raw = context.message.text.trim().substringAfter(" ", "")
        val parts = raw.split("|", limit = 3).map { it.trim() }
        if (parts.size != 3 || parts.any { it.isBlank() }) {
            return context.reply("Usage: .kbd <button text> | <https URL> | <message>\nExample: .kbd Open website | https://example.com | Visit our website")
        }
        val (buttonText, url, message) = parts
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank()) {
            return context.reply("Only valid HTTPS button links are allowed.")
        }
        if (buttonText.length > 64 || message.length > 3500) {
            return context.reply("Button text must be 64 characters or less; message must be 3500 characters or less.")
        }
        val keyboard = TdApi.ReplyMarkupInlineKeyboard(
            arrayOf(
                arrayOf(
                    TdApi.InlineKeyboardButton(
                        buttonText,
                        TdApi.InlineKeyboardButtonTypeUrl(url)
                    )
                )
            )
        )
        gateway.send(
            TdApi.SendMessage(
                context.message.chatId,
                null,
                null,
                null,
                keyboard,
                TdApi.InputMessageText(TdApi.FormattedText(message, emptyArray()), null, true)
            )
        ) { result ->
            context.reply(
                if (result is TdApi.Error) {
                    "Telegram could not create this keyboard: ${result.message}. Telegram may require a bot account to send inline keyboards; this userbot will not bypass that restriction."
                } else "Keyboard message sent."
            )
        }
    }
}
