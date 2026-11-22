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
        AutomationCommand("vmute", "Remove the audio track from a replied Telegram video without re-encoding.", "vmute (reply to video)"),
        AutomationCommand("vcut", "Trim a replied video from its beginning, aligned to a nearby keyframe.", "vcut <hh:mm:ss> (reply to video)"),
        AutomationCommand("vsubclip", "Extract a keyframe-aligned time range from a replied video.", "vsubclip <start hh:mm:ss> <end hh:mm:ss> (reply to video)")
    )
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TronDroid-VideoTools").apply { isDaemon = true } }

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a Telegram video with .${context.command}.")
        val video = (replied.content as? TdApi.MessageVideo)?.video
            ?: return context.reply("The replied message must contain a Telegram video.")
        val inputFile = video.video
        if (inputFile.size > MAX_INPUT_BYTES) return context.reply("Video is too large. Maximum supported input: 100 MB.")
        if (video.duration !in 1..MAX_DURATION_SECONDS) return context.reply("Video duration must be between 1 second and 10 minutes.")
        val requested = when (context.command) {
            "vmute" -> 0L to video.duration * 1000L
            "vcut" -> {
                val end = parseTime(context.arguments.firstOrNull()) ?: return context.reply("Usage: .vcut <hh:mm:ss>")
                0L to end
            }
            "vsubclip" -> {
                val start = parseTime(context.arguments.getOrNull(0)) ?: return context.reply("Usage: .vsubclip <start hh:mm:ss> <end hh:mm:ss>")
                val end = parseTime(context.arguments.getOrNull(1)) ?: return context.reply("Usage: .vsubclip <start hh:mm:ss> <end hh:mm:ss>")
                start to end
            }
            else -> return
        }
        val durationMs = video.duration * 1000L
        if (requested.first < 0 || requested.second > durationMs || requested.second <= requested.first ||
            requested.second - requested.first < 1000L
        ) return context.reply("Invalid time range. It must be at least 1 second and stay within the video duration (${video.duration}s).")
        context.reply("Downloading video for processing (100 MB limit)…")
        gateway.downloadFile(inputFile.id) { file, error ->
            if (file == null || error != null) return@downloadFile context.reply("Video download failed: ${error ?: "unknown error"}")
            val source = File(file.local.path)
            if (!source.isFile || source.length() <= 0L || source.length() > MAX_INPUT_BYTES) {
                return@downloadFile context.reply("Downloaded video is missing, empty or exceeds the 100 MB limit.")
            }
            worker.execute {
                val output = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
                runCatching { process(source.absolutePath, output, requested.first, requested.second, context.command == "vmute") }
                    .onSuccess {
                        if (output.length() > MAX_OUTPUT_BYTES) {
                            output.delete()
                            context.reply("Processed video exceeds the 100 MB output limit.")
                        } else {
                            gateway.sendDocumentPath(context.message.chatId, output.absolutePath, "Processed video · TronDroid") { result ->
                                context.reply(if (result is TdApi.Error) "Video upload failed: ${result.message}" else "Video processing complete.")
                                output.delete()
                            }
                        }
                    }.onFailure {
                        output.delete()
                        context.reply("Video processing failed: ${it.message ?: "unknown error"}")
                    }
            }
        }
    }

    private fun parseTime(value: String?): Long? {
        if (value == null || !value.matches(Regex("[0-9]{2,3}:[0-5][0-9]:[0-5][0-9]"))) return null
        val parts = value.split(":")
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        val total = hours * 3600L + minutes * 60L + seconds
        if (total > MAX_DURATION_SECONDS) return null
        return total * 1000L
    }

    private fun process(input: String, output: File, startMs: Long, endMs: Long, mute: Boolean) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(input)
            val selected = mutableListOf<Int>()
            val outputTracks = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || (!mute && mime.startsWith("audio/"))) selected += index
            }
            require(selected.any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                "No video track found."
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            selected.forEach { index -> outputTracks[index] = muxer.addTrack(extractor.getTrackFormat(index)) }
            muxer.start()
            muxerStarted = true
            selected.forEach { index -> extractor.selectTrack(index) }
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstSampleUs = extractor.sampleTime
            require(firstSampleUs >= 0L && firstSampleUs < endMs * 1000L) { "No media samples exist in this time range." }
            val endUs = endMs * 1000L
            val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            val info = MediaCodec.BufferInfo()
            val lastWritten = mutableMapOf<Int, Long>()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs < 0L || timeUs >= endUs) break
                if (track in outputTracks && timeUs >= firstSampleUs) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size > 0) {
                        require(size <= buffer.capacity()) { "A media sample exceeds the 8 MB processing buffer." }
                        val presentation = (timeUs - firstSampleUs).coerceAtLeast(0L)
                        val previous = lastWritten[track]
                        if (previous == null || presentation > previous) {
                            info.set(0, size, presentation, extractor.sampleFlags)
                            muxer.writeSampleData(outputTracks.getValue(track), buffer, info)
                            lastWritten[track] = presentation
                        }
                    }
                }
                if (!extractor.advance()) break
            }
            muxer.stop()
            muxerStarted = false
            require(output.length() > 1024L) { "The selected time range did not contain enough media data." }
            require(output.length() <= MAX_OUTPUT_BYTES) { "Processed output exceeds the 100 MB limit." }
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    companion object {
        private const val MAX_INPUT_BYTES = 100L * 1024L * 1024L
        private const val MAX_OUTPUT_BYTES = 100L * 1024L * 1024L
        private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
        private const val MAX_DURATION_SECONDS = 600
    }
}
