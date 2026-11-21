package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis

class ToolsExtrasAutomationPlugin : AutomationPlugin {
    override val id = "userbot:tools"
    override val commands = listOf(
        AutomationCommand("forward", "Forward a replied message to Saved Messages.", "forward (reply required)"),
        AutomationCommand("commonchat", "List groups shared with a replied user or numeric user ID.", "commonchat <user id> (or reply)"),
        AutomationCommand("speedtest", "Measure a short HTTPS download throughput sample.", "speedtest")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "forward" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a message with .forward")
                gateway.send(TdApi.CreatePrivateChat(gateway.currentUserId() ?: return context.reply("Account ID unavailable."), false)) { chatResult ->
                    val saved = chatResult as? TdApi.Chat
                        ?: return@send context.reply(if (chatResult is TdApi.Error) chatResult.message else "Saved Messages unavailable.")
                    gateway.send(TdApi.ForwardMessages(saved.id, null, replied.chatId, longArrayOf(replied.id), null, false, false)) { result ->
                        context.reply(if (result is TdApi.Error) "Forward failed: ${result.message}" else "Forwarded to Saved Messages.")
                    }
                }
            }
            "commonchat" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                val target = context.arguments.firstOrNull()?.toLongOrNull() ?: (replied?.senderId as? TdApi.MessageSenderUser)?.userId
                    ?: return context.reply("Usage: .commonchat <user id> or reply to a user's message")
                gateway.send(TdApi.GetGroupsInCommon(target, 0, 100)) { result ->
                    if (result is TdApi.Error) return@send context.reply("Telegram error: ${result.message}")
                    val chats: LongArray = (result as? TdApi.Chats)?.chatIds ?: longArrayOf()
                    val names = chats.map { id -> gateway.cachedChat(id)?.title ?: "Chat $id" }
                    context.reply(if (names.isEmpty()) "No shared groups returned." else "Common groups:\n" + names.joinToString("\n").take(3800))
                }
            }
            "speedtest" -> {
                context.reply("Testing download speed (up to 8 MB)…")
                Thread({
                    runCatching {
                        val connection = URL("https://speed.cloudflare.com/__down?bytes=8000000").openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 30000
                        connection.setRequestProperty("User-Agent", "TronDroid/1.0")
                        var bytes = 0L
                        val elapsed = measureTimeMillis {
                            try {
                                connection.inputStream.use { input ->
                                    val buffer = ByteArray(16 * 1024)
                                    while (bytes < 8_000_000L) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        bytes += read
                                    }
                                }
                            } finally { connection.disconnect() }
                        }.coerceAtLeast(1L)
                        val mbps = bytes * 8.0 / elapsed / 1000.0
                        "Downloaded %.2f MB in %.2f s\nApprox. download speed: %.2f Mbps".format(bytes / 1_000_000.0, elapsed / 1000.0, mbps)
                    }.onSuccess(context.reply).onFailure { context.reply("Speed test failed: ${it.message}") }
                }, "TronDroid-SpeedTest").apply { isDaemon = true }.start()
            }
        }
    }
}
