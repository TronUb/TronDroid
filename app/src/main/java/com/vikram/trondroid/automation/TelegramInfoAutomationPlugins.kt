package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

private fun TdApi.Message.senderUserId(): Long? =
    (senderId as? TdApi.MessageSenderUser)?.userId

class ProfileAutomationPlugin : AutomationPlugin {
    override val id = "userbot:profile"
    override val commands = listOf(
        AutomationCommand("id", "Show user and chat IDs, including the replied user.", "id (reply optional)"),
        AutomationCommand("whois", "Show the profile of a replied user or numeric Telegram user ID.", "whois <user id>"),
        AutomationCommand("uinfo", "Alias for whois.", "uinfo <user id>"),
        AutomationCommand("mention", "Create a Telegram mention link for a user ID.", "mention <user id> <display text>"),
        AutomationCommand("block", "Block a user by ID or replied message.", "block <user id> (or reply)"),
        AutomationCommand("unblock", "Unblock a user by ID or replied message.", "unblock <user id> (or reply)"),
        AutomationCommand("sg", "Send a replied user profile link and ID to Saved Messages.", "sg (reply optional)"),
        AutomationCommand("repo", "Show the TronDroid source repository.", "repo")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram
        when (context.command) {
            "id" -> {
                val message = context.message.tdMessage
                val replied = message?.let { gateway?.repliedMessage(it) }
                val sender = message?.senderUserId() ?: context.message.senderId?.toLongOrNull()
                val replySender = replied?.senderUserId()
                val lines = mutableListOf("Chat ID: ${context.message.chatId}")
                sender?.let { lines.add("Your ID: $it") }
                replySender?.let { lines.add("Replied user ID: $it") }
                context.reply(lines.joinToString("\n"))
            }
            "repo" -> context.reply("TronDroid source: https://github.com/TronUb/TronDroid")
            "mention" -> {
                val id = context.arguments.firstOrNull()?.toLongOrNull()
                    ?: return context.reply("Usage: .mention <user id> <display text>")
                val label = context.arguments.drop(1).joinToString(" ").ifBlank { "User" }
                context.reply("$label (tg://user?id=$id)")
            }
            "block", "unblock" -> {
                val replied = message?.let { gateway?.repliedMessage(it) }
                val target = context.arguments.firstOrNull()?.toLongOrNull() ?: replied?.senderUserId()
                    ?: return context.reply("Usage: .${context.command} <user id> or reply to a user message")
                val blockList = if (context.command == "block") TdApi.BlockListMain() else null
                gateway?.send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(target), blockList)) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else if (context.command == "block") "User blocked." else "User unblocked.")
                }
            }
            "sg" -> {
                val replied = message?.let { gateway?.repliedMessage(it) }
                val target = replied?.senderUserId() ?: message?.senderUserId()
                val saved = if (target != null) "User ID: $target\nProfile: tg://user?id=$target" else "Current chat ID: ${context.message.chatId}"
                gateway?.sendToSavedMessages(saved) { result ->
                    context.reply(if (result is TdApi.Error) "Saved Messages failed: ${result.message}" else "Sent to Saved Messages.")
                }
            }
            "whois", "uinfo" -> {
                val message = context.message.tdMessage
                val replied = message?.let { gateway?.repliedMessage(it) }
                val targetId = context.arguments.firstOrNull()?.toLongOrNull() ?: replied?.senderUserId()
                if (targetId == null || targetId <= 0L) {
                    context.reply("Usage: .whois <user id> or reply to a user's message with .whois")
                    return
                }
                if (gateway == null) {
                    context.reply("Telegram session is unavailable.")
                    return
                }
                context.reply("Looking up user…")
                gateway.send(TdApi.GetUser(targetId)) { result ->
                    if (result !is TdApi.User) {
                        context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else "User not found.")
                        return@send
                    }
                    val user = result
                    val name = listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
                    val username = user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "—"
                    context.reply(buildString {
                        append("${name.ifBlank { "Unknown user" }}\n")
                        append("ID: ${user.id}\n")
                        append("Username: $username\n")
                        append("Phone: ${user.phoneNumber.ifBlank { "Hidden" }}\n")
                        append("Bot: ${if (user.type is TdApi.UserTypeBot) "Yes" else "No"}\n")
                        append("Premium: ${if (user.isPremium) "Yes" else "No"}")
                    })
                }
            }
        }
    }
}

