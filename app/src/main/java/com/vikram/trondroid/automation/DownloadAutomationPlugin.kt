package com.vikram.trondroid.automation

import android.content.Context
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DownloadAutomationPlugin(context: Context) : AutomationPlugin {
    override val id = "userbot:download"
    private val directory = File(context.filesDir, "downloads").apply { mkdirs() }
    override val commands = listOf(
        AutomationCommand("ls", "List files in the app download directory.", "ls"),
        AutomationCommand("download", "Download media from a replied Telegram message to app storage.", "download (reply to media)"),
        AutomationCommand("upload", "Send a file from app download storage.", "upload <filename>"),
        AutomationCommand("batchupload", "Send multiple files from app download storage.", "batchupload <filename1> <filename2> ...")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "ls" -> {
                val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
                context.reply(if (files.isEmpty()) "Download directory is empty." else
                    files.joinToString("\n") { "${it.name} — ${it.length() / 1024} KB" }.take(3800))
            }
            "download" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a Telegram media message with .download")
                val fileId = fileId(replied.content)
                    ?: return context.reply("The replied message does not contain a supported downloadable file.")
                val knownSize = gateway.cachedFile(fileId)?.size ?: 0L
                if (knownSize > MAX_FILE_BYTES) return context.reply("File exceeds the 50 MB download limit.")
                context.reply("Downloading Telegram media (50 MB limit)…")
                gateway.downloadFile(fileId) { file, error ->
                    if (file == null || error != null) {
                        context.reply("Download failed: ${error ?: "unknown error"}")
                    } else {
                        val source = File(file.local.path)
                        if (!source.isFile || source.length() <= 0L || source.length() > MAX_FILE_BYTES) {
                            return@downloadFile context.reply("Downloaded file is empty or exceeds the 50 MB limit.")
                        }
                        val safe = safeName(source.name.ifBlank { "telegram_${file.id}" }) ?: "telegram_${file.id}"
                        val destination = File(directory, safe)
                        runCatching {
                            if (source.absolutePath != destination.absolutePath) source.copyTo(destination, overwrite = true)
                            "Downloaded: ${destination.name} (${destination.length() / 1024} KB)"
                        }.onSuccess(context.reply).onFailure { context.reply("Unable to save file: ${it.message}") }
                    }
                }
            }
            "upload", "batchupload" -> {
                val names = context.arguments.mapNotNull(::safeName).distinct()
                if (names.isEmpty()) return context.reply("Usage: .${context.command} <filename>")
                if (names.size > MAX_BATCH_FILES) return context.reply("Upload at most 5 files per batch.")
                val files = names.map { File(directory, it) }.filter { it.isFile && it.length() in 1..MAX_FILE_BYTES }
                if (files.isEmpty()) return context.reply("No matching files under the 50 MB per-file limit. Use .ls to list available files.")
                context.reply("Uploading ${files.size} file(s)…")
                val completed = AtomicInteger(0)
                val failed = AtomicInteger(0)
                files.forEach { file ->
                    gateway.sendDocumentPath(context.message.chatId, file.absolutePath, file.name) { result ->
                        if (result is TdApi.Error) failed.incrementAndGet() else completed.incrementAndGet()
                        if (completed.get() + failed.get() == files.size) context.reply("Uploads finished. Sent: ${completed.get()}, failed: ${failed.get()}.")
                    }
                }
            }
        }
    }

    private fun fileId(content: TdApi.MessageContent): Int? = when (content) {
        is TdApi.MessageDocument -> content.document.document.id
        is TdApi.MessagePhoto -> content.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo?.id
        is TdApi.MessageVideo -> content.video.video.id
        is TdApi.MessageAnimation -> content.animation.animation.id
        is TdApi.MessageAudio -> content.audio.audio.id
        is TdApi.MessageVoiceNote -> content.voiceNote.voice.id
        is TdApi.MessageVideoNote -> content.videoNote.video.id
        is TdApi.MessageSticker -> content.sticker.sticker.id
        else -> null
    }

    companion object {
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private const val MAX_BATCH_FILES = 5
    }

    private fun safeName(value: String): String? {
        val name = value.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '\\' || it == '/' }) return null
        return name.take(180)
    }
}
