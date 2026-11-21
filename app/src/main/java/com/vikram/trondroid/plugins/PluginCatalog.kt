package com.vikram.trondroid.plugins

import android.content.Context

data class PluginCommand(val name: String, val description: String, val usage: String)

enum class PluginOwner(val label: String) {
    USERBOT("Userbot"),
    BOT("Bot")
}

data class BotPlugin(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val owner: PluginOwner,
    val sourcePath: String,
    val commands: List<PluginCommand> = emptyList(),
    val requiresReview: Boolean = false
)

object PluginCatalog {
    private fun userbot(id: String, title: String, summary: String, category: String, review: Boolean = false) =
        BotPlugin("userbot:" + id, title, summary, category, PluginOwner.USERBOT,
            "main/userbot/modules/plugins/" + id + ".py", requiresReview = review)

    private fun bot(id: String, title: String, summary: String, category: String, review: Boolean = false) =
        BotPlugin("bot:" + id, title, summary, category, PluginOwner.BOT,
            "main/assistant/modules/plugins/" + id + ".py", requiresReview = review)

    val userbot = listOf(
        userbot("admin", "Admin tools", "Group administration and moderation commands.", "Administration", true),
        userbot("afk", "AFK", "Away status and automatic replies to incoming messages.", "Automation"),
        userbot("alive", "Alive", "Account and userbot runtime status information.", "Status"),
        userbot("anime", "Anime", "Anime-related lookup and entertainment commands.", "Entertainment"),
        userbot("broadcast", "Broadcast", "Send a message to selected chats.", "Messaging", true),
        userbot("carbon", "Carbon", "Create shareable code images.", "Media"),
        userbot("clone", "Clone", "Clone profile information and related account presentation.", "Account", true),
        userbot("dev", "Developer", "Developer utilities and diagnostic commands.", "Developer"),
        userbot("download", "Downloads", "Download supported media from Telegram and external sources.", "Media"),
        userbot("dv", "Digital utilities", "Miscellaneous utility and conversion commands.", "Utilities"),
        userbot("filetools", "File tools", "Inspect and manage supported files and documents.", "Utilities"),
        userbot("fun", "Fun", "Fun and entertainment commands.", "Entertainment"),
        userbot("google", "Google", "Search the web using Google-related commands.", "Search"),
        userbot("group", "Group tools", "Group information and member-management utilities.", "Groups"),
        userbot("help", "Help", "Show available commands and usage information.", "Utilities"),
        userbot("heroku", "Heroku", "Heroku deployment and application-management utilities.", "Hosting"),
        userbot("image", "Image tools", "Image processing and image-related commands.", "Media"),
        userbot("imdb", "IMDb", "Look up movie and television information.", "Search"),
        userbot("info", "Account info", "Retrieve Telegram account and chat information.", "Account"),
        userbot("keyboard", "Keyboard", "Create and manage Telegram inline keyboard layouts.", "Utilities"),
        userbot("notes", "Notes", "Save, retrieve and manage reusable notes.", "Productivity"),
        userbot("pastebin", "Pastebin", "Publish and retrieve text snippets.", "Developer"),
        userbot("ping", "Ping", "Check userbot responsiveness and latency.", "Diagnostics"),
        userbot("pmpermit", "PM Permit", "Control how incoming private messages are handled.", "Privacy"),
        userbot("power", "Power", "Account and chat power utilities.", "Utilities"),
        userbot("profile", "Profile", "View and manage profile-related information.", "Account"),
        userbot("purge", "Purge", "Bulk-delete messages in a chat.", "Moderation", true),
        userbot("quotly", "Quotly", "Create quote-style images from Telegram messages.", "Media"),
        userbot("reddit", "Reddit", "Search and retrieve Reddit content.", "Search"),
        userbot("share", "Share", "Share text and supported content through Telegram.", "Utilities"),
        userbot("song", "Song", "Find music and retrieve song information.", "Media"),
        userbot("spam", "Spam", "Automated repeated messaging utilities.", "Messaging", true),
        userbot("spotify", "Spotify", "Look up Spotify tracks and music information.", "Media"),
        userbot("stats", "Statistics", "Show account and usage statistics.", "Analytics"),
        userbot("stickers", "Stickers", "Create, edit and manage Telegram stickers.", "Media"),
        userbot("sudo", "Sudo users", "Manage privileged users and access controls.", "Administration", true),
        userbot("supertools", "Super tools", "A collection of advanced utility commands.", "Utilities"),
        userbot("telegraph", "Telegraph", "Publish content to Telegraph pages.", "Publishing"),
        userbot("time", "Time", "Time, date and timezone utilities.", "Utilities"),
        userbot("tools", "Tools", "General-purpose Telegram helper commands.", "Utilities"),
        userbot("translate", "Translate", "Translate text between supported languages.", "Utilities"),
        userbot("update", "Update", "Check for and manage userbot updates.", "Maintenance"),
        userbot("utils", "Utilities", "General helper functions and utility commands.", "Utilities"),
        userbot("utube", "YouTube", "Search and download supported YouTube media.", "Media"),
        userbot("videotools", "Video tools", "Process and convert supported video files.", "Media"),
        userbot("welcome", "Welcome", "Configure welcome messages for group members.", "Groups"),
        userbot("wikipedia", "Wikipedia", "Search Wikipedia and retrieve article summaries.", "Search"),
        userbot("zombies", "Zombie cleanup", "Find and remove inactive or deleted group members.", "Moderation", true)
    )

    val bot = listOf(
        bot("botfun", "Bot fun", "Entertainment commands for the Telegram bot account.", "Entertainment"),
        bot("broadcast", "Broadcast", "Send bot messages to configured destinations.", "Messaging", true),
        bot("eval", "Evaluate", "Developer evaluation tools for bot maintenance.", "Developer", true),
        bot("help", "Help", "Show bot commands and usage information.", "Utilities"),
        bot("ping", "Ping", "Check Telegram Bot API responsiveness.", "Diagnostics"),
        bot("start", "Start", "Handle the bot start command and initial interaction.", "Onboarding"),
        bot("utils", "Utilities", "Shared helper commands for the bot account.", "Utilities")
    )

    val all: List<BotPlugin> = userbot + bot

    fun find(id: String): BotPlugin? = all.firstOrNull { it.id == id }
}

class PluginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.plugins", Context.MODE_PRIVATE)
    fun enabled(id: String) = prefs.getBoolean(id, false)
    fun setEnabled(id: String, value: Boolean) { prefs.edit().putBoolean(id, value).apply() }
    fun handler() = prefs.getString("command_handler", ".") ?: "."
    fun setHandler(value: String) { prefs.edit().putString("command_handler", value).apply() }
}
