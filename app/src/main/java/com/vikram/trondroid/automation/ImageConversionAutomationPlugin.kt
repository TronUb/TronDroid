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
        AutomationCommand("itos", "Convert a replied image into a static Telegram sticker.", "itos (reply to photo or image document)"),
        AutomationCommand("colourtemplate", "Generate a solid-color image.", "colourtemplate <color name or #RRGGBB>"),
        AutomationCommand("pfp", "Download the profile photo of a replied user or your own account.", "pfp (reply to a user)")
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
                    val output = File(this.context.cacheDir, "sticker_${System.currentTimeMillis()}.jpg")
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
            "pfp" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                val targetId = (replied?.senderId as? TdApi.MessageSenderUser)?.userId
                    ?: gateway.currentUserId()
                    ?: return context.reply("Reply to a user's message or connect your account first.")
                val user = gateway.cachedUser(targetId)
                val photo = user?.profilePhoto?.big ?: user?.publicPhoto?.big
                    ?: return context.reply("No profile photo is available in the local Telegram cache.")
                context.reply("Downloading profile photo…")
                gateway.downloadFile(photo.id) { downloaded, error ->
                    if (downloaded == null || error != null) {
                        context.reply("Profile photo download failed: ${error ?: "unknown error"}")
                    } else {
                        gateway.sendPhotoPath(context.message.chatId, downloaded.local.path, "Profile photo") { result ->
                            context.reply(if (result is TdApi.Error) "Unable to send profile photo: ${result.message}" else "Profile photo sent.")
                        }
                    }
                }
            }
            "itos" -> {
                val replied = context.message.tdMessage?.let(gateway::repliedMessage)
                    ?: return context.reply("Reply to a photo or image document with .itos")
                val fileId = when (val media = replied.content) {
                    is TdApi.MessagePhoto -> media.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo?.id
                    is TdApi.MessageDocument -> media.document.takeIf {
                        it.mimeType.startsWith("image/") && it.mimeType != "image/gif"
                    }?.document?.id
                    else -> null
                } ?: return context.reply("The replied message is not a supported image.")
                context.reply("Converting image to sticker…")
                gateway.downloadFile(fileId) { downloaded, error ->
                    if (downloaded == null || error != null) {
                        context.reply("Image download failed: ${error ?: "unknown error"}")
                    } else {
                        val bitmap = BitmapFactory.decodeFile(downloaded.local.path)
                        if (bitmap == null) {
                            context.reply("Unable to decode this image.")
                        } else {
                            val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
                            if (scaled !== bitmap) bitmap.recycle()
                            val output = File(this.context.cacheDir, "sticker_${System.currentTimeMillis()}.webp")
                            runCatching {
                                output.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP, 90, it) }
                                scaled.recycle()
                            }.onSuccess {
                                gateway.send(
                                    TdApi.SendMessage(
                                        context.message.chatId, null, null, null, null,
                                        TdApi.InputMessageSticker(
                                            TdApi.InputFileLocal(output.absolutePath), null, 512, 512, "🙂"
                                        )
                                    )
                                ) { result ->
                                    context.reply(if (result is TdApi.Error) "Sticker send failed: ${result.message}" else "Sticker sent.")
                                    output.delete()
                                }
                            }.onFailure {
                                context.reply("Sticker conversion failed: ${it.message}")
                                output.delete()
                            }
                        }
                    }
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
