package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class AdminAutomationPlugin : AutomationPlugin {
    override val id = "userbot:admin"
    override val commands = listOf(
        AutomationCommand("ban", "Ban a replied user or numeric Telegram user ID from this chat.", "ban <user id> (or reply)"),
        AutomationCommand("unban", "Unban a numeric Telegram user ID in this chat.", "unban <user id>"),
        AutomationCommand("kick", "Remove a replied user or numeric Telegram user ID from this chat.", "kick <user id> (or reply)"),
        AutomationCommand("mute", "Restrict a replied user or numeric user ID for a duration (default 1 hour).", "mute <user id> [minutes] (or reply)"),
        AutomationCommand("unmute", "Restore a restricted user permissions.", "unmute <user id> (or reply)"),
        AutomationCommand("promote", "Grant a member limited administrator rights (invite users).", "promote <user id> (or reply)"),
        AutomationCommand("demote", "Remove administrator rights from a member.", "demote <user id> (or reply)"),
        AutomationCommand("pin", "Pin a replied message.", "pin (reply required)"),
        AutomationCommand("unpin", "Unpin a replied message, or unpin all messages with no reply.", "unpin (reply optional)")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val source = context.message.tdMessage
        val replied = source?.let(gateway::repliedMessage)
        val targetId = context.arguments.firstOrNull()?.toLongOrNull()
            ?: (replied?.senderId as? TdApi.MessageSenderUser)?.userId
        when (context.command) {
            "pin", "unpin" -> {
                if (context.command == "pin" && replied == null) return context.reply("Reply to a message with .pin")
                val request: TdApi.Function<*> = if (context.command == "pin") {
                    TdApi.PinChatMessage(context.message.chatId, replied!!.id, false, false)
                } else if (replied != null) {
                    TdApi.UnpinChatMessage(context.message.chatId, replied.id)
                } else {
                    TdApi.UnpinAllChatMessages(context.message.chatId)
                }
                gateway.send(request) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else if (context.command == "pin") "Message pinned." else "Message unpinned.")
                }
            }
            "mute", "unmute" -> {
                if (targetId == null || targetId <= 0L) return context.reply("Usage: .${context.command} <user id> or reply to a user message")
                val status: TdApi.ChatMemberStatus = if (context.command == "unmute") {
                    TdApi.ChatMemberStatusMember()
                } else {
                    val minutes = context.arguments.drop(1).firstOrNull()?.toIntOrNull() ?: 60
                    if (minutes !in 1..525600) return context.reply("Mute duration must be between 1 minute and 365 days.")
                    val permissions = TdApi.ChatPermissions(false, false, false, false, false, false, false, false, false, false, false, false, false, false)
                    TdApi.ChatMemberStatusRestricted(true, (System.currentTimeMillis() / 1000L + minutes * 60L).toInt(), permissions)
                }
                gateway.send(TdApi.SetChatMemberStatus(context.message.chatId, TdApi.MessageSenderUser(targetId), status)) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else if (context.command == "mute") "User muted." else "User unmuted.")
                }
            }
            "promote", "demote" -> {
                if (targetId == null || targetId <= 0L) return context.reply("Usage: .${context.command} <user id> or reply to a user message")
                val status: TdApi.ChatMemberStatus = if (context.command == "demote") {
                    TdApi.ChatMemberStatusMember()
                } else {
                    val rights = TdApi.ChatAdministratorRights().apply { canInviteUsers = true }
                    TdApi.ChatMemberStatusAdministrator("", false, rights)
                }
                gateway.send(TdApi.SetChatMemberStatus(context.message.chatId, TdApi.MessageSenderUser(targetId), status)) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else if (context.command == "promote") "User promoted with invite-only administrator rights." else "User demoted.")
                }
            }
            "ban", "kick", "unban" -> {
                if (targetId == null || targetId <= 0L) return context.reply("Usage: .${context.command} <user id> or reply to a user's message")
                val sender = TdApi.MessageSenderUser(targetId)
                val request: TdApi.Function<*> = when (context.command) {
                    "unban" -> TdApi.SetChatMemberStatus(context.message.chatId, sender, TdApi.ChatMemberStatusLeft())
                    else -> TdApi.SetChatMemberStatus(context.message.chatId, sender, TdApi.ChatMemberStatusBanned(0))
                }
                gateway.send(request) { result ->
                    if (result is TdApi.Error) {
                        context.reply("Telegram error: ${result.message}")
                    } else if (context.command == "kick") {
                        gateway.send(TdApi.SetChatMemberStatus(context.message.chatId, sender, TdApi.ChatMemberStatusLeft())) { unban ->
                            context.reply(if (unban is TdApi.Error) "Removed, but unban failed: ${unban.message}" else "User kicked.")
                        }
                    } else {
                        context.reply(if (context.command == "ban") "User banned." else "User unbanned.")
                    }
                }
            }
        }
    }
}

class BroadcastAutomationPlugin : AutomationPlugin {
    override val id = "userbot:broadcast"
    override val commands = listOf(
        AutomationCommand("broadcast", "Broadcast text to chats currently loaded in this Telegram session.", "broadcast <text>")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val text = context.arguments.joinToString(" ").trim()
        if (text.isBlank()) return context.reply("Usage: .broadcast <message>")
        val chats = gateway.updates.chats.value.values.toList()
        if (chats.isEmpty()) return context.reply("No chats are loaded yet. Open your Telegram dialogs and retry.")
        context.reply("Broadcast started for ${chats.size} loaded chats.")
        var sent = 0
        var failed = 0
        chats.forEach { chat ->
            when (chat.type) {
                is TdApi.ChatTypePrivate, is TdApi.ChatTypeBasicGroup, is TdApi.ChatTypeSupergroup -> {
                    val input = TdApi.InputMessageText(TdApi.FormattedText(text.take(4000), emptyArray()), null, true)
                    gateway.send(TdApi.SendMessage(chat.id, null, null, null, null, input)) { result ->
                        if (result is TdApi.Error) failed++ else sent++
                        if (sent + failed == chats.size) context.reply("Broadcast finished. Sent: $sent, failed: $failed.")
                    }
                }
            }
        }
    }
}
