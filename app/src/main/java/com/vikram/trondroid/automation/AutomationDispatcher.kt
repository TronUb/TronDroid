package com.vikram.trondroid.automation

import java.util.concurrent.CopyOnWriteArrayList

class AutomationDispatcher(
    private val commandPrefix: () -> String,
    private val isPluginEnabled: (String) -> Boolean,
    private val sendText: (Long, String) -> Unit,
    private val logger: (String, Boolean) -> Unit
) {
    private data class MessageHandler(
        val pluginId: String,
        val filter: AutomationFilter,
        val callback: (AutomationContext) -> Unit
    )

    private val plugins = CopyOnWriteArrayList<AutomationPlugin>()
    private val handlers = CopyOnWriteArrayList<MessageHandler>()

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
        val reply: (String) -> Unit = { text ->
            if (text.isNotBlank()) sendText(message.chatId, text)
        }
        handlers.forEach { handler ->
            if ((handler.pluginId == "core" || isPluginEnabled(handler.pluginId)) &&
                handler.filter.matches(message)
            ) {
                runCatching {
                    handler.callback(AutomationContext(message, reply = reply))
                }.onFailure { logger("Plugin ${handler.pluginId} failed: ${it.message}", true) }
            }
        }

        if (!message.isOutgoing) return
        val prefix = commandPrefix().ifBlank { "." }
        val parsed = parseCommand(message.text, prefix) ?: return
        val (name, arguments) = parsed
        val plugin = plugins.firstOrNull { candidate ->
            (candidate.id == "core" || isPluginEnabled(candidate.id)) &&
                candidate.commands.any { it.name.equals(name, true) }
        } ?: return
        val context = AutomationContext(message, name, arguments, reply)
        runCatching { plugin.onCommand(context) }
            .onFailure { logger("Command $name failed: ${it.message}", true) }
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
