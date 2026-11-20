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
            "ping" -> context.reply("Pong. TronDroid automation is running.")
            "help" -> {
                val marker = prefix().ifBlank { "." }
                context.reply(
                    "TronDroid automation commands:\\n" +
                        commands.joinToString("\\n") { "$marker${it.usage} — ${it.description}" }
                )
            }
        }
    }
}
