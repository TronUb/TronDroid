package com.vikram.trondroid.automation

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
    override val id = "userbot:publishing"
    private val colors = listOf("aqua", "red", "blue", "green", "yellow", "gold", "orange", "purple", "black", "white", "lime", "silver", "maroon", "olive", "teal", "navy", "chocolate")
    override val commands = listOf(
        AutomationCommand("carbon", "Render code or text as a Carbon image.", "carbon [colour] <text>"),
        AutomationCommand("carblist", "List Carbon color presets.", "carblist"),
        AutomationCommand("telegraph", "Publish replied text to a Telegraph page.", "telegraph (reply to text)")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "carblist" -> context.reply("Carbon color presets:\n" + colors.joinToString(", "))
            "carbon" -> carbon(context)
            "telegraph" -> telegraph(context)
        }
    }

    private fun carbon(context: AutomationContext) {
        val raw = context.arguments.joinToString(" ").trim()
        if (raw.isBlank()) return context.reply("Usage: .carbon [colour] <code or text>")
        val color = raw.substringBefore(' ').lowercase().takeIf { it in colors }
        val code = if (color != null) raw.substringAfter(' ') else raw
        if (code.isBlank()) return context.reply("Provide code or text to render.")
        context.reply("Generating Carbon image…")
        Thread({
            runCatching {
                val encoded = URLEncoder.encode(code.take(12_000), "UTF-8")
                val url = "https://carbon-api.vercel.app/api?code=$encoded&theme=dracula&background=true&padding=24"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 45_000
                connection.setRequestProperty("User-Agent", "TronDroid/1.0")
                val file = File(cacheDir, "carbon_${System.currentTimeMillis()}.png")
                try {
                    require(connection.responseCode == 200) { "Carbon service returned HTTP ${connection.responseCode}" }
                    connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    require(file.length() in 1..15L * 1024 * 1024) { "Generated image is empty or too large." }
                } finally { connection.disconnect() }
                file
            }.onSuccess { file ->
                context.telegram?.sendDocumentPath(context.message.chatId, file.absolutePath, "Generated with Carbon") { result ->
                    context.reply(if (result is TdApi.Error) "Carbon upload failed: ${result.message}" else "Carbon image sent.")
                    file.delete()
                } ?: context.reply("Telegram session is unavailable.")
            }.onFailure { context.reply("Carbon failed: ${it.message ?: "unknown error"}") }
        }, "TronDroid-Carbon").apply { isDaemon = true }.start()
    }

    private fun telegraph(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to text or supported media with .telegraph")
        val text = (replied.content as? TdApi.MessageText)?.text?.text.orEmpty()
        val fileId = when (val media = replied.content) {
            is TdApi.MessagePhoto -> media.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo?.id
            is TdApi.MessageVideo -> media.video.video.id
            is TdApi.MessageAnimation -> media.animation.animation.id
            is TdApi.MessageDocument -> media.document.document.id
            is TdApi.MessageSticker -> media.sticker.sticker.id
            else -> null
        }
        if (text.isBlank() && fileId == null) return context.reply("Reply to text, photo, video, animation, sticker or document.")
        if (text.length > 4000) return context.reply("Text must be 1–4000 characters.")
        context.reply("Preparing Telegraph page…")
        val publish: (String?) -> Unit = { mediaPath ->
            Thread({
                runCatching<String> {
                    val nodes = org.json.JSONArray()
                    if (text.isNotBlank()) nodes.put(JSONObject().put("tag", "p").put("children", org.json.JSONArray().put(text)))
                    if (mediaPath != null) {
                        val uploaded = uploadMedia(File(mediaPath))
                        val tag = if (mediaPath.endsWith(".mp4", true) || mediaPath.endsWith(".webm", true)) "video" else "img"
                        nodes.put(JSONObject().put("tag", "figure").put("children", org.json.JSONArray().put(
                            JSONObject().put("tag", tag).put("attrs", JSONObject().put("src", "https://telegra.ph" + uploaded)))))
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
            }, "TronDroid-Telegraph").apply { isDaemon = true; start() }
        }
        if (fileId == null) publish(null) else {
            context.reply("Downloading replied media…")
            gateway.downloadFile(fileId) { file, error ->
                if (file == null || error != null) context.reply("Media download failed: " + (error ?: "unknown error"))
                else if (File(file.local.path).length() > 5L * 1024 * 1024) context.reply("Telegraph media must be 5 MB or smaller.")
                else publish(file.local.path)
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
