package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutomationDispatcher(
    private val commandPrefix: () -> String,
    private val isPluginEnabled: (String) -> Boolean,
    private val sendText: (Long, String) -> Unit,
    private val editText: (Long, Long, String) -> Unit,
    private val logger: (String, Boolean) -> Unit,
    private val telegramGateway: () -> com.vikram.trondroid.telegram.TelegramClientGateway? = { null }
) {
    private data class MessageHandler(
        val pluginId: String,
        val filter: AutomationFilter,
        val callback: (AutomationContext) -> Unit
    )

    private val plugins = CopyOnWriteArrayList<AutomationPlugin>()
    private val handlers = CopyOnWriteArrayList<MessageHandler>()
    /** Serial execution preserves update order and keeps plugin code off TDLib's callback thread. */
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TronDroid-Automation").apply { isDaemon = true }
    }
    @Volatile private var sleepingUntilElapsed = 0L

    /** Pause message/update handlers without blocking the serial executor. */
    fun sleepFor(seconds: Long) {
        require(seconds in 1L..86_400L)
        val deadline = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        sleepingUntilElapsed = deadline
        executor.schedule({
            if (sleepingUntilElapsed == deadline) sleepingUntilElapsed = 0L
        }, seconds, TimeUnit.SECONDS)
    }

    private fun isSleeping(): Boolean =
        android.os.SystemClock.elapsedRealtime() < sleepingUntilElapsed

    fun register(plugin: AutomationPlugin) {
        unregister(plugin.id)
        plugins += plugin
        handlers += MessageHandler(plugin.id, AutomationFilters.all, plugin::onMessage)
        logger("Registered plugin ${plugin.id} (${plugin.commands.size} commands)", false)
    }

    fun registerMessageHandler(
        pluginId: String,
        filter: AutomationFilter = AutomationFilters.all,
        callback: (AutomationContext) -> Unit
    ) {
        handlers += MessageHandler(pluginId, filter, callback)
    }

    fun unregister(pluginId: String) {
        plugins.removeAll { it.id == pluginId }
        handlers.removeAll { it.pluginId == pluginId }
    }

    fun registeredPlugins(): List<String> = plugins.map { it.id }

    /** Dispatch every TDLib update to every enabled plugin. */
    fun dispatchUpdate(update: TdApi.Update) {
        executor.execute {
            if (isSleeping()) return@execute
            val context = AutomationUpdateContext(
                update = update,
                reply = { chatId, text -> if (text.isNotBlank()) sendText(chatId, text) },
                telegram = telegramGateway()
            )
            plugins.forEach { plugin ->
                if (plugin.id == "core" || isPluginEnabled(plugin.id)) {
                    runCatching { plugin.onTelegramUpdate(context) }
                        .onFailure {
                            logger("Plugin ${plugin.id} update handler failed: ${it.message}", true)
                        }
                }
            }
        }
    }

    fun dispatch(message: AutomationMessage) {
        executor.execute {
            dispatchMessage(message)
        }
    }

    private fun dispatchMessage(message: AutomationMessage) {
        if (isSleeping()) return
        val sendReply: (String) -> Unit = { text ->
            if (text.isNotBlank()) sendText(message.chatId, text)
        }
        val editCommand: (String) -> Unit = { text ->
            if (text.isNotBlank() && message.messageId != 0L) {
                editText(message.chatId, message.messageId, text)
            }
        }
        val delayedEdit: (Long, String) -> Unit = { delay, text ->
            executor.schedule({ editCommand(text) }, delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }

        handlers.forEach { handler ->
            if ((handler.pluginId == "core" || isPluginEnabled(handler.pluginId)) &&
                handler.filter.matches(message)
            ) {
                runCatching {
                    val messageReply: (String) -> Unit = if (message.isOutgoing) editCommand else sendReply
                    val delayedMessageReply: (Long, String) -> Unit = { delay, text ->
                        executor.schedule({ messageReply(text) }, delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    }
                    handler.callback(
                        AutomationContext(
                            message = message,
                            reply = messageReply,
                            replyAfter = delayedMessageReply,
                            telegram = telegramGateway()
                        )
                    )
                }.onFailure { logger("Plugin ${handler.pluginId} failed: ${it.message}", true) }
            }
        }

        val parsed = parseCommand(message.text, commandPrefix().ifBlank { "." }) ?: return
        val (name, arguments) = parsed
        val incomingAuthorized = message.isOutgoing || plugins.any { it.canExecuteIncoming(message) }
        val matches = plugins.filter { candidate ->
            (candidate.id == "core" || isPluginEnabled(candidate.id)) &&
                candidate.commands.any { it.name.equals(name, true) } &&
                incomingAuthorized
        }
        if (matches.isEmpty()) return
        if (matches.size > 1) {
            logger("Command collision for '$name': ${matches.joinToString { it.id }}; using first registered plugin", true)
        }
        val plugin = matches.first()
        val commandReply: (String) -> Unit = if (message.isOutgoing) editCommand else sendReply
        val commandDelayedReply: (Long, String) -> Unit = { delay, text ->
            executor.schedule({ commandReply(text) }, delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
        val context = AutomationContext(message, name, arguments, commandReply, commandDelayedReply, telegramGateway())
        runCatching { plugin.onCommand(context) }
            .onFailure { logger("Command $name in ${plugin.id} failed: ${it.message}", true) }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun parseCommand(text: String, prefix: String): Pair<String, List<String>>? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val token = parts.firstOrNull() ?: return null
        if (!token.startsWith(prefix) || token.length <= prefix.length) return null
        val command = token.removePrefix(prefix).substringBefore("@").lowercase()
        if (command.isBlank()) return null
        return command to parts.drop(1)
    }
}
