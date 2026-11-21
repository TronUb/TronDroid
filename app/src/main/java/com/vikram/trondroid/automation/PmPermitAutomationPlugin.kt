package com.vikram.trondroid.automation

import android.content.SharedPreferences
import org.drinkless.tdlib.TdApi

class PmPermitAutomationPlugin(private val prefs: SharedPreferences) : AutomationPlugin {
    override val id = "userbot:pmpermit"
    override val commands = listOf(
        AutomationCommand("pmpermit", "Enable or disable automatic private-message warnings and blocking.", "pmpermit <on|off>"),
        AutomationCommand("approve", "Allow a private user to message you without PM permit warnings.", "approve (reply required)"),
        AutomationCommand("disapprove", "Remove a user from the PM permit allow-list.", "disapprove (reply required)")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "pmpermit" -> {
                when (context.arguments.firstOrNull()?.lowercase()) {
                    "on" -> { prefs.edit().putBoolean("enabled", true).apply(); context.reply("PM permit enabled.") }
                    "off" -> { prefs.edit().putBoolean("enabled", false).apply(); context.reply("PM permit disabled.") }
                    else -> context.reply("Usage: .pmpermit <on|off>")
                }
            }
            "approve", "disapprove" -> {
                val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a private user's message with .${context.command}")
                val userId = (replied.senderId as? TdApi.MessageSenderUser)?.userId
                    ?: return context.reply("The replied message isn't from a Telegram user.")
                val key = "allowed:$userId"
                if (context.command == "approve") {
                    prefs.edit().putBoolean(key, true).remove("warns:$userId").apply()
                    context.reply("User approved for private messages.")
                } else {
                    prefs.edit().remove(key).remove("warns:$userId").apply()
                    context.reply("User removed from the PM permit allow-list.")
                }
            }
        }
    }

    override fun onMessage(context: AutomationContext) {
        if (context.message.isOutgoing || !prefs.getBoolean("enabled", false)) return
        val gateway = context.telegram ?: return
        val chat = gateway.cachedChat(context.message.chatId) ?: return
        if (chat.type !is TdApi.ChatTypePrivate) return
        val userId = (context.message.tdMessage?.senderId as? TdApi.MessageSenderUser)?.userId ?: return
        if (prefs.getBoolean("allowed:$userId", false)) return
        val count = prefs.getInt("warns:$userId", 0) + 1
        val limit = prefs.getInt("warning_limit", 3).coerceIn(1, 10)
        if (count < limit) {
            prefs.edit().putInt("warns:$userId", count).apply()
            context.reply("PM permit is enabled. Please get approval before sending more messages ($count/$limit).")
        } else {
            gateway.send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), TdApi.BlockListMain())) { result ->
                if (result is TdApi.Error) context.reply("PM permit reached its limit, but Telegram could not block this user: ${result.message}")
                else context.reply("PM permit limit reached. This user has been blocked.")
            }
        }
    }
}
