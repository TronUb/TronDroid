package com.vikram.trondroid.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * The only supported way for plugins to issue TDLib requests.
 * Plugins share the account's authenticated client; they must never create
 * another TDLib Client or open a second Telegram session.
 */
class TelegramClientGateway(
    private val clientProvider: () -> Client?,
    val updates: TelegramUpdateStore
) {
    fun <T : TdApi.Object> send(
        function: TdApi.Function<T>,
        callback: (T) -> Unit
    ) {
        val activeClient = clientProvider() ?: return
        activeClient.send(function) { result ->
            @Suppress("UNCHECKED_CAST")
            callback(result as T)
        }
    }

    fun cachedMessage(chatId: Long, messageId: Long): TdApi.Message? =
        updates.messages.value[chatId to messageId]

    fun repliedMessage(message: TdApi.Message): TdApi.Message? {
        val reply = message.replyTo as? TdApi.MessageReplyToMessage ?: return null
        return cachedMessage(message.chatId, reply.messageId)
    }

    fun cachedChat(chatId: Long): TdApi.Chat? = updates.chats.value[chatId]
    fun cachedUser(userId: Long): TdApi.User? = updates.users.value[userId]
    fun cachedFile(fileId: Int): TdApi.File? = updates.files.value[fileId]

    fun isConnected(): Boolean = clientProvider() != null
}
