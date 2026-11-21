package com.vikram.trondroid.automation

import android.content.Context
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileToolsAutomationPlugin(context: Context) : AutomationPlugin {
    override val id = "userbot:filetools"
    private val directory = File(context.filesDir, "plugin-files").apply { mkdirs() }
    override val commands = listOf(
        AutomationCommand("new", "Create and send a text file.", "new <filename> <content>"),
        AutomationCommand("zip", "Compress a file already in the plugin-files folder.", "zip <filename>"),
        AutomationCommand("unzip", "Safely extract a ZIP file into the plugin-files folder.", "unzip <filename>")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "new" -> {
                val filename = context.arguments.firstOrNull()?.let(::safeName)
                    ?: return context.reply("Usage: .new <filename> <content>")
                val content = context.arguments.drop(1).joinToString(" ")
                if (content.isBlank()) return context.reply("Provide file content after the filename.")
                val file = File(directory, filename)
                file.writeText(content.take(100_000), Charsets.UTF_8)
                gateway.sendDocumentPath(context.message.chatId, file.absolutePath, "Created: $filename") { result ->
                    context.reply(if (result is TdApi.Error) "Upload failed: ${result.message}" else "Created and sent $filename.")
                }
            }
            "zip" -> {
                val name = context.arguments.firstOrNull()?.let(::safeName)
                    ?: return context.reply("Usage: .zip <filename>")
                val source = File(directory, name)
                if (!source.isFile) return context.reply("File not found in local plugin storage: $name")
                val target = File(directory, "$name.zip")
                ZipOutputStream(target.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(source.name))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                gateway.sendDocumentPath(context.message.chatId, target.absolutePath, "Compressed: $name") { result ->
                    context.reply(if (result is TdApi.Error) "ZIP upload failed: ${result.message}" else "ZIP created and sent.")
                }
            }
            "unzip" -> {
                val name = context.arguments.firstOrNull()?.let(::safeName)
                    ?: return context.reply("Usage: .unzip <filename.zip>")
                val source = File(directory, name)
                if (!source.isFile || !name.endsWith(".zip", true)) return context.reply("ZIP file not found.")
                var extracted = 0
                ZipInputStream(source.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val entryName = safeName(entry.name) ?: return context.reply("Archive contains an unsafe path.")
                        if (!entry.isDirectory) {
                            val target = File(directory, entryName)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output -> zip.copyTo(output) }
                            extracted++
                        }
                        zip.closeEntry()
                    }
                }
                context.reply("Extracted $extracted file(s) into app plugin storage.")
            }
        }
    }

    private fun safeName(value: String): String? {
        val trimmed = value.trim().replace('\\', '/')
        if (trimmed.isBlank() || trimmed.startsWith("/") || trimmed.split('/').any { it == ".." }) return null
        return trimmed
    }
}
