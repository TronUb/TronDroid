package com.vikram.trondroid.automation

import android.content.Context
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import kotlin.system.measureTimeMillis

class ToolsExtrasAutomationPlugin(context: Context, private val isDeletedCacheEnabled: () -> Boolean = { true }) : AutomationPlugin {
    override val id = "userbot:tools"
    private val recentMessages = LinkedHashMap<Pair<Long, Long>, JSONObject>()
    private val deletedMessages = ArrayDeque<JSONObject>()
    override val commands = listOf(
        AutomationCommand("wlink", "Search up to 500 recent messages in this chat and return links where Telegram supports them.", "wlink <word or phrase>"),
        AutomationCommand("json", "Show a structured JSON summary of this or a replied Telegram message.", "json (reply optional)"),
        AutomationCommand("mlink", "Get a Telegram link for a replied message when the chat supports direct links.", "mlink (reply optional)"),
        AutomationCommand("saved", "Forward a replied message to your Saved Messages.", "saved (reply required)"),
        AutomationCommand("forward", "Forward a replied message to this chat or a numeric destination chat ID.", "forward [destination chat ID] (reply required)"),
        AutomationCommand("commonchat", "List groups shared with a replied user or numeric user ID.", "commonchat <user id> (or reply)"),
        AutomationCommand("speedtest", "Measure a short HTTPS download throughput sample.", "speedtest"),
        AutomationCommand("undlt", "Show messages this plugin cached before Telegram reported their deletion; cannot recover older deletions.", "undlt [1-20]")
    )

