package com.vikram.trondroid.telegram

import android.content.Context
import com.vikram.trondroid.data.BotStore
import com.vikram.trondroid.plugins.ExternalPluginLoader
import com.vikram.trondroid.plugins.PluginSettings
import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class BotPluginCommand(val name: String, val description: String, val usage: String = name)
interface BotCommandPlugin {
    val id: String
    val commands: List<BotPluginCommand>
    fun onCommand(command: String, arguments: List<String>, chatId: Long, reply: (String) -> Unit)
}

/**
 * Keeps Telegram Bot API sessions alive using long polling. Each bot token is isolated
 * under the userbot profile that owns it.
 */
object TelegramBotRuntime {
    private val workers = ConcurrentHashMap<String, BotWorker>()
    private val externalPlugins = CopyOnWriteArrayList<BotCommandPlugin>()
    private val numberGameScores = ConcurrentHashMap<String, Int>()

    fun loadExternalPlugins(context: Context, uri: Uri): Int {
        val plugins = ExternalPluginLoader.loadFromUri(context.applicationContext, uri).filterIsInstance<BotCommandPlugin>()
        require(plugins.isNotEmpty()) { "The selected file contains no BotCommandPlugin implementation." }
        plugins.forEach { plugin -> externalPlugins.removeAll { it.id == plugin.id }; externalPlugins += plugin }
        return plugins.size
    }
    private val mutableNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val botNames: StateFlow<Map<String, String>> = mutableNames.asStateFlow()

    @Synchronized
    fun reconcile(context: Context) {
        val app = context.applicationContext
        val store = BotStore(app)
        val eligible = store.all().filter { it.botEnabled }
            .mapNotNull { profile ->
                val token = store.customCredentials(profile.id).entries.firstOrNull { it.key.equals("BOT_TOKEN", ignoreCase = true) }?.value?.trim()
                if (token.isNullOrBlank()) null else profile.id to token
            }.toMap()

        workers.keys.toList().filter { it !in eligible }.forEach { id ->
            workers.remove(id)?.stopWorker()
        }
        eligible.forEach { (id, token) ->
            val current = workers[id]
            if (current == null || !current.isRunning() || !current.usesToken(token)) {
                workers[id]?.stopWorker()
                workers[id] = BotWorker(app, id, token).also { it.start() }
            }
        }
    }

    fun activeCount(): Int = workers.values.count { it.isRunning() }

    fun sendUserbotHelpDex(profileId: String, chatId: Long): Boolean =
        workers[profileId]?.sendUserbotHelpDex(chatId) ?: false

    fun sendUserbotAlive(profileId: String, chatId: Long, caption: String): Boolean =
        workers[profileId]?.sendUserbotAlive(chatId, caption) ?: false

    fun sendUserbotQuote(profileId: String, chatId: Long): Boolean =
        workers[profileId]?.sendUserbotQuote(chatId) ?: false

    @Synchronized
    fun stopAll() {
        workers.values.forEach { it.stopWorker() }
        workers.clear()
    }

