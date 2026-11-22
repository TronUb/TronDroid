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
        AutomationCommand("reddit", "Fetch a random image from a subreddit.", "reddit <subreddit>"),
        AutomationCommand("r", "Alias for subreddit image lookup.", "r <subreddit>")
    )

    override fun onCommand(context: AutomationContext) {
        val subreddit = context.arguments.firstOrNull().orEmpty().removePrefix("r/").trim()
        if (!subreddit.matches(Regex("[A-Za-z0-9_]{2,21}"))) {
            return context.reply("Usage: .reddit <subreddit> (example: .reddit anime)")
        }
        context.reply("Getting an image from r/$subreddit…")
        PluginHttp.run(context) {
            val root = JSONObject(PluginHttp.get(
                "https://old.reddit.com/r/$subreddit.json?limit=50",
                "Mozilla/5.0 (Android; TronDroid/1.0)"
            ))
            val children = root.optJSONObject("data")?.optJSONArray("children")
                ?: return@run "No Reddit posts found for r/$subreddit."
            val candidates = (0 until children.length()).mapNotNull { index ->
                val post = children.optJSONObject(index)?.optJSONObject("data") ?: return@mapNotNull null
                if (post.optBoolean("over_18") || post.optBoolean("is_video")) return@mapNotNull null
                val url = post.optString("url_overridden_by_dest").ifBlank { post.optString("url") }
                val lower = url.lowercase()
                if (url.startsWith("https://") &&
                    (lower.substringBefore('?').endsWith(".jpg") ||
                        lower.substringBefore('?').endsWith(".jpeg") ||
                        lower.substringBefore('?').endsWith(".png") ||
                        lower.substringBefore('?').endsWith(".webp") ||
                        lower.substringBefore('?').endsWith(".gif") ||
                        lower.substringBefore('?').endsWith(".gifv") ||
                        "i.redd.it/" in lower || "i.imgur.com/" in lower)
                ) url to post.optString("title") else null
            }
            val selected = candidates.randomOrNull() ?: return@run "No suitable image posts found in r/$subreddit."
            val gateway = context.telegram ?: return@run "Telegram session is unavailable."
            val (url, title) = selected
            val caption = title.ifBlank { "r/$subreddit" }.take(900)
            if (url.lowercase().substringBefore('?').let { it.endsWith(".gif") || it.endsWith(".gifv") || it.endsWith(".mp4") }) {
                val mediaUrl = if (url.lowercase().substringBefore('?').endsWith(".gifv")) {
                    url.substringBefore('?').removeSuffix(".gifv") + ".mp4"
                } else url
                gateway.sendAnimationUrl(context.message.chatId, mediaUrl, caption) { response ->
                    if (response is TdApi.Error) context.reply("Unable to send Reddit media: ${response.message}")
                }
            } else {
                gateway.sendPhotoUrl(context.message.chatId, url, caption) { response ->
                    if (response is TdApi.Error) context.reply("Unable to send Reddit image: ${response.message}")
                }
            }
            "Sending an image from r/$subreddit…"
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
        val frames = listOf("🕜", "🕡", "🕦", "🕣", "🕥", "🕧", "🕓", "🕔", "🕒", "🕑")
        context.reply("…")
        repeat(count) { index ->
            context.replyAfter(index * 280L, frames[index % frames.size])
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        context.replyAfter(count * 280L + 100L, buildString {
            append("Pöng!\n")
            append("%.2f ms".format(Locale.US, elapsed))
            append("\nTronDroid is responding.")
            if (count > 1) append("\nSamples: $count")
        })
    }
}

private object TronCommandMedia {
    const val USER_PIC = "https://telegra.ph/file/48f5dc15d51ea7f721275.jpg"
    const val BOT_PIC = "https://raw.githubusercontent.com/TronUb/Tron/master/main/others/resources/images/tron-square.png"

    fun button(text: String, data: String) =
        TdApi.InlineKeyboardButton(text, TdApi.InlineKeyboardButtonTypeCallback(data.toByteArray(Charsets.UTF_8)))

    fun markup(vararg rows: List<TdApi.InlineKeyboardButton>) =
        TdApi.ReplyMarkupInlineKeyboard(rows.map { it.toTypedArray() }.toTypedArray())

    fun uptime(startedAt: Long): String {
        val seconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0)
        val days = seconds / 86400
        val hours = (seconds / 3600) % 24
        val minutes = (seconds / 60) % 60
        val secs = seconds % 60
        return buildString {
            if (days > 0) append("${days}d ")
            append("%02dh %02dm %02ds".format(hours, minutes, secs))
        }
    }
}

