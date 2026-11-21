package com.vikram.trondroid.automation

import android.content.SharedPreferences
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpamAutomationPlugin : AutomationPlugin {
    override val id = "userbot:spam"
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TronDroid-Spam").apply { isDaemon = true }
    }
    override val commands = listOf(
        AutomationCommand("spam", "Repeat text or a replied message (maximum 20 sends).", "spam <1-20> <text>"),
        AutomationCommand("dspam", "Repeat text with a delay (maximum 20 sends).", "dspam <1-20> <delay-seconds> <text>")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val delayed = context.command == "dspam"
        val count = context.arguments.firstOrNull()?.toIntOrNull()
        if (count == null || count !in 1..20) {
            context.reply(if (delayed) "Usage: .dspam <1-20> <delay-seconds> <text>" else "Usage: .spam <1-20> <text>")
            return
        }
        val delaySeconds = if (delayed) context.arguments.getOrNull(1)?.toLongOrNull() else 0L
        if (delayed && (delaySeconds == null || delaySeconds !in 1L..60L)) {
            context.reply("Delay must be between 1 and 60 seconds.")
            return
        }
        val text = context.arguments.drop(if (delayed) 2 else 1).joinToString(" ").trim()
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
        val payload = text.ifBlank { (replied?.content as? TdApi.MessageText)?.text?.text.orEmpty() }
        if (payload.isBlank()) {
            context.reply("Provide text or reply to a text message.")
            return
        }
        context.reply("Queued $count message(s).")
        repeat(count) { index ->
            scheduler.schedule({
                val input = TdApi.InputMessageText(TdApi.FormattedText(payload.take(4000), emptyArray()), null, true)
                gateway.send(TdApi.SendMessage(context.message.chatId, null, null, null, null, input)) { result ->
                    if (result is TdApi.Error) context.reply("Send stopped: ${result.message}")
                }
            }, if (delayed) (index * (delaySeconds ?: 0L)) else 0L, TimeUnit.SECONDS)
        }
    }
}

class DigitalVaultAutomationPlugin(private val prefs: SharedPreferences) : AutomationPlugin {
    override val id = "userbot:dv"
    override val commands = listOf(
        AutomationCommand("setdv", "Save a private key/value entry.", "setdv <key> <value>"),
        AutomationCommand("getdv", "Retrieve a saved entry.", "getdv <key>"),
        AutomationCommand("deldv", "Delete a saved entry.", "deldv <key>"),
        AutomationCommand("listdv", "List saved entry names.", "listdv"),
        AutomationCommand("alldv", "Export all saved entries.", "alldv"),
        AutomationCommand("pm", "Send text to your Telegram Saved Messages.", "pm <text>")
    )
    override fun onCommand(context: AutomationContext) {
        val key = context.arguments.firstOrNull()?.lowercase()?.take(64).orEmpty()
        when (context.command) {
            "setdv" -> {
                if (key.isBlank() || context.arguments.size < 2) return context.reply("Usage: .setdv <key> <value>")
                val value = context.arguments.drop(1).joinToString(" ").take(4000)
                prefs.edit().putString("entry:$key", value).apply()
                context.reply("Saved '$key' in the local digital vault.")
            }
            "getdv" -> {
                if (key.isBlank()) return context.reply("Usage: .getdv <key>")
                context.reply(prefs.getString("entry:$key", null)?.let { "$key\n$it" } ?: "No entry named '$key'.")
            }
            "deldv" -> {
                if (key.isBlank()) return context.reply("Usage: .deldv <key>")
                if (!prefs.contains("entry:$key")) return context.reply("No entry named '$key'.")
                prefs.edit().remove("entry:$key").apply()
                context.reply("Deleted '$key'.")
            }
            "listdv" -> {
                val keys = prefs.all.keys.filter { it.startsWith("entry:") }.map { it.removePrefix("entry:") }.sorted()
                context.reply(if (keys.isEmpty()) "The vault is empty." else "Vault entries:\n" + keys.joinToString("\n"))
            }
            "alldv" -> {
                val values = prefs.all.filterKeys { it.startsWith("entry:") }.toSortedMap()
                context.reply(if (values.isEmpty()) "The vault is empty." else values.entries.joinToString("\n\n") { "${it.key.removePrefix("entry:")}\n${it.value}" }.take(3900))
            }
            "pm" -> {
                val text = context.arguments.joinToString(" ").trim()
                if (text.isBlank()) return context.reply("Usage: .pm <text>")
                val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
                gateway.sendToSavedMessages(text) { result ->
                    context.reply(if (result is TdApi.Error) "Saved Messages send failed: ${result.message}" else "Sent to Saved Messages.")
                }
            }
        }
    }
}

class TextToolsAutomationPlugin : AutomationPlugin {
    override val id = "userbot:texttools"
    override val commands = listOf(
        AutomationCommand("json", "Pretty-print and validate JSON text.", "json <json text>"),
        AutomationCommand("wlink", "Create a Telegram message link for a replied message.", "wlink (reply required)"),
        AutomationCommand("mlink", "Create a Telegram message link for a replied message.", "mlink (reply required)"),
        AutomationCommand("saved", "Copy replied text to Telegram Saved Messages.", "saved (reply required)")
    )
    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "json" -> {
                val raw = context.arguments.joinToString(" ").trim()
                if (raw.isBlank()) return context.reply("Usage: .json <json text>")
                val formatted = runCatching {
                    if (raw.startsWith("[")) JSONArray(raw).toString(2) else JSONObject(raw).toString(2)
                }.getOrElse { return context.reply("Invalid JSON: ${it.message}") }
                context.reply(formatted.take(3900))
            }
            "wlink", "mlink" -> {
                val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a message with .${context.command}")
                val chat = gateway.cachedChat(replied.chatId)
                val type = chat?.type as? TdApi.ChatTypeSupergroup
                val link = when {
                    type != null -> "https://t.me/c/${type.supergroupId}/${replied.id}"
                    chat?.type is TdApi.ChatTypePrivate -> "tg://openmessage?user_id=${(chat.type as TdApi.ChatTypePrivate).userId}&message_id=${replied.id}"
                    else -> return context.reply("A public link cannot be generated for this chat.")
                }
                context.reply(link)
            }
            "saved" -> {
                val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a text message with .saved")
                val text = (replied.content as? TdApi.MessageText)?.text?.text
                    ?: return context.reply("Only text messages can be copied with this command.")
                gateway.sendToSavedMessages(text) { result ->
                    context.reply(if (result is TdApi.Error) "Save failed: ${result.message}" else "Copied to Saved Messages.")
                }
            }
        }
    }
}