    override fun onTelegramUpdate(context: AutomationUpdateContext) {
        if (!isDeletedCacheEnabled()) return
        when (val update = context.update) {
            is TdApi.UpdateNewMessage -> {
                val message = update.message
                val text = (message.content as? TdApi.MessageText)?.text?.text ?: return
                if (text.isBlank()) return
                synchronized(recentMessages) {
                    recentMessages[message.chatId to message.id] = JSONObject()
                        .put("chat_id", message.chatId)
                        .put("message_id", message.id)
                        .put("date", message.date)
                        .put("sender_id", (message.senderId as? TdApi.MessageSenderUser)?.userId ?: 0L)
                        .put("outgoing", message.isOutgoing)
                        .put("text", text.take(3500))
                    while (recentMessages.size > 500) {
                        val oldest = recentMessages.keys.firstOrNull() ?: break
                        recentMessages.remove(oldest)
                    }
                }
            }
            is TdApi.UpdateDeleteMessages -> {
                synchronized(recentMessages) {
                    update.messageIds.forEach { messageId ->
                        val cached = recentMessages.remove(update.chatId to messageId) ?: return@forEach
                        deletedMessages.addLast(cached)
                        while (deletedMessages.size > 100) deletedMessages.removeFirst()
                    }
                }
            }
        }
    }

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "wlink" -> {
                val query = context.arguments.joinToString(" ").trim()
                if (query.isBlank()) return context.reply("Usage: .wlink <word or phrase>")
                context.reply("Searching up to 500 recent messages…")
                searchHistory(gateway, context, query, 0L, 5, mutableListOf())
            }
            "json" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                val message = replied ?: context.message.tdMessage
                    ?: return context.reply("Message data is unavailable.")
                val text = (message.content as? TdApi.MessageText)?.text?.text.orEmpty()
                val json = JSONObject()
                    .put("chat_id", message.chatId)
                    .put("message_id", message.id)
                    .put("date_unix", message.date)
                    .put("outgoing", message.isOutgoing)
                    .put("sender", when (val sender = message.senderId) {
                        is TdApi.MessageSenderUser -> JSONObject().put("type", "user").put("id", sender.userId)
                        is TdApi.MessageSenderChat -> JSONObject().put("type", "chat").put("id", sender.chatId)
                        else -> JSONObject().put("type", "unknown")
                    })
                    .put("content_type", message.content.javaClass.simpleName)
                    .put("text", text)
                context.reply(json.toString(2).take(3900))
            }
            "mlink" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a message with .mlink")
                val link = privateMessageLink(replied.chatId, replied.id)
                context.reply(link ?: "Telegram does not expose a public message link for this chat. Message ID: ${replied.id}")
            }
            "saved" -> forwardToSaved(gateway, context)
            "forward" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a message with .forward")
                val destination = context.arguments.firstOrNull()?.toLongOrNull() ?: context.message.chatId
                gateway.send(TdApi.ForwardMessages(destination, null, replied.chatId, longArrayOf(replied.id), null, false, false)) { result ->
                    context.reply(if (result is TdApi.Error) "Forward failed: ${result.message}" else "Message forwarded.")
                }
            }
            "commonchat" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                val target = context.arguments.firstOrNull()?.toLongOrNull() ?: (replied?.senderId as? TdApi.MessageSenderUser)?.userId
                    ?: return context.reply("Usage: .commonchat <user id> or reply to a user's message")
                gateway.send(TdApi.GetGroupsInCommon(target, 0, 100)) { result ->
                    if (result is TdApi.Error) return@send context.reply("Telegram error: ${result.message}")
                    val chats: LongArray = (result as? TdApi.Chats)?.chatIds ?: longArrayOf()
                    val names = chats.map { id -> gateway.cachedChat(id)?.title ?: "Chat $id" }
                    context.reply(if (names.isEmpty()) "No shared groups returned." else "Common groups:\\n" + names.joinToString("\\n").take(3800))
                }
            }
            "speedtest" -> speedTest(context)
            "undlt" -> {
                val count = context.arguments.firstOrNull()?.toIntOrNull()?.coerceIn(1, 20) ?: 5
                val deleted = readArray("deleted")
                if (deleted.length() == 0) return context.reply("No deleted messages have been cached yet. This only records messages received after Tools is enabled; Telegram cannot provide older deleted content.")
                val start = (deleted.length() - count).coerceAtLeast(0)
                val lines = (start until deleted.length()).mapNotNull { index ->
                    val item = deleted.optJSONObject(index) ?: return@mapNotNull null
                    val chatId = item.optLong("chat_id")
                    val chatName = gateway.cachedChat(chatId)?.title ?: "chat $chatId"
                    "• $chatName [${item.optLong("message_id")}]: ${item.optString("text")}"
                }
                context.reply("Locally cached deleted messages:\\n" + lines.joinToString("\\n").take(3900))
            }
        }
    }

    private fun searchHistory(
        gateway: com.vikram.trondroid.telegram.TelegramClientGateway,
        context: AutomationContext,
        query: String,
        fromMessageId: Long,
        pagesLeft: Int,
        found: MutableList<String>
    ) {
        if (pagesLeft <= 0) return finishSearch(context, query, found)
        gateway.send(TdApi.GetChatHistory(context.message.chatId, fromMessageId, 0, 100, false)) { result ->
            val page = result as? TdApi.Messages
            if (page == null) {
                context.reply(if (result is TdApi.Error) "History search failed: ${result.message}" else "No chat history returned.")
                return@send
            }
            page.messages.forEach { message ->
                val text = (message.content as? TdApi.MessageText)?.text?.text.orEmpty()
                if (text.contains(query, ignoreCase = true)) {
                    val link = privateMessageLink(message.chatId, message.id)
                    found += (link ?: "Message ID ${message.id}") + " — " + text.replace("\\n", " ").take(140)
                }
            }
            val oldest = page.messages.minOfOrNull { it.id } ?: 0L
            if (page.messages.size < 100 || oldest <= 1L) finishSearch(context, query, found)
            else searchHistory(gateway, context, query, oldest - 1L, pagesLeft - 1, found)
        }
    }

    private fun finishSearch(context: AutomationContext, query: String, found: List<String>) {
        context.reply(if (found.isEmpty()) "No matches found for: $query" else "Matches for “$query” (up to 500 recent messages):\\n" + found.take(25).joinToString("\\n").take(3900))
    }

    private fun privateMessageLink(chatId: Long, messageId: Long): String? {
        val raw = chatId.toString()
        if (!raw.startsWith("-100") || raw.length <= 4) return null
        return "https://t.me/c/${raw.removePrefix("-100")}/$messageId"
    }

    private fun forwardToSaved(gateway: com.vikram.trondroid.telegram.TelegramClientGateway, context: AutomationContext) {
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a message with .saved")
        val userId = gateway.currentUserId() ?: return context.reply("Account ID unavailable.")
        gateway.send(TdApi.CreatePrivateChat(userId, false)) { chatResult ->
            val saved = chatResult as? TdApi.Chat
                ?: return@send context.reply(if (chatResult is TdApi.Error) chatResult.message else "Saved Messages unavailable.")
            gateway.send(TdApi.ForwardMessages(saved.id, null, replied.chatId, longArrayOf(replied.id), null, false, false)) { result ->
                context.reply(if (result is TdApi.Error) "Save failed: ${result.message}" else "Saved to Saved Messages.")
            }
        }
    }

    private fun speedTest(context: AutomationContext) {
        context.reply("Testing download speed (up to 8 MB)…")
        Thread({
            runCatching {
                val connection = URL("https://speed.cloudflare.com/__down?bytes=8000000").openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "TronDroid/1.0")
                var bytes = 0L
                val elapsed = measureTimeMillis {
                    try {
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (bytes < 8_000_000L) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                bytes += read
                            }
                        }
                    } finally { connection.disconnect() }
                }.coerceAtLeast(1L)
                val mbps = bytes * 8.0 / elapsed / 1000.0
                "Downloaded %.2f MB in %.2f s\\nApprox. download speed: %.2f Mbps".format(bytes / 1_000_000.0, elapsed / 1000.0, mbps)
            }.onSuccess(context.reply).onFailure { context.reply("Speed test failed: ${it.message}") }
        }, "TronDroid-SpeedTest").apply { isDaemon = true; start() }
    }

    private fun readArray(key: String): JSONArray = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    private fun writeArray(key: String, value: JSONArray) { prefs.edit().putString(key, value.toString()).apply() }
    private fun trimArray(source: JSONArray, limit: Int): JSONArray {
        val result = JSONArray()
        for (index in (source.length() - limit).coerceAtLeast(0) until source.length()) {
            source.optJSONObject(index)?.let(result::put)
        }
        return result
    }
}
