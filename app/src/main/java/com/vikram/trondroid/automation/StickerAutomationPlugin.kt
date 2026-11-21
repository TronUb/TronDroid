package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class StickerAutomationPlugin : AutomationPlugin {
    override val id = "userbot:stickers"
    override val commands = listOf(
        AutomationCommand("kang", "Add a replied Telegram sticker to your favorite stickers.", "kang (reply to sticker)"),
        AutomationCommand("stinfo", "Show metadata for a replied sticker.", "stinfo (reply to sticker)")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a sticker message.")
        val stickerMessage = replied.content as? TdApi.MessageSticker
            ?: return context.reply("The replied message is not a sticker.")
        val sticker = stickerMessage.sticker
        when (context.command) {
            "stinfo" -> context.reply(
                "Sticker information\nEmoji: ${sticker.emoji.ifBlank { "—" }}\nSet ID: ${sticker.setId}\n" +
                    "Dimensions: ${sticker.width} × ${sticker.height}"
            )
            "kang" -> {
                val remoteId = sticker.sticker.remote.id
                if (remoteId.isBlank()) return context.reply("Sticker file is not available remotely.")
                gateway.send(TdApi.AddFavoriteSticker(TdApi.InputFileRemote(remoteId))) { result ->
                    context.reply(if (result is TdApi.Error) "Unable to add sticker: ${result.message}" else "Sticker added to your Telegram favorites.")
                }
            }
        }
    }
}
