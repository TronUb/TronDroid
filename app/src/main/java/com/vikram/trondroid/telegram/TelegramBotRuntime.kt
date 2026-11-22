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
                            Thread.sleep(10_000)
                            continue
                        }
                    }
                    val updates = request("getUpdates?timeout=25&offset=$offset")
                    if (!updates.optBoolean("ok")) {
                        if (!preferences.getBoolean("auto_reconnect_bots", true)) break
                        Thread.sleep(5_000)
                        continue
                    }
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
                    try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
                }
            }
        }

        private fun handleUpdate(update: JSONObject) {
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
                else -> null
            }
            if (builtInPluginId != null && !pluginSettings.commandEnabled(builtInPluginId, token)) return
            val builtInReply = when (token) {
                "start" -> "Welcome! TronDroid bot is online. Send /help to see available commands."
                "help" -> {
                    val builtins = listOf(
                        "start" to "Start the bot",
                        "help" to "Show this help",
                        "ping" to "Check bot response",
                        "id" to "Show user and chat IDs",
                        "quote" to "Show a random quote",
                        "broadcast" to "Broadcast an owner announcement"
                    ).filter { (name, _) ->
                        val pluginId = when (name) {
                            "start" -> "bot:start"
                            "help" -> "bot:help"
                            "ping" -> "bot:ping"
                            "id", "quote" -> "bot:utils"
                            else -> "bot:broadcast"
                        }
                        pluginSettings.commandEnabled(pluginId, name)
                    }.joinToString("\n") { "/" + it.first + " — " + it.second }
                    val external = externalPlugins.flatMap { plugin ->
                        plugin.commands.filter { pluginSettings.commandEnabled(plugin.id, it.name) }
                            .map { "/" + it.name + " — " + it.description }
                    }.joinToString("\n")
                    "Available commands:\n" + listOf(builtins, external).filter { it.isNotBlank() }.joinToString("\n")
                }
                "ping" -> "Pong! Telegram Bot API is responding."
                "quote" -> listOf(
                    "“The world is not perfect. But it is there for us, doing the best it can.” — Roy Mustang",
                    "“Whatever you lose, you'll find it again. But what you throw away you'll never get back.” — Kenshin Himura",
                    "“If you don't take risks, you can't create a future.” — Monkey D. Luffy",
                    "“A lesson without pain is meaningless.” — Edward Elric",
                    "“Knowing you're different is only the beginning.” — Natsuki Subaru"
                ).random()
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

        private fun sendReply(chatId: Long, reply: String) {
            request("sendMessage?chat_id=" + chatId + "&text=" + java.net.URLEncoder.encode(reply.take(4000), Charsets.UTF_8.name()))
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
