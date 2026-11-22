package com.vikram.trondroid.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private object TelegraphHttp {
    fun request(url: String, method: String = "GET", body: String? = null, contentType: String = "application/json"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "TronDroid/1.0")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "HTTP $code: ${response.take(300)}" }
            response
        } finally { connection.disconnect() }
    }
}

class PublishingAutomationPlugin(private val cacheDir: File) : AutomationPlugin {
    override val id = "userbot:telegraph"
    override val commands = listOf(
        AutomationCommand("telegraph", "Publish replied text or supported media to a Telegraph page.", "telegraph (reply to text, photo or video)"),
        AutomationCommand("tgm", "Alias for telegraph publishing.", "tgm (reply to text, photo or video)")
    )

    override fun onCommand(context: AutomationContext) {
        if (context.command == "telegraph" || context.command == "tgm") telegraph(context)
    }

    private fun telegraph(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to text, photo, video or animation with .telegraph")
        val text = when (val content = replied.content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text
            is TdApi.MessageVideo -> content.caption.text
            is TdApi.MessageAnimation -> content.caption.text
            is TdApi.MessageDocument -> content.caption.text
            else -> ""
        }
        val mediaFile = when (val media = replied.content) {
            is TdApi.MessagePhoto -> media.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo?.id?.let { it to ".jpg" }
            is TdApi.MessageVideo -> media.video.video.id to ".mp4"
            is TdApi.MessageAnimation -> when (media.animation.mimeType.lowercase()) {
                "image/gif" -> media.animation.animation.id to ".gif"
                "video/mp4" -> media.animation.animation.id to ".mp4"
                else -> null
            }
            is TdApi.MessageDocument -> when (media.document.mimeType.lowercase()) {
                "image/jpeg", "image/jpg" -> media.document.document.id to ".jpg"
                "image/png" -> media.document.document.id to ".png"
                "image/gif" -> media.document.document.id to ".gif"
                "video/mp4" -> media.document.document.id to ".mp4"
                else -> null
            }
            is TdApi.MessageSticker -> if (media.sticker.format is TdApi.StickerFormatWebp) media.sticker.sticker.id to ".webp" else null
            else -> null
        }
        val hasMedia = replied.content !is TdApi.MessageText
        if (text.isBlank() && mediaFile == null) {
            return context.reply(if (hasMedia) {
                "Unsupported Telegraph media. Use JPEG, PNG, GIF, MP4 or a static WebP sticker (max 5 MB)."
            } else "The replied message contains no publishable text.")
        }
        if (text.length > 4000) return context.reply("Text must be 1–4000 characters.")
        val knownMediaSize = mediaFile?.first?.let { gateway.cachedFile(it)?.size } ?: 0L
        if (knownMediaSize > 5L * 1024 * 1024) return context.reply("Telegraph media must be 5 MB or smaller.")
        context.reply("Preparing Telegraph page…")
        val publish: (String?) -> Unit = { mediaPath ->
            Thread({
                try {
                    runCatching<String> {
                        val nodes = org.json.JSONArray()
                        if (text.isNotBlank()) nodes.put(JSONObject().put("tag", "p").put("children", org.json.JSONArray().put(text)))
                        if (mediaPath != null) {
                            val uploaded = uploadMedia(File(mediaPath))
                            val extension = File(mediaPath).extension.lowercase()
                            val tag = if (extension == "mp4") "video" else "img"
                            nodes.put(JSONObject().put("tag", "figure").put("children",
                                org.json.JSONArray().put(JSONObject().put("tag", tag).put("attrs", JSONObject().put("src", "https://telegra.ph" + uploaded)))))
                        }
                        val accountBody = "short_name=TronDroid&author_name=TronDroid"
                        val account = JSONObject(TelegraphHttp.request("https://api.telegra.ph/createAccount", "POST", accountBody, "application/x-www-form-urlencoded"))
                        require(account.optBoolean("ok")) { account.optJSONObject("error")?.optString("message") ?: "Unable to create Telegraph account." }
                        val token = account.getJSONObject("result").getString("access_token")
                        val pageBody = "access_token=" + URLEncoder.encode(token, "UTF-8") +
                            "&title=" + URLEncoder.encode("Telegram message", "UTF-8") +
                            "&author_name=TronDroid&content=" + URLEncoder.encode(nodes.toString(), "UTF-8")
                        val page = JSONObject(TelegraphHttp.request("https://api.telegra.ph/createPage", "POST", pageBody, "application/x-www-form-urlencoded"))
                        require(page.optBoolean("ok")) { page.optJSONObject("error")?.optString("message") ?: "Unable to publish page." }
                        page.getJSONObject("result").getString("url")
                    }.onSuccess { context.reply("Telegraph page: " + it) }
                        .onFailure { context.reply("Telegraph failed: " + (it.message ?: "unknown error")) }
                } finally {
                    mediaPath?.let { File(it).delete() }
                }
            }, "TronDroid-Telegraph").apply { isDaemon = true; start() }
        }
        val media = mediaFile
        if (media == null) publish(null) else {
            context.reply("Downloading replied media…")
            gateway.downloadFile(media.first) { file, error ->
                if (file == null || error != null) {
                    context.reply("Media download failed: " + (error ?: "unknown error"))
                } else if (File(file.local.path).length() > 5L * 1024 * 1024) {
                    context.reply("Telegraph media must be 5 MB or smaller.")
                } else {
                    runCatching {
                        val extension = if (media.second == ".webp") ".png" else media.second
                        val staged = File(cacheDir, "telegraph_${System.currentTimeMillis()}$extension")
                        if (media.second == ".webp") {
                            val bitmap = BitmapFactory.decodeFile(file.local.path)
                                ?: throw IllegalArgumentException("Unable to decode this WebP sticker.")
                            try {
                                staged.outputStream().use { output ->
                                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                        throw IllegalStateException("Unable to convert sticker to PNG.")
                                    }
                                }
                            } finally { bitmap.recycle() }
                        } else {
                            File(file.local.path).inputStream().use { input ->
                                staged.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        require(staged.length() in 1..5L * 1024 * 1024) { "Converted Telegraph media must be 5 MB or smaller." }
                        staged
                    }.onSuccess { staged -> publish(staged.absolutePath) }
                        .onFailure { context.reply("Media preparation failed: " + (it.message ?: "unknown error")) }
                }
            }
        }
    }

    private fun uploadMedia(file: File): String {
        val boundary = "----TronDroid" + System.currentTimeMillis()
        val connection = URL("https://telegra.ph/upload").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        try {
            connection.outputStream.use { output ->
                output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + file.name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
                file.inputStream().use { it.copyTo(output) }
                output.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "Telegraph upload HTTP " + code }
            val path = org.json.JSONArray(body).optJSONObject(0)?.optString("src").orEmpty()
            require(path.startsWith("/")) { "Telegraph returned an invalid media path." }
            return path
        } finally { connection.disconnect() }
    }
}
