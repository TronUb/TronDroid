package com.vikram.trondroid.automation

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutomationDispatcher(
    private val commandPrefix: () -> String,
    private val isPluginEnabled: (String) -> Boolean,
    private val sendText: (Long, String) -> Unit,
    private val editText: (Long, Long, String) -> Unit,
    private val logger: (String, Boolean) -> Unit
) {
    private data class MessageHandler(
        val pluginId: String,
        val filter: AutomationFilter,
        val callback: (AutomationContext) -> Unit
    )

    private val plugins = CopyOnWriteArrayList<AutomationPlugin>()
    private val handlers = CopyOnWriteArrayList<MessageHandler>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TronDroid-Automation").apply { isDaemon = true }
    }

    fun register(plugin: AutomationPlugin) {
        unregister(plugin.id)
        plugins += plugin
        handlers += MessageHandler(plugin.id, AutomationFilters.all, plugin::onMessage)
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

    fun dispatch(message: AutomationMessage) {
        val sendReply: (String) -> Unit = { text ->
            if (text.isNotBlank()) sendText(message.chatId, text)
        }
        val editCommand: (String) -> Unit = { text ->
            if (text.isNotBlank() && message.messageId != 0L) editText(message.chatId, message.messageId, text)
        }
        val delayedEdit: (Long, String) -> Unit = { delay, text ->
            scheduler.schedule({ editCommand(text) }, delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }

        handlers.forEach { handler ->
            if ((handler.pluginId == "core" || isPluginEnabled(handler.pluginId)) &&
                handler.filter.matches(message)
            ) {
                runCatching {
                    handler.callback(AutomationContext(message, reply = sendReply))
                }.onFailure { logger("Plugin ${handler.pluginId} failed: ${it.message}", true) }
            }
        }

        if (!message.isOutgoing) return
        val parsed = parseCommand(message.text, commandPrefix().ifBlank { "." }) ?: return
        val (name, arguments) = parsed
        val plugin = plugins.firstOrNull { candidate ->
            (candidate.id == "core" || isPluginEnabled(candidate.id)) &&
                candidate.commands.any { it.name.equals(name, true) }
        } ?: return
        val context = AutomationContext(message, name, arguments, editCommand, delayedEdit)
        runCatching { plugin.onCommand(context) }
            .onFailure { logger("Command $name failed: ${it.message}", true) }
    }

    fun shutdown() {
        scheduler.shutdownNow()
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
