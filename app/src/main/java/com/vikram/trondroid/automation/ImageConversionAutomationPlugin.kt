package com.vikram.trondroid.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.drinkless.tdlib.TdApi
import java.io.File

class ImageConversionAutomationPlugin(private val context: Context) : AutomationPlugin {
    override val id = "userbot:image-conversion"
    override val commands = listOf(
        AutomationCommand("stoi", "Convert a static replied sticker into a photo.", "stoi (reply to sticker)"),
        AutomationCommand("colourtemplate", "Generate a solid-color image.", "colourtemplate <color name or #RRGGBB>")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        when (context.command) {
            "stoi" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a static sticker with .stoi")
                val sticker = (replied.content as? TdApi.MessageSticker)?.sticker
                    ?: return context.reply("The replied message is not a sticker.")
                if (sticker.isAnimated || sticker.isVideo) return context.reply("Animated and video stickers are not supported.")
                context.reply("Converting sticker…")
                gateway.downloadFile(sticker.sticker.id) { downloaded, error ->
                    if (downloaded == null || error != null) return@downloadFile context.reply("Sticker download failed: ${error ?: "unknown error"}")
                    val bitmap = BitmapFactory.decodeFile(downloaded.local.path)
                        ?: return@downloadFile context.reply("Unable to decode this sticker format.")
                    val output = File(context.cacheDir, "sticker_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        bitmap.recycle()
                    }.onSuccess {
                        gateway.sendPhotoPath(context.message.chatId, output.absolutePath, "Converted sticker") { result ->
                            context.reply(if (result is TdApi.Error) "Photo upload failed: ${result.message}" else "Sticker converted to photo.")
                            output.delete()
                        }
                    }.onFailure { context.reply("Conversion failed: ${it.message}"); output.delete() }
                }
            }
            "colourtemplate" -> {
                val name = context.arguments.joinToString(" ").trim()
                if (name.isBlank()) return context.reply("Usage: .colourtemplate <color name or #RRGGBB>")
                val color = runCatching { Color.parseColor(name) }.getOrElse {
                    return context.reply("Invalid color. Use a supported color name or #RRGGBB.")
                }
                val bitmap = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(color)
                val output = File(context.cacheDir, "color_${System.currentTimeMillis()}.png")
                runCatching {
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }.onSuccess {
                    gateway.sendPhotoPath(context.message.chatId, output.absolutePath, "Color: $name") { result ->
                        context.reply(if (result is TdApi.Error) "Image upload failed: ${result.message}" else "Color template generated.")
                        output.delete()
                    }
                }.onFailure { context.reply("Image generation failed: ${it.message}"); output.delete() }
            }
        }
    }
}