class AliveAutomationPlugin(
    private val profileName: () -> String,
    private val appContext: android.content.Context
) : AutomationPlugin {
    override val id = "userbot:alive"
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    override val commands = listOf(
        AutomationCommand("alive", "Show account, TronDroid, Kotlin, TDLib and uptime information with the Tron status image.", "alive"),
        AutomationCommand("ialive", "Show the interactive inline-style alive card with refresh and navigation buttons.", "ialive"),
        AutomationCommand("iquote", "Send an anime quote card with a More button.", "iquote"),
        AutomationCommand("iqt", "Alias for inline anime quotes.", "iqt")
    )

    private fun statusText(): String {
        val version = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
        return buildString {
            append("⦿ TronDroid — Telegram automation\n\n")
            append("⟜ **Account:** ${profileName()}\n")
            append("⟜ **TronDroid:** `$version`\n")
            append("⟜ **Kotlin:** `${KotlinVersion.CURRENT}`\n")
            append("⟜ **TDLib:** `Native TDLib (bundled)`\n")
            append("⟜ **Runtime:** `Android / JVM`\n")
            append("⟜ **Uptime:** `${TronCommandMedia.uptime(startedAt)}`\n")
        }
    }

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "alive" -> {
                gateway.sendPhotoUrl(context.message.chatId, TronCommandMedia.USER_PIC, statusText()) { response ->
                    if (response is TdApi.Error) context.reply("Unable to send alive card: ${response.message}")
                }
                if (context.message.isOutgoing) gateway.deleteMessage(context.message.chatId, context.message.messageId)
            }
            "ialive" -> {
                val keyboard = TronCommandMedia.markup(
                    listOf(TronCommandMedia.button("Refresh", "ialive:refresh")),
                    listOf(
                        TronCommandMedia.button("Plugins", "help:page:0"),
                        TronCommandMedia.button("Runtime", "ialive:runtime")
                    ),
                    listOf(
                        TronCommandMedia.button("Home", "ialive:home"),
                        TronCommandMedia.button("Close", "ialive:close")
                    )
                )
                gateway.sendPhotoUrl(context.message.chatId, TronCommandMedia.USER_PIC,
                    "⛊ **Tron Inline Status**\n\n${statusText()}", keyboard) { response ->
                    if (response is TdApi.Error) context.reply("Unable to send inline alive card: ${response.message}")
                }
                if (context.message.isOutgoing) gateway.deleteMessage(context.message.chatId, context.message.messageId)
            }
            "iquote", "iqt" -> sendQuote(context)
        }
    }

    private fun sendQuote(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val quotes = listOf(
            "“A lesson without pain is meaningless.” — Edward Elric\n**Fullmetal Alchemist**",
            "“If you don't take risks, you can't create a future.” — Monkey D. Luffy\n**One Piece**",
            "“Whatever you lose, you'll find it again.” — Kenshin Himura\n**Rurouni Kenshin**",
            "“Knowing you're different is only the beginning.” — Natsuki Subaru\n**Re:Zero**",
            "“The world is not perfect. But it is there for us.” — Roy Mustang\n**Fullmetal Alchemist**"
        )
        val text = "❝ ${quotes.random()} ❞"
        gateway.sendText(context.message.chatId, text,
            TronCommandMedia.markup(listOf(TronCommandMedia.button("More", "quote:more"))))
        if (context.message.isOutgoing) gateway.deleteMessage(context.message.chatId, context.message.messageId)
    }

    override fun onTelegramUpdate(context: AutomationUpdateContext) {
        val update = context.update as? TdApi.UpdateNewCallbackQuery ?: return
        val query = update.callbackQuery
        val data = ((query.payload as? TdApi.CallbackQueryPayloadData)?.data ?: return)
            .toString(Charsets.UTF_8)
        val gateway = context.telegram ?: return
        when (data) {
            "ialive:refresh", "ialive:runtime" -> {
                gateway.answerCallbackQuery(query.id, "Refreshing status")
                gateway.editText(query.message.chatId, query.message.id,
                    if (data.endsWith("runtime")) "⚙ **Runtime details**\n\n${statusText()}" else "⛊ **Tron Inline Status**\n\n${statusText()}",
                    TronCommandMedia.markup(
                        listOf(TronCommandMedia.button("Refresh", "ialive:refresh")),
                        listOf(TronCommandMedia.button("Plugins", "help:page:0"), TronCommandMedia.button("Runtime", "ialive:runtime")),
                        listOf(TronCommandMedia.button("Home", "ialive:home"), TronCommandMedia.button("Close", "ialive:close"))
                    ))
            }
            "ialive:home" -> {
                gateway.answerCallbackQuery(query.id)
                gateway.editText(query.message.chatId, query.message.id,
                    "⛊ **Tron Inline Status**\n\n${statusText()}",
                    TronCommandMedia.markup(listOf(TronCommandMedia.button("Refresh", "ialive:refresh"))))
            }
            "ialive:close" -> {
                gateway.answerCallbackQuery(query.id, "Menu closed")
                gateway.editText(query.message.chatId, query.message.id, "Tron Inline Status — menu closed.")
            }
            "quote:more" -> {
                gateway.answerCallbackQuery(query.id)
                gateway.editText(query.message.chatId, query.message.id,
                    "❝ ${listOf(
                        "“A lesson without pain is meaningless.” — Edward Elric",
                        "“If you don't take risks, you can't create a future.” — Monkey D. Luffy",
                        "“Whatever you lose, you'll find it again.” — Kenshin Himura",
                        "“Knowing you're different is only the beginning.” — Natsuki Subaru"
                    ).random()} ❞\n\n**Tron Anime Quotes**",
                    TronCommandMedia.markup(listOf(TronCommandMedia.button("More", "quote:more"))))
            }
        }
    }
}

