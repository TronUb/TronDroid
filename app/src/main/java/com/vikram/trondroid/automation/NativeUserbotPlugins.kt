package com.vikram.trondroid.automation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import android.content.SharedPreferences

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

    fun post(url: String, body: String, contentType: String = "text/plain; charset=utf-8"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "TronDroid/1.0")
        connection.setRequestProperty("Content-Type", contentType)
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            result
        } finally {
            connection.disconnect()
        }
    }
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
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    override val commands = listOf(
        AutomationCommand("alive", "Show the connected account and TronDroid runtime status.", "alive")
    )

    override fun onCommand(context: AutomationContext) {
        val seconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0)
        val uptime = "%dd %02dh %02dm".format(seconds / 86400, (seconds / 3600) % 24, (seconds / 60) % 60)
        context.reply("TronDroid is running\nAccount: ${profileName()}\nUptime: $uptime\nRuntime: Kotlin + TDLib")
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
                    else buildString { append("Anime image"); if (artist.isNotBlank()) append(" · Artist: $artist"); append("\n$url") }
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


class WeatherAutomationPlugin : AutomationPlugin {
    override val id = "userbot:weather"
    override val commands = listOf(AutomationCommand("weather", "Show current weather for a city.", "weather <city>"))
    override fun onCommand(context: AutomationContext) {
        val city = context.arguments.joinToString(" ").trim()
        if (city.isBlank()) return context.reply("Usage: .weather <city>")
        context.reply("Checking weather…")
        PluginHttp.run(context) { PluginHttp.get("https://wttr.in/${PluginHttp.encode(city)}?format=%l:+%c+%t,+feels+like+%f,+humidity+%h,+wind+%w") }
    }
}

class CurrencyAutomationPlugin : AutomationPlugin {
    override val id = "userbot:currency"
    override val commands = listOf(AutomationCommand("currency", "Convert an amount between currencies.", "currency <amount> <from> <to>"), AutomationCommand("cur", "Alias for currency conversion.", "cur <amount> <from> <to>"))
    override fun onCommand(context: AutomationContext) {
        if (context.arguments.size < 3) return context.reply("Usage: .currency <amount> <from> <to>")
        val amount = context.arguments[0].toDoubleOrNull() ?: return context.reply("Enter a valid amount.")
        val from = context.arguments[1].uppercase(Locale.ROOT)
        val to = context.arguments[2].uppercase(Locale.ROOT)
        context.reply("Converting $from to $to…")
        PluginHttp.run(context) {
            val json = JSONObject(PluginHttp.get("https://api.frankfurter.app/latest?amount=$amount&from=$from&to=$to"))
            val rate = json.optJSONObject("rates")?.optDouble(to, Double.NaN) ?: Double.NaN
            if (!rate.isFinite()) "Unable to find a conversion rate for $from to $to."
            else String.format(Locale.US, "%.2f %s = %.2f %s", amount, from, rate, to)
        }
    }
}

class TemperatureAutomationPlugin : AutomationPlugin {
    override val id = "userbot:temperature"
    override val commands = listOf(AutomationCommand("temp", "Convert Celsius and Fahrenheit.", "temp <value> <c|f>"))
    override fun onCommand(context: AutomationContext) {
        if (context.arguments.size < 2) return context.reply("Usage: .temp <value> <c|f>")
        val value = context.arguments[0].toDoubleOrNull() ?: return context.reply("Enter a valid temperature.")
        val result = when (context.arguments[1].lowercase(Locale.ROOT)) {
            "c" -> value * 9.0 / 5.0 + 32.0
            "f" -> (value - 32.0) * 5.0 / 9.0
            else -> return context.reply("Use c to convert Celsius to Fahrenheit, or f for Fahrenheit to Celsius.")
        }
        val unit = if (context.arguments[1].equals("c", true)) "°F" else "°C"
        context.reply(String.format(Locale.US, "%.2f%s", result, unit))
    }
}

