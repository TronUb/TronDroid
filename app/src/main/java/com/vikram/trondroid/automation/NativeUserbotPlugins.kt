package com.vikram.trondroid.automation

import org.json.JSONObject
import org.json.JSONArray
import org.drinkless.tdlib.TdApi
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
    private val commonLanguages = listOf("en", "hi", "mr", "de", "ja", "fr", "es", "it", "pt", "ru", "ar", "zh-CN", "ko", "bn", "ur", "ta", "te")

    override val commands = listOf(
        AutomationCommand("tr", "Translate command text or a replied text message.", "tr <language> <text>"),
        AutomationCommand("tl", "Alias for translate.", "tl <language> <text>"),
        AutomationCommand("translate", "Alias for translate.", "translate <language> <text>"),
        AutomationCommand("trlist", "Show common supported language codes.", "trlist"),
        AutomationCommand("tllist", "Alias for language list.", "tllist"),
        AutomationCommand("translatelist", "Alias for language list.", "translatelist"),
        AutomationCommand("translatelang", "Alias for language list.", "translatelang")
    )

    override fun onCommand(context: AutomationContext) {
        if (context.command in setOf("trlist", "tllist", "translatelist", "translatelang")) {
            return context.reply("Common language codes: " + commonLanguages.joinToString(", "))
        }

        val source = context.message.tdMessage
        val replied = source?.let { context.telegram?.repliedMessage(it) }
        val repliedText = replied?.let(::extractText).orEmpty()
        val language = context.arguments.firstOrNull()?.lowercase(Locale.ROOT) ?: "en"
        val inlineText = context.arguments.drop(1).joinToString(" ").trim()
        val text = repliedText.ifBlank { inlineText }
        if (text.isBlank()) return context.reply("Usage: .tr <language code> <text> or reply to text with .tr [language]")
        if (!language.matches(Regex("[a-z]{2,3}(-[a-zA-Z]{2,4})?"))) {
            return context.reply("Use a valid language code, e.g. en, hi, mr, de or ja.")
        }

        context.reply("Translating to $language…")
        PluginHttp.run(context) {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${PluginHttp.encode(language)}&dt=t&q=${PluginHttp.encode(text)}"
            val root = JSONArray(PluginHttp.get(url))
            val segments = root.optJSONArray(0) ?: return@run "No translation was returned."
            val translated = buildString {
                for (index in 0 until segments.length()) {
                    append(segments.optJSONArray(index)?.optString(0).orEmpty())
                }
            }
            translated.ifBlank { "No translation was returned. Check the language code and try again." }
        }
    }

    private fun extractText(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption.text
        is TdApi.MessageVideo -> content.caption.text
        is TdApi.MessageAnimation -> content.caption.text
        is TdApi.MessageDocument -> content.caption.text
        else -> ""
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
        AutomationCommand("ping", "Measure command response time.", "ping [count]"),
        AutomationCommand("pong", "Alias for ping.", "pong")
    )

    override fun onCommand(context: AutomationContext) {
        val count = context.arguments.firstOrNull()?.toIntOrNull() ?: 1
        if (count !in 1..10) return context.reply("Usage: .ping [1-10]")
        val started = System.nanoTime()
        context.reply("…")
        context.replyAfter(300, buildString {
            append("Pöng!\n")
            append("%.2f ms".format(Locale.US, (System.nanoTime() - started) / 1_000_000.0))
            append("\nTronDroid is responding.")
            if (count > 1) append("\nSamples: $count")
        })
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
        AutomationCommand("slap", "Generate a playful slap message for a replied user.", "slap (reply to a user)"),
        AutomationCommand("upcase", "Convert supplied or replied text to uppercase.", "upcase <text>"),
        AutomationCommand("type", "Animate typing by progressively editing the command.", "type <text>"),
        AutomationCommand("insult", "Fetch a random insult for a replied user.", "insult (reply to a user) [language]"),
        AutomationCommand("joke", "Fetch a random joke.", "joke"),
        AutomationCommand("advice", "Fetch a random piece of advice.", "advice (reply to a user)"),
        AutomationCommand("uadvice", "Fetch a random useless fact.", "uadvice (reply to a user)"),
        AutomationCommand("qs", "Ask a random trivia question.", "qs (reply to a user)"),
        AutomationCommand("wtd", "Suggest a random activity.", "wtd"),
        AutomationCommand("mqt", "Fetch a quote of the day.", "mqt")
    )

    override fun onCommand(context: AutomationContext) {
        val source = context.message.tdMessage
        val replied = source?.let { context.telegram?.repliedMessage(it) }
        when (context.command) {
            "upcase" -> {
                val text = context.arguments.joinToString(" ").ifBlank { replied?.let(::extractText).orEmpty() }
                context.reply(if (text.isBlank()) "Reply to a text message or use .upcase <text>." else text.uppercase(Locale.ROOT))
            }
            "slap" -> {
                if (replied == null) return context.reply("Reply to someone to use .slap.")
                val sender = replied.senderId as? TdApi.MessageSenderUser
                val user = sender?.let { context.telegram?.cachedUser(it.userId) }
                val name = user?.firstName?.takeIf { it.isNotBlank() } ?: "that user"
                if (sender?.userId == source?.senderId.let { (it as? TdApi.MessageSenderUser)?.userId }) {
                    return context.reply("You cannot slap yourself.")
                }
                val actions = listOf(
                    "slaps $name with a giant cardboard box",
                    "bonks $name with a rubber chicken",
                    "challenges $name to a pillow fight",
                    "gently pats $name on the head",
                    "launches a foam slipper at $name"
                )
                context.reply(actions.random())
            }
            "type" -> {
                val text = context.arguments.joinToString(" ").take(60)
                if (text.isBlank()) return context.reply("Usage: .type <text>")
                val current = StringBuilder()
                text.forEachIndexed { index, char ->
                    current.append(char)
                    context.replyAfter((index + 1L) * 180L, current.toString())
                }
            }
            "insult" -> {
                if (replied == null) return context.reply("Reply to someone to use .insult.")
                val language = context.arguments.firstOrNull()?.lowercase(Locale.ROOT) ?: "en"
                if (!language.matches(Regex("[a-z]{2,3}"))) return context.reply("Use a valid language code, e.g. en.")
                context.reply("Finding an insult…")
                PluginHttp.run(context) {
                    val json = JSONObject(PluginHttp.get("https://evilinsult.com/generate_insult.php?lang=$language&type=json"))
                    json.optString("insult").ifBlank { "No insult was returned." }
                }
            }
            "joke" -> {
                context.reply("Getting a joke…")
                PluginHttp.run(context) {
                    val json = JSONObject(PluginHttp.get("https://icanhazdadjoke.com/slack"))
                    json.optJSONArray("attachments")?.optJSONObject(0)?.optString("fallback")
                        ?.takeIf { it.isNotBlank() } ?: "No joke was returned."
                }
            }
            "wtd" -> {
                context.reply("Finding an activity…")
                PluginHttp.run(context) {
                    val json = JSONObject(PluginHttp.get("https://bored-api.appbrewery.com/random"))
                    "Activity: " + json.optString("activity") + "\nType: " + json.optString("type")
                }
            }
            "mqt" -> {
                context.reply("Finding a quote…")
                PluginHttp.run(context) {
                    val quote = JSONObject(PluginHttp.get("https://favqs.com/api/qotd")).optJSONObject("quote")
                        ?: return@run "No quote found."
                    "Quote:\n" + quote.optString("body") + "\n\nAuthor: " + quote.optString("author")
                }
            }
            "advice" -> {
                if (replied == null) return context.reply("Reply to someone to use .advice.")
                context.reply("Finding advice…")
                PluginHttp.run(context) {
                    JSONObject(PluginHttp.get("https://api.adviceslip.com/advice"))
                        .optJSONObject("slip")?.optString("advice") ?: "No advice found."
                }
            }
            "uadvice" -> {
                if (replied == null) return context.reply("Reply to someone to use .uadvice.")
                context.reply("Finding a useless fact…")
                PluginHttp.run(context) {
                    JSONObject(PluginHttp.get("https://uselessfacts.jsph.pl/api/v2/facts/random?language=en"))
                        .optString("text").ifBlank { "No fact found." }
                }
            }
            "qs" -> {
                if (replied == null) return context.reply("Reply to someone to use .qs.")
                context.reply("Finding a trivia question…")
                PluginHttp.run(context) {
                    val root = JSONObject(PluginHttp.get("https://opentdb.com/api.php?amount=1&type=multiple"))
                    val item = root.optJSONArray("results")?.optJSONObject(0) ?: return@run "No question found."
                    val question = item.optString("question")
                    val correct = item.optString("correct_answer")
                    val options = mutableListOf<String>()
                    val wrong = item.optJSONArray("incorrect_answers")
                    if (wrong != null) for (i in 0 until wrong.length()) options += wrong.optString(i)
                    options.add(correct)
                    options.shuffle()
                    val formatted = options.mapIndexed { i, value -> "${i + 1}. $value" }.joinToString("\n")
                    "Trivia question:\n$question\n\n$formatted\n\nCorrect answer: $correct"
                }
            }
        }
    }

    private fun extractText(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption.text
        is TdApi.MessageVideo -> content.caption.text
        is TdApi.MessageDocument -> content.caption.text
        else -> ""
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
    override val commands = listOf(
        AutomationCommand("paste", "Paste text from a message or command arguments.", "paste <text>"),
        AutomationCommand("bin", "Alias for paste.", "bin <text>")
    )

    override fun onCommand(context: AutomationContext) {
        val sourceMessage = context.message.tdMessage
        val replied = sourceMessage?.let { context.telegram?.repliedMessage(it) }
        val deleteReply = context.arguments.firstOrNull()?.lowercase() in setOf("d", "del") &&
            replied?.isOutgoing == true
        val argumentText = context.arguments.let { args ->
            if (args.firstOrNull()?.lowercase() in setOf("d", "del")) args.drop(1) else args
        }.joinToString(" ").trim()
        val text = replied?.let(::messageText).orEmpty().ifBlank { argumentText }
        if (text.isBlank()) {
            return context.reply("Reply to a text message or provide text after the command. Usage: .paste <text>")
        }

        context.reply("Pasting to pastebin…")
        PluginHttp.run(context) {
            val url = PluginHttp.post("https://paste.rs", text)
            if (!url.startsWith("http")) return@run "Paste service returned an unexpected response."
            if (deleteReply && replied != null && sourceMessage != null) {
                context.telegram?.send(TdApi.DeleteMessages(sourceMessage.chatId, longArrayOf(replied.id), true)) { }
            }
            "Pastebin: $url"
        }
    }

    private fun messageText(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption.text
        is TdApi.MessageVideo -> content.caption.text
        is TdApi.MessageAnimation -> content.caption.text
        is TdApi.MessageDocument -> content.caption.text
        is TdApi.MessageAudio -> content.caption.text
        else -> ""
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
        AutomationCommand("notelist", "Alias for saved note names.", "notelist"),
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
            "notes", "notelist" -> {
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

class AfkAutomationPlugin(
    private val commandPrefix: () -> String = { "." },
    private val ownerId: () -> Long? = { null }
) : AutomationPlugin {
    override val id = "userbot:afk"
    override val commands = listOf(
        AutomationCommand("afk", "Set an away status and optional reason.", "afk <reason>"),
        AutomationCommand("unafk", "Clear the away status.", "unafk")
    )
    @Volatile private var reason: String = ""
    @Volatile private var active: Boolean = false
    @Volatile private var startedAt: Long = 0L

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "afk" -> {
                reason = context.arguments.joinToString(" ").trim()
                active = true
                startedAt = System.currentTimeMillis()
                context.reply("AFK enabled" + (reason.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."))
            }
            "unafk" -> clearAfk(context.message.chatId, context)
        }
    }

    override fun onMessage(context: AutomationContext) {
        if (!active) return
        val message = context.message
        val text = message.text
        if (message.isOutgoing) {
            val prefix = commandPrefix().ifBlank { "." }
            val token = text.trim().substringBefore(Regex("\\s+").pattern)
            if (token.equals(prefix + "afk", true) ||
                token.equals(prefix + "unafk", true) || text.contains("#afk", true)
            ) return
            val minutes = ((System.currentTimeMillis() - startedAt) / 60_000).coerceAtLeast(0)
            active = false
            reason = ""
            startedAt = 0L
            sendNewMessage(context, "I'm back online. Offline time: ${minutes}m.")
            return
        }

        val tdMessage = message.tdMessage ?: return
        val isPrivate = context.telegram?.cachedChat(message.chatId)?.type is TdApi.ChatTypePrivate
        val expectedOwnerId = ownerId()
        val isMention = (tdMessage.content as? TdApi.MessageText)?.text?.entities
            ?.any { entity ->
                val mention = entity.type as? TdApi.TextEntityTypeMentionName
                mention != null && expectedOwnerId != null && mention.userId == expectedOwnerId
            } == true
        if (!isPrivate && !isMention) return

        val awayReason = reason.ifBlank { "No reason mentioned" }
        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000).coerceAtLeast(0)
        context.reply("Currently away for ${minutes}m. Reason: $awayReason")
    }

    private fun clearAfk(chatId: Long, context: AutomationContext) {
        reason = ""
        active = false
        startedAt = 0L
        context.reply("AFK disabled.")
    }

    private fun sendNewMessage(context: AutomationContext, text: String) {
        context.telegram?.send(
            TdApi.SendMessage(
                context.message.chatId, null, null, null, null,
                TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
            )
        ) { }
    }
}
