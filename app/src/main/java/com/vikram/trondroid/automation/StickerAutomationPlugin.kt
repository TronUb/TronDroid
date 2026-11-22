package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

class StickerAutomationPlugin : AutomationPlugin {
    override val id = "userbot:stickers"
    override val commands = listOf(
        AutomationCommand("kang", "Add a replied Telegram sticker to your favorite stickers.", "kang (reply to sticker)"),
        AutomationCommand("unkang", "Remove a replied Telegram sticker from your favorites.", "unkang (reply to sticker)"),
        AutomationCommand("stinfo", "Show sticker file, format, dimensions, emoji and pack ID.", "stinfo (reply to sticker)"),
        AutomationCommand("stickerinfo", "Alias for stinfo.", "stickerinfo (reply to sticker)")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a Telegram sticker with .kang, .unkang or .stinfo.")
        val stickerMessage = replied.content as? TdApi.MessageSticker
            ?: return context.reply("The replied message is not a Telegram sticker.")
        val sticker = stickerMessage.sticker
        val file = sticker.sticker
        val remoteId = file.remote.id

        when (context.command) {
            "stinfo", "stickerinfo" -> {
                context.reply(buildString {
                    append("Sticker information\n")
                    append("Emoji: ${sticker.emoji.ifBlank { "—" }}\n")
                    append("Set ID: ${sticker.setId}\n")
                    append("Dimensions: ${sticker.width} × ${sticker.height}\n")
                    append("Format: ${sticker.format.javaClass.simpleName.removePrefix("StickerFormat")}\n")
                    append("File ID: ${file.id}\n")
                    append("File size: ${formatBytes(file.size)}\n")
                    append("Remote ID available: ${if (remoteId.isNotBlank()) "Yes" else "No"}\n")
                    append("File downloaded: ${if (file.local.isDownloadingCompleted) "Yes" else "No"}")
                })
            }
            "kang" -> {
                if (remoteId.isBlank()) return context.reply("Sticker file has no reusable Telegram remote ID.")
                gateway.send(TdApi.AddFavoriteSticker(TdApi.InputFileRemote(remoteId))) { result ->
                    context.reply(if (result is TdApi.Error) {
                        "Unable to add sticker to favorites: ${result.message}"
                    } else {
                        "Sticker added to your Telegram favorites."
                    })
                }
            }
            "unkang" -> {
                if (remoteId.isBlank()) return context.reply("Sticker file has no reusable Telegram remote ID.")
                gateway.send(TdApi.RemoveFavoriteSticker(TdApi.InputFileRemote(remoteId))) { result ->
                    context.reply(if (result is TdApi.Error) {
                        "Unable to remove sticker from favorites: ${result.message}"
                    } else {
                        "Sticker removed from your Telegram favorites."
                    })
                }
            }
        }
    }

    private fun formatBytes(size: Long): String = when {
        size <= 0L -> "Unknown"
        size < 1024L -> "$size B"
        size < 1024L * 1024L -> "${size / 1024} KB"
        else -> "${size / (1024L * 1024L)} MB"
    }
}
