package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class PurgeAutomationPlugin : AutomationPlugin {
    override val id = "userbot:purge"
    override val commands = listOf(
        AutomationCommand("purge", "Delete messages from a replied message through the latest message.", "purge (reply required)"),
        AutomationCommand("purgecount", "Delete a number of recent messages.", "purgecount <1-100>"),
        AutomationCommand("del", "Delete the replied message and this command.", "del (reply required)")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val source = context.message.tdMessage
        val replied = source?.let(gateway::repliedMessage)
        when (context.command) {
            "del" -> {
                val ids = listOfNotNull(source?.id, replied?.id).distinct().toLongArray()
                if (ids.isEmpty()) return context.reply("Reply to a message with .del")
                gateway.send(TdApi.DeleteMessages(context.message.chatId, ids, true)) { result ->
                    context.reply(if (result is TdApi.Error) "Delete failed: ${result.message}" else "Deleted ${ids.size} message(s).")
                }
            }
            "purge" -> {
                if (replied == null) return context.reply("Reply to the first message to delete, then use .purge")
                context.reply("Purging messages…")
                gateway.send(TdApi.GetChatHistory(context.message.chatId, replied.id, 0, 100, false)) { result ->
                    if (result !is TdApi.Messages) {
                        context.reply(if (result is TdApi.Error) "Unable to load messages: ${result.message}" else "No messages were returned.")
                        return@send
                    }
                    val ids = result.messages.map { it.id }.filter { it >= replied.id }.toLongArray()
                    if (ids.isEmpty()) return@send context.reply("No messages found to delete.")
                    gateway.send(TdApi.DeleteMessages(context.message.chatId, ids, true)) { deleted ->
                        context.reply(if (deleted is TdApi.Error) "Purge failed: ${deleted.message}" else "Deleted ${ids.size} messages.")
                    }
                }
            }
            "purgecount" -> {
                val count = context.arguments.firstOrNull()?.toIntOrNull()
                if (count == null || count !in 1..100) return context.reply("Usage: .purgecount <1-100>")
                gateway.send(TdApi.GetChatHistory(context.message.chatId, 0, 0, count + 1, false)) { result ->
                    if (result !is TdApi.Messages) {
                        context.reply(if (result is TdApi.Error) "Unable to load messages: ${result.message}" else "No messages were returned.")
                        return@send
                    }
                    val ids = result.messages.map { it.id }.toLongArray()
                    if (ids.isEmpty()) return@send context.reply("No messages found.")
                    gateway.send(TdApi.DeleteMessages(context.message.chatId, ids, true)) { deleted ->
                        context.reply(if (deleted is TdApi.Error) "Delete failed: ${deleted.message}" else "Deleted ${ids.size} recent messages.")
                    }
                }
            }
        }
    }
}

class StatsAutomationPlugin : AutomationPlugin {
    override val id = "userbot:stats"
    override val commands = listOf(
        AutomationCommand("stats", "Count cached private chats, bots, groups and channels.", "stats")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val chats = gateway.updates.chats.value.values
        var bots = 0
        var users = 0
        var groups = 0
        var channels = 0
        chats.forEach { chat ->
            when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> {
                    val userId = type.userId
                    if (gateway.cachedUser(userId)?.type is TdApi.UserTypeBot) bots++ else users++
                }
                is TdApi.ChatTypeSecret -> users++
                is TdApi.ChatTypeBasicGroup -> groups++
                is TdApi.ChatTypeSupergroup -> if (type.isChannel) channels++ else groups++
            }
        }
        context.reply(
            "Telegram dialog statistics (loaded chats)\n" +
                "Bots: $bots\nUsers: $users\nGroups: $groups\nChannels: $channels\n" +
                "Total cached chats: ${chats.size}\n\nOpen more chats in Telegram to populate the local counts."
        )
    }
}
