package com.vikram.trondroid.automation

import android.content.SharedPreferences

class SudoAutomationPlugin(
    private val preferences: SharedPreferences,
    private val ownerId: () -> Long?
) : AutomationPlugin {
    override val id = "userbot:sudo"
    override val commands = listOf(
        AutomationCommand("addsudo", "Authorize a user ID or replied sender to run userbot commands.", "addsudo <user id> (or reply)"),
        AutomationCommand("listsudo", "List authorized sudo users.", "listsudo"),
        AutomationCommand("delsudo", "Remove a user's sudo access.", "delsudo <user id> (or reply)")
    )

    private fun ids(): Set<Long> = preferences.getStringSet("sudo_ids", emptySet())
        .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    override fun canExecuteIncoming(message: AutomationMessage): Boolean {
        if (message.isOutgoing || message.chatId <= 0L) return false
        val sender = message.senderId?.toLongOrNull() ?: return false
        if (sender == ownerId()) return false
        val first = message.text.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
        if (first in setOf(".addsudo", ".delsudo", ".listsudo")) return false
        return sender in ids()
    }

    override fun onCommand(context: AutomationContext) {
        val owner = ownerId() ?: return context.reply("Owner account ID is unavailable.")
        if (!context.message.isOutgoing) return context.reply("Sudo management is owner-only.")
        val repliedId = context.message.tdMessage?.let { message ->
            context.telegram?.repliedMessage(message)?.senderId as? org.drinkless.tdlib.TdApi.MessageSenderUser
        }?.userId
        val target = context.arguments.firstOrNull()?.toLongOrNull() ?: repliedId
        when (context.command) {
            "listsudo" -> {
                val users = ids()
                context.reply(if (users.isEmpty()) "No sudo users configured." else
                    "Sudo users:\n" + users.sorted().joinToString("\n"))
            }
            "addsudo" -> {
                if (target == null || target <= 0L) return context.reply("Usage: .addsudo <user id> or reply to a user's message")
                if (target == owner) return context.reply("The owner already has full access.")
                val updated = ids() + target
                preferences.edit().putStringSet("sudo_ids", updated.map(Long::toString).toSet()).apply()
                context.reply("Sudo access granted to $target.")
            }
            "delsudo" -> {
                if (target == null || target <= 0L) return context.reply("Usage: .delsudo <user id> or reply to a user's message")
                val previous = ids()
                if (target !in previous) return context.reply("That user was not a sudo user.")
                val updated = previous - target
                preferences.edit().putStringSet("sudo_ids", updated.map(Long::toString).toSet()).apply()
                context.reply("Sudo access removed from $target.")
            }
        }
    }
}
