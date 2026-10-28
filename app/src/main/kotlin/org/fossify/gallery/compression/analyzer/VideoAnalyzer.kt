package org.fossify.gallery.compression.analyzer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

object VideoAnalyzer {
    // Above this size the full sample scan is skipped and bitrates are estimated.
    const val MAX_SCAN_BYTES = 1_500_000_000L

    /** Blocking; call from a background thread. [isCancelled] is polled during the scan. */
    fun analyze(context: Context, uri: Uri, isCancelled: () -> Boolean = { false }): VideoInfo {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(context, uri)
            extractor.setDataSource(context, uri, null)

            val containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val fileSize = fileSize(context, uri)
            val overall = if (durationMs > 0 && fileSize > 0) fileSize * 8000L / durationMs else null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val mimes = formats.map { it.getString(MediaFormat.KEY_MIME).orEmpty() }
            val videoIdx = mimes.indices.filter { mimes[it].startsWith("video/") }
            val audioIdx = mimes.indices.filter { mimes[it].startsWith("audio/") }
            val subIdx = mimes.indices.filter { mimes[it].startsWith("text/") || mimes[it].startsWith("application/") }
            val mainVideo = videoIdx.firstOrNull()

            val shouldScan = fileSize in 1..MAX_SCAN_BYTES && durationMs > 0
            val bytes = LongArray(formats.size)
            val samples = LongArray(formats.size)
            var scanDone = false
            if (shouldScan) {
                val tracks = audioIdx + videoIdx
                tracks.forEach { extractor.selectTrack(it) }
                scanDone = true
                var n = 0
                while (true) {
                    if (++n % 1000 == 0 && isCancelled()) {
                        scanDone = false
                        break
                    }
                    val t = extractor.sampleTrackIndex
                    if (t < 0) break
                    val size = extractor.sampleSize
                    if (size > 0) bytes[t] += size
                    samples[t]++
                    extractor.advance()
                }
            }

            fun declared(i: Int): Long? =
                formats[i].takeIf { it.containsKey(MediaFormat.KEY_BIT_RATE) }?.getInteger(MediaFormat.KEY_BIT_RATE)?.toLong()?.takeIf { it > 0 }

            fun bitrateOf(i: Int, estimateFallback: Long?): Bitrate? = when {
                scanDone && bytes[i] > 0 -> Bitrate(bytes[i] * 8000L / durationMs, ValueSource.MEASURED)
                declared(i) != null -> Bitrate(declared(i)!!, ValueSource.DECLARED)
                estimateFallback != null && estimateFallback > 0 -> Bitrate(estimateFallback, ValueSource.ESTIMATED)
                else -> null
            }

            val audioDeclaredSum = audioIdx.sumOf { declared(it) ?: 0L }
            val audio = audioIdx.map { i ->
                val f = formats[i]
                AudioTrackInfo(
                    mime = mimes[i],
                    language = f.stringOrNull(MediaFormat.KEY_LANGUAGE),
                    channels = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    sampleRate = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    bitrate = bitrateOf(i, null),
                    decodable = DecoderCheck.audioDecoder(mimes[i]).supported,
                )
            }
            val subtitles = subIdx.map { SubtitleTrackInfo(mimes[it], formats[it].stringOrNull(MediaFormat.KEY_LANGUAGE)) }

            val vf = mainVideo?.let { formats[it] }
            val vMime = mainVideo?.let { mimes[it] }
            val width = vf?.intOrNull(MediaFormat.KEY_WIDTH) ?: 0
            val height = vf?.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0
            val profile = vf?.intOrNull(MediaFormat.KEY_PROFILE)
            val transfer = vf?.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            val standard = vf?.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
            val range = vf?.intOrNull(MediaFormat.KEY_COLOR_RANGE)
            val isDv = vMime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
            val isHdrTransfer = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
            val likely10Bit = isDv || isHdrTransfer || (vMime != null && profile != null && DecoderCheck.is10BitProfile(vMime, profile))

            var fps: Float? = null
            var fpsSource: ValueSource? = null
            if (mainVideo != null) {
                if (scanDone && samples[mainVideo] > 1) {
                    fps = samples[mainVideo] * 1000f / durationMs
                    fpsSource = ValueSource.MEASURED
                } else {
                    val declaredFps = vf?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                        ?.let { runCatching { it.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull() ?: it.getFloat(MediaFormat.KEY_FRAME_RATE) }
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    if (declaredFps != null && declaredFps > 0) {
                        fps = declaredFps
                        fpsSource = ValueSource.DECLARED
                    }
                }
            }

            val estimatedVideo = overall?.let { it - audioDeclaredSum }
            val videoBitrate = mainVideo?.let { bitrateOf(it, estimatedVideo) }

            return VideoInfo(
                containerMime = containerMime,
                fileSize = fileSize,
                durationMs = durationMs,
                overallBitrate = overall,
                videoMime = vMime,
                width = width,
                height = height,
                rotation = rotation,
                profile = profile,
                fps = fps,
                fpsSource = fpsSource,
                videoBitrate = videoBitrate,
                declaredVideoBitrate = mainVideo?.let { declared(it) },
                colorTransfer = transfer,
                colorStandard = standard,
                colorRange = range,
                isDolbyVision = isDv,
                likely10Bit = likely10Bit,
                extraVideoTracks = (videoIdx.size - 1).coerceAtLeast(0),
                audio = audio,
                subtitles = subtitles,
                videoDecoder = vMime?.let { DecoderCheck.videoDecoder(it, width, height, fps, profile) },
                hevcEncoder = DecoderCheck.encoder(MediaFormat.MIMETYPE_VIDEO_HEVC),
                avcEncoder = DecoderCheck.encoder(MediaFormat.MIMETYPE_VIDEO_AVC),
                scanned = scanDone,
            )
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    private fun fileSize(context: Context, uri: Uri): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun MediaFormat.intOrNull(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    private fun MediaFormat.stringOrNull(key: String) = if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
}
