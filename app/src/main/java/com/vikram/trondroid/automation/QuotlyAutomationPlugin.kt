package com.vikram.trondroid.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.drinkless.tdlib.TdApi
import java.io.File

class QuotlyAutomationPlugin(private val cacheDir: File) : AutomationPlugin {
    override val id = "userbot:quotly"
    override val commands = listOf(AutomationCommand("quotly", "Render a replied text message as a quote image.", "quotly (reply to text)"))
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a text message with .quotly")
        val text = (replied.content as? TdApi.MessageText)?.text?.text
            ?: return context.reply("Quotly currently supports text messages.")
        if (text.isBlank()) return context.reply("The replied message is empty.")
        val senderId = (replied.senderId as? TdApi.MessageSenderUser)?.userId
        val sender = senderId?.let(gateway::cachedUser)
        val name = listOfNotNull(sender?.firstName, sender?.lastName).filter(String::isNotBlank).joinToString(" ").ifBlank { "Telegram user" }
        val file = File(cacheDir, "quotly_${System.currentTimeMillis()}.png")
        runCatching {
            val bitmap = Bitmap.createBitmap(1080, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(25, 29, 38))
            val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(39, 45, 58) }
            canvas.drawRoundRect(56f, 56f, 1024f, 664f, 36f, 36f, card)
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105, 155, 255) }
            canvas.drawRoundRect(88f, 110f, 98f, 610f, 6f, 6f, accent)
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 42f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            canvas.drawText(name.take(48), 136f, 174f, titlePaint)
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(230, 234, 242)
                textSize = 34f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(text.take(900), 0, text.take(900).length, bodyPaint, 820)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8f, 1f)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(136f, 230f)
            layout.draw(canvas)
            canvas.restore()
            val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(145, 155, 174); textSize = 22f }
            canvas.drawText("TRONDROID • QUOTE", 136f, 620f, footer)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }.onFailure { return context.reply("Unable to render quote: ${it.message}") }
        gateway.sendDocumentPath(context.message.chatId, file.absolutePath, "Quote by $name") { result ->
            context.reply(if (result is TdApi.Error) "Quote upload failed: ${result.message}" else "Quote image sent.")
            file.delete()
        }
    }
}
