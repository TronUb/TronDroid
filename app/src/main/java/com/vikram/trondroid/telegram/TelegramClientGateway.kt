package com.vikram.trondroid.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The only supported way for plugins to issue TDLib requests.
 * Plugins share the account's authenticated client; they must never create
 * another TDLib Client or open a second Telegram session.
 */
class TelegramClientGateway(
    private val clientProvider: () -> Client?,
    val updates: TelegramUpdateStore,
    private val cacheDirectory: File,
    private val currentUserId: () -> Long? = { null }
) {
    private val mediaExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "TronDroid-PluginMedia").apply { isDaemon = true }
    }

    fun send(
        function: TdApi.Function<*>,
        callback: (TdApi.Object) -> Unit
    ) {
        val activeClient = clientProvider() ?: return
        @Suppress("UNCHECKED_CAST")
        val typedFunction = function as TdApi.Function<TdApi.Object>
        activeClient.send(typedFunction) { result -> callback(result) }
    }

    fun sendToSavedMessages(text: String, callback: (TdApi.Object) -> Unit = {}) {
        val userId = currentUserId() ?: return callback(TdApi.Error(400, "Current Telegram account is unavailable."))
        send(TdApi.CreatePrivateChat(userId, false)) { chatResult ->
            if (chatResult !is TdApi.Chat) return@send callback(chatResult)
            val content = TdApi.InputMessageText(TdApi.FormattedText(text.take(4000), emptyArray()), null, true)
            send(TdApi.SendMessage(chatResult.id, null, null, null, null, content), callback)
        }
    }

    /**
     * Parse Tron-style Markdown into Telegram entities using TDLib.
     * TDLib calculates UTF-16 entity offsets, avoiding broken formatting around emoji.
     */
    private fun parseFormattedText(text: String, limit: Int, callback: (TdApi.FormattedText) -> Unit) {
        val source = text.take(limit)
        // Tron templates use **bold**; TDLib legacy Markdown uses *bold*.
        val markdown = source.replace("**", "*")
        send(TdApi.ParseTextEntities(markdown, TdApi.TextParseModeMarkdown(1))) { result ->
            callback(result as? TdApi.FormattedText ?: TdApi.FormattedText(source, emptyArray()))
        }
    }

    fun sendText(
        chatId: Long,
        text: String,
        replyMarkup: TdApi.ReplyMarkup? = null,
        callback: (TdApi.Object) -> Unit = {}
    ) {
        parseFormattedText(text, 4000) { formatted ->
            val content = TdApi.InputMessageText(formatted, null, true)
            send(TdApi.SendMessage(chatId, null, null, null, replyMarkup, content), callback)
        }
    }

    fun editText(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: TdApi.ReplyMarkup? = null,
        callback: (TdApi.Object) -> Unit = {}
    ) {
        parseFormattedText(text, 4000) { formatted ->
            val content = TdApi.InputMessageText(formatted, null, true)
            send(TdApi.EditMessageText(chatId, messageId, replyMarkup, content), callback)
        }
    }

    fun deleteMessage(chatId: Long, messageId: Long, callback: (TdApi.Object) -> Unit = {}) {
        send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), false), callback)
    }

    fun answerCallbackQuery(queryId: Long, text: String = "") {
        send(TdApi.AnswerCallbackQuery(queryId, text.take(200), false, null, 0)) {}
    }

    /** Download an HTTPS image and send it as a real Telegram photo message. */
    fun sendPhotoUrl(
        chatId: Long,
        url: String,
        caption: String = "",
        replyMarkup: TdApi.ReplyMarkup? = null,
        callback: (TdApi.Object) -> Unit = {}
    ) = sendMediaUrl(chatId, url, caption, animation = false, replyMarkup = replyMarkup, callback = callback)

    /** Download an HTTPS GIF/MP4 and send it as a Telegram animation message. */
    fun sendAnimationUrl(
        chatId: Long,
        url: String,
        caption: String = "",
        replyMarkup: TdApi.ReplyMarkup? = null,
        callback: (TdApi.Object) -> Unit = {}
    ) = sendMediaUrl(chatId, url, caption, animation = true, replyMarkup = replyMarkup, callback = callback)

    private fun sendMediaUrl(
        chatId: Long,
        url: String,
        caption: String,
        animation: Boolean,
        replyMarkup: TdApi.ReplyMarkup? = null,
        callback: (TdApi.Object) -> Unit
    ) {
        mediaExecutor.execute {
            runCatching {
                require(url.startsWith("https://", true)) { "Only HTTPS media URLs are supported." }
                val directory = File(cacheDirectory, "trondroid/plugin-media").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 48L * 60 * 60 * 1000 }
                    ?.forEach { it.delete() }
                val urlPath = URL(url).path.lowercase()
                val suffix = if (animation) {
                    if (urlPath.endsWith(".gif")) ".gif" else ".mp4"
                } else {
                    listOf(".jpg", ".jpeg", ".png", ".webp").firstOrNull { urlPath.endsWith(it) } ?: ".jpg"
                }
                val destination = File.createTempFile("plugin_media_", suffix, directory)
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "TronDroid/1.0")
                try {
                    val code = connection.responseCode
                    require(code in 200..299) { "Media download failed (HTTP $code)." }
                    require(connection.contentLengthLong <= 20L * 1024 * 1024 || connection.contentLengthLong < 0) {
                        "Media is larger than the 20 MB plugin limit."
                    }
                    connection.inputStream.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 20L * 1024 * 1024) { "Media is larger than the 20 MB plugin limit." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                parseFormattedText(caption, 1024) { formattedCaption ->
                    val content: TdApi.InputMessageContent = if (animation) {
                        TdApi.InputMessageAnimation(
                            TdApi.InputFileLocal(destination.absolutePath), null, intArrayOf(),
                            0, 0, 0, formattedCaption, false, false
                        )
                    } else {
                        TdApi.InputMessagePhoto(
                            TdApi.InputFileLocal(destination.absolutePath), null, null, intArrayOf(),
                            0, 0, formattedCaption, false, null, false
                        )
                    }
                    send(TdApi.SendMessage(chatId, null, null, null, replyMarkup, content), callback)
                }
            }.onFailure { error ->
                callback(TdApi.Error(400, error.message ?: "Unable to send media"))
            }
        }
    }


    fun sendPhotoPath(chatId: Long, path: String, caption: String = "", callback: (TdApi.Object) -> Unit = {}) {
        parseFormattedText(caption, 1024) { formatted ->
            val content = TdApi.InputMessagePhoto(
                TdApi.InputFileLocal(path), null, null, intArrayOf(), 0, 0,
                formatted, false, null, false
            )
            send(TdApi.SendMessage(chatId, null, null, null, null, content), callback)
        }
    }
    fun sendDocumentPath(chatId: Long, path: String, caption: String = "", callback: (TdApi.Object) -> Unit = {}) {
        val content = TdApi.InputMessageDocument(
            TdApi.InputFileLocal(path), null, false,
            TdApi.FormattedText(caption.take(1024), emptyArray())
        )
        send(TdApi.SendMessage(chatId, null, null, null, null, content), callback)
    }


    /** Ask TDLib to download a Telegram file and return its completed local path. */
    fun downloadFile(fileId: Int, callback: (TdApi.File?, String?) -> Unit) {
        send(TdApi.DownloadFile(fileId, 16, 0, 0, true)) { result ->
            val file = result as? TdApi.File
            if (file == null) callback(null, (result as? TdApi.Error)?.message ?: "TDLib did not return a file.")
            else if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) callback(file, null)
            else callback(file, "Download has not completed. Retry after TDLib finishes downloading.")
        }
    }

    fun cachedMessage(chatId: Long, messageId: Long): TdApi.Message? =
        updates.messages.value[chatId to messageId]

    fun repliedMessage(message: TdApi.Message): TdApi.Message? {
        val reply = message.replyTo as? TdApi.MessageReplyToMessage ?: return null
        val chatId = reply.chatId.takeIf { it != 0L } ?: message.chatId
        return cachedMessage(chatId, reply.messageId)
    }

    fun cachedChat(chatId: Long): TdApi.Chat? = updates.chats.value[chatId]
    fun cachedUser(userId: Long): TdApi.User? = updates.users.value[userId]
    fun cachedFile(fileId: Int): TdApi.File? = updates.files.value[fileId]

    fun currentUserId(): Long? = currentUserId()

    fun isConnected(): Boolean = clientProvider() != null

    fun shutdown() {
        mediaExecutor.shutdownNow()
    }
}
