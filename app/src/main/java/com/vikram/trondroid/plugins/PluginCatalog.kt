package com.vikram.trondroid.plugins

import android.content.Context

data class BotPlugin(val id: String, val title: String, val summary: String, val category: String)
object PluginCatalog {
    val all = listOf(
        BotPlugin("afk", "AFK auto-reply", "Reply to incoming private messages while away.", "Automation"),
        BotPlugin("downloads", "Auto downloads", "Queue supported incoming media for download.", "Media"),
        BotPlugin("admin", "Group admin tools", "Moderation tools for groups where your account has permission.", "Administration"),
        BotPlugin("welcome", "Welcome messages", "Welcome new members in groups you manage.", "Groups"),
        BotPlugin("filters", "Message filters", "Match configured keywords and apply local actions.", "Automation"),
        BotPlugin("scheduler", "Message scheduler", "Schedule reminders and messages to chats you control.", "Automation")
    )
}
class PluginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.plugins", Context.MODE_PRIVATE)
    fun enabled(id: String) = prefs.getBoolean(id, false)
    fun setEnabled(id: String, value: Boolean) { prefs.edit().putBoolean(id, value).apply() }
}
