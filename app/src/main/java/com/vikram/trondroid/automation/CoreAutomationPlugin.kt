package com.vikram.trondroid.automation

class CoreAutomationPlugin(
    private val prefix: () -> String
) : AutomationPlugin {
    override val id: String = "core"
    override val commands = listOf(
        AutomationCommand("help", "List available TronDroid commands", "help"),
        AutomationCommand("ping", "Check whether the automation engine is responding", "ping")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "ping" -> {
                context.reply("• • •")
                context.replyAfter(450, "Pong! TronDroid is running.")
            }
            "help" -> {
                val marker = prefix().ifBlank { "." }
                context.reply(
                    "TronDroid automation commands:\n" +
                        commands.joinToString("\n") { "${it.usage} — ${it.description}" }.let { lines ->
                            lines.lines().joinToString("\n") { "$marker$it" }
                        }
                )
            }
        }
    }
}
