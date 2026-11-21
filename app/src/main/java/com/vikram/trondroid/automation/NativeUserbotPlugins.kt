package com.vikram.trondroid.automation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

private object PluginHttp {
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "TronDroid-PluginHttp").apply { isDaemon = true }
    }

    fun run(context: AutomationContext, task: () -> String) {
        executor.execute {
            val result = runCatching(task).getOrElse { error ->
                "Request failed: ${error.message ?: "unknown error"}"
            }
            context.reply(result.take(4000))
        }
    }

    fun get(url: String, userAgent: String = "TronDroid/1.0 (Android; Telegram userbot)"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            body
        } finally {
            connection.disconnect()
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

class WikipediaAutomationPlugin : AutomationPlugin {
    override val id = "userbot:wikipedia"
    override val commands = listOf(
        AutomationCommand("wiki", "Search Wikipedia and return the article summary.", "wiki <query>")
    )

    override fun onCommand(context: AutomationContext) {
        val query = context.arguments.joinToString(" ").trim()
        if (query.isBlank()) return context.reply("Usage: .wiki <query>")
        context.reply("Searching Wikipedia…")
        PluginHttp.run(context) {
            val json = JSONObject(PluginHttp.get(
                "https://en.wikipedia.org/api/rest_v1/page/summary/${PluginHttp.encode(query.replace(' ', '_'))}"
            ))
            if (json.optString("type") == "https://api.wikimedia.org/core/v1/wikipedia/en/page/not_found") {
                return@run "No Wikipedia article found for: $query"
            }
            val title = json.optString("title", query)
            val summary = json.optString("extract").ifBlank { "No summary is available for this article." }
            val page = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
            buildString {
                append("$title\n\n")
                append(summary.take(3500))
                if (page.isNotBlank()) append("\n\n$page")
            }
        }
    }
}

class ImdbAutomationPlugin : AutomationPlugin {
    override val id = "userbot:imdb"
    override val commands = listOf(
        AutomationCommand("imdb", "Find a movie or series and show its IMDb page.", "imdb <title>")
    )

    override fun onCommand(context: AutomationContext) {
        val query = context.arguments.joinToString(" ").trim()
        if (query.isBlank()) return context.reply("Usage: .imdb <movie or series>")
        context.reply("Searching IMDb…")
        PluginHttp.run(context) {
            val root = JSONObject(PluginHttp.get(
                "https://v3.sg.media-imdb.com/suggestion/x/${PluginHttp.encode(query)}.json"
            ))
            val results = root.optJSONArray("d") ?: return@run "No IMDb results found."
            val item = (0 until results.length()).asSequence()
                .map { results.optJSONObject(it) ?: JSONObject() }
                .firstOrNull { it.optString("id").startsWith("tt") }
                ?: return@run "No IMDb results found."
            val title = item.optString("l", query)
            val year = item.optInt("y", 0).takeIf { it > 0 }?.toString()
            val kind = item.optString("q")
            val id = item.optString("id")
            buildString {
                append("**$title**")
                if (year != null) append(" ($year)")
                if (kind.isNotBlank()) append("\n$kind")
                append("\nhttps://www.imdb.com/title/$id/")
            }
        }
    }
}

class RedditAutomationPlugin : AutomationPlugin {
    override val id = "userbot:reddit"
    override val commands = listOf(
        AutomationCommand("reddit", "Search Reddit and return the top matching posts.", "reddit <query>"),
        AutomationCommand("r", "Alias for Reddit search.", "r <query>")
    )

    override fun onCommand(context: AutomationContext) {
        val query = context.arguments.joinToString(" ").trim()
        if (query.isBlank()) return context.reply("Usage: .reddit <query>")
        context.reply("Searching Reddit…")
        PluginHttp.run(context) {
            val root = JSONObject(PluginHttp.get(
                "https://www.reddit.com/search.json?q=${PluginHttp.encode(query)}&limit=5&sort=relevance",
                "TronDroid/1.0"
            ))
            val children = root.optJSONObject("data")?.optJSONArray("children")
                ?: return@run "No Reddit posts found."
            val lines = (0 until children.length()).mapNotNull { index ->
                val post = children.optJSONObject(index)?.optJSONObject("data") ?: return@mapNotNull null
                if (post.optBoolean("over_18")) return@mapNotNull null
                val title = post.optString("title")
                val permalink = post.optString("permalink")
                if (title.isBlank() || permalink.isBlank()) null
                else "• $title\nhttps://www.reddit.com$permalink"
            }
            lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                ?: "No suitable Reddit posts found."
        }
    }
}

class TranslateAutomationPlugin : AutomationPlugin {
    override val id = "userbot:translate"
    override val commands = listOf(
        AutomationCommand("tr", "Translate text to the requested language.", "tr <language> <text>"),
        AutomationCommand("tl", "Alias for translate.", "tl <language> <text>"),
        AutomationCommand("translate", "Alias for translate.", "translate <language> <text>"),
        AutomationCommand("trlist", "Show common supported language codes.", "trlist")
    )

    override fun onCommand(context: AutomationContext) {
        if (context.command == "trlist") {
            return context.reply("Common language codes: en, hi, mr, de, ja, fr, es, it, pt, ru, ar, zh, ko")
        }
        if (context.arguments.size < 2) return context.reply("Usage: .tr <language code> <text>")
        val language = context.arguments.first().lowercase(Locale.ROOT)
        val text = context.arguments.drop(1).joinToString(" ")
        if (!language.matches(Regex("[a-z]{2,3}"))) return context.reply("Use a valid language code, e.g. hi, mr, de or ja.")
        context.reply("Translating…")
        PluginHttp.run(context) {
            val pair = "auto|$language"
            val json = JSONObject(PluginHttp.get(
                "https://api.mymemory.translated.net/get?q=${PluginHttp.encode(text)}&langpair=${PluginHttp.encode(pair)}"
            ))
            val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            translated.ifBlank { "No translation was returned. Check the language code and try again." }
        }
    }
}

class TimeAutomationPlugin : AutomationPlugin {
    override val id = "userbot:time"
    override val commands = listOf(
        AutomationCommand("today", "Show the current day, date and local time.", "today"),
        AutomationCommand("time", "Show the current local time.", "time"),
        AutomationCommand("date", "Show the current local date.", "date")
    )

    override fun onCommand(context: AutomationContext) {
        val now = ZonedDateTime.now()
        val result = when (context.command) {
            "today" -> "Today is **${now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))}**\n${now.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}\nTime: ${now.format(DateTimeFormatter.ofPattern("hh:mm:ss a"))}"
            "date" -> "Today's date: ${now.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}"
            else -> "Local time: ${now.format(DateTimeFormatter.ofPattern("hh:mm:ss a z"))}"
        }
        context.reply(result)
    }
}

class PingAutomationPlugin : AutomationPlugin {
    override val id = "userbot:ping"
    override val commands = listOf(
        AutomationCommand("ping", "Check that the userbot is responding.", "ping")
    )

    override fun onCommand(context: AutomationContext) {
        context.reply("Pong! TronDroid is responding.")
    }
}

class AliveAutomationPlugin(
    private val profileName: () -> String
) : AutomationPlugin {
    override val id = "userbot:alive"
    override val commands = listOf(
        AutomationCommand("alive", "Show the connected account and TronDroid runtime status.", "alive")
    )

    override fun onCommand(context: AutomationContext) {
        context.reply("TronDroid is running\nAccount: ${profileName()}\nRuntime: Kotlin + TDLib")
    }
}

class HelpAutomationPlugin(
    private val commandPrefix: () -> String,
    private val available: () -> List<AutomationPlugin>
) : AutomationPlugin {
    override val id = "userbot:help"
    override val commands = listOf(
        AutomationCommand("help", "List enabled commands and their short descriptions.", "help")
    )

    override fun onCommand(context: AutomationContext) {
        val prefix = commandPrefix().ifBlank { "." }
        val lines = available().flatMap { plugin ->
            plugin.commands.map { "${prefix}${it.name} — ${it.description}" }
        }.distinct().sorted()
        context.reply(if (lines.isEmpty()) "No commands are currently registered."
            else "**Available commands**\n" + lines.take(80).joinToString("\n"))
    }
}

class AnimeAutomationPlugin : AutomationPlugin {
    override val id = "userbot:anime"
    private val actions = listOf(
        "baka", "bite", "blush", "bored", "cry", "cuddle", "dance", "facepalm", "feed",
        "happy", "highfive", "hug", "kiss", "laugh", "pat", "poke", "pout", "shrug",
        "slap", "sleep", "smile", "stare", "think", "thumbsup", "tickle", "wave", "wink"
    )
    override val commands = listOf(
        AutomationCommand("animelist", "List available anime GIF actions.", "animelist"),
        AutomationCommand("nekopic", "Fetch a random anime neko image.", "nekopic"),
        AutomationCommand("npic", "Alias for neko image.", "npic"),
        AutomationCommand("animegif", "Fetch an anime GIF for an action.", "animegif <action>")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "animelist" -> context.reply("Available actions:\n" + actions.joinToString(", "))
            "nekopic", "npic" -> {
                context.reply("Fetching an anime image…")
                PluginHttp.run(context) {
                    val result = JSONObject(PluginHttp.get("https://nekos.best/api/v2/neko"))
                        .optJSONArray("results")?.optJSONObject(0) ?: return@run "No image found."
                    val url = result.optString("url")
                    val artist = result.optString("artist_name")
                    if (url.isBlank()) "No image found."
                    else "Anime image" + if (artist.isNotBlank()) " · Artist: $artist" else "" + "\n$url"
                }
            }
            "animegif" -> {
                val action = context.arguments.firstOrNull()?.lowercase().orEmpty()
                if (action !in actions) return context.reply("Usage: .animegif <action>\nActions: " + actions.joinToString(", "))
                context.reply("Fetching $action GIF…")
                PluginHttp.run(context) {
                    val result = JSONObject(PluginHttp.get("https://nekos.best/api/v2/$action"))
                        .optJSONArray("results")?.optJSONObject(0) ?: return@run "No GIF found."
                    val url = result.optString("url")
                    val anime = result.optString("anime_name")
                    if (url.isBlank()) "No GIF found."
                    else (if (anime.isBlank()) action else anime) + "\n$url"
                }
            }
        }
    }
}

class FunAutomationPlugin : AutomationPlugin {
    override val id = "userbot:fun"
    override val commands = listOf(
        AutomationCommand("upcase", "Convert supplied text to uppercase.", "upcase <text>"),
        AutomationCommand("joke", "Fetch a random joke.", "joke"),
        AutomationCommand("advice", "Fetch a random piece of advice.", "advice"),
        AutomationCommand("uadvice", "Alias for advice.", "uadvice")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "upcase" -> {
                val text = context.arguments.joinToString(" ")
                context.reply(if (text.isBlank()) "Usage: .upcase <text>" else text.uppercase(Locale.ROOT))
            }
            "joke" -> {
                context.reply("Getting a joke…")
                PluginHttp.run(context) {
                    val json = JSONObject(PluginHttp.get("https://official-joke-api.appspot.com/random_joke"))
                    json.optString("setup") + "\n" + json.optString("punchline")
                }
            }
            "advice", "uadvice" -> {
                context.reply("Getting advice…")
                PluginHttp.run(context) {
                    val json = JSONObject(PluginHttp.get("https://api.adviceslip.com/advice"))
                    json.optJSONObject("slip")?.optString("advice") ?: "No advice found."
                }
            }
        }
    }
}
