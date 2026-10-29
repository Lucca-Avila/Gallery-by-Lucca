package org.fossify.gallery.compression.analyzer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log

object VideoAnalyzer {
    private const val TAG = "VideoAnalyzer"

    /** Acima disto o fps declarado não é confiável e é medido. */
    private const val MAX_TRUSTED_FPS = 61f

    /** O fps ausente ou duvidoso é medido só nos primeiros segundos de vídeo. */
    private const val FPS_PROBE_US = 30_000_000L

    /**
     * Blocking; call from a background thread. [isCancelled] is polled during the scans.
     * Padrão ([precise] = false): bitrate estimado (tamanho ÷ duração menos o áudio) e fps declarado, ou medido em 30 s de vídeo.
     * [precise] = true: varre todas as amostras e mede bitrates e fps (lento: segundos a dezenas de segundos).
     */
    fun analyze(context: Context, uri: Uri, isCancelled: () -> Boolean = { false }, precise: Boolean = false): VideoInfo {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        val t0 = SystemClock.elapsedRealtime()
        var decoderMs = 0L
        // Mede só a checagem de codecs (a primeira chamada paga a criação da MediaCodecList).
        fun <T> timedDecoder(block: () -> T): T {
            val start = SystemClock.elapsedRealtime()
            return block().also { decoderMs += SystemClock.elapsedRealtime() - start }
        }
        try {
            retriever.setDataSource(context, uri)
            val tRetriever = SystemClock.elapsedRealtime()
            extractor.setDataSource(context, uri, null)
            val tOpened = SystemClock.elapsedRealtime()

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

            val tFormat = SystemClock.elapsedRealtime()
            val shouldScan = precise && fileSize > 0 && durationMs > 0
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

            val tScan = SystemClock.elapsedRealtime()

            fun declared(i: Int): Long? =
                formats[i].takeIf { it.containsKey(MediaFormat.KEY_BIT_RATE) }?.getInteger(MediaFormat.KEY_BIT_RATE)?.toLong()?.takeIf { it > 0 }

            fun audioBitrate(i: Int): Bitrate = when {
                scanDone && bytes[i] > 0 -> Bitrate(bytes[i] * 8000L / durationMs, ValueSource.MEASURED)
                declared(i) != null -> Bitrate(declared(i)!!, ValueSource.DECLARED)
                else -> Bitrate(guessAudioBitrate(formats[i].intOrNull(MediaFormat.KEY_CHANNEL_COUNT)), ValueSource.ESTIMATED)
            }

            val audioBitrates = audioIdx.associateWith { audioBitrate(it) }
            val audio = audioIdx.map { i ->
                val f = formats[i]
                AudioTrackInfo(
                    mime = mimes[i],
                    language = f.stringOrNull(MediaFormat.KEY_LANGUAGE),
                    channels = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    sampleRate = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    bitrate = audioBitrates[i],
                    decodable = timedDecoder { DecoderCheck.audioDecoder(mimes[i]) }.supported,
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
                    if (declaredFps != null && declaredFps > 0 && declaredFps <= MAX_TRUSTED_FPS) {
                        fps = declaredFps
                        fpsSource = ValueSource.DECLARED
                    } else {
                        val probed = measureFps(extractor, mainVideo, isCancelled)
                        if (probed != null) {
                            fps = probed
                            fpsSource = ValueSource.MEASURED
                        } else if (declaredFps != null && declaredFps > 0) {
                            fps = declaredFps
                            fpsSource = ValueSource.DECLARED
                        }
                    }
                }
            }

            val tFps = SystemClock.elapsedRealtime()
            val audioDecoderMs = decoderMs
            val audioSum = audioBitrates.values.sumOf { it.bps }
            val videoBitrate = mainVideo?.let { v ->
                when {
                    scanDone && bytes[v] > 0 -> Bitrate(bytes[v] * 8000L / durationMs, ValueSource.MEASURED)
                    overall != null && overall - audioSum > 0 -> Bitrate(overall - audioSum, ValueSource.ESTIMATED)
                    declared(v) != null -> Bitrate(declared(v)!!, ValueSource.DECLARED)
                    else -> null
                }
            }

            val decoder = vMime?.let { timedDecoder { DecoderCheck.videoDecoder(it, width, height, fps, profile) } }
            val hevc = timedDecoder { DecoderCheck.encoder(MediaFormat.MIMETYPE_VIDEO_HEVC) }
            val avc = timedDecoder { DecoderCheck.encoder(MediaFormat.MIMETYPE_VIDEO_AVC) }
            val total = SystemClock.elapsedRealtime() - t0
            Log.i(
                TAG,
                "tempos de ${uri.lastPathSegment}: ${fileSize / 1_000_000} MB, ${durationMs / 1000} s, ${samples.sum()} amostras, varredura=$scanDone | " +
                    "abrir=${tOpened - t0} ms (retriever ${tRetriever - t0}, extractor ${tOpened - tRetriever}) " +
                    "formato=${tFormat - tOpened} ms varredura=${tScan - tFormat} ms fps30s=${tFps - tScan - audioDecoderMs} ms decodificador=$decoderMs ms total=$total ms | " +
                    "modo=${if (precise) "preciso" else "estimativa"} bitrate=${videoBitrate?.bps}(${videoBitrate?.source}) fps=$fps($fpsSource)"
            )

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
                videoDecoder = decoder,
                hevcEncoder = hevc,
                avcEncoder = avc,
                scanned = scanDone,
            )
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    /** Sem bitrate declarado: um palpite por número de canais (AAC costuma ter ~64 kbps por canal). */
    private fun guessAudioBitrate(channels: Int?): Long = ((channels ?: 2) * 64_000L).coerceIn(96_000L, 448_000L)

    /** fps pelos primeiros [FPS_PROBE_US] da trilha de vídeo: amostras ÷ intervalo entre a primeira e a última. */
    private fun measureFps(extractor: MediaExtractor, videoTrack: Int, isCancelled: () -> Boolean): Float? {
        extractor.selectTrack(videoTrack)
        var count = 0
        var first = -1L
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        while (extractor.sampleTrackIndex >= 0) {
            val time = extractor.sampleTime
            if (first < 0) first = time
            if (time - first > FPS_PROBE_US) break
            count++
            if (time < min) min = time
            if (time > max) max = time
            if (count % 1000 == 0 && isCancelled()) return null
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(videoTrack)
        return if (count > 1 && max > min) (count - 1) * 1_000_000f / (max - min) else null
    }

    private fun fileSize(context: Context, uri: Uri): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun MediaFormat.intOrNull(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    private fun MediaFormat.stringOrNull(key: String) = if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
}
