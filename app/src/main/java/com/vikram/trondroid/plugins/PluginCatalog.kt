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
    val commands: List<PluginCommand>
)

object PluginCatalog {
    val userbot = listOf(
        BotPlugin("userbot:anime", "Anime", "Fetch anime images and GIFs by action.", "Entertainment", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/anime.py", listOf(
                PluginCommand("animelist", "List supported anime GIF actions.", "animelist"),
                PluginCommand("nekopic", "Fetch a random anime image.", "nekopic"),
                PluginCommand("npic", "Alias for neko image.", "npic"),
                PluginCommand("animegif", "Fetch a GIF for an action.", "animegif <action>")
            )),
        BotPlugin("userbot:fun", "Fun", "Small text utilities, jokes and advice.", "Entertainment", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/fun.py", listOf(
                PluginCommand("upcase", "Convert text to uppercase.", "upcase <text>"),
                PluginCommand("joke", "Fetch a random joke.", "joke"),
                PluginCommand("advice", "Fetch random advice.", "advice"),
                PluginCommand("uadvice", "Alias for advice.", "uadvice")
            )),
        BotPlugin("userbot:wikipedia", "Wikipedia", "Search Wikipedia articles and read concise summaries.", "Search", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/wikipedia.py", listOf(
                PluginCommand("wiki", "Find an article and return its summary.", "wiki <query>")
            )),
        BotPlugin("userbot:imdb", "IMDb", "Find movies and series using IMDb title search.", "Search", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/imdb.py", listOf(
                PluginCommand("imdb", "Find a title and open its IMDb page.", "imdb <title>")
            )),
        BotPlugin("userbot:reddit", "Reddit", "Search public Reddit posts and show relevant results.", "Search", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/reddit.py", listOf(
                PluginCommand("reddit", "Search Reddit posts by keyword.", "reddit <query>"),
                PluginCommand("r", "Alias for Reddit search.", "r <query>")
            )),
        BotPlugin("userbot:translate", "Translate", "Translate text using the selected target language.", "Utilities", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/translate.py", listOf(
                PluginCommand("tr", "Translate text into a language.", "tr <language> <text>"),
                PluginCommand("tl", "Alias for translate.", "tl <language> <text>"),
                PluginCommand("translate", "Alias for translate.", "translate <language> <text>"),
                PluginCommand("trlist", "Show common language codes.", "trlist")
            )),
        BotPlugin("userbot:time", "Time & date", "Show local time, date and weekday.", "Utilities", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/time.py", listOf(
                PluginCommand("today", "Show weekday, date and local time.", "today"),
                PluginCommand("time", "Show current local time.", "time"),
                PluginCommand("date", "Show current local date.", "date")
            )),
        BotPlugin("userbot:ping", "Ping", "Check the userbot command response.", "Diagnostics", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/ping.py", listOf(
                PluginCommand("ping", "Check that the userbot is responding.", "ping")
            )),
        BotPlugin("userbot:alive", "Alive", "Show the connected account and app runtime.", "Status", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/alive.py", listOf(
                PluginCommand("alive", "Show connected account and runtime status.", "alive")
            )),
        BotPlugin("userbot:help", "Help", "List commands currently implemented in TronDroid.", "Utilities", PluginOwner.USERBOT,
            "main/userbot/modules/plugins/help.py", listOf(
                PluginCommand("help", "List enabled commands and their descriptions.", "help")
            ))
    )

    val bot = listOf(
        BotPlugin("bot:ping", "Ping", "Check whether the Telegram bot is responding.", "Diagnostics", PluginOwner.BOT,
            "main/assistant/modules/plugins/ping.py", listOf(
                PluginCommand("ping", "Check bot responsiveness.", "/ping")
            )),
        BotPlugin("bot:start", "Start", "Show the bot welcome message.", "Onboarding", PluginOwner.BOT,
            "main/assistant/modules/plugins/start.py", listOf(
                PluginCommand("start", "Start the bot and show its welcome message.", "/start")
            )),
        BotPlugin("bot:help", "Help", "Show the bot command list.", "Utilities", PluginOwner.BOT,
            "main/assistant/modules/plugins/help.py", listOf(
                PluginCommand("help", "List commands supported by this bot.", "/help")
            ))
    )

    val all: List<BotPlugin> = userbot + bot
    fun find(id: String): BotPlugin? = all.firstOrNull { it.id == id }
}

class PluginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.plugins", Context.MODE_PRIVATE)
    fun enabled(id: String) = prefs.getBoolean(id, true)
    fun setEnabled(id: String, value: Boolean) { prefs.edit().putBoolean(id, value).apply() }
    fun handler() = prefs.getString("command_handler", ".") ?: "."
    fun setHandler(value: String) { prefs.edit().putString("command_handler", value).apply() }
}