class HelpAutomationPlugin(
    private val commandPrefix: () -> String,
    private val available: () -> List<AutomationPlugin>
) : AutomationPlugin {
    override val id = "userbot:help"
    override val commands = listOf(
        AutomationCommand("help", "Open the Tron-style Helpdex with paginated plugin buttons; add a plugin name for its command reference.", "help [plugin]"),
        AutomationCommand("uplugs", "List installed userbot plugins.", "uplugs"),
        AutomationCommand("bplugs", "List installed bot plugins.", "bplugs"),
        AutomationCommand("inline", "Show how to enable Telegram inline mode for the connected assistant bot.", "inline")
    )

    private fun plugins(context: AutomationContext): List<AutomationPlugin> =
        available().filter { it.id != id }.filter { context.isCommandEnabled(it.id, it.commands.firstOrNull()?.name.orEmpty()) }
            .sortedBy { it.id.substringAfter(':').lowercase() }

    private fun detail(plugin: AutomationPlugin, prefix: String): String = buildString {
        append("**PLUGIN:** ${plugin.id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }}\n")
        append("**COMMANDS:** ${plugin.commands.size}\n\n")
        plugin.commands.forEach { command ->
            append("✦ **${prefix}${command.name}**\n")
            append("${command.description}\n")
            append("**Usage:** `${prefix}${command.usage}`\n\n")
        }
    }.take(3900)

    private fun pageMarkup(items: List<AutomationPlugin>, page: Int): TdApi.ReplyMarkup {
        val pageSize = 8
        val pages = ((items.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        val slice = items.drop(current * pageSize).take(pageSize)
        val rows = slice.chunked(2).map { row ->
            row.map { plugin ->
                TronCommandMedia.button(
                    "🧩 " + plugin.id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() },
                    "help:plugin:${plugin.id}:$current"
                )
            }
        }.toMutableList()
        rows += listOf(
            listOfNotNull(
                if (current > 0) TronCommandMedia.button("❰ Prev", "help:page:${current - 1}") else null,
                TronCommandMedia.button("Home", "help:home"),
                if (current < pages - 1) TronCommandMedia.button("Next ❱", "help:page:${current + 1}") else null
            )
        )
        return TronCommandMedia.markup(*rows.toTypedArray())
    }

    private fun helpCaption(items: List<AutomationPlugin>): String =
        "✦ **TRON HELPDEx**\n\nExplore ${items.size} installed plugins. Select a plugin to view its commands, descriptions and exact usage."

    override fun onCommand(context: AutomationContext) {
        val prefix = commandPrefix().ifBlank { "." }
        val all = plugins(context)
        val requested = context.arguments.firstOrNull()?.lowercase()
        when (context.command) {
            "uplugs" -> context.reply("**USERBOT PLUGINS**\n\n" + all.joinToString("\n") { it.id.substringAfter(':') })
            "bplugs" -> context.reply("Bot plugins are listed in the Plugins → Bot tab. Bot runtime help is available with /help.")
            "inline" -> context.reply("Telegram inline mode is configured through @BotFather. Open BotFather → /mybots → select your assistant bot → Bot Settings → Inline Mode → Turn on. TronDroid's native TDLib command menus do not require inline mode.")
            else -> {
                if (!requested.isNullOrBlank()) {
                    val selected = all.firstOrNull { it.id.substringAfter(':').equals(requested, true) || it.id.equals(requested, true) }
                        ?: return context.reply("Plugin '$requested' was not found. Use ${prefix}uplugs to list installed plugins.")
                    return context.reply(detail(selected, prefix))
                }
                val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
                val keyboard = pageMarkup(all, 0)
                gateway.sendPhotoUrl(context.message.chatId, TronCommandMedia.BOT_PIC, helpCaption(all), keyboard) { response ->
                    if (response is TdApi.Error) {
                        context.reply("Unable to send Helpdex image: ${response.message}")
                        gateway.sendText(context.message.chatId, helpCaption(all), keyboard)
                    }
                }
                if (context.message.isOutgoing) gateway.deleteMessage(context.message.chatId, context.message.messageId)
            }
        }
    }

    override fun onTelegramUpdate(context: AutomationUpdateContext) {
        val update = context.update as? TdApi.UpdateNewCallbackQuery ?: return
        val query = update.callbackQuery
        val data = ((query.payload as? TdApi.CallbackQueryPayloadData)?.data ?: return).toString(Charsets.UTF_8)
        if (!data.startsWith("help:")) return
        val gateway = context.telegram ?: return
        val prefix = commandPrefix().ifBlank { "." }
        val all = available().filter { it.id != id }.sortedBy { it.id.substringAfter(':').lowercase() }
        when {
            data == "help:home" -> {
                gateway.answerCallbackQuery(query.id)
                gateway.editText(query.message.chatId, query.message.id, helpCaption(all), pageMarkup(all, 0))
            }
            data.startsWith("help:page:") -> {
                val page = data.substringAfterLast(':').toIntOrNull() ?: 0
                gateway.answerCallbackQuery(query.id)
                gateway.editText(query.message.chatId, query.message.id, helpCaption(all), pageMarkup(all, page))
            }
            data.startsWith("help:plugin:") -> {
                val body = data.removePrefix("help:plugin:")
                val page = body.substringAfterLast(':').toIntOrNull() ?: 0
                val pluginId = body.substringBeforeLast(':')
                val plugin = all.firstOrNull { it.id == pluginId }
                if (plugin == null) {
                    gateway.answerCallbackQuery(query.id, "Plugin is no longer available")
                } else {
                    gateway.answerCallbackQuery(query.id)
                    gateway.editText(query.message.chatId, query.message.id, detail(plugin, prefix),
                        TronCommandMedia.markup(listOf(TronCommandMedia.button("❰ Back to plugins", "help:page:$page"))))
                }
            }
        }
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
        AutomationCommand("animegif", "Fetch and send an anime GIF for an action.", "animegif <action>")
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
                    val artist = result.optString("artist_name").ifBlank { "Unknown Artist" }
                    if (url.isBlank()) return@run "No image found."
                    val gateway = context.telegram ?: return@run "Telegram session is unavailable."
                    gateway.sendPhotoUrl(context.message.chatId, url, artist) { response ->
                        if (response is TdApi.Error) context.reply("Unable to send image: ${response.message}")
                    }
                    "Sending anime image…"
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
                    val anime = result.optString("anime_name").ifBlank { action }
                    if (url.isBlank()) return@run "No GIF found."
                    val gateway = context.telegram ?: return@run "Telegram session is unavailable."
                    gateway.sendAnimationUrl(context.message.chatId, url, anime) { response ->
                        if (response is TdApi.Error) context.reply("Unable to send GIF: ${response.message}")
                    }
                    "Sending $action GIF…"
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
                    val gateway = context.telegram ?: return@run "Telegram session is unavailable."
                    gateway.sendToSavedMessages("Correct answer:\n$correct") { response ->
                        if (response is TdApi.Error) context.reply("Unable to save the answer: ${response.message}")
                    }
                    val formatted = options.mapIndexed { i, value -> "${i + 1}. $value" }.joinToString("\n")
                    "Trivia question:\n$question\n\n$formatted\n\nAnswer saved in your Saved Messages."
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
    override val commands = listOf(AutomationCommand("weather", "Show current weather for a city.", "weather <city>"), AutomationCommand("wtr", "Alias for weather.", "wtr <city>"))
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
    override val commands = listOf(AutomationCommand("qrcode", "Generate and send a QR code for text or a URL.", "qrcode <text>"))
    override fun onCommand(context: AutomationContext) {
        val value = context.arguments.joinToString(" ").trim()
        if (value.isBlank()) return context.reply("Usage: .qrcode <text>")
        val url = "https://api.qrserver.com/v1/create-qr-code/?size=512x512&data=" + PluginHttp.encode(value)
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        context.reply("Generating QR code…")
        gateway.sendPhotoUrl(context.message.chatId, url, "QR code") { response ->
            if (response is TdApi.Error) context.reply("Unable to send QR code: ${response.message}")
        }
    }
}

class NotesAutomationPlugin(private val preferences: SharedPreferences) : AutomationPlugin {
    override val id = "userbot:notes"
    override val commands = listOf(
        AutomationCommand("save", "Save text or a replied Telegram message under a name.", "save <name> <text> (or reply with .save <name>)"),
        AutomationCommand("getnote", "Retrieve a saved text or media note.", "getnote <name>"),
        AutomationCommand("notes", "List saved note names.", "notes"),
        AutomationCommand("notelist", "Alias for saved note names.", "notelist"),
        AutomationCommand("clear", "Delete a saved note.", "clear <name>")
    )

    override fun onCommand(context: AutomationContext) {
        val name = context.arguments.firstOrNull().orEmpty()
        when (context.command) {
            "save" -> {
                if (name.isBlank()) return context.reply("Usage: .save <name> <text> or reply to a message with .save <name>")
                val source = context.message.tdMessage
                val replied = source?.let { context.telegram?.repliedMessage(it) }
                val value = context.arguments.drop(1).joinToString(" ").trim()
                    .ifBlank { replied?.let(::extractText).orEmpty() }

                if (value.isNotBlank()) {
                    preferences.edit()
                        .putString("note.$name", value)
                        .remove("noteMedia.$name.chat")
                        .remove("noteMedia.$name.message")
                        .apply()
                } else if (replied != null) {
                    preferences.edit()
                        .remove("note.$name")
                        .putLong("noteMedia.$name.chat", replied.chatId)
                        .putLong("noteMedia.$name.message", replied.id)
                        .apply()
                } else {
                    return context.reply("Provide note text or reply to a Telegram message to save it.")
                }
                context.reply("Saved note: $name")
            }
            "getnote" -> {
                if (name.isBlank()) return context.reply("Usage: .getnote <name>")
                val text = preferences.getString("note.$name", null)
                if (text != null) return context.reply(text)
                val chatId = preferences.getLong("noteMedia.$name.chat", 0L)
                val messageId = preferences.getLong("noteMedia.$name.message", 0L)
                if (chatId == 0L || messageId == 0L) return context.reply("No note named $name.")
                val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
                gateway.send(
                    TdApi.ForwardMessages(context.message.chatId, null, chatId, longArrayOf(messageId), null, true, false)
                ) { result ->
                    if (result is TdApi.Error) context.reply("Unable to restore note: ${result.message}")
                }
            }
            "notes", "notelist" -> {
                val names = preferences.all.keys.mapNotNull { key ->
                    when {
                        key.startsWith("noteMedia.") -> key.removePrefix("noteMedia.").substringBefore(".")
                        key.startsWith("note.") -> key.removePrefix("note.")
                        else -> null
                    }
                }.distinct().sorted()
                context.reply(names.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "No saved notes.")
            }
            "clear" -> {
                if (name.isBlank()) return context.reply("Usage: .clear <name>")
                preferences.edit()
                    .remove("note.$name")
                    .remove("noteMedia.$name.chat")
                    .remove("noteMedia.$name.message")
                    .apply()
                context.reply("Deleted note: $name")
            }
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

class AfkAutomationPlugin(
    private val commandPrefix: () -> String = { "." },
    private val ownerId: () -> Long? = { null },
    private val ownerUsername: () -> String? = { null }
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
        if (!context.isCommandEnabled("userbot:afk", "afk") || !active) return
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
        val formatted = (tdMessage.content as? TdApi.MessageText)?.text
        val username = ownerUsername()?.removePrefix("@")?.lowercase(Locale.ROOT)
        val isMention = formatted?.entities?.any { entity ->
            val mentionName = entity.type as? TdApi.TextEntityTypeMentionName
            val mentionById = mentionName != null && expectedOwnerId != null && mentionName.userId == expectedOwnerId
            val mentionByUsername = entity.type is TdApi.TextEntityTypeMention &&
                username != null && formatted.text.substring(
                    entity.offset.coerceAtLeast(0).coerceAtMost(formatted.text.length),
                    (entity.offset + entity.length).coerceAtLeast(0).coerceAtMost(formatted.text.length)
                ).equals("@$username", true)
            mentionById || mentionByUsername
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
