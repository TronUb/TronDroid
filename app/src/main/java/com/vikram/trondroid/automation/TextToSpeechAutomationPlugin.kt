package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TextToSpeechAutomationPlugin(private val cacheDir: File) : AutomationPlugin {
    override val id = "userbot:tts"
    override val commands = listOf(
        AutomationCommand("tts", "Convert command text or a replied text message to speech.", "tts <text> (or reply to text)")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
        val repliedText = (replied?.content as? TdApi.MessageText)?.text?.text.orEmpty()
        val text = context.arguments.joinToString(" ").trim().ifBlank { repliedText }
        if (text.isBlank()) return context.reply("Usage: .tts <text> or reply to a text message.")
        if (text.length > 200) return context.reply("TTS is limited to 200 characters per request.")
        context.reply("Generating speech…")
        Thread({
            val output = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            runCatching {
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=en&q=" +
                    URLEncoder.encode(text, Charsets.UTF_8.name())
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                try {
                    require(connection.responseCode in 200..299) { "TTS service returned HTTP ${connection.responseCode}" }
                    connection.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
                } finally { connection.disconnect() }
                require(output.length() > 0) { "TTS service returned an empty audio file." }
                gateway.sendDocumentPath(context.message.chatId, output.absolutePath, "Text to speech") { result ->
                    context.reply(if (result is TdApi.Error) "Audio upload failed: ${result.message}" else "Audio generated.")
                    output.delete()
                }
            }.onFailure { output.delete(); context.reply("TTS failed: ${it.message ?: "unknown error"}") }
        }, "TronDroid-TTS").apply { isDaemon = true; start() }
    }
}