class PasteAutomationPlugin : AutomationPlugin {
    override val id = "userbot:pastebin"
    override val commands = listOf(AutomationCommand("paste", "Upload text to paste.rs and return its link.", "paste <text>"), AutomationCommand("bin", "Alias for paste.", "bin <text>"))
    override fun onCommand(context: AutomationContext) {
        val text = context.arguments.joinToString(" ").trim()
        if (text.isBlank()) return context.reply("Usage: .paste <text>")
        context.reply("Uploading paste…")
        PluginHttp.run(context) {
            val url = PluginHttp.post("https://paste.rs", text)
            if (url.startsWith("http")) "Paste created: $url" else "Paste service returned an unexpected response."
        }
    }
}

class QrAutomationPlugin : AutomationPlugin {
    override val id = "userbot:qrcode"
    override val commands = listOf(AutomationCommand("qrcode", "Create a QR code link for text or a URL.", "qrcode <text>"))
    override fun onCommand(context: AutomationContext) {
        val value = context.arguments.joinToString(" ").trim()
        if (value.isBlank()) return context.reply("Usage: .qrcode <text>")
        val url = "https://api.qrserver.com/v1/create-qr-code/?size=512x512&data=" + PluginHttp.encode(value)
        context.reply("QR code: $url")
    }
}

class NotesAutomationPlugin(private val preferences: SharedPreferences) : AutomationPlugin {
    override val id = "userbot:notes"
    override val commands = listOf(
        AutomationCommand("save", "Save a text note under a name.", "save <name> <text>"),
        AutomationCommand("getnote", "Retrieve a saved note.", "getnote <name>"),
        AutomationCommand("notes", "List saved note names.", "notes"),
        AutomationCommand("clear", "Delete a saved note.", "clear <name>")
    )
    override fun onCommand(context: AutomationContext) {
        val name = context.arguments.firstOrNull().orEmpty()
        when (context.command) {
            "save" -> {
                if (context.arguments.size < 2) return context.reply("Usage: .save <name> <text>")
                val value = context.arguments.drop(1).joinToString(" ")
                preferences.edit().putString("note.$name", value).apply()
                context.reply("Saved note: $name")
            }
            "getnote" -> {
                if (name.isBlank()) return context.reply("Usage: .getnote <name>")
                context.reply(preferences.getString("note.$name", null) ?: "No note named $name.")
            }
            "notes" -> {
                val names = preferences.all.keys.filter { it.startsWith("note.") }.map { it.removePrefix("note.") }.sorted()
                context.reply(names.takeIf { it.isNotEmpty() }?.joinToString("\\n") ?: "No saved notes.")
            }
            "clear" -> {
                if (name.isBlank()) return context.reply("Usage: .clear <name>")
                preferences.edit().remove("note.$name").apply()
                context.reply("Deleted note: $name")
            }
        }
    }
}

class AfkAutomationPlugin : AutomationPlugin {
    override val id = "userbot:afk"
    override val commands = listOf(
        AutomationCommand("afk", "Set an away status and optional reason.", "afk <reason>"),
        AutomationCommand("unafk", "Clear the away status.", "unafk")
    )
    @Volatile private var reason: String? = null
    @Volatile private var startedAt: Long = 0L
    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "afk" -> {
                reason = context.arguments.joinToString(" ").ifBlank { null }
                startedAt = System.currentTimeMillis()
                context.reply("AFK enabled" + (reason?.let { ": $it" } ?: "."))
            }
            "unafk" -> {
                reason = null
                startedAt = 0L
                context.reply("AFK disabled.")
            }
        }
    }
    override fun onMessage(context: AutomationContext) {
        val awayReason = reason ?: return
        if (!context.message.isOutgoing) {
            val minutes = ((System.currentTimeMillis() - startedAt) / 60000).coerceAtLeast(0)
            context.reply("This user is currently away (" + minutes + "m). Reason: " + awayReason)
        }
    }
}
