package com.vikram.trondroid.telegram

import android.content.Context
import com.vikram.trondroid.data.BotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps Telegram Bot API sessions alive using long polling. Each bot token is isolated
 * under the userbot profile that owns it.
 */
object TelegramBotRuntime {
    private val workers = ConcurrentHashMap<String, BotWorker>()
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
                            store.setBotName(profileId, name)
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
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    if (!preferences.getBoolean("auto_reconnect_bots", true)) break
                    try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
                }
            }
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
