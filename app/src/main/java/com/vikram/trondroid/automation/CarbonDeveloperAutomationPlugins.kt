package com.vikram.trondroid.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale

class CarbonAutomationPlugin(private val context: Context) : AutomationPlugin {
    override val id = "userbot:carbon"
    private val colours = linkedMapOf(
        "aqua" to "#00FFFF", "red" to "#FF5555", "blue" to "#5599FF",
        "green" to "#50FA7B", "yellow" to "#F1FA8C", "gold" to "#FFD700",
        "orange" to "#FFB86C", "purple" to "#BD93F9", "black" to "#000000",
        "white" to "#FFFFFF", "lime" to "#00FF00", "silver" to "#C0C0C0",
        "maroon" to "#800000", "olive" to "#808000", "teal" to "#008080",
        "navy" to "#000080", "chocolate" to "#D2691E"
    )
    override val commands = listOf(
        AutomationCommand("carbon", "Generate a syntax-styled code image locally on the device.", "carbon [colour] <code>"),
        AutomationCommand("cb", "Alias for carbon.", "cb [colour] <code>"),
        AutomationCommand("carblist", "List supported Carbon accent colours.", "carblist")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        if (context.command == "carblist") return context.reply("**Carbon colours**\n" + colours.keys.joinToString(" · "))
        val raw = context.message.text.trim().substringAfter(" ", "").trim()
        if (raw.isBlank()) return context.reply("Usage: .carbon [colour] <code>\nExample: .carbon aqua fun main() { println(\"Hello\") }")
        val first = raw.substringBefore(' ')
        val colour = colours[first.lowercase(Locale.ROOT)] ?: "#8BE9FD"
        val code = if (first.lowercase(Locale.ROOT) in colours) raw.substringAfter(' ', "") else raw
        if (code.isBlank()) return context.reply("Add code after the colour name.")
        if (code.length > 3500) return context.reply("Code is too long. Limit: 3500 characters.")
        val output = File(this.context.cacheDir, "carbon_${System.currentTimeMillis()}.png")
        context.reply("Rendering code image locally…")
        runCatching { render(code, colour, output) }.onFailure {
            output.delete()
            context.reply("Carbon rendering failed: ${it.message}")
            return
        }
        gateway.sendPhotoPath(context.message.chatId, output.absolutePath, "Generated with TronDroid Carbon") { result ->
            context.reply(if (result is TdApi.Error) "Unable to send image: ${result.message}" else "Carbon image generated.")
            output.delete()
        }
    }

    private fun render(code: String, accent: String, output: File) {
        val lines = code.lines().take(100)
        val width = 1200
        val lineHeight = 42f
        val height = (150 + lines.size * lineHeight).toInt().coerceIn(260, 4400)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(40, 42, 54))
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 34, 44) }
        canvas.drawRoundRect(36f, 36f, 1164f, (height - 36).toFloat(), 24f, 24f, card)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dots = intArrayOf(Color.rgb(255,95,86), Color.rgb(255,189,46), Color.rgb(39,201,63))
        dots.forEachIndexed { index, color ->
            dotPaint.color = color
            canvas.drawCircle(76f + index * 34f, 76f, 10f, dotPaint)
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 24f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        canvas.drawText("TronDroid  ·  Carbon", 500f, 84f, title)
        val mono = Typeface.create("monospace", Typeface.NORMAL)
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(98, 104, 125); textSize = 25f; typeface = mono }
        val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248,248,242); textSize = 25f; typeface = mono }
        val keywordPaint = Paint(codePaint).apply { color = Color.parseColor(accent) }
        val keywords = Regex("\\b(fun|val|var|class|object|return|if|else|for|while|import|from|def|const|let|function|public|private|static|void|int|String|true|false|null|suspend|override)\\b")
        lines.forEachIndexed { index, line ->
            val y = 145f + index * lineHeight
            canvas.drawText((index + 1).toString(), 70f, y, numberPaint)
            val match = keywords.find(line)
            if (match == null) canvas.drawText(line.take(90), 125f, y, codePaint)
            else {
                val before = line.substring(0, match.range.first)
                val token = match.value
                canvas.drawText(before, 125f, y, codePaint)
                val x = 125f + codePaint.measureText(before)
                canvas.drawText(token, x, y, keywordPaint)
                canvas.drawText(line.substring(match.range.last + 1).take(90), x + keywordPaint.measureText(token), y, codePaint)
            }
        }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

/**
 * Safe, read-only developer diagnostics. No interpreter, shell, process execution,
 * file mutation, package management or privileged device controls are exposed.
 */
class DeveloperToolsAutomationPlugin(private val context: Context) : AutomationPlugin {
    override val id = "userbot:dev"
    override val commands = listOf(
        AutomationCommand("devinfo", "Show Android and app build information.", "devinfo"),
        AutomationCommand("meminfo", "Show app memory usage and device memory.", "meminfo"),
        AutomationCommand("storageinfo", "Show app cache and device storage usage.", "storageinfo"),
        AutomationCommand("displayinfo", "Show screen density and resolution.", "displayinfo"),
        AutomationCommand("eval", "Disabled: arbitrary code execution is not supported on-device.", "eval (disabled)"),
        AutomationCommand("e", "Alias for disabled code evaluation.", "e (disabled)"),
        AutomationCommand("term", "Disabled: shell execution is not supported on-device.", "term (disabled)"),
        AutomationCommand("shell", "Alias for disabled shell execution.", "shell (disabled)")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "eval", "e" -> context.reply("Code evaluation is disabled in TronDroid. This app does not execute arbitrary code.")
            "term", "shell" -> context.reply("Shell access is disabled in TronDroid. No commands are run on your device.")
            "devinfo" -> context.reply(
                "**Android / app**\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nBuild: ${Build.DISPLAY}\nABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"}\nApp: ${context.packageNameOrFallback(this.context)}"
            )
            "meminfo" -> {
                val runtime = Runtime.getRuntime()
                val appUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val appMax = runtime.maxMemory() / 1024 / 1024
                val native = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
                val activity = android.app.ActivityManager::class.java
                val manager = this.context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val info = android.app.ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                context.reply("**Memory (read-only)**\nApp Java heap: ${appUsed} MB / ${appMax} MB\nNative heap: ${native} MB\nDevice available: ${info.availMem / 1024 / 1024} MB\nLow-memory state: ${info.lowMemory}")
            }
            "storageinfo" -> {
                val stat = StatFs(Environment.getDataDirectory().absolutePath)
                val total = stat.totalBytes / 1024 / 1024
                val free = stat.availableBytes / 1024 / 1024
                val cache = this.context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
                context.reply("**Storage (read-only)**\nInternal storage: ${total - free} MB used / ${total} MB total\nAvailable: ${free} MB\nTronDroid cache: ${cache} MB")
            }
            "displayinfo" -> {
                val metrics = this.context.resources.displayMetrics
                context.reply("**Display**\nResolution: ${metrics.widthPixels} × ${metrics.heightPixels}\nDensity: ${metrics.densityDpi} dpi\nScale: ${"%.2f".format(Locale.US, metrics.density)}\nFont scale: ${"%.2f".format(Locale.US, metrics.scaledDensity)}")
            }
        }
    }

    private fun AutomationContext.packageNameOrFallback(context: Context): String =
        context.packageName
}
