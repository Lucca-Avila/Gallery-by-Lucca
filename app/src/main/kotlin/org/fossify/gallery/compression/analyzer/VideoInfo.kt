package org.fossify.gallery.compression.analyzer

enum class ValueSource { DECLARED, MEASURED, ESTIMATED }

data class Bitrate(val bps: Long, val source: ValueSource)

data class AudioTrackInfo(
    val mime: String,
    val language: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val bitrate: Bitrate?,
    val decodable: Boolean,
)

data class SubtitleTrackInfo(val mime: String, val language: String?)

data class CodecSupport(
    val mime: String,
    val supported: Boolean,
    val hardware: Boolean,
    val codecName: String?,
    val profileSupported: Boolean = true,
)

data class VideoInfo(
    val containerMime: String?,
    val fileSize: Long,
    val durationMs: Long,
    val overallBitrate: Long?,
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val profile: Int?,
    val fps: Float?,
    val fpsSource: ValueSource?,
    val videoBitrate: Bitrate?,
    val declaredVideoBitrate: Long?,
    val colorTransfer: Int?,
    val colorStandard: Int?,
    val colorRange: Int?,
    val isDolbyVision: Boolean,
    val likely10Bit: Boolean,
    val extraVideoTracks: Int,
    val audio: List<AudioTrackInfo>,
    val subtitles: List<SubtitleTrackInfo>,
    val videoDecoder: CodecSupport?,
    val hevcEncoder: CodecSupport,
    val avcEncoder: CodecSupport,
    val scanned: Boolean,
    /** N da marca de "já comprimido" dentro do MP4 ([org.fossify.gallery.compression.mark.Mp4Mark]), se houver. */
    val mark: Int? = null,
) {
    val isRotatedSideways get() = rotation == 90 || rotation == 270
    val effectiveWidth get() = if (isRotatedSideways) height else width
    val effectiveHeight get() = if (isRotatedSideways) width else height
}
