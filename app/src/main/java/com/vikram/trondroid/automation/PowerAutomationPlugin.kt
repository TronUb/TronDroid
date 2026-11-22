package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Power controls for the Telegram userbot runtime, not Android device power.
 * Android does not expose reboot/screen-off to ordinary third-party apps.
 */
class PowerAutomationPlugin(
    private val sleepFor: (Long) -> Unit,
    private val restartSession: () -> Boolean
) : AutomationPlugin {
    override val id = "userbot:power"
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TronDroid-Power").apply { isDaemon = true }
    }

    override val commands = listOf(
        AutomationCommand("reboot", "Restart the current Telegram userbot session without rebooting Android.", "reboot"),
        AutomationCommand("sleep", "Pause userbot command and update handling for up to 24 hours.", "sleep <seconds>")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "sleep" -> {
                val seconds = context.arguments.firstOrNull()?.toLongOrNull()
                    ?: return context.reply("Usage: .sleep <seconds> (1–86400)")
                if (seconds !in 1L..86_400L) {
                    return context.reply("Sleep duration must be between 1 and 86400 seconds.")
                }
                sleepFor(seconds)
                context.reply("TronDroid userbot is sleeping for ${formatDuration(seconds)}. Commands and plugin updates will resume automatically.")
            }
            "reboot" -> {
                context.reply("Restarting this Telegram userbot session…")
                executor.schedule({
                    val restarted = runCatching(restartSession).getOrDefault(false)
                    if (!restarted) {
                        val text = "Userbot restart failed. Check that this account is enabled and its API credentials are available."
                        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
                        context.telegram?.send(TdApi.SendMessage(context.message.chatId, null, null, null, null, content)) {}
                    }
                }, 900, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds} second(s)"
        seconds < 3600 -> "${seconds / 60} minute(s)"
        else -> "${seconds / 3600} hour(s)"
    }
}