    private class BotWorker(
        context: Context,
        private val profileId: String,
        private val token: String
    ) : Thread("TronDroid-Bot-$profileId") {
        private val app = context.applicationContext
        private val running = AtomicBoolean(true)
        private val store = BotStore(app)
        private val pluginSettings = PluginSettings(app)
        private val preferences = app.getSharedPreferences("trondroid.settings", Context.MODE_PRIVATE)

        fun usesToken(value: String) = token == value
        fun isRunning() = running.get() && isAlive
        fun stopWorker() {
            running.set(false)
            interrupt()
        }

        override fun run() {
            var offset = 0L
            var identityLoaded = false
            var retryDelayMs = 3_000L
            while (running.get()) {
                try {
                    if (!identityLoaded) {
                        val me = request("getMe")
                        if (me.optBoolean("ok")) {
                            val result = me.optJSONObject("result") ?: JSONObject()
                            val name = result.optString("first_name").ifBlank {
                                result.optString("username").ifBlank { "Telegram bot" }
                            }
                            val botId = result.optLong("id")
                            val photo = if (botId > 0) runCatching { loadBotPhoto(botId) }.getOrNull() else null
                            store.setBotIdentity(profileId, name, result.optString("username").takeIf { it.isNotBlank() }, botId.takeIf { it > 0 }, photo)
                            mutableNames.value = mutableNames.value + (profileId to name)
                            identityLoaded = true
                        } else {
                            if (!preferences.getBoolean("auto_reconnect_bots", true)) break
                            Thread.sleep(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
                            continue
                        }
                    }
                    // Telegram remembers allowed_updates between long-poll requests.
                    // Explicitly include inline_query so /setinline results are actually delivered.
                    val allowedUpdates = encode("""["message","callback_query","inline_query"]""")
                    val updates = request("getUpdates?timeout=25&offset=$offset&allowed_updates=$allowedUpdates")
                    if (!updates.optBoolean("ok")) {
                        if (!preferences.getBoolean("auto_reconnect_bots", true)) break
                        Thread.sleep(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
                        continue
                    }
                    retryDelayMs = 3_000L
                    val results = updates.optJSONArray("result") ?: continue
                    for (index in 0 until results.length()) {
                        val update = results.optJSONObject(index) ?: continue
                        offset = maxOf(offset, update.optLong("update_id") + 1L)
                        handleUpdate(update)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    if (!preferences.getBoolean("auto_reconnect_bots", true)) break
                    try { Thread.sleep(retryDelayMs); retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L) } catch (_: InterruptedException) { break }
                }
            }
        }

        private val workerStartedAt = android.os.SystemClock.elapsedRealtime()
        private val tronBotImage = "https://raw.githubusercontent.com/TronUb/Tron/master/main/others/resources/images/tron-square.png"
        @Volatile private var lastUserbotAliveCaption: String? = null

        private fun encode(value: String) =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun button(text: String, data: String) =
            JSONObject().put("text", text).put("callback_data", data)

        private fun helpPlugins(userbot: Boolean = false) =
            (if (userbot) com.vikram.trondroid.plugins.PluginCatalog.userbot
             else com.vikram.trondroid.plugins.PluginCatalog.bot)
                .filter { pluginSettings.enabled(it.id) }
                .filter { plugin -> plugin.commands.any { pluginSettings.commandEnabled(plugin.id, it.name) } }
                .sortedBy { it.title.lowercase() }

        private fun helpKeyboard(page: Int, userbot: Boolean = false): JSONObject {
            val plugins = helpPlugins(userbot)
            val callbackPrefix = if (userbot) "ubhelp" else "bothelp"
            val pageSize = 8
            val pages = ((plugins.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            val current = page.coerceIn(0, pages - 1)
            val rows = org.json.JSONArray()
            plugins.drop(current * pageSize).take(pageSize).chunked(2).forEach { group ->
                val row = org.json.JSONArray()
                group.forEach { plugin ->
                    row.put(button("🧩 ${plugin.title}", "$callbackPrefix:detail:${plugin.id}:$current"))
                }
                rows.put(row)
            }
            val navigation = org.json.JSONArray()
            if (current > 0) navigation.put(button("❰ Prev", "$callbackPrefix:page:${current - 1}"))
            navigation.put(button("Home", "$callbackPrefix:home"))
            if (current < pages - 1) navigation.put(button("Next ❱", "$callbackPrefix:page:${current + 1}"))
            rows.put(navigation)
            return JSONObject().put("inline_keyboard", rows)
        }

        private fun helpCaption(userbot: Boolean = false): String {
            val plugins = helpPlugins(userbot)
            val commands = plugins.sumOf { plugin ->
                plugin.commands.count { pluginSettings.commandEnabled(plugin.id, it.name) }
            }
            return (if (userbot) "✦ **TRON USERBOT HELPDEx**\n\n" else "✦ **TRON BOT HELPDEx**\n\n") +
                "Explore **${plugins.size} plugins** and **$commands commands**. " +
                "Choose a plugin below to view its commands, descriptions and usage."
        }

        private fun pluginCaption(id: String, userbot: Boolean = false): String {
            val plugin = helpPlugins(userbot).firstOrNull { it.id == id } ?: return "This plugin is no longer available."
            return buildString {
                append("🧩 **${plugin.title}**\n")
                append("${plugin.summary}\n\n")
                val prefix = if (userbot) pluginSettings.handler() else "/"
                plugin.commands.filter { pluginSettings.commandEnabled(plugin.id, it.name) }.forEach { command ->
                    append("✦ **${prefix}${command.name}**\n")
                    append("${command.description}\n")
                    append("**Usage:** `${prefix}${command.usage}`\n")
                    append("**Example:** `${prefix}${command.example}`\n\n")
                }
            }.take(950)
        }

        private fun sendBotPhoto(chatId: Long, caption: String, markup: JSONObject): JSONObject =
            request("sendPhoto?chat_id=$chatId&photo=${encode(tronBotImage)}" +
                "&caption=${encode(caption)}&parse_mode=Markdown&reply_markup=${encode(markup.toString())}")

        private fun switchInlineButton(label: String, query: String): JSONObject =
            JSONObject().put("text", label).put("switch_inline_query_current_chat", query)

        private fun inlineLauncher(query: String): JSONObject =
            JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                org.json.JSONArray().put(switchInlineButton(
                    if (query == "help") "Browse Helpdex inline" else "Show Alive inline",
                    query
                ))
            ))

        fun sendUserbotHelpDex(chatId: Long): Boolean = runCatching {
            sendBotText(chatId,
                "✦ **TRON USERBOT HELPDEX**\n\nOpen the Helpdex in any chat using inline mode. Browse plugins, commands and usage without sending a command message.",
                inlineLauncher("help")).optBoolean("ok")
        }.getOrDefault(false)

        fun sendUserbotAlive(chatId: Long, caption: String): Boolean = runCatching {
            lastUserbotAliveCaption = caption
            app.getSharedPreferences("trondroid.bot.runtime", Context.MODE_PRIVATE)
                .edit().putString("userbot_alive_caption.$profileId", caption).apply()
            sendBotText(chatId, caption, inlineLauncher("alive")).optBoolean("ok")
        }.getOrDefault(false)

        private fun currentUserbotAliveCaption(): String =
            lastUserbotAliveCaption
                ?: app.getSharedPreferences("trondroid.bot.runtime", Context.MODE_PRIVATE)
                    .getString("userbot_alive_caption.$profileId", null)
                ?: "Userbot status is not cached yet. Run .ialive once from this account to populate the live TDLib and uptime details."

        fun sendUserbotQuote(chatId: Long): Boolean = runCatching {
            sendBotText(chatId, "Open Tron quotes in inline mode.", inlineLauncher("quote")).optBoolean("ok")
        }.getOrDefault(false)

        private fun botAliveCaption(): String {
            val elapsed = ((android.os.SystemClock.elapsedRealtime() - workerStartedAt) / 1000).coerceAtLeast(0)
            val uptime = "%dd %02dh %02dm %02ds".format(elapsed / 86400, (elapsed / 3600) % 24, (elapsed / 60) % 60, elapsed % 60)
            val version = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown")
            return "⦿ **TronDroid Bot is online**\n\n" +
                "⟜ **TronDroid:** `$version`\n" +
                "⟜ **Kotlin:** `${KotlinVersion.CURRENT}`\n" +
                "⟜ **Transport:** `Telegram Bot API`\n" +
                "⟜ **Runtime:** `Android / JVM`\n" +
                "⟜ **Uptime:** `$uptime`"
        }

        private fun handleCallback(callback: JSONObject) {
            val callbackId = callback.optString("id")
            val data = callback.optString("data")
            val message = callback.optJSONObject("message")
            val inlineMessageId = callback.optString("inline_message_id")
            if (message == null && inlineMessageId.isBlank()) return
            val chatId = message?.optJSONObject("chat")?.optLong("id") ?: 0L
            val messageId = message?.optLong("message_id") ?: 0L
            request("answerCallbackQuery?callback_query_id=${encode(callbackId)}")
            val hasPhoto = message?.optJSONArray("photo") != null
            fun edit(caption: String, markup: JSONObject? = null) {
                val markupArg = markup?.let { "&reply_markup=${encode(it.toString())}" }.orEmpty()
                if (inlineMessageId.isNotBlank()) {
                    request("editMessageText?inline_message_id=${encode(inlineMessageId)}&text=${encode(caption)}&parse_mode=Markdown$markupArg")
                } else {
                    val method = if (hasPhoto) "editMessageCaption" else "editMessageText"
                    val field = if (hasPhoto) "caption" else "text"
                    request("$method?chat_id=$chatId&message_id=$messageId&$field=${encode(caption)}&parse_mode=Markdown$markupArg")
                }
            }
            when {
                data == "bothelp:home" -> edit(helpCaption(), helpKeyboard(0))
                data.startsWith("bothelp:page:") -> {
                    val page = data.substringAfterLast(':').toIntOrNull() ?: 0
                    edit(helpCaption(), helpKeyboard(page))
                }
                data.startsWith("bothelp:detail:") -> {
                    val body = data.removePrefix("bothelp:detail:")
                    val page = body.substringAfterLast(':').toIntOrNull() ?: 0
                    val pluginId = body.substringBeforeLast(':')
                    val back = JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                        org.json.JSONArray().put(button("❰ Back to plugins", "bothelp:page:$page"))
                    ))
                    edit(pluginCaption(pluginId), back)
                }
                data == "ubhelp:home" -> edit(helpCaption(userbot = true), helpKeyboard(0, userbot = true))
                data.startsWith("ubhelp:page:") -> {
                    val page = data.substringAfterLast(':').toIntOrNull() ?: 0
                    edit(helpCaption(userbot = true), helpKeyboard(page, userbot = true))
                }
                data.startsWith("ubhelp:detail:") -> {
                    val body = data.removePrefix("ubhelp:detail:")
                    val page = body.substringAfterLast(':').toIntOrNull() ?: 0
                    val pluginId = body.substringBeforeLast(':')
                    val back = JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                        org.json.JSONArray().put(button("❰ Back to plugins", "ubhelp:page:$page"))
                    ))
                    edit(pluginCaption(pluginId, userbot = true), back)
                }
                data == "ubalive:refresh" -> edit(currentUserbotAliveCaption(), JSONObject().put("inline_keyboard",
                    org.json.JSONArray().apply {
                        put(org.json.JSONArray().put(button("Refresh", "ubalive:refresh")))
                        put(org.json.JSONArray().put(button("Plugins", "ubhelp:home")).put(button("Close", "ubalive:close")))
                    }))
                data == "ubalive:close" -> edit("Tron Inline Status — menu closed.")
                data == "botalive:refresh" -> edit(botAliveCaption(), JSONObject().put("inline_keyboard",
                    org.json.JSONArray().put(org.json.JSONArray().put(button("Refresh", "botalive:refresh")))))
                data == "botalive:close" -> edit("TronDroid bot status menu closed.")
                data == "botquote:more" -> {
                    val quotes = listOf(
                        "“A lesson without pain is meaningless.” — Edward Elric",
                        "“If you don't take risks, you can't create a future.” — Monkey D. Luffy",
                        "“Whatever you lose, you'll find it again.” — Kenshin Himura",
                        "“Knowing you're different is only the beginning.” — Natsuki Subaru"
                    )
                    edit("❝ ${quotes.random()} ❞\n\n**Tron Anime Quotes**",
                        JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                            org.json.JSONArray().put(button("More", "botquote:more")))))
                }
            }
        }

        private fun inlineArticle(
            id: String, title: String, description: String, message: String, markup: JSONObject? = null
        ): JSONObject {
            val content = JSONObject().put("message_text", message.take(4000)).put("parse_mode", "Markdown")
            return JSONObject().put("type", "article").put("id", id).put("title", title.take(64))
                .put("description", description.take(256)).put("input_message_content", content)
                .apply { if (markup != null) put("reply_markup", markup) }
        }

        private fun userbotAliveKeyboard(): JSONObject =
            JSONObject().put("inline_keyboard", org.json.JSONArray().apply {
                put(org.json.JSONArray().put(button("Refresh", "ubalive:refresh")))
                put(org.json.JSONArray().put(button("Plugins", "ubhelp:home")).put(button("Close", "ubalive:close")))
            })

        private fun handleInlineQuery(query: JSONObject) {
            val queryId = query.optString("id")
            val raw = query.optString("query").trim()
            val normalized = raw.removePrefix("/").removePrefix(".").removePrefix("!")
                .substringBefore('@').trim()
            val command = normalized.substringBefore(' ').lowercase()
            val argument = normalized.substringAfter(' ', "").trim()
            val results = org.json.JSONArray()
            when (command) {
                "", "help", "ihelp", "helpdex", "plugins" -> {
                    val page = argument.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    results.put(inlineArticle("userbot-help", "Tron Userbot Helpdex",
                        "Browse enabled userbot plugins and command usage",
                        helpCaption(userbot = true), helpKeyboard(page, userbot = true)))
                    if (command.isEmpty()) {
                        results.put(inlineArticle("userbot-alive", "Userbot status",
                            "Show userbot uptime and runtime information",
                            lastUserbotAliveCaption ?: botAliveCaption(), userbotAliveKeyboard()))
                        results.put(inlineArticle("userbot-quote", "Tron quote", "Share an anime quote",
                            "❝ “If you don't take risks, you can't create a future.” — Monkey D. Luffy ❞",
                            inlineLauncher("quote")))
                    }
                }
                "alive", "ialive", "stats", "ialive" -> results.put(inlineArticle("userbot-alive", "Userbot status",
                    "Uptime and runtime information", lastUserbotAliveCaption ?: botAliveCaption(),
                    userbotAliveKeyboard()))
                "quote" -> results.put(inlineArticle("userbot-quote", "Tron quote", "Share an anime quote",
                    "❝ “A lesson without pain is meaningless.” — Edward Elric ❞", inlineLauncher("quote")))
                "ping" -> results.put(inlineArticle("userbot-ping", "Pong!",
                    "Check whether the companion bot is responding", "Pong! Telegram Bot API is responding."))
                else -> {
                    val pluginQuery = if (command == "plugin") argument else normalized
                    val plugin = helpPlugins(userbot = true).firstOrNull {
                        it.id.equals(pluginQuery, true) || it.title.equals(pluginQuery, true) ||
                            it.title.contains(pluginQuery, true)
                    }
                    if (plugin != null) results.put(inlineArticle("plugin-${plugin.id}", plugin.title,
                        plugin.summary, pluginCaption(plugin.id, userbot = true),
                        JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                            org.json.JSONArray().put(button("Back to plugins", "ubhelp:home"))))))
                }
            }
            if (results.length() == 0) {
                results.put(inlineArticle("userbot-help-fallback", "Tron Userbot Helpdex",
                    "No exact match. Browse all available plugins and commands.",
                    helpCaption(userbot = true), helpKeyboard(0, userbot = true)))
            }
            val response = request("answerInlineQuery?inline_query_id=${encode(queryId)}" +
                "&results=${encode(results.toString())}&cache_time=0&is_personal=true")
            if (!response.optBoolean("ok")) {
                android.util.Log.w("TronDroidBot", "Inline query failed: ${response.optString("description")}")
            }
        }

        private fun handleUpdate(update: JSONObject) {
            update.optJSONObject("inline_query")?.let { handleInlineQuery(it); return }
            update.optJSONObject("callback_query")?.let { handleCallback(it); return }
            val message = update.optJSONObject("message") ?: return
            val text = message.optString("text").trim()
            val chat = message.optJSONObject("chat") ?: return
            val chatId = chat.optLong("id")
            if (!text.startsWith("/")) {
                handleNumberGame(message, chat, text)
                return
            }
            val token = text.substringBefore(' ').substringBefore('@').removePrefix("/").lowercase()
            if (token == "start" && chat.optString("type") == "private") rememberStartedUser(chatId)
            val builtInPluginId = when (token) {
                "start" -> "bot:start"
                "help" -> "bot:help"
                "ping" -> "bot:ping"
                "quote", "id" -> "bot:utils"
                "broadcast" -> "bot:broadcast"
                "alive", "ialive", "stats" -> "bot:alive"
                else -> null
            }
            if (builtInPluginId != null && !pluginSettings.commandEnabled(builtInPluginId, token)) return
            val builtInReply = when (token) {
                "start" -> {
                    val markup = JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                        org.json.JSONArray().put(button("📜 Open Helpdex", "bothelp:home"))))
                    sendBotPhoto(chatId, "👋 **Welcome to TronDroid!**\n\nYour Telegram automation assistant is ready. Open the Helpdex to explore available plugins and commands.", markup)
                    return
                }
                "help" -> {
                    sendBotPhoto(chatId, helpCaption(), helpKeyboard(0))
                    return
                }
                "alive", "ialive", "stats" -> {
                    val markup = JSONObject().put("inline_keyboard", org.json.JSONArray().apply {
                        put(org.json.JSONArray().put(button("Refresh", "botalive:refresh")))
                        put(org.json.JSONArray().put(button("Help", "bothelp:home")).put(button("Close", "botalive:close")))
                    })
                    sendBotPhoto(chatId, botAliveCaption(), markup)
                    return
                }
                "ping" -> "Pong! Telegram Bot API is responding."
                "quote" -> {
                    val quote = listOf(
                        "“The world is not perfect. But it is there for us, doing the best it can.” — Roy Mustang",
                        "“Whatever you lose, you'll find it again. But what you throw away you'll never get back.” — Kenshin Himura",
                        "“If you don't take risks, you can't create a future.” — Monkey D. Luffy",
                        "“A lesson without pain is meaningless.” — Edward Elric",
                        "“Knowing you're different is only the beginning.” — Natsuki Subaru"
                    ).random()
                    val markup = JSONObject().put("inline_keyboard", org.json.JSONArray().put(
                        org.json.JSONArray().put(button("More", "botquote:more"))))
                    sendBotText(chatId, "❝ $quote ❞", markup)
                    return
                }
                "broadcast" -> {
                    val senderId = message.optJSONObject("from")?.optLong("id") ?: 0L
                    val configuredOwner = store.customCredentials(profileId).entries
                        .firstOrNull { it.key.equals("OWNER_ID", true) }?.value?.toLongOrNull()
                        ?: preferences.getLong("bot_owner_id.$profileId", 0L)
                    val body = text.substringAfter(' ', "").trim()
                    if (configuredOwner <= 0L || senderId != configuredOwner) {
                        "This command is restricted to the configured bot owner. Set OWNER_ID in this bot profile's credentials."
                    } else if (body.isBlank()) {
                        "Usage: /broadcast <message>"
                    } else {
                        val sent = broadcastToStartedUsers(body)
                        "Broadcast complete. Sent: $sent"
                    }
                }
                "id" -> {
                    val sender = message.optJSONObject("from") ?: JSONObject()
                    val replySender = message.optJSONObject("reply_to_message")?.optJSONObject("from")
                    "User: " + sender.optString("first_name") + " (" + sender.optLong("id") + ")\nChat: " +
                        chat.optString("title", chat.optString("type")) + " (" + chat.optLong("id") + ")" +
                        (replySender?.let { "\nReplied user: " + it.optString("first_name") + " (" + it.optLong("id") + ")" } ?: "")
                }
                else -> null
            }
            if (builtInReply != null) {
                sendReply(chatId, builtInReply)
                return
            }
            val plugin = externalPlugins.firstOrNull { candidate ->
                candidate.commands.any { it.name.equals(token, true) && pluginSettings.commandEnabled(candidate.id, it.name) }
            } ?: return
            runCatching {
                val arguments = text.substringAfter(' ', "").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                plugin.onCommand(token, arguments, chatId) { response -> sendReply(chatId, response) }
            }.onFailure { android.util.Log.e("TronDroidBot", "External plugin command failed", it) }
        }

        private fun rememberStartedUser(chatId: Long) {
            val key = "bot_started_ids.$profileId"
            val ids = app.getSharedPreferences("trondroid.bot.runtime", Context.MODE_PRIVATE)
                .getString(key, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toMutableSet()
            ids.add(chatId)
            app.getSharedPreferences("trondroid.bot.runtime", Context.MODE_PRIVATE)
                .edit().putString(key, ids.joinToString(",")).apply()
        }

        private fun broadcastToStartedUsers(text: String): Int {
            val key = "bot_started_ids.$profileId"
            val ids = app.getSharedPreferences("trondroid.bot.runtime", Context.MODE_PRIVATE)
                .getString(key, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            var sent = 0
            ids.forEach { userId ->
                runCatching {
                    val response = request(
                        "sendMessage?chat_id=$userId&text=" +
                            java.net.URLEncoder.encode(text.take(4000), Charsets.UTF_8.name())
                    )
                    if (response.optBoolean("ok")) sent++
                }
            }
            return sent
        }

        private fun handleNumberGame(message: JSONObject, chat: JSONObject, text: String) {
            if (!pluginSettings.commandEnabled("bot:botfun", "numbers")) return
            if (chat.optString("type") !in setOf("group", "supergroup")) return
            if (!text.matches(Regex("[+-][1-9]"))) return
            val reply = message.optJSONObject("reply_to_message") ?: return
            val target = reply.optJSONObject("from") ?: return
            if (target.optBoolean("is_bot")) return
            val userId = target.optLong("id")
            if (userId <= 0) return
            val key = "$profileId:" + chat.optLong("id") + ":" + userId
            val delta = text.drop(1).toInt() * if (text.startsWith("+")) 1 else -1
            val score = numberGameScores.compute(key) { _, previous -> (previous ?: 0) + delta } ?: delta
            sendReply(chat.optLong("id"), target.optString("first_name") + ": $score increments")
        }

        private fun loadBotPhoto(botId: Long): String? {
            val photos = request("getUserProfilePhotos?user_id=$botId&limit=1")
            val first = photos.optJSONObject("result")?.optJSONArray("photos")?.optJSONArray(0) ?: return null
            val size = first.optJSONObject(first.length() - 1) ?: return null
            val fileId = size.optString("file_id")
            if (fileId.isBlank()) return null
            val fileInfo = request("getFile?file_id=" + java.net.URLEncoder.encode(fileId, Charsets.UTF_8.name()))
            val filePath = fileInfo.optJSONObject("result")?.optString("file_path").orEmpty()
            if (filePath.isBlank()) return null
            val destination = File(app.filesDir, "bot-avatars/$profileId.jpg")
            destination.parentFile?.mkdirs()
            val connection = URL("https://api.telegram.org/file/bot$token/$filePath").openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            return try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                destination.absolutePath
            } finally {
                connection.disconnect()
            }
        }

        private fun sendBotText(chatId: Long, text: String, markup: JSONObject? = null): JSONObject {
            val markupArg = markup?.let { "&reply_markup=${encode(it.toString())}" }.orEmpty()
            return request("sendMessage?chat_id=$chatId&text=${encode(text.take(4000))}&parse_mode=Markdown$markupArg")
        }

        private fun sendReply(chatId: Long, reply: String) {
            sendBotText(chatId, reply)
        }

        private fun request(method: String): JSONObject {
            val connection = URL("https://api.telegram.org/bot$token/$method")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 35_000
            connection.setRequestProperty("Accept", "application/json")
            return try {
                val stream = if (connection.responseCode in 200..299) connection.inputStream
                    else connection.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
