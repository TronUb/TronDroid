package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class ZombieCleanupAutomationPlugin : AutomationPlugin {
    override val id = "userbot:zombies"
    override val commands = listOf(
        AutomationCommand("zombies", "Find deleted accounts in the first 100 recent members; use confirm to remove them.", "zombies [confirm]")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        gateway.send(TdApi.GetChat(context.message.chatId)) { chatResult ->
            val chat = chatResult as? TdApi.Chat
                ?: return@send context.reply(if (chatResult is TdApi.Error) chatResult.message else "Chat unavailable.")
            val supergroup = chat.type as? TdApi.ChatTypeSupergroup
                ?: return@send context.reply("Zombie scan supports supergroups and channels only.")
            gateway.send(TdApi.GetSupergroupMembers(supergroup.supergroupId, TdApi.SupergroupMembersFilterRecent(), 0, 100)) { result ->
                if (result is TdApi.Error) return@send context.reply("Telegram error: ${result.message}")
                val ids = (result as? TdApi.ChatMembers)?.members.orEmpty().mapNotNull { member ->
                    val id = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
                    id.takeIf { gateway.cachedUser(it)?.type is TdApi.UserTypeDeleted }
                }.distinct()
                if (!context.arguments.firstOrNull().equals("confirm", true)) {
                    context.reply("Deleted accounts found in cached user data: ${ids.size}\n" +
                        ids.joinToString("\n").ifBlank { "None found. Uncached users cannot be classified." } +
                        "\n\nRun .zombies confirm to ban these accounts.")
                    return@send
                }
                if (ids.isEmpty()) return@send context.reply("No cached deleted accounts found.")
                var done = 0
                var failed = 0
                ids.forEach { id ->
                    gateway.send(TdApi.SetChatMemberStatus(context.message.chatId, TdApi.MessageSenderUser(id), TdApi.ChatMemberStatusBanned(0))) { ban ->
                        if (ban is TdApi.Error) failed++ else done++
                        if (done + failed == ids.size) context.reply("Cleanup complete. Removed: $done, failed: $failed.")
                    }
                }
            }
        }
    }
}
