package com.vikram.trondroid.automation

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.drinkless.tdlib.TdApi
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class VideoToolsAutomationPlugin(private val cacheDir: File) : AutomationPlugin {
    override val id = "userbot:videotools"
    override val commands = listOf(
        AutomationCommand("vmute", "Remove audio from a replied video.", "vmute (reply to video)"),
        AutomationCommand("vcut", "Trim a replied video from the beginning to a duration.", "vcut <hh:mm:ss> (reply to video)"),
        AutomationCommand("vsubclip", "Extract a time range from a replied video.", "vsubclip <start hh:mm:ss> <end hh:mm:ss> (reply to video)")
    )
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TronDroid-VideoTools").apply { isDaemon = true } }

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a Telegram video with .${context.command}.")
        val video = (replied.content as? TdApi.MessageVideo)?.video
            ?: return context.reply("The replied message must contain a Telegram video.")
        val (startMs, endMs) = when (context.command) {
            "vmute" -> 0L to Long.MAX_VALUE
            "vcut" -> {
                val end = parseTime(context.arguments.firstOrNull()) ?: return context.reply("Usage: .vcut <hh:mm:ss>")
                0L to end
            }
            "vsubclip" -> {
                val start = parseTime(context.arguments.getOrNull(0)) ?: return context.reply("Usage: .vsubclip <start hh:mm:ss> <end hh:mm:ss>")
                val end = parseTime(context.arguments.getOrNull(1)) ?: return context.reply("Usage: .vsubclip <start hh:mm:ss> <end hh:mm:ss>")
                if (end <= start) return context.reply("End time must be after start time.")
                start to end
            }
            else -> return
        }
        context.reply("Downloading video for processing…")
        gateway.downloadFile(video.video.id) { file, error ->
            if (file == null || error != null) return@downloadFile context.reply("Video download failed: ${error ?: "unknown error"}")
            worker.execute {
                val output = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
                runCatching { process(file.local.path, output, startMs, endMs, context.command == "vmute") }
                    .onSuccess {
                        gateway.sendDocumentPath(context.message.chatId, output.absolutePath, "Processed video") { result ->
                            context.reply(if (result is TdApi.Error) "Video upload failed: ${result.message}" else "Video processing complete.")
                            output.delete()
                        }
                    }.onFailure { output.delete(); context.reply("Video processing failed: ${it.message ?: "unknown error"}") }
            }
        }
    }

    private fun parseTime(value: String?): Long? {
        if (value == null) return null
        val parts = value.split(":")
        if (parts.size != 3 || parts.any { it.toIntOrNull() == null }) return null
        val h = parts[0].toLong(); val m = parts[1].toLong(); val s = parts[2].toLong()
        if (m !in 0..59 || s !in 0..59 || h < 0) return null
        return ((h * 3600 + m * 60 + s) * 1000L)
    }

    private fun process(input: String, output: File, startMs: Long, endMs: Long, mute: Boolean) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input)
            val selected = mutableListOf<Int>()
            val outputTracks = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || (!mute && mime.startsWith("audio/"))) {
                    selected += index
                }
            }
            require(selected.any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) { "No video track found." }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            selected.forEach { index -> outputTracks[index] = muxer.addTrack(extractor.getTrackFormat(index)) }
            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            selected.forEach { index -> extractor.selectTrack(index) }
            val startUs = startMs * 1000L
            val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time < 0 || time >= endUs) break
                if (time >= startUs && track in outputTracks) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size > 0) {
                        info.set(0, size, (time - startUs).coerceAtLeast(0), extractor.sampleFlags)
                        muxer.writeSampleData(outputTracks.getValue(track), buffer, info)
                    }
                }
                if (!extractor.advance()) break
            }
            muxer.stop()
            require(output.length() > 1024) { "The selected time range did not contain enough video data." }
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
