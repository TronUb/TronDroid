package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class GroupUtilitiesAutomationPlugin : AutomationPlugin {
    override val id = "userbot:utils"
    override val commands = listOf(
        AutomationCommand("admins", "List chat administrators.", "admins"),
        AutomationCommand("members", "List recently active members of a supergroup (up to 100).", "members"),
        AutomationCommand("bots", "List bots in a supergroup (up to 100).", "bots"),
        AutomationCommand("settitle", "Change the current group title.", "settitle <new title>"),
        AutomationCommand("invite", "Invite a user by numeric Telegram ID.", "invite <user id>"),
        AutomationCommand("kickme", "Leave the current group.", "kickme")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "admins" -> gateway.send(TdApi.GetChatAdministrators(context.message.chatId)) { result ->
                if (result is TdApi.Error) return@send context.reply("Telegram error: ${result.message}")
                val admins = (result as? TdApi.ChatAdministrators)?.administrators.orEmpty()
                context.reply(if (admins.isEmpty()) "No administrators returned." else admins.joinToString("\n") {
                    val user = gateway.cachedUser(it.userId)
                    val name = listOfNotNull(user?.firstName, user?.lastName).filter { n -> n.isNotBlank() }.joinToString(" ")
                    "${name.ifBlank { it.userId.toString() }} — ${if (it.isOwner) "Owner" else "Admin"}${it.customTitle.takeIf(String::isNotBlank)?.let { title -> " ($title)" }.orEmpty()}"
                }.take(3800))
            }
            "members", "bots" -> gateway.send(TdApi.GetChat(context.message.chatId)) { chatResult ->
                val chat = chatResult as? TdApi.Chat
                    ?: return@send context.reply(if (chatResult is TdApi.Error) "Telegram error: ${chatResult.message}" else "Chat unavailable.")
                val supergroup = chat.type as? TdApi.ChatTypeSupergroup
                    ?: return@send context.reply("This command currently supports supergroups and channels only.")
                val filter: TdApi.SupergroupMembersFilter = if (context.command == "bots") TdApi.SupergroupMembersFilterBots() else TdApi.SupergroupMembersFilterRecent()
                gateway.send(TdApi.GetSupergroupMembers(supergroup.supergroupId, filter, 0, 100)) { membersResult ->
                    if (membersResult is TdApi.Error) return@send context.reply("Telegram error: ${membersResult.message}")
                    val members = (membersResult as? TdApi.ChatMembers)?.members.orEmpty()
                    val lines = members.mapNotNull { member ->
                        val id = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
                        val user = gateway.cachedUser(id)
                        val name = listOfNotNull(user?.firstName, user?.lastName).filter(String::isNotBlank).joinToString(" ")
                        "${name.ifBlank { "User" }} — $id"
                    }
                    context.reply("${context.command} (${lines.size}/100):\n" + (lines.joinToString("\n").ifBlank { "No results." }).take(3800))
                }
            }
            "settitle" -> {
                val title = context.arguments.joinToString(" ").trim()
                if (title.isBlank()) return context.reply("Usage: .settitle <new title>")
                gateway.send(TdApi.SetChatTitle(context.message.chatId, title.take(128))) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else "Chat title updated.")
                }
            }
            "invite" -> {
                val userId = context.arguments.firstOrNull()?.toLongOrNull()
                    ?: return context.reply("Usage: .invite <numeric user id>")
                if (userId <= 0L) return context.reply("Invalid user ID.")
                gateway.send(TdApi.AddChatMember(context.message.chatId, userId, 0)) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else "Invite request processed. Telegram may report that the user could not be added.")
                }
            }
            "kickme" -> {
                val senderId = context.message.senderId?.toLongOrNull()
                    ?: return context.reply("Unable to determine the connected account ID.")
                gateway.send(TdApi.SetChatMemberStatus(context.message.chatId, TdApi.MessageSenderUser(senderId), TdApi.ChatMemberStatusLeft())) { result ->
                    context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}" else "You left the chat.")
                }
            }
        }
    }
}
