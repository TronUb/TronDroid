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
    override val commands = listOf(
        AutomationCommand("q", "Create a quote image from a replied Telegram text message.", "q [black|white|blue|purple|green] (reply required)"),
        AutomationCommand("quotly", "Alias for q; render a replied text message as a quote image.", "quotly [colour] (reply required)")
    )

    private data class Palette(val background: Int, val card: Int, val accent: Int, val foreground: Int, val muted: Int)

    private val palettes = mapOf(
        "black" to Palette(Color.rgb(12, 14, 18), Color.rgb(31, 35, 43), Color.rgb(170, 180, 198), Color.WHITE, Color.rgb(160, 170, 188)),
        "white" to Palette(Color.rgb(235, 238, 243), Color.WHITE, Color.rgb(55, 105, 190), Color.rgb(28, 33, 42), Color.rgb(100, 108, 120)),
        "blue" to Palette(Color.rgb(12, 25, 46), Color.rgb(25, 48, 78), Color.rgb(91, 165, 255), Color.WHITE, Color.rgb(170, 195, 225)),
        "purple" to Palette(Color.rgb(31, 19, 43), Color.rgb(54, 34, 72), Color.rgb(195, 135, 255), Color.WHITE, Color.rgb(195, 175, 215)),
        "green" to Palette(Color.rgb(14, 32, 28), Color.rgb(29, 53, 45), Color.rgb(100, 210, 160), Color.WHITE, Color.rgb(165, 205, 188))
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val source = context.message.tdMessage
        val replied = source?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a text message with .q or .quotly.")
        val text = (replied.content as? TdApi.MessageText)?.text?.text
            ?: return context.reply("Quotly currently supports replied text messages only.")
        if (text.isBlank()) return context.reply("The replied message is empty.")

        val requested = context.arguments.firstOrNull()?.lowercase()
        val paletteName = requested?.takeIf { it in palettes } ?: "black"
        val quoteText = if (requested != null && requested in palettes.keys) context.arguments.drop(1).joinToString(" ").ifBlank { text } else text
        val senderId = (replied.senderId as? TdApi.MessageSenderUser)?.userId
        val sender = senderId?.let(gateway::cachedUser)
        val name = listOfNotNull(sender?.firstName, sender?.lastName)
            .filter(String::isNotBlank).joinToString(" ").ifBlank { "Telegram user" }
        val output = File(cacheDir, "quotly_${System.currentTimeMillis()}.jpg")

        val palette = palettes.getValue(paletteName)
        val rendered = runCatching { renderQuote(quoteText.take(1800), name, palette, output) }
        if (rendered.isFailure) {
            output.delete()
            return context.reply("Unable to render quote: ${rendered.exceptionOrNull()?.message ?: "unknown error"}")
        }

        gateway.sendPhotoPath(context.message.chatId, output.absolutePath, "Quote by $name") { result ->
            context.reply(if (result is TdApi.Error) "Quote upload failed: ${result.message}" else "Quote image sent.")
            output.delete()
        }
    }

    private fun renderQuote(text: String, name: String, palette: Palette, output: File) {
        val width = 1080
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.foreground
            textSize = 38f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, 840)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1f)
            .setIncludePad(false)
            .setMaxLines(28)
            .build()
        val height = (300 + layout.height + 150).coerceIn(600, 2200)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(palette.background)
            val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card }
            canvas.drawRoundRect(48f, 48f, 1032f, height - 48f, 38f, 38f, card)
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
            canvas.drawRoundRect(92f, 112f, 104f, height - 112f, 6f, 6f, accent)

            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.foreground
                textSize = 42f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            canvas.drawText(name.take(42), 144f, 176f, title)
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                textSize = 23f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            canvas.drawText("TELEGRAM QUOTE", 144f, 215f, sub)

            canvas.save()
            canvas.translate(144f, 270f)
            layout.draw(canvas)
            canvas.restore()

            val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText("TRONDROID", 144f, height - 100f, footer)
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
