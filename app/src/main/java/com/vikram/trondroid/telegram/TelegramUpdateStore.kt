package com.vikram.trondroid.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

/**
 * In-memory, per-account cache of the TDLib stream.
 *
 * The raw update journal is deliberately retained alongside normalized entities:
 * new plugin features can consume an exact TDLib update even before a dedicated
 * projection is added here. The cache is bounded to avoid unbounded memory growth.
 */
class TelegramUpdateStore(
    private val journalLimit: Int = 500
) {
    private val _updates = MutableStateFlow<List<TdApi.Update>>(emptyList())
    val updates: StateFlow<List<TdApi.Update>> = _updates.asStateFlow()

    private val _messages = MutableStateFlow<Map<Long, TdApi.Message>>(emptyMap())
    val messages: StateFlow<Map<Long, TdApi.Message>> = _messages.asStateFlow()

    private val _chats = MutableStateFlow<Map<Long, TdApi.Chat>>(emptyMap())
    val chats: StateFlow<Map<Long, TdApi.Chat>> = _chats.asStateFlow()

    private val _users = MutableStateFlow<Map<Long, TdApi.User>>(emptyMap())
    val users: StateFlow<Map<Long, TdApi.User>> = _users.asStateFlow()

    private val _files = MutableStateFlow<Map<Int, TdApi.File>>(emptyMap())
    val files: StateFlow<Map<Int, TdApi.File>> = _files.asStateFlow()

    @Synchronized
    fun accept(update: TdApi.Update) {
        _updates.value = (_updates.value + update).takeLast(journalLimit.coerceAtLeast(1))

        when (update) {
            is TdApi.UpdateNewMessage -> putMessage(update.message)
            is TdApi.UpdateMessageSendSucceeded -> putMessage(update.message)
            is TdApi.UpdateMessageSendFailed -> putMessage(update.message)
            is TdApi.UpdateNewChat -> {
                _chats.value = _chats.value + (update.chat.id to update.chat)
            }
            is TdApi.UpdateUser -> {
                _users.value = _users.value + (update.user.id to update.user)
            }
            is TdApi.UpdateFile -> {
                _files.value = _files.value + (update.file.id to update.file)
            }
            is TdApi.UpdateDeleteMessages -> {
                val next = _messages.value.toMutableMap()
                update.messageIds.forEach { next.remove(it) }
                _messages.value = next
            }
        }
    }

    @Synchronized
    fun rememberChat(chat: TdApi.Chat) {
        _chats.value = _chats.value + (chat.id to chat)
    }

    @Synchronized
    fun rememberUser(user: TdApi.User) {
        _users.value = _users.value + (user.id to user)
    }

    @Synchronized
    fun rememberFile(file: TdApi.File) {
        _files.value = _files.value + (file.id to file)
    }

    private fun putMessage(message: TdApi.Message) {
        val chatMessages = _messages.value.toMutableMap()
        chatMessages[message.id] = message
        // Keep the latest 2,000 messages across chats in memory. TDLib remains
        // the durable source and can be queried for older messages on demand.
        if (chatMessages.size > 2_000) {
            val oldest = chatMessages.values.minByOrNull { it.date }
            if (oldest != null) chatMessages.remove(oldest.id)
        }
        _messages.value = chatMessages
    }

    @Synchronized
    fun clear() {
        _updates.value = emptyList()
        _messages.value = emptyMap()
        _chats.value = emptyMap()
        _users.value = emptyMap()
        _files.value = emptyMap()
    }
}
