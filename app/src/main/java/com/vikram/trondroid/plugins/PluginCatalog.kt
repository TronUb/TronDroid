package com.vikram.trondroid.plugins

import android.content.Context

data class PluginCommand(val name: String, val description: String, val usage: String)
data class BotPlugin(val id: String, val title: String, val summary: String, val category: String, val commands: List<PluginCommand>)

object PluginCatalog {
    val all = listOf(
        BotPlugin("afk", "AFK", "Automatically reply to incoming private messages while you are away.",
            "Automation", listOf(
                PluginCommand("afk", "Enable away mode with an optional status message.", "afk [message]"),
                PluginCommand("back", "Disable away mode and clear your away status.", "back"),
                PluginCommand("afkstatus", "Show whether away mode is currently enabled.", "afkstatus")
            )),
        BotPlugin("admin", "Admin tools", "Moderate groups where your Telegram account has the required administrator permissions.",
            "Administration", listOf(
                PluginCommand("ban", "Ban the replied-to user or a specified user.", "ban [reply|@username|user_id]"),
                PluginCommand("unban", "Remove a ban from a specified user.", "unban @username|user_id"),
                PluginCommand("mute", "Restrict a member from sending messages.", "mute [duration]"),
                PluginCommand("unmute", "Restore a member's message permissions.", "unmute"),
                PluginCommand("kick", "Remove a member from the group without a permanent ban.", "kick [reply|@username]"),
                PluginCommand("purge", "Delete messages in the current chat from the replied message up to the command.", "purge [count]")
            )),
        BotPlugin("downloads", "Downloads", "Manage incoming media downloads and local download preferences.",
            "Media", listOf(
                PluginCommand("download", "Download the replied-to media to app storage.", "download"),
                PluginCommand("autodownload", "Toggle automatic downloads for supported media.", "autodownload on|off"),
                PluginCommand("downloadstatus", "Show active and queued downloads.", "downloadstatus")
            )),
        BotPlugin("welcome", "Welcome", "Send configurable welcome messages when new members join a group you manage.",
            "Groups", listOf(
                PluginCommand("setwelcome", "Set the group's welcome message.", "setwelcome <message>"),
                PluginCommand("delwelcome", "Remove the group's welcome message.", "delwelcome"),
                PluginCommand("testwelcome", "Preview the current welcome message.", "testwelcome")
            )),
        BotPlugin("filters", "Message filters", "Create keyword rules that trigger a configured response or action.",
            "Automation", listOf(
                PluginCommand("filter", "Add a keyword response rule.", "filter <keyword> <response>"),
                PluginCommand("stop", "Remove a keyword filter.", "stop <keyword>"),
                PluginCommand("filters", "List configured keyword filters.", "filters")
            )),
        BotPlugin("scheduler", "Scheduler", "Schedule a message or reminder for a chat you control.",
            "Automation", listOf(
                PluginCommand("schedule", "Schedule a message using a delay such as 30m or 2h.", "schedule <delay> <message>"),
                PluginCommand("scheduled", "List pending scheduled messages.", "scheduled"),
                PluginCommand("unschedule", "Cancel a scheduled item by its ID.", "unschedule <id>")
            ))
    )
}

class PluginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.plugins", Context.MODE_PRIVATE)
    fun enabled(id: String) = prefs.getBoolean(id, false)
    fun setEnabled(id: String, value: Boolean) { prefs.edit().putBoolean(id, value).apply() }
    fun handler() = prefs.getString("command_handler", ".") ?: "."
    fun setHandler(value: String) { prefs.edit().putString("command_handler", value).apply() }
}