class ChatInfoAutomationPlugin : AutomationPlugin {
    override val id = "userbot:info"
    override val commands = listOf(
        AutomationCommand("chatinfo", "Show the current chat's title, ID, type and unread count.", "chatinfo"),
        AutomationCommand("minfo", "Show metadata for a replied message.", "minfo (reply required)")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        if (context.command == "minfo") {
            val source = context.message.tdMessage
            val replied = source?.let(gateway::repliedMessage) ?: return context.reply("Reply to a message with .minfo")
            val sender = replied.senderUserId()?.toString() ?: "non-user sender"
            val content = replied.content.javaClass.simpleName.removePrefix("Message")
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(replied.date * 1000L))
            context.reply("Message ID: ${replied.id}\nChat ID: ${replied.chatId}\nSender: $sender\nType: $content\nDate: $date")
            return
        }
        val cached = gateway.cachedChat(context.message.chatId)
        if (cached != null) {
            context.reply(formatChat(cached))
            return
        }
        gateway.send(TdApi.GetChat(context.message.chatId)) { result ->
            if (result is TdApi.Chat) context.reply(formatChat(result))
            else context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else "Chat information unavailable.")
        }
    }

    private fun formatChat(chat: TdApi.Chat): String =
        "${chat.title}\nChat ID: ${chat.id}\nType: ${chat.type.javaClass.simpleName.removePrefix("ChatType")}\n" +
            "Unread messages: ${chat.unreadCount}\nUnread mentions: ${chat.unreadMentionCount}\n" +
            "Last message ID: ${chat.lastMessage?.id ?: "—"}"
}

class GroupInfoAutomationPlugin : AutomationPlugin {
    override val id = "userbot:group"
    override val commands = listOf(
        AutomationCommand("basicgroup", "Show whether the current chat is a basic group.", "basicgroup"),
        AutomationCommand("supergroup", "Show whether the current chat is a supergroup or megagroup.", "supergroup"),
        AutomationCommand("channel", "Show whether the current chat is a channel.", "channel"),
        AutomationCommand("unreadchat", "Show unread counters for the current chat.", "unreadchat")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val cached = gateway.cachedChat(context.message.chatId)
        if (cached != null) {
            context.reply(describe(context.command.orEmpty(), cached))
            return
        }
        gateway.send(TdApi.GetChat(context.message.chatId)) { result ->
            if (result is TdApi.Chat) context.reply(describe(context.command.orEmpty(), result))
            else context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else "Chat information unavailable.")
        }
    }

    private fun describe(command: String, chat: TdApi.Chat): String {
        val type = chat.type
        val basic = type is TdApi.ChatTypeBasicGroup
        val supergroup = type is TdApi.ChatTypeSupergroup
        val isChannel = (type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        val matches = when (command) {
            "basicgroup" -> basic
            "supergroup" -> supergroup && !isChannel
            "channel" -> isChannel
            else -> true
        }
        if (!matches) return "This chat is not a $command."
        if (command == "unreadchat") return "Unread messages: ${chat.unreadCount}\nUnread mentions: ${chat.unreadMentionCount}"
        val typeName = when {
            basic -> "Basic group"
            isChannel -> "Channel"
            supergroup -> "Supergroup"
            type is TdApi.ChatTypePrivate -> "Private chat"
            else -> "Other"
        }
        return "${chat.title}\nChat ID: ${chat.id}\nType: $typeName"
    }
}
