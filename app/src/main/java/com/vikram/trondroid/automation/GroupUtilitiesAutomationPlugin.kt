package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class GroupUtilitiesAutomationPlugin : AutomationPlugin {
    override val id = "userbot:utils"
    override val commands = listOf(
        AutomationCommand("admins", "List administrators in the current group.", "admins"),
        AutomationCommand("adminlist", "Alias for admins.", "adminlist"),
        AutomationCommand("members", "List recently active members of a supergroup (1–100).", "members [1-100]"),
        AutomationCommand("bots", "List bots in a supergroup (1–100).", "bots [1-100]"),
        AutomationCommand("gtitle", "Change the current group title (Telegram admin rights required). Custom administrator titles are not exposed by TDLib for this user session.", "gtitle <new group title>"),
        AutomationCommand("invite", "Invite one user by replying to them or supplying their numeric ID.", "invite <user id> (or reply)"),
        AutomationCommand("join", "Join a public username or invite link.", "join <@username or t.me invite link>"),
        AutomationCommand("kickme", "Leave the current group. This action is immediate.", "kickme"),
        AutomationCommand("slowmo", "Set supergroup slow mode to a Telegram-supported interval, or 0 to disable.", "slowmo <0|5|10|30|60|300|900|3600>")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "admins", "adminlist" -> listAdmins(context, gateway)
            "members", "bots" -> listMembers(context, gateway)
            "gtitle" -> {
                val title = context.arguments.joinToString(" ").trim()
                if (title.isBlank()) return context.reply("Usage: .gtitle <new group title>")
                if (title.length > 128) return context.reply("Group title must be 128 characters or fewer.")
                gateway.send(TdApi.SetChatTitle(context.message.chatId, title)) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram rejected the title change: ${result.message}" else "Group title updated.")
                }
            }
            "invite" -> invite(context, gateway)
            "join" -> join(context, gateway)
            "slowmo" -> {
                val delay = context.arguments.firstOrNull()?.toIntOrNull()
                    ?: return context.reply("Usage: .slowmo <0|5|10|30|60|300|900|3600>")
                if (delay !in setOf(0, 5, 10, 30, 60, 300, 900, 3600)) {
                    return context.reply("Slow mode must be 0, 5, 10, 30, 60, 300, 900 or 3600 seconds.")
                }
                gateway.send(TdApi.GetChat(context.message.chatId)) { chatResult ->
                    val chat = chatResult as? TdApi.Chat
                        ?: return@send context.reply(if (chatResult is TdApi.Error) chatResult.message else "Chat unavailable.")
                    val type = chat.type as? TdApi.ChatTypeSupergroup
                        ?: return@send context.reply("Slow mode is available in supergroups only.")
                    if (type.isChannel) return@send context.reply("Slow mode is not supported in broadcast channels.")
                    gateway.send(TdApi.SetChatSlowModeDelay(context.message.chatId, delay)) { result ->
                        context.reply(if (result is TdApi.Error) "Slow mode update failed: ${result.message}"
                            else if (delay == 0) "Slow mode disabled." else "Slow mode set to ${delay}s.")
                    }
                }
            }
            "kickme" -> {
                if (gateway.currentUserId() == null) return context.reply("Unable to determine the connected Telegram account.")
                context.reply("Leaving this chat…")
                gateway.send(TdApi.LeaveChat(context.message.chatId)) { result ->
                    context.reply(if (result is TdApi.Error) "Could not leave chat: ${result.message}" else "Left the chat.")
                }
            }
        }
    }

    private fun listAdmins(context: AutomationContext, gateway: com.vikram.trondroid.telegram.TelegramClientGateway) {
        gateway.send(TdApi.GetChatAdministrators(context.message.chatId)) { result ->
            if (result is TdApi.Error) return@send context.reply("Telegram error: ${result.message}")
            val admins = (result as? TdApi.ChatAdministrators)?.administrators.orEmpty()
            val lines = admins.map { member ->
                val user = gateway.cachedUser(member.userId)
                val name = listOfNotNull(user?.firstName, user?.lastName).filter(String::isNotBlank).joinToString(" ")
                val role = if (member.isOwner) "Owner" else "Admin"
                "$role — ${name.ifBlank { member.userId.toString() }} (${member.userId})" +
                    member.customTitle.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            }
            context.reply(if (lines.isEmpty()) "No administrators returned." else "Chat administrators:\\n" + lines.joinToString("\\n").take(3800))
        }
    }

    private fun listMembers(context: AutomationContext, gateway: com.vikram.trondroid.telegram.TelegramClientGateway) {
        val requested = context.arguments.firstOrNull()?.toIntOrNull() ?: 50
        if (requested !in 1..100) return context.reply("Usage: .${context.command} [1-100]")
        gateway.send(TdApi.GetChat(context.message.chatId)) { chatResult ->
            val chat = chatResult as? TdApi.Chat
                ?: return@send context.reply(if (chatResult is TdApi.Error) chatResult.message else "Chat unavailable.")
            val supergroup = chat.type as? TdApi.ChatTypeSupergroup
                ?: return@send context.reply("This command supports supergroups only.")
            if (supergroup.isChannel) return@send context.reply("Member listing is supported for groups, not broadcast channels.")
            val filter: TdApi.SupergroupMembersFilter =
                if (context.command == "bots") TdApi.SupergroupMembersFilterBots() else TdApi.SupergroupMembersFilterRecent()
            gateway.send(TdApi.GetSupergroupMembers(supergroup.supergroupId, filter, 0, requested)) { result ->
                if (result is TdApi.Error) return@send context.reply("Telegram error: ${result.message}")
                val members = (result as? TdApi.ChatMembers)?.members.orEmpty()
                val lines = members.mapNotNull { member ->
                    val userId = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
                    val user = gateway.cachedUser(userId)
                    val name = listOfNotNull(user?.firstName, user?.lastName).filter(String::isNotBlank).joinToString(" ")
                    "${name.ifBlank { "User" }} — $userId"
                }
                val label = if (context.command == "bots") "Bots" else "Recent members"
                context.reply("$label (${lines.size}/$requested):\\n" + lines.joinToString("\\n").ifBlank { "No results." }.take(3800))
            }
        }
    }

    private fun invite(context: AutomationContext, gateway: com.vikram.trondroid.telegram.TelegramClientGateway) {
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
        val token = context.arguments.firstOrNull()?.trim().orEmpty()
        val repliedId = (replied?.senderId as? TdApi.MessageSenderUser)?.userId
        val numericId = token.toLongOrNull()
        val addUser: (Long) -> Unit = { userId ->
            if (userId <= 0L) {
                context.reply("Invalid Telegram user ID.")
            } else {
                gateway.send(TdApi.GetChat(context.message.chatId)) { chatResult ->
                    val chat = chatResult as? TdApi.Chat
                        ?: return@send context.reply(if (chatResult is TdApi.Error) chatResult.message else "Chat unavailable.")
                    if (chat.type !is TdApi.ChatTypeSupergroup && chat.type !is TdApi.ChatTypeBasicGroup) {
                        return@send context.reply("Inviting members is only supported in groups and supergroups.")
                    }
                    if ((chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) {
                        return@send context.reply("Use a group or supergroup, not a broadcast channel.")
                    }
                    gateway.send(TdApi.AddChatMember(context.message.chatId, userId, 0)) { result ->
                        context.reply(if (result is TdApi.Error) "Invite failed: ${result.message}" else "Telegram processed the invite request for $userId.")
                    }
                }
            }
        }
        when {
            repliedId != null -> addUser(repliedId)
            numericId != null -> addUser(numericId)
            token.isBlank() -> context.reply("Usage: .invite <user id or @username> or reply to a user.")
            else -> {
                val username = token.removePrefix("@")
                if (!username.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,31}"))) {
                    context.reply("Provide a valid numeric user ID or Telegram username.")
                } else {
                    gateway.send(TdApi.SearchPublicChat(username)) { result ->
                        val target = result as? TdApi.Chat
                        val privateChat = target?.type as? TdApi.ChatTypePrivate
                        if (privateChat == null) context.reply(if (result is TdApi.Error) "User lookup failed: ${result.message}" else "That username does not resolve to a user account.")
                        else addUser(privateChat.userId)
                    }
                }
            }
        }
    }

    private fun join(context: AutomationContext, gateway: com.vikram.trondroid.telegram.TelegramClientGateway) {
        val target = context.arguments.firstOrNull()?.trim()
            ?: return context.reply("Usage: .join <@username or t.me invite link>")
        if (target.length > 256 || target.any(Char::isWhitespace)) return context.reply("Provide one public username or invite link.")
        if (target.startsWith("https://t.me/+", true) || target.startsWith("https://t.me/joinchat/", true) ||
            target.startsWith("http://t.me/+", true) || target.startsWith("http://t.me/joinchat/", true)) {
            gateway.send(TdApi.JoinChatByInviteLink(target)) { result ->
                val chat = result as? TdApi.Chat
                context.reply(if (chat != null) "Joined ${chat.title}." else if (result is TdApi.Error) "Join failed: ${result.message}" else "Telegram did not confirm the join.")
            }
            return
        }
        val username = target.removePrefix("@").removePrefix("https://t.me/").removePrefix("http://t.me/").substringBefore('/')
        if (!username.matches(Regex("[A-Za-z0-9_]{5,32}"))) return context.reply("Invalid public Telegram username or invite link.")
        gateway.send(TdApi.SearchPublicChat(username)) { result ->
            val chat = result as? TdApi.Chat
                ?: return@send context.reply(if (result is TdApi.Error) "Chat lookup failed: ${result.message}" else "Public chat not found.")
            gateway.send(TdApi.JoinChat(chat.id)) { joined ->
                context.reply(if (joined is TdApi.Error) "Join failed: ${joined.message}" else "Joined ${chat.title}.")
            }
        }
    }
}
