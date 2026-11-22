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
        AutomationCommand("new", "Create and send a text file in app-private plugin storage.", "new <filename> <content>"),
        AutomationCommand("zip", "Compress one file from app-private plugin storage (50 MB maximum).", "zip <filename>"),
        AutomationCommand("unzip", "Safely extract a ZIP with entry-count and decompressed-size limits.", "unzip <filename.zip>")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "new" -> {
                val filename = context.arguments.firstOrNull()?.let(::safeName)
                    ?: return context.reply("Usage: .new <filename> <content> (filename: letters, numbers, dot, dash or underscore)")
                val content = context.arguments.drop(1).joinToString(" ")
                if (content.isBlank()) return context.reply("Provide file content after the filename.")
                val file = File(directory, filename)
                if (file.exists()) return context.reply("A file with that name already exists. Choose another filename.")
                runCatching { file.writeText(content.take(MAX_TEXT_CHARS), Charsets.UTF_8) }
                    .onFailure { return context.reply("Unable to create file: ${it.message}") }
                gateway.sendDocumentPath(context.message.chatId, file.absolutePath, "Created: $filename") { result ->
                    context.reply(if (result is TdApi.Error) "Upload failed: ${result.message}" else "Created and sent $filename.")
                }
            }
            "zip" -> {
                val name = context.arguments.firstOrNull()?.let(::safeName)
                    ?: return context.reply("Usage: .zip <filename>")
                val source = File(directory, name)
                if (!source.isFile) return context.reply("File not found in local plugin storage: $name")
                if (source.length() > MAX_ARCHIVE_BYTES) return context.reply("Source file exceeds the 50 MB limit.")
                val target = File(directory, "$name.zip")
                if (target.exists()) return context.reply("The ZIP output already exists. Rename or remove it first.")
                runCatching {
                    ZipOutputStream(target.outputStream().buffered()).use { zip ->
                        zip.putNextEntry(ZipEntry(source.name))
                        source.inputStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_ARCHIVE_BYTES) { "Source exceeds the 50 MB limit." }
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                    require(target.length() <= MAX_ARCHIVE_BYTES) { "ZIP output exceeds the 50 MB limit." }
                }.onFailure {
                    target.delete()
                    return context.reply("ZIP failed: ${it.message ?: "unknown error"}")
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
                if (source.length() > MAX_ARCHIVE_BYTES) return context.reply("ZIP archive exceeds the 50 MB compressed-size limit.")
                val created = mutableListOf<File>()
                val result = runCatching {
                    var extracted = 0
                    var totalBytes = 0L
                    ZipInputStream(source.inputStream().buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            require(!entry.isDirectory) { "Folders are not accepted inside ZIP files." }
                            require(++extracted <= MAX_ZIP_ENTRIES) { "ZIP contains more than 100 files." }
                            val entryName = safeName(entry.name)
                                ?: throw IllegalArgumentException("Archive contains an unsafe filename.")
                            val target = File(directory, entryName)
                            require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
                                "Archive entry escapes app storage."
                            }
                            require(!target.exists()) { "File already exists: $entryName" }
                            created += target
                            target.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var entryBytes = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    totalBytes += count
                                    require(entryBytes <= MAX_ENTRY_BYTES) { "A ZIP entry exceeds the 10 MB limit." }
                                    require(totalBytes <= MAX_ARCHIVE_BYTES) { "Extracted data exceeds the 50 MB limit." }
                                    output.write(buffer, 0, count)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                    "Extracted $extracted file(s) (${totalBytes / 1024} KB) into app-private plugin storage."
                }
                result.onSuccess(context.reply).onFailure {
                    created.forEach { file -> file.delete() }
                    context.reply("ZIP extraction rejected: ${it.message ?: "invalid archive"}")
                }
            }
        }
    }

    private fun safeName(value: String): String? {
        val name = value.trim()
        if (name.isBlank() || name == "." || name == ".." || name.length > 80) return null
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) return null
        return name
    }

    companion object {
        private const val MAX_TEXT_CHARS = 100_000
        private const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 10L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 100
    }
}
