package com.vikram.trondroid.automation

data class AutomationMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val isOutgoing: Boolean,
    val senderId: String? = null
)

data class AutomationCommand(
    val name: String,
    val description: String,
    val usage: String = name
)

data class AutomationContext(
    val message: AutomationMessage,
    val command: String? = null,
    val arguments: List<String> = emptyList(),
    val reply: (String) -> Unit
)

interface AutomationPlugin {
    val id: String
    val commands: List<AutomationCommand> get() = emptyList()
    fun onMessage(context: AutomationContext) = Unit
    fun onCommand(context: AutomationContext) = Unit
}

fun interface AutomationFilter {
    fun matches(message: AutomationMessage): Boolean
}

object AutomationFilters {
    val all = AutomationFilter { true }
    val incoming = AutomationFilter { !it.isOutgoing }
    val outgoing = AutomationFilter { it.isOutgoing }
    fun chat(chatId: Long) = AutomationFilter { it.chatId == chatId }
    fun textContains(value: String, ignoreCase: Boolean = true) =
        AutomationFilter { it.text.contains(value, ignoreCase) }
    fun command(name: String, prefix: String = ".") = AutomationFilter { message ->
        if (!message.isOutgoing) false else {
            val first = message.text.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            first.removePrefix(prefix).substringBefore("@").equals(name, true) && first.startsWith(prefix)
        }
    }
}

infix fun AutomationFilter.and(other: AutomationFilter) =
    AutomationFilter { message -> matches(message) && other.matches(message) }

infix fun AutomationFilter.or(other: AutomationFilter) =
    AutomationFilter { message -> matches(message) || other.matches(message) }

operator fun AutomationFilter.not() =
    AutomationFilter { message -> !matches(message) }
