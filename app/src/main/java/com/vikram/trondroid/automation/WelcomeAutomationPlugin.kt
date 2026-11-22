package com.vikram.trondroid.automation

import android.content.SharedPreferences
import org.drinkless.tdlib.TdApi

class WelcomeAutomationPlugin(private val prefs: SharedPreferences) : AutomationPlugin {
    override val id = "userbot:welcome"
    override val commands = listOf(
        AutomationCommand("welcome", "Show whether welcome messages are configured in this chat.", "welcome"),
        AutomationCommand("setwelcome", "Set a text welcome message for new group members.", "setwelcome <text>"),
        AutomationCommand("delwelcome", "Remove this chat's welcome message.", "delwelcome"),
        AutomationCommand("getwelcome", "Show this chat's configured welcome message.", "getwelcome")
    )

    override fun onCommand(context: AutomationContext) {
        val key = "welcome:${context.message.chatId}"
        when (context.command) {
            "setwelcome" -> {
                val text = context.arguments.joinToString(" ").trim()
                if (text.isBlank()) return context.reply("Usage: .setwelcome <text>")
                prefs.edit().putString(key, text.take(1000)).apply()
                context.reply("Welcome message saved for this chat.")
            }
            "delwelcome" -> {
                prefs.edit().remove(key).apply()
                context.reply("Welcome message removed.")
            }
            "getwelcome", "welcome" -> {
                val value = prefs.getString(key, null)
                context.reply(value?.let { "Configured welcome message:\n$it" } ?: "No welcome message is configured for this chat.")
            }
        }
    }

    override fun onTelegramUpdate(context: AutomationUpdateContext) {
        if (!context.isCommandEnabled("userbot:welcome", "setwelcome")) return
        val update = context.update as? TdApi.UpdateNewMessage ?: return
        val message = update.message
        val added = message.content as? TdApi.MessageChatAddMembers ?: return
        if (added.memberUserIds.size == 0) return
        val template = prefs.getString("welcome:${message.chatId}", null) ?: return
        val chat = context.telegram?.cachedChat(message.chatId)
        val names = added.memberUserIds.toList().mapNotNull { id ->
            context.telegram?.cachedUser(id)?.let { user ->
                listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
            }
        }
        val memberText = names.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "new member"
        val rendered = template.replace("{user}", memberText).replace("{chat}", chat?.title ?: "this group")
        context.reply(message.chatId, rendered.take(4000))
    }
}
